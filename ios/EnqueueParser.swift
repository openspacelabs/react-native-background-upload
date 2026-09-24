import Foundation

/// What enqueue() received, validated. A throw here rejects E_INVALID: input
/// native cannot send.
struct ParsedEnqueue {
  enum Body {
    case none
    case data(json: String)
    case form([FormField])
    case file(path: String)
    case parts(file: String)
  }

  struct FormField: Equatable {
    let name: String
    let contentType: String
    let string: String?
    let path: String?
    let fileName: String?
  }

  let id: String
  let key: String
  let varsJSON: String
  let url: String?
  let method: String
  let headers: [String: String]
  let body: Body
  let parts: [QueueEntry.Part]
  let accept: [UploadOutcome.AcceptRule]
  let expiresAt: Double
  let retry: RetryOverride?
  /// nil when the descriptor has none: the entry follows the queue setting.
  let wifiOnly: Bool?
  /// Body identity for the same-id rules.
  let fingerprint: String
}

struct ParseError: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

enum EnqueueParser {
  static let methods: Set<String> = ["POST", "PUT", "PATCH", "DELETE", "GET"]

  /// Parses the bridged `{ id, key, varsJson, descriptor }`. `varsJson` and
  /// `descriptor.dataJson` are JSON text, because React Native on iOS drops
  /// object keys whose value is null. dataJson "null" is the JSON body null.
  /// NSNull counts as absent for the other optional fields.
  static func parse(_ raw: [String: Any]) throws -> ParsedEnqueue {
    guard let id = raw["id"] as? String, !id.isEmpty else { throw ParseError(message: "missing 'id'") }
    guard let key = raw["key"] as? String, !key.isEmpty else { throw ParseError(message: "missing 'key'") }
    guard let d = raw["descriptor"] as? [String: Any] else {
      throw ParseError(message: "missing 'descriptor'")
    }
    guard let varsJSON = raw["varsJson"] as? String, JSONText.parse(varsJSON) != nil else {
      throw ParseError(message: "'varsJson' must be JSON text")
    }
    // An object `data` would have lost its null-valued keys on the way here.
    guard d["data"] == nil else { throw ParseError(message: "'data' must cross as 'dataJson'") }
    let method = ((present(d["method"]) as? String) ?? "POST").uppercased()
    guard methods.contains(method) else { throw ParseError(message: "unknown method '\(method)'") }
    guard let expiresAt = (present(d["expiresAt"]) as? NSNumber)?.doubleValue else {
      throw ParseError(message: "missing 'expiresAt'")
    }

    let url = present(d["url"]) as? String
    if let url { try requireURL(url, "url") }
    var wifiOnly: Bool?
    if let raw = present(d["wifiOnly"]) {
      guard let b = raw as? Bool else { throw ParseError(message: "'wifiOnly' must be a boolean") }
      wifiOnly = b
    }

    var kinds: [ParsedEnqueue.Body] = []
    if let text = present(d["dataJson"]) {
      guard let json = text as? String, JSONText.parse(json) != nil else {
        throw ParseError(message: "'dataJson' must be JSON text")
      }
      kinds.append(.data(json: json))
    }
    if let form = present(d["form"]) { kinds.append(.form(try parseForm(form))) }
    let file = present(d["file"]) as? String
    var parts: [QueueEntry.Part] = []
    if let rawParts = present(d["parts"]) {
      guard let file else { throw ParseError(message: "'parts' requires 'file'") }
      parts = try parseParts(rawParts)
      kinds.append(.parts(file: file))
    } else if let file {
      kinds.append(.file(path: file))
    }
    guard kinds.count <= 1 else { throw ParseError(message: "more than one body kind") }
    guard url != nil || !parts.isEmpty else { throw ParseError(message: "'url' is required unless 'parts' is set") }
    let body = kinds.first ?? .none
    if method == "GET", !kinds.isEmpty { throw ParseError(message: "a GET request cannot have a body") }

    return ParsedEnqueue(
      id: id, key: key, varsJSON: varsJSON, url: url,
      method: method, headers: try headers(present(d["headers"])), body: body, parts: parts,
      accept: UploadOutcome.parseAcceptRules(present(d["accept"])), expiresAt: expiresAt,
      retry: RetryOverride.parse(present(d["retry"])), wifiOnly: wifiOnly,
      fingerprint: fingerprint(body, parts: parts, url: url, method: method))
  }

  /// Strings and numbers become headers. Anything else is skipped, never
  /// interpolated onto the wire ("<null>" in an Authorization header).
  /// Throws on a name that is not an HTTP token, or a value with CR, LF or
  /// NUL: URLRequest drops those without a word. The message names the
  /// header, never its value.
  static func headers(_ raw: Any?, field: String = "headers") throws -> [String: String] {
    guard let headers = raw as? [String: Any] else { return [:] }
    var result: [String: String] = [:]
    for (k, v) in headers {
      let value: String
      if let s = v as? String {
        value = s
      } else if let n = v as? NSNumber {
        value = n.stringValue
      } else {
        continue
      }
      guard isToken(k) else { throw ParseError(message: "'\(field)' has an invalid header name") }
      guard !hasLineBreakOrNUL(value) else {
        throw ParseError(message: "'\(field)' header '\(k)' has a line break or NUL in its value")
      }
      result[k] = value
    }
    return result
  }

  // RFC 9110 token: the characters a header name may use.
  private static let tokenChars = CharacterSet(charactersIn: "!#$%&'*+-.^_`|~")
    .union(CharacterSet(charactersIn: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"))

  // By scalar: "\r\n" is one Character, so a Character compare misses it.
  private static func hasLineBreakOrNUL(_ s: String) -> Bool {
    s.unicodeScalars.contains { $0 == "\r" || $0 == "\n" || $0 == "\0" }
  }

  private static func isToken(_ name: String) -> Bool {
    !name.isEmpty && name.unicodeScalars.allSatisfy { tokenChars.contains($0) }
  }

  /// Body identity, and the url and method: a different url or method is a
  /// different body. data: canonical JSON, so key order does not matter.
  /// form: the fields as sent; paths compare as strings, because the caller
  /// may have deleted the source after the first mutate() resolved. file: the
  /// path as sent. parts: url and range of each part; the `file` path is not
  /// part of it, because the moved blob is the body.
  static func fingerprint(_ body: ParsedEnqueue.Body, parts: [QueueEntry.Part], url: String?,
                          method: String) -> String {
    bodyFingerprint(body, parts: parts) + "|" + JSONText.sha256(method + " " + (url ?? ""))
  }

  private static func bodyFingerprint(_ body: ParsedEnqueue.Body, parts: [QueueEntry.Part]) -> String {
    switch body {
    case .none:
      return "none"
    case .data(let json):
      // Canonical text, so key order does not matter.
      let canonical = JSONText.parse(json).flatMap { JSONText.encode($0) } ?? json
      return "data:" + JSONText.sha256(canonical)
    case .form(let fields):
      let text = fields.map { f in
        [f.name, f.contentType, f.string.map { "s:" + $0 } ?? "", f.path.map { "p:" + $0 } ?? "",
         f.fileName ?? ""].map { $0.replacingOccurrences(of: "|", with: "||") }.joined(separator: "|")
      }.joined(separator: "\n")
      return "form:" + JSONText.sha256(text)
    case .file(let path):
      return "file:" + path
    case .parts:
      return "parts:" + JSONText.sha256(parts.map { "\($0.url)|\($0.start)|\($0.end)" }.joined(separator: "\n"))
    }
  }

  private static func present(_ value: Any?) -> Any? {
    value is NSNull ? nil : value
  }

  /// http or https with a host. The message leaves the URL out: its query
  /// may hold a token.
  private static func requireURL(_ s: String, _ field: String) throws {
    guard let u = URL(string: s), let scheme = u.scheme?.lowercased(),
          scheme == "http" || scheme == "https", let host = u.host, !host.isEmpty else {
      throw ParseError(message: "'\(field)' must be an http or https URL")
    }
  }

  private static func parseForm(_ raw: Any) throws -> [ParsedEnqueue.FormField] {
    guard let fields = raw as? [[String: Any]], !fields.isEmpty else {
      throw ParseError(message: "'form' must be a non-empty array")
    }
    return try fields.enumerated().map { i, f in
      guard let name = f["name"] as? String, let contentType = f["contentType"] as? String else {
        throw ParseError(message: "'form[\(i)]' needs name and contentType")
      }
      // The content type goes into the multipart part header as it is.
      guard !hasLineBreakOrNUL(contentType) else {
        throw ParseError(message: "'form[\(i)].contentType' has a line break")
      }
      let string = present(f["string"]) as? String
      let path = present(f["path"]) as? String
      guard (string == nil) != (path == nil) else {
        throw ParseError(message: "'form[\(i)]' must set exactly one of string, path")
      }
      return ParsedEnqueue.FormField(
        name: name, contentType: contentType, string: string, path: path,
        fileName: present(f["fileName"]) as? String)
    }
  }

  private static func parseParts(_ raw: Any) throws -> [QueueEntry.Part] {
    guard let parts = raw as? [[String: Any]], !parts.isEmpty else {
      throw ParseError(message: "'parts' must be a non-empty array")
    }
    return try parts.enumerated().map { i, p in
      guard let url = p["url"] as? String else { throw ParseError(message: "missing 'parts[\(i)].url'") }
      try requireURL(url, "parts[\(i)].url")
      guard let range = p["range"] as? [String: Any],
            let start = (range["start"] as? NSNumber)?.int64Value,
            let end = (range["end"] as? NSNumber)?.int64Value,
            start >= 0, start < end else {
        throw ParseError(message: "invalid 'parts[\(i)].range'")
      }
      return QueueEntry.Part(url: url, headers: try headers(present(p["headers"]), field: "parts[\(i)].headers"), start: start,
                             end: end, accepted: false, rejections: 0)
    }
  }
}
