import Foundation

// Durable "<sessionId>:<taskIdentifier>" -> { id, accept, partIndex } mapping.
//
// Apple documents `taskDescription` only as an uninterpreted app string with no
// guarantee it survives process death, and DTS guidance is to persist task
// metadata externally keyed by the (stable) taskIdentifier. taskDescription
// stays the primary id. This map is the durable fallback. Thus a task
// observed after a relaunch is never orphaned under an unknown id, and the
// accept rules are still known when a task completes after the original
// startUpload options are gone. Chunked part tasks carry `partIndex`. Their
// accept rules live in the manifest, so `accept` is nil for them.
//
// Synchronous serial-queue access; a single JSON file.
enum TaskMap {
  struct Meta: Codable {
    let id: String
    // All are optional. Thus entries that older builds persisted still
    // decode.
    var accept: [UploadOutcome.AcceptRule]?
    var partIndex: Int?
    // Chunked part tasks only: the manifest incarnation that the task was
    // created under. It mirrors the taskDescription encoding (see
    // ChunkedEngine).
    var incarnation: String?

    init(id: String, accept: [UploadOutcome.AcceptRule]?, partIndex: Int?,
         incarnation: String? = nil) {
      self.id = id
      self.accept = accept
      self.partIndex = partIndex
      self.incarnation = incarnation
    }

    private enum CodingKeys: String, CodingKey {
      case id, accept, partIndex, incarnation
      // Earlier builds persisted `acceptStatus: [Int]` where this build
      // persists `accept` rules. The key is read, and never written. Thus a
      // task that an older build enqueued keeps its accept rules when it
      // completes under this build. This is the same legacy mapping as
      // Android's Upload.normalized().
      case acceptStatus
    }

    init(from decoder: Decoder) throws {
      let c = try decoder.container(keyedBy: CodingKeys.self)
      id = try c.decode(String.self, forKey: .id)
      partIndex = try c.decodeIfPresent(Int.self, forKey: .partIndex)
      incarnation = try c.decodeIfPresent(String.self, forKey: .incarnation)
      accept = try c.decodeIfPresent([UploadOutcome.AcceptRule].self, forKey: .accept)
        ?? c.decodeIfPresent([Int].self, forKey: .acceptStatus)?
        .map { UploadOutcome.AcceptRule(status: $0, bodyIncludes: nil) }
    }

    func encode(to encoder: Encoder) throws {
      var c = encoder.container(keyedBy: CodingKeys.self)
      try c.encode(id, forKey: .id)
      try c.encodeIfPresent(accept, forKey: .accept)
      try c.encodeIfPresent(partIndex, forKey: .partIndex)
      try c.encodeIfPresent(incarnation, forKey: .incarnation)
    }
  }

  private static let queue = DispatchQueue(label: "ai.openspace.rnbgupload.taskmap")

  static func key(_ session: URLSession, _ task: URLSessionTask) -> String {
    "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)"
  }

  private static var fileURL: URL {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    return base.appendingPathComponent("RNFileUploaderTaskMap.json")
  }

  static func set(_ meta: Meta, forKey key: String) {
    queue.sync {
      var map = read()
      map[key] = meta
      write(map)
    }
  }

  static func meta(forKey key: String) -> Meta? {
    queue.sync { read()[key] }
  }

  static func removeKey(_ key: String) {
    queue.sync {
      var map = read()
      map.removeValue(forKey: key)
      write(map)
    }
  }

  private static func read() -> [String: Meta] {
    guard let data = try? Data(contentsOf: fileURL) else { return [:] }
    return (try? JSONDecoder().decode([String: Meta].self, from: data)) ?? [:]
  }

  private static func write(_ map: [String: Meta]) {
    guard let data = try? JSONEncoder().encode(map) else { return }
    try? data.write(to: fileURL, options: .atomic)
  }
}
