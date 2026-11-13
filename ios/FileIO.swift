import Foundation

// Small file helpers shared by the store, the journal, the task map and body
// staging. Every durable write is tmp + fsync + rename, so a reader never sees
// a half-written file: after a crash there is either the old file or the new
// one, plus at most a stray `.tmp` that the next write replaces. The rename
// itself survives a power loss only after its directory is synced, so
// `writeAtomically` syncs the directory after every rename, as Android does.
enum FileIO {
  struct IOError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  static func tmpURL(for url: URL) -> URL {
    url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".tmp")
  }

  /// Writes `data` to `url.tmp`, flushes it to disk, then renames it onto `url`.
  /// On a failure the tmp file is deleted, so a partly written file does not
  /// hold space that a retry needs.
  static func writeAtomically(_ data: Data, to url: URL) throws {
    // Runs last: after the rename, so the new name is what the sync makes
    // durable. After a failure, it makes the tmp delete durable.
    defer { syncDirectoryOrLog(url.deletingLastPathComponent()) }
    let tmp = tmpURL(for: url)
    do {
      try writeSynced(data, to: tmp)
      try rename(tmp, onto: url)
    } catch {
      try? FileManager.default.removeItem(at: tmp)
      throw error
    }
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

  /// Flushes a directory. Tests replace it to watch the syncs or fail them.
  static var syncDirectory: (URL) throws -> Void = fsyncDirectory

  /// Flushes a directory, so the names that renames and new files put in it
  /// survive a power loss. Plain fsync, as Android does. On Apple platforms
  /// fsync does not make the drive empty its cache; F_FULLFSYNC would.
  static func fsyncDirectory(_ url: URL) throws {
    let fd = open(url.path, O_RDONLY)
    guard fd >= 0 else {
      throw IOError(message: "cannot open \(url.lastPathComponent) to sync it: errno \(errno)")
    }
    defer { close(fd) }
    guard fsync(fd) == 0 else {
      throw IOError(message: "fsync \(url.lastPathComponent) failed: errno \(errno)")
    }
  }

  /// A failed sync is logged, never thrown: the rename it follows already
  /// happened, so the write still counts.
  static func syncDirectoryOrLog(_ url: URL) {
    do {
      try syncDirectory(url)
    } catch {
      NSLog("[RNFileUploader] cannot sync \(url.lastPathComponent): \(error.localizedDescription)")
    }
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
