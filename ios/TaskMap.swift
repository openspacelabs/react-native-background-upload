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
    var partIndex: Int?
    var incarnation: String?
    var attempt: Int?
    var requestId: String?
    var headerGeneration: Int?
    var generation: Int?
    var purpose: Purpose?
    /// true when the task was created while the app was active. iOS starts
    /// a task created in the background at its own discretion and ignores
    /// isDiscretionary = false, so such a task is replaced when the app
    /// becomes active. nil in tasks created before this field: those count
    /// as background tasks.
    var foreground: Bool?

    init(id: String, partIndex: Int? = nil, incarnation: String? = nil, attempt: Int? = nil,
         requestId: String? = nil, headerGeneration: Int? = nil, generation: Int? = nil,
         purpose: Purpose? = nil, foreground: Bool? = nil) {
      self.id = id
      self.partIndex = partIndex
      self.incarnation = incarnation
      self.attempt = attempt
      self.requestId = requestId
      self.headerGeneration = headerGeneration
      self.generation = generation
      self.purpose = purpose
      self.foreground = foreground
    }

    private enum CodingKeys: String, CodingKey {
      case id, partIndex, incarnation, attempt, requestId, headerGeneration, generation, purpose, foreground
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
      foreground = try c.decodeIfPresent(Bool.self, forKey: .foreground)
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
    setPurpose(purpose, for: [(key: key, id: id)])
  }

  /// setPurpose for many tasks, in one write of the file.
  func setPurpose(_ purpose: Purpose, for tasks: [(key: String, id: String)]) {
    guard !tasks.isEmpty else { return }
    queue.sync {
      for (key, id) in tasks {
        var meta = cache[key] ?? Meta(id: id)
        meta.purpose = purpose
        cache[key] = meta
      }
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
