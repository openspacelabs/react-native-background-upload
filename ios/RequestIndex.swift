import Foundation

/// The in-memory copy of every stored entry. getRequests() reads it
/// synchronously from the JS thread, so it never touches the disk. The
/// coordinator queue writes it after every store write. Guarded by a lock.
final class RequestIndex {
  private struct Item {
    let entry: QueueEntry
    /// varsJSON decoded once, at load or upsert.
    let vars: Any
  }

  private let lock = NSLock()
  private var items: [String: Item] = [:]

  func load(_ entries: [QueueEntry]) {
    lock.lock()
    defer { lock.unlock() }
    items = [:]
    for e in entries { items[e.id] = Item(entry: e, vars: JSONText.decode(e.varsJSON)) }
  }

  func upsert(_ entry: QueueEntry) {
    lock.lock()
    defer { lock.unlock() }
    // Decode again only when vars changed.
    if let old = items[entry.id], old.entry.varsJSON == entry.varsJSON {
      items[entry.id] = Item(entry: entry, vars: old.vars)
    } else {
      items[entry.id] = Item(entry: entry, vars: JSONText.decode(entry.varsJSON))
    }
  }

  /// Live progress while an entry runs: memory only, no save, no `state`
  /// event. The next commit of the entry saves it.
  func setBytes(_ id: String, _ bytesSent: Int64) {
    lock.lock()
    defer { lock.unlock() }
    guard let item = items[id] else { return }
    var entry = item.entry
    entry.bytesSent = bytesSent
    items[id] = Item(entry: entry, vars: item.vars)
  }

  func remove(_ id: String) {
    lock.lock()
    defer { lock.unlock() }
    items[id] = nil
  }

  func entry(_ id: String) -> QueueEntry? {
    lock.lock()
    defer { lock.unlock() }
    return items[id]?.entry
  }

  /// One RequestRow, with the cached vars.
  func row(_ id: String) -> [String: Any]? {
    lock.lock()
    defer { lock.unlock() }
    return items[id].map { $0.entry.row(vars: $0.vars) }
  }

  /// Every entry, oldest first.
  func entries() -> [QueueEntry] {
    lock.lock()
    defer { lock.unlock() }
    return items.values.map(\.entry).sorted(by: Self.order)
  }

  /// The RequestRow dictionaries, oldest first, for a stable order.
  func rows() -> [[String: Any]] {
    lock.lock()
    defer { lock.unlock() }
    return items.values.sorted { Self.order($0.entry, $1.entry) }.map { $0.entry.row(vars: $0.vars) }
  }

  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return items.count
  }

  private static func order(_ a: QueueEntry, _ b: QueueEntry) -> Bool {
    a.createdAt != b.createdAt ? a.createdAt < b.createdAt : a.id < b.id
  }
}
