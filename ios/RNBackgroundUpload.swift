import Foundation
import React
import UIKit

// Live events destined for JS. The TurboModule shell (RNFileUploader.mm)
// adopts this and forwards to the codegen emitters. The delegate is nil
// whenever JS is not around (a headless relaunch, before the module exists,
// after a reload tears the old one down). Every terminal is journaled before
// it gets here, so dropping a live event is always safe.
@objc public protocol RNFileUploaderEventDelegate {
  func emitState(_ body: [String: Any])
  func emitProgress(_ body: [String: Any])
  func emitAttempt(_ body: [String: Any])
  func emitSettled(_ body: [String: Any])
}

// The process-wide owner of the two background URLSessions and their
// delegate. It adapts URLSession callbacks and the TurboModule methods to the
// QueueCoordinator, which holds every queue rule.
//
// The TurboModule instance comes and goes with the JS runtime; this object
// lives for the whole process. That keeps one delegate per background session
// identifier, and the response buffers and task ownership stay correct
// across a reload.
@objc(RNBackgroundUpload)
public class RNBackgroundUpload: NSObject, URLSessionDataDelegate {

  // Created on first access: by the TurboModule, or by the AppDelegate's
  // handleEventsForBackgroundURLSession hook, whichever comes first. The
  // second path matters: on a system relaunch there may be no JS at all, and
  // touching `shared` recreates the sessions so nsurlsessiond can deliver the
  // events it queued.
  @objc public static let shared = RNBackgroundUpload()

  private let transport: SessionTransport
  private let sink: DelegateSink
  private let coordinator: QueueCoordinator

  private let bufferLock = NSLock()
  private var responses: [String: ResponseBuffer] = [:] // TaskMap key -> body so far

  // Its own lock, not bufferLock: creating `shared` must never wait on the
  // lock that guards the delegate.
  private static let delegateLock = NSLock()
  private static weak var eventDelegate: RNFileUploaderEventDelegate?
  // true from the registered module's first journal drain (its JS listener
  // is attached by then) until that module goes away. Guarded by
  // delegateLock. A settle with no listener is journaled at deliveries 0.
  private static var listening = false

  // AppDelegate stores the system completion handler here per session id.
  private static let bgHandlerLock = NSLock()
  private static var bgCompletionHandlers: [String: () -> Void] = [:]
  // While a relaunch reconcile runs (deferrals > 0), urlSessionDidFinishEvents
  // must not hand the system its handler: the system could suspend the app
  // before the refill enqueues the next tasks, leaving zero daemon tasks and
  // no future wake. A session that finishes its events in that window parks
  // its id here; the release drains it.
  private static var bgHandlerDeferrals = 0
  private static var bgSessionsAwaitingDrain: Set<String> = []

  public override init() {
    let transport = SessionTransport()
    let sink = DelegateSink()
    self.transport = transport
    self.sink = sink
    // The coordinator exists, and its index is loaded from disk, before any
    // session can deliver a callback.
    coordinator = QueueCoordinator(
      store: .shared, journal: .shared, taskMap: .shared, transport: transport, sink: sink)
    super.init()
    transport.createSessions(delegate: self)
    observeAppState()
    // Claim the completion-handler deferral BEFORE the reconcile is queued.
    // The release waits for the reconcile and for every grace wait it opens.
    // A relaunch reaches here inside the init of `shared`, and the AppDelegate
    // hook finishes that init before it stores the handler, so the claim
    // always precedes any drain.
    RNBackgroundUpload.deferBackgroundCompletionHandlers()
    coordinator.reconcileAll { RNBackgroundUpload.releaseBackgroundCompletionHandlers() }
  }

  // MARK: - Event delegate

  @objc public static func setEventDelegate(_ delegate: RNFileUploaderEventDelegate) {
    // Force the singleton (and the sessions) into existence before the lock.
    _ = shared
    delegateLock.lock()
    eventDelegate = delegate
    // A new module has no JS listener until its own drain.
    listening = false
    delegateLock.unlock()
  }

  /// Deregisters a delegate only if it is still the registered one. React
  /// Native runs invalidate asynchronously and stops waiting after 10 s, so
  /// the replacement module can register first. Clearing unconditionally
  /// would stop every event for the rest of the process.
  @objc public static func clearEventDelegate(_ delegate: RNFileUploaderEventDelegate) {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    if eventDelegate === delegate {
      eventDelegate = nil
      listening = false
    }
  }

  fileprivate static var currentDelegate: RNFileUploaderEventDelegate? {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    return eventDelegate
  }

  fileprivate static var canDeliver: Bool {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    return listening && eventDelegate != nil
  }

  fileprivate static func markListening() {
    delegateLock.lock()
    defer { delegateLock.unlock() }
    if eventDelegate != nil { listening = true }
  }

  // MARK: - Module methods (called from the TurboModule shell)

  // Each is a one-line forward. The coordinator hops onto its own queue and
  // returns, so the module queue never waits.

  @objc(configure:)
  public func configure(_ options: [String: Any]) {
    coordinator.configure(options)
  }

  @objc(enqueue:resolve:reject:)
  public func enqueue(_ entry: [String: Any], resolve: @escaping RCTPromiseResolveBlock,
                      reject: @escaping RCTPromiseRejectBlock) {
    coordinator.enqueue(entry, resolve: { resolve($0) }, reject: { reject($0, $1, nil) })
  }

  // scope is { keys?: [String] }; [:] is the whole queue.
  @objc(pause:resolve:reject:)
  public func pause(_ scope: [String: Any], resolve: @escaping RCTPromiseResolveBlock,
                    reject: @escaping RCTPromiseRejectBlock) {
    coordinator.pause(scope, resolve: { resolve(nil) }, reject: { reject($0, $1, nil) })
  }

  @objc(resume:resolve:reject:)
  public func resume(_ scope: [String: Any], resolve: @escaping RCTPromiseResolveBlock,
                     reject: @escaping RCTPromiseRejectBlock) {
    coordinator.resume(scope, resolve: { resolve(nil) }, reject: { reject($0, $1, nil) })
  }

  @objc(cancel:resolve:reject:)
  public func cancel(_ id: String, resolve: @escaping RCTPromiseResolveBlock,
                     reject: @escaping RCTPromiseRejectBlock) {
    coordinator.cancel(id, resolve: { resolve(nil) }, reject: { reject($0, $1, nil) })
  }

  @objc(setWifiOnly:resolve:reject:)
  public func setWifiOnly(_ enabled: Bool, resolve: @escaping RCTPromiseResolveBlock,
                          reject: @escaping RCTPromiseRejectBlock) {
    coordinator.setWifiOnly(enabled, resolve: { resolve(nil) }, reject: { reject($0, $1, nil) })
  }

  @objc(updateHeaders:resolve:reject:)
  public func updateHeaders(_ patch: [String: Any], resolve: @escaping RCTPromiseResolveBlock,
                            reject: @escaping RCTPromiseRejectBlock) {
    coordinator.updateHeaders(patch, resolve: { resolve(nil) }, reject: { reject($0, $1, nil) })
  }

  /// Synchronous, from the in-memory index.
  @objc public func getRequests() -> [[String: Any]] {
    coordinator.rows()
  }

  @objc(getUnacknowledgedEvents:reject:)
  public func getUnacknowledgedEvents(_ resolve: @escaping RCTPromiseResolveBlock,
                                      reject: @escaping RCTPromiseRejectBlock) {
    coordinator.unacknowledgedEvents { resolve($0) }
  }

  @objc(ackEvents:resolve:reject:)
  public func ackEvents(_ eventIds: [Any], resolve: @escaping RCTPromiseResolveBlock,
                        reject: @escaping RCTPromiseRejectBlock) {
    // Resolves void. A non-string id is ignored, like an unknown one.
    coordinator.ack(eventIds.compactMap { $0 as? String }) { resolve(nil) }
  }

  // MARK: - Background session completion

  // Called from AppDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:).
  // Reachable from a consumer's plain Obj-C via `@import
  // react_native_background_upload;`, not on the TurboModule class, whose
  // header is Obj-C++ only.
  @objc(setBackgroundSessionCompletionHandler:forIdentifier:)
  public static func setBackgroundSessionCompletionHandler(_ handler: @escaping () -> Void,
                                                          forIdentifier identifier: String) {
    // Touching `shared` recreates the sessions in a system-relaunched
    // process, and claims the handler deferral before the handler is stored.
    _ = shared
    bgHandlerLock.lock()
    bgCompletionHandlers[identifier] = handler
    bgHandlerLock.unlock()
  }

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
      }
      // A parked id with no stored handler is dropped. Kept, it could let a
      // later wake's handler drain before that wake's events ran.
      bgSessionsAwaitingDrain.removeAll()
    }
    bgHandlerLock.unlock()
    for handler in handlers { DispatchQueue.main.async { handler() } }
  }

  // MARK: - URLSession delegate

  public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    guard !data.isEmpty else { return }
    // Keyed by session id and task id: taskIdentifier alone is unique per
    // session only.
    let key = TaskMap.key(session, dataTask)
    bufferLock.lock()
    responses[key, default: ResponseBuffer()].append(data)
    bufferLock.unlock()
  }

  public func urlSession(_ session: URLSession, task: URLSessionTask,
                         didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
                         totalBytesExpectedToSend: Int64) {
    coordinator.taskProgress(key: TaskMap.key(session, task), description: task.taskDescription,
                             sent: totalBytesSent, expected: totalBytesExpectedToSend)
  }

  public func urlSession(_ session: URLSession, task: URLSessionTask,
                         willBeginDelayedRequest request: URLRequest,
                         completionHandler: @escaping (URLSession.DelayedRequestDisposition, URLRequest?) -> Void) {
    if let next = coordinator.taskWillBegin(key: TaskMap.key(session, task),
                                            description: task.taskDescription) {
      completionHandler(.useNewRequest, next)
    } else {
      completionHandler(.cancel, nil)
    }
  }

  public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    let key = TaskMap.key(session, task)
    let http = task.response as? HTTPURLResponse
    var headers: [String: String] = [:]
    if let http {
      for (name, value) in http.allHeaderFields { headers["\(name)"] = "\(value)" }
    }
    bufferLock.lock()
    let buffer = responses.removeValue(forKey: key) ?? ResponseBuffer()
    bufferLock.unlock()
    let (body, truncated) = buffer.decoded()
    // Synchronous hop: the journal write for a terminal lands before this
    // callback returns.
    coordinator.taskCompleted(TaskCompletion(
      key: key, description: task.taskDescription,
      url: task.originalRequest?.url?.absoluteString, statusCode: http?.statusCode,
      headers: headers, body: http == nil ? nil : body, bodyTruncated: truncated,
      error: error as NSError?))
  }

  public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    guard let identifier = session.configuration.identifier else { return }
    RNBackgroundUpload.bgHandlerLock.lock()
    guard RNBackgroundUpload.bgHandlerDeferrals <= 0 else {
      // A relaunch reconcile is in flight. Park the id; the release drains it.
      RNBackgroundUpload.bgSessionsAwaitingDrain.insert(identifier)
      RNBackgroundUpload.bgHandlerLock.unlock()
      return
    }
    let handler = RNBackgroundUpload.bgCompletionHandlers.removeValue(forKey: identifier)
    RNBackgroundUpload.bgHandlerLock.unlock()
    if let handler { DispatchQueue.main.async { handler() } }
  }

  // MARK: - App state

  // The progress throttle is 1 s in the foreground and 10 min in the
  // background. The flag is set from notifications, because reading
  // applicationState needs the main thread.
  private func observeAppState() {
    let center = NotificationCenter.default
    let throttle = coordinator.throttle
    for name in [UIApplication.willEnterForegroundNotification, UIApplication.didBecomeActiveNotification] {
      center.addObserver(forName: name, object: nil, queue: nil) { _ in throttle.isForeground = true }
    }
    center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                       queue: nil) { _ in throttle.isForeground = false }
    DispatchQueue.main.async {
      throttle.isForeground = UIApplication.shared.applicationState != .background
    }
  }
}

/// Forwards coordinator events to the registered TurboModule, if any.
private final class DelegateSink: EventSink {
  func emitState(_ body: [String: Any]) { RNBackgroundUpload.currentDelegate?.emitState(body) }
  func emitProgress(_ body: [String: Any]) { RNBackgroundUpload.currentDelegate?.emitProgress(body) }
  func emitAttempt(_ body: [String: Any]) { RNBackgroundUpload.currentDelegate?.emitAttempt(body) }
  func emitSettled(_ body: [String: Any]) { RNBackgroundUpload.currentDelegate?.emitSettled(body) }
  func canDeliver() -> Bool { RNBackgroundUpload.canDeliver }
  func listenerReady() { RNBackgroundUpload.markListening() }
}

/// The two background sessions: one that may use cellular, one Wi-Fi only.
/// allowsCellularAccess and friends are session properties, so a task keeps
/// the session it started on. Both exist from init, so reconcile sees every
/// task.
private final class SessionTransport: Transport {
  private static let backgroundSessionId = "ReactNativeBackgroundUpload"
  private static let wifiOnlySessionId = "ReactNativeBackgroundUpload_WifiOnly"

  private let lock = NSLock()
  private var background: URLSession?
  private var wifiOnly: URLSession?

  func createSessions(delegate: URLSessionDelegate) {
    lock.lock()
    defer { lock.unlock() }
    background = Self.makeSession(identifier: Self.backgroundSessionId, wifiOnly: false, delegate: delegate)
    wifiOnly = Self.makeSession(identifier: Self.wifiOnlySessionId, wifiOnly: true, delegate: delegate)
  }

  func upload(_ request: URLRequest, fromFile file: URL, wifiOnly: Bool, description: String,
              beginAt: Date?, beforeResume: (String) -> Void) -> UploadTask {
    let session = self.session(wifiOnly: wifiOnly)
    // A background session uploads from a file only.
    let task = session.uploadTask(with: request, fromFile: file)
    task.taskDescription = description
    if let beginAt { task.earliestBeginDate = beginAt }
    let handle = SessionTask(session: session, task: task)
    beforeResume(handle.key)
    task.resume()
    return handle
  }

  func allTasks(_ completion: @escaping ([UploadTask]) -> Void) {
    let sessions = [session(wifiOnly: false), session(wifiOnly: true)]
    let group = DispatchGroup()
    let collectLock = NSLock()
    var collected: [UploadTask] = []
    for session in sessions {
      group.enter()
      session.getAllTasks { tasks in
        collectLock.lock()
        collected.append(contentsOf: tasks.map { SessionTask(session: session, task: $0) })
        collectLock.unlock()
        group.leave()
      }
    }
    group.notify(queue: .global()) { completion(collected) }
  }

  private func session(wifiOnly: Bool) -> URLSession {
    lock.lock()
    defer { lock.unlock() }
    // createSessions runs in init, before anything can call this.
    return (wifiOnly ? self.wifiOnly : background)!
  }

  // Session config is set before the session is created (URLSession copies
  // it). Carried over from v9.
  private static func makeSession(identifier: String, wifiOnly: Bool,
                                  delegate: URLSessionDelegate) -> URLSession {
    let config = URLSessionConfiguration.background(withIdentifier: identifier)
    config.isDiscretionary = false
    // A per-host backstop. The chunked window of 3 is the real limiter.
    config.httpMaximumConnectionsPerHost = 4
    config.waitsForConnectivity = true
    config.allowsCellularAccess = !wifiOnly
    config.allowsConstrainedNetworkAccess = !wifiOnly
    config.allowsExpensiveNetworkAccess = !wifiOnly
    return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
  }
}

private final class SessionTask: UploadTask {
  let key: String
  private let task: URLSessionTask

  init(session: URLSession, task: URLSessionTask) {
    key = TaskMap.key(session, task)
    self.task = task
  }

  var taskDescription: String? { task.taskDescription }
  var isLive: Bool { task.state == .running || task.state == .suspended }
  var beginAt: Date? { task.earliestBeginDate }
  func cancel() { task.cancel() }
}
