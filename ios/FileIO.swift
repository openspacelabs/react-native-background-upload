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
    try? FileManager.default.removeItem(at: url)
    let handle = try create(url)
    defer { try? handle.close() }
    try handle.write(contentsOf: data)
    try handle.synchronize()
  }

  /// Creates (or truncates) a file and opens it for writing. POSIX `open`,
  /// not `FileManager.createFile`, so a failure carries its errno.
  static func create(_ url: URL) throws -> FileHandle {
    let fd = open(url.path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
    guard fd >= 0 else { throw posixError(errno, "cannot create \(url.lastPathComponent)") }
    return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
  }

  /// POSIX rename: atomic, and it replaces an existing destination.
  static func rename(_ from: URL, onto to: URL) throws {
    guard Foundation.rename(from.path, to.path) == 0 else {
      throw posixError(errno, "rename \(from.lastPathComponent) -> \(to.lastPathComponent) failed")
    }
  }

  /// An NSPOSIXErrorDomain error whose description names the step and the errno.
  static func posixError(_ code: Int32, _ step: String) -> NSError {
    NSError(domain: NSPOSIXErrorDomain, code: Int(code), userInfo: [
      NSLocalizedDescriptionKey: "\(step): errno \(code) (\(String(cString: strerror(code))))",
    ])
  }

  /// The underlying error of an E_STORAGE rejection, as a suffix for its
  /// message: ` (cause: <domain> <code>; POSIX <n>; <description>)`. The
  /// POSIX code comes from the NSUnderlyingErrorKey chain and is left out
  /// when the error is itself POSIX or the chain holds none. Pass
  /// `description: false` when the message already holds the description.
  ///
  /// A later option: also pass the same fields as a structured
  /// `userInfo.cause` on the rejection. Not done: the module rejects with a
  /// nil NSError, and it is not checked that RN brings `userInfo` to JS.
  static func cause(_ error: Error, description: Bool = true) -> String {
    let ns = error as NSError
    var fields = ["\(ns.domain) \(ns.code)"]
    if ns.domain != NSPOSIXErrorDomain, let posix = underlyingPOSIXCode(ns) { fields.append("POSIX \(posix)") }
    if description { fields.append(ns.localizedDescription) }
    return " (cause: \(fields.joined(separator: "; ")))"
  }

  private static func underlyingPOSIXCode(_ error: NSError) -> Int? {
    var next = error.userInfo[NSUnderlyingErrorKey] as? NSError
    for _ in 0..<8 {
      guard let e = next else { return nil }
      if e.domain == NSPOSIXErrorDomain { return e.code }
      next = e.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return nil
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
