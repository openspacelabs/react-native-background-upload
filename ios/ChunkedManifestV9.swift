import Foundation

/// The v9 chunked manifest (`manifest.json`), kept only to read the files a
/// v9 build left behind. Decode only; v10 never writes it. A same-id enqueue
/// with the same parts adopts its accepted flags, incarnation and blob.
struct ChunkedManifestV9: Codable, Equatable {
  struct Part: Codable, Equatable {
    let url: String
    var headers: [String: String]
    let start: Int64
    let end: Int64
    var accepted: Bool = false
    var rejections: Int?

    var size: Int64 { end - start }
  }

  let id: String
  var parts: [Part]
  var accept: [UploadOutcome.AcceptRule]
  var expiresAt: Double
  var wifiOnly: Bool
  let createdAt: Double
  var stalled: Bool = false
  var incarnation: String

  /// v9 wrote the moved bytes at this fixed name.
  static let blobName = "blob"

  var totalBytes: Int64 { parts.reduce(0) { $0 + $1.size } }
  var acceptedBytes: Int64 { parts.filter(\.accepted).reduce(0) { $0 + $1.size } }

  /// Same count, and the same url and range at each index.
  func sameParts(as other: [QueueEntry.Part]) -> Bool {
    parts.count == other.count && parts.indices.allSatisfy {
      parts[$0].url == other[$0].url && parts[$0].start == other[$0].start
        && parts[$0].end == other[$0].end
    }
  }
}
