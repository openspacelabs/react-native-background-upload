import Foundation

// enqueue() and the same-id rules (spec 5.4). Runs on the coordinator queue.
// Every reject path leaves the previous entry and its body as they were:
// a new body is staged under a fresh name, and the old one is deleted only
// after the new entry.json landed.
extension QueueCoordinator {

  /// Returns the id, and whether to issue it after the resolve.
  func enqueueLocked(_ raw: [String: Any]) throws -> (id: String, issue: Bool) {
    let p: ParsedEnqueue
    do {
      p = try EnqueueParser.parse(raw)
    } catch {
      throw EnqueueError.invalid("enqueue: \(error.localizedDescription)")
    }
    if let existing = index.entry(p.id) {
      if existing.legacy {
        // A legacy row over v9 chunked bytes: create() adopts the manifest
        // (same parts resume their accepted parts) or keeps the blob. It
        // writes entry.json over the legacy row.
        if case .parts = p.body, store.loadV9Manifest(p.id) != nil { return try create(p) }
        return try enqueueExisting(existing, p)
      }
      // A completed entry whose ack landed but whose forget did not (a crash
      // between the two) is gone for JS. Start over.
      if existing.state == .completed, settledEvent(existing) == nil {
        forget(existing.id, dropEvents: true)
      } else {
        return try enqueueExisting(existing, p)
      }
    }
    return try create(p)
  }

  /// Rule 2: no entry has the id.
  private func create(_ p: ParsedEnqueue) throws -> (id: String, issue: Bool) {
    let dir = store.dir(p.id)
    var fallback = store.adoptableBlob(p.id)
    var adopted: ChunkedManifestV9?
    if case .parts = p.body, let manifest = store.loadV9Manifest(p.id),
       FileIO.exists(store.fileURL(p.id, ChunkedManifestV9.blobName)) {
      fallback = ChunkedManifestV9.blobName
      // A dormant v9 upload with the same parts resumes: its blob and its
      // accepted parts are the body. The source path is ignored, as in v9.
      if manifest.sameParts(as: p.parts) { adopted = manifest }
    }

    let staged: StagedBody
    if adopted != nil {
      let size = FileIO.size(store.fileURL(p.id, ChunkedManifestV9.blobName)) ?? 0
      try mapStaging { try BodyStaging.requireTiling(p.parts, size: size) }
      staged = StagedBody(kind: .parts, relativePath: ChunkedManifestV9.blobName, contentType: nil,
                          forceContentType: false, totalBytes: size, adopted: true)
    } else {
      staged = try mapStaging {
        try BodyStaging.stage(p.body, parts: p.parts, into: dir, fallbackBlob: fallback)
      }
    }

    var e = QueueEntry.created(from: p, staged: staged, headerGeneration: settings.headerGeneration,
                               paused: settings.paused, now: now())
    if let manifest = adopted {
      e.incarnation = manifest.incarnation
      for i in e.parts.indices { e.parts[i].accepted = manifest.parts[i].accepted }
      e.bytesSent = e.acceptedBytes
    }
    try saveOrDiscard(e, staged: staged)
    store.removeV9Manifest(p.id)
    store.sweep(e)
    publish(e)
    armExpiry(e)
    return (p.id, !settings.paused)
  }

  private func enqueueExisting(_ existing: QueueEntry, _ p: ParsedEnqueue) throws
    -> (id: String, issue: Bool) {
    let paused = settings.paused
    if existing.bodyFingerprint == p.fingerprint && !existing.legacy {
      switch existing.state {
      case .completed:
        // Rule 7: re-emit the journaled outcome. Do not run again. With no
        // listener yet, the drain delivers it.
        if sink?.canDeliver() == true, let eventId = existing.settledEventId,
           let event = redeliver(eventId) {
          sink?.emitSettled(event.bridged)
        }
        return (p.id, false)

      case .error, .cancelled:
        // Rule 3 on a settled entry, and rule 6 for a cancelled one: reopen
        // under a fresh generation. The old outcome's ack no longer forgets it.
        var n = existing.resumed(with: p, resetBudget: true, now: now())
        n.generation += 1
        n.state = paused ? .paused : .queued
        n.settledEventId = nil
        n.authParked = false
        n.nextAttemptAt = nil
        n.bytesSent = n.isChunked ? n.acceptedBytes : 0
        try saveOrThrow(n)
        publish(n)
        armExpiry(n)
        return (p.id, !paused)

      case .awaitingAuth:
        // Fresh headers came with the call: leave the parking spot. Same
        // generation, so attempts keep counting.
        var n = existing.resumed(with: p, resetBudget: false, now: now())
        n.authParked = false
        n.state = paused ? .paused : .queued
        try saveOrThrow(n)
        publish(n)
        armExpiry(n)
        return (p.id, !paused)

      case .queued, .running, .paused:
        // The in-flight task keeps its request. A retry waiting in the daemon
        // picks up the new headers in willBeginDelayedRequest.
        let n = existing.resumed(with: p, resetBudget: false, now: now())
        try saveOrThrow(n)
        publish(n)
        armExpiry(n)
        if ready, !paused, n.state == .queued, !n.isChunked, let at = n.nextAttemptAt, at > now() {
          // A simple retry waiting out its backoff: the caller asks again,
          // so retry now. The waiting attempt never ran: keep its ordinal.
          cancelTasks(n.id, purpose: .superseded)
          issue(n.id, delayMs: nil, advanceAttempt: false)
        }
        return (p.id, false)
      }
    }

    // Rules 4 and 5: a different body.
    guard existing.state != .running else { throw EnqueueError.running(p.id) }
    // A delayed retry task may wait in the daemon. Mark it superseded first,
    // so its NSURLErrorCancelled is dropped.
    cancelTasks(existing.id, purpose: .superseded)
    chunked.stop(existing.id)
    // A chunked replace may keep the current blob when the caller already
    // deleted its source (the part-404 recreate over moved bytes).
    var fallback: String?
    if let path = existing.bodyPath, existing.isChunked || existing.legacy,
       path == ChunkedManifestV9.blobName || path.hasPrefix(BodyStaging.blobPrefix) {
      fallback = path
    }
    let staged = try mapStaging {
      try BodyStaging.stage(p.body, parts: p.parts, into: store.dir(p.id), fallbackBlob: fallback)
    }
    var n = existing.replaced(with: p, staged: staged, now: now())
    n.headerGeneration = settings.headerGeneration
    n.state = paused ? .paused : .queued
    try saveOrDiscard(n, staged: staged)
    store.removeV9Manifest(p.id)
    store.sweep(n) // deletes the old body
    publish(n)
    armExpiry(n)
    return (p.id, !paused)
  }

  // MARK: - Helpers

  private func saveOrThrow(_ e: QueueEntry) throws {
    do {
      try store.save(e)
    } catch {
      throw EnqueueError.storage("enqueue: cannot save '\(e.id)': \(error.localizedDescription)")
    }
  }

  /// A failed save deletes the body staged for it, unless that body is an
  /// adopted file the store already owned.
  private func saveOrDiscard(_ e: QueueEntry, staged: StagedBody) throws {
    do {
      try store.save(e)
    } catch {
      if !staged.adopted { try? FileManager.default.removeItem(at: store.fileURL(e.id, staged.relativePath)) }
      throw EnqueueError.storage("enqueue: cannot save '\(e.id)': \(error.localizedDescription)")
    }
  }

  private func mapStaging<T>(_ body: () throws -> T) throws -> T {
    do {
      return try body()
    } catch StagingError.fileMissing(let path) {
      throw EnqueueError.fileMissing(path)
    } catch StagingError.invalid(let message) {
      throw EnqueueError.invalid("enqueue: \(message)")
    } catch StagingError.io(let message) {
      throw EnqueueError.storage("enqueue: \(message)")
    }
  }
}
