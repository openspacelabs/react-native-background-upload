import Foundation

// The seams between the queue logic and the platform. RNBackgroundUpload
// implements them over the two background URLSessions and the TurboModule
// emitters; the unit tests implement them with fakes. Everything behind them
// imports Foundation only.

/// One task held by nsurlsessiond.
protocol UploadTask: AnyObject {
  /// "<sessionId>:<taskIdentifier>", the TaskMap key.
  var key: String { get }
  var taskDescription: String? { get }
  /// Running or suspended. A completed or canceling task is not live: its
  /// delegate callback settles it.
  var isLive: Bool { get }
  /// earliestBeginDate: set on a delayed retry.
  var beginAt: Date? { get }
  func cancel()
}

protocol Transport: AnyObject {
  /// Creates an upload task, sets its description and begin date, calls
  /// `beforeResume` with its key (the caller writes the TaskMap there), then
  /// resumes it.
  func upload(_ request: URLRequest, fromFile file: URL, wifiOnly: Bool, description: String,
              beginAt: Date?, beforeResume: (String) -> Void) -> UploadTask

  /// Every task of both sessions. The completion may run on any queue.
  func allTasks(_ completion: @escaping ([UploadTask]) -> Void)
}

/// Live events for JS. Best-effort: JS may be dead, and every terminal is
/// journaled before emitSettled.
protocol EventSink: AnyObject {
  func emitState(_ body: [String: Any])
  func emitProgress(_ body: [String: Any])
  func emitAttempt(_ body: [String: Any])
  func emitSettled(_ body: [String: Any])
}

/// What didCompleteWithError reports, with the response body already capped.
struct TaskCompletion {
  let key: String
  let description: String?
  let url: String?
  let statusCode: Int?
  let headers: [String: String]
  let body: String?
  let bodyTruncated: Bool
  let error: NSError?
}

/// Who a task belongs to. From taskDescription first, TaskMap second.
enum TaskOwner: Equatable {
  case request(id: String, generation: Int, attempt: Int)
  case part(id: String, part: Int, incarnation: String?)

  var id: String {
    switch self {
    case .request(let id, _, _), .part(let id, _, _): return id
    }
  }

  static func resolve(description: String?, meta: TaskMap.Meta?) -> TaskOwner? {
    if let p = ChunkedEngine.parsePartDescription(description) {
      return .part(id: p.id, part: p.part, incarnation: p.incarnation)
    }
    if let r = ChunkedEngine.parseRequestDescription(description) {
      return .request(id: r.id, generation: r.generation, attempt: r.attempt)
    }
    guard let meta else { return nil }
    if let part = meta.partIndex { return .part(id: meta.id, part: part, incarnation: meta.incarnation) }
    if let generation = meta.generation, let attempt = meta.attempt {
      return .request(id: meta.id, generation: generation, attempt: attempt)
    }
    // A v9 simple task: a bare id with no generation. No v10 owner.
    return nil
  }
}

/// A task's response body while it streams in, capped at the settled body
/// cap. Bytes past the cap are dropped and flagged, so a huge error page
/// cannot grow memory without bound.
struct ResponseBuffer {
  private(set) var data = Data()
  private(set) var truncated = false

  mutating func append(_ chunk: Data, cap: Int = EventJournal.maxBodyBytes) {
    let room = cap - data.count
    if chunk.count <= room {
      data.append(chunk)
    } else {
      if room > 0 { data.append(chunk.prefix(room)) }
      truncated = true
    }
  }

  /// The body as text, and whether the cap cut it.
  func decoded() -> (body: String, truncated: Bool) {
    EventJournal.decodeBody(data, truncated: truncated)
  }
}
