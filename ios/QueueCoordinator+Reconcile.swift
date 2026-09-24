import Foundation

// Relaunch: matching the daemon's surviving tasks to the stored entries, and
// the one-time v9 import. Runs at `shared` init: an app launch, a JS reload,
// or the AppDelegate background wake.
extension QueueCoordinator {
  /// How long a running entry with no live task waits for a completion the
  /// daemon may still replay before it re-issues.
  static let graceMs = 10_000

  /// `completion` runs on the queue once every entry has its tasks again.
  /// The caller holds the background completion handlers until then, so the
  /// system cannot suspend the app before the refill enqueues new tasks.
  func reconcileAll(completion: @escaping () -> Void) {
    queue.async {
      self.transport.allTasks { tasks in
        self.queue.async {
          self.reconcile(tasks)
          completion()
        }
      }
    }
  }

  func reconcile(_ tasks: [UploadTask]) {
    ready = true
    sweepOrphanedOutcomes()
    var simpleLive: Set<String> = []
    var partTasks: [String: [ChunkedCoordinator.LiveTask]] = [:]
    let liveKeys = Set(tasks.filter(\.isLive).map(\.key))

    for task in tasks where task.isLive {
      let owner = TaskOwner.resolve(description: task.taskDescription,
                                    meta: taskMap.meta(forKey: task.key))
      let entry = owner.flatMap { index.entry($0.id) }
      let runnable = entry.map { !$0.legacy && ($0.state == .queued || $0.state == .running) } ?? false
      switch owner {
      case .part(let id, let part, let incarnation)? where runnable && entry?.isChunked == true:
        partTasks[id, default: []].append(.init(task: task, part: part, incarnation: incarnation))
      case .request(let id, let generation, let attempt)?
        where runnable && entry?.isChunked == false && entry?.generation == generation
          && entry?.attempts == attempt && !simpleLive.contains(id):
        simpleLive.insert(id)
        liveTasks[task.key] = (id, task)
      default:
        // No v10 owner (a v9 task), an older generation or attempt, a
        // duplicate, or an entry that must have no task. Drop its callback.
        taskMap.setPurpose(.superseded, forKey: task.key, id: owner?.id ?? "")
        task.cancel()
      }
    }
    // A key whose id has no entry belongs to nothing. A live task keeps its
    // key until its cancel callback, which reads the purpose.
    taskMap.removeAll { key, meta in !liveKeys.contains(key) && index.entry(meta.id) == nil }

    for e in index.entries() where e.isLive && !e.legacy {
      armExpiry(e)
      guard e.state == .queued || e.state == .running else { continue }
      if e.isChunked {
        chunked.reconcile(e.id, tasks: partTasks[e.id] ?? [])
      } else if !simpleLive.contains(e.id) {
        withoutTask(e)
      }
    }
  }

  /// A queued or running entry with no live task. Either the daemon finished
  /// the task while we were dead and will replay its completion now, or the
  /// task never reached the daemon (a crash between the save and resume), or
  /// it was lost. The TaskMap tells them apart: its key goes when a
  /// completion is handled.
  private func withoutTask(_ e: QueueEntry) {
    let pending = !taskMap.keys(where: {
      $0.id == e.id && $0.generation == e.generation && $0.attempt == e.attempts
    }).isEmpty
    guard pending else {
      // No task was ever made for a waiting attempt: it never ran, so it
      // keeps its ordinal and request id.
      reissue(e, advanceAttempt: false)
      return
    }
    guard !graceChecks.contains(e.id) else { return }
    graceChecks.insert(e.id)
    schedule(Self.graceMs) { [weak self] in
      guard let self else { return }
      self.graceChecks.remove(e.id)
      guard let current = self.index.entry(e.id), current.state == e.state,
            current.generation == e.generation, current.attempts == e.attempts,
            !self.liveTasks.values.contains(where: { $0.id == e.id }) else { return }
      // No replay came: the task is lost. Its key would send every later
      // launch through this wait again.
      self.taskMap.removeAll { _, m in
        m.id == e.id && m.generation == e.generation && m.attempt == e.attempts
      }
      // It may have run, so the next attempt gets a new ordinal: a late
      // replay of this one is then dropped as stale.
      self.reissue(current, advanceAttempt: true)
    }
  }

  /// Issues again, keeping what is left of a wait.
  private func reissue(_ e: QueueEntry, advanceAttempt: Bool) {
    var n = e
    n.state = .queued
    index.upsert(n)
    let remaining = e.nextAttemptAt.map { Int($0 - now()) }.flatMap { $0 > 0 ? $0 : nil }
    issue(n.id, delayMs: remaining, advanceAttempt: advanceAttempt)
  }

  /// First v10 launch, from init: disk only, before any session exists.
  /// Each v9 journal entry becomes a legacy row; nothing is emitted. v9
  /// manifests with no journal entry stay dormant. v9 task metadata is
  /// dropped; reconcile cancels the v9 tasks. The marker is written last, so
  /// a crash mid-import runs it again.
  func importLegacyIfNeeded() {
    guard !store.isImported() else { return }
    let events = journal.legacyEvents()
    let manifests = store.allV9Manifests()
    for e in LegacyImport.plan(events: events, manifests: manifests) {
      if let existing = index.entry(e.id), !existing.legacy { continue }
      do {
        try store.save(e)
      } catch {
        NSLog("[RNFileUploader] v9 import: cannot save \(e.id): \(error.localizedDescription)")
        return
      }
      index.upsert(e)
    }
    for event in events { journal.removeLegacy(event.eventId) }
    taskMap.removeAll { _, meta in meta.generation == nil }
    do {
      try store.markImported()
    } catch {
      NSLog("[RNFileUploader] v9 import: cannot write the marker: \(error.localizedDescription)")
    }
  }
}
