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

  func testTilingMismatchRejectsStorageAndKeepsTheSource() throws {
    let src = h.root.appendingPathComponent("short.mp4")
    writeFile(src, bytes: 25)
    guard case .failure(let e) = h.enqueue(h.chunkedRaw(id: "cap", size: 30, source: src)) else { return XCTFail() }
    XCTAssertEqual(e.code, "E_STORAGE")
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

/// Relaunch reconciliation and the v9 import.
final class CoordinatorRelaunchTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  func testNothingIssuesBeforeReconcileThenReconcileIssues() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertTrue(h.transport.created.isEmpty, "not ready: nothing issues")
    XCTAssertEqual(h.row("a")?["state"] as? String, "queued", "getRequests works before reconcile")
    h.boot()
    XCTAssertEqual(h.transport.live.count, 1)
    XCTAssertEqual(h.entry("a")?.state, .running)
  }

  func testRelaunchAdoptsTheLiveTaskAndItsCompletionSettles() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    // New process: the daemon still holds the task.
    let survivor = FakeTask(key: task.key, description: task.taskDescription, request: task.request, file: task.file)
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: [survivor])
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty, "adopted, not re-issued")
    XCTAssertEqual(fresh.row("a")?["state"] as? String, "running")
    fresh.complete(survivor)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.sink.settled.count, 1)
  }

  func testRunningWithNoTaskAndNoTaskMapKeyReissuesNow() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    h.map.removeKey(task.key) // as if the completion was handled, or the task never made it
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertEqual(fresh.transport.created.count, 1)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
  }

  func testRunningWithAPendingCompletionWaitsForTheReplay() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty, "the TaskMap key says a completion may be pending")
    // The daemon replays the completion that happened while we were dead.
    fresh.complete(FakeTask(key: task.key, description: task.taskDescription, request: task.request))
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertTrue(fresh.transport.created.isEmpty, "no duplicate request")
  }

  func testRunningWithALostTaskReissuesAfterTheGraceAndPrunesItsKey() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let lost = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty)
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertEqual(fresh.transport.created.count, 1)
    // Fake keys restart per process, so check by attempt, not by key.
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" && $0.attempt == 1 }).isEmpty, "\(lost.key) pruned")
    // The next launch does not wait the grace again for the same lost task.
    let third = Harness(root: h.root)
    third.boot()
    XCTAssertTrue(third.map.keys(where: { $0.attempt == 1 }).isEmpty)
  }

  func testReconcileDropsKeysWhoseIdHasNoEntry() throws {
    h.boot()
    h.map.set(.init(id: "ghost", attempt: 1, generation: 1, purpose: .attempt), forKey: "any:77")
    let live = FakeTask(key: "any:78", description: ChunkedEngine.taskDescription(id: "ghost2", attempt: 1, generation: 1))
    h.map.set(.init(id: "ghost2", attempt: 1, generation: 1, purpose: .attempt), forKey: live.key)
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: [live])
    fresh.boot()
    XCTAssertNil(fresh.map.meta(forKey: "any:77"))
    XCTAssertEqual(fresh.map.meta(forKey: live.key)?.purpose, .superseded, "a live task keeps its key for its cancel")
  }

  // The delayed task never reached the daemon (a crash between the save and
  // resume): no TaskMap key. It never ran, so it keeps its ordinal and id.
  func testDelayedRetryThatNeverReachedTheDaemonKeepsItsWaitAndOrdinal() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(h.transport.live[0], status: 503)
    let waiting = h.transport.live[0]
    h.map.removeKey(waiting.key)
    let fresh = Harness(root: h.root)
    fresh.clock = h.clock + 400
    fresh.boot()
    let task = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(task.beginAt, Date(timeIntervalSince1970: (h.clock + 1_000) / 1000))
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
    XCTAssertEqual(task.header("X-Request-Id"), waiting.header("X-Request-Id"))
  }

  // The key says the delayed task may have run while the app was dead: wait
  // for its replay, then mint a new attempt so a late replay is stale.
  func testQueuedEntryWithAPendingKeyWaitsTheGraceThenMintsANewAttempt() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(h.transport.live[0], status: 503)
    let waiting = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty, "a replay may be pending")
    fresh.advance(Double(QueueCoordinator.graceMs))
    let task = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.attempts, 3)
    XCTAssertNotEqual(task.header("X-Request-Id"), waiting.header("X-Request-Id"))
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" && $0.attempt == 2 }).isEmpty, "the lost task's key is pruned")
    // A late replay of the lost attempt is dropped.
    fresh.complete(FakeTask(key: waiting.key, description: waiting.taskDescription, request: waiting.request))
    XCTAssertEqual(fresh.entry("a")?.state, .running)
  }

  func testUnownedAndStaleTasksAreCancelled() throws {
    let v9 = FakeTask(key: "any:90", description: "bare-v9-id")
    let orphan = FakeTask(key: "any:91", description: ChunkedEngine.taskDescription(id: "gone", attempt: 1, generation: 1))
    h.relaunch(daemonTasks: [v9, orphan])
    h.boot()
    XCTAssertTrue(v9.cancelled)
    XCTAssertTrue(orphan.cancelled)
    XCTAssertEqual(h.map.meta(forKey: "any:90")?.purpose, .superseded)
    h.complete(v9, error: NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
    XCTAssertTrue(h.sink.attempts.isEmpty)
    XCTAssertTrue(h.sink.settled.isEmpty)
  }

  func testChunkedRelaunchAdoptsLivePartsAndCancelsDuplicates() throws {
    h.boot()
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let parts = h.transport.live
    let fresh = Harness(root: h.root)
    let survivors = parts.map { FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request) }
    let duplicate = FakeTask(key: "any:99", description: parts[0].taskDescription)
    fresh.relaunch(daemonTasks: survivors + [duplicate])
    fresh.boot()
    XCTAssertTrue(duplicate.cancelled, "never two tasks for one part")
    XCTAssertTrue(fresh.transport.created.isEmpty, "the window is full with the adopted tasks")
    fresh.complete(survivors[0])
    XCTAssertEqual(fresh.transport.created.count, 1, "refill after the adopted part completes")
  }

  func testV9ImportMakesLegacyRowsAndKeepsDormantManifests() throws {
    // v9 state on disk before the first v10 launch: a journal entry, a
    // half-done chunked manifest with no journal entry, and v9 task metadata.
    let root = makeTempDir()
    let v9Store = QueueStore(root: root.appendingPathComponent("queue"))
    let v9Journal = EventJournal(root: root.appendingPathComponent("events"))
    let v9Event = JournaledEventV9(eventId: "ev1", id: "transfer-1", type: "completed", timestamp: 100)
    try JSONEncoder().encode(v9Event).write(to: v9Journal.root.appendingPathComponent("ev1.json"))
    let manifest = ChunkedManifestV9(id: "cap-1", parts: [
      .init(url: "https://s3.test/part1", headers: [:], start: 0, end: 10, accepted: true),
      .init(url: "https://s3.test/part2", headers: [:], start: 10, end: 20, accepted: false),
      .init(url: "https://s3.test/part3", headers: [:], start: 20, end: 30, accepted: false),
    ], accept: [], expiresAt: h.expiresAt, wifiOnly: false, createdAt: 1, incarnation: "v9inc")
    writeFile(v9Store.fileURL("cap-1", QueueStore.manifestName),
              String(data: try JSONEncoder().encode(manifest), encoding: .utf8)!)
    writeFile(v9Store.fileURL("cap-1", "blob"), bytes: 30)
    TaskMap(fileURL: root.appendingPathComponent("taskmap.json")).set(.init(id: "transfer-1", accept: []), forKey: "any:5")

    h = Harness(root: root) // first v10 launch: the import runs in init
    XCTAssertNotNil(h.row("transfer-1"), "legacy rows are in the index before any reconcile")
    h.boot()
    let legacy = try XCTUnwrap(h.row("transfer-1"))
    XCTAssertEqual(legacy["key"] as? String, "legacy")
    XCTAssertEqual(legacy["state"] as? String, "completed")
    XCTAssertTrue(legacy["vars"] is NSNull)
    XCTAssertNil(h.row("cap-1"), "a dormant manifest is not a row")
    XCTAssertTrue(h.sink.settled.isEmpty, "nothing is delivered")
    XCTAssertTrue(h.journal.legacyEvents().isEmpty)
    XCTAssertNil(h.map.meta(forKey: "any:5"))
    XCTAssertTrue(h.store.isImported())

    // Diana's re-send with the same parts resumes the v9 bytes.
    let resend = h.chunkedRaw(id: "cap-1", size: 30, parts: 3, source: h.root.appendingPathComponent("gone"),
                              urlPrefix: "https://s3.test/part")
    _ = try h.enqueue(resend).get()
    let e = try XCTUnwrap(h.entry("cap-1"))
    XCTAssertEqual(e.parts.map(\.accepted), [true, false, false])
    XCTAssertEqual(e.incarnation, "v9inc")
    XCTAssertEqual(e.bodyPath, "blob")
    XCTAssertNil(h.store.loadV9Manifest("cap-1"), "adopted")
    XCTAssertEqual(h.transport.live.count, 2, "only the unaccepted parts")

    // Diana cancels the legacy row: gone now.
    h.cancel("transfer-1")
    XCTAssertNil(h.row("transfer-1"))
  }

  /// v9 state with a journaled outcome for a chunked id whose manifest and
  /// blob are still on disk.
  private func legacyChunked(type: String, accepted: [Bool]) throws -> Harness {
    let root = makeTempDir()
    let v9Store = QueueStore(root: root.appendingPathComponent("queue"))
    let v9Journal = EventJournal(root: root.appendingPathComponent("events"))
    let v9Event = JournaledEventV9(eventId: "ev1", id: "cap-1", type: type, timestamp: 100)
    try JSONEncoder().encode(v9Event).write(to: v9Journal.root.appendingPathComponent("ev1.json"))
    let manifest = ChunkedManifestV9(id: "cap-1", parts: (0..<3).map {
      .init(url: "https://s3.test/part\($0 + 1)", headers: [:], start: Int64($0 * 10),
            end: Int64($0 * 10 + 10), accepted: accepted[$0])
    }, accept: [], expiresAt: h.expiresAt, wifiOnly: false, createdAt: 1, incarnation: "v9inc")
    writeFile(v9Store.fileURL("cap-1", QueueStore.manifestName),
              String(data: try JSONEncoder().encode(manifest), encoding: .utf8)!)
    writeFile(v9Store.fileURL("cap-1", "blob"), bytes: 30)
    let fresh = Harness(root: root)
    fresh.boot()
    return fresh
  }

  func testLegacyCompletedRowAdoptsTheV9BytesOnASameIdEnqueue() throws {
    let l = try legacyChunked(type: "completed", accepted: [true, true, false])
    defer { try? FileManager.default.removeItem(at: l.root) }
    XCTAssertEqual(l.row("cap-1")?["state"] as? String, "completed")
    let gone = l.root.appendingPathComponent("gone")
    _ = try l.enqueue(l.chunkedRaw(id: "cap-1", size: 30, parts: 3, source: gone)).get()
    let e = try XCTUnwrap(l.entry("cap-1"))
    XCTAssertFalse(e.legacy)
    XCTAssertEqual(e.bodyPath, "blob")
    XCTAssertEqual(e.parts.map(\.accepted), [true, true, false], "resumes, not E_FILE_MISSING")
    XCTAssertEqual(l.transport.live.count, 1)
  }

  func testLegacyErrorRowWithNewPartsKeepsTheV9Blob() throws {
    let l = try legacyChunked(type: "error", accepted: [true, false, false])
    defer { try? FileManager.default.removeItem(at: l.root) }
    let gone = l.root.appendingPathComponent("gone")
    _ = try l.enqueue(l.chunkedRaw(id: "cap-1", size: 30, parts: 3, source: gone,
                                   urlPrefix: "https://s3.test/new")).get()
    let e = try XCTUnwrap(l.entry("cap-1"))
    XCTAssertEqual(e.bodyPath, "blob")
    XCTAssertTrue(e.parts.allSatisfy { !$0.accepted }, "a new plan starts over on the kept bytes")
    XCTAssertEqual(l.transport.live.count, 3)
  }

  func testImportRunsOnce() throws {
    h.boot()
    let late = JournaledEventV9(eventId: "late", id: "t", type: "error", timestamp: 1)
    try JSONEncoder().encode(late).write(to: h.journal.root.appendingPathComponent("late.json"))
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertNil(fresh.row("t"), "the marker stops a second import")
  }
}
