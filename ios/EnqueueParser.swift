import Foundation

/// What enqueue() received, validated. JS validates first, so a throw here
/// is a bug worth surfacing (it rejects E_STORAGE), not a user error.
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
  let descriptorJSON: String
  let url: String?
  let method: String
  let headers: [String: String]
  let body: Body
  let parts: [QueueEntry.Part]
  let accept: [UploadOutcome.AcceptRule]
  let expiresAt: Double
  let retry: RetryOverride?
  /// Body identity for the same-id rules.
  let fingerprint: String
}

struct ParseError: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

enum EnqueueParser {
  static let methods: Set<String> = ["POST", "PUT", "PATCH", "DELETE", "GET"]

  /// Parses the bridged `{ id, key, vars, descriptor }`. NSNull counts as
  /// absent for every field but `data`, where it is the JSON body `null`
  /// (the JS contract accepts any JSON value). The bridge drops a JS
  /// `undefined` before native code sees it.
  static func parse(_ raw: [String: Any]) throws -> ParsedEnqueue {
    guard let id = raw["id"] as? String, !id.isEmpty else { throw ParseError(message: "missing 'id'") }
    guard let key = raw["key"] as? String, !key.isEmpty else { throw ParseError(message: "missing 'key'") }
    guard let d = raw["descriptor"] as? [String: Any] else {
      throw ParseError(message: "missing 'descriptor'")
    }
    guard let varsJSON = JSONText.encode(present(raw["vars"])) else {
      throw ParseError(message: "'vars' is not JSON")
    }
    // The data body lives in the staged file. Keeping it here too would
    // double it in entry.json, which is rewritten on every transition.
    var described = d
    described["data"] = nil
    guard let descriptorJSON = JSONText.encode(described) else {
      throw ParseError(message: "'descriptor' is not JSON")
    }
    let method = ((present(d["method"]) as? String) ?? "POST").uppercased()
    guard methods.contains(method) else { throw ParseError(message: "unknown method '\(method)'") }
    guard let expiresAt = (present(d["expiresAt"]) as? NSNumber)?.doubleValue else {
      throw ParseError(message: "missing 'expiresAt'")
    }

    let url = present(d["url"]) as? String
    if let url { try requireURL(url, "url") }

    var kinds: [ParsedEnqueue.Body] = []
    if d["data"] is NSNull {
      kinds.append(.data(json: "null"))
    } else if let data = d["data"] {
      guard let json = JSONText.encode(data) else { throw ParseError(message: "'data' is not JSON") }
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

    return ParsedEnqueue(
      id: id, key: key, varsJSON: varsJSON, descriptorJSON: descriptorJSON, url: url,
      method: method, headers: headers(present(d["headers"])), body: body, parts: parts,
      accept: UploadOutcome.parseAcceptRules(present(d["accept"])), expiresAt: expiresAt,
      retry: RetryOverride.parse(present(d["retry"])),
      fingerprint: fingerprint(body, parts: parts))
  }

  /// Strings and numbers become headers. Anything else is skipped, never
  /// interpolated onto the wire ("<null>" in an Authorization header).
  static func headers(_ raw: Any?) -> [String: String] {
    guard let headers = raw as? [String: Any] else { return [:] }
    var result: [String: String] = [:]
    for (k, v) in headers {
      if let s = v as? String {
        result[k] = s
      } else if let n = v as? NSNumber {
        result[k] = n.stringValue
      }
    }
    return result
  }

  /// Body identity. data: canonical JSON, so key order does not matter.
  /// form: the fields as sent; paths compare as strings, because the caller
  /// may have deleted the source after the first mutate() resolved. file: the
  /// path as sent. parts: url and range of each part; the `file` path is not
  /// part of it, because the moved blob is the body.
  static func fingerprint(_ body: ParsedEnqueue.Body, parts: [QueueEntry.Part]) -> String {
    switch body {
    case .none:
      return "none"
    case .data(let json):
      return "data:" + JSONText.sha256(json)
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

  private static func requireURL(_ s: String, _ field: String) throws {
    guard let u = URL(string: s), u.scheme != nil, u.host != nil else {
      throw ParseError(message: "'\(field)' is not a valid URL")
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
      return QueueEntry.Part(url: url, headers: headers(present(p["headers"])), start: start,
                             end: end, accepted: false, rejections: 0)
    }
  }
}
