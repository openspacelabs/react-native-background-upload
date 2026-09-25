import Foundation

// Simple (one-body) entries: one task per attempt, and the URLSession
// delegate hooks. A chunked entry routes to ChunkedCoordinator from each hook.
extension QueueCoordinator {

  /// Starts the next attempt of a queued entry. With `delayMs` the task is
  /// created now with earliestBeginDate, so nsurlsessiond starts it on time
  /// whether the app lives or not; the row stays queued with nextAttemptAt.
  /// Write-ahead: the attempt ordinal and request id are saved before the
  /// task exists. A failed save creates no task and tries again later.
  ///
  /// `advanceAttempt: false` re-creates a waiting attempt that never ran (a
  /// session move, or a delayed task that never reached the daemon). It keeps
  /// the ordinal and request id. It applies only while the entry holds such
  /// an attempt (nextAttemptAt set); otherwise a new attempt is minted.
  func issue(_ id: String, delayMs: Int? = nil, advanceAttempt: Bool = true) {
    guard var e = index.entry(id), !e.legacy, e.state == .queued,
          !settings.isPaused(e.key) else { return }
    let t = now()
    if t >= e.expiresAt {
      settle(id, .expired)
      return
    }
    guard ready else {
      // Reconcile has not matched the daemon's tasks yet. Keep the queued
      // state and the wait on disk; reconcile issues it. A wait follows an
      // attempt that ran (a completion that landed before reconcile), so
      // mint its successor now: reconcile keeps a waiting attempt's ordinal,
      // and must not reuse the one that ran.
      if let delayMs {
        if advanceAttempt {
          e.attempts += 1
          e.lastRequestId = UUID().uuidString
        }
        e.nextAttemptAt = t + Double(delayMs)
      }
      commit(e)
      return
    }
    if e.isChunked {
      chunked.start(id)
      return
    }
    guard let body = store.bodyURL(e), FileIO.exists(body) else {
      settle(id, .fileError("the staged body is missing"))
      return
    }
    guard let urlString = e.url, let url = URL(string: urlString) else {
      settle(id, .error(OutcomeErrorRecord(errorKind: "unknown", message: "the url is not valid")))
      return
    }

    let before = e
    let reuse = !advanceAttempt && e.nextAttemptAt != nil && e.attempts > 0 && e.lastRequestId != nil
    let requestId = reuse ? e.lastRequestId! : UUID().uuidString
    if !reuse {
      e.attempts += 1
      e.bytesSent = 0 // a new attempt sends from byte 0
    }
    e.lastRequestId = requestId
    e.lastUrl = urlString
    e.lastPartIndex = nil
    let beginAt = delayMs.map { t + Double($0) }
    if let beginAt {
      e.nextAttemptAt = beginAt
    } else {
      e.state = .running
      e.nextAttemptAt = nil
    }
    guard commitAhead(e) else {
      deferIssue(before, delayMs: delayMs)
      return
    }

    let meta = TaskMap.Meta(
      id: id, attempt: e.attempts, requestId: requestId,
      headerGeneration: settings.headerGeneration, generation: e.generation, purpose: .attempt)
    let task = transport.upload(
      buildRequest(e, url: url, requestId: requestId), fromFile: body,
      wifiOnly: settings.wifiOnly(e.wifiOnly),
      description: ChunkedEngine.taskDescription(id: id, attempt: e.attempts, generation: e.generation),
      beginAt: beginAt.map { Date(timeIntervalSince1970: $0 / 1000) },
      beforeResume: { key in self.taskMap.set(meta, forKey: key) })
    liveTasks[task.key] = (id, task)
    throttle.reset(id)
  }

  /// The attempt could not be written ahead (disk full, protected data).
  /// The entry stays as the disk has it, queued in memory, with no task.
  /// Issue again after the wait it asked for, or a backoff, whichever is
  /// longer, unless something else moved the entry first.
  private func deferIssue(_ e: QueueEntry, delayMs: Int?) {
    let backoff = RetryClassifier.backoffMs(attempt: max(e.attempts, 1), policy: policy(e), random: random)
    let generation = e.generation
    let attempts = e.attempts
    schedule(max(delayMs ?? 0, backoff)) { [weak self] in
      guard let self, let current = self.index.entry(e.id), current.generation == generation,
            current.attempts == attempts else { return }
      self.issue(e.id)
    }
  }

  // MARK: - Delegate hooks

  /// didCompleteWithError. Synchronous, so the journal write for a terminal
  /// lands before the delegate callback returns (a background wake may
  /// suspend the app right after).
  func taskCompleted(_ c: TaskCompletion) {
    queue.sync { taskCompletedLocked(c) }
  }

  func taskCompletedLocked(_ c: TaskCompletion) {
    let meta = taskMap.meta(forKey: c.key)
    taskMap.removeKey(c.key)
    liveTasks[c.key] = nil
    // Runs after the completion moved the entry, so a grace that ends here
    // sees the new state.
    defer { replayHandled(c.key) }
    guard let owner = TaskOwner.resolve(description: c.description, meta: meta) else { return }
    if case .part(let id, let part, let incarnation) = owner {
      chunked.partCompleted(id: id, part: part, incarnation: incarnation, key: c.key, meta: meta,
                            completion: c)
      return
    }
    // Only the current attempt of the current generation may drive the entry.
    guard case .request(let id, let generation, let attempt) = owner,
          var e = index.entry(id), !e.legacy, !e.isChunked,
          e.generation == generation, e.attempts == attempt else { return }

    let cancelled = RetryClassifier.isCancellation(c.error)
    if cancelled, meta?.purpose == .pause || meta?.purpose == .superseded { return }
    let accepted = c.error == nil
      && c.statusCode.map { UploadOutcome.isAccepted($0, body: c.body, accept: e.accept) } == true
    // A replaced task that finished before its cancel took effect. Its
    // replacement drives the entry, unless this one landed.
    if meta?.purpose == .superseded && !accepted { return }
    // A cancel the library did not ask for (the system, a force-quit) is not
    // an attempt: no event. It retries below.
    if !cancelled {
      emitAttempt(e, requestId: meta?.requestId ?? e.lastRequestId, attempt: attempt, completion: c,
                  partIndex: nil, accepted: accepted)
    }

    guard e.state == .running || e.state == .queued else {
      // A pause raced this completion. An accepted response did land, so
      // settle it: a resume must not send it twice.
      if accepted && e.state == .paused { settle(id, .completed(response(c))) }
      return
    }
    // A system cancel is a transient failure, never a 'cancelled' outcome.
    if cancelled {
      scheduleRetry(e)
      return
    }
    let fileExists = store.bodyURL(e).map(FileIO.exists) ?? false
    let verdict = RetryClassifier.classify(RetryClassifier.Input(
      statusCode: c.statusCode, body: c.body, error: c.error, accept: e.accept, policy: policy(e),
      fileExists: fileExists, now: now(), expiresAt: e.expiresAt))
    switch verdict {
    case .accepted:
      settle(id, .completed(response(c)))
    case .transient:
      scheduleRetry(e)
    case .auth:
      if let g = meta?.headerGeneration, g < settings.headerGeneration {
        // Issued under older headers. updateHeaders() already merged the new
        // ones into the entry, so re-issue at once.
        e.state = .queued
        index.upsert(e)
        issue(id)
      } else {
        park(e)
      }
    case .terminalHttp:
      settle(id, .error(OutcomeErrorRecord(
        errorKind: "http", message: "HTTP \(c.statusCode ?? 0)", response: response(c))))
    case .fileMissing:
      settle(id, .fileError("the staged body is missing"))
    case .expired:
      settle(id, .expired)
    }
  }

  /// willBeginDelayedRequest: a delayed retry is about to start while the
  /// app is alive. Returns the request rebuilt from the entry's current
  /// headers (an updateHeaders during the backoff reaches the retry), or nil
  /// to cancel a task whose entry moved on.
  func taskWillBegin(key: String, description: String?) -> URLRequest? {
    queue.sync {
      let meta = taskMap.meta(forKey: key)
      guard let owner = TaskOwner.resolve(description: description, meta: meta) else {
        taskMap.setPurpose(.superseded, forKey: key, id: "")
        return nil
      }
      if case .part(let id, let part, let incarnation) = owner {
        return chunked.partWillBegin(id: id, part: part, incarnation: incarnation, key: key, meta: meta)
      }
      // A task the library already replaced (a session move keeps the
      // attempt ordinal, so the ordinal alone cannot tell them apart).
      guard meta?.purpose != .superseded,
            case .request(let id, let generation, let attempt) = owner,
            var e = index.entry(id), !e.isChunked, e.generation == generation,
            e.attempts == attempt, e.state == .queued || e.state == .running, !settings.isPaused(e.key),
            let url = e.url.flatMap(URL.init(string:)) else {
        taskMap.setPurpose(.superseded, forKey: key, id: owner.id)
        liveTasks[key] = nil
        return nil
      }
      if e.state == .queued {
        e.state = .running
        e.nextAttemptAt = nil
        commit(e)
      }
      taskMap.setHeaderGeneration(settings.headerGeneration, forKey: key)
      return buildRequest(e, url: url, requestId: meta?.requestId ?? e.lastRequestId ?? UUID().uuidString)
    }
  }

  /// didSendBodyData. The throttle runs here, on the delegate queue, before
  /// the hop. The first event after an issue always passes, which also moves
  /// a delayed retry that began while the app was dead to running.
  func taskProgress(key: String, description: String?, sent: Int64, expected: Int64) {
    let meta = taskMap.meta(forKey: key)
    guard meta?.purpose != .superseded, let owner = TaskOwner.resolve(description: description, meta: meta),
          throttle.shouldEmit(owner.id, now: now()) else { return }
    queue.async {
      switch owner {
      case .part(let id, let part, let incarnation):
        self.chunked.partProgress(id: id, part: part, incarnation: incarnation, sent: sent)
      case .request(let id, let generation, let attempt):
        guard var e = self.index.entry(id), e.generation == generation, e.attempts == attempt,
              e.state == .queued || e.state == .running else { return }
        if e.state == .queued {
          e.state = .running
          e.nextAttemptAt = nil
          self.commit(e)
        }
        self.index.setBytes(id, sent)
        self.emitProgress(id, sent: sent, total: expected > 0 ? expected : e.totalBytes)
      }
    }
  }

  // MARK: - Retry and auth

  /// Backoff from the attempt count. A wait that would pass expiresAt
  /// settles 'expired' now instead of scheduling.
  func scheduleRetry(_ e: QueueEntry) {
    let delay = RetryClassifier.backoffMs(attempt: max(e.attempts, 1), policy: policy(e), random: random)
    if now() + Double(delay) >= e.expiresAt {
      settle(e.id, .expired)
      return
    }
    var n = e
    n.state = .queued
    index.upsert(n)
    issue(n.id, delayMs: delay)
  }

  /// awaiting-auth: no task, one `state` event. updateHeaders() resumes it.
  func park(_ e: QueueEntry) {
    cancelTasks(e.id, purpose: .superseded)
    chunked.stop(e.id)
    var n = e
    n.state = .awaitingAuth
    n.authParked = true
    n.nextAttemptAt = nil
    commit(n)
    // The timer may have passed while the entry ran.
    armExpiry(n)
  }

  func response(_ c: TaskCompletion) -> RawResponseRecord {
    RawResponseRecord(status: c.statusCode, headers: c.headers, body: c.body ?? "",
                      bodyTruncated: c.bodyTruncated)
  }
}
