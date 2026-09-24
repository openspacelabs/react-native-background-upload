import Foundation

/// Builds the live `attempt` event: one HTTP attempt before the library
/// interprets it for retry. `outcome` follows the v8 taxonomy: 'completed'
/// only for a 2xx or a matching accept rule; any other response is 'error'
/// with errorKind 'http'; a transport failure is 'error' with its kind; a
/// cancel the library did not ask for is 'cancelled' with 'system'.
enum AttemptEvent {
  struct Input {
    var id: String
    var key: String
    var requestId: String
    var attempt: Int
    var url: String
    var method: String
    var partIndex: Int?
    var statusCode: Int?
    var headers: [String: String]
    var body: String?
    var error: NSError?
    var accepted: Bool
    var systemCancel: Bool
    var at: Double
  }

  static func build(_ i: Input) -> [String: Any] {
    var m: [String: Any] = [
      "id": i.id, "key": i.key, "requestId": i.requestId, "attempt": i.attempt,
      "url": i.url, "method": i.method, "at": i.at,
    ]
    if let partIndex = i.partIndex { m["partIndex"] = partIndex }
    if let code = i.statusCode, i.error == nil {
      m["httpCode"] = code
      m["responseHeaders"] = i.headers
      let (body, truncated) = EventJournal.capChars(i.body ?? "", EventJournal.maxAttemptBodyChars)
      m["responseBody"] = body ?? ""
      m["responseBodyTruncated"] = truncated
    }
    if i.systemCancel {
      m["outcome"] = "cancelled"
      m["cancelReason"] = "system"
    } else if let error = i.error {
      m["outcome"] = "error"
      m["errorKind"] = RetryClassifier.errorKind(for: error)
      m["errorMessage"] = error.localizedDescription
    } else if i.accepted {
      m["outcome"] = "completed"
    } else {
      m["outcome"] = "error"
      m["errorKind"] = "http"
      m["errorMessage"] = "HTTP \(i.statusCode ?? 0)"
    }
    return m
  }
}
