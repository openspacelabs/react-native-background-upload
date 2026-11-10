import Foundation

/// v9 work still on the device, from `QueueStore.countV9Leftovers`: the
/// legacy rows, and the v9 uploads no v10 entry owns with their size in
/// bytes. All zero once the app has adopted or cancelled every v9 id.
struct V9Leftovers: Equatable {
  var legacyRows = 0
  var unownedUploads = 0
  var unownedBytes: Int64 = 0

  var dictionary: [String: Any] {
    ["legacyRows": legacyRows, "unownedUploads": unownedUploads, "unownedBytes": unownedBytes]
  }
}

/// The records takeDiagnostics() returns on iOS: one boot snapshot per
/// process start, with the v9 leftover count, and a warning for each error
/// of that count. Records use the Android shape `{ at, level, message,
/// error?, data? }`.
///
/// Kept in memory, not on disk. Each process start counts again, so a
/// record that no JS took is replaced by the next start's count.
///
/// The count runs on this object's own serial queue, so it delays neither
/// the thread that creates the coordinator nor the coordinator queue. A
/// take runs on the same queue, after the count, so the first take of a
/// process has the snapshot.
final class BootDiagnostics {
  /// The same message as the Android boot snapshot.
  static let message = "boot sweep"

  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.diagnostics", qos: .utility)
  /// Read and written on `queue` only.
  private var records: [[String: Any]] = []

  /// Counts the v9 leftovers and records the snapshot, at `at` (epoch ms).
  func start(store: QueueStore, legacyIds: [String], at: Double) {
    queue.async {
      let (counts, warnings) = store.countV9Leftovers(legacyIds: legacyIds)
      for w in warnings {
        NSLog("[RNFileUploader] \(w.message): \(w.error)")
        self.records.append(["at": at, "level": "warn", "message": w.message, "error": w.error])
      }
      self.records.append(["at": at, "level": "info", "message": Self.message,
                           "data": ["v9Leftovers": counts.dictionary]])
    }
  }

  /// Every record, oldest first, then none.
  func take(_ completion: @escaping ([[String: Any]]) -> Void) {
    queue.async {
      let out = self.records
      self.records = []
      completion(out)
    }
  }
}
