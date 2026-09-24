import XCTest
@testable import RNBGUCore

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
    XCTAssertNil(fresh.map.meta(forKey: lost.key))
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

  // MARK: - Review fixes: lost saves, early completions, pending replays

  /// The journal write landed, the entry.json save did not. The relaunch
  /// must not send the request again.
  private func settleWithFailedSave(_ finish: (Harness, FakeTask) -> Void) throws -> String {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let dir = h.store.dir("a")
    setReadOnly(dir, true)
    finish(h, task)
    setReadOnly(dir, false)
    XCTAssertEqual(h.store.load("a")?.state, .running, "the disk still says running")
    return try XCTUnwrap(h.journal.unacknowledged().first?.eventId)
  }

  func testSettleThenFailedSaveIsRepairedAtRelaunchAndNotSentAgain() throws {
    let eventId = try settleWithFailedSave { h, task in h.complete(task) }
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty, "no second POST")
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.entry("a")?.settledEventId, eventId)
    XCTAssertEqual(fresh.store.load("a")?.state, .completed, "the repair is saved")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertTrue(fresh.transport.created.isEmpty)
    XCTAssertEqual(fresh.unacknowledged().first?["eventId"] as? String, eventId)
    fresh.ack([eventId])
    XCTAssertNil(fresh.row("a"))
  }

  func testCancelThenFailedSaveDoesNotRunAgainAtRelaunch() throws {
    let eventId = try settleWithFailedSave { h, _ in h.cancel("a") }
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty)
    XCTAssertEqual(fresh.entry("a")?.state, .cancelled)
    XCTAssertEqual(fresh.entry("a")?.settledEventId, eventId)
  }

  func testAckBeforeReconcileForgetsARowWhoseSettleSaveFailed() throws {
    let eventId = try settleWithFailedSave { h, task in h.complete(task) }
    let fresh = Harness(root: h.root) // no boot: the ack runs first
    fresh.ack([eventId])
    XCTAssertNil(fresh.row("a"))
    XCTAssertFalse(FileIO.exists(fresh.store.dir("a")))
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty)
  }

  func testCompletionBeforeReconcileMintsTheNextAttempt() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let ran = h.transport.live[0]
    let survivor = FakeTask(key: ran.key, description: ran.taskDescription, request: ran.request)
    let fresh = Harness(root: h.root)
    fresh.transport.deferAllTasks = true
    fresh.relaunch(daemonTasks: [survivor])
    fresh.coordinator.reconcileAll {}
    fresh.drain()
    fresh.complete(survivor, status: 503) // lands before reconcile
    fresh.transport.releaseAllTasks()
    fresh.drain()
    fresh.drain()
    let retry = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
    XCTAssertNotEqual(retry.header("X-Request-Id"), ran.header("X-Request-Id"), "a new attempt, a new id")
    XCTAssertEqual(ChunkedEngine.parseRequestDescription(retry.taskDescription)?.attempt, 2)
    XCTAssertNotNil(retry.beginAt, "it keeps the backoff")
  }

  /// Parts 1 and 2 still live in the daemon; part 0 finished while the app
  /// was dead, so its TaskMap key is there and its completion may replay.
  private func chunkedRelaunchWithPart0Pending() throws -> (Harness, FakeTask) {
    h.boot()
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 50, parts: 5)).get()
    let parts = h.transport.live
    let part0 = try XCTUnwrap(parts.first { ChunkedEngine.parsePartDescription($0.taskDescription)?.part == 0 })
    let survivors = parts.filter { $0 !== part0 }
      .map { FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request) }
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: survivors)
    return (fresh, part0)
  }

  func testChunkedRelaunchHoldsAPendingPartUntilItsReplay() throws {
    let (fresh, part0) = try chunkedRelaunchWithPart0Pending()
    var released = false
    fresh.coordinator.reconcileAll { released = true }
    fresh.drain()
    fresh.drain()
    XCTAssertTrue(fresh.transport.created.isEmpty, "no second PUT of part 0, and no part 3 past the window")
    XCTAssertFalse(released, "the background completion handler waits for the grace")
    let file = fresh.store.partFileURL("cap", 0, incarnation: fresh.entry("cap")!.incarnation, start: 0, end: 10)
    XCTAssertTrue(FileIO.exists(file), "the part file stays for the replay")
    fresh.complete(FakeTask(key: part0.key, description: part0.taskDescription, request: part0.request))
    XCTAssertEqual(fresh.entry("cap")?.parts[0].accepted, true)
    XCTAssertEqual(fresh.transport.created.count, 1)
    XCTAssertEqual(ChunkedEngine.parsePartDescription(fresh.transport.created[0].taskDescription)?.part, 3)
    XCTAssertTrue(released, "the replay came, so the handler releases at once")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertEqual(fresh.transport.created.count, 1, "the grace timer releases nothing")
  }

  func testChunkedPendingPartWithNoReplayIsSentAfterTheGrace() throws {
    let (fresh, part0) = try chunkedRelaunchWithPart0Pending()
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty)
    fresh.advance(Double(QueueCoordinator.graceMs))
    let resent = try XCTUnwrap(fresh.transport.live.first {
      ChunkedEngine.parsePartDescription($0.taskDescription)?.part == 0 })
    XCTAssertNotEqual(resent.key, part0.key)
    XCTAssertNil(fresh.map.meta(forKey: part0.key), "the lost task's key is pruned")
    // A late replay of the lost task: the part is accepted, and the
    // duplicate PUT stops before its part file goes.
    fresh.complete(FakeTask(key: part0.key, description: part0.taskDescription, request: part0.request))
    XCTAssertEqual(fresh.entry("cap")?.parts[0].accepted, true)
    XCTAssertTrue(resent.cancelled)
  }

  func testBackgroundCompletionWaitsForTheSimpleGraceAndNotLonger() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let fresh = Harness(root: h.root) // the task's key is there, the task is not
    var released = false
    fresh.coordinator.reconcileAll { released = true }
    fresh.drain()
    fresh.drain()
    XCTAssertFalse(released, "held while the replay may still come")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertTrue(released)
    XCTAssertEqual(fresh.transport.live.count, 1, "released after the re-issue")

    let idle = Harness(root: makeTempDir())
    defer { try? FileManager.default.removeItem(at: idle.root) }
    var idleReleased = false
    idle.coordinator.reconcileAll { idleReleased = true }
    idle.drain()
    idle.drain()
    XCTAssertTrue(idleReleased, "no grace, no wait")
  }

  func testBackgroundCompletionReleasesWhenTheSimpleReplayLands() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let fresh = Harness(root: h.root)
    var released = false
    fresh.coordinator.reconcileAll { released = true }
    fresh.drain()
    fresh.drain()
    XCTAssertFalse(released)
    fresh.complete(FakeTask(key: task.key, description: task.taskDescription, request: task.request))
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertTrue(released, "the replay came, so the handler does not wait out the grace")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertTrue(fresh.transport.created.isEmpty, "the grace timer sends nothing")
  }

  func testAReplayThatLeavesTheEntryAloneReissuesAtOnce() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertTrue(fresh.transport.created.isEmpty)
    // The replay was superseded, so it does not move the entry. The attempt
    // is lost, and the wait for it is over.
    fresh.map.setPurpose(.superseded, forKey: task.key, id: "a")
    fresh.complete(FakeTask(key: task.key, description: task.taskDescription, request: task.request),
                   status: 503)
    XCTAssertEqual(fresh.transport.live.count, 1, "re-issued without waiting out the grace")
    XCTAssertEqual(fresh.entry("a")?.attempts, 2, "a new ordinal: the lost attempt may have run")
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertEqual(fresh.transport.created.count, 1, "the timer does not send it again")
  }

  func testAnEndedGraceTimerDoesNotCloseANewerWait() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let first = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot() // wait A opens, its timer is due at graceMs
    fresh.complete(FakeTask(key: first.key, description: first.taskDescription, request: first.request),
                   status: 503) // the replay ends wait A; the retry is attempt 2
    let retry = try XCTUnwrap(fresh.transport.live.first)
    fresh.advance(Double(QueueCoordinator.graceMs / 2))
    // The retry finished while the app was dead: its key is there, the task
    // is not. A second reconcile opens wait B for it.
    retry.isLive = false
    var released = false
    fresh.coordinator.reconcileAll { released = true }
    fresh.drain()
    fresh.drain()
    XCTAssertFalse(released)
    fresh.advance(Double(QueueCoordinator.graceMs / 2)) // wait A's timer fires
    XCTAssertFalse(released, "wait A's timer does not close wait B")
    fresh.advance(Double(QueueCoordinator.graceMs / 2))
    XCTAssertTrue(released)
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
    V9Journal.write(V9Journal.completed(eventId: "ev1", id: "transfer-1", timestamp: 100), eventId: "ev1",
                    into: v9Journal.root)
    let manifest = ChunkedManifestV9(id: "cap-1", parts: [
      .init(url: "https://s3.test/part1", start: 0, end: 10, accepted: true),
      .init(url: "https://s3.test/part2", start: 10, end: 20, accepted: false),
      .init(url: "https://s3.test/part3", start: 20, end: 30, accepted: false),
    ], expiresAt: h.expiresAt, incarnation: "v9inc")
    writeFile(v9Store.fileURL("cap-1", QueueStore.manifestName),
              String(data: try JSONEncoder().encode(manifest), encoding: .utf8)!)
    writeFile(v9Store.fileURL("cap-1", "blob"), bytes: 30)
    TaskMap(fileURL: root.appendingPathComponent("taskmap.json")).set(.init(id: "transfer-1"), forKey: "any:5")

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
    let json = type == "error" ? V9Journal.error(eventId: "ev1", id: "cap-1", timestamp: 100)
      : V9Journal.completed(eventId: "ev1", id: "cap-1", timestamp: 100)
    V9Journal.write(json, eventId: "ev1", into: v9Journal.root)
    let parts: [ChunkedManifestV9.Part] = (0..<3).map { i in
      ChunkedManifestV9.Part(url: "https://s3.test/part\(i + 1)", start: Int64(i * 10),
                             end: Int64(i * 10 + 10), accepted: accepted[i])
    }
    let manifest = ChunkedManifestV9(id: "cap-1", parts: parts, expiresAt: h.expiresAt, incarnation: "v9inc")
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
    XCTAssertEqual(l.row("cap-1")?["bytesSent"] as? Int64, 0, "a legacy row reports 0/0, as on Android")
    XCTAssertEqual(l.row("cap-1")?["totalBytes"] as? Int64, 0)
    let gone = l.root.appendingPathComponent("gone")
    _ = try l.enqueue(l.chunkedRaw(id: "cap-1", size: 30, parts: 3, source: gone)).get()
    let e = try XCTUnwrap(l.entry("cap-1"))
    XCTAssertEqual(l.row("cap-1")?["bytesSent"] as? Int64, 20, "the adopted entry counts the v9 accepted parts")
    XCTAssertEqual(l.row("cap-1")?["totalBytes"] as? Int64, 30)
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

  func testV9JournalFilesOfEachKindImportAsLegacyRows() throws {
    let root = makeTempDir()
    let events = root.appendingPathComponent("events")
    V9Journal.write(V9Journal.completed(eventId: "e1", id: "t-done", timestamp: 100), eventId: "e1", into: events)
    V9Journal.write(V9Journal.error(eventId: "e2", id: "t-bad", timestamp: 200), eventId: "e2", into: events)
    V9Journal.write(V9Journal.cancelled(eventId: "e3", id: "t-off", timestamp: 300), eventId: "e3", into: events)
    h = Harness(root: root)
    h.boot()
    XCTAssertEqual(h.row("t-done")?["state"] as? String, "completed")
    XCTAssertEqual(h.row("t-bad")?["state"] as? String, "error")
    XCTAssertEqual(h.entry("t-bad")?.lastPartIndex, 2)
    XCTAssertEqual(h.row("t-off")?["state"] as? String, "cancelled")
    XCTAssertTrue(["t-done", "t-bad", "t-off"].allSatisfy { h.row($0)?["key"] as? String == "legacy" })
    XCTAssertTrue(h.journal.legacyEvents().isEmpty, "the v9 files are gone after the import")
    XCTAssertTrue(h.unacknowledged().isEmpty, "nothing is delivered")
  }

  func testImportRunsOnce() throws {
    h.boot()
    V9Journal.write(V9Journal.error(eventId: "late", id: "t", timestamp: 1), eventId: "late", into: h.journal.root)
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertNil(fresh.row("t"), "the marker stops a second import")
  }
}
