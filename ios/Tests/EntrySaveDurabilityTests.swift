import XCTest
@testable import RNBGUCore

/// The entry save survives a power loss: the directory sync after the
/// rename, and the boot promotion of a lone entry.json.tmp.
final class EntrySaveDurabilityTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  private func entryURL(_ id: String) -> URL { h.store.fileURL(id, QueueStore.entryName) }
  private func tmpURL(_ id: String) -> URL { FileIO.tmpURL(for: entryURL(id)) }

  /// What a power loss before the directory sync can leave: the complete
  /// tmp, and no entry.json.
  private func undoRename(_ id: String) throws {
    try FileManager.default.moveItem(at: entryURL(id), to: tmpURL(id))
  }

  private func errorKind(_ settled: [String: Any]?) -> String? {
    (settled?["error"] as? [String: Any])?["errorKind"] as? String
  }

  // MARK: - Directory sync on save

  func testEverySaveSyncsTheEntryDirectoryAndTheFirstAlsoSyncsTheRoot() throws {
    h.boot()
    var synced: [URL] = []
    h.store.syncDirectory = { synced.append($0.standardizedFileURL) }
    let dir = h.store.dir("a").standardizedFileURL
    let root = h.store.root.standardizedFileURL
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertEqual(h.entry("a")?.state, .running)
    // The first save syncs the new entry.json and the new directory. The
    // issue then saves again and syncs only the entry directory.
    XCTAssertEqual(synced, [dir, root, dir])
  }

  func testAFailedDirectorySyncDoesNotFailTheSave() throws {
    var failures = 0
    h.store.syncDirectory = { _ in
      failures += 1
      throw FileIO.IOError(message: "fsync failed")
    }
    XCTAssertEqual(try h.enqueue(h.dataRaw(id: "a")).get(), "a", "mutate() still resolves")
    XCTAssertGreaterThan(failures, 0)
    XCTAssertNotNil(QueueStore(root: h.store.root).load("a"), "the entry is on disk")
    h.boot()
    XCTAssertEqual(h.transport.live.count, 1)
  }

  func testTheRealDirectorySyncWorksAndAMissingDirectoryThrows() throws {
    XCTAssertNoThrow(try FileIO.syncDirectory(h.store.root))
    XCTAssertThrowsError(try FileIO.syncDirectory(h.root.appendingPathComponent("missing")))
  }

  func testAFailedWriteDeletesItsTmp() throws {
    let target = h.root.appendingPathComponent("target")
    // A non-empty directory at the target makes the rename fail.
    writeFile(target.appendingPathComponent("x"), "x")
    XCTAssertThrowsError(try FileIO.writeAtomically(Data("{}".utf8), to: target))
    XCTAssertFalse(FileIO.exists(FileIO.tmpURL(for: target)))
  }

  func testARejectedFirstSaveLeavesNoTmpForBootToPromote() throws {
    writeFile(entryURL("a").appendingPathComponent("x"), "x") // the rename onto entry.json fails
    guard case .failure(let error) = h.enqueue(h.dataRaw(id: "a")) else { return XCTFail("enqueue resolved") }
    XCTAssertEqual(error.code, "E_STORAGE")
    XCTAssertFalse(FileIO.exists(tmpURL("a")))
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertNil(fresh.entry("a"), "a rejected request does not come back")
    XCTAssertTrue(fresh.transport.created.isEmpty)
  }

  // MARK: - Boot promotion

  func testALoneTmpIsPromotedAtBootAndTheRequestRuns() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try undoRename("a")
    let fresh = Harness(root: h.root)
    XCTAssertTrue(FileIO.exists(entryURL("a")), "promoted before the index loads")
    XCTAssertFalse(FileIO.exists(tmpURL("a")))
    XCTAssertEqual(fresh.row("a")?["state"] as? String, "queued")
    fresh.boot()
    let task = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.attempts, 1)
    fresh.complete(task)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.sink.settled.last?["kind"] as? String, "completed", "the normal outcome path")
  }

  func testPromotionSyncsTheEntryDirectoryAndTheRoot() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try undoRename("a")
    let store = QueueStore(root: h.store.root)
    var synced: [URL] = []
    store.syncDirectory = { synced.append($0.standardizedFileURL) }
    XCTAssertEqual(store.promoteInterruptedSaves(), ["a"])
    XCTAssertEqual(synced, [store.dir("a").standardizedFileURL, store.root.standardizedFileURL])
    XCTAssertEqual(store.promoteInterruptedSaves(), [], "a second boot finds nothing to do")
  }

  func testAPromotedEntryTakesARepeatEnqueueAsAnExistingEntry() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try undoRename("a")
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertEqual(fresh.transport.live.count, 1)
    // The app never saw the first enqueue resolve, so it calls again.
    XCTAssertEqual(try fresh.enqueue(fresh.dataRaw(id: "a")).get(), "a")
    XCTAssertEqual(fresh.transport.live.count, 1, "the running attempt keeps going")
    XCTAssertEqual(fresh.entry("a")?.attempts, 1)
    XCTAssertEqual(fresh.entry("a")?.generation, 1)
  }

  func testAPromotedEntryWithAMissingBodySettlesAsAFileError() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let body = try XCTUnwrap(h.store.bodyURL(try XCTUnwrap(h.entry("a"))))
    try FileManager.default.removeItem(at: body)
    try undoRename("a")
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertEqual(fresh.entry("a")?.state, .error)
    XCTAssertEqual(errorKind(fresh.sink.settled.last), "file")
    XCTAssertTrue(fresh.transport.created.isEmpty)
    fresh.advance(60_000)
    XCTAssertEqual(fresh.sink.settled.count, 1, "settled once, no loop")
  }

  func testAPromotedChunkedEntryWithAMissingBlobSettlesAsAFileError() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "c")).get()
    let blob = try XCTUnwrap(h.store.bodyURL(try XCTUnwrap(h.entry("c"))))
    try FileManager.default.removeItem(at: blob)
    try undoRename("c")
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertEqual(fresh.entry("c")?.state, .error)
    XCTAssertEqual(errorKind(fresh.sink.settled.last), "file")
    XCTAssertTrue(fresh.transport.created.isEmpty)
  }

  func testATmpThatDoesNotDecodeIsLeftAlone() throws {
    writeFile(tmpURL("a"), #"{"id":"a","state":"#) // cut short
    writeFile(h.store.fileURL("a", "body-1"), "{}")
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertNil(fresh.entry("a"))
    XCTAssertFalse(FileIO.exists(entryURL("a")))
    XCTAssertEqual(try String(contentsOf: tmpURL("a")), #"{"id":"a","state":"#)
    XCTAssertTrue(FileIO.exists(h.store.fileURL("a", "body-1")), "nothing is deleted")
  }

  func testATmpOfAnotherIdIsLeftAlone() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try undoRename("a")
    // The same file under the directory of another id.
    let other = h.store.dir("b").appendingPathComponent(QueueStore.entryName + ".tmp")
    try FileManager.default.createDirectory(at: h.store.dir("b"), withIntermediateDirectories: true)
    try FileManager.default.moveItem(at: tmpURL("a"), to: other)
    let fresh = Harness(root: h.root)
    XCTAssertNil(fresh.entry("a"))
    XCTAssertNil(fresh.entry("b"))
    XCTAssertTrue(FileIO.exists(other))
  }

  func testATmpNextToAReadableEntryIsDeletedAndTheEntryWins() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    var later = try XCTUnwrap(h.entry("a"))
    later.state = .cancelled // a later save that did not finish
    try QueueStore.encode(later).write(to: tmpURL("a"))
    let fresh = Harness(root: h.root)
    XCTAssertEqual(fresh.entry("a")?.state, .queued, "entry.json wins")
    XCTAssertFalse(FileIO.exists(tmpURL("a")), "so a later removal cannot leave it alone")
    fresh.boot()
    XCTAssertEqual(fresh.transport.live.count, 1)
  }

  func testATmpNextToAnUnreadableEntryIsLeftAlone() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try FileManager.default.copyItem(at: entryURL("a"), to: tmpURL("a"))
    try Data("{".utf8).write(to: entryURL("a"))
    let fresh = Harness(root: h.root)
    XCTAssertNil(fresh.entry("a"))
    XCTAssertTrue(FileIO.exists(tmpURL("a")))
    XCTAssertEqual(try String(contentsOf: entryURL("a")), "{")
  }

  func testRemoveDeletesAStrayTmpFirstAndSyncsThatDelete() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try FileManager.default.copyItem(at: entryURL("a"), to: tmpURL("a"))
    var remainingAtSync: [String] = []
    h.store.syncDirectory = { [unowned self] dir in
      remainingAtSync = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
      XCTAssertEqual(dir.standardizedFileURL, self.h.store.dir("a").standardizedFileURL)
    }
    h.store.remove("a")
    XCTAssertTrue(remainingAtSync.contains(QueueStore.entryName), "the tmp went before entry.json")
    XCTAssertFalse(remainingAtSync.contains(QueueStore.entryName + ".tmp"))
    XCTAssertFalse(FileIO.exists(h.store.dir("a")))
  }

  func testSetAsideAndV9DirectoriesAreNotTouched() throws {
    // A v9 directory: a manifest and its blob, no entry file.
    let v9 = h.store.dir("v9")
    writeFile(v9.appendingPathComponent(QueueStore.manifestName), "{}")
    writeFile(v9.appendingPathComponent(ChunkedManifestV9.blobName), bytes: 10)
    // A set-aside directory holding a lone tmp.
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    try undoRename("a")
    let aside = h.store.root.appendingPathComponent(".forget-x", isDirectory: true)
    try FileManager.default.moveItem(at: h.store.dir("a"), to: aside)
    let before = try FileManager.default.contentsOfDirectory(atPath: v9.path).sorted()
    XCTAssertEqual(QueueStore(root: h.store.root).promoteInterruptedSaves(), [])
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: v9.path).sorted(), before)
    XCTAssertTrue(FileIO.exists(aside.appendingPathComponent(QueueStore.entryName + ".tmp")))
  }
}
