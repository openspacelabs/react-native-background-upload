import Foundation

/// The retry policy after every override is applied. Defaults are the spec's
/// table: base 1 s, max 2 h, jitter 0.2, exempt [404].
struct RetryPolicy: Codable, Equatable {
  var baseMs: Double
  var maxMs: Double
  var jitter: Double
  var exempt: [Int]

  static let defaults = RetryPolicy(baseMs: 1_000, maxMs: 7_200_000, jitter: 0.2, exempt: [404])

  /// Applies the overrides in order. A later override wins, field by field.
  static func resolve(_ overrides: [RetryOverride?]) -> RetryPolicy {
    var p = defaults
    for o in overrides.compactMap({ $0 }) {
      if let v = o.baseMs { p.baseMs = v }
      if let v = o.maxMs { p.maxMs = v }
      if let v = o.jitter { p.jitter = v }
      if let v = o.exempt { p.exempt = v }
    }
    return p
  }
}

/// A partial retry policy, as `configure({ retry })` or `descriptor.retry`
/// sends it: `{ backoff?: { baseMs, maxMs, jitter }, terminalHttp?: { exempt } }`.
/// Each field is optional, because JS checks names, not presence.
struct RetryOverride: Codable, Equatable {
  var baseMs: Double?
  var maxMs: Double?
  var jitter: Double?
  var exempt: [Int]?

  static func parse(_ raw: Any?) -> RetryOverride? {
    guard let r = raw as? [String: Any] else { return nil }
    let backoff = r["backoff"] as? [String: Any]
    let terminal = r["terminalHttp"] as? [String: Any]
    let o = RetryOverride(
      baseMs: (backoff?["baseMs"] as? NSNumber)?.doubleValue,
      maxMs: (backoff?["maxMs"] as? NSNumber)?.doubleValue,
      jitter: (backoff?["jitter"] as? NSNumber)?.doubleValue,
      exempt: (terminal?["exempt"] as? [NSNumber])?.map(\.intValue))
    return o == RetryOverride() ? nil : o
  }
}

/// Queue-wide settings, persisted as `settings.json` in the queue directory.
struct QueueSettings: Codable, Equatable {
  var wifiOnly = false
  /// The global pause gate: pause() / resume() with no keys.
  var paused = false
  /// Keys paused by pause({ keys }). An entry is paused when the gate is on
  /// or its key is in this set.
  var pausedKeys: Set<String> = []
  /// Bumped by every updateHeaders(). An attempt records the value it was
  /// issued under; a 401/403 from an older value re-issues instead of parking.
  var headerGeneration = 0
  var retry: RetryOverride?

  init() {}

  // Every field is optional on decode, so a settings file from an older build
  // (or a newer one) still loads.
  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    wifiOnly = try c.decodeIfPresent(Bool.self, forKey: .wifiOnly) ?? false
    paused = try c.decodeIfPresent(Bool.self, forKey: .paused) ?? false
    pausedKeys = try c.decodeIfPresent(Set<String>.self, forKey: .pausedKeys) ?? []
    headerGeneration = try c.decodeIfPresent(Int.self, forKey: .headerGeneration) ?? 0
    retry = try c.decodeIfPresent(RetryOverride.self, forKey: .retry)
  }

  /// true when either scope pauses entries of `key`.
  func isPaused(_ key: String) -> Bool { paused || pausedKeys.contains(key) }

  /// The queue setting unless the entry pins its own.
  func wifiOnly(_ entryWifiOnly: Bool?) -> Bool { entryWifiOnly ?? wifiOnly }

  /// configure(options). iOS reads `retry` only: JS turns lifetimeMs into
  /// each entry's expiresAt, and the Android notification keys are Android's.
  mutating func apply(configure options: [String: Any]) {
    retry = RetryOverride.parse(options["retry"])
  }
}
