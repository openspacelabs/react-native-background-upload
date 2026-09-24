import Foundation

/// The pure half of the first-launch v9 import: which legacy rows to create.
///
/// Each v9 journal entry becomes a read-only settled row with key "legacy"
/// and id = the v9 upload id. Nothing is delivered: Diana reads the rows,
/// marks those transfers terminal, and cancels them. When the id also has a
/// v9 chunked manifest, the row reports its bytes, and the blob stays in the
/// directory until cancel(id).
///
/// A manifest with no journal entry makes no row. It stays dormant until a
/// same-id enqueue adopts it (a legacy row would be cancelled by Diana, and
/// the capture re-send needs the bytes).
enum LegacyImport {
  static let key = "legacy"
  static let fingerprint = "legacy"

  static func plan(events: [JournaledEventV9], manifests: [String: ChunkedManifestV9]) -> [QueueEntry] {
    // One row per id: the latest v9 outcome wins.
    var latest: [String: JournaledEventV9] = [:]
    for e in events where latest[e.id].map({ $0.timestamp <= e.timestamp }) ?? true {
      latest[e.id] = e
    }
    return latest.values.sorted { ($0.timestamp, $0.id) < ($1.timestamp, $1.id) }.compactMap { e in
      guard let state = state(e.type) else { return nil }
      let manifest = manifests[e.id]
      return QueueEntry(
        id: e.id, key: key, varsJSON: "null", descriptorJSON: "{}", url: nil, method: "POST",
        accept: [], retry: nil, bodyKind: .none,
        bodyPath: manifest == nil ? nil : ChunkedManifestV9.blobName,
        bodyContentType: nil, forceContentType: false, bodyFingerprint: fingerprint, parts: [],
        incarnation: manifest?.incarnation ?? UUID().uuidString, headers: [:], headerGeneration: 0,
        state: state, authParked: false, generation: 1, attempts: 0,
        bytesSent: manifest?.acceptedBytes ?? 0, totalBytes: manifest?.totalBytes ?? 0,
        expiresAt: manifest?.expiresAt ?? e.timestamp, nextAttemptAt: nil, settledEventId: nil,
        lastRequestId: nil, lastUrl: nil, lastPartIndex: e.partIndex, legacy: true,
        createdAt: e.timestamp, updatedAt: e.timestamp)
    }
  }

  static func state(_ v9Type: String) -> QueueEntry.State? {
    switch v9Type {
    case "completed": return .completed
    case "error": return .error
    case "cancelled": return .cancelled
    default: return nil
    }
  }
}
