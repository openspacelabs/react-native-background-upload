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
///
/// `entry.json.tmp` alone in a directory is an entry save that a crash or a
/// power loss cut short. Boot finishes it (`promoteInterruptedSaves`).
final class QueueStore {
  static let shared = QueueStore(
    root: FileIO.applicationSupport().appendingPathComponent("RNFileUploaderChunked", isDirectory: true))

  let root: URL
  private let queue = DispatchQueue(label: "ai.openspace.rnbgupload.store")

  static let entryName = "entry.json"
  static let manifestName = "manifest.json"
  private static let settingsName = "settings.json"
  private static let importedMarker = "v10-imported"
  /// Id directory names are base64url, which never starts with ".".
  private static let asidePrefix = ".forget-"

  /// Flushes a directory. A test seam; the default is FileIO.syncDirectory.
  var syncDirectory: (URL) throws -> Void = FileIO.syncDirectory

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
  ///
  /// After the rename it syncs the entry directory, so a power loss cannot
  /// undo the rename: the first save would leave only entry.json.tmp, and a
  /// later save would bring back an older entry. The first save also syncs
  /// the root, which holds the new directory's name. A failed sync is logged
  /// and the save still counts, because the rename itself happened. If a
  /// power loss then undoes a first save, boot promotes the lone tmp.
  func save(_ entry: QueueEntry) throws {
    try queue.sync {
      let d = dir(entry.id)
      let url = d.appendingPathComponent(Self.entryName)
      try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
      let first = !FileIO.exists(url)
      try FileIO.writeAtomically(Self.encode(entry), to: url)
      syncLocked(d)
      if first { syncLocked(root) }
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
  /// manifest. A stray entry.json.tmp goes first, and that delete is synced:
  /// a removal cut short must never leave the tmp alone, because boot would
  /// promote it and bring the request back.
  func remove(_ id: String) {
    queue.sync {
      let d = dir(id)
      let tmp = FileIO.tmpURL(for: d.appendingPathComponent(Self.entryName))
      if (try? FileManager.default.removeItem(at: tmp)) != nil { syncLocked(d) }
      _ = try? FileManager.default.removeItem(at: d)
    }
  }

  // MARK: - Boot repair

  /// Runs at boot, before anything reads, counts or cleans the entry
  /// directories. Finishes entry saves that a crash or a power loss cut
  /// short. For each id directory that holds `entry.json.tmp`:
  /// - No `entry.json`, and the tmp decodes to the entry of this directory:
  ///   the tmp is renamed onto `entry.json` and loads like any entry. The
  ///   app asked for the request, even when the process died before its
  ///   enqueue resolved.
  /// - No `entry.json`, and the tmp does not decode: both stay as they are.
  /// - A readable `entry.json`: the tmp is a later save that did not finish.
  ///   `entry.json` wins and the tmp is deleted.
  /// - An unreadable `entry.json`: both stay as they are.
  /// It reads nothing but `entry.json.tmp`, so v9 files stay as they are. It
  /// skips set-aside directories, which are hidden. Returns the promoted ids.
  @discardableResult
  func promoteInterruptedSaves() -> [String] {
    queue.sync {
      var promoted: [String] = []
      for d in subdirectories() {
        let url = d.appendingPathComponent(Self.entryName)
        let tmp = FileIO.tmpURL(for: url)
        guard FileIO.exists(tmp) else { continue }
        if FileIO.exists(url) {
          if Self.read(url) != nil { _ = try? FileManager.default.removeItem(at: tmp) }
          continue
        }
        guard let e = Self.read(tmp), dir(e.id).lastPathComponent == d.lastPathComponent else {
          NSLog("[RNFileUploader] \(d.lastPathComponent)/\(tmp.lastPathComponent) is not an entry of this "
                + "directory; left as it is")
          continue
        }
        do {
          try FileIO.rename(tmp, onto: url)
        } catch {
          NSLog("[RNFileUploader] cannot recover entry \(e.id): \(error.localizedDescription)")
          continue
        }
        syncLocked(d)
        syncLocked(root)
        promoted.append(e.id)
        NSLog("[RNFileUploader] recovered entry \(e.id) from an entry save that did not finish")
      }
      return promoted
    }
  }

  // MARK: - Forget in steps (cancel on a settled entry)

  /// Step one of a forget that must not half-happen: renames the id
  /// directory to a hidden name in `root`. One rename, so either the row is
  /// gone from `all()` or nothing moved. Throws when the rename fails.
  /// Returns nil when the id has no directory.
  func setAside(_ id: String) throws -> URL? {
    try queue.sync {
      let d = dir(id)
      guard FileIO.exists(d) else { return nil }
      let aside = root.appendingPathComponent(
        Self.asidePrefix + d.lastPathComponent + "-" + UUID().uuidString, isDirectory: true)
      try FileIO.rename(d, onto: aside)
      return aside
    }
  }

  /// Undoes `setAside`.
  func restore(_ aside: URL, _ id: String) throws {
    try queue.sync { try FileIO.rename(aside, onto: dir(id)) }
  }

  /// Deletes a set-aside directory. A failure leaves it for the next launch.
  func discard(_ aside: URL) {
    queue.sync { _ = try? FileManager.default.removeItem(at: aside) }
  }

  /// Set-aside directories a crash left, with the id their entry names (nil
  /// when entry.json is unreadable).
  func setAsideDirectories() -> [(aside: URL, id: String?)] {
    queue.sync {
      let items = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
      return items.filter { $0.lastPathComponent.hasPrefix(Self.asidePrefix) }
        .map { ($0, Self.read($0.appendingPathComponent(Self.entryName))?.id) }
    }
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

  /// A failed sync is logged, never thrown: see `save`.
  private func syncLocked(_ d: URL) {
    do {
      try syncDirectory(d)
    } catch {
      NSLog("[RNFileUploader] cannot sync \(d.lastPathComponent): \(error.localizedDescription)")
    }
  }

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
      at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? []
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
