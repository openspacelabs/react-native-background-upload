import Foundation

// The pure scheduling half of chunked execution: window arithmetic, the
// retry policy, backoff, and the part-task identity encoding. It is kept free
// of session state. Thus the highest-consequence invariants (at most WINDOW
// part tasks enqueued per upload, and never two for one part index) can be
// examined in one place. [ChunkedCoordinator] owns the session side.
enum ChunkedEngine {

  // The number of part tasks of one upload enqueued with the daemon at one
  // time. It is a library constant, not an option: if soak data argues for a
  // different value, this constant changes, not the API. The window is also a
  // liveness decision. A background session only progresses tasks that are
  // already enqueued. Thus WINDOW tasks of runway let a multi-part upload
  // proceed while the app is dead. Without them, the upload pays a
  // rate-limited wake per part.
  static let window = 3

  // A non-accepted, non-transient HTTP response is retried this many times
  // for each part. Then it becomes a terminal error and stalls the upload.
  // The number is small on purpose. A response that the server repeats (401,
  // 400) will not change without a new startUpload. Only transient failures
  // retry without a limit.
  static let partHttpRetries = 3

  private static let backoffBaseMs = 1_000
  private static let backoffCapMs = 60_000

  // A 5xx means that the server fails, not that the request is wrong. Thus
  // it retries like a transport failure: without a limit, within expiresAt.
  static func isTransientHttp(_ code: Int) -> Bool { (500...599).contains(code) }

  /// Exponential backoff for transient failures: 1s, 2s, 4s, up to a 60s cap.
  static func backoffMs(attempt: Int) -> Int {
    min(backoffBaseMs << min(max(attempt - 1, 0), 6), backoffCapMs)
  }

  /// The part indexes to enqueue now: pending (not accepted), not already
  /// enqueued, and not cooling down after a failure, up to the window size.
  /// It never returns an index in `inFlight`. That is the one-task-per-part
  /// invariant.
  static func indexesToEnqueue(
    pending: [Int], inFlight: Set<Int>, cooling: Set<Int>, window: Int = window
  ) -> [Int] {
    let slots = window - inFlight.count
    guard slots > 0 else { return [] }
    return Array(pending.filter { !inFlight.contains($0) && !cooling.contains($0) }.prefix(slots))
  }

  // MARK: - Part-task identity

  // A chunked part task must carry (uploadId, partIndex, incarnation)
  // through the daemon. taskDescription is the primary carrier. It is a
  // prefix plus JSON, so a consumer id that contains a delimiter survives.
  // TaskMap holds the same triple as the durable fallback, per the DTS
  // guidance that TaskMap documents. The incarnation is the manifest token
  // that the task was created under. A callback whose token no longer matches
  // the stored manifest's token is from a removed or replaced plan. It must
  // not write into the current plan.
  private static let descriptionPrefix = "rnbgu-chunk:"

  private struct PartRef: Codable {
    let id: String
    let part: Int
    var inc: String?
  }

  static func taskDescription(id: String, part: Int, incarnation: String) -> String {
    let data = (try? JSONEncoder().encode(PartRef(id: id, part: part, inc: incarnation))) ?? Data()
    return descriptionPrefix + (String(data: data, encoding: .utf8) ?? "")
  }

  static func parseTaskDescription(
    _ description: String?
  ) -> (id: String, part: Int, incarnation: String?)? {
    guard let description, description.hasPrefix(descriptionPrefix) else { return nil }
    let json = Data(description.dropFirst(descriptionPrefix.count).utf8)
    guard let ref = try? JSONDecoder().decode(PartRef.self, from: json) else { return nil }
    return (ref.id, ref.part, ref.inc)
  }
}
