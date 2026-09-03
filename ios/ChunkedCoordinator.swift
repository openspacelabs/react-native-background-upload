import Foundation

/// Runs chunked uploads against the background sessions. It keeps the sliding
/// window of part tasks enqueued with the daemon. It evaluates the outcome of
/// each part. After a relaunch, it reconciles the durable [ChunkedManifest]
/// with the tasks that the daemon still holds.
///
/// Every state transition occurs on one serial queue. The queue enforces the
/// invariants that the design marks binding: at most [ChunkedEngine.window]
/// part tasks are enqueued per upload, and never two for the same part index.
/// `inFlight` maps each enqueued part to the task key that owns it. Only
/// refill, on this queue, creates tasks.
final class ChunkedCoordinator {

  // The singleton that owns the background sessions. It outlives this object.
  // Both live for the whole process.
  private unowned let uploader: RNBackgroundUpload

  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.chunked")

  // partIndex -> the TaskMap key of the one task that may be in flight for it.
  // The key lets us tell a superseded task's late completion (possible around
  // a relaunch reconcile) apart from the live task's completion.
  private var inFlight: [String: [Int: String]] = [:]
  // The uploads whose in-flight set we rebuild from the daemon now. Refill is
  // blocked until the rebuild lands. Thus a stale snapshot can never
  // double-enqueue. The token makes overlapping reconciles safe: only the
  // latest reconcile may apply its snapshot. An earlier snapshot could miss
  // tasks enqueued after it was taken. To apply it would re-enqueue their
  // part indexes.
  private var reconcileToken: [String: UUID] = [:]
  private var cooldownUntil: [String: [Int: Double]] = [:] // epoch ms
  private var transientAttempts: [String: [Int: Int]] = [:]
  private var expiryArmed: Set<String> = []
  // The in-flight bytes per part index. They feed the byte-weighted
  // aggregate progress.
  private var partSent: [String: [Int: Int64]] = [:]
  // A cache of the stored manifests, refreshed on every load. The progress
  // path reads it. Thus didSendBodyData never touches the disk.
  private var manifests: [String: ChunkedManifest] = [:]

  private static let progressThrottle: TimeInterval = 0.5 // seconds, per upload
  private let progressLock = NSLock()
  private var lastProgressAt: [String: TimeInterval] = [:]

  init(uploader: RNBackgroundUpload) {
    self.uploader = uploader
  }

  private func nowMs() -> Double { Date().timeIntervalSince1970 * 1000 }

  // MARK: - Entry points (module methods)

  /// Starts, or resumes, a chunked upload. The durable manifest makes the call
  /// idempotent. A first call takes ownership of the source file (an O(1)
  /// rename into the library's directory) and saves the manifest BEFORE any
  /// task is enqueued. A new call with the same id reconciles instead. The
  /// same parts resume: the stored headers are replaced, and accepted parts
  /// are skipped. Different parts recreate the upload, per the design's rule
  /// (see ChunkedManifest.reconciled). Crash recovery, resume after a stop,
  /// and resume with fresh auth are all this same call.
  ///
  /// Every rejection-type validation runs BEFORE the source is consumed. The
  /// parse throws first, and a reconcile never touches the source (`path` is
  /// ignored once a manifest exists). One rejection is possible after the
  /// move: the manifest save can fail. That leaves the blob adoptable. A
  /// retry with the same id finds the blob at the blob path and proceeds (see
  /// takeOwnership).
  func startUpload(_ options: [String: Any],
                   resolve: @escaping (String) -> Void,
                   reject: @escaping (String) -> Void) {
    queue.async {
      do {
        let incoming = try ChunkedManifest.parse(options, createdAt: self.nowMs())
        let id = incoming.id
        let manifest: ChunkedManifest
        if let existing = ChunkedStore.load(id) {
          // "Running" per the design's recreate rule: not stalled (no
          // journaled terminal error or cancel that awaits this resume) and
          // not past its deadline. Everything else rejects a different parts
          // array. That includes part tasks live with the daemon, and
          // finished-but-unacked.
          let running = !existing.stalled && !existing.isExpired(self.nowMs())
          manifest = try existing.reconciled(
            with: incoming, running: running, blobSize: ChunkedStore.blobSize(id))
          if manifest.incarnation != existing.incarnation {
            // This is a recreate. The in-flight byte counts belong to the
            // replaced parts. reconcileLocked below cancels the old
            // incarnation's tasks, and does not adopt them. enqueuePart
            // sweeps its temp files.
            self.partSent[id] = nil
          }
        } else {
          guard let path = options["path"] as? String else {
            throw ChunkedManifest.ParseError(message: "Missing 'path'")
          }
          try self.takeOwnership(path: path, id: id)
          // The same rule as recreate, and as Android's validatedForCreate:
          // the parts must tile [0, blob size) exactly. A partial or
          // overlapping cover would silently upload wrong bytes. This throws
          // BEFORE the manifest is saved and before anything is enqueued.
          // Thus the moved blob stays adoptable by a corrected retry with the
          // same id (see takeOwnership).
          let blobSize = ChunkedStore.blobSize(id)
          guard ChunkedManifest.tilesExactly(incoming.parts, size: blobSize) else {
            throw ChunkedManifest.ParseError(
              message: "chunked upload '\(id)' parts must tile exactly [0, \(blobSize))")
          }
          manifest = incoming
        }
        try ChunkedStore.save(manifest)
        self.manifests[id] = manifest
        // A fresh call gets a fresh retry budget. The persisted per-part
        // rejection counts reset in the parts rebuild above (reconciled or
        // parse).
        self.transientAttempts[id] = nil
        self.cooldownUntil[id] = nil
        self.reconcileLocked(id, resumedByStart: true)
        resolve(id)
      } catch {
        reject(error.localizedDescription)
      }
    }
  }

  /// Rebuilds every stored upload's in-flight set from the daemon, and then
  /// refills. Called when the sessions are created or recreated: an app
  /// relaunch, a JS reload, or the background-wake path through
  /// `RNBackgroundUpload.shared`.
  func reconcileAll() {
    // Claim the system's background completion handlers BEFORE the reconcile
    // is queued. After a relaunch, the replayed didCompleteWithError callbacks
    // run unowned and never refill. Thus this chain is the only refill.
    // Nothing else stops urlSessionDidFinishEvents from handing the system
    // its handler, and the app its suspension, before one new part task is
    // enqueued. The risk is largest exactly when every enqueued part finished
    // while the app was dead: zero daemon tasks left, no future wake, and a
    // silent stall. The claim provably precedes any drain: a relaunch reaches
    // this point inside the init of `shared`, and the AppDelegate hook
    // finishes that init before it stores the handler.
    RNBackgroundUpload.deferBackgroundCompletionHandlers()
    queue.async {
      let group = DispatchGroup()
      for manifest in ChunkedStore.all() {
        self.manifests[manifest.id] = manifest
        group.enter()
        self.reconcileLocked(manifest.id, resumedByStart: false) { group.leave() }
      }
      group.notify(queue: self.queue) {
        // Every upload's post-reconcile refill has resumed its tasks. The
        // handlers can drain now.
        RNBackgroundUpload.releaseBackgroundCompletionHandlers()
      }
    }
  }

  /// Cancels a chunked upload. It journals one 'cancelled' (user) terminal
  /// and stalls the upload. The manifest and the bytes are kept. Thus the
  /// next startUpload resumes. Completion receives nil when the id has no
  /// manifest (not a chunked upload). It receives false when nothing runs
  /// (the upload is already terminal).
  func cancel(_ id: String, completion: @escaping (Bool?) -> Void) {
    queue.async {
      guard let manifest = self.latest(id) else { completion(nil); return }
      if manifest.stalled || manifest.allAccepted { completion(false); return }
      var entry = JournaledEvent(
        eventId: UUID().uuidString, id: id, type: "cancelled", timestamp: self.nowMs())
      entry.cancelReason = "user"
      self.stall(id, entry: entry)
      completion(true)
    }
  }

  /// An explicit release. It cancels the in-flight part tasks, with no
  /// terminal event: the consumer lets go, and awaits no outcome. It deletes
  /// the manifest, the moved bytes, and all part temp files.
  func remove(_ id: String, completion: @escaping () -> Void) {
    queue.async {
      if self.latest(id) != nil { self.cancelTasks(for: id) }
      ChunkedStore.remove(id)
      self.clearState(id)
      completion()
    }
  }

  /// The one moment when the library may delete a chunked upload's bytes: the
  /// consumer acknowledged its 'completed' terminal event.
  func releaseCompleted(_ ids: [String], completion: @escaping () -> Void) {
    queue.async {
      for id in ids {
        ChunkedStore.remove(id)
        self.clearState(id)
      }
      completion()
    }
  }

  /// The chunked rows for getAllUploads: one aggregate row per manifest. The
  /// part tasks are transport detail. bytesSent counts accepted parts only.
  /// That is the durable number.
  func snapshots(completion: @escaping ([[String: Any]]) -> Void) {
    queue.async {
      let rows = ChunkedStore.all().map { manifest -> [String: Any] in
        let state: String
        if manifest.allAccepted {
          state = "completed"
        } else if manifest.stalled {
          state = "error"
        } else if !(self.inFlight[manifest.id] ?? [:]).isEmpty {
          state = "running"
        } else {
          state = "pending"
        }
        return ["id": manifest.id,
                "state": state,
                "bytesSent": manifest.acceptedBytes,
                "totalBytes": manifest.totalBytes]
      }
      completion(rows)
    }
  }

  // MARK: - Delegate hooks (called by RNBackgroundUpload)

  func partProgress(id: String, part: Int, incarnation: String?, sent: Int64) {
    let now = Date().timeIntervalSince1970
    progressLock.lock()
    if let last = lastProgressAt[id], now - last < Self.progressThrottle {
      progressLock.unlock()
      return
    }
    lastProgressAt[id] = now
    progressLock.unlock()
    queue.async {
      // A removed or replaced incarnation's task must not feed the aggregate.
      guard let manifest = self.manifests[id], manifest.incarnation == incarnation else { return }
      self.partSent[id, default: [:]][part] = sent
      self.emitAggregateProgress(id, manifest)
    }
  }

  /// One part task finished (a foreground or background-wake delegate
  /// callback). We evaluate the accept rules, update the manifest, delete the
  /// temp file, and refill the window. It is synchronous on purpose: the
  /// journal write for a terminal outcome must land before the delegate
  /// callback returns. The simple-upload path obeys the same rule.
  func handlePartCompletion(id: String, part: Int, incarnation: String?, taskKey: String,
                            statusCode: Int?, headers: [String: String],
                            body: String?, error: NSError?) {
    queue.sync {
      TaskMap.removeKey(taskKey)
      let owned = inFlight[id]?[part] == taskKey
      if owned {
        inFlight[id]?[part] = nil
        partSent[id]?[part] = nil
      }
      guard var manifest = latest(id) else {
        // The upload was removed (removeUpload, or a completed ack) while
        // this task was in flight. There is nothing left to report.
        if owned { ChunkedStore.removePartFile(id, part) }
        return
      }
      // A late callback from a removed-then-recreated or replaced
      // incarnation. Its response is about byte ranges and URLs that this
      // manifest no longer describes. Thus nothing about it, the accept flag
      // included, may be written into the current manifest. Its temp file has
      // the old token in its name. The sweep removes it when the current plan
      // next materializes this index.
      guard incarnation == manifest.incarnation else {
        if owned { refill(id) }
        return
      }
      // A part index that the manifest does not know (corrupt task metadata)
      // must not crash the delegate. Drop the task's outcome and let refill
      // plan again.
      guard manifest.parts.indices.contains(part) else {
        if owned { refill(id) }
        return
      }

      // Accept evaluation comes first. The server holds these bytes now,
      // regardless of a concurrent stall or a superseded task in the same
      // incarnation. If we lose the flag, we re-send a part that the server
      // already has.
      if error == nil, let statusCode,
         UploadOutcome.isAccepted(statusCode, body: body, accept: manifest.accept) {
        manifest = updateManifest(id) { $0.withPartAccepted(part) }
          ?? manifest.withPartAccepted(part)
        transientAttempts[id]?[part] = nil
        cooldownUntil[id]?[part] = nil
        if owned { ChunkedStore.removePartFile(id, part) }
        // A stalled upload keeps the flag but reports nothing more. The
        // journaled terminal stands until the next startUpload resume. That
        // resume finds all parts accepted and completes without a re-send.
        guard !manifest.stalled else { return }
        if manifest.allAccepted {
          finalizeCompleted(id, manifest, reemit: false)
        } else {
          emitAggregateProgress(id, manifest)
          if owned { refill(id) }
        }
        return
      }

      // A superseded task's failure carries no policy weight. The live task
      // for this part drives the retries. But an UNOWNED task with no live
      // replacement is a relaunch replay that runs before reconcile rebuilds
      // ownership. If we drop its deterministic HTTP rejection, the part gets
      // a fresh retry budget on every system wake. So count it, and let it
      // trip the budget. The in-flight reconcile does the re-enqueueing.
      guard owned else {
        if inFlight[id]?[part] == nil, !manifest.stalled, !manifest.parts[part].accepted,
           error == nil, let code = statusCode, !ChunkedEngine.isTransientHttp(code) {
          recordRejection(id, part: part, manifest: manifest, code: code,
                          headers: headers, body: body, scheduleRetryInBudget: false)
        }
        return
      }
      ChunkedStore.removePartFile(id, part) // the retry builds the file again
      // This is a duplicate of a part that a superseded task already
      // delivered. The part is settled, whatever this task's outcome was. Its
      // failure must not burn retries.
      if manifest.parts[part].accepted {
        if !manifest.stalled { refill(id) }
        return
      }
      // A terminal is already journaled (a cancel, or a sibling part's
      // stall). Swallow the fallout.
      guard !manifest.stalled else { return }

      if let error, error.domain == NSURLErrorDomain, error.code == NSURLErrorCancelled {
        // A user cancel journals and stalls in cancel() before the tasks are
        // torn down. Thus a cancel here, with no stall, comes from the
        // system. Retry it like a transient failure.
        scheduleTransientRetry(id, part: part)
        return
      }

      if manifest.isExpired(nowMs()) {
        stall(id, entry: expiredEntry(id))
        return
      }

      if let error {
        if RNBackgroundUpload.errorKind(for: error) == "file",
           !FileManager.default.fileExists(atPath: ChunkedStore.blobURL(id).path) {
          stall(id, entry: errorEntry(
            id: id, error: "chunked source blob missing", errorKind: "file", partIndex: part))
        } else {
          // This includes a lost temp part file. The retry rebuilds it from
        // the blob.
          scheduleTransientRetry(id, part: part)
        }
        return
      }

      let code = statusCode ?? 0
      if ChunkedEngine.isTransientHttp(code) {
        scheduleTransientRetry(id, part: part)
        return
      }
      recordRejection(id, part: part, manifest: manifest, code: code,
                      headers: headers, body: body, scheduleRetryInBudget: true)
    }
  }

  /// The identity of a chunked part task, or nil for a simple upload's task.
  /// taskDescription is primary. The persisted TaskMap entry, written before
  /// the task first resumed, is the durable fallback. `incarnation` is the
  /// manifest token that the task was created under. It is nil only for
  /// corrupt metadata, and the consumers treat nil as a mismatch.
  static func partRef(_ session: URLSession, _ task: URLSessionTask)
    -> (id: String, part: Int, incarnation: String?)? {
    if let ref = ChunkedEngine.parseTaskDescription(task.taskDescription) { return ref }
    if let meta = TaskMap.meta(forKey: TaskMap.key(session, task)), let part = meta.partIndex {
      return (meta.id, part, meta.incarnation)
    }
    return nil
  }

  // MARK: - Window (all on `queue`)

  /// Rebuilds inFlight for one upload from the daemon's live tasks, and then
  /// refills. A task in the .completed or .canceling state is NOT live: its
  /// delegate callback, replayed after a relaunch, settles it. A part with no
  /// live task simply enqueues again. Accept evaluation absorbs a
  /// completed-but-unreported duplicate. We never guess.
  /// `completion` fires, on `queue`, when this reconcile has settled: the
  /// refill ran, or a newer reconcile superseded this one. reconcileAll gates
  /// the background completion handlers on it.
  private func reconcileLocked(_ id: String, resumedByStart: Bool,
                               completion: (() -> Void)? = nil) {
    let token = UUID()
    reconcileToken[id] = token
    enumerateAllTasks { tasks in
      self.queue.async {
        defer { completion?() }
        guard self.reconcileToken[id] == token else { return } // superseded
        let manifest = self.latest(id)
        var live: [Int: String] = [:]
        for (session, task) in tasks {
          guard let ref = Self.partRef(session, task), ref.id == id,
                task.state == .running || task.state == .suspended else { continue }
          if ref.incarnation != manifest?.incarnation || live[ref.part] != nil {
            // Never adopt a task from a replaced incarnation. Its bytes and
            // URL belong to the old plan, and the token check in
            // handlePartCompletion drops its late completion. Never adopt a
            // second live task for one part index: concurrent PUTs of one
            // partNum are verified unsafe on the server side.
            task.cancel()
          } else {
            live[ref.part] = TaskMap.key(session, task)
          }
        }
        self.inFlight[id] = live
        self.reconcileToken[id] = nil
        if let manifest {
          // Temp files for accepted parts with no live task are orphans.
          for index in manifest.parts.indices
          where manifest.parts[index].accepted && live[index] == nil {
            ChunkedStore.removePartFile(id, index)
          }
        }
        self.refill(id, resumedByStart: resumedByStart)
      }
    }
  }

  /// Fills the window back up to [ChunkedEngine.window] enqueued part tasks.
  /// Called after every part completion (the background-wake refill that the
  /// design's liveness rationale requires), after a retry cooldown, and at
  /// the end of every reconcile.
  private func refill(_ id: String, resumedByStart: Bool = false) {
    guard reconcileToken[id] == nil, let manifest = latest(id) else { return }
    // Stalled wins, even over all-accepted. The journaled terminal stands
    // until an explicit startUpload resume. The resume clears the stall,
    // lands here again, and completes without a re-send.
    guard !manifest.stalled else { return }
    if manifest.allAccepted {
      finalizeCompleted(id, manifest, reemit: resumedByStart)
      return
    }
    let now = nowMs()
    if manifest.isExpired(now) {
      stall(id, entry: expiredEntry(id))
      return
    }
    armExpiryCheck(id, expiresAt: manifest.expiresAt)
    // A blob shorter than a part's range can never finish. Report a terminal
    // 'file' now, not a surprise when the window reaches the short part
    // later. A retry cannot help, because the bytes are not there. Thus this
    // stalls, and awaits removeUpload or a recreate whose tiling rule fits
    // the real size.
    let blobSize = ChunkedStore.blobSize(id)
    if let short = manifest.parts.indices.first(where: { manifest.parts[$0].end > blobSize }) {
      stall(id, entry: errorEntry(
        id: id,
        error: "source blob is \(blobSize) bytes; part \(short) needs "
          + "[\(manifest.parts[short].start), \(manifest.parts[short].end))",
        errorKind: "file", partIndex: short))
      return
    }
    let flight = Set((inFlight[id] ?? [:]).keys)
    let cooling = Set((cooldownUntil[id] ?? [:]).filter { $0.value > now }.keys)
    for index in ChunkedEngine.indexesToEnqueue(
      pending: manifest.pendingIndexes(), inFlight: flight, cooling: cooling) {
      if !enqueuePart(id, index, manifest) { return } // stalled inside
    }
  }

  private func enqueuePart(_ id: String, _ index: Int, _ manifest: ChunkedManifest) -> Bool {
    let part = manifest.parts[index]
    guard let url = URL(string: part.url) else {
      stall(id, entry: errorEntry(
        id: id, error: "part \(index) url is not a valid URL", errorKind: "unknown",
        partIndex: index))
      return false
    }
    // A background session can upload only from a file. Thus each enqueued
    // part gets a temp file that holds exactly its byte range. The transient
    // disk usage stays at window × partSize, not a second full copy of the
    // source.
    let partFile: URL
    do {
      partFile = try ChunkedStore.writePartFile(
        id: id, index: index, start: part.start, end: part.end,
        incarnation: manifest.incarnation)
    } catch {
      stall(id, entry: errorEntry(
        id: id, error: "cannot materialize part \(index): \(error.localizedDescription)",
        errorKind: "file", partIndex: index))
      return false
    }
    var request = URLRequest(url: url)
    request.httpMethod = "PUT"
    // Unchanged, per the protocol-as-data rule. The library adds nothing.
    for (key, value) in part.headers {
      request.setValue(value, forHTTPHeaderField: key)
    }
    let session = uploader.session(wifiOnly: manifest.wifiOnly)
    let task = session.uploadTask(with: request, fromFile: partFile)
    task.taskDescription = ChunkedEngine.taskDescription(
      id: id, part: index, incarnation: manifest.incarnation)
    let key = TaskMap.key(session, task)
    TaskMap.set(TaskMap.Meta(id: id, accept: nil, partIndex: index,
                             incarnation: manifest.incarnation), forKey: key)
    inFlight[id, default: [:]][index] = key
    task.resume()
    return true
  }

  // MARK: - Terminal transitions (all on `queue`)

  /// Journals the terminal, marks the upload stalled, and cancels its
  /// in-flight tasks. The stall is durable: relaunch reconciliation must not
  /// resume the upload; only startUpload may. The manifest and the bytes are
  /// kept. Every non-completed terminal leaves the consumer its recovery
  /// options.
  private func stall(_ id: String, entry: JournaledEvent) {
    _ = updateManifest(id) { manifest in
      var next = manifest
      next.stalled = true
      return next
    }
    cancelTasks(for: id)
    partSent[id] = nil
    cooldownUntil[id] = nil
    RNBackgroundUpload.journalAndEmit(entry)
  }

  private func finalizeCompleted(_ id: String, _ manifest: ChunkedManifest, reemit: Bool) {
    for index in manifest.parts.indices { ChunkedStore.removePartFile(id, index) }
    partSent[id] = nil
    // A resume of a finished-but-unacked upload must not mint a second
    // terminal event. Emit the journaled event again. Thus a live listener
    // still hears it, with the eventId that the consumer will ack.
    if let existing = EventJournal.unacknowledgedEntries()
      .first(where: { $0.id == id && $0.type == "completed" }) {
      if reemit { RNBackgroundUpload.emitEvent(existing) }
      return
    }
    // There are no response fields, because no single response represents N
    // accepted parts. The blob is deleted only when this event is ACKED (see
    // ackEvents).
    RNBackgroundUpload.journalAndEmit(
      JournaledEvent(eventId: UUID().uuidString, id: id, type: "completed", timestamp: nowMs()))
  }

  // MARK: - Retry scheduling (all on `queue`)

  /// Counts one non-transient HTTP rejection against the budget of `part`.
  /// The count lives in the manifest, persisted best-effort like the accepted
  /// flag. Thus it survives process death and can trip across wakes. A resume
  /// or a recreate resets it (ChunkedManifest.reconciled rebuilds the parts
  /// from the incoming call). Over budget: journal the terminal 'http' and
  /// stall. In budget: schedule the backoff retry when this callback owns the
  /// part. For an unowned replay, the reconcile already in flight does the
  /// re-enqueueing.
  private func recordRejection(_ id: String, part: Int, manifest: ChunkedManifest,
                               code: Int, headers: [String: String], body: String?,
                               scheduleRetryInBudget: Bool) {
    let count = (manifest.parts[part].rejections ?? 0) + 1
    _ = updateManifest(id) { $0.withPartRejections(part, count) }
    if count > ChunkedEngine.partHttpRetries {
      let (capped, truncated) = EventJournal.capBody(body)
      var entry = errorEntry(
        id: id, error: "HTTP \(code) on part \(part)", errorKind: "http", partIndex: part)
      entry.responseCode = code
      entry.responseBody = capped
      entry.responseBodyTruncated = truncated
      entry.responseHeaders = headers
      stall(id, entry: entry)
    } else if scheduleRetryInBudget {
      scheduleRetry(id, part: part, attempt: count)
    }
  }

  private func scheduleTransientRetry(_ id: String, part: Int) {
    let attempt = (transientAttempts[id]?[part] ?? 0) + 1
    transientAttempts[id, default: [:]][part] = attempt
    scheduleRetry(id, part: part, attempt: attempt)
  }

  private func scheduleRetry(_ id: String, part: Int, attempt: Int) {
    let delayMs = ChunkedEngine.backoffMs(attempt: attempt)
    cooldownUntil[id, default: [:]][part] = nowMs() + Double(delayMs)
    queue.asyncAfter(deadline: .now() + .milliseconds(delayMs)) { [weak self] in
      guard let self else { return }
      self.cooldownUntil[id]?[part] = nil
      self.refill(id)
    }
  }

  // Expiry is evaluated on every transition. But an upload whose tasks all
  // wait (for connectivity, or for backoff) would pass its deadline silently
  // while the app is alive. Thus we arm one timer at the deadline. When a
  // resume extended expiresAt, the stale timer's refill is a no-op that arms
  // the timer again.
  private func armExpiryCheck(_ id: String, expiresAt: Double) {
    guard !expiryArmed.contains(id) else { return }
    expiryArmed.insert(id)
    let delayMs = Int(min(max(expiresAt - nowMs(), 0) + 100, 7 * 24 * 3_600_000))
    queue.asyncAfter(deadline: .now() + .milliseconds(delayMs)) { [weak self] in
      guard let self else { return }
      self.expiryArmed.remove(id)
      self.refill(id)
    }
  }

  // MARK: - Helpers

  // The stored copy is the truth. A reconcile can have replaced the headers
  // or expiresAt. The cache exists for the progress path, and as a fallback
  // when a read fails in flight.
  private func latest(_ id: String) -> ChunkedManifest? {
    guard let manifest = ChunkedStore.load(id) else {
      manifests[id] = nil
      return nil
    }
    manifests[id] = manifest
    return manifest
  }

  private func updateManifest(
    _ id: String, _ transform: (ChunkedManifest) -> ChunkedManifest
  ) -> ChunkedManifest? {
    // Here the save is best-effort, unlike in startUpload. A lost accepted
    // flag only causes a re-send of a part, and the server absorbs the
    // duplicate through the accept rules. That is better than a failed upload
    // that the server in fact took.
    let next = ChunkedStore.update(id, transform) ?? manifests[id].map(transform)
    if let next { manifests[id] = next }
    return next
  }

  private func takeOwnership(path: String, id: String) throws {
    let source = URL(string: path) ?? URL(fileURLWithPath: path)
    let blob = ChunkedStore.blobURL(id)
    let fm = FileManager.default
    guard fm.fileExists(atPath: source.path) else {
      // A crash between the move and the manifest save leaves the bytes at
      // the blob path with no manifest. Adopt the bytes. Do not fail the
      // retry.
      if fm.fileExists(atPath: blob.path) { return }
      throw ChunkedManifest.ParseError(
        message: "chunked source file does not exist: \(source.path)")
    }
    try fm.createDirectory(at: ChunkedStore.uploadDir(id), withIntermediateDirectories: true)
    try? fm.removeItem(at: blob)
    // This is an O(1) rename on the same volume. Across volumes, FileManager
    // falls back to a copy.
    try fm.moveItem(at: source, to: blob)
  }

  private func emitAggregateProgress(_ id: String, _ manifest: ChunkedManifest) {
    let total = manifest.totalBytes
    guard total > 0 else { return }
    let sent = min(manifest.acceptedBytes + (partSent[id]?.values.reduce(0, +) ?? 0), total)
    RNBackgroundUpload.emitProgress(id: id, progress: 100.0 * Float(sent) / Float(total))
  }

  private func clearState(_ id: String) {
    inFlight[id] = nil
    reconcileToken[id] = nil // discards any pending reconcile snapshot
    partSent[id] = nil
    cooldownUntil[id] = nil
    transientAttempts[id] = nil
    manifests[id] = nil
    // A removed-then-recreated id must be able to arm its own expiry
    // deadline, which is possibly earlier. It must not wait out the stale
    // timer.
    expiryArmed.remove(id)
    progressLock.lock()
    lastProgressAt[id] = nil // without this, one entry per id stays forever
    progressLock.unlock()
  }

  private func cancelTasks(for id: String) {
    enumerateAllTasks { tasks in
      for (session, task) in tasks where Self.partRef(session, task)?.id == id {
        task.cancel()
      }
    }
  }

  // Always examine both sessions. A resume can change wifiOnly while earlier
  // part tasks continue where they started.
  private func enumerateAllTasks(
    _ completion: @escaping ([(URLSession, URLSessionTask)]) -> Void
  ) {
    let sessions = [uploader.session(wifiOnly: false), uploader.session(wifiOnly: true)]
    let group = DispatchGroup()
    let lock = NSLock()
    var collected: [(URLSession, URLSessionTask)] = []
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        lock.lock()
        collected.append(contentsOf: tasks.map { (session, $0) })
        lock.unlock()
        group.leave()
      }
    }
    group.notify(queue: .global()) { completion(collected) }
  }

  private func expiredEntry(_ id: String) -> JournaledEvent {
    errorEntry(id: id, error: "upload expired before every part was accepted",
               errorKind: "expired")
  }

  private func errorEntry(id: String, error: String, errorKind: String,
                          partIndex: Int? = nil) -> JournaledEvent {
    var entry = JournaledEvent(
      eventId: UUID().uuidString, id: id, type: "error", timestamp: nowMs())
    entry.error = error
    entry.errorKind = errorKind
    entry.partIndex = partIndex
    return entry
  }
}
