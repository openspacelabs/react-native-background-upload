import Foundation

// A terminal upload outcome, persisted before it is emitted to JS.
struct JournaledEvent: Codable {
  let eventId: String
  let id: String            // upload id
  var type: String          // completed | error | cancelled
  let timestamp: Double     // epoch ms
  var responseCode: Int?
  var responseBody: String?
  var responseBodyTruncated: Bool?
  var responseHeaders: [String: String]?
  var error: String?
  var errorKind: String?    // http | network | file | expired | unknown
  var cancelReason: String? // user | system
  var partIndex: Int?       // chunked uploads only: the failing part, when known

  // Bridge-friendly dictionary (nil fields omitted so nothing becomes NSNull).
  var bridged: [String: Any] {
    var m: [String: Any] = ["eventId": eventId, "id": id, "type": type, "timestamp": timestamp]
    if let responseCode { m["responseCode"] = responseCode }
    if let responseBody { m["responseBody"] = responseBody }
    if let responseBodyTruncated { m["responseBodyTruncated"] = responseBodyTruncated }
    if let responseHeaders { m["responseHeaders"] = responseHeaders }
    if let error { m["error"] = error }
    if let errorKind { m["errorKind"] = errorKind }
    if let cancelReason { m["cancelReason"] = cancelReason }
    if let partIndex { m["partIndex"] = partIndex }
    return m
  }
}

// Durable record of terminal upload events (completed / error / cancelled).
// Written BEFORE the event is emitted to JS, deleted only when JS acknowledges,
// so an outcome that fires while JS is dead survives to the next launch.
//
// Synchronous (serial queue) — deliberately NOT an actor. The URLSession delegate
// is synchronous and must journal an outcome BEFORE emitting it; an actor would
// force that ordering to become async and racy.
//
// One JSON file per event: Data.write(atomically:) is its own tmp+rename, so a
// crash mid-write can't corrupt other entries, and separate files avoid a shared
// mutable file across processes.
enum EventJournal {
  static let maxBodyChars = 64 * 1024
  static let maxEntries = 1000

  private static let queue = DispatchQueue(label: "ai.openspace.rnbgupload.journal")

  // Char-count cap (a byte-accurate split could cut a surrogate pair). Single
  // source of truth so the journaled body and the live-emitted body match.
  static func capBody(_ body: String?) -> (String?, Bool) {
    guard let body, body.count > maxBodyChars else { return (body, false) }
    return (String(body.prefix(maxBodyChars)), true)
  }

  // Computed once: creating the dir and re-setting the backup flag on every
  // append/read/ack call is wasteful.
  private static let dirURL: URL = {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    var dir = base.appendingPathComponent("RNFileUploaderEvents", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    // Transient device-local state; keep it out of iCloud/iTunes backups.
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try? dir.setResourceValues(values)
    return dir
  }()

  static func append(_ event: JournaledEvent) {
    queue.sync {
      var e = event
      let (body, truncated) = capBody(e.responseBody)
      if truncated {
        e.responseBody = body
        e.responseBodyTruncated = true
      }
      // A journal write must never throw into the caller: the delegate calls this
      // right after a completed upload, and a propagated failure could misfire the
      // error path. Dropping one entry is the lesser evil.
      guard let data = try? JSONEncoder().encode(e) else { return }
      do {
        try data.write(to: dirURL.appendingPathComponent("\(e.eventId).json"), options: .atomic)
      } catch {
        NSLog("[RNFileUploader] journal append failed: \(error.localizedDescription)")
        return
      }
      pruneToMax()
    }
  }

  static func unacknowledged() -> [[String: Any]] {
    unacknowledgedEntries().map { $0.bridged }
  }

  static func unacknowledgedEntries() -> [JournaledEvent] {
    queue.sync {
      let files = (try? FileManager.default.contentsOfDirectory(at: dirURL, includingPropertiesForKeys: nil)) ?? []
      return files
        .filter { $0.pathExtension == "json" }
        .compactMap { url -> JournaledEvent? in
          guard let data = try? Data(contentsOf: url) else { return nil }
          return try? JSONDecoder().decode(JournaledEvent.self, from: data)
        }
        .sorted { $0.timestamp < $1.timestamp }
    }
  }

  static func ack(_ eventIds: [String]) {
    queue.sync {
      for id in eventIds {
        try? FileManager.default.removeItem(at: dirURL.appendingPathComponent("\(id).json"))
      }
    }
  }

  // Runaway guard: assumes JS drains via ack on each boot, but bounds the
  // directory if that loop breaks or hasn't been adopted. Drops the oldest by
  // file modification time (no parsing). Caller already holds `queue`.
  private static func pruneToMax() {
    let key: URLResourceKey = .contentModificationDateKey
    guard let files = try? FileManager.default.contentsOfDirectory(
      at: dirURL, includingPropertiesForKeys: [key]) else { return }
    let jsons = files.filter { $0.pathExtension == "json" }
    guard jsons.count > maxEntries else { return }
    let sorted = jsons.sorted {
      let a = (try? $0.resourceValues(forKeys: [key]).contentModificationDate) ?? .distantPast
      let b = (try? $1.resourceValues(forKeys: [key]).contentModificationDate) ?? .distantPast
      return a < b
    }
    for f in sorted.prefix(jsons.count - maxEntries) {
      try? FileManager.default.removeItem(at: f)
    }
  }
}
