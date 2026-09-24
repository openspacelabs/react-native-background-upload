import Foundation

/// A rejection that crosses to JS with a code.
struct EnqueueError: Error {
  let code: String
  let message: String

  static func storage(_ message: String) -> EnqueueError { EnqueueError(code: "E_STORAGE", message: message) }
  /// Input native cannot send: a bad URL scheme, header, body kind or tiling.
  static func invalid(_ message: String) -> EnqueueError { EnqueueError(code: "E_INVALID", message: message) }
  static func running(_ id: String) -> EnqueueError {
    EnqueueError(code: "E_RUNNING", message: "enqueue: '\(id)' is running; a different body is accepted once it stops")
  }
  static func fileMissing(_ path: String) -> EnqueueError {
    EnqueueError(code: "E_FILE_MISSING", message: "enqueue: file does not exist: \(path)")
  }
}

/// A terminal outcome, before it is journaled.
enum Outcome {
  case completed(RawResponseRecord)
  case error(OutcomeErrorRecord)
  case cancelled(reason: String)

  static let expired = Outcome.error(OutcomeErrorRecord(
    errorKind: "expired", message: "expiresAt passed before the request completed"))

  static func fileError(_ message: String, partIndex: Int? = nil) -> Outcome {
    .error(OutcomeErrorRecord(errorKind: "file", message: message, partIndex: partIndex))
  }
}

/// The queue's owner. It holds the store, the settings, the in-memory index,
/// the journal and the task map, schedules every entry, and makes every
/// emit. Every state change runs on one serial queue, which the chunked
/// coordinator shares. Rules it keeps:
/// - Write-ahead: an entry, its body and each attempt ordinal are on disk
///   before a task exists.
/// - Journal before emit: a terminal is a journal file before any emit.
/// - Store first, then index, then the `state` event.
/// - The module queue never waits on this queue, except the synchronous
///   delegate hops, which never wait on JS.
///
/// The files next to this one extend it: enqueue and the same-id rules,
/// simple attempts, outcomes (settle, ack, forget), and relaunch
/// reconciliation.
final class QueueCoordinator {
  static let queueLabel = "ai.openspace.rnbgupload.queue"

  let queue: DispatchQueue
  let store: QueueStore
  let journal: EventJournal
  let taskMap: TaskMap
  let transport: Transport
  weak var sink: EventSink?
  let index = RequestIndex()
  let throttle = ProgressThrottle()

  let now: () -> Double
  let random: () -> Double
  /// Runs `block` on `queue` after `delayMs`. Injected so tests control time.
  let schedule: (_ delayMs: Int, _ block: @escaping () -> Void) -> Void

  var settings: QueueSettings
  /// false until the first reconcile has matched the daemon's tasks to the
  /// entries. Until then nothing issues: a task made now could duplicate one
  /// the daemon already holds. Reconcile issues whatever waited.
  var ready = false
  /// Every task this process created or adopted, by TaskMap key.
  var liveTasks: [String: (id: String, task: UploadTask)] = [:]
  var expiryTokens: [String: UUID] = [:]
  /// Open grace waits (a completion that may still replay), by name. While
  /// any is open, `afterGrace` holds the background completion handler
  /// release.
  var graces: [String: Grace] = [:]
  var afterGrace: [() -> Void] = []
  /// Settle outcomes (never a user cancel) whose journal write failed, by
  /// eventId. They were emitted live; a timer retries the write while the
  /// entry still names them.
  var pendingJournal: [String: JournaledEvent] = [:]
  lazy var chunked = ChunkedCoordinator(self)

  init(store: QueueStore, journal: EventJournal, taskMap: TaskMap, transport: Transport,
       sink: EventSink?, queue: DispatchQueue = DispatchQueue(label: QueueCoordinator.queueLabel),
       now: @escaping () -> Double = { Date().timeIntervalSince1970 * 1000 },
       random: @escaping () -> Double = { Double.random(in: 0..<1) },
       schedule: ((Int, @escaping () -> Void) -> Void)? = nil) {
    self.queue = queue
    self.store = store
    self.journal = journal
    self.taskMap = taskMap
    self.transport = transport
    self.sink = sink
    self.now = now
    self.random = random
    self.schedule = schedule ?? { ms, block in
      queue.asyncAfter(deadline: .now() + .milliseconds(ms), execute: block)
    }
    settings = store.loadSettings()
    finishSetAsideForgets()
    // Synchronous, before any session exists, so getRequests() is warm by
    // the time JS can call it, legacy rows included.
    index.load(store.all())
    importLegacyIfNeeded()
  }

  // MARK: - Module methods

  func configure(_ options: [String: Any]) {
    queue.async {
      var next = self.settings
      next.apply(configure: options)
      _ = self.saveSettings(next)
    }
  }

  func enqueue(_ raw: [String: Any], resolve: @escaping (String) -> Void,
               reject: @escaping (String, String) -> Void) {
    queue.async {
      let result: (id: String, issue: Bool)
      do {
        result = try self.enqueueLocked(raw)
      } catch let e as EnqueueError {
        reject(e.code, e.message)
        return
      } catch {
        reject("E_STORAGE", error.localizedDescription)
        return
      }
      resolve(result.id)
      if result.issue { self.issue(result.id) }
    }
  }

  /// Whole-queue pause. Cancels every task with purpose "pause", so its
  /// NSURLErrorCancelled produces no outcome and no attempt event. A single
  /// body restarts from byte 0 on resume; a chunked upload keeps its
  /// accepted parts.
  func pause(resolve: @escaping () -> Void, reject: @escaping (String, String) -> Void) {
    queue.async {
      var next = self.settings
      next.paused = true
      guard self.saveSettings(next) else {
        reject("E_STORAGE", "pause: cannot save the queue settings")
        return
      }
      for e in self.index.entries() where !e.legacy
        && [.queued, .running, .awaitingAuth].contains(e.state) {
        self.cancelTasks(e.id, purpose: .pause)
        self.chunked.stop(e.id)
        var n = e
        n.state = .paused
        n.nextAttemptAt = nil
        self.commit(n)
      }
      resolve()
    }
  }

  func resume(resolve: @escaping () -> Void, reject: @escaping (String, String) -> Void) {
    queue.async {
      var next = self.settings
      next.paused = false
      guard self.saveSettings(next) else {
        reject("E_STORAGE", "resume: cannot save the queue settings")
        return
      }
      for e in self.index.entries() where e.state == .paused && !e.legacy {
        // A pause does not expire an entry; the resume does.
        if self.now() >= e.expiresAt {
          self.settle(e.id, .expired)
          continue
        }
        var n = e
        n.state = e.authParked ? .awaitingAuth : .queued
        self.commit(n)
        if n.state == .queued { self.issue(n.id) }
      }
      resolve()
    }
  }

  /// Live entry: journal 'cancelled' (user); forgotten after its ack.
  /// Settled entry: forgotten now, with its unacked outcomes. Unknown: no-op.
  /// When the journal or the store cannot be written, rejects E_STORAGE and
  /// changes nothing: the entry keeps running (or stays settled), and JS may
  /// call again.
  func cancel(_ id: String, resolve: @escaping () -> Void, reject: @escaping (String, String) -> Void) {
    queue.async {
      guard let e = self.index.entry(id) else { return resolve() }
      if e.isLive && !e.legacy {
        guard self.settle(id, .cancelled(reason: "user"), requireJournal: true) else {
          return reject("E_STORAGE", "cancel: cannot journal the outcome of '\(id)'; nothing changed")
        }
      } else {
        do {
          try self.forgetWithEvents(id)
        } catch {
          return reject("E_STORAGE", "cancel: cannot delete '\(id)': \(error.localizedDescription)")
        }
      }
      resolve()
    }
  }

  /// Persisted. Queued and future entries use the session it picks. A queued
  /// entry whose retry waits in the daemon moves to the new session; a
  /// running task finishes where it started (session config is fixed).
  func setWifiOnly(_ enabled: Bool, resolve: @escaping () -> Void,
                   reject: @escaping (String, String) -> Void) {
    queue.async {
      var next = self.settings
      let changed = next.wifiOnly != enabled
      next.wifiOnly = enabled
      guard self.saveSettings(next) else {
        reject("E_STORAGE", "setWifiOnly: cannot save the queue settings")
        return
      }
      if changed && self.ready {
        for e in self.index.entries() where e.state == .queued && !e.isChunked && !e.legacy {
          let remaining = e.nextAttemptAt.map { Int($0 - self.now()) }
          self.cancelTasks(e.id, purpose: .superseded)
          // The waiting attempt never ran: keep its ordinal and request id.
          self.issue(e.id, delayMs: remaining.flatMap { $0 > 0 ? $0 : nil }, advanceAttempt: false)
        }
      }
      resolve()
    }
  }

  /// Merges the patch into every entry not yet forgotten (names match
  /// without regard to case) and bumps the header generation. Parked entries
  /// go back to queued and issue. Resolves after the loop.
  func updateHeaders(_ patch: [String: Any], resolve: @escaping () -> Void,
                     reject: @escaping (String, String) -> Void) {
    queue.async {
      let headers: [String: String]
      do {
        headers = try EnqueueParser.headers(patch, field: "patch")
      } catch {
        reject("E_INVALID", "updateHeaders: \(error.localizedDescription)")
        return
      }
      var next = self.settings
      next.headerGeneration += 1
      guard self.saveSettings(next) else {
        reject("E_STORAGE", "updateHeaders: cannot save the queue settings")
        return
      }
      for e in self.index.entries() where !e.legacy {
        var n = e
        n.headers = HeaderMerge.merge(e.headers, headers)
        // A part's own header of the same name would win over the entry's.
        for i in n.parts.indices {
          n.parts[i].headers = HeaderMerge.replaceExisting(n.parts[i].headers, headers)
        }
        n.headerGeneration = self.settings.headerGeneration
        if n.state == .awaitingAuth {
          n.authParked = false
          n.state = self.settings.paused ? .paused : .queued
          self.commit(n)
          self.issue(n.id)
        } else {
          n.authParked = false
          self.commit(n, emit: false)
        }
      }
      resolve()
    }
  }

  /// Synchronous: the in-memory index, under its lock. Called from the JS
  /// thread; never touches this queue or the disk.
  func rows() -> [[String: Any]] {
    index.rows()
  }

  /// Every unacked outcome, oldest first. Each return counts as a delivery.
  func unacknowledgedEvents(resolve: @escaping ([[String: Any]]) -> Void) {
    queue.async {
      // JS drains after it attaches its listener. From here on, a settle is
      // emitted live.
      self.sink?.listenerReady()
      resolve(self.unacknowledgedLocked().map(\.bridged))
    }
  }

  /// Removes the outcomes. An acked 'completed' or 'cancelled' of the
  /// entry's current generation forgets the entry: row and bytes. An 'error'
  /// keeps both until cancel() or a same-id enqueue. Unknown ids are ignored.
  func ack(_ eventIds: [String], resolve: @escaping () -> Void) {
    queue.async {
      for eventId in eventIds { self.ackLocked(eventId) }
      resolve()
    }
  }

  // MARK: - Transitions (on `queue`)

  /// Store, then index, then the `state` event. A failed save is logged and
  /// the index still moves: the in-memory state is what runs, and the next
  /// save of this entry writes it. Returns whether the save landed.
  @discardableResult
  func commit(_ entry: QueueEntry, emit: Bool = true) -> Bool {
    let (e, saved) = save(entry)
    publish(e, emit: emit)
    return saved
  }

  /// Write-ahead for an attempt. Publishes only when the save landed, so a
  /// failed save leaves the index at what the disk holds. On false the caller
  /// creates no task.
  func commitAhead(_ entry: QueueEntry, emit: Bool = true) -> Bool {
    let (e, saved) = save(entry)
    if saved { publish(e, emit: emit) }
    return saved
  }

  private func save(_ entry: QueueEntry) -> (QueueEntry, Bool) {
    var e = entry
    e.updatedAt = now()
    do {
      try store.save(e)
      return (e, true)
    } catch {
      NSLog("[RNFileUploader] cannot save entry \(e.id): \(error.localizedDescription)")
      return (e, false)
    }
  }

  /// Index, then the `state` event, for an entry already saved.
  func publish(_ entry: QueueEntry, emit: Bool = true) {
    index.upsert(entry)
    guard emit, let row = index.row(entry.id) else { return }
    sink?.emitState(row)
  }

  /// Records why, then cancels every task this process holds for `id`.
  func cancelTasks(_ id: String, purpose: TaskMap.Purpose) {
    for (key, owner) in liveTasks where owner.id == id {
      cancelTask(key, purpose: purpose)
    }
  }

  /// One task, by TaskMap key.
  func cancelTask(_ key: String, purpose: TaskMap.Purpose) {
    guard let owner = liveTasks[key] else { return }
    taskMap.setPurpose(purpose, forKey: key, id: owner.id)
    owner.task.cancel()
    liveTasks[key] = nil
  }

  func emitProgress(_ id: String, sent: Int64, total: Int64) {
    sink?.emitProgress(["id": id, "bytesSent": sent, "totalBytes": total])
  }

  func policy(_ e: QueueEntry) -> RetryPolicy {
    RetryPolicy.resolve([settings.retry, e.retry])
  }

  /// The request for one attempt: the entry's method and headers, part
  /// headers over them, the staged body's Content-Type when the headers set
  /// none (a multipart body always uses its own), and a fresh X-Request-Id.
  func buildRequest(_ e: QueueEntry, url: URL, requestId: String,
                    partHeaders: [String: String] = [:]) -> URLRequest {
    var request = URLRequest(url: url)
    request.httpMethod = e.method
    let headers = HeaderMerge.merge(e.headers, partHeaders)
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
    if let contentType = e.bodyContentType,
       e.forceContentType || HeaderMerge.value("Content-Type", in: headers) == nil {
      request.setValue(contentType, forHTTPHeaderField: "Content-Type")
    }
    request.setValue(requestId, forHTTPHeaderField: "X-Request-Id")
    return request
  }

  /// One HTTP attempt that ended with a response or a transport error. A
  /// cancel of any kind is not an attempt and never gets here.
  func emitAttempt(_ e: QueueEntry, requestId: String?, attempt: Int, completion c: TaskCompletion,
                   partIndex: Int?, accepted: Bool) {
    let url = c.url ?? partIndex.flatMap { e.parts.indices.contains($0) ? e.parts[$0].url : nil }
      ?? e.url ?? ""
    sink?.emitAttempt(AttemptEvent.build(AttemptEvent.Input(
      id: e.id, key: e.key, requestId: requestId ?? "", attempt: attempt, url: url,
      method: e.method, partIndex: partIndex, statusCode: c.statusCode, headers: c.headers,
      body: c.body, error: c.error, accepted: accepted, at: now())))
  }

  // MARK: - Expiry

  /// One in-process timer per live entry at expiresAt + 100 ms. A later arm
  /// replaces the token, so a resume that moved expiresAt makes the old timer
  /// a no-op. Long waits re-arm daily. It settles a queued or awaiting-auth
  /// entry. It leaves a paused one to resume(), and a running one to the
  /// result of its attempt: a real response keeps its own error kind, and a
  /// transient one becomes 'expired' (scheduleRetry, retryPart). An entry
  /// that parks after that is armed again by park().
  func armExpiry(_ e: QueueEntry) {
    guard e.isLive, !e.legacy else { return }
    let token = UUID()
    expiryTokens[e.id] = token
    let delay = Int(min(max(e.expiresAt - now(), 0) + 100, 86_400_000))
    schedule(delay) { [weak self] in
      guard let self, self.expiryTokens[e.id] == token else { return }
      self.expiryTokens[e.id] = nil
      guard let current = self.index.entry(e.id), current.isLive, !current.legacy else { return }
      guard self.now() >= current.expiresAt else {
        self.armExpiry(current)
        return
      }
      switch current.state {
      case .paused, .running: return
      default: self.settle(current.id, .expired)
      }
    }
  }

  func disarmExpiry(_ id: String) {
    expiryTokens[id] = nil
  }

  // Assigns only when the write landed.
  func saveSettings(_ next: QueueSettings) -> Bool {
    do {
      try store.saveSettings(next)
      settings = next
      return true
    } catch {
      NSLog("[RNFileUploader] cannot save settings: \(error.localizedDescription)")
      return false
    }
  }
}
