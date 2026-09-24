import Foundation

// Small file helpers shared by the store, the journal, the task map and body
// staging. Every durable write is tmp + fsync + rename, so a reader never sees
// a half-written file: after a crash there is either the old file or the new
// one, plus at most a stray `.tmp` that the next write replaces.
enum FileIO {
  struct IOError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  static func tmpURL(for url: URL) -> URL {
    url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".tmp")
  }

  /// Writes `data` to `url.tmp`, flushes it to disk, then renames it onto `url`.
  static func writeAtomically(_ data: Data, to url: URL) throws {
    let tmp = tmpURL(for: url)
    try writeSynced(data, to: tmp)
    try rename(tmp, onto: url)
  }

  /// Writes and fsyncs a file in place. Callers rename it afterwards.
  static func writeSynced(_ data: Data, to url: URL) throws {
    let fm = FileManager.default
    try? fm.removeItem(at: url)
    guard fm.createFile(atPath: url.path, contents: nil) else {
      throw IOError(message: "cannot create \(url.lastPathComponent)")
    }
    let handle = try FileHandle(forWritingTo: url)
    defer { try? handle.close() }
    try handle.write(contentsOf: data)
    try handle.synchronize()
  }

  /// POSIX rename: atomic, and it replaces an existing destination.
  static func rename(_ from: URL, onto to: URL) throws {
    guard Foundation.rename(from.path, to.path) == 0 else {
      throw IOError(message: "rename \(from.lastPathComponent) -> \(to.lastPathComponent) failed: errno \(errno)")
    }
  }

  static func size(_ url: URL) -> Int64? {
    ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value
  }

  static func exists(_ url: URL) -> Bool {
    FileManager.default.fileExists(atPath: url.path)
  }

  /// Creates a directory that holds device-local upload state and keeps it
  /// out of iCloud and iTunes backups.
  static func makeLocalDirectory(_ url: URL) {
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    var dir = url
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try? dir.setResourceValues(values)
  }

  static func applicationSupport() -> URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
  }
}
