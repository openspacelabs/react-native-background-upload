import XCTest
@testable import RNBGUCore

/// An app can use one request id again for the same object. Each use is a
/// new entry: it starts again at generation 1 and attempt 1. A task result,
/// cancel callback, progress event or relaunch match that comes from an
/// earlier use of the id must never change a later use.
final class CoordinatorPerUseTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  private func replay(_ task: FakeTask) -> FakeTask {
    FakeTask(key: task.key, description: task.taskDescription, request: task.request)
  }

  /// Use 1's attempt 1 (`original`) finished while the app was dead and its
  /// callback is late. The relaunch wait ends, attempt 2 (B) completes use 1,
  /// the outcome is acked, and the id is used again with another body. Use
  /// 2 runs its attempt 1 (`c`).
  private func secondUseAfterALateWait() throws -> (fresh: Harness, original: FakeTask, c: FakeTask) {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let original = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    fresh.advance(Double(QueueCoordinator.graceMs))
    let b = try XCTUnwrap(fresh.transport.live.first)
    fresh.complete(b, status: 200)
    fresh.ack([try XCTUnwrap(fresh.sink.settled.first?["eventId"] as? String)])
    XCTAssertNil(fresh.row("a"))
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.entry("a")?.generation, 1)
    XCTAssertEqual(fresh.entry("a")?.attempts, 1)
    return (fresh, original, c)
  }

  /// Use 2 is untouched: still running its own attempt 1, with no event.
  private func assertTheSecondUseIsUntouched(
    _ fresh: Harness, c: FakeTask, settledBefore: Int, attemptsBefore: Int, createdBefore: Int,
    file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertEqual(fresh.entry("a")?.state, .running, file: file, line: line)
    XCTAssertEqual(fresh.entry("a")?.attempts, 1, file: file, line: line)
    XCTAssertEqual(fresh.entry("a")?.lastRequestId, c.header("X-Request-Id"), file: file, line: line)
    XCTAssertFalse(c.cancelled, "use 2's own task goes on", file: file, line: line)
    XCTAssertEqual(fresh.transport.created.count, createdBefore, "no retry", file: file, line: line)
    XCTAssertEqual(fresh.sink.attempts.count, attemptsBefore, "no attempt event", file: file, line: line)
    XCTAssertEqual(fresh.sink.settled.count, settledBefore, "no outcome", file: file, line: line)
    // Use 2's own result still settles it.
    fresh.complete(c, status: 201, body: "USE-2")
    XCTAssertEqual(fresh.entry("a")?.state, .completed, file: file, line: line)
    XCTAssertEqual((fresh.sink.settled.last?["response"] as? [String: Any])?["body"] as? String, "USE-2",
                   file: file, line: line)
  }

  private func assertALateResultOfTheFirstUseLeavesTheSecondAlone(
    status: Int, file: StaticString = #filePath, line: UInt = #line) throws {
    let (fresh, original, c) = try secondUseAfterALateWait()
    let settled = fresh.sink.settled.count
    let attempts = fresh.sink.attempts.count
    let created = fresh.transport.created.count
    fresh.complete(replay(original), status: status, body: "STALE-FROM-USE-1")
    assertTheSecondUseIsUntouched(fresh, c: c, settledBefore: settled, attemptsBefore: attempts,
                                  createdBefore: created, file: file, line: line)
  }

  // MARK: - A late result of an earlier use

  func testALate500FromAnEarlierUseIsNotAFailedAttemptOfTheNextUse() throws {
    try assertALateResultOfTheFirstUseLeavesTheSecondAlone(status: 500)
  }

  func testALate2xxFromAnEarlierUseDoesNotCompleteTheNextUse() throws {
    try assertALateResultOfTheFirstUseLeavesTheSecondAlone(status: 201)
  }

  func testALate4xxFromAnEarlierUseDoesNotFailTheNextUse() throws {
    try assertALateResultOfTheFirstUseLeavesTheSecondAlone(status: 400)
  }

  /// The task-map key of use 1's attempt 1 is still there: the app cancelled
  /// use 1 during the relaunch wait, which leaves the key as it was. Use 2
  /// is on attempt 1, so the key and the description both match it by id,
  /// generation and attempt.
  func testALate2xxFromAnEarlierUseWithItsKeyStillThereDoesNotCompleteTheNextUse() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let original = h.transport.live[0]
    let fresh = Harness(root: h.root)
    fresh.boot()
    XCTAssertNil(fresh.cancel("a"))
    fresh.ack([try XCTUnwrap(fresh.unacknowledged().first?["eventId"] as? String)])
    XCTAssertNil(fresh.row("a"))
    XCTAssertNotNil(fresh.map.meta(forKey: original.key), "the waited key is still there")
    _ = try fresh.enqueue(fresh.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(fresh.transport.live.first)
    let settled = fresh.sink.settled.count
    let attempts = fresh.sink.attempts.count
    fresh.complete(replay(original), status: 201, body: "STALE-FROM-USE-1")
    assertTheSecondUseIsUntouched(fresh, c: c, settledBefore: settled, attemptsBefore: attempts, createdBefore: 1)
  }

  /// cancel(id) cancels use 1's running task, but the task had already
  /// landed. Its 2xx comes after the ack and after use 2 started.
  func testATaskStoppedByCancelThatLandsInALaterUseIsDropped() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let first = h.transport.live[0]
    XCTAssertNil(h.cancel("a"))
    XCTAssertTrue(first.cancelled)
    h.ack([try XCTUnwrap(h.sink.settled.first?["eventId"] as? String)])
    XCTAssertNil(h.row("a"))
    _ = try h.enqueue(h.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(h.transport.live.first)
    let settled = h.sink.settled.count
    let attempts = h.sink.attempts.count
    h.complete(first, status: 200, body: "STALE-FROM-USE-1")
    assertTheSecondUseIsUntouched(h, c: c, settledBefore: settled, attemptsBefore: attempts, createdBefore: 2)
  }

  // MARK: - Cancel callback, begin, progress and relaunch

  /// Use 1 was cancelled and acked, use 2 started. Use 1's task-map key is
  /// gone (as after a lost write), so only the description names the task.
  private func secondUseWithAnUnkeyedFirstTask() throws -> (first: FakeTask, c: FakeTask) {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let first = h.transport.live[0]
    XCTAssertNil(h.cancel("a"))
    h.ack([try XCTUnwrap(h.sink.settled.first?["eventId"] as? String)])
    _ = try h.enqueue(h.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(h.transport.live.first)
    h.map.removeKey(first.key)
    return (replay(first), c)
  }

  /// A cancel callback with no key reads as a cancel the system made, which
  /// retries. From an earlier use it must not retry the next use.
  func testALateCancelOfAnEarlierUseDoesNotRetryTheNextUse() throws {
    let (first, c) = try secondUseWithAnUnkeyedFirstTask()
    let settled = h.sink.settled.count
    let attempts = h.sink.attempts.count
    let created = h.transport.created.count
    h.deliverCancel(first)
    assertTheSecondUseIsUntouched(h, c: c, settledBefore: settled, attemptsBefore: attempts, createdBefore: created)
  }

  func testADelayedTaskOfAnEarlierUseThatBeginsIsCancelled() throws {
    let (first, _) = try secondUseWithAnUnkeyedFirstTask()
    XCTAssertNil(h.coordinator.taskWillBegin(key: first.key, description: first.taskDescription),
                 "the old task must not send")
    XCTAssertEqual(h.map.meta(forKey: first.key)?.purpose, .superseded, "its cancel callback is dropped")
    XCTAssertEqual(h.entry("a")?.state, .running)
  }

  /// Before the first reconcile no task is held, so the progress guard can
  /// only use the task's own identity.
  func testProgressOfAnEarlierUseBeforeReconcileIsDropped() throws {
    let (first, _) = try secondUseWithAnUnkeyedFirstTask()
    h.relaunch()
    let progressBefore = h.sink.progress.count
    h.coordinator.taskProgress(key: first.key, description: first.taskDescription, sent: 5, expected: 10)
    h.drain()
    XCTAssertEqual(h.sink.progress.count, progressBefore)
    XCTAssertEqual(h.entry("a")?.bytesSent, 0)
  }

  /// Both tasks are live at the next launch, the old one listed first. The
  /// old one must not be adopted in place of use 2's own task.
  func testReconcileAdoptsTheTaskOfTheCurrentUseNotAnEarlierOne() throws {
    let (first, c) = try secondUseWithAnUnkeyedFirstTask()
    let current = replay(c)
    let next = Harness(root: h.root)
    next.relaunch(daemonTasks: [first, current])
    next.boot()
    XCTAssertTrue(first.cancelled, "the old task stops")
    XCTAssertFalse(current.cancelled, "use 2's task is adopted")
    XCTAssertTrue(next.transport.created.isEmpty)
    next.complete(current, status: 201, body: "USE-2")
    XCTAssertEqual(next.entry("a")?.state, .completed)
  }

  /// cancel(id) marked use 1's task superseded, and its cancel callback
  /// never came: the process died first, and the daemon did not replay it.
  /// Use 2 is on generation 1, attempt 1 too, and its own task finished
  /// while the app was dead. Its replay must end the relaunch wait at once:
  /// the old key holds nothing.
  func testAKeyOfAnEarlierUseDoesNotHoldTheNextUsesRelaunchWait() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let first = h.transport.live[0]
    XCTAssertNil(h.cancel("a"))
    h.ack([try XCTUnwrap(h.sink.settled.first?["eventId"] as? String)])
    XCTAssertEqual(h.map.meta(forKey: first.key)?.purpose, .superseded, "it outlives the use")
    _ = try h.enqueue(h.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(h.transport.live.first)
    let next = Harness(root: h.root)
    var released = false
    next.coordinator.reconcileAll { released = true }
    next.drain()
    next.drain()
    XCTAssertNil(next.map.meta(forKey: first.key), "the next launch prunes it")
    XCTAssertFalse(released, "it waits for use 2's own replay")
    next.complete(replay(c), status: 201, body: "USE-2")
    XCTAssertTrue(released, "the replay of use 2's task ends the wait")
    XCTAssertEqual(next.entry("a")?.state, .completed)
    XCTAssertTrue(next.transport.created.isEmpty)
  }

  /// As above, but the process died before the daemon applied the cancel,
  /// so use 1's task is still live at the next launch. It owes a cancel
  /// callback; the wait for use 2's replay must not wait for it.
  func testALiveTaskOfAnEarlierUseDoesNotHoldTheNextUsesRelaunchWait() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let first = h.transport.live[0]
    XCTAssertNil(h.cancel("a"))
    h.ack([try XCTUnwrap(h.sink.settled.first?["eventId"] as? String)])
    _ = try h.enqueue(h.dataRaw(id: "a", data: ["x": 2])).get()
    let c = try XCTUnwrap(h.transport.live.first)
    let old = replay(first)
    let next = Harness(root: h.root)
    next.relaunch(daemonTasks: [old])
    var released = false
    next.coordinator.reconcileAll { released = true }
    next.drain()
    next.drain()
    XCTAssertTrue(old.cancelled)
    XCTAssertFalse(released, "it waits for use 2's own replay")
    next.complete(replay(c), status: 201, body: "USE-2")
    XCTAssertTrue(released, "the replay of use 2's task ends the wait")
    XCTAssertEqual(next.entry("a")?.state, .completed)
    next.deliverCancel(old)
    XCTAssertEqual(next.sink.settled.count, 1)
    XCTAssertTrue(next.transport.created.isEmpty)
  }

  // MARK: - Tasks and files from 11.3.0 and earlier

  /// What 11.3.0 wrote for a simple attempt: a description and a key with no
  /// incarnation, and an entry.json with no tasksCarryIncarnation. Literal,
  /// so a change to the encoder cannot change the fixture with it.
  private static func description113(id: String, generation: Int, attempt: Int) -> String {
    #"rnbgu-req:{"id":"\#(id)","gen":\#(generation),"att":\#(attempt)}"#
  }

  /// Enqueues "a" and rewrites its entry, its task and its key as 11.3.0
  /// wrote them. Returns the task as the daemon holds it after the upgrade.
  private func entryAndTaskFrom113() throws -> FakeTask {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let url = h.store.fileURL("a", QueueStore.entryName)
    var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    XCTAssertEqual(json["tasksCarryIncarnation"] as? Bool, true)
    json["tasksCarryIncarnation"] = nil
    try JSONSerialization.data(withJSONObject: json).write(to: url)
    var meta = try XCTUnwrap(h.map.meta(forKey: task.key))
    XCTAssertEqual(meta.incarnation, h.entry("a")?.incarnation)
    meta.incarnation = nil
    h.map.set(meta, forKey: task.key)
    return FakeTask(key: task.key, description: Self.description113(id: "a", generation: 1, attempt: 1),
                    request: task.request)
  }

  func testATaskFrom113StillLiveAfterTheUpgradeIsAdoptedAndCompletesItsEntry() throws {
    let old = try entryAndTaskFrom113()
    let next = Harness(root: h.root)
    next.relaunch(daemonTasks: [old])
    next.boot()
    XCTAssertNil(next.entry("a")?.tasksCarryIncarnation)
    XCTAssertFalse(old.cancelled, "the 11.3.0 task goes on")
    XCTAssertTrue(next.transport.created.isEmpty, "nothing is sent again")
    next.complete(old, status: 201, body: "FROM-113")
    XCTAssertEqual(next.entry("a")?.state, .completed)
    XCTAssertEqual((next.sink.settled.last?["response"] as? [String: Any])?["body"] as? String, "FROM-113")
  }

  /// The 11.3.0 task finished while the app was dead, and its callback
  /// replays inside the relaunch wait after the upgrade.
  func testATaskFrom113ThatFinishesAfterTheUpgradeCompletesItsEntry() throws {
    let old = try entryAndTaskFrom113()
    let next = Harness(root: h.root)
    var released = false
    next.coordinator.reconcileAll { released = true }
    next.drain()
    next.drain()
    XCTAssertFalse(released, "the 11.3.0 key is waited for")
    next.complete(old, status: 201)
    XCTAssertTrue(released)
    XCTAssertEqual(next.entry("a")?.state, .completed)
    XCTAssertTrue(next.transport.created.isEmpty)
  }

  /// A failure of an 11.3.0 task still counts for its entry. The retry is a
  /// tagged task, and it drives the entry that 11.3.0 created: it begins,
  /// and its 2xx completes the entry.
  func testAFailureOfATaskFrom113StillRetriesItsEntry() throws {
    let old = try entryAndTaskFrom113()
    let next = Harness(root: h.root)
    next.relaunch(daemonTasks: [old])
    next.boot()
    next.complete(old, status: 503)
    XCTAssertEqual(next.entry("a")?.attempts, 2)
    let retry = try XCTUnwrap(next.transport.live.first)
    XCTAssertEqual(ChunkedEngine.parseRequestDescription(retry.taskDescription)?.incarnation,
                   next.entry("a")?.incarnation)
    XCTAssertEqual(next.map.meta(forKey: retry.key)?.incarnation, next.entry("a")?.incarnation)
    XCTAssertNil(next.entry("a")?.tasksCarryIncarnation, "the entry is still the one 11.3.0 created")
    XCTAssertNotNil(next.coordinator.taskWillBegin(key: retry.key, description: retry.taskDescription),
                    "the retry begins")
    XCTAssertEqual(next.entry("a")?.state, .running)
    next.complete(retry, status: 201, body: "RETRY")
    XCTAssertEqual(next.entry("a")?.state, .completed)
    XCTAssertEqual((next.sink.settled.last?["response"] as? [String: Any])?["body"] as? String, "RETRY")
    XCTAssertEqual(next.sink.settled.count, 1)
  }

  /// After the upgrade the 11.3.0 entry completes and is acked, and the id
  /// is used again. Another task from 11.3.0 then reports a 2xx. It has no
  /// incarnation, and the new use was created by this build, so it is from
  /// an earlier use.
  func testATaskFrom113ThatReportsAfterTheIdIsUsedAgainIsDropped() throws {
    let old = try entryAndTaskFrom113()
    let next = Harness(root: h.root)
    next.relaunch(daemonTasks: [old])
    next.boot()
    next.complete(old, status: 201)
    next.ack([try XCTUnwrap(next.sink.settled.last?["eventId"] as? String)])
    _ = try next.enqueue(next.dataRaw(id: "a", data: ["x": 2])).get()
    XCTAssertEqual(next.entry("a")?.tasksCarryIncarnation, true)
    let c = try XCTUnwrap(next.transport.live.first)
    let stray = FakeTask(key: "any:113113", description: Self.description113(id: "a", generation: 1, attempt: 1))
    let settled = next.sink.settled.count
    next.complete(stray, status: 201, body: "STALE-FROM-113")
    XCTAssertEqual(next.entry("a")?.state, .running)
    XCTAssertFalse(c.cancelled)
    XCTAssertEqual(next.sink.settled.count, settled)
    XCTAssertNil(next.coordinator.taskWillBegin(key: "any:113114",
                                                description: Self.description113(id: "a", generation: 1, attempt: 1)))
  }

  /// The 11.3.0 RequestRef, exactly as released.
  private struct RequestRef113: Decodable {
    let id: String
    let gen: Int
    let att: Int
  }

  /// The stored fields of the 11.3.0 QueueEntry, exactly as released, with
  /// its synthesized decoder.
  struct Entry113: Decodable {
    let id: String
    var key: String
    var varsJSON: String
    var url: String?
    var method: String
    var accept: [UploadOutcome.AcceptRule]
    var retry: RetryOverride?
    var wifiOnly: Bool?
    var bodyKind: QueueEntry.BodyKind
    var bodyPath: String?
    var bodyContentType: String?
    var forceContentType: Bool
    var bodyFingerprint: String
    var parts: [QueueEntry.Part]
    var incarnation: String
    var headers: [String: String]
    var headerGeneration: Int
    var state: QueueEntry.State
    var authParked: Bool
    var generation: Int
    var attempts: Int
    var transientHitsByRule: [String: Int]? = nil
    var bytesSent: Int64
    var totalBytes: Int64
    var expiresAt: Double
    var nextAttemptAt: Double?
    var settledEventId: String?
    var lastRequestId: String?
    var lastUrl: String?
    var lastPartIndex: Int?
    var legacy: Bool
    let createdAt: Double
    var updatedAt: Double
  }

  /// A downgrade to 11.3.0 reads what this build wrote.
  func testAnOlderBuildReadsTheNewDescriptionEntryAndKey() throws {
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = h.transport.live[0]
    let entry = try XCTUnwrap(h.entry("a"))
    let description = try XCTUnwrap(task.taskDescription)
    XCTAssertTrue(description.contains(entry.incarnation))
    let prefix = "rnbgu-req:"
    XCTAssertTrue(description.hasPrefix(prefix))
    let ref = try JSONDecoder().decode(RequestRef113.self, from: Data(description.dropFirst(prefix.count).utf8))
    XCTAssertEqual(ref.id, "a")
    XCTAssertEqual(ref.gen, 1)
    XCTAssertEqual(ref.att, 1)
    let saved = try Data(contentsOf: h.store.fileURL("a", QueueStore.entryName))
    let old = try JSONDecoder().decode(Entry113.self, from: saved)
    XCTAssertEqual(old.incarnation, entry.incarnation)
    XCTAssertEqual(old.attempts, 1)
    XCTAssertEqual(old.expiresAt, 8_640_000_000_000_000, "a time 11.3.0 never reaches")
    // The key adds no field: an 11.3.0 key of a part already had
    // `incarnation`, and 11.3.0 resolves a key with no partIndex as a
    // simple attempt whatever its incarnation.
    let reread = TaskMap(fileURL: h.map.fileURL)
    XCTAssertEqual(reread.meta(forKey: task.key)?.incarnation, entry.incarnation)
    XCTAssertNil(reread.meta(forKey: task.key)?.partIndex)
  }
}
