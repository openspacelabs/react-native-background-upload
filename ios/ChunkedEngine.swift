import Foundation

// The pure half of task scheduling: which parts get a task, and the task
// identity encodings. Free of session state, so the high-consequence
// invariant (never two tasks for one part index) can be examined in one
// place. ChunkedCoordinator owns the session side.
enum ChunkedEngine {

  /// The part indexes to enqueue now: every pending (not accepted) part not
  /// already in flight. Every part gets its task at once, so the tasks are
  /// created while the caller is in mutate(), usually in the foreground. iOS
  /// starts a task created in the background at its own discretion. It never
  /// returns an index in `inFlight`: the one-task-per-part invariant. A part
  /// waiting out a backoff is in flight (its delayed task holds the slot).
  static func indexesToEnqueue(pending: [Int], inFlight: Set<Int>) -> [Int] {
    pending.filter { !inFlight.contains($0) }
  }

  // MARK: - Task identity

  // A task carries its owner through the daemon in taskDescription: a prefix
  // plus JSON, so an id with a colon or a slash survives. TaskMap holds the
  // same fields as the durable fallback.
  private static let partPrefix = "rnbgu-chunk:"
  private static let requestPrefix = "rnbgu-req:"

  private struct PartRef: Codable {
    let id: String
    let part: Int
    var inc: String?
  }

  private struct RequestRef: Codable {
    let id: String
    let gen: Int
    let att: Int
  }

  /// A chunked part task: (id, part index, incarnation).
  static func taskDescription(id: String, part: Int, incarnation: String) -> String {
    partPrefix + encode(PartRef(id: id, part: part, inc: incarnation))
  }

  static func parsePartDescription(_ description: String?) -> (id: String, part: Int, incarnation: String?)? {
    guard let ref: PartRef = decode(description, partPrefix) else { return nil }
    return (ref.id, ref.part, ref.inc)
  }

  /// A simple attempt: (id, entry generation, attempt ordinal).
  static func taskDescription(id: String, attempt: Int, generation: Int) -> String {
    requestPrefix + encode(RequestRef(id: id, gen: generation, att: attempt))
  }

  static func parseRequestDescription(_ description: String?) -> (id: String, generation: Int, attempt: Int)? {
    guard let ref: RequestRef = decode(description, requestPrefix) else { return nil }
    return (ref.id, ref.gen, ref.att)
  }

  private static func encode<T: Encodable>(_ value: T) -> String {
    let data = (try? JSONEncoder().encode(value)) ?? Data()
    return String(data: data, encoding: .utf8) ?? ""
  }

  private static func decode<T: Decodable>(_ description: String?, _ prefix: String) -> T? {
    guard let description, description.hasPrefix(prefix) else { return nil }
    return try? JSONDecoder().decode(T.self, from: Data(description.dropFirst(prefix.count).utf8))
  }
}
