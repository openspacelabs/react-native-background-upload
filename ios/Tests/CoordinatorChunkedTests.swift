import XCTest
@testable import RNBGUCore

/// Chunked entries: window, accept, part failures, recreate, parking.
final class CoordinatorChunkedTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
    h.boot()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  private func partTask(_ index: Int) -> FakeTask? {
    h.transport.live.first { ChunkedEngine.parsePartDescription($0.taskDescription)?.part == index }
  }

  func testWindowOfThreeAndCompletion() throws {
    let src = h.root.appendingPathComponent("capture.mp4")
    writeFile(src, bytes: 50)
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src)).get()
    XCTAssertFalse(FileIO.exists(src), "moved into the library")
    XCTAssertEqual(h.sink.stateNames, ["queued", "running"])
    XCTAssertEqual(h.transport.live.count, 3, "window of 3")
    let first = try XCTUnwrap(partTask(0))
    XCTAssertEqual(first.request.httpMethod, "PUT")
    XCTAssertEqual(first.header("Content-Range"), "0-9/50")
    XCTAssertEqual(first.header("Content-Type"), "video/mp4", "descriptor headers under part headers")
    XCTAssertNotNil(first.header("X-Request-Id"))
    XCTAssertEqual(FileIO.size(first.file!), 10)

    h.complete(first)
    XCTAssertEqual(h.transport.live.count, 3, "refilled")
    XCTAssertNotNil(partTask(3))
    XCTAssertEqual(h.sink.progress.last?["bytesSent"] as? Int64, 10)
    XCTAssertEqual(h.entry("cap")?.parts[0].accepted, true)
    XCTAssertEqual(h.store.load("cap")?.parts[0].accepted, true, "accept is persisted")

    for i in 1...4 { h.complete(try XCTUnwrap(partTask(i))) }
    XCTAssertEqual(h.entry("cap")?.state, .completed)
    let settled = try XCTUnwrap(h.sink.settled.last)
    XCTAssertEqual(settled["partIndex"] as? Int, 4)
    XCTAssertEqual(settled["url"] as? String, "https://s3.test/part5")
    XCTAssertEqual(settled["method"] as? String, "PUT")
    XCTAssertEqual(settled["attempts"] as? Int, 5)
    XCTAssertNil((settled["response"] as? [String: Any])?["status"], "no single response")
    XCTAssertEqual(h.sink.progress.last?["bytesSent"] as? Int64, 50)
    h.ack([settled["eventId"] as! String])
    XCTAssertFalse(FileIO.exists(h.store.dir("cap")))
  }

  func testPart404WithEmptyExemptIsTerminalAndKeepsBytes() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", extra: ["retry": ["terminalHttp": ["exempt": []]]])).get()
    let second = try XCTUnwrap(partTask(1))
    let others = h.transport.live.filter { $0 !== second }
    h.complete(second, status: 404, body: "NoSuchUpload")
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "http")
    XCTAssertEqual(error["partIndex"] as? Int, 1)
    XCTAssertEqual((error["response"] as? [String: Any])?["status"] as? Int, 404)
    XCTAssertTrue(others.allSatisfy(\.cancelled), "the other parts stop")
    let blob = try XCTUnwrap(h.entry("cap")?.bodyPath)
    XCTAssertTrue(FileIO.exists(h.store.fileURL("cap", blob)), "bytes survive a non-completed terminal")
  }

  // The part-404 recovery: a same-id enqueue with new parts over the moved bytes.
  func testRecreateOverKeptBlobWhenTheSourceIsGone() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", extra: ["retry": ["terminalHttp": ["exempt": []]]])).get()
    h.complete(try XCTUnwrap(partTask(0)))
    h.complete(try XCTUnwrap(partTask(1)), status: 404)
    let old = try XCTUnwrap(h.entry("cap"))
    let gone = h.root.appendingPathComponent("deleted-by-the-caller.mp4")
    _ = try h.enqueue(h.chunkedRaw(id: "cap", source: gone, urlPrefix: "https://s3.test/new")).get()
    let e = try XCTUnwrap(h.entry("cap"))
    XCTAssertEqual(e.bodyPath, old.bodyPath, "the blob is kept")
    XCTAssertNotEqual(e.incarnation, old.incarnation)
    XCTAssertEqual(e.generation, old.generation + 1)
    XCTAssertTrue(e.parts.allSatisfy { !$0.accepted })
    XCTAssertEqual(e.state, .running)
    XCTAssertEqual(h.transport.live.count, 3)
    XCTAssertEqual(partTask(0)?.request.url?.absoluteString, "https://s3.test/new1")
  }

  func testResumeWithSamePartsKeepsAcceptedAndStartsNothingTwice() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let created = h.transport.created.count
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: h.root.appendingPathComponent("x"))).get()
    XCTAssertEqual(h.transport.created.count, created, "a live resume starts no task")
    XCTAssertEqual(h.entry("cap")?.parts[0].accepted, true)
  }

  func testDifferentPartsWhileRunningReject() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap")).get()
    guard case .failure(let e) = h.enqueue(h.chunkedRaw(id: "cap", urlPrefix: "https://other.test/")) else {
      return XCTFail()
    }
    XCTAssertEqual(e.code, "E_RUNNING")
  }

  func testTilingMismatchRejectsInvalidAndKeepsTheSource() throws {
    let src = h.root.appendingPathComponent("short.mp4")
    writeFile(src, bytes: 25)
    guard case .failure(let e) = h.enqueue(h.chunkedRaw(id: "cap", size: 30, source: src)) else { return XCTFail() }
    XCTAssertEqual(e.code, "E_INVALID")
    XCTAssertTrue(FileIO.exists(src))
  }

  func testTransientPartRetryHoldsItsSlotWithADelayedTask() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let first = try XCTUnwrap(partTask(0))
    h.complete(first, status: 500)
    let retry = try XCTUnwrap(partTask(0))
    XCTAssertTrue(retry !== first)
    XCTAssertNotNil(retry.beginAt)
    XCTAssertEqual(h.transport.live.count, 3, "no extra part enters the window")
    XCTAssertEqual(h.entry("cap")?.parts[0].rejections, 1)
    XCTAssertEqual(h.entry("cap")?.state, .running)
  }

  func testRowShowsNextAttemptAtWhenEveryPartWaits() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    h.complete(try XCTUnwrap(partTask(0)), status: 503)
    XCTAssertNil(h.row("cap")?["nextAttemptAt"], "two parts still move")
    h.clock += 100
    h.complete(try XCTUnwrap(partTask(1)), status: 503)
    let states = h.sink.states.count
    h.clock += 100
    h.complete(try XCTUnwrap(partTask(2)), status: 503)
    let first = h.clock - 200 + 1_000
    XCTAssertEqual(h.row("cap")?["state"] as? String, "running")
    XCTAssertEqual(h.row("cap")?["nextAttemptAt"] as? Double, first, "the earliest begin")
    XCTAssertEqual(h.store.load("cap")?.nextAttemptAt, first)
    XCTAssertEqual(h.sink.states.count, states + 1, "one state event")
    // The first delayed part begins: the entry moves again.
    let begun = try XCTUnwrap(partTask(0))
    XCTAssertNotNil(h.coordinator.taskWillBegin(key: begun.key, description: begun.taskDescription))
    XCTAssertNil(h.row("cap")?["nextAttemptAt"])
  }

  func testRelaunchRestoresNextAttemptAtFromTheDelayedPartTasks() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    for i in 0..<3 { h.complete(try XCTUnwrap(partTask(i)), status: 503) }
    let waiting = h.transport.live
    let fresh = Harness(root: h.root)
    fresh.clock = h.clock + 200
    let survivors = waiting.map {
      FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request, beginAt: $0.beginAt)
    }
    fresh.relaunch(daemonTasks: survivors)
    fresh.boot()
    XCTAssertEqual(fresh.row("cap")?["nextAttemptAt"] as? Double, h.clock + 1_000)
    // Progress from a part that began while the app was dead clears it.
    fresh.coordinator.taskProgress(key: survivors[1].key, description: survivors[1].taskDescription,
                                   sent: 1, expected: 10)
    fresh.drain()
    XCTAssertNil(fresh.row("cap")?["nextAttemptAt"])
  }

  func testFailedPartSaveCreatesNoTaskAndRefillsAfterABackoff() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let dir = h.store.dir("cap")
    setReadOnly(dir, true)
    defer { setReadOnly(dir, false) }
    let attempts = h.entry("cap")?.attempts
    h.complete(try XCTUnwrap(partTask(0)), status: 500)
    XCTAssertNil(partTask(0), "no task without the saved attempt")
    XCTAssertEqual(h.transport.live.count, 2)
    XCTAssertEqual(h.entry("cap")?.attempts, attempts)
    setReadOnly(dir, false)
    h.advance(1_000)
    XCTAssertNotNil(partTask(0))
    XCTAssertEqual(h.store.load("cap")?.attempts, (attempts ?? 0) + 1)
  }

  func testPartFileBuildFailureWithAnIntactBlobRefillsLater() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let e = try XCTUnwrap(h.entry("cap"))
    // A non-empty directory where part 3's file goes: the rename fails, as
    // on a full disk. The blob is whole.
    let blocker = h.store.partFileURL("cap", 3, incarnation: e.incarnation, start: 30, end: 40)
    writeFile(blocker.appendingPathComponent("x"), "x")
    h.complete(try XCTUnwrap(partTask(0)))
    XCTAssertTrue(h.sink.settled.isEmpty, "not a file terminal")
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertNil(partTask(3))
    try FileManager.default.removeItem(at: blocker)
    h.advance(1_000)
    XCTAssertNotNil(partTask(3), "built and sent after the backoff")
  }

  func testPart401ParksTheWholeEntryAndHeadersResumeIt() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let rejected = try XCTUnwrap(partTask(1))
    let others = h.transport.live.filter { $0 !== rejected }
    h.complete(rejected, status: 401)
    XCTAssertEqual(h.entry("cap")?.state, .awaitingAuth)
    XCTAssertEqual(others.count, 2)
    XCTAssertTrue(others.allSatisfy(\.cancelled), "the other in-flight parts stop")
    XCTAssertTrue(h.transport.live.isEmpty)
    h.updateHeaders(["Authorization": "fresh"])
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.transport.live.count, 3)
    XCTAssertTrue(h.transport.live.allSatisfy { $0.header("Authorization") == "fresh" })
    XCTAssertNil(partTask(0), "accepted parts are not sent again")
  }

  func testPart401UnderAnOlderGenerationReissuesThePartAtOnce() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let first = try XCTUnwrap(partTask(1))
    h.updateHeaders(["Authorization": "fresh"])
    h.complete(first, status: 401)
    XCTAssertEqual(h.entry("cap")?.state, .running, "not parked")
    let again = try XCTUnwrap(partTask(1))
    XCTAssertTrue(again !== first)
    XCTAssertNil(again.beginAt, "no backoff")
    XCTAssertEqual(again.header("Authorization"), "fresh")
    XCTAssertEqual(h.transport.live.count, 3)
  }

  func testUpdateHeadersReplacesAHeaderAPartCarries() throws {
    var raw = h.chunkedRaw(id: "cap", size: 30, parts: 3)
    var d = raw["descriptor"] as! [String: Any]
    d["parts"] = (d["parts"] as! [[String: Any]]).map { p in
      var p = p
      var headers = p["headers"] as! [String: Any]
      headers["authorization"] = "part-old"
      p["headers"] = headers
      return p
    }
    raw["descriptor"] = d
    _ = try h.enqueue(raw).get()
    h.complete(try XCTUnwrap(partTask(0)), status: 401)
    XCTAssertEqual(h.entry("cap")?.state, .awaitingAuth)
    h.updateHeaders(["Authorization": "fresh", "X-New": "1"])
    XCTAssertEqual(h.transport.live.count, 3)
    XCTAssertTrue(h.transport.live.allSatisfy { $0.header("Authorization") == "fresh" }, "the part's own header too")
    XCTAssertEqual(h.entry("cap")?.parts[0].headers["Authorization"], "fresh")
    XCTAssertNil(h.entry("cap")?.parts[0].headers["authorization"], "one spelling")
    XCTAssertNil(h.entry("cap")?.parts[0].headers["X-New"], "a name the part lacks stays on the entry")
    XCTAssertNotNil(h.entry("cap")?.parts[0].headers["Content-Range"])
  }

  func testRowCarriesByteWeightedLiveProgress() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let running = try XCTUnwrap(partTask(1))
    h.coordinator.taskProgress(key: running.key, description: running.taskDescription, sent: 4, expected: 10)
    h.drain()
    XCTAssertEqual(h.row("cap")?["bytesSent"] as? Int64, 14)
  }

  func testPauseKeepsAcceptedPartsAndResumeRefills() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    h.pause()
    XCTAssertTrue(h.transport.live.isEmpty)
    XCTAssertEqual(h.entry("cap")?.state, .paused)
    h.resume()
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.transport.live.count, 3)
    XCTAssertNil(partTask(0))
  }

  func testCancelLiveChunked() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let live = h.transport.live
    h.cancel("cap")
    XCTAssertTrue(live.allSatisfy(\.cancelled))
    XCTAssertEqual(h.sink.settled.last?["kind"] as? String, "cancelled")
    // A late accept from a cancelled part still records the bytes but reports nothing.
    let settled = h.sink.settled.count
    h.complete(live[0])
    XCTAssertEqual(h.sink.settled.count, settled)
    h.ack([h.sink.settled.last!["eventId"] as! String])
    XCTAssertFalse(FileIO.exists(h.store.dir("cap")))
  }

  func testLateCallbackFromAReplacedPlanIsDropped() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", extra: ["retry": ["terminalHttp": ["exempt": []]]])).get()
    let stale = try XCTUnwrap(partTask(2))
    h.complete(try XCTUnwrap(partTask(0)), status: 404)
    _ = try h.enqueue(h.chunkedRaw(id: "cap", urlPrefix: "https://s3.test/v2-")).get()
    h.complete(stale) // from the old incarnation
    XCTAssertEqual(h.entry("cap")?.parts[2].accepted, false)
  }

  func testShortBlobSettlesFile() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    let blob = h.store.fileURL("cap", h.entry("cap")!.bodyPath!)
    try Data(count: 12).write(to: blob)
    h.complete(try XCTUnwrap(partTask(0)))
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "file")
  }
}
