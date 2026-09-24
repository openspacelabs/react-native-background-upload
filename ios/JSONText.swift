import CryptoKit
import Foundation

// JSON text for values that come over the bridge (NSDictionary, NSArray,
// NSString, NSNumber, NSNull). `vars`, the descriptor and a `data` body are
// persisted as text and decoded again when a row or an event is built.
enum JSONText {
  /// Canonical JSON text: keys sorted, fragments allowed. The same value with
  /// its keys in another order gives the same text. Nil when the value is not
  /// JSON (a NaN, a non-string key). The check runs first because
  /// JSONSerialization raises an Obj-C exception, not a Swift error, on an
  /// invalid object.
  static func encode(_ value: Any?) -> String? {
    let v: Any = value ?? NSNull()
    guard JSONSerialization.isValidJSONObject([v]),
          let data = try? JSONSerialization.data(
            withJSONObject: v, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes])
    else { return nil }
    return String(data: data, encoding: .utf8)
  }

  /// The bridged object for stored text. NSNull (JS null) when the text is
  /// "null" or does not parse.
  static func decode(_ text: String) -> Any {
    (try? JSONSerialization.jsonObject(with: Data(text.utf8), options: [.fragmentsAllowed]))
      ?? NSNull()
  }

  static func sha256(_ text: String) -> String {
    SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
  }
}
