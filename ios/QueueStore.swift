import Foundation

/// The durable queue: one directory per entry id under
/// `Application Support/RNFileUploaderChunked/`. It generalizes the v9
/// chunked store in place, so v9 bytes and manifests survive the upgrade.
///
/// An entry directory holds:
/// - `entry.json`: the QueueEntry (v10).
/// - `body-<uuid>` or `blob-<uuid>` / `blob`: the staged body.
/// - `part-<i>.<incarnation>.<start>-<end>`: a chunked part while in flight.
/// - `manifest.json`: a v9 manifest, until a same-id enqueue adopts it.
///
/// Next to the directories: `settings.json` and the `v10-imported` marker.
/// Writes are tmp + fsync + rename. A corrupt or half-written file reads as
/// absent. Synchronous on a private serial queue, like the v9 store.
final class QueueStore {
  static let shared = QueueStore(
    root: FileIO.applicationSupport().appendingPathComponent("RNFileUploaderChunked", isDirectory: true))

  let root: URL
  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.store")

  static let entryName = "entry.json"
  static let manifestName = "manifest.json"
  private static let settingsName = "settings.json"
  private static let importedMarker = "v10-imported"

  init(root: URL) {
    self.root = root
    FileIO.makeLocalDirectory(root)
  }

  // MARK: - Paths

  /// Ids come from the consumer and may hold path separators. The directory
  /// name is url-safe base64 of the id, never the id itself.
  func dir(_ id: String) -> URL {
    root.appendingPathComponent(
      Data(id.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: ""),
      isDirectory: true)
  }

  func fileURL(_ id: String, _ relative: String) -> URL {
    dir(id).appendingPathComponent(relative)
  }

  /// The staged body of an entry, or nil for a legacy row with no bytes.
  func bodyURL(_ entry: QueueEntry) -> URL? {
    entry.bodyPath.map { fileURL(entry.id, $0) }
  }

  // MARK: - Entries

  /// Throws on a write failure. An entry that did not persist must fail the
  /// enqueue.
  func save(_ entry: QueueEntry) throws {
    try queue.sync {
      try FileManager.default.createDirectory(at: dir(entry.id), withIntermediateDirectories: true)
      try FileIO.writeAtomically(Self.encode(entry), to: fileURL(entry.id, Self.entryName))
    }
  }

  /// Never reads a `.tmp`. A corrupt file reads as nil.
  func load(_ id: String) -> QueueEntry? {
    queue.sync { Self.read(fileURL(id, Self.entryName)) }
  }

  func all() -> [QueueEntry] {
    queue.sync {
      subdirectories().compactMap { Self.read($0.appendingPathComponent(Self.entryName)) }
    }
  }

  /// Deletes the id directory: entry, body, blob, part files, and a v9
  /// manifest.
  func remove(_ id: String) {
    queue.sync { _ = try? FileManager.default.removeItem(at: dir(id)) }
  }

  /// Deletes files the entry does not reference: an old body after a
  /// replace, a body staged by an enqueue that crashed before its save,
  /// `.tmp` leftovers, and part files of another incarnation.
  func sweep(_ entry: QueueEntry) {
    queue.sync {
      for file in files(in: dir(entry.id)) {
        let name = file.lastPathComponent
        let isBody = name.hasPrefix(BodyStaging.bodyPrefix) || name.hasPrefix(BodyStaging.blobPrefix)
          || name == ChunkedManifestV9.blobName
        let stalePart = name.hasPrefix("part-") && !name.contains(".\(entry.incarnation).")
        if (isBody && name != entry.bodyPath) || name.hasSuffix(".tmp") || stalePart {
          try? FileManager.default.removeItem(at: file)
        }
      }
    }
  }

  /// A blob in a directory with no entry.json: the bytes a crash left
  /// between the move and the first save. A same-id retry adopts them.
  func adoptableBlob(_ id: String) -> String? {
    queue.sync {
      let d = dir(id)
      guard !FileIO.exists(d.appendingPathComponent(Self.entryName)) else { return nil }
      return files(in: d).map(\.lastPathComponent)
        .filter { $0 == ChunkedManifestV9.blobName || $0.hasPrefix(BodyStaging.blobPrefix) }
        .sorted().first
    }
  }

  // MARK: - Settings and the import marker

  func loadSettings() -> QueueSettings {
    queue.sync {
      guard let data = try? Data(contentsOf: root.appendingPathComponent(Self.settingsName)),
            let s = try? JSONDecoder().decode(QueueSettings.self, from: data) else { return QueueSettings() }
      return s
    }
  }

  func saveSettings(_ settings: QueueSettings) throws {
    try queue.sync {
      try FileIO.writeAtomically(
        try JSONEncoder().encode(settings), to: root.appendingPathComponent(Self.settingsName))
    }
  }

  func isImported() -> Bool {
    queue.sync { FileIO.exists(root.appendingPathComponent(Self.importedMarker)) }
  }

  func markImported() throws {
    try queue.sync {
      try FileIO.writeAtomically(Data(), to: root.appendingPathComponent(Self.importedMarker))
    }
  }

  // MARK: - v9 manifests

  /// A v9 `manifest.json` in the id directory, with or without an entry.
  func loadV9Manifest(_ id: String) -> ChunkedManifestV9? {
    queue.sync { Self.readManifest(fileURL(id, Self.manifestName)) }
  }

  /// Every v9 manifest, keyed by id.
  func allV9Manifests() -> [String: ChunkedManifestV9] {
    queue.sync {
      var result: [String: ChunkedManifestV9] = [:]
      for d in subdirectories() {
        if let m = Self.readManifest(d.appendingPathComponent(Self.manifestName)) { result[m.id] = m }
      }
      return result
    }
  }

  func removeV9Manifest(_ id: String) {
    queue.sync { _ = try? FileManager.default.removeItem(at: fileURL(id, Self.manifestName)) }
  }

  // MARK: - Part files (carried over from v9)

  /// The temp file that holds exactly the byte range of part `index` while
  /// that part is enqueued (a background session uploads only from a file).
  /// The name carries the incarnation and the range, so a stale file from
  /// another plan is never adopted by a size coincidence.
  func partFileURL(_ id: String, _ index: Int, incarnation: String, start: Int64, end: Int64) -> URL {
    dir(id).appendingPathComponent("part-\(index).\(incarnation).\(start)-\(end)")
  }

  /// Writes bytes [start, end) of `blob` into the part file, tmp + rename.
  /// Reuses a finished file with the same identity and size. Throws when the
  /// blob is missing or shorter than `end`: a 'file' terminal for the caller.
  func writePartFile(id: String, blob: String, index: Int, start: Int64, end: Int64,
                     incarnation: String) throws -> URL {
    try queue.sync {
      let dest = partFileURL(id, index, incarnation: incarnation, start: start, end: end)
      removePartFilesLocked(id, index, keeping: dest)
      if FileIO.size(dest) == end - start { return dest }
      let blobURL = fileURL(id, blob)
      let blobSize = FileIO.size(blobURL) ?? 0
      guard blobSize >= end else {
        throw FileIO.IOError(message: "source blob is \(blobSize) bytes; part \(index) needs [\(start), \(end))")
      }
      let tmp = dir(id).appendingPathComponent("part-\(index).tmp")
      try? FileManager.default.removeItem(at: tmp)
      FileManager.default.createFile(atPath: tmp.path, contents: nil)
      let reader = try FileHandle(forReadingFrom: blobURL)
      defer { try? reader.close() }
      let writer = try FileHandle(forWritingTo: tmp)
      defer { try? writer.close() }
      try reader.seek(toOffset: UInt64(start))
      var remaining = end - start
      while remaining > 0 {
        let chunk = Int(min(remaining, 1 << 20))
        guard let data = try reader.read(upToCount: chunk), !data.isEmpty else {
          throw FileIO.IOError(message: "short read building part \(index)")
        }
        try writer.write(contentsOf: data)
        remaining -= Int64(data.count)
      }
      try FileIO.rename(tmp, onto: dest)
      return dest
    }
  }

  /// Removes every file of part `index`: current, stale, and tmp.
  func removePartFile(_ id: String, _ index: Int) {
    queue.sync { removePartFilesLocked(id, index, keeping: nil) }
  }

  // MARK: - Private (callers hold `queue`)

  // The "part-<i>." prefix cannot collide across indexes ("part-1." is not a
  // prefix of "part-12.").
  private func removePartFilesLocked(_ id: String, _ index: Int, keeping: URL?) {
    for file in files(in: dir(id))
    where file.lastPathComponent.hasPrefix("part-\(index).")
      && file.lastPathComponent != keeping?.lastPathComponent {
      try? FileManager.default.removeItem(at: file)
    }
  }

  private func subdirectories() -> [URL] {
    let items = (try? FileManager.default.contentsOfDirectory(
      at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
    return items.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
  }

  private func files(in dir: URL) -> [URL] {
    (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
  }

  static func encode(_ entry: QueueEntry) throws -> Data {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    return try encoder.encode(entry)
  }

  private static func read(_ url: URL) -> QueueEntry? {
    guard let data = try? Data(contentsOf: url) else { return nil }
    return try? JSONDecoder().decode(QueueEntry.self, from: data)
  }

  private static func readManifest(_ url: URL) -> ChunkedManifestV9? {
    guard let data = try? Data(contentsOf: url),
          let m = try? JSONDecoder().decode(ChunkedManifestV9.self, from: data),
          !m.parts.isEmpty else { return nil }
    return m
  }
}
