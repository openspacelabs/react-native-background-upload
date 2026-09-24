import XCTest
@testable import RNBGUCore

final class QueueStoreTests: XCTestCase {
  private var root: URL!
  private var store: QueueStore!

  override func setUp() {
    root = makeTempDir()
    store = QueueStore(root: root)
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  private func entry(_ id: String, state: QueueEntry.State = .queued, body: String? = "body-a") -> QueueEntry {
    QueueEntry(
      id: id, key: "k", varsJSON: "null", descriptorJSON: "{}", url: "https://a.test", method: "POST",
      accept: [], retry: nil, bodyKind: .data, bodyPath: body, bodyContentType: "application/json",
      forceContentType: false, bodyFingerprint: "f", parts: [], incarnation: "inc-1", headers: [:],
      headerGeneration: 0, state: state, authParked: false, generation: 1, attempts: 0, bytesSent: 0,
      totalBytes: 0, expiresAt: 10, nextAttemptAt: nil, settledEventId: nil, lastRequestId: nil,
      lastUrl: nil, lastPartIndex: nil, legacy: false, createdAt: 1, updatedAt: 1)
  }

  func testSaveLoadRoundTrip() throws {
    let e = entry("a/b:c") // a hostile id
    try store.save(e)
    XCTAssertEqual(store.load("a/b:c"), e)
    XCTAssertEqual(store.all(), [e])
    XCTAssertFalse(store.dir("a/b:c").lastPathComponent.contains("/"))
  }

  func testAllSkipsCorruptEntryAndTmp() throws {
    try store.save(entry("good"))
    writeFile(store.fileURL("corrupt", QueueStore.entryName), "{ not json")
    writeFile(store.fileURL("tmponly", QueueStore.entryName + ".tmp"), "{}")
    XCTAssertEqual(store.all().map(\.id), ["good"])
    XCTAssertNil(store.load("corrupt"))
    XCTAssertNil(store.load("tmponly"), "a .tmp with no entry.json reads as absent")
  }

  // Crash mid-write: a half-written tmp next to a valid entry.
  func testCrashMidWriteKeepsTheLastGoodEntry() throws {
    let good = entry("x")
    try store.save(good)
    let tmp = store.fileURL("x", QueueStore.entryName + ".tmp")
    writeFile(tmp, #"{"id":"x","key":"#) // truncated JSON
    XCTAssertEqual(store.load("x"), good, "load never reads the tmp")
    var next = good
    next.state = .running
    try store.save(next)
    XCTAssertEqual(store.load("x"), next)
    XCTAssertFalse(FileIO.exists(tmp), "the next save replaces the tmp")
  }

  func testRemoveDeletesTheDirectory() throws {
    try store.save(entry("r"))
    writeFile(store.fileURL("r", "body-a"), "b")
    store.remove("r")
    XCTAssertFalse(FileIO.exists(store.dir("r")))
    XCTAssertNil(store.load("r"))
  }

  func testSweepDeletesUnreferencedFiles() throws {
    let e = entry("s", body: "body-new")
    try store.save(e)
    for name in ["body-new", "body-old", "blob-old", "entry.json.tmp", "part-0.inc-1.0-5", "part-0.inc-0.0-5"] {
      writeFile(store.fileURL("s", name), "x")
    }
    store.sweep(e)
    let left = Set(try FileManager.default.contentsOfDirectory(atPath: store.dir("s").path))
    XCTAssertEqual(left, ["entry.json", "body-new", "part-0.inc-1.0-5"])
  }

  func testAdoptableBlobOnlyWithoutEntry() throws {
    writeFile(store.fileURL("c", "blob-123"), "bytes")
    XCTAssertEqual(store.adoptableBlob("c"), "blob-123")
    try store.save(entry("c"))
    XCTAssertNil(store.adoptableBlob("c"))
  }

  func testSettingsRoundTripAndDefault() throws {
    XCTAssertEqual(store.loadSettings(), QueueSettings())
    var s = QueueSettings()
    s.wifiOnly = true
    s.headerGeneration = 4
    s.retry = RetryOverride(baseMs: 5)
    try store.saveSettings(s)
    XCTAssertEqual(QueueStore(root: root).loadSettings(), s)
  }

  func testImportMarker() throws {
    XCTAssertFalse(store.isImported())
    try store.markImported()
    XCTAssertTrue(store.isImported())
  }

  func testWritePartFileTmpRenameAndReuse() throws {
    writeFile(store.fileURL("p", "blob"), bytes: 100)
    let url = try store.writePartFile(id: "p", blob: "blob", index: 1, start: 10, end: 30, incarnation: "i1")
    XCTAssertEqual(FileIO.size(url), 20)
    let bytes = try Data(contentsOf: url)
    XCTAssertEqual(bytes.first, UInt8(10 % 251))
    // Reuse by identity: the same call returns the same file untouched.
    let mtime = try FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date
    let again = try store.writePartFile(id: "p", blob: "blob", index: 1, start: 10, end: 30, incarnation: "i1")
    XCTAssertEqual(again, url)
    XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date, mtime)
    // Another incarnation sweeps the stale file of the same index.
    let other = try store.writePartFile(id: "p", blob: "blob", index: 1, start: 10, end: 30, incarnation: "i2")
    XCTAssertFalse(FileIO.exists(url))
    XCTAssertTrue(FileIO.exists(other))
    store.removePartFile("p", 1)
    XCTAssertFalse(FileIO.exists(other))
  }

  func testWritePartFileShortBlobThrows() {
    writeFile(store.fileURL("p", "blob"), bytes: 10)
    XCTAssertThrowsError(try store.writePartFile(id: "p", blob: "blob", index: 0, start: 0, end: 20, incarnation: "i"))
  }

  func testDormantManifestReadAndRemove() throws {
    let manifest = ChunkedManifestV9(
      id: "v9", parts: [.init(url: "https://s3.test/1", headers: [:], start: 0, end: 5, accepted: true)],
      accept: [], expiresAt: 10, wifiOnly: false, createdAt: 1, incarnation: "old")
    writeFile(store.fileURL("v9", QueueStore.manifestName),
              String(data: try JSONEncoder().encode(manifest), encoding: .utf8)!)
    XCTAssertEqual(store.loadV9Manifest("v9"), manifest)
    XCTAssertEqual(store.allDormantManifests(), [manifest])
    XCTAssertEqual(store.allV9Manifests()["v9"], manifest)
    try store.save(entry("v9"))
    XCTAssertTrue(store.allDormantManifests().isEmpty, "an entry.json makes it not dormant")
    store.removeV9Manifest("v9")
    XCTAssertNil(store.loadV9Manifest("v9"))
  }

  func testV9ManifestFileDecodes() {
    // The exact shape a v9 build wrote (stalled, rejections, accepted flags).
    let json = """
    {"id":"cap","parts":[{"url":"https://s3.test/1","headers":{"Content-Range":"0-4/10"},"start":0,"end":5,\
    "accepted":true,"rejections":2},{"url":"https://s3.test/2","headers":{},"start":5,"end":10,"accepted":false}],\
    "accept":[{"status":409,"bodyIncludes":"already completed"}],"expiresAt":123,"wifiOnly":false,\
    "createdAt":1,"stalled":true,"incarnation":"inc"}
    """
    writeFile(store.fileURL("cap", QueueStore.manifestName), json)
    let m = store.loadV9Manifest("cap")
    XCTAssertEqual(m?.acceptedBytes, 5)
    XCTAssertEqual(m?.totalBytes, 10)
    XCTAssertEqual(m?.incarnation, "inc")
  }
}
