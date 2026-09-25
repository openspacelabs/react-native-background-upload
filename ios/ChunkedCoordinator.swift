import Foundation

/// Runs chunked entries against the background sessions: keeps the sliding
/// window of part tasks enqueued with the daemon, evaluates each part's
/// outcome, and after a relaunch rebuilds the window from the tasks the
/// daemon still holds.
///
/// Every call runs on the QueueCoordinator's serial queue, which enforces the
/// binding invariants: at most ChunkedEngine.window part tasks per entry, and
/// never two for one part index. `inFlight` maps each enqueued part to the
/// key of the one task that owns it. Only enqueuePart creates part tasks.
///
/// The entry (QueueEntry) is the durable truth: parts, accepted flags,
/// incarnation. Terminals go through QueueCoordinator.settle.
final class ChunkedCoordinator {
  struct LiveTask {
    let task: UploadTask
    let part: Int
    let incarnation: String?
  }

  private unowned let q: QueueCoordinator
  // id -> part index -> the task key that owns it.
  private var inFlight: [String: [Int: String]] = [:]
  // id -> part index -> bytes sent by its live task. Feeds the byte-weighted
  // progress.
  private var partSent: [String: [Int: Int64]] = [:]
  // id -> part index -> when its delayed task begins (epoch ms), until it
  // begins. When every part in the window waits, the row shows the earliest
  // as nextAttemptAt.
  private var partBeginAt: [String: [Int: Double]] = [:]

  init(_ coordinator: QueueCoordinator) {
    q = coordinator
  }

  // MARK: - Called by QueueCoordinator

  /// queued -> running, then fill the window.
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

  /// Forgets the window. The caller cancels the tasks.
  func stop(_ id: String) {
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
    for t in tasks {
      let keep = t.incarnation == e.incarnation && e.parts.indices.contains(t.part)
        && !e.parts[t.part].accepted && live[t.part] == nil
      if keep {
        live[t.part] = t.task.key
        q.liveTasks[t.task.key] = (id, t.task)
        if let begin = t.task.beginAt.map({ $0.timeIntervalSince1970 * 1000 }), begin > q.now() {
          partBeginAt[id, default: [:]][t.part] = begin
        }
      } else {
        q.taskMap.setPurpose(.superseded, forKey: t.task.key, id: id)
        t.task.cancel()
      }
    }
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
    // Every path below may change the window; a settle or park makes this
    // a no-op.
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
        _ = enqueuePart(id, part, delayMs: nil)
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

  func partProgress(id: String, part: Int, incarnation: String?, sent: Int64) {
    guard let e = q.index.entry(id), e.incarnation == incarnation, e.state == .running else { return }
    partSent[id, default: [:]][part] = sent
    // A delayed part that began while the app was dead reports progress
    // before any willBegin.
    if partBeginAt[id]?.removeValue(forKey: part) != nil { updateWait(id) }
    emitProgress(q.index.entry(id) ?? e)
  }

  // MARK: - Window

  /// Fills the window back up. Called after every part completion (the
  /// background-wake refill that keeps the upload moving while the app is
  /// dead), at start, and at the end of every reconcile.
  func refill(_ id: String) {
    guard q.ready, let e = q.index.entry(id), e.isChunked, e.state == .running,
          !q.settings.isPaused(e.key) else { return }
    if e.allAccepted {
      q.settle(id, .completed(RawResponseRecord(bodyTruncated: false)))
      return
    }
    if q.now() >= e.expiresAt {
      q.settle(id, .expired)
      return
    }
    // A blob shorter than a part can never finish: report it now.
    let blobSize = e.bodyPath.flatMap { FileIO.size(q.store.fileURL(id, $0)) } ?? 0
    if let short = e.parts.indices.first(where: { e.parts[$0].end > blobSize }) {
      q.settle(id, .fileError(
        "source blob is \(blobSize) bytes; part \(short) needs "
          + "[\(e.parts[short].start), \(e.parts[short].end))", partIndex: short))
      return
    }
    let flight = Set((inFlight[id] ?? [:]).keys)
    for index in ChunkedEngine.indexesToEnqueue(pending: e.pendingIndexes(), inFlight: flight) {
      if !enqueuePart(id, index, delayMs: nil) { return } // settled or deferred inside
    }
  }

  // MARK: - Private

  /// One part task. Write-ahead: the attempt count is saved first; the
  /// TaskMap entry is written before resume. Returns false when the entry
  /// settled instead, or when the save failed: then no task exists and a
  /// refill runs after a backoff.
  private func enqueuePart(_ id: String, _ index: Int, delayMs: Int?) -> Bool {
    guard var e = q.index.entry(id), e.parts.indices.contains(index) else { return false }
    let part = e.parts[index]
    guard let url = URL(string: part.url) else {
      q.settle(id, .error(OutcomeErrorRecord(
        errorKind: "unknown", message: "part \(index) url is not valid", partIndex: index)))
      return false
    }
    let blob = e.bodyPath ?? ChunkedManifestV9.blobName
    let file: URL
    do {
      file = try q.store.writePartFile(
        id: id, blob: blob, index: index, start: part.start, end: part.end, incarnation: e.incarnation)
    } catch {
      // Only a missing or short blob can never succeed. Any other failure
      // (a full disk, protected data) may pass: build the part again later.
      let blobSize = FileIO.size(q.store.fileURL(id, blob)) ?? 0
      if blobSize < part.end {
        q.settle(id, .fileError("cannot build part \(index): \(error.localizedDescription)", partIndex: index))
      } else {
        refillLater(e, part: part, delayMs: delayMs)
      }
      return false
    }
    e.attempts += 1
    guard q.commitAhead(e, emit: false) else {
      refillLater(e, part: part, delayMs: delayMs)
      return false
    }

    let requestId = UUID().uuidString
    let meta = TaskMap.Meta(
      id: id, partIndex: index, incarnation: e.incarnation, attempt: e.attempts,
      requestId: requestId, headerGeneration: q.settings.headerGeneration,
      generation: e.generation, purpose: .attempt)
    let task = q.transport.upload(
      q.buildRequest(e, url: url, requestId: requestId, partHeaders: part.headers),
      fromFile: file, wifiOnly: q.settings.wifiOnly(e.wifiOnly),
      description: ChunkedEngine.taskDescription(id: id, part: index, incarnation: e.incarnation),
      beginAt: delayMs.map { Date(timeIntervalSince1970: (q.now() + Double($0)) / 1000) },
      beforeResume: { key in self.q.taskMap.set(meta, forKey: key) })
    inFlight[id, default: [:]][index] = task.key
    q.liveTasks[task.key] = (id, task)
    if let delayMs {
      partBeginAt[id, default: [:]][index] = q.now() + Double(delayMs)
    } else {
      partBeginAt[id]?[index] = nil
    }
    return true
  }

  /// No task was made for a part (a failed save or part file). Fill the
  /// window again after the wait it asked for, or a backoff, whichever is
  /// longer.
  private func refillLater(_ e: QueueEntry, part: QueueEntry.Part, delayMs: Int?) {
    let backoff = RetryClassifier.backoffMs(
      attempt: max(part.rejections, 1), policy: q.policy(e), random: q.random)
    q.schedule(max(delayMs ?? 0, backoff)) { [weak self] in self?.refill(e.id) }
  }

  /// A transient part failure: the next task is created now with a
  /// backoff delay, so it holds the part's window slot and the daemon starts
  /// it on time even while the app is dead.
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
    _ = enqueuePart(n.id, part, delayMs: delay)
  }

  /// Sets nextAttemptAt to the earliest begin date when every part in the
  /// window is a delayed task that has not begun, and clears it otherwise.
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
