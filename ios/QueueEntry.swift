import Foundation

/// One durable queue entry: what JS sent at enqueue(), the staged body, and
/// where the entry is in its life. Saved as `entry.json` in the entry's
/// directory (see QueueStore). A pure model: no I/O here.
///
/// It generalizes the v9 chunked manifest. A chunked entry keeps the v9
/// fields (parts, accepted flags, incarnation) and gains the queue fields.
struct QueueEntry: Codable, Equatable {
  enum State: String, Codable {
    case queued, running, awaitingAuth = "awaiting-auth", paused, completed, error, cancelled
  }

  enum BodyKind: String, Codable { case none, data, form, file, parts }

  /// One part of a chunked upload, exactly as the consumer authored it. The
  /// library sends the file bytes [start, end) to `url`.
  struct Part: Codable, Equatable {
    let url: String
    var headers: [String: String]
    let start: Int64
    let end: Int64 // exclusive
    var accepted: Bool
    /// Failed attempts of this part since its last success or resume. Drives
    /// the backoff exponent. Persisted, so a relaunch does not reset it.
    var rejections: Int

    var size: Int64 { end - start }
  }

  let id: String
  var key: String
  var varsJSON: String
  var descriptorJSON: String
  /// nil only for a chunked entry whose descriptor has no url.
  var url: String?
  var method: String
  var accept: [UploadOutcome.AcceptRule]
  var retry: RetryOverride?
  var bodyKind: BodyKind
  /// File name inside the entry directory: "body-<uuid>" or a blob name.
  /// nil for a legacy row with no bytes.
  var bodyPath: String?
  /// Content-Type the staged body needs (JSON or multipart).
  var bodyContentType: String?
  /// true for a multipart body: its boundary is ours, so our Content-Type wins.
  var forceContentType: Bool
  /// The body identity for the same-id rules. See EnqueueParser.fingerprint.
  var bodyFingerprint: String
  var parts: [Part]
  /// The parts-plan identity. Rotates when the body is replaced. Part tasks
  /// carry it, so a late callback from a replaced plan is dropped.
  var incarnation: String
  /// The merged request headers. updateHeaders() patches them.
  var headers: [String: String]
  /// settings.headerGeneration at the last header write.
  var headerGeneration: Int
  var state: State
  /// true while parked on a 401/403. Survives a pause.
  var authParked: Bool
  /// Bumps when a settled entry reopens or its body is replaced. An ack
  /// forgets the entry only when the event's generation matches.
  var generation: Int
  /// HTTP attempts issued, parts included. The current simple attempt's
  /// ordinal equals this value.
  var attempts: Int
  var bytesSent: Int64
  var totalBytes: Int64
  var expiresAt: Double // epoch ms
  var nextAttemptAt: Double? // epoch ms, set while a delayed retry waits
  var settledEventId: String?
  var lastRequestId: String?
  var lastUrl: String?
  var lastPartIndex: Int?
  /// An imported v9 journal row: read-only, never scheduled.
  var legacy: Bool
  let createdAt: Double
  var updatedAt: Double
}

extension QueueEntry {
  var isLive: Bool {
    switch state {
    case .queued, .running, .awaitingAuth, .paused: return true
    case .completed, .error, .cancelled: return false
    }
  }

  var isSettled: Bool { !isLive }
  var isChunked: Bool { bodyKind == .parts }

  var acceptedBytes: Int64 { parts.filter(\.accepted).reduce(0) { $0 + $1.size } }
  var allAccepted: Bool { !parts.isEmpty && parts.allSatisfy(\.accepted) }
  func pendingIndexes() -> [Int] { parts.indices.filter { !parts[$0].accepted } }

  /// The url a SettledEvent reports when no attempt has run yet.
  var targetURL: String { lastUrl ?? url ?? parts.first?.url ?? "" }

  func withPartAccepted(_ index: Int) -> QueueEntry {
    var next = self
    next.parts[index].accepted = true
    next.parts[index].rejections = 0
    next.bytesSent = next.acceptedBytes
    return next
  }

  /// true when `parts` cover [0, size) exactly: no gap, no overlap, nothing
  /// past the end.
  static func tilesExactly(_ parts: [Part], size: Int64) -> Bool {
    guard !parts.isEmpty else { return false }
    var cursor: Int64 = 0
    for part in parts.sorted(by: { $0.start < $1.start }) {
      guard part.start == cursor, part.end > part.start else { return false }
      cursor = part.end
    }
    return cursor == size
  }

  /// Same parts = same count, and the same url and range at each index.
  static func sameParts(_ a: [Part], _ b: [Part]) -> Bool {
    a.count == b.count && a.indices.allSatisfy {
      a[$0].url == b[$0].url && a[$0].start == b[$0].start && a[$0].end == b[$0].end
    }
  }

  /// Rule 2: a new entry.
  static func created(from p: ParsedEnqueue, staged: StagedBody, headerGeneration: Int,
                      paused: Bool, now: Double, createdAt: Double? = nil) -> QueueEntry {
    QueueEntry(
      id: p.id, key: p.key, varsJSON: p.varsJSON, descriptorJSON: p.descriptorJSON,
      url: p.url, method: p.method, accept: p.accept, retry: p.retry,
      bodyKind: staged.kind, bodyPath: staged.relativePath,
      bodyContentType: staged.contentType, forceContentType: staged.forceContentType,
      bodyFingerprint: p.fingerprint, parts: p.parts, incarnation: UUID().uuidString,
      headers: p.headers, headerGeneration: headerGeneration,
      state: paused ? .paused : .queued, authParked: false, generation: 1, attempts: 0,
      bytesSent: 0, totalBytes: staged.totalBytes, expiresAt: p.expiresAt,
      nextAttemptAt: nil, settledEventId: nil, lastRequestId: nil, lastUrl: nil,
      lastPartIndex: nil, legacy: false, createdAt: createdAt ?? now, updatedAt: now)
  }

  /// Rule 3, same body: the new vars, headers, expiresAt and descriptor
  /// fields replace the stored ones. The body, accepted parts, generation and
  /// createdAt stay. `resetBudget` (a reopen) also resets attempts and part
  /// rejections, because a resume brings fresh headers.
  func resumed(with p: ParsedEnqueue, resetBudget: Bool, now: Double) -> QueueEntry {
    var next = self
    next.key = p.key
    next.varsJSON = p.varsJSON
    next.descriptorJSON = p.descriptorJSON
    next.url = p.url
    next.method = p.method
    next.headers = p.headers
    next.expiresAt = p.expiresAt
    next.accept = p.accept
    next.retry = p.retry
    // Same parts by definition of "same body"; the incoming ones carry the
    // new per-part headers.
    if isChunked, p.parts.count == parts.count {
      next.parts = p.parts.enumerated().map { i, part in
        var merged = part
        merged.accepted = parts[i].accepted
        merged.rejections = resetBudget ? 0 : parts[i].rejections
        return merged
      }
    }
    if resetBudget { next.attempts = 0 }
    next.updatedAt = now
    return next
  }

  /// Rule 4, different body: a new body and plan, a new generation, state
  /// queued. The caller moves it to paused when the queue is paused.
  func replaced(with p: ParsedEnqueue, staged: StagedBody, now: Double) -> QueueEntry {
    var next = QueueEntry.created(from: p, staged: staged, headerGeneration: headerGeneration,
                                  paused: false, now: now, createdAt: createdAt)
    next.generation = generation + 1
    return next
  }

  /// The RequestRow dictionary. `vars` is the decoded object (the index
  /// caches it). nextAttemptAt is omitted when nil, so nothing becomes NSNull.
  func row(vars: Any) -> [String: Any] {
    var r: [String: Any] = [
      "id": id,
      "key": key,
      "vars": vars,
      "state": state.rawValue,
      "bytesSent": state == .completed ? totalBytes : bytesSent,
      "totalBytes": totalBytes,
      "attempts": attempts,
      "updatedAt": updatedAt,
    ]
    if let nextAttemptAt { r["nextAttemptAt"] = nextAttemptAt }
    return r
  }
}

/// Header names match without regard to case, as in the JS layer.
enum HeaderMerge {
  /// `over` wins. A name in `base` that `over` sets in another case is
  /// dropped, so the request never carries both spellings.
  static func merge(_ base: [String: String], _ over: [String: String]) -> [String: String] {
    let overridden = Set(over.keys.map { $0.lowercased() })
    var result = base.filter { !overridden.contains($0.key.lowercased()) }
    for (k, v) in over { result[k] = v }
    return result
  }

  static func value(_ name: String, in headers: [String: String]) -> String? {
    let lower = name.lowercased()
    return headers.first { $0.key.lowercased() == lower }?.value
  }
}
