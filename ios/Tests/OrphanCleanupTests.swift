import XCTest
@testable import RNBGUCore

/// The boot cleanup: an orphan directory (no entry.json, not v9) that holds
/// only copied bodies and is older than `QueueStore.orphanMinAgeMs` is
/// deleted, and the boot snapshot reports it. Anything else stays.
final class OrphanCleanupTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    root = makeTempDir()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  /// The Harness clock. Files written by a test are aged against it.
  private let clock: Double = 1_700_000_000_000
  private var old: Double { clock - QueueStore.orphanMinAgeMs - 1_000 }

  /// The store as the first process sees it, to stage files before a launch.
  private var disk: QueueStore { QueueStore(root: root.appendingPathComponent("queue")) }

  private func body() -> String { BodyStaging.uniqueName(BodyStaging.bodyPrefix) }
  private func blob() -> String { BodyStaging.uniqueName(BodyStaging.blobPrefix) }

  private func orphanFile(_ id: String, _ name: String, bytes: Int) {
    writeFile(disk.fileURL(id, name), bytes: bytes)
  }

  /// Sets the modification time of `id`'s directory and every file in it.
  private func age(_ id: String, _ ms: Double) {
    let d = disk.dir(id)
    let date = Date(timeIntervalSince1970: ms / 1000)
    for name in names(id) {
      try! FileManager.default.setAttributes([.modificationDate: date],
                                             ofItemAtPath: d.appendingPathComponent(name).path)
    }
    try! FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: d.path)
  }

  private func names(_ id: String) -> Set<String> {
    Set((try? FileManager.default.contentsOfDirectory(atPath: disk.dir(id).path)) ?? [])
  }

  private func orphans(_ dirs: Int = 0, _ bytes: Int64 = 0, removedDirs: Int = 0,
                       removedBytes: Int64 = 0) -> [String: Int64] {
    ["dirs": Int64(dirs), "bytes": bytes, "removedDirs": Int64(removedDirs), "removedBytes": removedBytes]
  }

  private func snapshot(_ records: [[String: Any]], _ field: String) throws -> [String: Int64] {
    let boot = try XCTUnwrap(records.first { $0["level"] as? String == "info" })
    XCTAssertEqual(boot["message"] as? String, DiagnosticLog.bootMessage)
    let data = try XCTUnwrap(boot["data"] as? [String: Any])
    let value = try XCTUnwrap(data[field] as? [String: Any])
    return value.compactMapValues { ($0 as? NSNumber)?.int64Value }
  }

  /// Boots a process over the staged disk and returns its snapshot orphans.
  private func bootOrphans() throws -> [String: Int64] {
    try snapshot(Harness(root: root).takeDiagnostics(), "orphans")
  }

  /// Every path under the root, with its size and modification time.
  private func diskState() -> [String: String] {
    var out: [String: String] = [:]
    let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)
    while let url = walker?.nextObject() as? URL {
      let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
      out[url.path] = "\(attrs?[.size] ?? "-") \(attrs?[.modificationDate] ?? "-")"
    }
    return out
  }

  // MARK: - Deleted

  func testAnOldOrphanWithOnlyCopiedBodiesIsDeletedAndCounted() throws {
    orphanFile("a", body(), bytes: 7)
    orphanFile("a", body(), bytes: 9)
    orphanFile("a", body() + ".tmp", bytes: 2)
    orphanFile("a", "entry.json.tmp", bytes: 3)
    age("a", old)
    XCTAssertEqual(try bootOrphans(), orphans(removedDirs: 1, removedBytes: 21))
    XCTAssertFalse(FileIO.exists(disk.dir("a")))
  }

  // MARK: - Kept

  func testAnOrphanYoungerThanTheAgeLimitIsKept() throws {
    orphanFile("a", body(), bytes: 7)
    age("a", clock - QueueStore.orphanMinAgeMs) // exactly the limit: not older
    let newest = body()
    orphanFile("b", body(), bytes: 5)
    orphanFile("b", newest, bytes: 4)
    age("b", old)
    // One new file: the newest file decides.
    try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: (clock - 1_000) / 1000)],
                                          ofItemAtPath: disk.fileURL("b", newest).path)
    XCTAssertEqual(try bootOrphans(), orphans(2, 16))
    XCTAssertEqual(names("a").count, 1)
    XCTAssertEqual(names("b").count, 2)
  }

  func testAnOrphanWhoseDirectoryChangedRecentlyIsKept() throws {
    orphanFile("a", body(), bytes: 7)
    age("a", old)
    try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: (clock - 1_000) / 1000)],
                                          ofItemAtPath: disk.dir("a").path)
    XCTAssertEqual(try bootOrphans(), orphans(1, 7))
  }

  func testAMovedChunkedFileIsNeverDeletedAloneOrNextToACopiedBody() throws {
    let alone = blob()
    let next = blob()
    let copy = body()
    orphanFile("alone", alone, bytes: 30)
    orphanFile("next", next, bytes: 20)
    orphanFile("next", copy, bytes: 7)
    age("alone", old)
    age("next", old)
    XCTAssertEqual(try bootOrphans(), orphans(2, 57))
    XCTAssertEqual(names("alone"), [alone])
    XCTAssertEqual(names("next"), [next, copy])
  }

  func testAV9DirectoryIsNeverDeleted() throws {
    let copy = body()
    orphanFile("manifest", QueueStore.manifestName, bytes: 2)
    orphanFile("manifest", copy, bytes: 7)
    orphanFile("bare", ChunkedManifestV9.blobName, bytes: 30)
    orphanFile("bare", copy, bytes: 7)
    age("manifest", old)
    age("bare", old)
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(try snapshot(records, "v9Leftovers")["unownedUploads"], 2)
    XCTAssertEqual(try snapshot(records, "orphans"), orphans())
    XCTAssertEqual(names("manifest"), [QueueStore.manifestName, copy])
    XCTAssertEqual(names("bare"), [ChunkedManifestV9.blobName, copy])
  }

  func testADirectoryWithAnEntryFileIsNeverDeletedReadableOrNot() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "owned")).get()
    let ownedNames = names("owned")
    orphanFile("corrupt", body(), bytes: 7)
    writeFile(disk.fileURL("corrupt", QueueStore.entryName), "{")
    age("owned", old)
    age("corrupt", old)
    XCTAssertEqual(try bootOrphans(), orphans())
    XCTAssertEqual(names("owned"), ownedNames)
    XCTAssertEqual(names("corrupt").count, 2)
  }

  func testADirectoryWithAnUnknownFileOrASubdirectoryIsNeverDeleted() throws {
    orphanFile("unknown", body(), bytes: 7)
    orphanFile("unknown", "notes.txt", bytes: 1)
    orphanFile("part", body(), bytes: 7)
    orphanFile("part", "part-0.inc.0-10", bytes: 10)
    orphanFile("blobtmp", body(), bytes: 7)
    orphanFile("blobtmp", blob() + ".tmp", bytes: 1)
    orphanFile("badname", "body-123", bytes: 7)
    orphanFile("hidden", body(), bytes: 7)
    orphanFile("hidden", ".DS_Store", bytes: 1)
    orphanFile("sub", body(), bytes: 7)
    try FileManager.default.createDirectory(at: disk.dir("sub").appendingPathComponent("nested"),
                                            withIntermediateDirectories: true)
    let ids = ["unknown", "part", "blobtmp", "badname", "hidden", "sub"]
    for id in ids { age(id, old) }
    let before = ids.map(names)
    XCTAssertEqual(try bootOrphans(), orphans(6, 55))
    XCTAssertEqual(ids.map(names), before)
  }

  func testADirectoryThatGainsAnEntryFileAfterTheCountIsKept() throws {
    let h = Harness(root: root)
    _ = h.takeDiagnostics()
    orphanFile("a", body(), bytes: 7)
    age("a", old)
    h.store.beforeOrphanRemoval = { writeFile($0.appendingPathComponent(QueueStore.entryName), "{}") }
    h.relaunch()
    // The count saw an orphan; the cleanup found an entry file and kept it.
    XCTAssertEqual(try snapshot(h.takeDiagnostics(), "orphans"), orphans(1, 7))
    XCTAssertEqual(names("a").count, 2)
  }

  func testAFailedDeleteIsAWarningAndTheOtherOrphansAreStillDeleted() throws {
    let lockedBody = body()
    orphanFile("locked", lockedBody, bytes: 7)
    orphanFile("free", body(), bytes: 9)
    age("locked", old)
    age("free", old)
    let locked = disk.dir("locked")
    setReadOnly(locked, true)
    defer { setReadOnly(locked, false) }
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.compactMap { $0["level"] as? String }, ["warn", "info"])
    XCTAssertEqual(records[0]["message"] as? String,
                   "orphan cleanup: could not delete \(locked.lastPathComponent)/\(lockedBody)")
    let error = try XCTUnwrap(records[0]["error"] as? String)
    XCTAssertTrue(error.hasPrefix("NSCocoaErrorDomain "), error)
    XCTAssertEqual(try snapshot(records, "orphans"), orphans(1, 7, removedDirs: 1, removedBytes: 9))
    XCTAssertEqual(names("locked"), [lockedBody])
    XCTAssertFalse(FileIO.exists(disk.dir("free")))
  }

  func testTheCleanupChangesNothingOnDiskButTheDeletedDirectories() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "live")).get()
    _ = first.takeDiagnostics()
    writeFile(disk.fileURL("cap", QueueStore.manifestName), "{}")
    orphanFile("blobbed", blob(), bytes: 30)
    orphanFile("young", body(), bytes: 7)
    orphanFile("unknown", body(), bytes: 7)
    orphanFile("unknown", "notes.txt", bytes: 1)
    orphanFile("gone", body(), bytes: 7)
    orphanFile("gone", body() + ".tmp", bytes: 5)
    try FileManager.default.createDirectory(at: disk.dir("empty"), withIntermediateDirectories: true)
    for id in ["live", "cap", "blobbed", "unknown", "gone", "empty"] { age(id, old) }
    // The walker reports /private/var for /var, so match the path ends.
    let gone = "/queue/" + disk.dir("gone").lastPathComponent
    let isQueueRoot: (String) -> Bool = { $0.hasSuffix("/queue") }
    let before = diskState().filter { !isQueueRoot($0.key) && !$0.key.hasSuffix(gone) && !$0.key.contains(gone + "/") }
    XCTAssertEqual(before.count, diskState().count - 4, "the root, the directory and its two files")
    first.relaunch()
    XCTAssertEqual(try snapshot(first.takeDiagnostics(), "orphans"), orphans(3, 45, removedDirs: 1, removedBytes: 12))
    XCTAssertEqual(diskState().filter { !isQueueRoot($0.key) }, before)
  }

  func testTheCleanupRunsOnTheCoordinatorQueue() throws {
    let h = Harness(root: root)
    _ = h.takeDiagnostics()
    orphanFile("a", body(), bytes: 7)
    age("a", old)
    let key = DispatchSpecificKey<Int>()
    h.queue.setSpecific(key: key, value: 1)
    var onQueue: Bool?
    h.store.beforeOrphanRemoval = { _ in onQueue = DispatchQueue.getSpecific(key: key) == 1 }
    h.relaunch()
    XCTAssertEqual(try snapshot(h.takeDiagnostics(), "orphans"), orphans(removedDirs: 1, removedBytes: 7))
    XCTAssertEqual(onQueue, true)
  }

  func testASameIdEnqueueStillAdoptsAMovedChunkedFileTheCleanupKept() throws {
    let kept = blob()
    orphanFile("cap", kept, bytes: 30)
    orphanFile("cap", body(), bytes: 7)
    age("cap", old)
    let h = Harness(root: root)
    XCTAssertEqual(try snapshot(h.takeDiagnostics(), "orphans"), orphans(1, 37))
    let gone = root.appendingPathComponent("gone.mp4")
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, source: gone)).get()
    XCTAssertEqual(h.entry("cap")?.bodyPath, kept)
    XCTAssertEqual(FileIO.size(h.store.fileURL("cap", kept)), 30)
  }

  // MARK: - Names

  func testOnlyTheNamesStagingWritesAreDeletable() {
    for name in [body(), body() + ".tmp", "entry.json.tmp"] {
      XCTAssertTrue(QueueStore.isCopiedBodyFile(name), name)
    }
    for name in [blob(), blob() + ".tmp", ChunkedManifestV9.blobName, QueueStore.manifestName,
                 QueueStore.entryName, "body-", "body-123", "body-" + UUID().uuidString, // upper case
                 body() + ".tmp.tmp", body() + "x", "part-0.inc.0-10", "part-0.tmp", "notes.tmp"] {
      XCTAssertFalse(QueueStore.isCopiedBodyFile(name), name)
    }
  }
}
