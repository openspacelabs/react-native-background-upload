import XCTest
@testable import RNBGUCore

/// After a relaunch, the grace for a simple entry with no live task ends
/// and the entry moves to a new attempt. The old attempt's key is kept as a
/// late-result key, so its late accepted result still completes the entry.
final class CoordinatorLateResultTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  /// Attempt 1's task finished while the app was dead, and its callback is
  /// late. The grace ends, attempt 2 (task B) is issued, and then attempt 1's
  /// result arrives.
  private func reissuedAfterTheGrace(extra: [String: Any] = [:]) throws
    -> (fresh: Harness, original: FakeTask, b: FakeTask) {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a", extra: extra)).get()
    let original = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    fresh.advance(Double(QueueCoordinator.graceMs))
    let b = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
    XCTAssertEqual(fresh.map.meta(forKey: original.key)?.purpose, .lateResult)
    return (fresh, original, b)
  }

  private func replay(_ task: FakeTask) -> FakeTask {
    FakeTask(key: task.key, description: task.taskDescription, request: task.request)
  }

  private func settledResponse(_ x: Harness) -> [String: Any]? {
    x.sink.settled.last?["response"] as? [String: Any]
  }

  // MARK: - A late accepted result wins

  func testALateReplayAfterAReissueCompletesTheEntryAndStopsTheNewTask() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(replay(original), status: 201, body: #"{"id":7}"#)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertTrue(b.cancelled, "the second send stops")
    XCTAssertEqual(fresh.sink.settled.count, 1)
    XCTAssertEqual(settledResponse(fresh)?["status"] as? Int, 201, "the app gets the first attempt's response")
    XCTAssertEqual(settledResponse(fresh)?["body"] as? String, #"{"id":7}"#)
    XCTAssertEqual(fresh.sink.settled.first?["attempts"] as? Int, 2, "the outcome counts both attempts")
    XCTAssertEqual(fresh.sink.settled.first?["requestId"] as? String, original.header("X-Request-Id"),
                   "the request id of the response's own request")
    XCTAssertEqual(fresh.sink.attempts.last?["attempt"] as? Int, 1)
    XCTAssertEqual(fresh.sink.attempts.last?["requestId"] as? String, original.header("X-Request-Id"))
    // B's cancel callback adds nothing.
    fresh.deliverCancel(b)
    XCTAssertEqual(fresh.sink.settled.count, 1)
    XCTAssertEqual(fresh.transport.created.count, 1, "no third send")
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" }).isEmpty)
  }

  /// A late 2xx while the new attempt waits out a backoff. The waiting task
  /// never began, so the outcome must not name its request id.
  func testALate2xxWhileTheNewAttemptWaitsInBackoffCompletesTheEntry() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(b, status: 503)
    let delayed = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.state, .queued)
    fresh.complete(replay(original), status: 201)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertTrue(delayed.cancelled)
    XCTAssertEqual(fresh.sink.settled.first?["attempts"] as? Int, 3)
    XCTAssertEqual(fresh.sink.settled.first?["requestId"] as? String, original.header("X-Request-Id"))
    XCTAssertNotEqual(fresh.sink.settled.first?["requestId"] as? String, delayed.header("X-Request-Id"))
    fresh.deliverCancel(delayed)
    fresh.advance(60_000)
    XCTAssertEqual(fresh.transport.created.count, 2, "B and the delayed task only")
    XCTAssertEqual(fresh.sink.settled.count, 1)
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" }).isEmpty)
  }

  func testALate2xxWhilePausedCompletesTheEntry() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.pause()
    XCTAssertEqual(fresh.entry("a")?.state, .paused)
    fresh.complete(replay(original), status: 201)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.sink.settled.count, 1)
    fresh.deliverCancel(b)
    fresh.resume()
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.transport.created.count, 1, "the resume sends nothing")
    XCTAssertEqual(fresh.sink.settled.count, 1)
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" }).isEmpty)
  }

  func testALate2xxWhileAwaitingAuthCompletesTheEntry() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(b, status: 401)
    XCTAssertEqual(fresh.entry("a")?.state, .awaitingAuth)
    fresh.complete(replay(original), status: 201)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.sink.settled.count, 1)
    fresh.updateHeaders(["Authorization": "new"])
    XCTAssertEqual(fresh.transport.created.count, 1, "new headers send nothing")
  }

  /// The late 2xx lands at the next launch, before its reconcile, while B is
  /// still live in the daemon. Reconcile then cancels B.
  func testALate2xxBeforeTheNextReconcileCompletesTheEntryAndReconcileStopsB() throws {
    let (_, original, b) = try reissuedAfterTheGrace()
    let survivor = replay(b)
    let third = Harness(root: h.root)
    third.transport.deferAllTasks = true
    third.relaunch(daemonTasks: [survivor])
    var released = false
    third.coordinator.reconcileAll { released = true }
    third.drain()
    third.complete(replay(original), status: 201)
    XCTAssertEqual(third.entry("a")?.state, .completed)
    third.transport.releaseAllTasks()
    third.drain()
    third.drain()
    XCTAssertTrue(survivor.cancelled)
    XCTAssertTrue(released)
    XCTAssertTrue(third.transport.created.isEmpty)
    third.deliverCancel(survivor)
    XCTAssertEqual(third.sink.settled.count, 1)
    XCTAssertTrue(third.map.keys(where: { $0.id == "a" }).isEmpty)
  }

  /// A second relaunch between the re-issue and the late result. Attempt
  /// 1's task is listed as finished (not live): reconcile neither adopts nor
  /// cancels it, and its result still completes the entry.
  func testALateReplayAfterASecondRelaunchStillCompletesTheEntry() throws {
    let (_, original, b) = try reissuedAfterTheGrace()
    let listed = replay(original)
    listed.isLive = false
    let survivor = replay(b)
    let third = Harness(root: h.root)
    third.relaunch(daemonTasks: [listed, survivor])
    third.boot()
    XCTAssertFalse(listed.cancelled)
    XCTAssertTrue(third.transport.created.isEmpty, "B is adopted")
    XCTAssertEqual(third.map.meta(forKey: original.key)?.purpose, .lateResult)
    third.complete(listed, status: 201)
    XCTAssertEqual(third.entry("a")?.state, .completed)
    XCTAssertTrue(survivor.cancelled)
    XCTAssertEqual(third.sink.settled.count, 1)
  }

  /// Same, but B is lost too: the second grace issues attempt 3, and attempt
  /// 1's late result still wins over it.
  func testALateReplayAfterTwoReissuesCompletesTheEntry() throws {
    let (_, original, b) = try reissuedAfterTheGrace()
    let third = Harness(root: h.root)
    third.boot()
    third.advance(Double(QueueCoordinator.graceMs))
    let c = try XCTUnwrap(third.transport.live.first)
    XCTAssertEqual(third.entry("a")?.attempts, 3)
    XCTAssertEqual(third.map.meta(forKey: b.key)?.purpose, .lateResult)
    third.complete(replay(original), status: 201)
    XCTAssertEqual(third.entry("a")?.state, .completed)
    XCTAssertTrue(c.cancelled)
    XCTAssertEqual(third.sink.settled.count, 1)
    XCTAssertTrue(third.map.keys(where: { $0.id == "a" && $0.purpose == .lateResult }).isEmpty)
  }

  // MARK: - A late failure or cancel is dropped

  /// Each late result gets its own kept key: the first delivery removes it.
  private func assertALateResultLeavesTheNewAttemptAlone(
    status: Int? = nil, error: NSError? = nil, file: StaticString = #filePath, line: UInt = #line) throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    let attemptsBefore = fresh.sink.attempts.count
    fresh.complete(replay(original), status: status, error: error)
    XCTAssertEqual(fresh.entry("a")?.state, .running, file: file, line: line)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2, file: file, line: line)
    XCTAssertFalse(b.cancelled, file: file, line: line)
    XCTAssertEqual(fresh.transport.created.count, 1, "no retry for the old attempt", file: file, line: line)
    XCTAssertEqual(fresh.sink.attempts.count, attemptsBefore, "no event for the old attempt", file: file, line: line)
    XCTAssertTrue(fresh.sink.settled.isEmpty, file: file, line: line)
    fresh.complete(b, status: 201)
    XCTAssertEqual(fresh.entry("a")?.state, .completed, file: file, line: line)
    XCTAssertEqual(fresh.sink.settled.count, 1, file: file, line: line)
    XCTAssertEqual(fresh.sink.settled.first?["requestId"] as? String, b.header("X-Request-Id"), file: file, line: line)
  }

  func testALate500AfterAReissueLeavesTheNewAttemptAlone() throws {
    try assertALateResultLeavesTheNewAttemptAlone(status: 500)
  }

  func testALateTransportErrorAfterAReissueLeavesTheNewAttemptAlone() throws {
    try assertALateResultLeavesTheNewAttemptAlone(
      error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut))
  }

  func testALateCancelAfterAReissueLeavesTheNewAttemptAlone() throws {
    try assertALateResultLeavesTheNewAttemptAlone(
      error: NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled))
  }

  func testALateNonAcceptedResultLeavesTheRequestIdAlone() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    XCTAssertEqual(fresh.entry("a")?.lastRequestId, b.header("X-Request-Id"))
    fresh.complete(replay(original), status: 409)
    XCTAssertEqual(fresh.entry("a")?.lastRequestId, b.header("X-Request-Id"))
    XCTAssertEqual(fresh.store.load("a")?.lastRequestId, b.header("X-Request-Id"))
  }

  /// The entry's request id changes only for an accepted result. Here the
  /// task's TaskMap request id is set by hand to differ from the entry's,
  /// and a 401 parks the entry: the parked row keeps its own request id.
  func testOnlyAnAcceptedResultChangesTheEntrysRequestId() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    var meta = try XCTUnwrap(h.map.meta(forKey: task.key))
    let own = h.entry("a")?.lastRequestId
    meta.requestId = "another-request"
    h.map.set(meta, forKey: task.key)
    h.complete(task, status: 401)
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    XCTAssertEqual(h.entry("a")?.lastRequestId, own)
    XCTAssertEqual(h.store.load("a")?.lastRequestId, own)
  }

  func testALate2xxAfterTheNewAttemptCompletedIsDropped() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(b, status: 200)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    let attemptsBefore = fresh.sink.attempts.count
    fresh.complete(replay(original), status: 201)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
    XCTAssertEqual(fresh.sink.settled.count, 1, "one outcome")
    XCTAssertEqual(fresh.sink.attempts.count, attemptsBefore)
    XCTAssertTrue(fresh.transport.live.isEmpty)
  }

  func testALate2xxAfterAUserCancelIsDropped() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.cancel("a")
    XCTAssertEqual(fresh.entry("a")?.state, .cancelled)
    XCTAssertNil(fresh.map.meta(forKey: original.key))
    let attemptsBefore = fresh.sink.attempts.count
    fresh.complete(replay(original), status: 201)
    fresh.deliverCancel(b)
    XCTAssertEqual(fresh.entry("a")?.state, .cancelled)
    XCTAssertEqual(fresh.sink.settled.count, 1, "the cancel only")
    XCTAssertEqual(fresh.sink.attempts.count, attemptsBefore)
  }

  // MARK: - When the kept keys go

  func testTheLateResultKeysAreRemovedWhenTheEntrySettles() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(b, status: 400)
    XCTAssertEqual(fresh.entry("a")?.state, .error)
    XCTAssertNil(fresh.map.meta(forKey: original.key))
    XCTAssertTrue(fresh.map.keys(where: { $0.id == "a" }).isEmpty, "nothing left for the entry")
  }

  /// The settle's journal write landed and its entry save did not, and the
  /// process died before the kept key was removed. The relaunch repair of
  /// the lost settle removes it.
  func testTheRepairOfALostSettleRemovesTheLateResultKeys() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    let kept = try XCTUnwrap(fresh.map.meta(forKey: original.key))
    let dir = fresh.store.dir("a")
    setReadOnly(dir, true)
    fresh.complete(b, status: 201)
    setReadOnly(dir, false)
    XCTAssertEqual(fresh.store.load("a")?.state, .running, "the disk still says running")
    fresh.map.set(kept, forKey: original.key) // the removal did not land
    let third = Harness(root: h.root)
    third.boot()
    XCTAssertEqual(third.entry("a")?.state, .completed)
    XCTAssertNil(third.map.meta(forKey: original.key))
    XCTAssertTrue(third.transport.created.isEmpty)
  }

  /// A settled row whose kept key is still there, as if the removal at the
  /// settle did not land. Forgetting the row removes it.
  private func settledWithAKeptKey(status: Int) throws -> (Harness, FakeTask) {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    let kept = try XCTUnwrap(fresh.map.meta(forKey: original.key))
    fresh.complete(b, status: status)
    fresh.map.set(kept, forKey: original.key)
    return (fresh, original)
  }

  func testTheAckThatForgetsTheRowRemovesTheLateResultKeys() throws {
    let (fresh, original) = try settledWithAKeptKey(status: 200)
    fresh.ack([try XCTUnwrap(fresh.sink.settled.last?["eventId"] as? String)])
    XCTAssertNil(fresh.row("a"))
    XCTAssertNil(fresh.map.meta(forKey: original.key))
  }

  func testCancelOfASettledRowRemovesTheLateResultKeys() throws {
    let (fresh, original) = try settledWithAKeptKey(status: 400)
    XCTAssertNil(fresh.cancel("a"))
    XCTAssertNil(fresh.row("a"))
    XCTAssertNil(fresh.map.meta(forKey: original.key))
  }

  /// The entry expires inside the grace's own re-issue, so it settles before
  /// a new attempt is added: the kept key has the entry's current attempt.
  /// It still goes, so a later use of the id cannot take its late result.
  /// The later use is on attempt 2 here. On attempt 1 the task description
  /// alone would match it by id, generation and attempt; the incarnation in
  /// the description drops it (CoordinatorPerUseTests).
  func testAnEntryThatSettlesInsideTheGraceReissueDropsItsKeptKey() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 60_000])).get()
    let original = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.clock = h.clock + 120_000
    fresh.boot()
    fresh.advance(Double(QueueCoordinator.graceMs))
    XCTAssertEqual(fresh.entry("a")?.state, .error, "expired")
    XCTAssertEqual(fresh.entry("a")?.attempts, 1)
    XCTAssertNil(fresh.map.meta(forKey: original.key))
    // The app cancels the error row and uses the id again.
    fresh.cancel("a")
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 2])).get()
    fresh.complete(try XCTUnwrap(fresh.transport.live.first), status: 503)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
    let delayed = try XCTUnwrap(fresh.transport.live.first)
    let settledBefore = fresh.sink.settled.count
    fresh.complete(replay(original), status: 201, body: "STALE")
    XCTAssertEqual(fresh.entry("a")?.state, .queued, "the new body still goes out")
    XCTAssertFalse(delayed.cancelled)
    XCTAssertEqual(fresh.sink.settled.count, settledBefore)
  }

  /// A different body replaces the entry: the old body's late result must
  /// not complete the new one.
  private func assertADifferentBodyDropsTheKeptKey(
    file: StaticString = #filePath, line: UInt = #line, _ setUp: (Harness, FakeTask) throws -> Void) throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    try setUp(fresh, b)
    XCTAssertNotEqual(fresh.entry("a")?.state, .running, file: file, line: line)
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 2])).get()
    XCTAssertEqual(fresh.entry("a")?.generation, 2, file: file, line: line)
    XCTAssertNil(fresh.map.meta(forKey: original.key), file: file, line: line)
    let stateBefore = fresh.entry("a")?.state
    let live = fresh.transport.live
    fresh.complete(replay(original), status: 201, body: "STALE")
    XCTAssertEqual(fresh.entry("a")?.state, stateBefore, file: file, line: line)
    XCTAssertTrue(live.allSatisfy { !$0.cancelled }, file: file, line: line)
    XCTAssertTrue(fresh.sink.settled.isEmpty, file: file, line: line)
  }

  func testADifferentBodyInBackoffDropsTheKeptKey() throws {
    try assertADifferentBodyDropsTheKeptKey { fresh, b in fresh.complete(b, status: 503) }
  }

  func testADifferentBodyWhilePausedDropsTheKeptKey() throws {
    try assertADifferentBodyDropsTheKeptKey { fresh, b in
      fresh.pause()
      fresh.deliverCancel(b)
    }
  }

  func testADifferentBodyWhileAwaitingAuthDropsTheKeptKey() throws {
    try assertADifferentBodyDropsTheKeptKey { fresh, b in fresh.complete(b, status: 401) }
  }

  /// Three uses of one id. Use 1's attempt 1 is kept; a different body
  /// replaces it (use 1, generation 2), which completes and is acked; use 3
  /// starts again at generation 1 and its first attempt fails. Use 1's late
  /// 2xx must not complete use 3 with an old response.
  func testALateResultFromAnEarlierUseOfTheIdDoesNotCompleteALaterUse() throws {
    let (fresh, original, b) = try reissuedAfterTheGrace()
    fresh.complete(b, status: 503)
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 2])).get()
    let e = try XCTUnwrap(fresh.transport.live.last)
    fresh.complete(e, status: 200)
    let eventId = try XCTUnwrap(fresh.sink.settled.last?["eventId"] as? String)
    fresh.ack([eventId])
    XCTAssertNil(fresh.row("a"))
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 3])).get()
    let c = try XCTUnwrap(fresh.transport.live.last)
    fresh.complete(c, status: 503)
    XCTAssertEqual(fresh.entry("a")?.generation, 1)
    XCTAssertEqual(fresh.entry("a")?.attempts, 2)
    let delayed = try XCTUnwrap(fresh.transport.live.last)
    let settledBefore = fresh.sink.settled.count
    fresh.complete(replay(original), status: 201, body: "STALE-FROM-USE-1")
    XCTAssertEqual(fresh.entry("a")?.state, .queued, "use 3 still sends its own body")
    XCTAssertFalse(delayed.cancelled)
    XCTAssertEqual(fresh.sink.settled.count, settledBefore)
  }

  /// A task that reconcile found live under a kept key is cancelled. It
  /// owes a cancel callback, so its key must outlive the settle and the ack.
  /// Without it, after the id is used again, the old cancel would count as
  /// a system cancel of the new use and send it twice.
  func testACancelCallbackOfAReconcileCancelledTaskDoesNothingAfterTheIdIsUsedAgain() throws {
    let (_, original, b) = try reissuedAfterTheGrace()
    let old = replay(original)
    let survivor = replay(b)
    let third = Harness(root: h.root)
    third.relaunch(daemonTasks: [old, survivor])
    third.boot()
    XCTAssertTrue(old.cancelled)
    XCTAssertFalse(survivor.cancelled, "B is adopted")
    XCTAssertEqual(third.map.meta(forKey: old.key)?.purpose, .superseded, "it owes a cancel callback")
    third.complete(survivor, status: 200)
    XCTAssertEqual(third.map.meta(forKey: old.key)?.purpose, .superseded, "kept past the settle")
    let eventId = try XCTUnwrap(third.sink.settled.last?["eventId"] as? String)
    third.ack([eventId])
    _ = try third.enqueue(third.dataRaw(id: "a", data: ["x": 2])).get()
    let created = third.transport.created.count
    third.deliverCancel(old)
    XCTAssertEqual(third.entry("a")?.state, .running)
    XCTAssertEqual(third.entry("a")?.attempts, 1)
    XCTAssertEqual(third.transport.created.count, created, "no second send")
  }
}
