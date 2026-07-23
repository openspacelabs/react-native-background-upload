import Foundation
import React

// Background HTTP file uploader (iOS). Uploads run on a background URLSession so
// they continue while the app is suspended and complete/relaunch when terminated
// by the system. Terminal outcomes are journaled before being emitted, so JS can
// recover them even if it was dead when they fired.
//
// State that must be consistent regardless of which module instance is alive is
// STATIC (process-global): the background sessions, the in-flight response
// buffers, the user-cancel set, and the latest instance used for emitting. This
// matters because after a JS reload the URLSession delegate stays pinned to the
// first instance while JS talks to the newest one; sharing this state via statics
// keeps cancel-attribution and response assembly correct across that split, and
// avoids ever creating two background sessions with the same identifier.
@objc(RNFileUploader)
class RNFileUploader: RCTEventEmitter, URLSessionDataDelegate {

  private static let backgroundSessionId = "ReactNativeBackgroundUpload"
  private static let wifiOnlySessionId = "ReactNativeBackgroundUpload_WifiOnly"
  private static let progressThrottle: TimeInterval = 0.5 // seconds, per upload
  private static let maxBodyChars = 64 * 1024

  private static let lock = NSLock()
  private static var responsesData: [String: NSMutableData] = [:] // sessionId:taskId -> body
  private static var lastProgressAt: [String: TimeInterval] = [:] // uploadId -> time
  private static var userCancelledIds = Set<String>()
  private static weak var latestInstance: RNFileUploader?

  private static var backgroundSession: URLSession?
  private static var wifiOnlySession: URLSession?

  // AppDelegate stores the system-provided completion handler here (per session
  // id) so the app can be relaunched to finish uploads after termination.
  private static let bgHandlerLock = NSLock()
  private static var bgCompletionHandlers: [String: () -> Void] = [:]

  override init() {
    super.init()
    RNFileUploader.latestInstance = self
    // Recreate the sessions as early as possible so delegate events queued by
    // nsurlsessiond from a previous launch are delivered to this process.
    _ = session(wifiOnly: false)
    _ = session(wifiOnly: true)
  }

  override static func requiresMainQueueSetup() -> Bool { false }

  override func supportedEvents() -> [String]! {
    ["RNFileUploader-progress", "RNFileUploader-error", "RNFileUploader-cancelled", "RNFileUploader-completed"]
  }

  private func emit(_ name: String, _ body: [String: Any]) {
    // Route through the latest instance: after a JS reload the delegate may be an
    // older instance whose bridge is gone.
    RNFileUploader.latestInstance?.sendEvent(withName: name, body: body)
  }

  // MARK: - Sessions

  private func session(wifiOnly: Bool) -> URLSession {
    RNFileUploader.lock.lock()
    defer { RNFileUploader.lock.unlock() }
    if wifiOnly {
      if let s = RNFileUploader.wifiOnlySession { return s }
      let s = makeSession(identifier: RNFileUploader.wifiOnlySessionId, wifiOnly: true)
      RNFileUploader.wifiOnlySession = s
      return s
    } else {
      if let s = RNFileUploader.backgroundSession { return s }
      let s = makeSession(identifier: RNFileUploader.backgroundSessionId, wifiOnly: false)
      RNFileUploader.backgroundSession = s
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
    [RNFileUploader.backgroundSession, RNFileUploader.wifiOnlySession].compactMap { $0 }
  }

  // MARK: - Exported methods

  @objc(startUpload:resolver:rejecter:)
  func startUpload(_ options: [String: Any],
                   resolver resolve: @escaping RCTPromiseResolveBlock,
                   rejecter reject: @escaping RCTPromiseRejectBlock) {
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
    let acceptStatus = (options["acceptStatus"] as? [Int]) ?? []
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

  @objc(cancelUpload:resolver:rejecter:)
  func cancelUpload(_ cancelUploadId: String,
                    resolver resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
    // Record intent before cancelling so the delegate reports cancelReason 'user'.
    RNFileUploader.lock.lock()
    RNFileUploader.userCancelledIds.insert(cancelUploadId)
    RNFileUploader.lock.unlock()

    let sessions = activeSessions
    let group = DispatchGroup()
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        for task in tasks where self.uploadId(session, task) == cancelUploadId {
          task.cancel()
        }
        group.leave()
      }
    }
    group.notify(queue: .main) { resolve(true) }
  }

  @objc(getUploadStatus:resolver:rejecter:)
  func getUploadStatus(_ uploadId: String,
                       resolver resolve: @escaping RCTPromiseResolveBlock,
                       rejecter reject: @escaping RCTPromiseRejectBlock) {
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

  @objc(getUnacknowledgedEvents:rejecter:)
  func getUnacknowledgedEvents(_ resolve: @escaping RCTPromiseResolveBlock,
                               rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(EventJournal.unacknowledged())
  }

  @objc(ackEvents:resolver:rejecter:)
  func ackEvents(_ eventIds: [String],
                 resolver resolve: @escaping RCTPromiseResolveBlock,
                 rejecter reject: @escaping RCTPromiseRejectBlock) {
    EventJournal.ack(eventIds)
    resolve(true)
  }

  @objc(getAllUploads:rejecter:)
  func getAllUploads(_ resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
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

  // Called from AppDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:)
  @objc(setBackgroundSessionCompletionHandler:forIdentifier:)
  static func setBackgroundSessionCompletionHandler(_ handler: @escaping () -> Void,
                                                    forIdentifier identifier: String) {
    bgHandlerLock.lock()
    bgCompletionHandlers[identifier] = handler
    bgHandlerLock.unlock()
  }

  // MARK: - URLSession delegate

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    guard !data.isEmpty else { return }
    // Key by sessionId:taskId, not taskIdentifier alone: taskIdentifier is unique
    // per session, so two concurrent uploads (one wifiOnly, one not) can share an
    // identifier and would otherwise cross-contaminate response bodies.
    let key = taskMapKey(session, dataTask)
    RNFileUploader.lock.lock()
    if let existing = RNFileUploader.responsesData[key] {
      existing.append(data)
    } else {
      RNFileUploader.responsesData[key] = NSMutableData(data: data)
    }
    RNFileUploader.lock.unlock()
  }

  func urlSession(_ session: URLSession, task: URLSessionTask,
                  didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                  totalBytesExpectedToSend: Int64) {
    var progress: Float = -1
    if totalBytesExpectedToSend > 0 {
      progress = 100.0 * Float(totalBytesSent) / Float(totalBytesExpectedToSend)
    }
    let id = uploadId(session, task)
    let now = Date().timeIntervalSince1970
    RNFileUploader.lock.lock()
    if progress < 100,
       let last = RNFileUploader.lastProgressAt[id],
       now - last < RNFileUploader.progressThrottle {
      RNFileUploader.lock.unlock()
      return
    }
    RNFileUploader.lastProgressAt[id] = now
    RNFileUploader.lock.unlock()
    emit("RNFileUploader-progress", ["id": id, "progress": progress])
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    let id = uploadId(session, task)
    let http = task.response as? HTTPURLResponse
    let statusCode = http?.statusCode ?? 0

    var headers: [String: String] = [:]
    if let http {
      for (key, value) in http.allHeaderFields { headers["\(key)"] = "\(value)" }
    }

    RNFileUploader.lock.lock()
    let bodyData = RNFileUploader.responsesData.removeValue(forKey: taskMapKey(session, task))
    RNFileUploader.lastProgressAt[id] = nil
    RNFileUploader.lock.unlock()

    var responseBody = bodyData.flatMap { String(data: $0 as Data, encoding: .utf8) } ?? ""
    var truncated = false
    if responseBody.count > RNFileUploader.maxBodyChars {
      responseBody = String(responseBody.prefix(RNFileUploader.maxBodyChars))
      truncated = true
    }

    let eventId = UUID().uuidString
    let timestamp = Date().timeIntervalSince1970 * 1000
    var event = JournaledEvent(eventId: eventId, id: id, type: "completed", timestamp: timestamp)
    if http != nil {
      event.responseCode = statusCode
      event.responseHeaders = headers
      event.responseBody = responseBody
      event.responseBodyTruncated = truncated
    }

    let eventName: String
    if error == nil {
      // "completed" only for 2xx or a per-request acceptStatus code; any other
      // HTTP response is a terminal http error carrying the full response.
      let accepted = (200..<300).contains(statusCode) || acceptStatus(session, task).contains(statusCode)
      if accepted {
        eventName = "completed"
        event.type = "completed"
      } else {
        eventName = "error"
        event.type = "error"
        event.errorKind = "http"
        event.error = "HTTP \(statusCode)"
      }
    } else {
      let nsError = error! as NSError
      if nsError.code == NSURLErrorCancelled {
        eventName = "cancelled"
        event.type = "cancelled"
        RNFileUploader.lock.lock()
        let userCancelled = RNFileUploader.userCancelledIds.remove(id) != nil
        RNFileUploader.lock.unlock()
        event.cancelReason = userCancelled ? "user" : "system"
      } else {
        eventName = "error"
        event.type = "error"
        event.errorKind = "network"
        event.error = nsError.localizedDescription
      }
    }

    // Journal BEFORE emitting; the emit is best-effort (JS may be dead).
    EventJournal.append(event)
    TaskMap.removeKey(taskMapKey(session, task))
    emit("RNFileUploader-\(eventName)", event.bridged)
  }

  func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    guard let identifier = session.configuration.identifier else { return }
    RNFileUploader.bgHandlerLock.lock()
    let handler = RNFileUploader.bgCompletionHandlers.removeValue(forKey: identifier)
    RNFileUploader.bgHandlerLock.unlock()
    if let handler { DispatchQueue.main.async { handler() } }
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
