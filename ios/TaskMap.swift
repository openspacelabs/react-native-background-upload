import Foundation

/// Durable "<sessionId>:<taskIdentifier>" -> Meta mapping.
///
/// Apple documents `taskDescription` only as an app string with no promise
/// that it survives process death, and DTS guidance is to persist task
/// metadata keyed by the stable taskIdentifier. taskDescription stays the
/// primary owner; this map is the durable fallback. Both are written before
/// the task resumes.
///
/// It also records why the library cancelled a task (`purpose`), so the
/// NSURLErrorCancelled callback can tell a pause or a supersede (no outcome)
/// from a cancel the system made (a transient retry).
///
/// One JSON file, cached in memory, written through on every change.
final class TaskMap {
  enum Purpose: String, Codable {
    case attempt
    case pause
    case superseded
  }

  struct Meta: Codable, Equatable {
    let id: String
    var accept: [UploadOutcome.AcceptRule]?
    var partIndex: Int?
    var incarnation: String?
    var attempt: Int?
    var requestId: String?
    var headerGeneration: Int?
    var generation: Int?
    var purpose: Purpose?

    init(id: String, accept: [UploadOutcome.AcceptRule]? = nil, partIndex: Int? = nil,
         incarnation: String? = nil, attempt: Int? = nil, requestId: String? = nil,
         headerGeneration: Int? = nil, generation: Int? = nil, purpose: Purpose? = nil) {
      self.id = id
      self.accept = accept
      self.partIndex = partIndex
      self.incarnation = incarnation
      self.attempt = attempt
      self.requestId = requestId
      self.headerGeneration = headerGeneration
      self.generation = generation
      self.purpose = purpose
    }

    private enum CodingKeys: String, CodingKey {
      case id, accept, partIndex, incarnation, attempt, requestId, headerGeneration, generation, purpose
      // Builds before v9 persisted `acceptStatus: [Int]`. Read, never written.
      case acceptStatus
    }

    init(from decoder: Decoder) throws {
      let c = try decoder.container(keyedBy: CodingKeys.self)
      id = try c.decode(String.self, forKey: .id)
      partIndex = try c.decodeIfPresent(Int.self, forKey: .partIndex)
      incarnation = try c.decodeIfPresent(String.self, forKey: .incarnation)
      attempt = try c.decodeIfPresent(Int.self, forKey: .attempt)
      requestId = try c.decodeIfPresent(String.self, forKey: .requestId)
      headerGeneration = try c.decodeIfPresent(Int.self, forKey: .headerGeneration)
      generation = try c.decodeIfPresent(Int.self, forKey: .generation)
      // An unknown purpose from a newer build reads as nil.
      purpose = (try? c.decodeIfPresent(String.self, forKey: .purpose)).flatMap { Purpose(rawValue: $0) }
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
      try c.encodeIfPresent(attempt, forKey: .attempt)
      try c.encodeIfPresent(requestId, forKey: .requestId)
      try c.encodeIfPresent(headerGeneration, forKey: .headerGeneration)
      try c.encodeIfPresent(generation, forKey: .generation)
      try c.encodeIfPresent(purpose, forKey: .purpose)
    }
  }

  static let shared = TaskMap(
    fileURL: FileIO.applicationSupport().appendingPathComponent("RNFileUploaderTaskMap.json"))

  let fileURL: URL
  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.taskmap")
  private var cache: [String: Meta]

  init(fileURL: URL) {
    self.fileURL = fileURL
    try? FileManager.default.createDirectory(
      at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
    cache = Self.read(fileURL)
  }

  static func key(_ session: URLSession, _ task: URLSessionTask) -> String {
    "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)"
  }

  func set(_ meta: Meta, forKey key: String) {
    queue.sync {
      cache[key] = meta
      write()
    }
  }

  func meta(forKey key: String) -> Meta? {
    queue.sync { cache[key] }
  }

  func removeKey(_ key: String) {
    queue.sync {
      guard cache.removeValue(forKey: key) != nil else { return }
      write()
    }
  }

  /// Records why the library is about to cancel a task. Creates the entry
  /// when the task has none (a v9 task).
  func setPurpose(_ purpose: Purpose, forKey key: String, id: String) {
    queue.sync {
      var meta = cache[key] ?? Meta(id: id)
      meta.purpose = purpose
      cache[key] = meta
      write()
    }
  }

  func setHeaderGeneration(_ generation: Int, forKey key: String) {
    queue.sync {
      guard cache[key] != nil else { return }
      cache[key]?.headerGeneration = generation
      write()
    }
  }

  func keys(where predicate: (Meta) -> Bool) -> [String] {
    queue.sync { cache.filter { predicate($0.value) }.map(\.key) }
  }

  func removeAll(where predicate: (String, Meta) -> Bool) {
    queue.sync {
      let before = cache.count
      cache = cache.filter { !predicate($0.key, $0.value) }
      if cache.count != before { write() }
    }
  }

  // Caller holds `queue`.
  private func write() {
    guard let data = try? JSONEncoder().encode(cache) else { return }
    do {
      try FileIO.writeAtomically(data, to: fileURL)
    } catch {
      NSLog("[RNFileUploader] task map write failed: \(error.localizedDescription)")
    }
  }

  private static func read(_ url: URL) -> [String: Meta] {
    guard let data = try? Data(contentsOf: url) else { return [:] }
    return (try? JSONDecoder().decode([String: Meta].self, from: data)) ?? [:]
  }
}
