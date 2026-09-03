import Foundation

// The pure classification of upload outcomes. It mirrors the Android
// UploadOutcome. This is the highest-consequence logic in the uploader,
// because it decides between success and failure. Thus it lives in a small
// unit with no session state, where it is easy to examine.
enum UploadOutcome {

  /// A non-2xx response to treat as success. `bodyIncludes` narrows the rule
  /// by a response-body substring. This is necessary when one status has
  /// several meanings, and only the message shows the difference (our
  /// backend's 409). It is Codable: it is persisted in the chunked manifest
  /// and in the TaskMap metadata.
  struct AcceptRule: Codable, Equatable {
    let status: Int
    var bodyIncludes: String?
  }

  // Tells whether an HTTP response counts as a successful completion. A 2xx
  // always counts, plus any matching per-request accept rule. Anything else,
  // 4xx and 5xx included, is an http error, not a completion.
  static func isAccepted(_ code: Int, body: String?, accept: [AcceptRule]) -> Bool {
    if (200..<300).contains(code) { return true }
    return accept.contains { rule in
      rule.status == code
        && (rule.bodyIncludes == nil || body?.contains(rule.bodyIncludes!) == true)
    }
  }

  // The bridge delivers `accept` as an array of dictionaries. A malformed
  // rule is dropped, not guessed at. JS validates the shape before it
  // crosses.
  static func parseAcceptRules(_ raw: Any?) -> [AcceptRule] {
    guard let rules = raw as? [[String: Any]] else { return [] }
    return rules.compactMap { rule in
      guard let status = (rule["status"] as? NSNumber)?.intValue else { return nil }
      return AcceptRule(status: status, bodyIncludes: rule["bodyIncludes"] as? String)
    }
  }
}
