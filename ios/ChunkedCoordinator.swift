import Foundation

/// Runs chunked entries against the background sessions: keeps a task
/// enqueued with the daemon for every pending part, evaluates each part's
/// outcome, and after a relaunch rebuilds the part tasks from the ones the
/// daemon still holds.
///
/// Every call runs on the QueueCoordinator's serial queue, which enforces the
/// binding invariant: never two tasks for one part index. `inFlight` maps
/// each enqueued part to the key of the one task that owns it. Only
/// enqueueParts creates part tasks.
///
/// refill builds the part tasks one slice at a time (about 32 MiB of parts),
/// each slice in its own block on the queue. Another call (the next
/// enqueue, a delegate callback) waits for one slice at most, not for the
/// whole plan.
///
/// The entry (QueueEntry) is the durable truth: parts, accepted flags,
/// incarnation. Terminals go through QueueCoordinator.settle.
final class ChunkedCoordinator {
  struct LiveTask {
    let task: UploadTask
    let part: Int
    let incarnation: String?
  }

  /// A part task to replace: see replace(_:_:).
  struct Replaced {
    let part: Int
    let incarnation: String?
    let key: String
  }

  private unowned let q: QueueCoordinator
  // id -> part index -> the task key that owns it.
  private var inFlight: [String: [Int: String]] = [:]
  // id -> part index -> bytes sent by its live task. Feeds the byte-weighted
  // progress.
  private var partSent: [String: [Int: Int64]] = [:]
  // id -> part index -> when its delayed task begins (epoch ms), until it
  // begins. When every part in flight waits, the row shows the earliest
  // as nextAttemptAt.
  private var partBeginAt: [String: [Int: Double]] = [:]

  /// About how many part bytes one block on the queue copies.
  static let defaultSliceBytes: Int64 = 32 << 20
  /// A test hook: a small budget builds a plan in many slices.
  var sliceBytes = ChunkedCoordinator.defaultSliceBytes
  /// Ids with a slice block queued. At most one block per id: the block
  /// takes its id out, then refills, which queues the next block when parts
  /// are left. While it is not empty, the coordinator holds the background
  /// completion handler (QueueCoordinator.whenIdle).
  private(set) var queuedSlices: Set<String> = []
  /// A test hook: where a slice block goes. nil puts it on the queue.
  var dispatchSlice: ((@escaping () -> Void) -> Void)?
  /// id -> background-created part tasks still to replace, in part order.
  /// replace() takes one slice of them per block.
  private var replacing: [String: [Replaced]] = [:]

  init(_ coordinator: QueueCoordinator) {
    q = coordinator
  }

  // MARK: - Called by QueueCoordinator

  /// queued -> running, then give every pending part a task.
  func start(_ id: String) {
    guard var e = q.index.entry(id), e.isChunked, e.state == .queued || e.state == .running,
          !q.settings.isPaused(e.key) else { return }
    if e.state == .queued {
      e.state = .running
      e.nextAttemptAt = nil
      q.commit(e)
    }
    refill(id)
  }

  /// Forgets the part tasks. The caller cancels them.
  func stop(_ id: String) {
    replacing[id] = nil
    inFlight[id] = nil
    partSent[id] = nil
    partBeginAt[id] = nil
  }

  /// Relaunch: adopt the daemon's live part tasks of the current
  /// incarnation, one per part index. Cancel the rest (a replaced plan, an
  /// accepted part, a duplicate: concurrent PUTs of one partNum are unsafe on
  /// the server). Then refill.
  func reconcile(_ id: String, tasks: [LiveTask]) {
    guard let e = q.index.entry(id) else { return }
    var live: [Int: String] = [:]
    var dropped: [UploadTask] = []
    for t in tasks {
      let keep = t.incarnation == e.incarnation && e.parts.indices.contains(t.part)
        && !e.parts[t.part].accepted && live[t.part] == nil
      if keep {
        live[t.part] = t.task.key
        q.liveTasks[t.task.key] = (id, t.task)
        // A crash between a slice's TaskMap write and its resumes leaves
        // the task suspended. resume() on a running task does nothing.
        t.task.resume()
        if let begin = t.task.beginAt.map({ $0.timeIntervalSince1970 * 1000 }), begin > q.now() {
          partBeginAt[id, default: [:]][t.part] = begin
        }
      } else {
        dropped.append(t.task)
      }
    }
    q.taskMap.setPurpose(.superseded, for: dropped.map { (key: $0.key, id: id) })
    dropped.forEach { $0.cancel() }
    // A pending part with a TaskMap key but no live task finished while the
    // app was dead, and its completion may replay now. Hold its slot for the
    // grace, so no second PUT of that part starts, and its part file stays.
    var held: [Int: String] = [:]
    for key in q.taskMap.keys(where: { $0.id == id && $0.incarnation == e.incarnation
                                       && ($0.purpose ?? .attempt) == .attempt }) {
      guard let i = q.taskMap.meta(forKey: key)?.partIndex, e.parts.indices.contains(i),
            !e.parts[i].accepted, live[i] == nil, held[i] == nil else { continue }
      held[i] = key
    }
    inFlight[id] = live.merging(held) { current, _ in current }
    if !held.isEmpty { holdForReplay(id, held) }
    // Part files of accepted parts with no live task are orphans.
    for i in e.parts.indices where e.parts[i].accepted && live[i] == nil {
      q.store.removePartFile(id, i)
    }
    start(id)
    updateWait(id)
  }

  /// When the grace ends (every held replay came, or the timer fired), a
  /// held slot whose replay never came is released: its key is pruned and
  /// the part is sent again.
  private func holdForReplay(_ id: String, _ held: [Int: String]) {
    q.openGrace("part:" + id, keys: Set(held.values)) { [weak self] in
      guard let self else { return }
      var released = false
      for (i, key) in held where self.inFlight[id]?[i] == key {
        self.inFlight[id]?[i] = nil
        self.q.taskMap.removeKey(key)
        released = true
      }
      if released { self.refill(id) }
    }
  }

  // MARK: - Delegate hooks

  func partCompleted(id: String, part: Int, incarnation: String?, key: String, meta: TaskMap.Meta?,
                     completion c: TaskCompletion) {
    let owned = inFlight[id]?[part] == key
    if owned {
      inFlight[id]?[part] = nil
      partSent[id]?[part] = nil
      partBeginAt[id]?[part] = nil
    }
    // Every path below may change the parts in flight; a settle or park
    // makes this a no-op.
    defer { updateWait(id) }
    guard var e = q.index.entry(id), e.isChunked, !e.legacy else {
      if owned { q.store.removePartFile(id, part) }
      return
    }
    // A late callback from a replaced plan: its response is about ranges and
    // urls this entry no longer describes. Write nothing from it.
    guard incarnation == e.incarnation, e.parts.indices.contains(part) else {
      if owned { refill(id) }
      return
    }
    let cancelled = RetryClassifier.isCancellation(c.error)
    if cancelled, meta?.purpose == .pause || meta?.purpose == .superseded { return }
    let accepted = c.error == nil
      && c.statusCode.map { UploadOutcome.isAccepted($0, body: c.body, accept: e.accept) } == true
    // A system cancel is not an attempt: no event. It retries below.
    if !cancelled {
      q.emitAttempt(e, requestId: meta?.requestId, attempt: meta?.attempt ?? e.attempts, completion: c,
                    partIndex: part, accepted: accepted)
    }
    e.lastRequestId = meta?.requestId ?? e.lastRequestId
    e.lastUrl = e.parts[part].url
    e.lastPartIndex = part

    // Accept first, whatever the entry's state: the server holds these bytes
    // now. Losing the flag would re-send a part the server already has.
    if accepted {
      // A replay that lands after its slot was given to a new task: that
      // task is a duplicate PUT, and it reads the part file. Stop it first.
      if !owned, let other = inFlight[id]?[part] {
        q.cancelTask(other, purpose: .superseded)
        inFlight[id]?[part] = nil
        partSent[id]?[part] = nil
        partBeginAt[id]?[part] = nil
      }
      e = e.withPartAccepted(part)
      q.commit(e, emit: false)
      q.store.removePartFile(id, part)
      guard e.state == .running else { return }
      if e.allAccepted {
        q.settle(id, .completed(RawResponseRecord(bodyTruncated: false)))
      } else {
        emitProgress(e)
        refill(id)
      }
      return
    }

    // A failure of a task this process does not own is a relaunch replay or
    // a superseded duplicate. The live task, or the reconcile refill, drives
    // the part. The part file stays for reuse.
    guard owned, e.state == .running else { return }
    q.index.upsert(e)
    if e.parts[part].accepted {
      refill(id)
      return
    }
    if cancelled {
      retryPart(e, part)
      return
    }
    let blobExists = e.bodyPath.map { FileIO.exists(q.store.fileURL(id, $0)) } ?? false
    let verdict = RetryClassifier.classify(RetryClassifier.Input(
      statusCode: c.statusCode, body: c.body, error: c.error, accept: e.accept, policy: q.policy(e),
      fileExists: blobExists, now: q.now(), expiresAt: e.expiresAt))
    switch verdict {
    case .accepted:
      break // handled above
    case .transient:
      retryPart(e, part)
    case .auth:
      if let g = meta?.headerGeneration, g < q.settings.headerGeneration {
        _ = enqueueParts(id, [(index: part, delayMs: nil)])
      } else {
        // Park the whole entry: the other parts would get the same answer.
        q.park(e)
      }
    case .terminalHttp:
      q.settle(id, .error(OutcomeErrorRecord(
        errorKind: "http", message: "HTTP \(c.statusCode ?? 0) on part \(part)",
        response: q.response(c), partIndex: part)))
    case .fileMissing:
      q.settle(id, .fileError("the chunked source blob is missing", partIndex: part))
    case .expired:
      q.settle(id, .expired)
    }
  }

  /// A delayed part retry is about to start. Rebuild its request from the
  /// entry's current headers, or cancel it when the entry moved on.
  func partWillBegin(id: String, part: Int, incarnation: String?, key: String,
                     meta: TaskMap.Meta?) -> URLRequest? {
    // Before the first reconcile nothing is owned yet; accept the task if the
    // entry wants it. Reconcile then adopts or cancels it.
    let ownedOrUnknown = !q.ready || inFlight[id]?[part] == key
    guard let e = q.index.entry(id), e.isChunked, incarnation == e.incarnation,
          e.parts.indices.contains(part), !e.parts[part].accepted, e.state == .running,
          !q.settings.isPaused(e.key), ownedOrUnknown, let url = URL(string: e.parts[part].url) else {
      q.taskMap.setPurpose(.superseded, forKey: key, id: id)
      q.liveTasks[key] = nil
      if inFlight[id]?[part] == key {
        inFlight[id]?[part] = nil
        partBeginAt[id]?[part] = nil
        updateWait(id)
      }
      return nil
    }
    partBeginAt[id]?[part] = nil
    updateWait(id)
    q.taskMap.setHeaderGeneration(q.settings.headerGeneration, forKey: key)
    return q.buildRequest(e, url: url, requestId: meta?.requestId ?? UUID().uuidString,
                          partHeaders: e.parts[part].headers)
  }

  /// Only the task that owns the part reports: a replaced one's queued
  /// progress is dropped. Before reconcile nothing is owned yet.
  func partProgress(id: String, part: Int, incarnation: String?, key: String, sent: Int64) {
    guard !q.ready || inFlight[id]?[part] == key,
          let e = q.index.entry(id), e.incarnation == incarnation, e.state == .running else { return }
    partSent[id, default: [:]][part] = sent
    // A delayed part that began while the app was dead reports progress
    // before any willBegin.
    if partBeginAt[id]?.removeValue(forKey: part) != nil { updateWait(id) }
    emitProgress(q.index.entry(id) ?? e)
  }

  // MARK: - Part tasks

  /// Gives each part a new task in place of its `key`, which has not sent a
  /// body byte (QueueCoordinator.replaceBackgroundTasks). A new task keeps
  /// what is left of the old one's backoff. The old ones are cancelled as
  /// superseded, so their callbacks produce no outcome. If one landed before
  /// the cancel took effect, partCompleted accepts the part and stops its
  /// new task.
  ///
  /// It works one slice at a time, like refill. Each old task is cancelled
  /// in the slice that replaces it, so a part keeps its old task until its
  /// new one exists.
  func replace(_ id: String, _ replaced: [Replaced]) {
    var queued = replacing[id] ?? []
    let keys = Set(queued.map(\.key))
    queued += replaced.filter { !keys.contains($0.key) }
    replacing[id] = queued.sorted { $0.part < $1.part }
    replaceSlice(id)
    updateWait(id)
  }

  /// Replaces the first slice of `replacing[id]` that is still stale, then
  /// queues a block for the rest (and for any part with no task).
  private func replaceSlice(_ id: String) {
    guard let e = runnable(id) else {
      replacing[id] = nil
      return
    }
    let stale = (replacing[id] ?? []).filter {
      $0.incarnation == e.incarnation && e.parts.indices.contains($0.part) && !e.parts[$0.part].accepted
        && inFlight[id]?[$0.part] == $0.key
    }
    let slice = ChunkedEngine.slice(stale.map(\.part), budget: sliceBytes) { e.parts[$0].size }
    let now = Array(stale.prefix(slice.count))
    replacing[id] = stale.count > now.count ? Array(stale.dropFirst(now.count)) : nil
    guard !now.isEmpty else { return }
    // A new task keeps what is left of the old one's backoff.
    let requests: [(index: Int, delayMs: Int?)] = now.map { (index: $0.part, delayMs: remainingMs(id, $0.part)) }
    q.cancelTasks(keys: now.map(\.key), purpose: .superseded)
    for r in now {
      inFlight[id]?[r.part] = nil
      partSent[id]?[r.part] = nil
    }
    let built = Signpost.interval("part replace slice", "\(id) parts \(now.count) of \(stale.count)") {
      enqueueParts(id, requests)
    }
    // On false the entry settled, or a refill after a backoff follows and
    // gives the parts of this slice their tasks. The old tasks of the rest
    // keep running; the next activation replaces them.
    guard built else {
      replacing[id] = nil
      return
    }
    refillNextSlice(id)
  }

  /// The entry when its parts may get tasks now. Otherwise nil, and an
  /// entry that is done, expired or has a short blob is settled.
  private func runnable(_ id: String) -> QueueEntry? {
    guard q.ready, let e = q.index.entry(id), e.isChunked, e.state == .running,
          !q.settings.isPaused(e.key) else { return nil }
    if e.allAccepted {
      q.settle(id, .completed(RawResponseRecord(bodyTruncated: false)))
      return nil
    }
    if q.now() >= e.expiresAt {
      q.settle(id, .expired)
      return nil
    }
    // A blob shorter than a part can never finish: report it now.
    let blobSize = e.bodyPath.flatMap { FileIO.size(q.store.fileURL(id, $0)) } ?? 0
    if let short = e.parts.indices.first(where: { e.parts[$0].end > blobSize }) {
      q.settle(id, .fileError(
        "source blob is \(blobSize) bytes; part \(short) needs "
          + "[\(e.parts[short].start), \(e.parts[short].end))", partIndex: short))
      return nil
    }
    return e
  }

  /// Gives every pending part with no task one, a slice at a time. Called at
  /// start, after every part completion, at the end of every reconcile, and
  /// from each slice block. After start, it only replaces a task that ended
  /// without its part being accepted (a lost task, or one that could not be
  /// built).
  ///
  /// Each call checks the entry again and computes "pending minus in
  /// flight" again. So a part never gets a second task, and a cancel, pause,
  /// replace, settle or expiry between two slices stops the build.
  func refill(_ id: String) {
    guard let e = runnable(id) else { return }
    let flight = Set((inFlight[id] ?? [:]).keys)
    let indexes = ChunkedEngine.indexesToEnqueue(pending: e.pendingIndexes(), inFlight: flight)
    guard !indexes.isEmpty else { return }
    let slice = ChunkedEngine.slice(indexes, budget: sliceBytes) { e.parts[$0].size }
    let requests: [(index: Int, delayMs: Int?)] = slice.map { (index: $0, delayMs: remainingMs(id, $0)) }
    let built = Signpost.interval("part slice", "\(id) parts \(slice.count) of \(indexes.count)") {
      enqueueParts(id, requests)
    }
    // On false the entry settled, or a refill after a backoff follows.
    if built && slice.count < indexes.count { refillNextSlice(id) }
  }

  /// Queues one block for `id`, unless one is queued already. The block
  /// replaces the next slice of background-created tasks when any are
  /// left, else it refills. The blocks of other calls already queued run
  /// first.
  private func refillNextSlice(_ id: String) {
    guard queuedSlices.insert(id).inserted else { return }
    let block = { [weak self] in
      guard let self else { return }
      // Before the id leaves queuedSlices, this releases only when a
      // background wake is short on time: then the completion handler goes
      // back to the system now. Every entry has at least its first slice's
      // tasks, so a later wake comes.
      self.q.releaseIfIdle()
      self.queuedSlices.remove(id)
      if self.replacing[id] != nil {
        self.replaceSlice(id)
      } else {
        self.refill(id)
      }
      // A later slice may start parts while every earlier one waits.
      self.updateWait(id)
      self.q.releaseIfIdle()
    }
    if let dispatchSlice { dispatchSlice(block) } else { q.queue.async(execute: block) }
  }

  /// What is left of a part's begin date, or nil once it has passed.
  private func remainingMs(_ id: String, _ part: Int) -> Int? {
    partBeginAt[id]?[part].map { Int($0 - q.now()) }.flatMap { $0 > 0 ? $0 : nil }
  }

  // MARK: - Private

  /// What stopped a batch of part tasks: a terminal for the entry, or a
  /// part to build again after a backoff.
  private enum PartFailure {
    case settle(Outcome)
    case later(QueueEntry.Part, delayMs: Int?)
  }

  /// Part tasks for `requests`, in order. Write-ahead: every part file is
  /// built, then one save of the entry carries every new attempt ordinal,
  /// before any task exists. The tasks are created suspended, their TaskMap
  /// entries go in one write, and then they resume. A part that cannot be
  /// built stops the batch: a terminal settles the entry with no task made,
  /// and a transient failure keeps the tasks of the parts before it and
  /// refills after a backoff. Returns false when the entry settled, or a
  /// part got no task and a refill follows.
  private func enqueueParts(_ id: String, _ requests: [(index: Int, delayMs: Int?)]) -> Bool {
    guard var e = q.index.entry(id) else { return false }
    let blob = e.bodyPath ?? ChunkedManifestV9.blobName
    var built: [(index: Int, delayMs: Int?, url: URL, file: URL)] = []
    var failure: PartFailure?
    for (index, delayMs) in requests where e.parts.indices.contains(index) {
      let part = e.parts[index]
      guard let url = URL(string: part.url) else {
        failure = PartFailure.settle(.error(OutcomeErrorRecord(
          errorKind: "unknown", message: "part \(index) url is not valid", partIndex: index)))
        break
      }
      do {
        let file = try q.store.writePartFile(
          id: id, blob: blob, index: index, start: part.start, end: part.end, incarnation: e.incarnation)
        built.append((index: index, delayMs: delayMs, url: url, file: file))
      } catch {
        // Only a missing or short blob can never succeed. Any other failure
        // (a full disk, protected data) may pass: build the part again later.
        let blobSize = FileIO.size(q.store.fileURL(id, blob)) ?? 0
        if blobSize < part.end {
          failure = PartFailure.settle(
            .fileError("cannot build part \(index): \(error.localizedDescription)", partIndex: index))
        } else {
          failure = PartFailure.later(part, delayMs: delayMs)
        }
        break
      }
    }
    if case .settle(let outcome) = failure {
      q.settle(id, outcome)
      return false
    }
    if let first = built.first {
      let base = e.attempts
      e.attempts += built.count
      guard q.commitAhead(e, emit: false) else {
        refillLater(e, part: e.parts[first.index], delayMs: first.delayMs)
        return false
      }
      var made: [(index: Int, delayMs: Int?, task: UploadTask, meta: TaskMap.Meta)] = []
      var unopened: (index: Int, delayMs: Int?, error: Error)?
      for (offset, b) in built.enumerated() {
        let part = e.parts[b.index]
        let requestId = UUID().uuidString
        let meta = TaskMap.Meta(
          id: id, partIndex: b.index, incarnation: e.incarnation, attempt: base + offset + 1,
          requestId: requestId, headerGeneration: q.settings.headerGeneration,
          generation: e.generation, purpose: .attempt, foreground: q.activity.isActive)
        do {
          let task = try q.transport.upload(
            q.buildRequest(e, url: b.url, requestId: requestId, partHeaders: part.headers),
            fromFile: b.file, wifiOnly: q.settings.wifiOnly(e.wifiOnly),
            description: ChunkedEngine.taskDescription(id: id, part: b.index, incarnation: e.incarnation),
            beginAt: b.delayMs.map { Date(timeIntervalSince1970: (q.now() + Double($0)) / 1000) })
          made.append((index: b.index, delayMs: b.delayMs, task: task, meta: meta))
        } catch {
          unopened = (index: b.index, delayMs: b.delayMs, error: error)
          break
        }
      }
      // One TaskMap write for the batch, before any of its tasks resumes.
      q.taskMap.set(made.map { (key: $0.task.key, meta: $0.meta) })
      for m in made {
        inFlight[id, default: [:]][m.index] = m.task.key
        q.liveTasks[m.task.key] = (id, m.task)
        if let delayMs = m.delayMs {
          partBeginAt[id, default: [:]][m.index] = q.now() + Double(delayMs)
        } else {
          partBeginAt[id]?[m.index] = nil
        }
        m.task.resume()
      }
      if let unopened {
        // The session could not open the part file. As for a failed part
        // file build: a short blob can never succeed; otherwise try again
        // later. The tasks made before it keep running. The ordinals saved
        // for the parts left are not reused.
        let part = e.parts[unopened.index]
        let blobSize = FileIO.size(q.store.fileURL(id, blob)) ?? 0
        if blobSize < part.end {
          q.settle(id, .fileError("cannot read part \(unopened.index): \(unopened.error.localizedDescription)",
                                  partIndex: unopened.index))
        } else {
          refillLater(e, part: part, delayMs: unopened.delayMs)
        }
        return false
      }
    }
    if case .later(let part, let delayMs) = failure {
      refillLater(e, part: part, delayMs: delayMs)
      return false
    }
    return true
  }

  /// No task was made for a part (a failed save or part file). Refill after
  /// the wait it asked for, or a backoff, whichever is longer.
  private func refillLater(_ e: QueueEntry, part: QueueEntry.Part, delayMs: Int?) {
    let backoff = RetryClassifier.backoffMs(
      attempt: max(part.rejections, 1), policy: q.policy(e), random: q.random)
    q.schedule(max(delayMs ?? 0, backoff)) { [weak self] in self?.refill(e.id) }
  }

  /// A transient part failure: the next task is created now with a
  /// backoff delay, so it holds the part's slot and the daemon starts it on
  /// time even while the app is dead.
  private func retryPart(_ e: QueueEntry, _ part: Int) {
    var n = e
    n.parts[part].rejections += 1
    let delay = RetryClassifier.backoffMs(
      attempt: n.parts[part].rejections, policy: q.policy(n), random: q.random)
    if q.now() + Double(delay) >= n.expiresAt {
      q.settle(n.id, .expired)
      return
    }
    q.commit(n, emit: false)
    _ = enqueueParts(n.id, [(index: part, delayMs: delay)])
  }

  /// Sets nextAttemptAt to the earliest begin date when every part in
  /// flight is a delayed task that has not begun, and clears it otherwise.
  /// The state stays running. Emits `state` only on a change.
  private func updateWait(_ id: String) {
    guard var e = q.index.entry(id), e.isChunked, e.state == .running else { return }
    let flight = inFlight[id] ?? [:]
    let waits = partBeginAt[id] ?? [:]
    let next = !flight.isEmpty && flight.keys.allSatisfy { waits[$0] != nil }
      ? flight.keys.compactMap { waits[$0] }.min() : nil
    guard next != e.nextAttemptAt else { return }
    e.nextAttemptAt = next
    q.commit(e)
  }

  /// Byte-weighted: accepted parts plus what the live part tasks sent. The
  /// row carries the same value.
  private func emitProgress(_ e: QueueEntry) {
    guard e.totalBytes > 0 else { return }
    let sent = min(e.acceptedBytes + (partSent[e.id]?.values.reduce(0, +) ?? 0), e.totalBytes)
    q.index.setBytes(e.id, sent)
    q.emitProgress(e.id, sent: sent, total: e.totalBytes)
  }
}
