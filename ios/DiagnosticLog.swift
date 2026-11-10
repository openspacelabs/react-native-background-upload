import Foundation

/// v9 work still on the device, from `QueueStore.countLeftovers`. The
/// counts are disjoint: legacyRows + unownedUploads is the number of v9 ids.
/// - legacyRows: legacy rows; legacyBytes: the v9 manifest and blob they keep.
/// - unownedUploads: v9 directories with no row; unownedBytes: their files;
///   unownedIds: their ids, sorted, at most `QueueStore.maxUnownedIds`, so
///   the app can cancel the ones it will not send again.
/// All zero once the app has adopted or cancelled every v9 id.
struct V9Leftovers: Equatable {
  var legacyRows = 0
  var legacyBytes: Int64 = 0
  var unownedUploads = 0
  var unownedBytes: Int64 = 0
  var unownedIds: [String] = []

  var dictionary: [String: Any] {
    ["legacyRows": legacyRows, "legacyBytes": legacyBytes,
     "unownedUploads": unownedUploads, "unownedBytes": unownedBytes, "unownedIds": unownedIds]
  }
}

/// Directories with no entry file that are not v9: the library's own
/// leftovers. `dirs` and `bytes` are what is left; `removedDirs` and
/// `removedBytes` are what the boot cleanup deleted.
struct Orphans: Equatable {
  var dirs = 0
  var bytes: Int64 = 0
  var removedDirs = 0
  var removedBytes: Int64 = 0

  var dictionary: [String: Any] {
    ["dirs": dirs, "bytes": bytes, "removedDirs": removedDirs, "removedBytes": removedBytes]
  }

  /// The counts after the cleanup deleted `removedDirs` directories and
  /// `removedBytes` bytes of the ones counted here.
  func afterCleanup(removedDirs: Int, removedBytes: Int64) -> Orphans {
    Orphans(dirs: max(0, dirs - removedDirs), bytes: max(0, bytes - removedBytes),
            removedDirs: removedDirs, removedBytes: removedBytes)
  }
}

/// What `QueueStore.countLeftovers` found. `orphanDirs`: the orphan
/// directories it counted, for `QueueStore.removeOrphans`.
struct Leftovers: Equatable {
  var v9 = V9Leftovers()
  var orphans = Orphans()
  var orphanDirs: [URL] = []
}

/// The records takeDiagnostics() returns on iOS, in the Android shape
/// `{ at, level, message, error?, data? }`. Holds the newest `maxRecords`,
/// as Android does, and drops the oldest first.
///
/// Kept in memory, not on disk: a record that no take() returned is gone
/// when the process ends.
///
/// `perform` runs work on this log's own serial queue, and `take` drains on
/// it too, so a take waits for work started before it, such as the boot
/// count. `warn` and `info` add a record at once, from any thread.
final class DiagnosticLog {
  static let maxRecords = 50
  /// The same message as the Android boot snapshot.
  static let bootMessage = "boot sweep"

  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.diagnostics", qos: .utility)
  private let lock = NSLock()
  private var records: [[String: Any]] = []
  private let clock: () -> Double

  /// `clock`: epoch ms. `warn` and `info` call it on their caller's thread
  /// when no `at` is given.
  init(clock: @escaping () -> Double) {
    self.clock = clock
  }

  /// `at`: epoch ms; nil means now, from the clock. `error` is written as
  /// `describe(error)`.
  func warn(_ message: String, error: Error? = nil, data: [String: Any]? = nil, at: Double? = nil) {
    let described = error.map(Self.describe)
    NSLog("[RNFileUploader] \(message)\(described.map { ": \($0)" } ?? "")")
    add("warn", message, error: described, data: data, at: at)
  }

  /// The error's type and message, the iOS match of Android's
  /// "<exception class>: <message>": "<NSError domain> <code>: <description>",
  /// for example "NSCocoaErrorDomain 257: The file couldn't be opened.".
  static func describe(_ error: Error) -> String {
    let ns = error as NSError
    return "\(ns.domain) \(ns.code): \(ns.localizedDescription)"
  }

  func info(_ message: String, data: [String: Any]? = nil, at: Double? = nil) {
    add("info", message, error: nil, data: data, at: at)
  }

  /// Runs `work` on the log's queue, before any take() called after this.
  func perform(_ work: @escaping () -> Void) {
    queue.async(execute: work)
  }

  /// Every record, oldest first, then none.
  func take(_ completion: @escaping ([[String: Any]]) -> Void) {
    queue.async {
      self.lock.lock()
      let out = self.records
      self.records = []
      self.lock.unlock()
      completion(out)
    }
  }

  private func add(_ level: String, _ message: String, error: String?, data: [String: Any]?, at: Double?) {
    var record: [String: Any] = ["at": at ?? clock(), "level": level, "message": message]
    if let error { record["error"] = error }
    if let data { record["data"] = data }
    lock.lock()
    records.append(record)
    if records.count > Self.maxRecords { records.removeFirst(records.count - Self.maxRecords) }
    lock.unlock()
  }
}
