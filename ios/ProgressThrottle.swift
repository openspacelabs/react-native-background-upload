import Foundation

/// Per-id progress throttle: one event per second while the app is in the
/// foreground, one per 10 minutes in the background. settle() forces a
/// trailing edge past it. It runs on the URLSession delegate queue, before
/// the hop onto the coordinator queue, so a chatty task never floods that
/// queue. Lock-guarded.
final class ProgressThrottle {
  static let foregroundMs = 1_000.0
  static let backgroundMs = 600_000.0

  private let lock = NSLock()
  private var last: [String: Double] = [:]
  private var foreground = false

  /// Set from the UIApplication notifications. Reading applicationState
  /// needs the main thread; the delegate queue is not it.
  var isForeground: Bool {
    get { lock.lock(); defer { lock.unlock() }; return foreground }
    set { lock.lock(); foreground = newValue; lock.unlock() }
  }

  /// true when an event for `id` may go now; records it.
  func shouldEmit(_ id: String, now: Double) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    let interval = foreground ? Self.foregroundMs : Self.backgroundMs
    if let t = last[id], now - t < interval { return false }
    last[id] = now
    return true
  }

  /// The next event for `id` passes. Called at issue and at settle.
  func reset(_ id: String) {
    lock.lock()
    last[id] = nil
    lock.unlock()
  }
}
