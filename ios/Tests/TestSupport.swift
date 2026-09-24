import Foundation
import XCTest
@testable import RNBGUCore

/// A fresh directory per test, removed afterwards.
func makeTempDir(_ name: String = #function) -> URL {
  let dir = FileManager.default.temporaryDirectory
    .appendingPathComponent("rnbgu-tests", isDirectory: true)
    .appendingPathComponent(UUID().uuidString, isDirectory: true)
  try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
  return dir
}

/// Makes a directory read-only (writes into it fail) or writable again.
/// Tests restore it before tearDown deletes the tree.
func setReadOnly(_ dir: URL, _ readOnly: Bool) {
  try! FileManager.default.setAttributes([.posixPermissions: readOnly ? 0o555 : 0o755], ofItemAtPath: dir.path)
}

/// JSON text as JS JSON.stringify sends it (keys sorted, for stable tests).
func jsonText(_ value: Any) -> String {
  String(data: try! JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]),
         encoding: .utf8)!
}

/// Journal files exactly as a v9 build wrote them (JSONEncoder of the v9
/// JournaledEvent: nil fields omitted), one per kind. Literal, so a change to
/// JournaledEventV9 cannot change the fixture with it.
enum V9Journal {
  static func completed(eventId: String, id: String, timestamp: Int) -> String {
    #"{"eventId":"\#(eventId)","id":"\#(id)","type":"completed","timestamp":\#(timestamp),"#
      + #""responseCode":200,"responseBody":"{\"ok\":true}","responseHeaders":{"Content-Type":"application\/json"}}"#
  }

  static func error(eventId: String, id: String, timestamp: Int) -> String {
    #"{"eventId":"\#(eventId)","id":"\#(id)","type":"error","timestamp":\#(timestamp),"responseCode":404,"#
      + #""responseBody":"NoSuchUpload","error":"HTTP 404","errorKind":"http","partIndex":2}"#
  }

  static func cancelled(eventId: String, id: String, timestamp: Int) -> String {
    #"{"eventId":"\#(eventId)","id":"\#(id)","type":"cancelled","timestamp":\#(timestamp),"cancelReason":"user"}"#
  }

  static func write(_ json: String, eventId: String, into journalRoot: URL) {
    writeFile(journalRoot.appendingPathComponent(eventId + ".json"), json)
  }
}

func writeFile(_ url: URL, _ text: String) {
  try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
  try! Data(text.utf8).write(to: url)
}

func writeFile(_ url: URL, bytes: Int) {
  try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
  try! Data((0..<bytes).map { UInt8($0 % 251) }).write(to: url)
}

final class FakeTask: UploadTask {
  let key: String
  var taskDescription: String?
  var isLive = true
  private(set) var cancelled = false
  let request: URLRequest
  let file: URL?
  let beginAt: Date?
  let wifiOnly: Bool

  init(key: String, description: String?, request: URLRequest = URLRequest(url: URL(string: "https://x.test")!),
       file: URL? = nil, beginAt: Date? = nil, wifiOnly: Bool = false) {
    self.key = key
    taskDescription = description
    self.request = request
    self.file = file
    self.beginAt = beginAt
    self.wifiOnly = wifiOnly
  }

  func cancel() {
    cancelled = true
    isLive = false
  }

  func header(_ name: String) -> String? { request.value(forHTTPHeaderField: name) }
}

final class FakeTransport: Transport {
  /// Tasks the daemon held before this process started.
  var daemonTasks: [FakeTask] = []
  private(set) var created: [FakeTask] = []
  /// Static: task keys stay unique across a relaunch, as session task ids do.
  private static var next = 1
  /// When set, allTasks holds its answer until `releaseAllTasks()`, so a
  /// completion can land before reconcile.
  var deferAllTasks = false
  private var pendingAllTasks: (() -> Void)?

  func upload(_ request: URLRequest, fromFile file: URL, wifiOnly: Bool, description: String,
              beginAt: Date?, beforeResume: (String) -> Void) -> UploadTask {
    let task = FakeTask(key: "\(wifiOnly ? "wifi" : "any"):\(Self.next)", description: description,
                        request: request, file: file, beginAt: beginAt, wifiOnly: wifiOnly)
    Self.next += 1
    beforeResume(task.key)
    created.append(task)
    return task
  }

  func allTasks(_ completion: @escaping ([UploadTask]) -> Void) {
    let answer = { completion(self.daemonTasks + self.created) }
    if deferAllTasks { pendingAllTasks = answer } else { answer() }
  }

  func releaseAllTasks() {
    let answer = pendingAllTasks
    pendingAllTasks = nil
    answer?()
  }

  var live: [FakeTask] { created.filter(\.isLive) }
}

final class FakeSink: EventSink {
  var states: [[String: Any]] = []
  var progress: [[String: Any]] = []
  var attempts: [[String: Any]] = []
  var settled: [[String: Any]] = []

  func emitState(_ body: [String: Any]) { states.append(body) }
  func emitProgress(_ body: [String: Any]) { progress.append(body) }
  func emitAttempt(_ body: [String: Any]) { attempts.append(body) }
  func emitSettled(_ body: [String: Any]) { settled.append(body) }
  /// A JS listener is attached. A test sets it false for a headless run.
  var listening = true
  func canDeliver() -> Bool { listening }
  func listenerReady() { listening = true }

  var stateNames: [String] { states.compactMap { $0["state"] as? String } }
}

/// A coordinator over temp stores and fakes. Time and timers are manual.
final class Harness {
  let root: URL
  let store: QueueStore
  let journal: EventJournal
  let taskMap: TaskMap
  let transport = FakeTransport()
  let sink = FakeSink()
  var clock: Double = 1_700_000_000_000
  var timers: [(delayMs: Int, block: () -> Void)] = []
  private(set) var coordinator: QueueCoordinator!
  let queue = DispatchQueue(label: "test.queue")

  init(root: URL = makeTempDir()) {
    self.root = root
    store = QueueStore(root: root.appendingPathComponent("queue"))
    journal = EventJournal(root: root.appendingPathComponent("events"))
    taskMap = TaskMap(fileURL: root.appendingPathComponent("taskmap.json"))
    relaunch()
  }

  /// A new process over the same disk: the index reloads from the store.
  func relaunch(daemonTasks: [FakeTask] = []) {
    transport.daemonTasks = daemonTasks
    timers = []
    coordinator = QueueCoordinator(
      store: store, journal: journal, taskMap: TaskMap(fileURL: taskMap.fileURL),
      transport: transport, sink: sink, queue: queue, now: { [unowned self] in self.clock },
      random: { 0.5 }, schedule: { [unowned self] ms, block in self.timers.append((ms, block)) })
  }

  var map: TaskMap { coordinator.taskMap }

  /// Runs the relaunch reconcile to completion.
  func boot() {
    coordinator.reconcileAll {}
    drain()
    drain()
  }

  func drain() { queue.sync {} }

  /// Fires every timer that is due within `ms` (advancing the clock).
  func advance(_ ms: Double) {
    clock += ms
    let due = timers
    timers = []
    for t in due {
      if Double(t.delayMs) <= ms { queue.sync { t.block() } } else { timers.append((t.delayMs - Int(ms), t.block)) }
    }
  }

  @discardableResult
  func enqueue(_ raw: [String: Any]) -> Result<String, EnqueueError> {
    var result: Result<String, EnqueueError>?
    coordinator.enqueue(raw, resolve: { result = .success($0) },
                        reject: { result = .failure(EnqueueError(code: $0, message: $1)) })
    drain()
    return result!
  }

  /// Returns the rejection, or nil when cancel resolved.
  @discardableResult
  func cancel(_ id: String) -> EnqueueError? {
    var rejected: EnqueueError?
    coordinator.cancel(id, resolve: {}, reject: { rejected = EnqueueError(code: $0, message: $1) })
    drain()
    return rejected
  }

  func ack(_ eventIds: [String]) {
    coordinator.ack(eventIds) {}
    drain()
  }

  func pause() {
    coordinator.pause(resolve: {}, reject: { _, _ in XCTFail("pause rejected") })
    drain()
  }

  func resume() {
    coordinator.resume(resolve: {}, reject: { _, _ in XCTFail("resume rejected") })
    drain()
  }

  func updateHeaders(_ patch: [String: Any]) {
    coordinator.updateHeaders(patch, resolve: {}, reject: { _, _ in XCTFail("updateHeaders rejected") })
    drain()
  }

  func setWifiOnly(_ on: Bool) {
    coordinator.setWifiOnly(on, resolve: {}, reject: { _, _ in XCTFail("setWifiOnly rejected") })
    drain()
  }

  func unacknowledged() -> [[String: Any]] {
    var out: [[String: Any]] = []
    coordinator.unacknowledgedEvents { out = $0 }
    drain()
    return out
  }

  /// Delivers a completion for `task`, as didCompleteWithError would.
  func complete(_ task: FakeTask, status: Int? = 200, body: String = "", headers: [String: String] = [:],
                error: NSError? = nil) {
    task.isLive = false
    coordinator.taskCompleted(TaskCompletion(
      key: task.key, description: task.taskDescription, url: task.request.url?.absoluteString,
      statusCode: error == nil ? status : nil, headers: headers, body: error == nil ? body : nil,
      bodyTruncated: false, error: error))
  }

  /// Delivers the NSURLErrorCancelled callback of a task the test cancelled.
  func deliverCancel(_ task: FakeTask) {
    complete(task, error: NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
  }

  func entry(_ id: String) -> QueueEntry? { coordinator.index.entry(id) }
  func row(_ id: String) -> [String: Any]? { coordinator.rows().first { $0["id"] as? String == id } }

  // MARK: - Builders

  var expiresAt: Double { clock + 14 * 24 * 3_600_000 }

  func raw(id: String, key: String = "k", vars: Any = ["n": 1], descriptor: [String: Any]) -> [String: Any] {
    var d = descriptor
    if d["expiresAt"] == nil { d["expiresAt"] = expiresAt }
    return ["id": id, "key": key, "varsJson": jsonText(vars), "descriptor": d]
  }

  /// `data` crosses as its JSON text, as the JS layer sends it.
  func dataRaw(id: String, data: Any = ["x": 1], url: String = "https://api.test/x",
               headers: [String: Any] = [:], extra: [String: Any] = [:]) -> [String: Any] {
    var d: [String: Any] = ["url": url, "dataJson": jsonText(data), "headers": headers]
    for (k, v) in extra { d[k] = v }
    return raw(id: id, descriptor: d)
  }

  /// A chunked descriptor over a fresh source file of `size` bytes cut into
  /// `parts` equal parts.
  func chunkedRaw(id: String, size: Int = 30, parts: Int = 3, source: URL? = nil,
                  urlPrefix: String = "https://s3.test/part", extra: [String: Any] = [:]) -> [String: Any] {
    let file = source ?? root.appendingPathComponent("src-\(UUID().uuidString)")
    if source == nil { writeFile(file, bytes: size) }
    let step = size / parts
    let list: [[String: Any]] = (0..<parts).map { i in
      let start = i * step
      let end = i == parts - 1 ? size : start + step
      return ["url": "\(urlPrefix)\(i + 1)", "headers": ["Content-Range": "\(start)-\(end - 1)/\(size)"],
              "range": ["start": start, "end": end]]
    }
    var d: [String: Any] = ["file": file.path, "parts": list, "method": "PUT",
                            "headers": ["Content-Type": "video/mp4"]]
    for (k, v) in extra { d[k] = v }
    return raw(id: id, descriptor: d)
  }
}
