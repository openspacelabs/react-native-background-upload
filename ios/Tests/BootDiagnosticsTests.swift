import XCTest
@testable import RNBGUCore

/// The boot snapshot that takeDiagnostics() returns: the v9 work still on disk.
final class BootDiagnosticsTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    root = makeTempDir()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  /// The store as the first process sees it, to stage files before a launch.
  private var disk: QueueStore { QueueStore(root: root.appendingPathComponent("queue")) }

  private func counts(_ legacyRows: Int, _ unownedUploads: Int, _ unownedBytes: Int64) -> [String: Int64] {
    ["legacyRows": Int64(legacyRows), "unownedUploads": Int64(unownedUploads), "unownedBytes": unownedBytes]
  }

  /// The snapshot's v9Leftovers, as numbers.
  private func leftovers(_ records: [[String: Any]]) throws -> [String: Int64] {
    let snapshot = try XCTUnwrap(records.first { $0["level"] as? String == "info" })
    XCTAssertEqual(snapshot["message"] as? String, BootDiagnostics.message)
    let data = try XCTUnwrap(snapshot["data"] as? [String: Any])
    let v9 = try XCTUnwrap(data["v9Leftovers"] as? [String: Any])
    return v9.compactMapValues { ($0 as? NSNumber)?.int64Value }
  }

  private func writeV9Upload(_ store: QueueStore, _ id: String, blobBytes: Int) throws -> Int64 {
    let manifest = ChunkedManifestV9(id: id, parts: [
      .init(url: "https://s3.test/part1", start: 0, end: Int64(blobBytes), accepted: false),
    ], expiresAt: 1, incarnation: "v9inc")
    writeFile(store.fileURL(id, QueueStore.manifestName),
              String(data: try JSONEncoder().encode(manifest), encoding: .utf8)!)
    writeFile(store.fileURL(id, ChunkedManifestV9.blobName), bytes: blobBytes)
    return FileIO.size(store.fileURL(id, QueueStore.manifestName))! + Int64(blobBytes)
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

  func testACleanInstallReportsZeros() throws {
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.count, 1)
    XCTAssertEqual(try leftovers(records), counts(0, 0, 0))
  }

  func testTheSnapshotIsTakenOnce() throws {
    let h = Harness(root: root)
    XCTAssertEqual(h.takeDiagnostics().count, 1)
    XCTAssertTrue(h.takeDiagnostics().isEmpty)
  }

  func testAV9DirectoryWithNoRowIsCountedWithItsSize() throws {
    let size = try writeV9Upload(disk, "cap", blobBytes: 20)
    XCTAssertEqual(try leftovers(Harness(root: root).takeDiagnostics()), counts(0, 1, size))
  }

  func testADirectoryWithOnlyABlobIsCounted() throws {
    writeFile(disk.fileURL("cap", ChunkedManifestV9.blobName), bytes: 30)
    XCTAssertEqual(try leftovers(Harness(root: root).takeDiagnostics()), counts(0, 1, 30))
  }

  func testADirectoryAV10EntryOwnsIsNotCounted() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "a")).get()
    _ = try first.enqueue(first.chunkedRaw(id: "c")).get()
    XCTAssertEqual(try leftovers(Harness(root: root).takeDiagnostics()), counts(0, 0, 0))
  }

  func testADirectoryWithAnUnreadableEntryFileIsNotCounted() throws {
    _ = try writeV9Upload(disk, "cap", blobBytes: 20)
    writeFile(disk.fileURL("cap", QueueStore.entryName), "{")
    XCTAssertEqual(try leftovers(Harness(root: root).takeDiagnostics()), counts(0, 0, 0))
  }

  func testAnEmptyDirectoryIsNotCounted() throws {
    try FileManager.default.createDirectory(at: disk.dir("cap"), withIntermediateDirectories: true)
    XCTAssertEqual(try leftovers(Harness(root: root).takeDiagnostics()), counts(0, 0, 0))
  }

  func testALegacyRowIsCountedAndTheV9FilesItKeepsAreUnownedBytes() throws {
    let events = root.appendingPathComponent("events")
    V9Journal.write(V9Journal.error(eventId: "e1", id: "t-1", timestamp: 100), eventId: "e1", into: events)
    V9Journal.write(V9Journal.error(eventId: "e2", id: "cap", timestamp: 200), eventId: "e2", into: events)
    let size = try writeV9Upload(disk, "cap", blobBytes: 20)
    let h = Harness(root: root) // the import runs first
    XCTAssertEqual(h.entry("cap")?.legacy, true)
    XCTAssertEqual(try leftovers(h.takeDiagnostics()), counts(2, 1, size))
  }

  func testADirectoryThatCannotBeListedLeavesAWarningAndTheOtherCounts() throws {
    let a = try writeV9Upload(disk, "a", blobBytes: 20)
    _ = try writeV9Upload(disk, "b", blobBytes: 50)
    let locked = disk.dir("b")
    try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: locked.path)
    defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: locked.path) }
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.compactMap { $0["level"] as? String }, ["warn", "info"])
    XCTAssertNotNil(records[0]["error"] as? String)
    XCTAssertEqual(try leftovers(records), counts(0, 1, a))
  }

  func testCountingChangesNothingOnDisk() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "a")).get()
    _ = try writeV9Upload(first.store, "cap", blobBytes: 20)
    writeFile(first.store.fileURL("blob-only", ChunkedManifestV9.blobName), bytes: 30)
    try FileManager.default.createDirectory(at: first.store.dir("empty"), withIntermediateDirectories: true)
    let before = diskState()
    // "cap" has no entry file: listed as a legacy id too, it still counts once.
    let (counted, warnings) = first.store.countV9Leftovers(legacyIds: ["cap"])
    XCTAssertEqual(counted.unownedUploads, 2)
    XCTAssertTrue(warnings.isEmpty)
    XCTAssertEqual(diskState(), before)
  }
}
