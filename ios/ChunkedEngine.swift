import Foundation

// The pure half of task scheduling: the chunked window and the task identity
// encodings. Free of session state, so the high-consequence invariants (at
// most `window` part tasks per upload, never two for one part index) can be
// examined in one place. ChunkedCoordinator owns the session side.
enum ChunkedEngine {

  // The number of part tasks of one upload enqueued with the daemon at one
  // time. A library constant, not an option. It is also a liveness decision:
  // a background session only progresses tasks already enqueued, so `window`
  // tasks of runway let an upload proceed while the app is dead.
  static let window = 3

  /// The part indexes to enqueue now: pending (not accepted) and not already
  /// in flight, up to the free window slots. It never returns an index in
  /// `inFlight`: the one-task-per-part invariant. A part waiting out a
  /// backoff is in flight (its delayed task holds the slot).
  static func indexesToEnqueue(pending: [Int], inFlight: Set<Int>, window: Int = window) -> [Int] {
    let slots = window - inFlight.count
    guard slots > 0 else { return [] }
    return Array(pending.filter { !inFlight.contains($0) }.prefix(slots))
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
