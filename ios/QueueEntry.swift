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
    /// This part's count of the responses each `retry.transient` rule has
    /// excused in this generation, by `TransientRule.identity`. Each part has
    /// its own count, so each gets the rule's full maxRetries. nil before the
    /// first; optional, so a part saved before the field decodes.
    var transientHitsByRule: [String: Int]? = nil

    var size: Int64 { end - start }
  }

  let id: String
  var key: String
  var varsJSON: String
  /// nil only for a chunked entry whose descriptor has no url.
  var url: String?
  var method: String
  var accept: [UploadOutcome.AcceptRule]
  var retry: RetryOverride?
  /// descriptor.wifiOnly. nil follows the queue setting (setWifiOnly), so a
  /// toggle moves this entry; true or false pins it.
  var wifiOnly: Bool?
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
  /// The parts-plan identity, and the identity of this use of the id. A new
  /// random value at create and when the body is replaced, so no two uses of
  /// an id share one. Part tasks and simple tasks carry it, so a late
  /// callback from a replaced plan or from an earlier use of the id is
  /// dropped. A same-body reopen keeps it: the generation tells those apart.
  /// A v9 chunked upload that create() resumes keeps its v9 value.
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
  /// A simple entry's count of the responses each `retry.transient` rule has
  /// excused in this generation, by `TransientRule.identity`. A chunked entry
  /// counts on each Part instead. nil before the first; a reopen starts over.
  /// Optional, so an entry saved before the field decodes. A pre-release
  /// build wrote a positional array under the key `transientHits`; Codable
  /// ignores that unknown key, so it reads as no hits.
  var transientHitsByRule: [String: Int]? = nil
  var bytesSent: Int64
  var totalBytes: Int64
  /// Epoch ms. Not read: this version never expires an entry. Versions
  /// 10.0.0 to 11.3.0 settle an entry 'expired' once this time passes, and
  /// their decoder requires the field. So a new or re-queued entry stores
  /// noDeadline, and an app that goes back to such a version does not
  /// expire it. An entry an earlier version stored, and that is not queued
  /// again, keeps the value it had.
  var expiresAt: Double
  var nextAttemptAt: Double? // epoch ms, set while a delayed retry waits
  var settledEventId: String?
  var lastRequestId: String?
  var lastUrl: String?
  var lastPartIndex: Int?
  /// An imported v9 journal row: read-only, never scheduled.
  var legacy: Bool
  let createdAt: Double
  var updatedAt: Double
  /// true when this use of the id started in a build that writes
  /// `incarnation` into every simple task. Then a simple task with no
  /// incarnation comes from an earlier use. nil in an entry that 11.3.0 or
  /// earlier created or saved: its tasks may carry no incarnation. Those
  /// releases ignore the field and drop it at their next save of the entry.
  var tasksCarryIncarnation: Bool? = nil
}

extension QueueEntry {
  var isLive: Bool {
    switch state {
    case .queued, .running, .awaitingAuth, .paused: return true
    case .completed, .error, .cancelled: return false
    }
  }

  var isSettled: Bool { !isLive }

  /// The expiresAt of a new or re-queued entry: the largest time a
  /// JavaScript Date holds, 8.64e15 ms. Exact in a Double. In 10.0.0 to
  /// 11.3.0 a time check against it is never true, and their expiry timer
  /// caps its wait at 24 hours before its one Int conversion, so nothing
  /// overflows.
  static let noDeadline: Double = 8_640_000_000_000_000

  /// Whether a simple task that carries `incarnation` (nil: none) belongs
  /// to this use of the id. A task with a value must carry this entry's. A
  /// task with none is from 11.3.0 or earlier. It matches only an entry such
  /// a release created or saved, as every task did before, so a task in
  /// flight across the upgrade still drives its entry and is not sent again.
  func ownsSimpleTask(incarnation task: String?) -> Bool {
    guard let task else { return tasksCarryIncarnation != true }
    return task == incarnation
  }
  var isChunked: Bool { bodyKind == .parts }

  var acceptedBytes: Int64 { parts.filter(\.accepted).reduce(0) { $0 + $1.size } }

  /// The counts a response to `part` is classified against: the part's for a
  /// chunked entry, the entry's for a simple one (`part` nil).
  func transientHits(part: Int?) -> [String: Int] {
    guard let part else { return transientHitsByRule ?? [:] }
    return parts.indices.contains(part) ? parts[part].transientHitsByRule ?? [:] : [:]
  }

  /// One more response excused by `rule`, on `part` or (nil) on the entry.
  mutating func recordTransientHit(_ rule: UploadOutcome.TransientRule, part: Int?) {
    guard let part else {
      transientHitsByRule = (transientHitsByRule ?? [:]).merging([rule.identity: 1], uniquingKeysWith: +)
      return
    }
    guard parts.indices.contains(part) else { return }
    parts[part].transientHitsByRule =
      (parts[part].transientHitsByRule ?? [:]).merging([rule.identity: 1], uniquingKeysWith: +)
  }
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
    var e = QueueEntry(
      id: p.id, key: p.key, varsJSON: p.varsJSON, url: p.url, method: p.method, accept: p.accept, retry: p.retry,
      wifiOnly: p.wifiOnly, bodyKind: staged.kind, bodyPath: staged.relativePath,
      bodyContentType: staged.contentType, forceContentType: staged.forceContentType,
      bodyFingerprint: p.fingerprint, parts: p.parts, incarnation: UUID().uuidString,
      headers: p.headers, headerGeneration: headerGeneration,
      state: paused ? .paused : .queued, authParked: false, generation: 1, attempts: 0,
      bytesSent: 0, totalBytes: staged.totalBytes, expiresAt: noDeadline,
      nextAttemptAt: nil, settledEventId: nil, lastRequestId: nil, lastUrl: nil,
      lastPartIndex: nil, legacy: false, createdAt: createdAt ?? now, updatedAt: now)
    e.tasksCarryIncarnation = true
    return e
  }

  /// Rule 3, same body: the new vars, headers and descriptor fields replace
  /// the stored ones, and expiresAt becomes noDeadline. The body, url,
  /// method, accepted parts, generation and createdAt stay (a different url
  /// or method is a different body). `resetBudget` (a reopen, which starts a new generation) also
  /// resets attempts, part rejections and every transient count: attempts
  /// count one generation. A live entry keeps its counts, the parts' included.
  func resumed(with p: ParsedEnqueue, resetBudget: Bool, now: Double) -> QueueEntry {
    var next = self
    next.key = p.key
    next.varsJSON = p.varsJSON
    next.headers = p.headers
    next.expiresAt = Self.noDeadline
    next.accept = p.accept
    next.retry = p.retry
    next.wifiOnly = p.wifiOnly
    // Same parts by definition of "same body"; the incoming ones carry the
    // new per-part headers.
    if isChunked, p.parts.count == parts.count {
      next.parts = p.parts.enumerated().map { i, part in
        var merged = part
        merged.accepted = parts[i].accepted
        merged.rejections = resetBudget ? 0 : parts[i].rejections
        merged.transientHitsByRule = resetBudget ? nil : parts[i].transientHitsByRule
        return merged
      }
    }
    if resetBudget {
      next.attempts = 0
      next.transientHitsByRule = nil
    }
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

  /// Replaces only the names `base` already carries, in any case. A part's
  /// own header (say Authorization) takes the patched value; a name the
  /// part does not carry is left to the entry headers.
  static func replaceExisting(_ base: [String: String], _ patch: [String: String]) -> [String: String] {
    let carried = Set(base.keys.map { $0.lowercased() })
    return merge(base, patch.filter { carried.contains($0.key.lowercased()) })
  }

  static func value(_ name: String, in headers: [String: String]) -> String? {
    let lower = name.lowercased()
    return headers.first { $0.key.lowercased() == lower }?.value
  }
}
