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

/// Whether the app is active. Set from the UIApplication notifications, on
/// the main thread, and read on the coordinator queue when a task is
/// created. Lock-guarded and set without a hop, so a task created right after
/// the app resigns is tagged as a background task.
final class AppActivity {
  private let lock = NSLock()
  private var active = false

  var isActive: Bool {
    get { lock.lock(); defer { lock.unlock() }; return active }
    set { lock.lock(); active = newValue; lock.unlock() }
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
  /// How long after the app becomes active the background tasks are
  /// replaced. After a suspension, the daemon's progress and completions
  /// for the tasks reach this process after didBecomeActive. Waiting lets
  /// them land, so a task that already sent its body is not seen as unstarted.
  static let replaceDelayMs = 2_000

  let queue: DispatchQueue
  let store: QueueStore
  let journal: EventJournal
  let taskMap: TaskMap
  let transport: Transport
  weak var sink: EventSink?
  let index = RequestIndex()
  let throttle = ProgressThrottle()
  let activity = AppActivity()

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
  /// Bumped by scheduleReplace: only the latest scheduled replace runs.
  var replaceGeneration = 0
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

  /// pause(scope). `{}` turns the global gate on; `{ keys }` adds the keys
  /// to the paused set. Each live entry that is now paused stops: every task
  /// is cancelled with purpose "pause", so its NSURLErrorCancelled produces
  /// no outcome and no attempt event. A single body restarts from byte 0 on
  /// resume; a chunked upload keeps its accepted parts.
  func pause(_ scope: [String: Any], resolve: @escaping () -> Void,
             reject: @escaping (String, String) -> Void) {
    changeGate(scope, method: "pause", resolve: resolve, reject: reject) { next, keys in
      if let keys { next.pausedKeys.formUnion(keys) } else { next.paused = true }
    }
  }

  /// resume(scope). `{}` turns the global gate off; `{ keys }` removes the
  /// keys from the paused set. An entry another scope still pauses stays
  /// paused.
  func resume(_ scope: [String: Any], resolve: @escaping () -> Void,
              reject: @escaping (String, String) -> Void) {
    changeGate(scope, method: "resume", resolve: resolve, reject: reject) { next, keys in
      if let keys { next.pausedKeys.subtract(keys) } else { next.paused = false }
    }
  }

  /// Parses the scope, saves the changed settings, then moves every entry
  /// whose paused state changed. `change` gets nil keys for the whole queue.
  private func changeGate(_ scope: [String: Any], method: String, resolve: @escaping () -> Void,
                          reject: @escaping (String, String) -> Void,
                          change: @escaping (inout QueueSettings, Set<String>?) -> Void) {
    queue.async {
      var keys: Set<String>?
      if let raw = scope["keys"], !(raw is NSNull) {
        guard let list = raw as? [String] else {
          reject("E_INVALID", "\(method): 'keys' must be a list of strings")
          return
        }
        keys = Set(list)
      }
      var next = self.settings
      change(&next, keys)
      guard next == self.settings || self.saveSettings(next) else {
        reject("E_STORAGE", "\(method): cannot save the queue settings")
        return
      }
      self.applyPauseGates()
      resolve()
    }
  }

  /// Moves every live entry to match the gates. See applyPauseGate.
  func applyPauseGates() {
    for e in index.entries() { applyPauseGate(e) }
  }

  /// An entry is paused when the global gate is on or its key is paused.
  /// A live entry that should be paused stops and moves to 'paused'. A
  /// paused entry that should not be moves back: past expiresAt it settles
  /// expired (a pause does not expire an entry; the resume does), else to
  /// awaiting-auth when it was parked, else to queued and issues.
  func applyPauseGate(_ e: QueueEntry) {
    guard e.isLive, !e.legacy else { return }
    let gated = settings.isPaused(e.key)
    if gated, e.state != .paused {
      cancelTasks(e.id, purpose: .pause)
      chunked.stop(e.id)
      var n = e
      n.state = .paused
      n.nextAttemptAt = nil
      commit(n)
    } else if !gated, e.state == .paused {
      if now() >= e.expiresAt {
        settle(e.id, .expired)
        return
      }
      var n = e
      n.state = e.authParked ? .awaitingAuth : .queued
      commit(n)
      armExpiry(n)
      if n.state == .queued { issue(n.id) }
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

  /// Persisted. Queued and future entries with no wifiOnly of their own use
  /// the session it picks. Such a queued entry whose retry waits in the
  /// daemon moves to the new session; a running task finishes where it
  /// started (session config is fixed). An entry that pins wifiOnly stays.
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
        for e in self.index.entries() where e.state == .queued && !e.isChunked && !e.legacy
          && e.wifiOnly == nil {
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
          n.state = self.settings.isPaused(n.key) ? .paused : .queued
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

  /// The app became active. Tasks created from now on are foreground tasks,
  /// and the background tasks that have not started are replaced after
  /// replaceDelayMs.
  func appBecameActive() {
    activity.isActive = true
    queue.async { self.scheduleReplace() }
  }

  /// Runs replaceBackgroundTasks after replaceDelayMs, while the app is
  /// active. A later call supersedes an earlier one, so a quick trip to the
  /// background and back still waits the full delay after the return.
  func scheduleReplace() {
    guard activity.isActive else { return }
    replaceGeneration += 1
    let generation = replaceGeneration
    schedule(Self.replaceDelayMs) { [weak self] in
      guard let self, generation == self.replaceGeneration else { return }
      self.replaceBackgroundTasks()
    }
  }

  /// The app is about to leave the foreground. Tasks created from now on are
  /// background tasks.
  func appResignedActive() {
    activity.isActive = false
  }

  /// Replaces every live task that was created while the app was not active
  /// and has neither sent a body byte nor had a response. Apple's
  /// isDiscretionary docs: for a transfer started in the background, the
  /// system assumes true and ignores the value set. So such a task can wait
  /// for the system, for example until the device charges. The replacement
  /// is created now, while the app is active, and keeps what is left of a
  /// backoff. Tasks from builds without the tag count as background tasks,
  /// so the first activation after an upgrade replaces them too. Runs
  /// replaceDelayMs after the app becomes active, and after reconcile while
  /// it is.
  ///
  /// The checks can still miss a body the daemon sent but has not reported.
  /// Then the request is sent twice. Each copy carries the same X-Request-Id
  /// for a simple request. Either copy that lands completes the entry.
  func replaceBackgroundTasks() {
    guard ready, activity.isActive else { return }
    // Parts are replaced per entry, so each entry is saved once.
    var parts: [String: [ChunkedCoordinator.Replaced]] = [:]
    for (key, owner) in liveTasks {
      let meta = taskMap.meta(forKey: key)
      guard meta?.foreground != true, meta?.purpose != .superseded, owner.task.isLive,
            owner.task.bytesSent == 0, !owner.task.hasResponse,
            let resolved = TaskOwner.resolve(description: owner.task.taskDescription, meta: meta) else { continue }
      switch resolved {
      case .part(let id, let part, let incarnation):
        parts[id, default: []].append(.init(part: part, incarnation: incarnation, key: key))
      case .request(let id, let generation, let attempt):
        replaceRequestTask(id, generation: generation, attempt: attempt, key: key)
      }
    }
    for (id, replaced) in parts {
      chunked.replace(id, replaced.sorted { $0.part < $1.part })
    }
  }

  /// A simple entry's current attempt, sent again from a new task. The
  /// attempt never sent its body, so it keeps its ordinal and request id.
  /// An empty body is left alone: 0 bytes sent cannot tell whether it went.
  private func replaceRequestTask(_ id: String, generation: Int, attempt: Int, key: String) {
    guard var e = index.entry(id), !e.legacy, !e.isChunked, e.generation == generation,
          e.attempts == attempt, e.state == .queued || e.state == .running, e.totalBytes > 0,
          !settings.isPaused(e.key) else { return }
    let remaining = e.nextAttemptAt.map { Int($0 - now()) }.flatMap { $0 > 0 ? $0 : nil }
    cancelTask(key, purpose: .superseded)
    e.state = .queued
    index.upsert(e)
    issue(id, delayMs: remaining, replacing: true)
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
    cancelTasks(keys: liveTasks.filter { $0.value.id == id }.map { $0.key }, purpose: purpose)
  }

  /// One task, by TaskMap key.
  func cancelTask(_ key: String, purpose: TaskMap.Purpose) {
    cancelTasks(keys: [key], purpose: purpose)
  }

  /// Records why for every task in one TaskMap write, then cancels them.
  /// Keys this process does not hold are skipped.
  func cancelTasks(keys: [String], purpose: TaskMap.Purpose) {
    let held = keys.compactMap { key in liveTasks[key].map { (key: key, owner: $0) } }
    guard !held.isEmpty else { return }
    taskMap.setPurpose(purpose, for: held.map { (key: $0.key, id: $0.owner.id) })
    for (key, owner) in held {
      owner.task.cancel()
      liveTasks[key] = nil
    }
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
