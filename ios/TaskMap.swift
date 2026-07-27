import Foundation

// Durable "<sessionId>:<taskIdentifier>" -> { id, acceptStatus } mapping.
//
// Apple documents `taskDescription` only as an uninterpreted app string with no
// guarantee it survives process death, and DTS guidance is to persist task
// metadata externally keyed by the (stable) taskIdentifier. taskDescription
// stays the primary id; this map is the durable fallback so a task observed
// after relaunch is never orphaned under an unknown id, and so acceptStatus is
// still known when a task completes after the original startUpload options are gone.
//
// Synchronous serial-queue access; a single JSON file.
enum TaskMap {
  struct Meta: Codable {
    let id: String
    let acceptStatus: [Int]
  }

  private static let queue = DispatchQueue(label: "ai.openspace.rnbgupload.taskmap")

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
