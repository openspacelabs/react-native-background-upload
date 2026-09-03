import Foundation

/// The durable record of one chunked upload: the parts that the consumer
/// authored, and which of them the server has accepted. [ChunkedStore] saves
/// it at startUpload, BEFORE any task is enqueued. Thus a process that the
/// system relaunches (or a startUpload after a crash, a stop, or a reauth)
/// resumes from it without a call into JS. This manifest IS the resume
/// mechanism.
///
/// The content is the same as the Android manifest, with two platform
/// differences:
/// - There is no sourcePath field. iOS moves the app container between
///   launches, so an absolute path would go stale. The moved bytes live at a
///   location derived from the id (ChunkedStore.blobURL).
/// - `stalled` is persisted. On Android, "stalled" only means that the worker
///   is not scheduled. iOS reconciles every upload on relaunch. Thus an
///   upload that journaled a terminal outcome needs a durable marker that
///   says: await an explicit startUpload resume, and do not refill.
struct ChunkedManifest: Codable {
  /// One part, exactly as the consumer authored it. The library sends the
  /// file bytes [start, end) as the body of a PUT to `url`, with `headers`
  /// unchanged. It never derives or edits a protocol field.
  struct Part: Codable {
    let url: String
    var headers: [String: String]
    let start: Int64
    let end: Int64 // exclusive
    var accepted: Bool = false
    /// The non-transient HTTP rejections counted against this part's retry
    /// budget (nil means 0). It is persisted so that the budget survives
    /// process death. An in-memory count resets on every system wake. That
    /// would let a deterministic 4xx upload the part again until expiresAt,
    /// with no terminal ever journaled. The count resets when the part is
    /// rebuilt from an incoming call. Resume and recreate both do that (see
    /// [reconciled]).
    var rejections: Int?

    var size: Int64 { end - start }
  }

  let id: String
  var parts: [Part]
  var accept: [UploadOutcome.AcceptRule]
  /// Epoch ms. After this time, the upload stops with errorKind 'expired'.
  var expiresAt: Double
  var wifiOnly: Bool
  let createdAt: Double
  var stalled: Bool = false
  /// The identity of this parts plan. It rotates on a recreate (a startUpload
  /// that replaced the parts wholesale). It never rotates on a resume. Part
  /// tasks carry it in their identity. Thus a late delegate callback from a
  /// removed or replaced incarnation can be told apart from the live plan's
  /// callbacks and dropped. Its response is about byte ranges and URLs that
  /// this manifest no longer describes.
  var incarnation: String

  var totalBytes: Int64 { parts.reduce(0) { $0 + $1.size } }
  var acceptedBytes: Int64 { parts.filter(\.accepted).reduce(0) { $0 + $1.size } }

  /// The server's auto-publish condition. It is the only thing that
  /// 'completed' may mean.
  var allAccepted: Bool { parts.allSatisfy(\.accepted) }

  func isExpired(_ nowMs: Double) -> Bool { nowMs >= expiresAt }

  func pendingIndexes() -> [Int] { parts.indices.filter { !parts[$0].accepted } }

  func withPartAccepted(_ index: Int) -> ChunkedManifest {
    var next = self
    next.parts[index].accepted = true
    return next
  }

  func withPartRejections(_ index: Int, _ count: Int) -> ChunkedManifest {
    var next = self
    next.parts[index].rejections = count
    return next
  }

  struct ReconcileError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  /// A new startUpload call with an existing id is one of two things. The
  /// semantics are identical to Android's `ChunkedManifest.reconcile`.
  ///
  /// **Resume** — the incoming parts are the SAME array (identical count,
  /// ranges, and urls). The headers, the accept rules, expiresAt, and wifiOnly
  /// come from the new call. This is how fresh auth reaches stalled parts,
  /// and how a salvage extends the deadline. The accepted part statuses,
  /// createdAt, and the incarnation survive from this manifest. A resume is
  /// permitted at any time, running or not. The stall clears, because a
  /// resume is the whole point of the new call.
  ///
  /// **Recreate** — a DIFFERENT parts array: the consumer authored the upload
  /// again, under a fresh server uploadId, after the old one died. The owned
  /// bytes are kept. The parts are replaced wholesale. Every part status
  /// resets to unsent. The headers, accept rules, and expiresAt come from the
  /// new call. The new ranges must tile exactly [0, blobSize). A partial or
  /// overlapping cover would silently upload wrong bytes. A recreate is
  /// accepted only while the upload is NOT running (stalled on a terminal
  /// error or cancel, or expired). A different parts array while part tasks
  /// are live is a consumer bug, not a recreate, because the in-flight
  /// requests belong to the old parts. The incarnation rotates to the
  /// incoming manifest's fresh token. Thus late callbacks from the replaced
  /// parts are dropped.
  func reconciled(with incoming: ChunkedManifest, running: Bool,
                  blobSize: Int64) throws -> ChunkedManifest {
    if samePartsAs(incoming) {
      // Built from `incoming`, so the per-part rejection counts reset. A
      // resume arrives with fresh headers and gets a fresh retry budget.
      let mergedParts = incoming.parts.enumerated().map { i, new -> Part in
        var part = new
        part.accepted = parts[i].accepted
        return part
      }
      return ChunkedManifest(
        id: id, parts: mergedParts, accept: incoming.accept, expiresAt: incoming.expiresAt,
        wifiOnly: incoming.wifiOnly, createdAt: createdAt, stalled: false,
        incarnation: incarnation)
    }
    guard !running else {
      throw ReconcileError(message:
        "chunked upload '\(id)' is running; a different parts array is only accepted once it stops")
    }
    guard Self.tilesExactly(incoming.parts, size: blobSize) else {
      throw ReconcileError(message:
        "chunked upload '\(id)' recreate parts must tile exactly [0, \(blobSize))")
    }
    return ChunkedManifest(
      id: id, parts: incoming.parts, accept: incoming.accept, expiresAt: incoming.expiresAt,
      wifiOnly: incoming.wifiOnly, createdAt: createdAt, stalled: false,
      incarnation: incoming.incarnation)
  }

  private func samePartsAs(_ incoming: ChunkedManifest) -> Bool {
    incoming.parts.count == parts.count && parts.indices.allSatisfy { i in
      incoming.parts[i].url == parts[i].url
        && incoming.parts[i].start == parts[i].start
        && incoming.parts[i].end == parts[i].end
    }
  }

  /// Tells whether `parts` cover [0, size) exactly: no gap, no overlap, and
  /// nothing past the end. It is order-independent, like everything else
  /// about parts.
  static func tilesExactly(_ parts: [Part], size: Int64) -> Bool {
    guard !parts.isEmpty else { return false }
    var cursor: Int64 = 0
    for part in parts.sorted(by: { $0.start < $1.start }) {
      guard part.start == cursor, part.end > part.start else { return false }
      cursor = part.end
    }
    return cursor == size
  }

  struct ParseError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  /// Turns bridged options into a manifest. It throws on each field that the
  /// engine relies on. JS validates first. Thus a throw here is a bug worth
  /// surfacing, not UX.
  static func parse(_ options: [String: Any], createdAt: Double) throws -> ChunkedManifest {
    guard let id = options["id"] as? String, !id.isEmpty else {
      throw ParseError(message: "Missing 'id'")
    }
    guard let rawParts = options["parts"] as? [[String: Any]], !rawParts.isEmpty else {
      throw ParseError(message: "'parts' must be a non-empty array")
    }
    guard let expiresAt = (options["expiresAt"] as? NSNumber)?.doubleValue else {
      throw ParseError(message: "Missing 'expiresAt'")
    }
    let parts = try rawParts.enumerated().map { i, raw -> Part in
      guard let url = raw["url"] as? String else {
        throw ParseError(message: "Missing 'parts[\(i)].url'")
      }
      guard let range = raw["range"] as? [String: Any],
            let start = (range["start"] as? NSNumber)?.int64Value,
            let end = (range["end"] as? NSNumber)?.int64Value,
            start >= 0, start < end else {
        throw ParseError(message: "Invalid 'parts[\(i)].range'")
      }
      return Part(url: url, headers: parseHeaders(raw["headers"]), start: start, end: end)
    }
    return ChunkedManifest(
      id: id,
      parts: parts,
      accept: UploadOutcome.parseAcceptRules(options["accept"]),
      expiresAt: expiresAt,
      wifiOnly: (options["wifiOnly"] as? Bool) ?? false,
      createdAt: createdAt,
      incarnation: UUID().uuidString)
  }

  // The same header coercion as the simple-upload path: strings and numbers
  // only. Anything else is skipped. It is not interpolated onto the wire.
  private static func parseHeaders(_ raw: Any?) -> [String: String] {
    guard let headers = raw as? [String: Any] else { return [:] }
    var result: [String: String] = [:]
    for (key, value) in headers {
      if let s = value as? String {
        result[key] = s
      } else if let n = value as? NSNumber {
        result[key] = n.stringValue
      }
    }
    return result
  }
}

/// A file-backed store: one directory per upload id. The directory holds
/// `manifest.json`, `blob` (the moved source bytes), and the in-flight part
/// temp files. It has the same durability pattern as [EventJournal]: a
/// synchronous serial queue, atomic writes, and corrupt files read as
/// absent.
enum ChunkedStore {
  struct StoreError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  private static let queue = DispatchQueue(label: "ai.openspace.rnbgupload.chunkedstore")

  private static let dirURL: URL = {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    var dir = base.appendingPathComponent("RNFileUploaderChunked", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    // This is device-local upload state. Keep it out of iCloud/iTunes
    // backups.
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try? dir.setResourceValues(values)
    return dir
  }()

  // Upload ids come from the consumer. They can contain path separators or
  // other filesystem-hostile characters. Thus the directory name is an
  // encoding of the id, never the id itself. The id is read back from the
  // manifest, not decoded from the name.
  static func uploadDir(_ id: String) -> URL {
    dirURL.appendingPathComponent(
      Data(id.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: ""),
      isDirectory: true)
  }

  private static func manifestURL(_ id: String) -> URL {
    uploadDir(id).appendingPathComponent("manifest.json")
  }

  /// The location where startUpload moves the source file for this id.
  static func blobURL(_ id: String) -> URL {
    uploadDir(id).appendingPathComponent("blob")
  }

  /// The temp file that holds exactly the byte range of part `index` while
  /// that part is enqueued with the daemon (a background session can upload
  /// only from a file). The name encodes the manifest incarnation and the
  /// byte range. Thus a stale file from a previous incarnation or a different
  /// plan can never be adopted by a size coincidence. Reuse checks the full
  /// identity, not only the byte count.
  static func partFileURL(_ id: String, _ index: Int, incarnation: String,
                          start: Int64, end: Int64) -> URL {
    uploadDir(id).appendingPathComponent("part-\(index).\(incarnation).\(start)-\(end)")
  }

  /// The size in bytes of the moved blob. It is 0 when the blob is missing.
  static func blobSize(_ id: String) -> Int64 {
    queue.sync {
      (((try? FileManager.default.attributesOfItem(atPath: blobURL(id).path))?[.size]
        as? NSNumber)?.int64Value) ?? 0
    }
  }

  static func load(_ id: String) -> ChunkedManifest? {
    queue.sync { read(manifestURL(id)) }
  }

  /// Throws on a write failure. A manifest that did not persist must fail
  /// the startUpload call.
  static func save(_ manifest: ChunkedManifest) throws {
    try queue.sync {
      try FileManager.default.createDirectory(
        at: uploadDir(manifest.id), withIntermediateDirectories: true)
      let data = try JSONEncoder().encode(manifest)
      try data.write(to: manifestURL(manifest.id), options: .atomic)
    }
  }

  /// An atomic read-modify-write. Thus a mark of one part as accepted can
  /// never clobber a concurrent reconcile's fresh headers, or another part's
  /// flag. It returns nil, and does not throw, when the manifest is gone or
  /// the write failed. Callers that can proceed from memory do so.
  static func update(_ id: String, _ transform: (ChunkedManifest) -> ChunkedManifest) -> ChunkedManifest? {
    queue.sync {
      guard let manifest = read(manifestURL(id)) else { return nil }
      let next = transform(manifest)
      guard let data = try? JSONEncoder().encode(next) else { return nil }
      do {
        try data.write(to: manifestURL(id), options: .atomic)
        return next
      } catch {
        return nil
      }
    }
  }

  /// Deletes the manifest, the moved bytes, AND all part temp files. It does
  /// nothing for an unknown id (for example, a simple upload's id).
  static func remove(_ id: String) {
    queue.sync { try? FileManager.default.removeItem(at: uploadDir(id)) }
  }

  static func all() -> [ChunkedManifest] {
    queue.sync {
      let dirs = (try? FileManager.default.contentsOfDirectory(
        at: dirURL, includingPropertiesForKeys: nil)) ?? []
      return dirs.compactMap { read($0.appendingPathComponent("manifest.json")) }
    }
  }

  // Codable enforces the non-optional fields at decode time, unlike Gson.
  // Thus a corrupt or field-renamed file simply reads as absent.
  private static func read(_ url: URL) -> ChunkedManifest? {
    guard let data = try? Data(contentsOf: url) else { return nil }
    guard let m = try? JSONDecoder().decode(ChunkedManifest.self, from: data),
          !m.parts.isEmpty else { return nil }
    return m
  }

  /// Writes bytes [start, end) of the blob into `dest`. It writes a tmp file
  /// and renames it. Thus a partial write can never be mistaken for a
  /// finished part file. It throws when the blob is missing or shorter than
  /// `end`. For the caller that is a 'file' terminal, because a retry can
  /// never succeed.
  static func writePartFile(id: String, index: Int, start: Int64, end: Int64,
                            incarnation: String) throws -> URL {
    try queue.sync {
      let dest = partFileURL(id, index, incarnation: incarnation, start: start, end: end)
      // Sweep the other files of this index first. A temp file left by a
      // replaced incarnation or plan must not stay and leak disk. It cannot
      // be reused, because the identity is in the name, but it can pile up.
      removePartFilesLocked(id, index, keeping: dest)
      // An existing file with exactly this identity and size is a finished
      // copy from a previous enqueue of this part. Reuse it. Size alone is
      // not trusted. The name carries the incarnation and the range that
      // produced the file.
      if let size = try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber,
         size.int64Value == end - start {
        return dest
      }
      let blob = blobURL(id)
      let blobSize = ((try FileManager.default.attributesOfItem(atPath: blob.path)[.size]
        as? NSNumber)?.int64Value) ?? 0
      guard blobSize >= end else {
        throw StoreError(
          message: "source blob is \(blobSize) bytes; part \(index) needs [\(start), \(end))")
      }
      let tmp = uploadDir(id).appendingPathComponent("part-\(index).tmp")
      FileManager.default.createFile(atPath: tmp.path, contents: nil)
      let reader = try FileHandle(forReadingFrom: blob)
      defer { try? reader.close() }
      let writer = try FileHandle(forWritingTo: tmp)
      defer { try? writer.close() }
      try reader.seek(toOffset: UInt64(start))
      var remaining = end - start
      while remaining > 0 {
        let chunk = Int(min(remaining, 1 << 20))
        guard let data = try reader.read(upToCount: chunk), !data.isEmpty else {
          throw StoreError(message: "short read building part \(index)")
        }
        try writer.write(contentsOf: data)
        remaining -= Int64(data.count)
      }
      try? FileManager.default.removeItem(at: dest)
      try FileManager.default.moveItem(at: tmp, to: dest)
      return dest
    }
  }

  /// Removes every file for part `index`: the current incarnation's file,
  /// stale files, and half-written tmp files. They all share the
  /// `part-<index>.` prefix.
  static func removePartFile(_ id: String, _ index: Int) {
    queue.sync { removePartFilesLocked(id, index, keeping: nil) }
  }

  // Must run on `queue`. The `part-<index>.` prefix cannot collide across
  // indexes ("part-1." is not a prefix of "part-12.<...>").
  private static func removePartFilesLocked(_ id: String, _ index: Int, keeping: URL?) {
    let files = (try? FileManager.default.contentsOfDirectory(
      at: uploadDir(id), includingPropertiesForKeys: nil)) ?? []
    for file in files
    where file.lastPathComponent.hasPrefix("part-\(index).")
      && file.lastPathComponent != keeping?.lastPathComponent {
      try? FileManager.default.removeItem(at: file)
    }
  }
}
