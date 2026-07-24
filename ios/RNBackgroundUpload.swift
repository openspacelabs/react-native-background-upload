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

  public override init() {
    super.init()
    // Recreate the sessions as early as possible so delegate events queued by
    // nsurlsessiond from a previous launch are delivered to this process.
    _ = session(wifiOnly: false)
    _ = session(wifiOnly: true)
  }

  // MARK: - Event delegate

  @objc public static func setEventDelegate(_ delegate: RNFileUploaderEventDelegate?) {
    // Force the singleton (and therefore the sessions) into existence before
    // taking the lock — see the note on delegateLock.
    _ = shared
    delegateLock.lock()
    eventDelegate = delegate
    delegateLock.unlock()
  }

  private static var currentDelegate: RNFileUploaderEventDelegate? {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    return eventDelegate
  }

  // MARK: - Sessions

  private func session(wifiOnly: Bool) -> URLSession {
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
    config.httpMaximumConnectionsPerHost = 1
    config.waitsForConnectivity = true
    config.allowsCellularAccess = !wifiOnly
    config.allowsConstrainedNetworkAccess = !wifiOnly
    config.allowsExpensiveNetworkAccess = !wifiOnly
    return URLSession(configuration: config, delegate: self, delegateQueue: nil)
  }

  private func taskMapKey(_ session: URLSession, _ task: URLSessionTask) -> String {
    "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)"
  }

  // taskDescription is the primary id; the persisted map is the durable fallback.
  private func uploadId(_ session: URLSession, _ task: URLSessionTask) -> String {
    task.taskDescription ?? TaskMap.meta(forKey: taskMapKey(session, task))?.id ?? "unknown"
  }

  private func acceptStatus(_ session: URLSession, _ task: URLSessionTask) -> [Int] {
    TaskMap.meta(forKey: taskMapKey(session, task))?.acceptStatus ?? []
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
        if let s = value as? String { request.setValue(s, forHTTPHeaderField: key) }
        else { request.setValue("\(value)", forHTTPHeaderField: key) }
      }
    }

    let wifiOnly = (options["wifiOnly"] as? Bool) ?? false
    // RN bridges a JS number[] to NSArray<NSNumber>; map explicitly rather than
    // rely on an [Int] bridging cast that can yield nil and silently drop it.
    let acceptStatus = (options["acceptStatus"] as? [NSNumber])?.map { $0.intValue } ?? []
    let uploadId = (options["customUploadId"] as? String) ?? UUID().uuidString
    let fileURL = URL(string: path) ?? URL(fileURLWithPath: path)

    let session = self.session(wifiOnly: wifiOnly)
    let task = session.uploadTask(with: request, fromFile: fileURL)
    task.taskDescription = uploadId
    TaskMap.set(TaskMap.Meta(id: uploadId, acceptStatus: acceptStatus),
                forKey: taskMapKey(session, task))
    task.resume()
    resolve(uploadId)
  }

  @objc(cancelUpload:resolve:reject:)
  public func cancelUpload(_ cancelUploadId: String,
                           resolve: @escaping RCTPromiseResolveBlock,
                           reject: @escaping RCTPromiseRejectBlock) {
    // Record intent before cancelling so the delegate reports cancelReason 'user'.
    RNBackgroundUpload.lock.lock()
    RNBackgroundUpload.userCancelledIds.insert(cancelUploadId)
    RNBackgroundUpload.lock.unlock()

    let sessions = activeSessions
    let group = DispatchGroup()
    let found = NSMutableArray()
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        for task in tasks where self.uploadId(session, task) == cancelUploadId {
          found.add(true)
          task.cancel()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) {
      if found.count == 0 {
        // Nothing to cancel: drop the intent again so a later upload reusing this
        // customUploadId isn't misattributed as a user cancel.
        RNBackgroundUpload.lock.lock()
        RNBackgroundUpload.userCancelledIds.remove(cancelUploadId)
        RNBackgroundUpload.lock.unlock()
      }
      resolve(found.count > 0)
    }
  }

  @objc(getUploadStatus:resolve:reject:)
  public func getUploadStatus(_ uploadId: String,
                              resolve: @escaping RCTPromiseResolveBlock,
                              reject: @escaping RCTPromiseRejectBlock) {
    let sessions = activeSessions
    let group = DispatchGroup()
    let lock = NSLock()
    var result: [String: Any]?
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        for task in tasks where self.uploadId(session, task) == uploadId {
          lock.lock()
          if result == nil {
            result = ["state": self.stateString(task.state),
                      "bytesSent": task.countOfBytesSent,
                      "totalBytes": task.countOfBytesExpectedToSend]
          }
          lock.unlock()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) { resolve(result) }
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
    EventJournal.ack(eventIds)
    resolve(true)
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
          let id = self.uploadId(session, task)
          if id == "unknown" { continue }
          lock.lock()
          result.append(["id": id,
                         "state": task.state == .running ? "running" : "pending",
                         "bytesSent": task.countOfBytesSent,
                         "totalBytes": task.countOfBytesExpectedToSend])
          lock.unlock()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) { resolve(result) }
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
    // (and therefore this handler) actually fire.
    _ = shared
    bgHandlerLock.lock()
    bgCompletionHandlers[identifier] = handler
    bgHandlerLock.unlock()
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
    var progress: Float = -1
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

    RNBackgroundUpload.lock.lock()
    let bodyData = RNBackgroundUpload.responsesData.removeValue(forKey: taskMapKey(session, task))
    RNBackgroundUpload.lastProgressAt[id] = nil
    RNBackgroundUpload.lock.unlock()

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
      // "completed" only for 2xx or a per-request acceptStatus code; any other
      // HTTP response is a terminal http error carrying the full response.
      let accepted = (200..<300).contains(statusCode) || acceptStatus(session, task).contains(statusCode)
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
        RNBackgroundUpload.lock.lock()
        let userCancelled = RNBackgroundUpload.userCancelledIds.remove(id) != nil
        RNBackgroundUpload.lock.unlock()
        event.cancelReason = userCancelled ? "user" : "system"
      } else {
        event.type = "error"
        event.errorKind = RNBackgroundUpload.errorKind(for: nsError)
        event.error = nsError.localizedDescription
      }
    }

    // Journal BEFORE emitting; the emit is best-effort (JS may be dead).
    EventJournal.append(event)
    TaskMap.removeKey(taskMapKey(session, task))

    let body = event.bridged
    let delegate = RNBackgroundUpload.currentDelegate
    switch event.type {
    case "completed": delegate?.emitCompleted(body)
    case "cancelled": delegate?.emitCancelled(body)
    default: delegate?.emitError(body)
    }
  }

  public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    guard let identifier = session.configuration.identifier else { return }
    RNBackgroundUpload.bgHandlerLock.lock()
    let handler = RNBackgroundUpload.bgCompletionHandlers.removeValue(forKey: identifier)
    RNBackgroundUpload.bgHandlerLock.unlock()
    if let handler { DispatchQueue.main.async { handler() } }
  }

  // Classify a transport error to match Android's errorKind taxonomy: a missing or
  // unreadable source file -> 'file'; other URL-domain errors -> 'network'; anything
  // else -> 'unknown'.
  private static func errorKind(for error: NSError) -> String {
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

  private func stateString(_ state: URLSessionTask.State) -> String {
    switch state {
    case .running: return "running"
    case .suspended: return "suspended"
    case .canceling: return "canceling"
    case .completed: return "completed"
    @unknown default: return "running"
    }
  }
}
