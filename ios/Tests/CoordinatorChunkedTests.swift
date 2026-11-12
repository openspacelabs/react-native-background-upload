import XCTest
@testable import RNBGUCore

/// Chunked entries: a task per part, accept, part failures, recreate, parking.
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

  func testEveryPartGetsATaskAtEnqueueAndCompletion() throws {
    let src = h.root.appendingPathComponent("capture.mp4")
    writeFile(src, bytes: 50)
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src)).get()
    XCTAssertFalse(FileIO.exists(src), "moved into the library")
    XCTAssertEqual(h.sink.stateNames, ["queued", "running"])
    XCTAssertEqual(h.transport.live.count, 5, "a task for every part")
    let first = try XCTUnwrap(partTask(0))
    XCTAssertEqual(first.request.httpMethod, "PUT")
    XCTAssertEqual(first.header("Content-Range"), "0-9/50")
    XCTAssertEqual(first.header("Content-Type"), "video/mp4", "descriptor headers under part headers")
    XCTAssertNotNil(first.header("X-Request-Id"))
    XCTAssertEqual(FileIO.size(first.file!), 10)

    h.complete(first)
    XCTAssertEqual(h.transport.live.count, 4, "nothing left to add")
    XCTAssertEqual(h.transport.created.count, 5)
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

  func testPartTheSessionCannotOpenTriesAgainLater() throws {
    let src = h.root.appendingPathComponent("capture.mp4")
    writeFile(src, bytes: 50)
    h.transport.failUpload = { _ in NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotOpenFile) }
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src)).get()
    XCTAssertTrue(h.transport.live.isEmpty)
    XCTAssertTrue(h.sink.settled.isEmpty, "the blob is whole: not terminal")
    h.transport.failUpload = nil
    h.advance(4 * 3_600_000)
    XCTAssertEqual(h.transport.live.count, 5, "every part gets its task")
  }

  func testATransientFailureMidBatchKeepsTheTasksBeforeIt() throws {
    var failed = false
    h.transport.failUpload = { file in
      guard !failed, file.lastPathComponent.hasPrefix("part-2.") else { return nil }
      failed = true
      return NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotOpenFile)
    }
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    XCTAssertEqual(h.transport.live.count, 2, "parts 0 and 1 keep their tasks")
    XCTAssertTrue(h.sink.settled.isEmpty, "the blob is whole: not terminal")
    h.advance(4 * 3_600_000)
    XCTAssertEqual(h.transport.live.count, 5, "the refill gives the rest their tasks")
    let attempts = h.transport.live.compactMap { h.map.meta(forKey: $0.key)?.attempt }
    XCTAssertEqual(Set(attempts).count, 5, "every task has its own ordinal")
  }

  func testPartTheSessionCannotOpenOverAShortBlobSettlesFile() throws {
    let src = h.root.appendingPathComponent("capture.mp4")
    writeFile(src, bytes: 50)
    h.transport.failUpload = { [unowned self] _ in
      let e = self.h.entry("cap")!
      try? FileManager.default.removeItem(at: self.h.store.fileURL("cap", e.bodyPath ?? ChunkedManifestV9.blobName))
      return NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotOpenFile)
    }
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src)).get()
    XCTAssertEqual(h.entry("cap")?.state, .error)
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "file")
    XCTAssertEqual(error["partIndex"] as? Int, 0)
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

  private let cap3: [String: Any] = ["retry": ["transient": [["status": 400, "maxRetries": 3]]]]

  func testEachPartGetsTheRulesFullMaxRetries() throws {
    h.coordinator.configure(cap3)
    h.drain()
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 40, parts: 4)).get()
    // Every part answers 400 three times. One shared count would settle at the fourth 400.
    for round in 1...3 {
      for p in 0..<4 { h.complete(try XCTUnwrap(partTask(p), "round \(round) part \(p)"), status: 400) }
      XCTAssertEqual(h.entry("cap")?.state, .running, "round \(round)")
    }
    XCTAssertEqual(h.entry("cap")?.parts.map(\.transientHitsByRule), Array(repeating: ["400": 3], count: 4))
    XCTAssertNil(h.entry("cap")?.transientHitsByRule, "a chunked entry counts on its parts")
    for p in 0..<4 { h.complete(try XCTUnwrap(partTask(p))) }
    XCTAssertEqual(h.entry("cap")?.state, .completed)
  }

  func testOnePartPastTheCapSettlesTheEntryWithThatPartsResponse() throws {
    h.coordinator.configure(cap3)
    h.drain()
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 40, parts: 4)).get()
    for p in [0, 1, 3] { h.complete(try XCTUnwrap(partTask(p))) }
    for _ in 1...3 { h.complete(try XCTUnwrap(partTask(2)), status: 400, body: "nope") }
    XCTAssertEqual(h.entry("cap")?.state, .running, "three 400s are within the cap")
    h.complete(try XCTUnwrap(partTask(2)), status: 400, body: "nope")
    XCTAssertEqual(h.entry("cap")?.state, .error)
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "http")
    XCTAssertEqual(error["partIndex"] as? Int, 2)
    XCTAssertEqual((error["response"] as? [String: Any])?["status"] as? Int, 400)
    XCTAssertEqual(h.entry("cap")?.parts[2].transientHitsByRule, ["400": 3])
  }

  func testAPartsCountSurvivesARelaunchAndALiveReMutateAndAReopenResetsIt() throws {
    h.coordinator.configure(cap3)
    h.drain()
    let raw = h.chunkedRaw(id: "cap")
    _ = try h.enqueue(raw).get()
    h.complete(try XCTUnwrap(partTask(1)), status: 400)
    XCTAssertEqual(h.store.load("cap")?.parts[1].transientHitsByRule, ["400": 1], "persisted with the part")
    let fresh = Harness(root: h.root)
    fresh.boot()
    fresh.advance(Double(QueueCoordinator.graceMs)) // a replay of a lost part task may be pending
    XCTAssertEqual(fresh.entry("cap")?.parts[1].transientHitsByRule, ["400": 1], "a new process reads it back")
    h = fresh
    var again = raw
    var d = try XCTUnwrap(again["descriptor"] as? [String: Any])
    d["expiresAt"] = h.expiresAt + 1
    again["descriptor"] = d
    _ = try h.enqueue(again).get()
    XCTAssertEqual(h.entry("cap")?.parts[1].transientHitsByRule, ["400": 1], "a live re-mutate keeps it")
    for _ in 1...3 { h.complete(try XCTUnwrap(partTask(1)), status: 400) }
    XCTAssertEqual(h.entry("cap")?.state, .error, "1 + 3 = the fourth 400 is past the cap")
    _ = try h.enqueue(raw).get()
    XCTAssertEqual(h.entry("cap")?.isLive, true)
    XCTAssertEqual(h.entry("cap")?.parts.map(\.transientHitsByRule), [nil, nil, nil], "a reopen starts every part at 0")
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
    XCTAssertEqual(h.transport.live.count, 5, "the retry replaces the part's task, nothing more")
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
    XCTAssertEqual(h.transport.live.count, 4)
    XCTAssertEqual(h.entry("cap")?.attempts, attempts)
    setReadOnly(dir, false)
    h.advance(1_000)
    XCTAssertNotNil(partTask(0))
    XCTAssertEqual(h.store.load("cap")?.attempts, (attempts ?? 0) + 1)
  }

  func testPartFileBuildFailureWithAnIntactBlobRefillsLater() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let e = try XCTUnwrap(h.entry("cap"))
    // A non-empty directory where part 3's file goes: the rebuild for its
    // retry fails, as on a full disk. The blob is whole.
    let blocker = h.store.partFileURL("cap", 3, incarnation: e.incarnation, start: 30, end: 40)
    try FileManager.default.removeItem(at: blocker)
    writeFile(blocker.appendingPathComponent("x"), "x")
    h.complete(try XCTUnwrap(partTask(3)), status: 500)
    XCTAssertTrue(h.sink.settled.isEmpty, "not a file terminal")
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertNil(partTask(3))
    try FileManager.default.removeItem(at: blocker)
    h.advance(1_000)
    XCTAssertNotNil(partTask(3), "built and sent after the backoff")
  }

  func testFailedEntrySaveKeepsTheMovedCaptureForASameIdRetry() throws {
    let src = h.root.appendingPathComponent("capture.mp4")
    writeFile(src, bytes: 50)
    // A non-empty directory where entry.json goes: the move works but the
    // save fails, as on a full disk.
    let blocker = h.store.fileURL("cap", QueueStore.entryName)
    writeFile(blocker.appendingPathComponent("x"), "x")
    guard case .failure(let rejected) = h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src))
    else { return XCTFail("enqueue resolved") }
    XCTAssertEqual(rejected.code, "E_STORAGE")
    XCTAssertTrue(rejected.message.hasPrefix("enqueue: cannot save 'cap': "), rejected.message)
    XCTAssertFalse(FileIO.exists(src), "moved into the library")
    let blobs = try FileManager.default.contentsOfDirectory(atPath: h.store.dir("cap").path)
      .filter { $0.hasPrefix(BodyStaging.blobPrefix) }
    XCTAssertEqual(blobs.count, 1, "the moved capture is the only copy: it stays")
    XCTAssertEqual(FileIO.size(h.store.fileURL("cap", blobs.first ?? "-")), 50)
    XCTAssertNil(h.row("cap"))
    XCTAssertTrue(h.transport.live.isEmpty)

    // The disk has room again. The caller's file is gone: the retry adopts the blob.
    try FileManager.default.removeItem(at: blocker)
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5, source: src)).get()
    XCTAssertEqual(h.store.load("cap")?.bodyPath, blobs.first)
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.transport.live.count, 5, "a task for every part")
  }

  func testPart401ParksTheWholeEntryAndHeadersResumeIt() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let rejected = try XCTUnwrap(partTask(1))
    let others = h.transport.live.filter { $0 !== rejected }
    h.complete(rejected, status: 401)
    XCTAssertEqual(h.entry("cap")?.state, .awaitingAuth)
    XCTAssertEqual(others.count, 3)
    XCTAssertTrue(others.allSatisfy(\.cancelled), "the other in-flight parts stop")
    XCTAssertTrue(h.transport.live.isEmpty)
    h.updateHeaders(["Authorization": "fresh"])
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.transport.live.count, 4)
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
    XCTAssertEqual(h.transport.live.count, 5)
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
    XCTAssertEqual(h.transport.live.count, 4)
    XCTAssertNil(partTask(0))
  }

  func testKeyPauseStopsOnlyThatKeysPartsAndResumeRefills() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", key: "capture", size: 50, parts: 5)).get()
    _ = try h.enqueue(h.dataRaw(id: "note", key: "fieldNote")).get()
    h.complete(try XCTUnwrap(partTask(0)))
    h.pause(keys: ["capture"])
    XCTAssertEqual(h.entry("cap")?.state, .paused)
    XCTAssertNil(partTask(1))
    XCTAssertEqual(h.entry("note")?.state, .running)
    XCTAssertEqual(h.entry("cap")?.parts[0].accepted, true, "accepted parts are kept")
    h.resume(keys: ["capture"])
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertNil(partTask(0))
    XCTAssertNotNil(partTask(1))
  }

  func testEntryWifiOnlyPicksThePartSession() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", extra: ["wifiOnly": true])).get()
    let parts = h.transport.live.filter { ChunkedEngine.parsePartDescription($0.taskDescription) != nil }
    XCTAssertEqual(parts.count, 3)
    XCTAssertTrue(parts.allSatisfy(\.wifiOnly))
    h.setWifiOnly(true)
    h.setWifiOnly(false)
    h.complete(try XCTUnwrap(partTask(0)), status: 503)
    XCTAssertEqual(partTask(0)?.wifiOnly, true, "a part retry after a toggle keeps the entry's own setting")
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

  func testCancelLiveChunkedWhoseJournalWriteFailsKeepsThePartsRunning() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let live = h.transport.live
    let states = h.sink.states.count
    setReadOnly(h.journal.root, true)
    XCTAssertEqual(h.cancel("cap")?.code, "E_STORAGE")
    XCTAssertTrue(live.allSatisfy { !$0.cancelled })
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.sink.states.count, states)
    XCTAssertTrue(h.sink.settled.isEmpty)
    // Parts still land: the chunked side did not stop.
    h.complete(live[0])
    XCTAssertEqual(h.transport.live.count, 3)
    XCTAssertEqual(h.entry("cap")?.parts.filter(\.accepted).count, 2)
    setReadOnly(h.journal.root, false)
    XCTAssertNil(h.cancel("cap"))
    XCTAssertTrue(h.transport.live.isEmpty)
    XCTAssertEqual(h.journal.unacknowledged().count, 1)
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

  func testRelaunchGivesEveryLostPartATask() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let survivors = h.transport.live.map {
      FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request, beginAt: $0.beginAt)
    }
    // The daemon lost the tasks of parts 0 and 1 while the app was dead.
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: Array(survivors.dropFirst(2)))
    fresh.boot()
    fresh.advance(Double(QueueCoordinator.graceMs))
    let recreated = fresh.transport.created.compactMap { ChunkedEngine.parsePartDescription($0.taskDescription)?.part }
    XCTAssertEqual(recreated.sorted(), [0, 1], "every pending part gets its task again")
  }
  // MARK: - Part slices

  /// The part indexes of `tasks`, sorted.
  private func parts(_ tasks: [FakeTask]) -> [Int] {
    tasks.compactMap { ChunkedEngine.parsePartDescription($0.taskDescription)?.part }.sorted()
  }

  private func partTasks(_ incarnation: String? = nil) -> [FakeTask] {
    h.transport.created.filter {
      guard let p = ChunkedEngine.parsePartDescription($0.taskDescription) else { return false }
      return incarnation == nil || p.incarnation == incarnation
    }
  }

  private func partFiles(_ id: String) -> [String] {
    ((try? FileManager.default.contentsOfDirectory(atPath: h.store.dir(id).path)) ?? [])
      .filter { $0.hasPrefix("part-") }.sorted()
  }

  // 6 parts of 10 bytes and a 20-byte budget: 3 slices of 2 parts.
  private func enqueueSixPartsInThreeSlices(id: String = "cap", extra: [String: Any] = [:]) throws {
    h.sliceBytes = 20
    _ = try h.enqueue(h.chunkedRaw(id: id, size: 60, parts: 6, extra: extra)).get()
  }

  func testAnEnqueueAfterAChunkedEnqueueResolvesBeforeEveryPartHasATask() throws {
    h.sliceBytes = 20
    var partTasksAtResolve: Int?
    // As Diana does: the metadata mutate() right after the file mutate() resolves.
    h.coordinator.enqueue(h.chunkedRaw(id: "cap", size: 60, parts: 6), resolve: { [unowned self] _ in
      self.h.coordinator.enqueue(self.h.dataRaw(id: "meta"), resolve: { [unowned self] _ in
        partTasksAtResolve = self.partTasks().count
      }, reject: { _, _ in XCTFail("meta rejected") })
    }, reject: { _, _ in XCTFail("cap rejected") })
    h.drainAll()
    XCTAssertEqual(partTasksAtResolve, 2, "the next enqueue waits for one slice, not the whole plan")
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "then every part gets its task")
    XCTAssertEqual(h.entry("meta")?.state, .running)
  }

  func testSlicesBuildInOrderAndEveryPartGetsExactlyOneTask() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1])
    XCTAssertEqual(h.heldSlices.count, 1, "one continuation per entry")
    h.runHeldSlice()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3])
    h.runHeldSlice()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5])
    XCTAssertTrue(h.heldSlices.isEmpty, "nothing left to build")
    XCTAssertEqual(h.transport.created.count, 6)
    XCTAssertTrue(h.transport.created.allSatisfy(\.resumed))
    let attempts = h.transport.created.compactMap { h.map.meta(forKey: $0.key)?.attempt }
    XCTAssertEqual(attempts.sorted(), [1, 2, 3, 4, 5, 6], "each task has its own ordinal")
    XCTAssertEqual(h.store.load("cap")?.attempts, 6)
  }

  func testAPartThatCompletesBetweenSlicesGetsNoSecondTask() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.complete(try XCTUnwrap(partTask(0)), status: 500) // a delayed retry holds the slot
    h.complete(try XCTUnwrap(partTask(1))) // accepted: its refill builds the next slice
    XCTAssertEqual(parts(h.transport.live), [0, 2, 3])
    XCTAssertEqual(h.heldSlices.count, 1, "the refill did not queue a second continuation")
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 2, 3, 4, 5], "one task per pending part")
    XCTAssertEqual(parts(partTasks()), [0, 0, 1, 2, 3, 4, 5], "only part 0's retry is a second task")
  }

  func testCancelBetweenSlicesStopsTheBuild() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.cancel("cap")
    h.runHeldSlices()
    XCTAssertEqual(h.transport.created.count, 2, "no task after the cancel")
    XCTAssertTrue(h.transport.live.isEmpty)
    XCTAssertEqual(h.sink.settled.last?["kind"] as? String, "cancelled")
    XCTAssertFalse(partFiles("cap").contains { !$0.hasPrefix("part-0.") && !$0.hasPrefix("part-1.") },
                   "no part file after the cancel: \(partFiles("cap"))")
  }

  func testPauseBetweenSlicesStopsTheBuildAndResumeFinishesIt() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.pause()
    h.runHeldSlices()
    XCTAssertEqual(h.transport.created.count, 2, "a paused entry builds no part")
    XCTAssertTrue(h.transport.live.isEmpty)
    h.resume()
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5])
  }

  func testReplaceBetweenSlicesBuildsOnlyTheNewPlan() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices(extra: ["retry": ["terminalHttp": ["exempt": []]]])
    let old = try XCTUnwrap(h.entry("cap")?.incarnation)
    h.complete(try XCTUnwrap(partTask(0)), status: 404)
    XCTAssertEqual(h.entry("cap")?.state, .error)
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 60, parts: 6, urlPrefix: "https://s3.test/v2-")).get()
    let new = try XCTUnwrap(h.entry("cap")?.incarnation)
    XCTAssertNotEqual(new, old)
    h.runHeldSlices()
    XCTAssertEqual(parts(partTasks(old)), [0, 1], "the old plan stopped at its first slice")
    XCTAssertTrue(partTasks(old).allSatisfy { !$0.isLive })
    XCTAssertEqual(parts(partTasks(new)), [0, 1, 2, 3, 4, 5], "the new plan: one task per part")
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5])
  }

  func testExpiryBetweenSlicesSettlesAndBuildsNothingMore() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.clock += 15 * 24 * 3_600_000
    h.runHeldSlices()
    XCTAssertEqual(h.entry("cap")?.state, .error)
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "expired")
    XCTAssertEqual(h.transport.created.count, 2)
    XCTAssertTrue(h.transport.live.isEmpty)
  }

  /// Slice 1 has tasks and TaskMap keys; the process dies with the rest
  /// queued. Returns a new process over the same disk, before boot.
  private func crashAfterTheFirstSlice() throws -> (fresh: Harness, survivors: [FakeTask]) {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    let survivors = h.transport.live.map {
      FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request, beginAt: $0.beginAt)
    }
    XCTAssertEqual(parts(survivors), [0, 1])
    let fresh = Harness(root: h.root)
    fresh.sliceBytes = 20
    fresh.relaunch(daemonTasks: survivors)
    return (fresh, survivors)
  }

  func testACrashBetweenSlicesIsRepairedByReconcileAndRefill() throws {
    let (fresh, survivors) = try crashAfterTheFirstSlice()
    fresh.boot()
    fresh.drainAll()
    XCTAssertEqual(parts(fresh.transport.created), [2, 3, 4, 5], "the parts with no task get one")
    XCTAssertTrue(survivors.allSatisfy { !$0.cancelled }, "the live tasks are adopted")
    XCTAssertTrue(survivors.allSatisfy(\.resumed), "a task left suspended by the crash runs")
    let attempts = (survivors + fresh.transport.created).compactMap { fresh.map.meta(forKey: $0.key)?.attempt }
    XCTAssertEqual(Set(attempts).count, 6, "no ordinal repeats")
  }

  func testACrashBeforeTheTaskMapWriteIsRepairedFromTheDescriptions() throws {
    let (fresh, survivors) = try crashAfterTheFirstSlice()
    // The process died after creating slice 1's tasks, before its TaskMap
    // write: the daemon holds them, the map does not name them.
    let map = TaskMap(fileURL: fresh.map.fileURL)
    survivors.forEach { map.removeKey($0.key) }
    fresh.relaunch(daemonTasks: survivors)
    fresh.boot()
    fresh.drainAll()
    XCTAssertTrue(survivors.allSatisfy { !$0.cancelled && $0.resumed }, "adopted by description and resumed")
    XCTAssertEqual(parts(fresh.transport.created), [2, 3, 4, 5], "parts 0 and 1 get no second task")
  }

  func testAWakeShortOnTimeReleasesTheHandlerBeforeTheLastSlice() throws {
    let (fresh, _) = try crashAfterTheFirstSlice()
    fresh.holdSlices = true
    var remaining: TimeInterval = 30
    fresh.coordinator.wakeTimeRemaining = { remaining }
    var partsAtRelease: [Int]?
    fresh.coordinator.reconcileAll { partsAtRelease = self.parts(fresh.transport.created) }
    fresh.drain()
    fresh.drain()
    XCTAssertNil(partsAtRelease, "30 s left: the handler waits for the slices")
    XCTAssertEqual(parts(fresh.transport.created), [2, 3])
    remaining = 3
    fresh.runHeldSlice()
    XCTAssertEqual(partsAtRelease, [2, 3], "released as the next slice starts")
    XCTAssertEqual(parts(fresh.transport.created), [2, 3, 4, 5], "the slice still builds")
  }

  func testAnActiveAppIgnoresTheWakeTime() throws {
    h.holdSlices = true
    h.coordinator.wakeTimeRemaining = { 0 }
    h.becomeActive()
    try enqueueSixPartsInThreeSlices()
    var idle = false
    h.coordinator.whenIdle { idle = true }
    h.drain()
    XCTAssertFalse(idle)
    h.runHeldSlices()
    XCTAssertTrue(idle)
  }

  func testALaterSliceClearsTheWaitOfEarlierDelayedParts() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.complete(try XCTUnwrap(partTask(0)), status: 503)
    h.complete(try XCTUnwrap(partTask(1)), status: 503)
    XCTAssertNotNil(h.row("cap")?["nextAttemptAt"], "every part in flight waits")
    h.runHeldSlice()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3])
    XCTAssertNil(h.row("cap")?["nextAttemptAt"], "parts 2 and 3 send now")
  }

  /// The app becomes active, and the replace delay passes.
  private func activate() {
    h.becomeActive()
    h.advance(Double(QueueCoordinator.replaceDelayMs))
  }

  private func isForeground(_ task: FakeTask) -> Bool { h.map.meta(forKey: task.key)?.foreground == true }

  func testActivationReplacesBackgroundPartTasksOneSliceAtATime() throws {
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    let old = h.transport.live
    XCTAssertEqual(parts(old), [0, 1, 2, 3, 4, 5])
    XCTAssertFalse(old.contains(where: isForeground))
    h.holdSlices = true
    activate()
    XCTAssertEqual(parts(old.filter(\.cancelled)), [0, 1])
    XCTAssertEqual(parts(old.filter(\.isLive)), [2, 3, 4, 5], "a part keeps its old task until its slice")
    XCTAssertEqual(h.heldSlices.count, 1)
    h.runHeldSlice()
    XCTAssertEqual(parts(old.filter(\.isLive)), [4, 5])
    h.runHeldSlices()
    XCTAssertTrue(old.allSatisfy(\.cancelled))
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "one task per part")
    XCTAssertTrue(h.transport.live.allSatisfy(isForeground))
    XCTAssertEqual(h.transport.created.count, 12)
  }

  func testActivationWhileBuildSlicesAreQueued() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    let old = h.transport.live
    activate()
    XCTAssertTrue(old.allSatisfy(\.cancelled), "slice 1's background tasks are replaced")
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "one task per part")
    XCTAssertTrue(h.transport.live.allSatisfy(isForeground), "the later slices run while active")
    XCTAssertEqual(h.transport.created.count, 8)
  }

  func testAReplaceSliceWithNothingLeftToReplaceStillContinuesTheBuild() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    h.runHeldSlice() // parts 2 and 3; the block for 4 and 5 waits
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3])
    activate() // replaces 0 and 1 now; 2 and 3 wait for the queued block
    // Before that block runs, 2 and 3 fail and their retries hold the slots.
    h.complete(try XCTUnwrap(partTask(2)), status: 503)
    h.complete(try XCTUnwrap(partTask(3)), status: 503)
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "parts 4 and 5 get their tasks")
  }

  func testAnOldTaskThatStartsWhileWaitingForItsSliceIsKept() throws {
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    let old = h.transport.live
    h.holdSlices = true
    activate() // replaces 0 and 1; 2 to 5 wait for their slices
    let sending = try XCTUnwrap(old.first { parts([$0]) == [2] })
    sending.bytesSent = 4 // the daemon started it meanwhile
    h.runHeldSlices()
    XCTAssertFalse(sending.cancelled, "a task that sends is never replaced")
    XCTAssertEqual(parts(old.filter(\.isLive)), [2])
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "one task per part")
    XCTAssertEqual(h.transport.created.count, 11)
  }

  func testASameIdMutateDuringAReplaceChainLeavesTheChain() throws {
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    h.holdSlices = true
    activate()
    let created = h.transport.created.count
    // The same body: a live resume, nothing changes.
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 60, parts: 6, source: h.root.appendingPathComponent("x"))).get()
    // A different body while running rejects.
    guard case .failure(let e) = h.enqueue(h.chunkedRaw(id: "cap", size: 60, parts: 6,
                                                         urlPrefix: "https://other.test/")) else {
      return XCTFail("a different body was accepted")
    }
    XCTAssertEqual(e.code, "E_RUNNING")
    XCTAssertEqual(h.transport.created.count, created)
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "one task per part")
    XCTAssertEqual(h.transport.created.count, 12, "the chain replaced every part once")
  }

  func testASliceThatFailsPartwayIsFinishedByTheBackoffRefill() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    var failed = false
    h.transport.failUpload = { file in
      guard !failed, file.lastPathComponent.hasPrefix("part-3.") else { return nil }
      failed = true
      return NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotOpenFile)
    }
    h.runHeldSlice()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2], "part 2 keeps its task")
    XCTAssertTrue(h.heldSlices.isEmpty, "no slice is queued; the backoff timer refills")
    h.advance(4 * 3_600_000)
    h.runHeldSlices()
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5], "one task per part")
    XCTAssertEqual(h.transport.created.count, 6)
    XCTAssertTrue(h.sink.settled.isEmpty)
  }

  func testASmallRequestWaitsForOneSlicePerChunkedUpload() throws {
    h.sliceBytes = 20
    h.holdSlices = true
    for id in ["a", "b", "c"] { _ = try h.enqueue(h.chunkedRaw(id: id, size: 60, parts: 6)).get() }
    XCTAssertEqual(partTasks().count, 6, "slice 1 of each")
    XCTAssertEqual(h.heldSlices.count, 3)
    // The three next slices go on the queue, then the small request.
    h.requeueHeldSlices()
    var partTasksAtResolve: Int?
    h.coordinator.enqueue(h.dataRaw(id: "meta"), resolve: { [unowned self] _ in
      partTasksAtResolve = self.partTasks().count
    }, reject: { _, _ in XCTFail("meta rejected") })
    h.drain()
    XCTAssertEqual(partTasksAtResolve, 12, "it waited for one slice of each upload, not for whole plans")
    h.runHeldSlices()
    XCTAssertEqual(partTasks().count, 18)
    for id in ["a", "b", "c"] {
      let mine = h.transport.live.filter { ChunkedEngine.parsePartDescription($0.taskDescription)?.id == id }
      XCTAssertEqual(parts(mine), [0, 1, 2, 3, 4, 5])
    }
  }

  func testAShortWakeNeverCutsAGraceWaitShort() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    // Part 1's task finished while the app was dead: its key stays, the
    // daemon no longer holds it, and its completion may still replay.
    let survivor = try XCTUnwrap(partTask(0))
    let fresh = Harness(root: h.root)
    fresh.sliceBytes = 20
    fresh.holdSlices = true
    fresh.relaunch(daemonTasks: [FakeTask(key: survivor.key, description: survivor.taskDescription,
                                          request: survivor.request)])
    fresh.coordinator.wakeTimeRemaining = { 1 }
    var released = false
    fresh.coordinator.reconcileAll { released = true }
    fresh.drain()
    fresh.drain()
    fresh.runHeldSlices()
    XCTAssertFalse(released, "the grace wait holds the handler, short wake or not")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertTrue(released, "released when the grace ends; queued slices do not hold a short wake")
  }

  func testReplaceStopsWhenTheAppResignsActive() throws {
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    let old = h.transport.live
    h.holdSlices = true
    activate()
    XCTAssertEqual(parts(old.filter(\.cancelled)), [0, 1])
    h.resignActive()
    h.runHeldSlices()
    XCTAssertEqual(parts(old.filter(\.isLive)), [2, 3, 4, 5], "the old tasks keep their place")
    XCTAssertEqual(h.transport.created.count, 8, "no background task made in their place")
    XCTAssertEqual(parts(h.transport.live), [0, 1, 2, 3, 4, 5])
  }

  func testAnAdoptedTaskWhoseEntrySettlesAtLaunchIsNotResumed() throws {
    let (fresh, survivors) = try crashAfterTheFirstSlice()
    fresh.clock = h.clock + 15 * 24 * 3_600_000
    fresh.boot()
    XCTAssertEqual(fresh.entry("cap")?.state, .error, "expired")
    XCTAssertTrue(survivors.allSatisfy(\.cancelled))
    XCTAssertFalse(survivors.contains(where: \.resumed), "a stopped task sends nothing")
  }

  func testAnExpiredEntryDueForReplacementOnActivationSettlesExpired() throws {
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    let old = h.transport.live
    h.clock += 15 * 24 * 3_600_000
    activate()
    XCTAssertEqual(h.entry("cap")?.state, .error)
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "expired")
    XCTAssertTrue(old.allSatisfy(\.cancelled))
    XCTAssertEqual(h.transport.created.count, 6, "no new part task")
  }

  func testTheBackgroundCompletionHandlerWaitsForQueuedSlices() throws {
    let (fresh, _) = try crashAfterTheFirstSlice()
    fresh.holdSlices = true
    var partsAtRelease: [Int]?
    fresh.coordinator.reconcileAll { partsAtRelease = self.parts(fresh.transport.created) }
    fresh.drain()
    fresh.drain()
    XCTAssertNil(partsAtRelease, "a slice is still queued")
    XCTAssertEqual(fresh.heldSlices.count, 1)
    fresh.runHeldSlices()
    XCTAssertEqual(partsAtRelease, [2, 3, 4, 5], "released after the last slice")
  }

  func testWhenIdleWaitsForQueuedSlices() throws {
    h.holdSlices = true
    try enqueueSixPartsInThreeSlices()
    var idle = false
    h.coordinator.whenIdle { idle = true }
    h.drain()
    XCTAssertFalse(idle)
    h.runHeldSlices()
    XCTAssertTrue(idle)
  }

  func testTheTaskMapIsWrittenOncePerSlice() throws {
    let before = h.map.writeCount
    try enqueueSixPartsInThreeSlices()
    h.drainAll()
    XCTAssertEqual(h.transport.live.count, 6)
    XCTAssertEqual(h.map.writeCount - before, 3, "one write per slice of 2 tasks")
  }

  func testEveryTaskMapEntryIsWrittenBeforeItsTaskResumes() throws {
    var missing: [String] = []
    h.transport.onResume = { [unowned self] task in
      if self.h.map.meta(forKey: task.key) == nil { missing.append(task.key) }
    }
    try enqueueSixPartsInThreeSlices()
    _ = try h.enqueue(h.dataRaw(id: "note")).get()
    h.drainAll()
    XCTAssertEqual(h.transport.created.count, 7)
    XCTAssertTrue(h.transport.created.allSatisfy(\.resumed))
    XCTAssertEqual(missing, [])
  }
}

/// `RNBGU_PERF=1 swift test --filter ChunkedPerfTests`. Writes about 1.2 GB
/// to the temp directory, so it does not run by default.
final class ChunkedPerfTests: XCTestCase {
  private static let mib = 1 << 20

  /// The time from the resolve of a 200 MiB chunked enqueue (10 parts of 20
  /// MiB) to the resolve of the enqueue that follows it at once. Real part
  /// files, fake sessions: it leaves out nsurlsessiond.
  private func nextEnqueueMs(sliceBytes: Int64?) throws -> Double {
    let h = Harness()
    defer { try? FileManager.default.removeItem(at: h.root) }
    h.boot()
    h.sliceBytes = sliceBytes
    let size = 200 * Self.mib
    let src = h.root.appendingPathComponent("capture.bin")
    FileManager.default.createFile(atPath: src.path, contents: nil)
    let writer = try FileHandle(forWritingTo: src)
    let block = Data(repeating: 7, count: Self.mib)
    for _ in 0..<200 { try writer.write(contentsOf: block) }
    try writer.close()

    var first: UInt64 = 0
    var next: UInt64 = 0
    let done = DispatchSemaphore(value: 0)
    h.coordinator.enqueue(h.chunkedRaw(id: "cap", size: size, parts: 10, source: src), resolve: { _ in
      first = DispatchTime.now().uptimeNanoseconds
      h.coordinator.enqueue(h.dataRaw(id: "meta"), resolve: { _ in
        next = DispatchTime.now().uptimeNanoseconds
        done.signal()
      }, reject: { _, _ in XCTFail("meta rejected") })
    }, reject: { _, _ in XCTFail("cap rejected") })
    done.wait()
    h.drainAll()
    XCTAssertEqual(h.transport.live.count, 11)
    return Double(next - first) / 1_000_000
  }

  func testTheNextEnqueueWaitsForOneSliceOfA200MiBPlan() throws {
    try XCTSkipUnless(ProcessInfo.processInfo.environment["RNBGU_PERF"] == "1", "set RNBGU_PERF=1 to run")
    func median(_ v: [Double]) -> Double { v.sorted()[v.count / 2] }
    let sliced = try (0..<3).map { _ in try nextEnqueueMs(sliceBytes: nil) }
    // Int64.max puts every part in one slice: the build before slices.
    let whole = try (0..<3).map { _ in try nextEnqueueMs(sliceBytes: .max) }
    print(String(format: "RNBGU_PERF nextMs: sliced median %.1f ms %@; one block median %.1f ms %@",
                 median(sliced), sliced.description, median(whole), whole.description))
    XCTAssertLessThan(median(sliced), 500)
  }
}
