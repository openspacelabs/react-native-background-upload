import XCTest
@testable import RNBGUCore

/// takeDiagnostics() on iOS: the boot snapshot (v9 leftovers and orphans)
/// and the in-memory log that holds it.
final class DiagnosticLogTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    root = makeTempDir()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  /// The store as the first process sees it, to stage files before a launch.
  private var disk: QueueStore { QueueStore(root: root.appendingPathComponent("queue")) }

  private func v9(legacyRows: Int = 0, legacyBytes: Int64 = 0, unownedUploads: Int = 0,
                  unownedBytes: Int64 = 0) -> [String: Int64] {
    ["legacyRows": Int64(legacyRows), "legacyBytes": legacyBytes,
     "unownedUploads": Int64(unownedUploads), "unownedBytes": unownedBytes]
  }

  private func orphans(_ dirs: Int = 0, _ bytes: Int64 = 0) -> [String: Int64] {
    ["dirs": Int64(dirs), "bytes": bytes]
  }

  /// The snapshot's v9Leftovers.unownedIds.
  private func unownedIds(_ records: [[String: Any]]) throws -> [String] {
    let boot = try XCTUnwrap(records.first { $0["level"] as? String == "info" })
    let v9 = try XCTUnwrap((boot["data"] as? [String: Any])?["v9Leftovers"] as? [String: Any])
    return try XCTUnwrap(v9["unownedIds"] as? [String])
  }

  /// A field of the snapshot, its numbers only.
  private func snapshot(_ records: [[String: Any]], _ field: String) throws -> [String: Int64] {
    let boot = try XCTUnwrap(records.first { $0["level"] as? String == "info" })
    XCTAssertEqual(boot["message"] as? String, DiagnosticLog.bootMessage)
    let data = try XCTUnwrap(boot["data"] as? [String: Any])
    let value = try XCTUnwrap(data[field] as? [String: Any])
    return value.compactMapValues { ($0 as? NSNumber)?.int64Value }
  }

  /// Boots a process over the staged disk and checks both snapshot fields.
  private func assertLeftovers(_ v9: [String: Int64], _ orphans: [String: Int64],
                               file: StaticString = #filePath, line: UInt = #line) throws {
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9, file: file, line: line)
    XCTAssertEqual(try snapshot(records, "orphans"), orphans, file: file, line: line)
  }

  @discardableResult
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

  // MARK: - The boot snapshot

  func testACleanInstallReportsZeros() throws {
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.count, 1)
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9())
    XCTAssertEqual(try snapshot(records, "orphans"), orphans())
  }

  func testTheSnapshotIsTakenOnce() throws {
    let h = Harness(root: root)
    XCTAssertEqual(h.takeDiagnostics().count, 1)
    XCTAssertTrue(h.takeDiagnostics().isEmpty)
  }

  func testAV9DirectoryWithNoRowIsCountedWithItsSize() throws {
    let size = try writeV9Upload(disk, "cap", blobBytes: 20)
    try assertLeftovers(v9(unownedUploads: 1, unownedBytes: size), orphans())
    XCTAssertEqual(try unownedIds(Harness(root: root).takeDiagnostics()), ["cap"])
  }

  func testUnownedIdsAreTheIdsOfTheV9DirectoriesSorted() throws {
    let store = disk
    try writeV9Upload(store, "cap/2", blobBytes: 20)
    try writeV9Upload(store, "cap 1", blobBytes: 20)
    writeFile(store.fileURL("orphan", BodyStaging.bodyPrefix + "x"), bytes: 7)
    let (found, _) = store.countLeftovers(legacyIds: [])
    XCTAssertEqual(found.v9.unownedIds, ["cap 1", "cap/2"])
    for id in found.v9.unownedIds { XCTAssertTrue(FileIO.exists(store.dir(id))) }
  }

  func testUnownedIdsStopAtTheCapAndTheCountStaysExact() throws {
    let store = disk
    let n = QueueStore.maxUnownedIds + 5
    for i in 0..<n { writeFile(store.fileURL(String(format: "cap-%03d", i), QueueStore.manifestName), "{") }
    let (found, _) = store.countLeftovers(legacyIds: [])
    XCTAssertEqual(found.v9.unownedUploads, n)
    XCTAssertEqual(found.v9.unownedBytes, Int64(n))
    XCTAssertEqual(found.v9.unownedIds, (0..<QueueStore.maxUnownedIds).map { String(format: "cap-%03d", $0) })
  }

  func testAV9DirectoryWhoseNameIsNoIdCountsWithAWarningAndNoId() throws {
    let size = try writeV9Upload(disk, "cap", blobBytes: 20)
    writeFile(disk.root.appendingPathComponent("not+base64").appendingPathComponent(QueueStore.manifestName), "{}")
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.compactMap { $0["level"] as? String }, ["warn", "info"])
    XCTAssertNil(records[0]["error"], "no exception, as on Android")
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9(unownedUploads: 2, unownedBytes: size + 2))
    XCTAssertEqual(try unownedIds(records), ["cap"])
  }

  func testAFailedCancelDeleteIsAWarning() throws {
    let h = Harness(root: root)
    XCTAssertEqual(h.takeDiagnostics().count, 1, "the boot record")
    try writeV9Upload(h.store, "cap", blobBytes: 20)
    try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: h.store.root.path)
    defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: h.store.root.path) }
    h.cancel("cap")
    let records = h.takeDiagnostics()
    XCTAssertEqual(records.count, 1)
    XCTAssertEqual(records[0]["level"] as? String, "warn")
    XCTAssertEqual(records[0]["message"] as? String, "could not delete the unowned files of 'cap'")
    let error = try XCTUnwrap(records[0]["error"] as? String)
    XCTAssertTrue(error.hasPrefix("NSCocoaErrorDomain "), error)
    XCTAssertTrue(FileIO.exists(h.store.dir("cap")))
  }

  func testADirectoryWithOnlyAManifestIsV9() throws {
    writeFile(disk.fileURL("cap", QueueStore.manifestName), "{}")
    try assertLeftovers(v9(unownedUploads: 1, unownedBytes: 2), orphans())
  }

  func testADirectoryWithOnlyABareBlobIsV9() throws {
    writeFile(disk.fileURL("cap", ChunkedManifestV9.blobName), bytes: 30)
    try assertLeftovers(v9(unownedUploads: 1, unownedBytes: 30), orphans())
  }

  func testADirectoryWithOnlyAV10BodyIsAnOrphan() throws {
    writeFile(disk.fileURL("a", BodyStaging.bodyPrefix + "x"), bytes: 7)
    writeFile(disk.fileURL("b", BodyStaging.blobPrefix + "y"), bytes: 9)
    writeFile(disk.fileURL("c", "entry.json.tmp"), bytes: 3)
    try assertLeftovers(v9(), orphans(3, 19))
  }

  func testADirectoryAV10EntryOwnsIsNotCounted() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "a")).get()
    _ = try first.enqueue(first.chunkedRaw(id: "c")).get()
    try assertLeftovers(v9(), orphans())
  }

  func testADirectoryWithAnUnreadableEntryFileIsNotCounted() throws {
    try writeV9Upload(disk, "cap", blobBytes: 20)
    writeFile(disk.fileURL("cap", QueueStore.entryName), "{")
    try assertLeftovers(v9(), orphans())
  }

  func testAnEmptyDirectoryIsNotCounted() throws {
    try FileManager.default.createDirectory(at: disk.dir("cap"), withIntermediateDirectories: true)
    try assertLeftovers(v9(), orphans())
  }

  func testAHiddenSetAsideDirectoryIsSkipped() throws {
    writeFile(disk.root.appendingPathComponent(".forget-x/blob"), bytes: 30)
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9())
    XCTAssertEqual(try snapshot(records, "orphans"), orphans())
  }

  func testLegacyRowsCountOnceAndTheV9FilesTheyKeepAreLegacyBytes() throws {
    let events = root.appendingPathComponent("events")
    V9Journal.write(V9Journal.error(eventId: "e1", id: "t-1", timestamp: 100), eventId: "e1", into: events)
    V9Journal.write(V9Journal.error(eventId: "e2", id: "cap", timestamp: 200), eventId: "e2", into: events)
    let size = try writeV9Upload(disk, "cap", blobBytes: 20)
    let h = Harness(root: root) // the import runs first
    XCTAssertEqual(h.entry("cap")?.legacy, true)
    let records = h.takeDiagnostics()
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9(legacyRows: 2, legacyBytes: size))
  }

  func testALegacyRowThatKeepsOnlyABlob() throws {
    let events = root.appendingPathComponent("events")
    V9Journal.write(V9Journal.error(eventId: "e1", id: "cap", timestamp: 100), eventId: "e1", into: events)
    writeFile(disk.fileURL("cap", ChunkedManifestV9.blobName), bytes: 30)
    let h = Harness(root: root)
    XCTAssertEqual(h.entry("cap")?.legacy, true)
    XCTAssertNil(h.entry("cap")?.bodyPath, "no manifest: the import names no body")
    XCTAssertEqual(try snapshot(h.takeDiagnostics(), "v9Leftovers"), v9(legacyRows: 1, legacyBytes: 30))
  }

  // MARK: - Errors

  func testADirectoryThatCannotBeListedLeavesAWarningAndTheOtherCounts() throws {
    let a = try writeV9Upload(disk, "a", blobBytes: 20)
    try writeV9Upload(disk, "b", blobBytes: 50)
    let locked = disk.dir("b")
    try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: locked.path)
    defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: locked.path) }
    let records = Harness(root: root).takeDiagnostics()
    XCTAssertEqual(records.compactMap { $0["level"] as? String }, ["warn", "info"])
    let error = try XCTUnwrap(records[0]["error"] as? String)
    XCTAssertTrue(error.hasPrefix("NSCocoaErrorDomain \(NSFileReadNoPermissionError): "), error)
    XCTAssertEqual(try snapshot(records, "v9Leftovers"), v9(unownedUploads: 1, unownedBytes: a))
  }

  func testARootThatCannotBeListedLeavesAWarningAndZeros() throws {
    let store = disk
    try writeV9Upload(store, "a", blobBytes: 20)
    try FileManager.default.setAttributes([.posixPermissions: 0o300], ofItemAtPath: store.root.path)
    defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: store.root.path) }
    let (found, warnings) = store.countLeftovers(legacyIds: [])
    XCTAssertEqual(found, Leftovers())
    XCTAssertEqual(warnings.map(\.message), ["leftover count: could not list the queue directory"])
  }

  func testADirectoryGoneBeforeItIsListedIsNotCountedAndIsNotAnError() throws {
    let store = disk
    let a = try writeV9Upload(store, "a", blobBytes: 20)
    try writeV9Upload(store, "b", blobBytes: 20)
    store.beforeCountListing = { if $0.lastPathComponent == store.dir("b").lastPathComponent {
      try? FileManager.default.removeItem(at: $0)
    } }
    let (found, warnings) = store.countLeftovers(legacyIds: [])
    XCTAssertEqual(found.v9, V9Leftovers(unownedUploads: 1, unownedBytes: a, unownedIds: ["a"]))
    XCTAssertTrue(warnings.isEmpty)
  }

  func testCountingChangesNothingOnDisk() throws {
    let first = Harness(root: root)
    _ = try first.enqueue(first.dataRaw(id: "a")).get()
    try writeV9Upload(first.store, "cap", blobBytes: 20)
    writeFile(first.store.fileURL("orphan", BodyStaging.bodyPrefix + "x"), bytes: 30)
    try FileManager.default.createDirectory(at: first.store.dir("empty"), withIntermediateDirectories: true)
    let before = diskState()
    let (found, warnings) = first.store.countLeftovers(legacyIds: [])
    XCTAssertEqual(found.v9.unownedUploads, 1)
    XCTAssertEqual(found.orphans, Orphans(dirs: 1, bytes: 30))
    XCTAssertTrue(warnings.isEmpty)
    XCTAssertEqual(diskState(), before)
  }

  // MARK: - The log

  func testWarnAddsARecordInTheAndroidShape() {
    let log = DiagnosticLog(clock: { 42 })
    log.warn("w", error: NSError(domain: "TestDomain", code: 7, userInfo: [NSLocalizedDescriptionKey: "boom"]),
             data: ["k": 1])
    let records = take(log)
    XCTAssertEqual(records.count, 1)
    XCTAssertEqual(records[0]["at"] as? Double, 42)
    XCTAssertEqual(records[0]["level"] as? String, "warn")
    XCTAssertEqual(records[0]["message"] as? String, "w")
    XCTAssertEqual(records[0]["error"] as? String, "TestDomain 7: boom")
    XCTAssertEqual((records[0]["data"] as? [String: Any])?["k"] as? Int, 1)
    XCTAssertTrue(take(log).isEmpty)
  }

  func testTheLogKeepsTheNewestRecords() {
    let log = DiagnosticLog(clock: { 0 })
    for i in 0..<(DiagnosticLog.maxRecords + 5) { log.warn("m\(i)") }
    let messages = take(log).compactMap { $0["message"] as? String }
    XCTAssertEqual(messages.count, DiagnosticLog.maxRecords)
    XCTAssertEqual(messages.first, "m5")
    XCTAssertEqual(messages.last, "m\(DiagnosticLog.maxRecords + 4)")
  }

  func testATakeWaitsForWorkStartedBeforeIt() {
    let log = DiagnosticLog(clock: { 0 })
    let gate = DispatchSemaphore(value: 0)
    log.perform {
      gate.wait()
      log.info("late")
    }
    var records: [[String: Any]]?
    let done = expectation(description: "take")
    log.take {
      records = $0
      done.fulfill()
    }
    gate.signal()
    wait(for: [done], timeout: 5)
    XCTAssertEqual(records?.compactMap { $0["message"] as? String }, ["late"])
  }

  private func take(_ log: DiagnosticLog) -> [[String: Any]] {
    let done = DispatchSemaphore(value: 0)
    var out: [[String: Any]] = []
    log.take {
      out = $0
      done.signal()
    }
    done.wait()
    return out
  }
}
