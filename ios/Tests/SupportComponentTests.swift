import XCTest
@testable import RNBGUCore

private func sampleEntry(_ id: String, createdAt: Double, vars: String = #"{"n":1}"#) -> QueueEntry {
  QueueEntry(
    id: id, key: "k", varsJSON: vars, url: "https://a.test", method: "POST",
    accept: [], retry: nil, bodyKind: .none, bodyPath: "body-1", bodyContentType: nil,
    forceContentType: false, bodyFingerprint: "none", parts: [], incarnation: "i", headers: [:],
    headerGeneration: 0, state: .queued, authParked: false, generation: 1, attempts: 0, bytesSent: 0,
    totalBytes: 0, expiresAt: 10, nextAttemptAt: nil, settledEventId: nil, lastRequestId: nil,
    lastUrl: nil, lastPartIndex: nil, legacy: false, createdAt: createdAt, updatedAt: createdAt)
}

final class RequestIndexTests: XCTestCase {
  func testLoadUpsertRemoveAndOrder() {
    let index = RequestIndex()
    index.load([sampleEntry("b", createdAt: 2), sampleEntry("a", createdAt: 1)])
    XCTAssertEqual(index.rows().map { $0["id"] as? String }, ["a", "b"])
    var b = sampleEntry("b", createdAt: 2)
    b.state = .running
    index.upsert(b)
    XCTAssertEqual(index.entry("b")?.state, .running)
    XCTAssertEqual(index.count, 2)
    index.remove("a")
    XCTAssertEqual(index.rows().count, 1)
    XCTAssertNil(index.entry("a"))
  }

  func testVarsDecodedOnceAndRefreshedOnChange() {
    let index = RequestIndex()
    index.upsert(sampleEntry("a", createdAt: 1))
    let first = index.row("a")?["vars"] as AnyObject
    var same = sampleEntry("a", createdAt: 1)
    same.attempts = 3
    index.upsert(same)
    XCTAssertTrue(first === index.row("a")?["vars"] as AnyObject, "same vars text reuses the decoded object")
    index.upsert(sampleEntry("a", createdAt: 1, vars: #"{"n":2}"#))
    XCTAssertEqual((index.row("a")?["vars"] as? [String: Int])?["n"], 2)
  }
}

final class EventJournalTests: XCTestCase {
  private var root: URL!
  private var journal: EventJournal!

  override func setUp() {
    root = makeTempDir()
    journal = EventJournal(root: root)
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  private func event(_ eventId: String, id: String = "e", at: Double = 1,
                     kind: JournaledEvent.Kind = .completed) -> JournaledEvent {
    JournaledEvent(eventId: eventId, id: id, key: "k", varsJSON: #"{"a":1}"#, at: at, attempts: 2,
                   requestId: "r", deliveries: 0, bytesSent: 5, totalBytes: 5, url: "https://a.test",
                   method: "POST", partIndex: nil, generation: 1, kind: kind)
  }

  func testAppendAndMarkDelivered() {
    XCTAssertTrue(journal.append(event("1")))
    XCTAssertEqual(journal.load("1")?.deliveries, 0)
    XCTAssertEqual(journal.markDelivered(["1", "missing"]).map(\.deliveries), [1])
    XCTAssertEqual(journal.markDelivered(["1"]).first?.deliveries, 2)
    XCTAssertEqual(journal.load("1")?.deliveries, 2, "persisted")
  }

  func testUnacknowledgedSortedAndSkipsV9() throws {
    journal.append(event("late", at: 5))
    journal.append(event("early", at: 1))
    V9Journal.write(V9Journal.cancelled(eventId: "old", id: "u", timestamp: 0), eventId: "old", into: root)
    XCTAssertEqual(journal.unacknowledged().map(\.eventId), ["early", "late"])
    XCTAssertEqual(journal.legacyEvents(),
                   [JournaledEventV9(eventId: "old", id: "u", type: "cancelled", timestamp: 0, cancelReason: "user")])
  }

  func testPruneKeepsEventsThatRowsName() {
    let small = EventJournal(root: root.appendingPathComponent("small"), maxEntries: 2)
    XCTAssertTrue(small.append(event("1"), keeping: ["1"]))
    XCTAssertTrue(small.append(event("2"), keeping: ["1"]))
    XCTAssertTrue(small.append(event("3"), keeping: ["1"]))
    XCTAssertNotNil(small.load("1"), "named by a row")
    XCTAssertNil(small.load("2"), "the oldest unnamed file goes")
    XCTAssertNotNil(small.load("3"), "the new event is never pruned")
    XCTAssertTrue(small.append(event("4"), keeping: ["1", "3"]))
    XCTAssertEqual(small.unacknowledged().map(\.eventId), ["1", "3", "4"], "all named: over the cap")
  }

  func testAckIsIdempotent() {
    journal.append(event("1"))
    journal.ack(["1", "unknown"])
    journal.ack(["1"])
    XCTAssertNil(journal.load("1"))
  }

  func testRemoveForId() throws {
    journal.append(event("1", id: "a"))
    journal.append(event("2", id: "b"))
    try journal.removeForId("a")
    XCTAssertEqual(journal.unacknowledged().map(\.eventId), ["2"])
    try journal.removeForId("a") // nothing left: not an error
  }

  func testRemoveForIdThrowsWhenAFileCannotBeDeleted() {
    journal.append(event("1", id: "a"))
    setReadOnly(journal.root, true)
    defer { setReadOnly(journal.root, false) }
    XCTAssertThrowsError(try journal.removeForId("a"))
    XCTAssertNotNil(journal.load("1"))
  }

  func testBodyCapAtOneMegabyte() {
    let big = Data(repeating: UInt8(ascii: "a"), count: EventJournal.maxBodyBytes + 10)
    let (body, truncated) = EventJournal.decodeBody(big)
    XCTAssertTrue(truncated)
    XCTAssertEqual(body.utf8.count, EventJournal.maxBodyBytes)
    let small = EventJournal.decodeBody(Data("hi".utf8))
    XCTAssertEqual(small.body, "hi")
    XCTAssertFalse(small.truncated)
    // A cut inside a multi-byte character backs off to a whole one.
    let multi = Data("aé".utf8) // 1 + 2 bytes
    let cut = EventJournal.decodeBody(multi, cap: 2)
    XCTAssertEqual(cut.body, "a")
    XCTAssertTrue(cut.truncated)
  }

  func testResponseBufferCaps() {
    var buffer = ResponseBuffer()
    buffer.append(Data(repeating: 1, count: 6), cap: 10)
    buffer.append(Data(repeating: 2, count: 6), cap: 10)
    XCTAssertEqual(buffer.data.count, 10)
    XCTAssertTrue(buffer.truncated)
    XCTAssertTrue(buffer.decoded().truncated)
  }

  func testBridgedFlattensEachKind() {
    var completed = event("c")
    completed.response = RawResponseRecord(status: 201, headers: ["h": "v"], body: "{}", bodyTruncated: false)
    let c = completed.bridged
    XCTAssertEqual(c["kind"] as? String, "completed")
    XCTAssertEqual(c["state"] as? String, "completed")
    XCTAssertEqual((c["response"] as? [String: Any])?["status"] as? Int, 201)
    XCTAssertEqual((c["vars"] as? [String: Int])?["a"], 1)
    XCTAssertEqual(c["requestId"] as? String, "r")
    XCTAssertNil(c["partIndex"])

    var chunked = event("cc")
    chunked.response = RawResponseRecord(bodyTruncated: false)
    let cc = chunked.bridged["response"] as? [String: Any]
    XCTAssertNil(cc?["status"], "a chunked completion has no status")
    XCTAssertEqual(cc?["bodyTruncated"] as? Bool, false)

    var error = event("e", kind: .error)
    error.error = OutcomeErrorRecord(errorKind: "http", message: "HTTP 404",
                                     response: RawResponseRecord(status: 404, bodyTruncated: false), partIndex: 2)
    error.partIndex = 2
    let e = error.bridged
    XCTAssertEqual((e["error"] as? [String: Any])?["errorKind"] as? String, "http")
    XCTAssertEqual((e["error"] as? [String: Any])?["partIndex"] as? Int, 2)
    XCTAssertEqual(e["partIndex"] as? Int, 2)
    XCTAssertNil(e["response"])

    var cancelled = event("x", kind: .cancelled)
    cancelled.cancelReason = "user"
    XCTAssertEqual(cancelled.bridged["cancelReason"] as? String, "user")

    let nullVars = JournaledEvent(eventId: "n", id: "e", key: "k", varsJSON: "null", at: 1, attempts: 0,
                                  deliveries: 1, bytesSent: 0, totalBytes: 0, url: "", method: "POST",
                                  generation: 1, kind: .cancelled)
    XCTAssertTrue(nullVars.bridged["vars"] is NSNull)
  }
}

final class TaskMapTests: XCTestCase {
  func testRoundTripOfNewFieldsAndPurpose() {
    let url = makeTempDir().appendingPathComponent("map.json")
    let map = TaskMap(fileURL: url)
    map.set(.init(id: "a", partIndex: 1, incarnation: "i", attempt: 3, requestId: "r", headerGeneration: 2,
                  generation: 4, purpose: .attempt), forKey: "s:1")
    map.setPurpose(.pause, forKey: "s:1", id: "a")
    map.setHeaderGeneration(5, forKey: "s:1")
    let reread = TaskMap(fileURL: url)
    XCTAssertEqual(reread.meta(forKey: "s:1"),
                   .init(id: "a", partIndex: 1, incarnation: "i", attempt: 3, requestId: "r", headerGeneration: 5,
                         generation: 4, purpose: .pause))
    reread.removeKey("s:1")
    XCTAssertNil(TaskMap(fileURL: url).meta(forKey: "s:1"))
  }

  func testV9EntryDecodes() throws {
    let url = makeTempDir().appendingPathComponent("map.json")
    try Data(#"{"s:9":{"id":"old","acceptStatus":[409]},"s:10":{"id":"new","purpose":"future"}}"#.utf8).write(to: url)
    let map = TaskMap(fileURL: url)
    XCTAssertEqual(map.meta(forKey: "s:9")?.id, "old", "an old key is ignored, not fatal")
    XCTAssertNil(map.meta(forKey: "s:9")?.generation)
    XCTAssertNil(map.meta(forKey: "s:10")?.purpose, "an unknown purpose reads as nil")
    map.removeAll { _, meta in meta.generation == nil }
    XCTAssertTrue(map.keys { _ in true }.isEmpty)
  }

  func testOwnerResolution() {
    let desc = ChunkedEngine.taskDescription(id: "a:b/c", attempt: 2, generation: 3)
    XCTAssertEqual(TaskOwner.resolve(description: desc, meta: nil), .request(id: "a:b/c", generation: 3, attempt: 2))
    XCTAssertEqual(TaskOwner.resolve(description: nil, meta: .init(id: "m", generation: 1, purpose: nil)), nil,
                   "a meta without attempt has no v10 owner")
    XCTAssertEqual(TaskOwner.resolve(description: nil, meta: .init(id: "m", attempt: 1, generation: 1)),
                   .request(id: "m", generation: 1, attempt: 1))
    XCTAssertEqual(TaskOwner.resolve(description: "legacy-bare-id", meta: .init(id: "legacy-bare-id")), nil)
    XCTAssertEqual(TaskOwner.resolve(description: nil, meta: .init(id: "p", partIndex: 2, incarnation: "i")),
                   .part(id: "p", part: 2, incarnation: "i"))
  }
}

final class ChunkedEngineTests: XCTestCase {
  func testWindowAndOneTaskPerPart() {
    XCTAssertEqual(ChunkedEngine.indexesToEnqueue(pending: [0, 1, 2, 3, 4], inFlight: []), [0, 1, 2])
    XCTAssertEqual(ChunkedEngine.indexesToEnqueue(pending: [0, 1, 2, 3, 4], inFlight: [0, 1]), [2])
    XCTAssertEqual(ChunkedEngine.indexesToEnqueue(pending: [1, 2, 3], inFlight: [0, 1, 2]), [])
    XCTAssertEqual(ChunkedEngine.indexesToEnqueue(pending: [3, 4], inFlight: [3]), [4], "never an in-flight index")
  }

  func testDescriptionsSurviveHostileIds() {
    let id = #"cap:1/"x"\n"#
    let part = ChunkedEngine.taskDescription(id: id, part: 7, incarnation: "inc")
    XCTAssertEqual(ChunkedEngine.parsePartDescription(part)?.id, id)
    XCTAssertEqual(ChunkedEngine.parsePartDescription(part)?.part, 7)
    XCTAssertNil(ChunkedEngine.parseRequestDescription(part))
    let req = ChunkedEngine.taskDescription(id: id, attempt: 4, generation: 2)
    XCTAssertEqual(ChunkedEngine.parseRequestDescription(req)?.id, id)
    XCTAssertEqual(ChunkedEngine.parseRequestDescription(req)?.attempt, 4)
    XCTAssertNil(ChunkedEngine.parsePartDescription(req))
    XCTAssertNil(ChunkedEngine.parsePartDescription("bare-v9-id"))
  }
}

final class LegacyImportTests: XCTestCase {
  private func manifest(_ id: String) -> ChunkedManifestV9 {
    ChunkedManifestV9(id: id, parts: [
      .init(url: "https://s3.test/1", start: 0, end: 5, accepted: true),
      .init(url: "https://s3.test/2", start: 5, end: 12, accepted: false),
    ], expiresAt: 99, incarnation: "inc")
  }

  func testJournalOnly() {
    let rows = LegacyImport.plan(events: [
      JournaledEventV9(eventId: "1", id: "t1", type: "error", timestamp: 10),
      JournaledEventV9(eventId: "2", id: "t1", type: "completed", timestamp: 20),
      JournaledEventV9(eventId: "3", id: "t2", type: "cancelled", timestamp: 5),
    ], manifests: [:])
    XCTAssertEqual(rows.map(\.id), ["t2", "t1"])
    XCTAssertEqual(rows.map(\.state), [.cancelled, .completed], "the latest v9 outcome wins")
    XCTAssertTrue(rows.allSatisfy { $0.legacy && $0.key == "legacy" && $0.varsJSON == "null" })
    XCTAssertNil(rows[0].bodyPath)
  }

  func testJournalAndManifest() {
    let rows = LegacyImport.plan(events: [JournaledEventV9(eventId: "1", id: "cap", type: "error", timestamp: 3)],
                                 manifests: ["cap": manifest("cap")])
    XCTAssertEqual(rows.count, 1)
    XCTAssertEqual(rows[0].bytesSent, 0, "legacy rows report 0/0, as on Android")
    XCTAssertEqual(rows[0].totalBytes, 0)
    XCTAssertEqual(rows[0].bodyPath, "blob")
  }

  func testManifestOnlyStaysDormant() {
    XCTAssertTrue(LegacyImport.plan(events: [], manifests: ["cap": manifest("cap")]).isEmpty)
  }
}

final class ProgressThrottleTests: XCTestCase {
  func testIntervals() {
    let t = ProgressThrottle()
    t.isForeground = true
    XCTAssertTrue(t.shouldEmit("a", now: 0))
    XCTAssertFalse(t.shouldEmit("a", now: 999))
    XCTAssertTrue(t.shouldEmit("a", now: 1_000))
    XCTAssertTrue(t.shouldEmit("b", now: 1_001), "per id")
    t.isForeground = false
    XCTAssertFalse(t.shouldEmit("a", now: 2_500))
    XCTAssertTrue(t.shouldEmit("a", now: 601_000))
    t.reset("a")
    XCTAssertTrue(t.shouldEmit("a", now: 601_001))
  }
}

final class AttemptEventTests: XCTestCase {
  private func input(status: Int? = 200, error: NSError? = nil, accepted: Bool = true,
                     body: String? = "ok") -> AttemptEvent.Input {
    AttemptEvent.Input(id: "i", key: "k", requestId: "r", attempt: 1, url: "https://a.test", method: "PUT",
                       partIndex: 2, statusCode: status, headers: ["h": "v"], body: body, error: error,
                       accepted: accepted, at: 5)
  }

  func testOutcomes() {
    let ok = AttemptEvent.build(input())
    XCTAssertEqual(ok["outcome"] as? String, "completed")
    XCTAssertEqual(ok["httpCode"] as? Int, 200)
    XCTAssertEqual(ok["partIndex"] as? Int, 2)
    let http = AttemptEvent.build(input(status: 400, accepted: false))
    XCTAssertEqual(http["outcome"] as? String, "error")
    XCTAssertEqual(http["errorKind"] as? String, "http")
    let net = AttemptEvent.build(input(status: nil, error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut),
                                       accepted: false))
    XCTAssertEqual(net["errorKind"] as? String, "network")
    XCTAssertNil(net["httpCode"])
    for event in [ok, http, net] {
      XCTAssertTrue(["completed", "error"].contains(event["outcome"] as? String), "only completed or error")
      XCTAssertNil(event["cancelReason"])
    }
  }

  func testBodyCappedAtFourKilobytes() {
    let e = AttemptEvent.build(input(body: String(repeating: "x", count: 5_000)))
    XCTAssertEqual((e["responseBody"] as? String)?.count, 4_096)
    XCTAssertEqual(e["responseBodyTruncated"] as? Bool, true)
  }
}

final class QueueSettingsTests: XCTestCase {
  func testPausedDerivesFromTheGateOrTheKey() {
    var s = QueueSettings()
    XCTAssertFalse(s.isPaused("capture"))
    s.pausedKeys = ["capture"]
    XCTAssertTrue(s.isPaused("capture"))
    XCTAssertFalse(s.isPaused("fieldNote"))
    s.paused = true
    XCTAssertTrue(s.isPaused("fieldNote"))
  }

  func testEntryWifiOnlyWinsOverTheQueueSetting() {
    var s = QueueSettings()
    s.wifiOnly = true
    XCTAssertTrue(s.wifiOnly(nil))
    XCTAssertFalse(s.wifiOnly(false))
    s.wifiOnly = false
    XCTAssertTrue(s.wifiOnly(true))
  }

  func testPausedKeysRoundTripAndAnOlderFileLoads() throws {
    var s = QueueSettings()
    s.paused = true
    s.pausedKeys = ["capture", "video"]
    let back = try JSONDecoder().decode(QueueSettings.self, from: try JSONEncoder().encode(s))
    XCTAssertEqual(back, s)
    let old = try JSONDecoder().decode(QueueSettings.self, from: Data(#"{"wifiOnly":true,"paused":true}"#.utf8))
    XCTAssertEqual(old.pausedKeys, [])
    XCTAssertTrue(old.paused)
  }

  func testAnEntryFromAnOlderBuildHasNoWifiOnly() throws {
    var json = try JSONSerialization.jsonObject(with: try QueueStore.encode(sampleEntry("a", createdAt: 1)))
      as! [String: Any]
    json["wifiOnly"] = nil
    let e = try JSONDecoder().decode(QueueEntry.self, from: try JSONSerialization.data(withJSONObject: json))
    XCTAssertNil(e.wifiOnly, "follows the queue setting")
  }

  func testParserReadsWifiOnly() throws {
    func parse(_ extra: [String: Any]) throws -> ParsedEnqueue {
      var d: [String: Any] = ["url": "https://a.test", "expiresAt": 1.0]
      for (k, v) in extra { d[k] = v }
      return try EnqueueParser.parse(["id": "a", "key": "k", "varsJson": "{}", "descriptor": d])
    }
    XCTAssertNil(try parse([:]).wifiOnly)
    XCTAssertNil(try parse(["wifiOnly": NSNull()]).wifiOnly)
    XCTAssertEqual(try parse(["wifiOnly": true]).wifiOnly, true)
    XCTAssertEqual(try parse(["wifiOnly": false]).wifiOnly, false)
    XCTAssertThrowsError(try parse(["wifiOnly": "yes"]))
  }
}
