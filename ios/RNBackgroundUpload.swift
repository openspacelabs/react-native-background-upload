import Foundation
import React

// Live events destined for JS. The TurboModule shell (RNFileUploader.mm) adopts
// this and forwards to the codegen-generated emitters. The delegate is nil
// whenever JS isn't around (headless relaunch, before the module is created,
// after a reload tears the old one down) — terminal outcomes are journaled
// before we ever get here, so dropping a live event is always safe.
@objc public protocol RNFileUploaderEventDelegate {
  func emitProgress(_ body: [String: Any])
  func emitCompleted(_ body: [String: Any])
  func emitError(_ body: [String: Any])
  func emitCancelled(_ body: [String: Any])
}

// Background HTTP file uploader (iOS). Uploads run on a background URLSession so
// they continue while the app is suspended and complete/relaunch when terminated
// by the system. Terminal outcomes are journaled before being emitted, so JS can
// recover them even if it was dead when they fired.
//
// State that must be consistent for the whole process is STATIC: the background
// sessions, the in-flight response buffers, the user-cancel set, and the event
// delegate. The TurboModule instance comes and goes with the JS runtime while the
// URLSession delegate stays pinned to this object, so keeping that state static
// (rather than on the module) is what keeps cancel attribution and response
// assembly correct across a reload — and guarantees we never create two
// background sessions with the same identifier.
@objc(RNBackgroundUpload)
public class RNBackgroundUpload: NSObject, URLSessionDataDelegate {

  // The instance that owns the URLSession delegate callbacks. Created on first
  // access — by the TurboModule, or by the AppDelegate's
  // handleEventsForBackgroundURLSession hook, whichever happens first. That
  // second path is load-bearing: on a system relaunch there may be no JS at all,
  // and touching `shared` is what recreates the sessions so nsurlsessiond can
  // deliver the delegate events it has queued for us.
  @objc public static let shared = RNBackgroundUpload()

  private static let backgroundSessionId = "ReactNativeBackgroundUpload"
  private static let wifiOnlySessionId = "ReactNativeBackgroundUpload_WifiOnly"
  private static let progressThrottle: TimeInterval = 0.5 // seconds, per upload

  private static let lock = NSLock()
  private static var responsesData: [String: NSMutableData] = [:] // sessionId:taskId -> body
  private static var lastProgressAt: [String: TimeInterval] = [:] // uploadId -> time
  private static var userCancelledIds = Set<String>()
  // The ids that removeUpload is releasing now. The cancellation of their
  // tasks is an explicit release, not an outcome that the consumer awaits.
  // Thus no terminal event is journaled. This matches Android, whose
  // removeUpload cancels work with no user-cancel mark.
  private static var removedIds = Set<String>()
  // The consumer-supplied ids whose check-and-create is in flight, mapped to
  // the resolves of the concurrent same-id calls. The existence check
  // enumerates the session tasks asynchronously. Without this claim, two
  // concurrent calls could both see "no task" and enqueue duplicates. The id
  // is claimed synchronously, under `lock`, BEFORE the enumeration is
  // dispatched. The map entry drains when the first caller's create-or-find
  // lands.
  private static var creationsInFlight: [String: [RCTPromiseResolveBlock]] = [:]

  private static var backgroundSession: URLSession?
  private static var wifiOnlySession: URLSession?

  // Deliberately its own lock, not `lock`: creating `shared` acquires `lock` to
  // build the sessions, so guarding the delegate with the same lock would risk a
  // deadlock between "ensure shared exists" and "set the delegate".
  private static let delegateLock = NSLock()
  private static weak var eventDelegate: RNFileUploaderEventDelegate?

  // AppDelegate stores the system-provided completion handler here (per session
  // id) so the app can be relaunched to finish uploads after termination.
  private static let bgHandlerLock = NSLock()
  private static var bgCompletionHandlers: [String: () -> Void] = [:]
  // Relaunch ordering: while the chunked coordinator reconciles (deferrals
  // > 0), urlSessionDidFinishEvents must NOT hand the system its completion
  // handler. The system could suspend the app before the post-reconcile
  // refill enqueues a new part task. That would leave zero daemon tasks and
  // no future wake. A session that finishes its events in that window parks
  // its id here. The release drains it.
  private static var bgHandlerDeferrals = 0
  private static var bgSessionsAwaitingDrain: Set<String> = []

  // Owns the chunked-upload window and the manifests. It is implicitly
  // unwrapped only because it needs `self` (for the sessions) and is assigned
  // before init returns. It is never nil after that.
  private var chunked: ChunkedCoordinator!

  public override init() {
    super.init()
    // Recreate the sessions as early as possible so delegate events queued by
    // nsurlsessiond from a previous launch are delivered to this process.
    _ = session(wifiOnly: false)
    _ = session(wifiOnly: true)
    chunked = ChunkedCoordinator(uploader: self)
    // Relaunch reconciliation: match the daemon's surviving tasks against
    // the stored manifests, and refill each upload's window. It runs on the
    // coordinator queue. Thus nothing here re-enters the initialization of
    // `shared`.
    chunked.reconcileAll()
  }

  // MARK: - Event delegate

  @objc public static func setEventDelegate(_ delegate: RNFileUploaderEventDelegate) {
    // Force the singleton (and therefore the sessions) into existence before
    // taking the lock — see the note on delegateLock.
    _ = shared
    delegateLock.lock()
    eventDelegate = delegate
    delegateLock.unlock()
  }

  /// Deregisters a delegate, but only if it is still the registered one.
  ///
  /// React Native dispatches `invalidate` asynchronously and gives up waiting
  /// after 10s, so a slow call can let the replacement module register itself
  /// before the outgoing module's `invalidate` actually runs. Clearing
  /// unconditionally there would null out the live delegate and silently stop
  /// every event for the rest of the process.
  @objc public static func clearEventDelegate(_ delegate: RNFileUploaderEventDelegate) {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    if eventDelegate === delegate { eventDelegate = nil }
  }

  private static var currentDelegate: RNFileUploaderEventDelegate? {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    return eventDelegate
  }

  // Journal-before-emit, the library's one terminal-event path. The write is
  // durable. The emit is best-effort, because JS can be dead. The
  // simple-upload delegate handling and the chunked coordinator share it.
  static func journalAndEmit(_ event: JournaledEvent) {
    EventJournal.append(event)
    emitEvent(event)
  }

  /// Emits WITHOUT a journal write. Use it to deliver again an event that is
  /// already in the journal (a resume of a finished-but-unacked upload).
  static func emitEvent(_ event: JournaledEvent) {
    let body = event.bridged
    let delegate = currentDelegate
    switch event.type {
    case "completed": delegate?.emitCompleted(body)
    case "cancelled": delegate?.emitCancelled(body)
    default: delegate?.emitError(body)
    }
  }

  static func emitProgress(id: String, progress: Float) {
    currentDelegate?.emitProgress(["id": id, "progress": progress])
  }

  // MARK: - Sessions

  // Internal, not private: the chunked coordinator enqueues part tasks on the
  // same two sessions.
  func session(wifiOnly: Bool) -> URLSession {
    RNBackgroundUpload.lock.lock()
    defer { RNBackgroundUpload.lock.unlock() }
    if wifiOnly {
      if let s = RNBackgroundUpload.wifiOnlySession { return s }
      let s = makeSession(identifier: RNBackgroundUpload.wifiOnlySessionId, wifiOnly: true)
      RNBackgroundUpload.wifiOnlySession = s
      return s
    } else {
      if let s = RNBackgroundUpload.backgroundSession { return s }
      let s = makeSession(identifier: RNBackgroundUpload.backgroundSessionId, wifiOnly: false)
      RNBackgroundUpload.backgroundSession = s
      return s
    }
  }

  // Session configuration is load-bearing and carried over verbatim from the
  // original Obj-C. Config must be set before the session is created (URLSession
  // copies it). Background upload tasks require uploadTask(with:fromFile:).
  private func makeSession(identifier: String, wifiOnly: Bool) -> URLSession {
    let config = URLSessionConfiguration.background(withIdentifier: identifier)
    config.isDiscretionary = false
    // A per-session, connection-level backstop for the design's library-wide
    // transmission cap of 4. The request-level control is the chunked window.
    // This limit mostly bounds piles of simple uploads over HTTP/1.1.
    config.httpMaximumConnectionsPerHost = 4
    config.waitsForConnectivity = true
    config.allowsCellularAccess = !wifiOnly
    config.allowsConstrainedNetworkAccess = !wifiOnly
    config.allowsExpensiveNetworkAccess = !wifiOnly
    return URLSession(configuration: config, delegate: self, delegateQueue: nil)
  }

  private func taskMapKey(_ session: URLSession, _ task: URLSessionTask) -> String {
    TaskMap.key(session, task)
  }

  // taskDescription is the primary id; the persisted map is the durable fallback.
  // A chunked part task's description encodes (uploadId, partIndex). This
  // returns the uploadId in both cases. Thus id matching works uniformly.
  private func uploadId(_ session: URLSession, _ task: URLSessionTask) -> String {
    if let ref = ChunkedCoordinator.partRef(session, task) { return ref.id }
    return task.taskDescription ?? TaskMap.meta(forKey: taskMapKey(session, task))?.id ?? "unknown"
  }

  private func acceptRules(_ session: URLSession, _ task: URLSessionTask) -> [UploadOutcome.AcceptRule] {
    TaskMap.meta(forKey: taskMapKey(session, task))?.accept ?? []
  }

  private var activeSessions: [URLSession] {
    [RNBackgroundUpload.backgroundSession, RNBackgroundUpload.wifiOnlySession].compactMap { $0 }
  }

  // MARK: - Exported methods (called from the TurboModule shell)

  @objc(startUpload:resolve:reject:)
  public func startUpload(_ options: [String: Any],
                          resolve: @escaping RCTPromiseResolveBlock,
                          reject: @escaping RCTPromiseRejectBlock) {
    guard let urlString = options["url"] as? String, let path = options["path"] as? String else {
      reject("RN Uploader", "Missing 'url' or 'path'", nil); return
    }
    guard let requestUrl = URL(string: urlString) else {
      reject("RN Uploader", "URL not compliant with RFC 2396", nil); return
    }
    let type = (options["type"] as? String) ?? "raw"
    if type != "raw" {
      reject("RN Uploader", "Only type: 'raw' is supported", nil); return
    }

    var request = URLRequest(url: requestUrl)
    request.httpMethod = (options["method"] as? String) ?? "POST"
    if let headers = options["headers"] as? [String: Any] {
      for (key, value) in headers {
        // Only strings and numbers become headers. The original Obj-C skipped
        // anything else, and interpolating instead would put "<null>" (or a
        // Swift struct description) on the wire for a null/object value —
        // silently corrupting e.g. an Authorization header rather than omitting it.
        if let s = value as? String {
          request.setValue(s, forHTTPHeaderField: key)
        } else if let n = value as? NSNumber {
          request.setValue(n.stringValue, forHTTPHeaderField: key)
        }
      }
    }

    let wifiOnly = (options["wifiOnly"] as? Bool) ?? false
    let accept = UploadOutcome.parseAcceptRules(options["accept"])
    let uploadId = (options["id"] as? String) ?? UUID().uuidString
    let fileURL = URL(string: path) ?? URL(fileURLWithPath: path)

    let session = self.session(wifiOnly: wifiOnly)
    let startNew = {
      let task = session.uploadTask(with: request, fromFile: fileURL)
      task.taskDescription = uploadId
      TaskMap.set(TaskMap.Meta(id: uploadId, accept: accept, partIndex: nil),
                  forKey: self.taskMapKey(session, task))
      task.resume()
    }

    // A consumer-supplied id makes startUpload idempotent. This is the same
    // behavior as Android's ExistingWorkPolicy.KEEP. If a task with this id is
    // already pending or running, we resolve with that id. We do not enqueue a
    // second task. We examine both sessions, because a new call can set a
    // different wifiOnly value while the first task continues in its first
    // session. A generated id cannot collide, so that path does not do the
    // (asynchronous) task enumeration.
    guard options["id"] != nil else {
      startNew()
      resolve(uploadId)
      return
    }

    // Serialize the check-and-create for each id: claim the id synchronously,
    // before we dispatch the enumeration. The first caller runs the check and
    // creates the task. A concurrent same-id caller parks its resolve here.
    // When the task lands, we answer the parked calls with the id. There is no
    // second task, and there is no polling.
    RNBackgroundUpload.lock.lock()
    if RNBackgroundUpload.creationsInFlight[uploadId] != nil {
      RNBackgroundUpload.creationsInFlight[uploadId]?.append(resolve)
      RNBackgroundUpload.lock.unlock()
      return
    }
    RNBackgroundUpload.creationsInFlight[uploadId] = []
    RNBackgroundUpload.lock.unlock()
    let settle = {
      RNBackgroundUpload.lock.lock()
      let waiters = RNBackgroundUpload.creationsInFlight.removeValue(forKey: uploadId) ?? []
      RNBackgroundUpload.lock.unlock()
      resolve(uploadId)
      for waiter in waiters { waiter(uploadId) }
    }

    let group = DispatchGroup()
    let foundLock = NSLock()
    var exists = false
    for s in activeSessions {
      group.enter()
      s.getAllTasks { tasks in
        for task in tasks
        where self.uploadId(s, task) == uploadId
          && (task.state == .running || task.state == .suspended) {
          foundLock.lock()
          exists = true
          foundLock.unlock()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) {
      if !exists { startNew() }
      settle()
    }
  }

  @objc(startChunkedUpload:resolve:reject:)
  public func startChunkedUpload(_ options: [String: Any],
                                 resolve: @escaping RCTPromiseResolveBlock,
                                 reject: @escaping RCTPromiseRejectBlock) {
    chunked.startUpload(
      options,
      resolve: { id in resolve(id) },
      reject: { message in reject("RN Uploader", message, nil) })
  }

  @objc(removeUpload:resolve:reject:)
  public func removeUpload(_ uploadId: String,
                           resolve: @escaping RCTPromiseResolveBlock,
                           reject: @escaping RCTPromiseRejectBlock) {
    // The chunked release runs first: it cancels the in-flight part tasks
    // and deletes the manifest and the bytes. Then we cancel any simple task
    // that wears this id. That cancel is kept out of the journal, because an
    // explicit release is not an outcome that the consumer awaits.
    chunked.remove(uploadId) {
      RNBackgroundUpload.lock.lock()
      RNBackgroundUpload.removedIds.insert(uploadId)
      RNBackgroundUpload.lock.unlock()
      let group = DispatchGroup()
      let foundLock = NSLock()
      var found = false
      for session in self.activeSessions {
        group.enter()
        session.getAllTasks { tasks in
          for task in tasks
          where self.uploadId(session, task) == uploadId
            && ChunkedCoordinator.partRef(session, task) == nil {
            foundLock.lock()
            found = true
            foundLock.unlock()
            task.cancel()
          }
          group.leave()
        }
      }
      group.notify(queue: .main) {
        foundLock.lock()
        let matched = found
        foundLock.unlock()
        if !matched {
          // Nothing was cancelled. Thus no delegate callback will consume
          // the suppression. Drop it. If we keep it, a later upload that
          // reuses this id has its real terminal swallowed.
          RNBackgroundUpload.lock.lock()
          RNBackgroundUpload.removedIds.remove(uploadId)
          RNBackgroundUpload.lock.unlock()
        }
        resolve(nil)
      }
    }
  }

  @objc(cancelUpload:resolve:reject:)
  public func cancelUpload(_ cancelUploadId: String,
                           resolve: @escaping RCTPromiseResolveBlock,
                           reject: @escaping RCTPromiseRejectBlock) {
    // A chunked upload cancels through its coordinator: one 'cancelled'
    // terminal for the whole upload, journaled before its part tasks are torn
    // down. nil means that the id has no manifest. It then falls through to
    // the simple-task path.
    chunked.cancel(cancelUploadId) { handled in
      if let handled { resolve(handled); return }
      self.cancelSimpleUpload(cancelUploadId, resolve: resolve)
    }
  }

  private func cancelSimpleUpload(_ cancelUploadId: String,
                                  resolve: @escaping RCTPromiseResolveBlock) {
    // Record intent before cancelling so the delegate reports cancelReason 'user'.
    RNBackgroundUpload.lock.lock()
    RNBackgroundUpload.userCancelledIds.insert(cancelUploadId)
    RNBackgroundUpload.lock.unlock()

    let sessions = activeSessions
    let group = DispatchGroup()
    // Guarded: the two sessions' getAllTasks completions run on independent
    // delegate queues, so this is written concurrently.
    let foundLock = NSLock()
    var found = false
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        for task in tasks where self.uploadId(session, task) == cancelUploadId {
          foundLock.lock()
          found = true
          foundLock.unlock()
          task.cancel()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) {
      foundLock.lock()
      let matched = found
      foundLock.unlock()
      if !matched {
        // Nothing to cancel: drop the intent again so a later upload reusing this
        // id isn't misattributed as a user cancel.
        RNBackgroundUpload.lock.lock()
        RNBackgroundUpload.userCancelledIds.remove(cancelUploadId)
        RNBackgroundUpload.lock.unlock()
      }
      resolve(matched)
    }
  }

  @objc(getUnacknowledgedEvents:reject:)
  public func getUnacknowledgedEvents(_ resolve: @escaping RCTPromiseResolveBlock,
                                      reject: @escaping RCTPromiseRejectBlock) {
    resolve(EventJournal.unacknowledged())
  }

  @objc(ackEvents:resolve:reject:)
  public func ackEvents(_ eventIds: [String],
                        resolve: @escaping RCTPromiseResolveBlock,
                        reject: @escaping RCTPromiseRejectBlock) {
    // An acked 'completed' is the ONE moment when a chunked upload's manifest
    // and moved bytes may be deleted. Every other terminal keeps them for a
    // resume. Find those uploads before the entries are removed.
    let completedUploadIds = EventJournal.unacknowledgedEntries()
      .filter { $0.type == "completed" && eventIds.contains($0.eventId) }
      .map { $0.id }
    EventJournal.ack(eventIds)
    chunked.releaseCompleted(completedUploadIds) { // no-op for simple uploads
      resolve(true)
    }
  }

  @objc(getAllUploads:reject:)
  public func getAllUploads(_ resolve: @escaping RCTPromiseResolveBlock,
                            reject: @escaping RCTPromiseRejectBlock) {
    let sessions = activeSessions
    let group = DispatchGroup()
    let lock = NSLock()
    var result: [[String: Any]] = []
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        for task in tasks {
          // A chunked upload is one logical row, built from its manifest
          // below. Its per-part tasks are transport detail.
          if ChunkedCoordinator.partRef(session, task) != nil { continue }
          let id = self.uploadId(session, task)
          if id == "unknown" { continue }
          lock.lock()
          // Report the real state. Collapsing everything non-running into
          // "pending" told a consumer's boot reconciliation that an upload had
          // never started, inviting it to re-enqueue one that was already
          // finishing or cancelling.
          let state: String
          switch task.state {
          case .running: state = "running"
          case .suspended: state = "pending"
          case .canceling: state = "cancelled"
          case .completed: state = "completed"
          @unknown default: state = "pending"
          }
          result.append(["id": id,
                         "state": state,
                         "bytesSent": task.countOfBytesSent,
                         "totalBytes": task.countOfBytesExpectedToSend])
          lock.unlock()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) {
      self.chunked.snapshots { chunkedRows in
        lock.lock()
        let combined = result + chunkedRows
        lock.unlock()
        resolve(combined)
      }
    }
  }

  // Called from AppDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:).
  // Reachable from a consumer's plain Obj-C via `@import
  // react_native_background_upload;` — deliberately NOT on the TurboModule class,
  // whose header is Obj-C++ only.
  @objc(setBackgroundSessionCompletionHandler:forIdentifier:)
  public static func setBackgroundSessionCompletionHandler(_ handler: @escaping () -> Void,
                                                          forIdentifier identifier: String) {
    // Touching `shared` recreates the background sessions when this is a fresh,
    // system-relaunched process, which is what lets the queued delegate events
    // (and therefore this handler) actually fire. On a relaunch, it also
    // claims the handler deferral (see below) BEFORE the handler is stored
    // here. Thus the claim provably precedes any drain.
    _ = shared
    bgHandlerLock.lock()
    bgCompletionHandlers[identifier] = handler
    bgHandlerLock.unlock()
  }

  /// For the chunked coordinator only. It parks every
  /// urlSessionDidFinishEvents drain until the matching release. Thus the
  /// system cannot suspend the app between a relaunch's replayed part
  /// completions and the post-reconcile refill that enqueues the next part
  /// tasks.
  static func deferBackgroundCompletionHandlers() {
    bgHandlerLock.lock()
    bgHandlerDeferrals += 1
    bgHandlerLock.unlock()
  }

  static func releaseBackgroundCompletionHandlers() {
    bgHandlerLock.lock()
    bgHandlerDeferrals -= 1
    var handlers: [() -> Void] = []
    if bgHandlerDeferrals <= 0 {
      for identifier in bgSessionsAwaitingDrain {
        if let handler = bgCompletionHandlers.removeValue(forKey: identifier) {
          handlers.append(handler)
        }
        // A parked id with no stored handler is simply dropped. There is
        // nothing to hold. If we keep it, a LATER wake's handler could drain
        // before that wake's events were processed.
      }
      bgSessionsAwaitingDrain.removeAll()
    }
    bgHandlerLock.unlock()
    for handler in handlers { DispatchQueue.main.async { handler() } }
  }

  // MARK: - URLSession delegate

  public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    guard !data.isEmpty else { return }
    // Key by sessionId:taskId, not taskIdentifier alone: taskIdentifier is unique
    // per session, so two concurrent uploads (one wifiOnly, one not) can share an
    // identifier and would otherwise cross-contaminate response bodies.
    let key = taskMapKey(session, dataTask)
    RNBackgroundUpload.lock.lock()
    if let existing = RNBackgroundUpload.responsesData[key] {
      existing.append(data)
    } else {
      RNBackgroundUpload.responsesData[key] = NSMutableData(data: data)
    }
    RNBackgroundUpload.lock.unlock()
  }

  public func urlSession(_ session: URLSession, task: URLSessionTask,
                         didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                         totalBytesExpectedToSend: Int64) {
    // A chunked part's bytes feed the upload's byte-weighted aggregate. A
    // per-task percentage would have no meaning to the consumer.
    if let ref = ChunkedCoordinator.partRef(session, task) {
      chunked.partProgress(id: ref.id, part: ref.part, incarnation: ref.incarnation,
                           sent: totalBytesSent)
      return
    }
    // 0 rather than -1 when the length is unknown: the documented range is
    // 0-100, Android reports 0 for the same case, and a negative value renders
    // as a broken progress bar in a consumer that passes it straight through.
    var progress: Float = 0
    if totalBytesExpectedToSend > 0 {
      progress = 100.0 * Float(totalBytesSent) / Float(totalBytesExpectedToSend)
    }
    let id = uploadId(session, task)
    let now = Date().timeIntervalSince1970
    RNBackgroundUpload.lock.lock()
    if progress < 100,
       let last = RNBackgroundUpload.lastProgressAt[id],
       now - last < RNBackgroundUpload.progressThrottle {
      RNBackgroundUpload.lock.unlock()
      return
    }
    RNBackgroundUpload.lastProgressAt[id] = now
    RNBackgroundUpload.lock.unlock()
    RNBackgroundUpload.currentDelegate?.emitProgress(["id": id, "progress": progress])
  }

  public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    let id = uploadId(session, task)
    let http = task.response as? HTTPURLResponse
    let statusCode = http?.statusCode ?? 0

    var headers: [String: String] = [:]
    if let http {
      for (key, value) in http.allHeaderFields { headers["\(key)"] = "\(value)" }
    }

    // A chunked part's outcome belongs to the coordinator of its upload:
    // accept evaluation against the manifest, the window refill, and one
    // journaled terminal, only when the whole upload settles.
    if let ref = ChunkedCoordinator.partRef(session, task) {
      RNBackgroundUpload.lock.lock()
      let bodyData = RNBackgroundUpload.responsesData.removeValue(forKey: taskMapKey(session, task))
      RNBackgroundUpload.lock.unlock()
      chunked.handlePartCompletion(
        id: ref.id, part: ref.part, incarnation: ref.incarnation,
        taskKey: taskMapKey(session, task),
        statusCode: http != nil ? statusCode : nil, headers: headers,
        body: bodyData.flatMap { String(data: $0 as Data, encoding: .utf8) },
        error: error as NSError?)
      return
    }

    RNBackgroundUpload.lock.lock()
    let bodyData = RNBackgroundUpload.responsesData.removeValue(forKey: taskMapKey(session, task))
    RNBackgroundUpload.lastProgressAt[id] = nil
    // Consume the user-cancel intent on EVERY terminal outcome, not only the
    // cancelled branch. If cancelUpload lost the race with completion, the id
    // would otherwise linger for the life of the process and a later upload
    // reusing that id would report a system cancel as a user cancel.
    let userCancelled = RNBackgroundUpload.userCancelledIds.remove(id) != nil
    let removed = RNBackgroundUpload.removedIds.remove(id) != nil
    RNBackgroundUpload.lock.unlock()

    // removeUpload cancelled this task as an explicit release, not as an
    // outcome that the consumer awaits. Journal nothing. A non-cancel
    // terminal that only raced the removal still reports normally.
    if removed, let nsError = error as NSError?, nsError.code == NSURLErrorCancelled {
      TaskMap.removeKey(taskMapKey(session, task))
      return
    }

    let rawBody = bodyData.flatMap { String(data: $0 as Data, encoding: .utf8) } ?? ""
    let (cappedBody, truncated) = EventJournal.capBody(rawBody)
    let responseBody = cappedBody ?? ""

    let eventId = UUID().uuidString
    let timestamp = Date().timeIntervalSince1970 * 1000
    var event = JournaledEvent(eventId: eventId, id: id, type: "completed", timestamp: timestamp)
    if http != nil {
      event.responseCode = statusCode
      event.responseHeaders = headers
      event.responseBody = responseBody
      event.responseBodyTruncated = truncated
    }

    if error == nil {
      // "completed" only for a 2xx or a matching per-request accept rule.
      // Any other HTTP response is a terminal http error that carries the
      // full response.
      let accepted = UploadOutcome.isAccepted(
        statusCode, body: rawBody, accept: acceptRules(session, task))
      if accepted {
        event.type = "completed"
      } else {
        event.type = "error"
        event.errorKind = "http"
        event.error = "HTTP \(statusCode)"
      }
    } else {
      let nsError = error! as NSError
      if nsError.code == NSURLErrorCancelled {
        event.type = "cancelled"
        event.cancelReason = userCancelled ? "user" : "system"
      } else {
        event.type = "error"
        event.errorKind = RNBackgroundUpload.errorKind(for: nsError)
        event.error = nsError.localizedDescription
      }
    }

    TaskMap.removeKey(taskMapKey(session, task))
    // Journals BEFORE it emits. The emit is best-effort, because JS can be
    // dead.
    RNBackgroundUpload.journalAndEmit(event)
  }

  public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    guard let identifier = session.configuration.identifier else { return }
    RNBackgroundUpload.bgHandlerLock.lock()
    guard RNBackgroundUpload.bgHandlerDeferrals <= 0 else {
      // A relaunch reconcile is in flight. If we hand the system the handler
      // now, it can suspend the app before the refill enqueues new part
      // tasks. The id is parked. releaseBackgroundCompletionHandlers drains
      // it.
      RNBackgroundUpload.bgSessionsAwaitingDrain.insert(identifier)
      RNBackgroundUpload.bgHandlerLock.unlock()
      return
    }
    let handler = RNBackgroundUpload.bgCompletionHandlers.removeValue(forKey: identifier)
    RNBackgroundUpload.bgHandlerLock.unlock()
    if let handler { DispatchQueue.main.async { handler() } }
  }

  // Classify a transport error to match Android's errorKind taxonomy: a missing or
  // unreadable source file -> 'file'; other URL-domain errors -> 'network'; anything
  // else -> 'unknown'. It is internal because the chunked coordinator also
  // classifies with it.
  static func errorKind(for error: NSError) -> String {
    switch (error.domain, error.code) {
    case (NSURLErrorDomain, NSURLErrorFileDoesNotExist),
         (NSURLErrorDomain, NSURLErrorCannotOpenFile),
         (NSURLErrorDomain, NSURLErrorNoPermissionsToReadFile),
         (NSCocoaErrorDomain, NSFileNoSuchFileError),
         (NSCocoaErrorDomain, NSFileReadNoSuchFileError),
         (NSCocoaErrorDomain, NSFileReadNoPermissionError):
      return "file"
    case (NSURLErrorDomain, _):
      return "network"
    default:
      return "unknown"
    }
  }
}
