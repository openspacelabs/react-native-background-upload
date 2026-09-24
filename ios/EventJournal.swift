import Foundation

/// The last response of a request, as the journal keeps it.
struct RawResponseRecord: Codable, Equatable {
  var status: Int?
  var headers: [String: String]?
  var body: String?
  var bodyTruncated: Bool

  var bridged: [String: Any] {
    var m: [String: Any] = ["bodyTruncated": bodyTruncated]
    if let status { m["status"] = status }
    if let headers { m["headers"] = headers }
    if let body { m["body"] = body }
    return m
  }
}

struct OutcomeErrorRecord: Codable, Equatable {
  var errorKind: String // http | network | file | expired | unknown
  var message: String
  var response: RawResponseRecord?
  var partIndex: Int?

  var bridged: [String: Any] {
    var m: [String: Any] = ["errorKind": errorKind, "message": message]
    if let response { m["response"] = response.bridged }
    if let partIndex { m["partIndex"] = partIndex }
    return m
  }
}

/// A terminal outcome, in the SettledEvent shape. Journaled before it is
/// emitted; deleted when JS acknowledges it.
struct JournaledEvent: Codable, Equatable {
  enum Kind: String, Codable { case completed, error, cancelled }

  let eventId: String
  let id: String
  let key: String
  let varsJSON: String
  let at: Double
  var attempts: Int
  var requestId: String?
  /// 0 when journaled; +1 on every emit and every getUnacknowledgedEvents.
  var deliveries: Int
  var bytesSent: Int64
  var totalBytes: Int64
  var url: String
  var method: String
  var partIndex: Int?
  /// The entry generation this outcome settled. An ack forgets the entry
  /// only when it still matches.
  var generation: Int
  var kind: Kind
  var response: RawResponseRecord?
  var error: OutcomeErrorRecord?
  var cancelReason: String?

  /// The SettledEvent dictionary. Nil fields are omitted, so nothing becomes
  /// NSNull; `vars` is the decoded object (NSNull for JS null).
  var bridged: [String: Any] {
    var m: [String: Any] = [
      "eventId": eventId, "id": id, "key": key, "vars": JSONText.decode(varsJSON), "at": at,
      "attempts": attempts, "deliveries": deliveries, "state": kind.rawValue,
      "bytesSent": bytesSent, "totalBytes": totalBytes, "url": url, "method": method,
      "kind": kind.rawValue,
    ]
    if let requestId { m["requestId"] = requestId }
    if let partIndex { m["partIndex"] = partIndex }
    switch kind {
    case .completed:
      m["response"] = (response ?? RawResponseRecord(bodyTruncated: false)).bridged
    case .error:
      m["error"] = (error ?? OutcomeErrorRecord(errorKind: "unknown", message: "")).bridged
    case .cancelled:
      m["cancelReason"] = cancelReason ?? "user"
    }
    return m
  }
}

/// The v9 journal entry, kept only for the one-time import as legacy rows.
struct JournaledEventV9: Codable, Equatable {
  let eventId: String
  let id: String
  var type: String // completed | error | cancelled
  let timestamp: Double
  var responseCode: Int?
  var errorKind: String?
  var cancelReason: String?
  var partIndex: Int?
}

/// Durable record of terminal outcomes: `RNFileUploaderEvents/<eventId>.json`,
/// one file per event, each written tmp + fsync + rename. Written BEFORE the
/// event is emitted and deleted only when JS acknowledges it, so an outcome
/// that fires while JS is dead survives to the next launch.
///
/// Synchronous on a serial queue, not an actor: the delegate must journal an
/// outcome before it emits, and an actor would make that ordering async.
final class EventJournal {
  static let shared = EventJournal(
    root: FileIO.applicationSupport().appendingPathComponent("RNFileUploaderEvents", isDirectory: true))

  /// The settled response body cap, in UTF-8 bytes.
  static let maxBodyBytes = 1_048_576
  /// The attempt event body cap, in characters.
  static let maxAttemptBodyChars = 4_096
  /// Runaway guard: past this count the oldest files a row does not name
  /// are dropped.
  let maxEntries: Int

  let root: URL
  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.journal")

  init(root: URL, maxEntries: Int = 1000) {
    self.root = root
    self.maxEntries = maxEntries
    FileIO.makeLocalDirectory(root)
  }

  /// Returns false when the write failed. `keeping` holds the eventIds that
  /// rows still name; the prune never deletes them, because an entry whose
  /// outcome file is gone cannot be acked.
  @discardableResult
  func append(_ event: JournaledEvent, keeping: Set<String> = []) -> Bool {
    queue.sync {
      do {
        try write(event)
      } catch {
        NSLog("[RNFileUploader] journal append failed: \(error.localizedDescription)")
        return false
      }
      pruneToMax(keeping: keeping.union([event.eventId]))
      return true
    }
  }

  func load(_ eventId: String) -> JournaledEvent? {
    queue.sync { read(url(eventId)) }
  }

  /// deliveries += 1 on each event, persisted. Returns the updated events in
  /// the order of `ids`; unknown ids are skipped. Every emit and every
  /// getUnacknowledgedEvents goes through this.
  func markDelivered(_ ids: [String]) -> [JournaledEvent] {
    queue.sync {
      ids.compactMap { id in
        guard var e = read(url(id)) else { return nil }
        e.deliveries += 1
        do {
          try write(e)
        } catch {
          NSLog("[RNFileUploader] journal update failed: \(error.localizedDescription)")
        }
        return e
      }
    }
  }

  /// Every v10 event, oldest first. v9-shaped files are left for the import.
  func unacknowledged() -> [JournaledEvent] {
    queue.sync { jsonFiles().compactMap(read).sorted { ($0.at, $0.eventId) < ($1.at, $1.eventId) } }
  }

  func unacknowledgedForId(_ id: String) -> [JournaledEvent] {
    unacknowledged().filter { $0.id == id }
  }

  /// Deletes the files. Unknown ids are ignored, so ack is idempotent.
  func ack(_ ids: [String]) {
    queue.sync {
      for id in ids { try? FileManager.default.removeItem(at: url(id)) }
    }
  }

  /// cancel() on a settled entry: its unacked outcomes go with it.
  func removeForId(_ id: String) {
    ack(unacknowledgedForId(id).map(\.eventId))
  }

  /// v9 entries: files that decode as the v9 shape (have `type` and
  /// `timestamp`, no `kind`).
  func legacyEvents() -> [JournaledEventV9] {
    queue.sync {
      jsonFiles().compactMap { file -> JournaledEventV9? in
        guard read(file) == nil, let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(JournaledEventV9.self, from: data)
      }
    }
  }

  func removeLegacy(_ eventId: String) {
    queue.sync { _ = try? FileManager.default.removeItem(at: url(eventId)) }
  }

  /// Decodes a response body capped at `cap` bytes. A cut that splits a
  /// UTF-8 sequence backs off to the last whole character.
  static func decodeBody(_ data: Data, cap: Int = maxBodyBytes, truncated: Bool = false)
    -> (body: String, truncated: Bool) {
    guard data.count > cap || truncated else {
      return (String(decoding: data, as: UTF8.self), false)
    }
    var cut = data.prefix(cap)
    for _ in 0..<4 {
      if let s = String(data: cut, encoding: .utf8) { return (s, true) }
      cut = cut.dropLast()
    }
    return (String(decoding: data.prefix(cap), as: UTF8.self), true)
  }

  /// A character cap, for the 4 KB attempt body.
  static func capChars(_ s: String?, _ max: Int) -> (String?, Bool) {
    guard let s, s.count > max else { return (s, false) }
    return (String(s.prefix(max)), true)
  }

  // MARK: - Private (callers hold `queue`)

  private func url(_ eventId: String) -> URL {
    // eventId is a UUID the library minted, but keep a hostile one inside root.
    root.appendingPathComponent(eventId.replacingOccurrences(of: "/", with: "_") + ".json")
  }

  private func write(_ event: JournaledEvent) throws {
    try FileIO.writeAtomically(try JSONEncoder().encode(event), to: url(event.eventId))
  }

  private func read(_ file: URL) -> JournaledEvent? {
    guard let data = try? Data(contentsOf: file) else { return nil }
    return try? JSONDecoder().decode(JournaledEvent.self, from: data)
  }

  private func jsonFiles() -> [URL] {
    ((try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [])
      .filter { $0.pathExtension == "json" }
  }

  // Drops the oldest unnamed files by modification time, without parsing.
  // When rows name more than maxEntries events, the count stays above it.
  private func pruneToMax(keeping: Set<String>) {
    let key: URLResourceKey = .contentModificationDateKey
    guard let files = try? FileManager.default.contentsOfDirectory(
      at: root, includingPropertiesForKeys: [key]) else { return }
    let jsons = files.filter { $0.pathExtension == "json" }
    guard jsons.count > maxEntries else { return }
    let kept = Set(keeping.map { url($0).lastPathComponent })
    let candidates = jsons.filter { !kept.contains($0.lastPathComponent) }.sorted {
      let a = (try? $0.resourceValues(forKeys: [key]).contentModificationDate) ?? .distantPast
      let b = (try? $1.resourceValues(forKeys: [key]).contentModificationDate) ?? .distantPast
      return a < b
    }
    for f in candidates.prefix(jsons.count - maxEntries) {
      try? FileManager.default.removeItem(at: f)
    }
  }
}
