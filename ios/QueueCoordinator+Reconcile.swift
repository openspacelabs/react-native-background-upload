import Foundation

// Relaunch: matching the daemon's surviving tasks to the stored entries, and
// the one-time v9 import. Runs at `shared` init: an app launch, a JS reload,
// or the AppDelegate background wake.
extension QueueCoordinator {
  /// How long a running entry with no live task waits for a completion the
  /// daemon may still replay before it re-issues.
  static let graceMs = 10_000

  /// `completion` runs on the queue once every entry has its tasks again:
  /// every grace wait has ended and every queued part slice has run. The
  /// caller holds the background completion handlers until then, so the
  /// system cannot suspend the app before the refill enqueues new tasks, or
  /// in the middle of a grace wait.
  func reconcileAll(completion: @escaping () -> Void) {
    queue.async {
      self.transport.allTasks { tasks in
        self.queue.async {
          self.reconcile(tasks)
          self.whenIdleLocked(completion)
        }
      }
    }
  }

  /// Runs `block` on the queue once no grace wait is open and no part slice
  /// is queued. urlSessionDidFinishEvents holds the session's completion
  /// handler until then: the completions it just delivered may have queued
  /// part slices.
  func whenIdle(_ block: @escaping () -> Void) {
    queue.async { self.whenIdleLocked(block) }
  }

  /// One open grace wait: the TaskMap keys whose completion may still
  /// replay, and what to do when the wait ends.
  struct Grace {
    let token: UUID
    var keys: Set<String>
    let resolve: () -> Void
  }

  /// Runs `block` now when no grace wait is open and no part slice is
  /// queued. Otherwise it runs when the last of them ends.
  func whenIdleLocked(_ block: @escaping () -> Void) {
    if isIdle {
      block()
    } else {
      afterGrace.append(block)
    }
  }

  /// No grace wait is open, and no part slice is queued or the background
  /// wake is short on time. A grace wait is never cut short.
  private var isIdle: Bool {
    graces.isEmpty && (chunked.queuedSlices.isEmpty || wakeIsShort)
  }

  private var wakeIsShort: Bool {
    guard !activity.isActive, let remaining = wakeTimeRemaining?() else { return false }
    return remaining < Self.wakeReserveSeconds
  }

  /// Runs the held blocks when nothing holds them any more. Called when a
  /// grace wait ends and before and after each part slice.
  func releaseIfIdle() {
    guard !afterGrace.isEmpty, isIdle else { return }
    let blocks = afterGrace
    afterGrace = []
    blocks.forEach { $0() }
  }

  /// Opens a grace wait for `keys`. It ends when the completion of every key
  /// was handled, or after `graceMs`, whichever comes first. Then `resolve`
  /// runs. Does nothing when a wait with this name is open.
  func openGrace(_ name: String, keys: Set<String>, resolve: @escaping () -> Void) {
    guard graces[name] == nil else { return }
    let token = UUID()
    graces[name] = Grace(token: token, keys: keys, resolve: resolve)
    schedule(Self.graceMs) { [weak self] in self?.endGrace(name, token: token) }
  }

  /// A completion was handled for `key`. A wait with no key left ends now,
  /// so the background completion handler does not wait out the timer.
  func replayHandled(_ key: String) {
    for (name, grace) in graces where grace.keys.contains(key) {
      graces[name]?.keys.remove(key)
      if graces[name]?.keys.isEmpty == true { endGrace(name, token: grace.token) }
    }
  }

  /// Closes one wait, runs its resolve, and releases the held blocks when no
  /// wait is open. The token stops the timer of a wait that already ended
  /// from closing a newer wait with the same name.
  private func endGrace(_ name: String, token: UUID) {
    guard let grace = graces[name], grace.token == token else { return }
    graces[name] = nil
    grace.resolve()
    releaseIfIdle()
  }

  func reconcile(_ tasks: [UploadTask]) {
    ready = true
    repairLostSettles()
    sweepOrphanedOutcomes()
    var simpleLive: Set<String> = []
    var partTasks: [String: [ChunkedCoordinator.LiveTask]] = [:]
    let liveKeys = Set(tasks.filter(\.isLive).map(\.key))

    for task in tasks where task.isLive {
      var meta = taskMap.meta(forKey: task.key)
      let owner = TaskOwner.resolve(description: task.taskDescription, meta: meta)
      let entry = owner.flatMap { index.entry($0.id) }
      // A late-result key whose task is live and is the entry's current
      // attempt. The wait gave up on this attempt, but the re-issue after it
      // was never saved (a failed write-ahead), so the entry is still on this
      // attempt. Adopt the task: it may have sent the body already.
      if meta?.purpose == .lateResult, case .request(let id, let generation, let attempt, let incarnation)? = owner,
         let e = entry, isCurrentSimpleTask(e, generation: generation, attempt: attempt, incarnation: incarnation) {
        taskMap.setPurpose(.attempt, forKey: task.key, id: id)
        meta?.purpose = .attempt
      }
      // A task the library replaced or dropped, still live because the
      // process died before the daemon applied the cancel. Never adopt it:
      // its replacement, or the re-issue below, drives the entry. A kept
      // late-result key on a live task becomes superseded: the task now owes
      // a cancel callback, so its key must stay until that callback, and a
      // settle removes only late-result keys.
      if let purpose = meta?.purpose, purpose.isOutdated {
        if purpose == .lateResult { taskMap.setPurpose(.superseded, forKey: task.key, id: meta?.id ?? "") }
        task.cancel()
        continue
      }
      let runnable = entry.map { !$0.legacy && ($0.state == .queued || $0.state == .running) } ?? false
      switch owner {
      case .part(let id, let part, let incarnation)? where runnable && entry?.isChunked == true:
        partTasks[id, default: []].append(.init(task: task, part: part, incarnation: incarnation))
      case .request(let id, let generation, let attempt, let incarnation)?
        where entry.map { isCurrentSimpleTask($0, generation: generation, attempt: attempt, incarnation: incarnation) }
          == true && !simpleLive.contains(id):
        simpleLive.insert(id)
        liveTasks[task.key] = (id, task)
      default:
        // No v10 owner (a v9 task), an older generation or attempt, an
        // earlier use of the id, a duplicate, or an entry that must have no
        // task. Drop its callback.
        taskMap.setPurpose(.superseded, forKey: task.key, id: owner?.id ?? "")
        task.cancel()
      }
    }
    // A key whose id has no entry belongs to nothing, and a simple key of an
    // earlier use of the id belongs to nothing now. A live task keeps its key
    // until its cancel callback, which reads the purpose. A task listed as
    // finished may still report: its description names its use, so the
    // report is dropped without the key.
    taskMap.removeAll { key, meta in
      guard !liveKeys.contains(key) else { return false }
      guard let e = index.entry(meta.id) else { return true }
      return meta.partIndex == nil && meta.generation != nil && !e.ownsSimpleTask(incarnation: meta.incarnation)
    }

    for e in index.entries() where e.isLive && !e.legacy {
      armExpiry(e)
      guard e.state == .queued || e.state == .running else { continue }
      if e.isChunked {
        chunked.reconcile(e.id, tasks: partTasks[e.id] ?? [])
      } else if !simpleLive.contains(e.id) {
        withoutTask(e)
      }
    }
    // A crash between the settings save and the entry saves of a pause or
    // resume leaves rows that do not match the gates. Match them now.
    applyPauseGates()
    // A crash between a TaskMap write and resume() leaves a task suspended.
    // Resume every adopted task still held, after the settles and pauses
    // above, so a task they stopped sends nothing. resume() on a running
    // task does nothing.
    for task in tasks where task.isLive && liveTasks[task.key] != nil {
      task.resume()
    }
    // A launch into the foreground: replace the adopted tasks that were
    // created in the background, and any this reconcile created before the
    // app became active.
    scheduleReplace()
  }

  /// A queued or running entry with no live task. Either the daemon finished
  /// the task while we were dead and will replay its completion now, or the
  /// task never reached the daemon (a crash between the save and resume), or
  /// it was lost. The TaskMap tells them apart: its key goes when a
  /// completion is handled.
  private func withoutTask(_ e: QueueEntry) {
    let pending = taskMap.keys(where: {
      $0.id == e.id && e.ownsSimpleTask(incarnation: $0.incarnation)
        && $0.generation == e.generation && $0.attempt == e.attempts
    })
    guard !pending.isEmpty else {
      // No task was ever made for a waiting attempt: it never ran, so it
      // keeps its ordinal and request id.
      reissue(e, advanceAttempt: false)
      return
    }
    openGrace(e.id, keys: Set(pending)) { [weak self] in
      guard let self, let current = self.index.entry(e.id), current.state == e.state,
            current.generation == e.generation, current.attempts == e.attempts,
            !self.liveTasks.values.contains(where: { $0.id == e.id }) else { return }
      // No replay came in time. The task may be lost, or its result may
      // still arrive later. Keep its keys as late-result keys instead of
      // deleting them. A late accepted result then completes the entry and
      // cancels the new attempt's task, and a late failure or cancel is
      // dropped (taskCompletedLocked). The wait covers only the current
      // attempt, so once the re-issue below moves the entry to a new
      // attempt, a later launch does not wait for these keys again. A key
      // already marked superseded or pause keeps its purpose: its task may
      // still owe a cancel callback.
      let stale = self.taskMap.keys(where: {
        $0.id == e.id && $0.generation == e.generation && $0.attempt == e.attempts
          && ($0.purpose ?? .attempt) == .attempt
      })
      self.taskMap.setPurpose(.lateResult, for: stale.map { (key: $0, id: e.id) })
      // It may have run, so the next attempt gets a new ordinal and request id.
      self.reissue(current, advanceAttempt: true)
    }
  }

  /// Whether a simple task with this identity is the current attempt of `e`,
  /// a simple entry that may have a task.
  private func isCurrentSimpleTask(_ e: QueueEntry, generation: Int, attempt: Int, incarnation: String?) -> Bool {
    !e.legacy && !e.isChunked && (e.state == .queued || e.state == .running)
      && e.ownsSimpleTask(incarnation: incarnation) && e.generation == generation && e.attempts == attempt
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
