import XCTest
@testable import RNBGUCore

/// Tasks created while the app is not active: iOS defers them at its own
/// discretion, so the coordinator replaces the ones that have not started
/// when the app becomes active.
final class CoordinatorForegroundTests: XCTestCase {
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

  func testTaskCreatedInTheBackgroundIsReplacedWhenTheAppBecomesActive() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: background.key)?.foreground, false)

    h.becomeActive()
    XCTAssertTrue(background.cancelled)
    XCTAssertEqual(h.map.meta(forKey: background.key)?.purpose, .superseded)
    let replacement = try XCTUnwrap(h.transport.live.first)
    XCTAssertTrue(replacement !== background)
    XCTAssertEqual(h.map.meta(forKey: replacement.key)?.foreground, true)
    XCTAssertEqual(replacement.header("X-Request-Id"), background.header("X-Request-Id"), "the same attempt")
    XCTAssertEqual(h.entry("a")?.attempts, 1)
    XCTAssertEqual(h.entry("a")?.state, .running)

    h.deliverCancel(background)
    XCTAssertTrue(h.sink.attempts.isEmpty, "a replaced task's cancel is not an attempt")
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertEqual(h.entry("a")?.state, .running)

    h.complete(replacement)
    XCTAssertEqual(h.entry("a")?.state, .completed)
  }

  func testTaskCreatedWhileActiveIsKept() throws {
    h.becomeActive()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: task.key)?.foreground, true)
    h.becomeActive()
    XCTAssertFalse(task.cancelled)
    XCTAssertEqual(h.transport.created.count, 1)
  }

  func testTaskThatStartedSendingIsKept() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    task.bytesSent = 3
    h.becomeActive()
    XCTAssertFalse(task.cancelled, "a body in flight is never cut off")
    XCTAssertEqual(h.transport.created.count, 1)
  }

  func testTaskCreatedAfterResigningActiveCountsAsBackground() throws {
    h.becomeActive()
    h.resignActive()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: task.key)?.foreground, false)
    h.becomeActive()
    XCTAssertTrue(task.cancelled)
    XCTAssertEqual(h.transport.live.count, 1)
  }

  func testDelayedRetryKeepsWhatIsLeftOfItsBackoff() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(try XCTUnwrap(h.transport.live.first), status: 503)
    let waiting = try XCTUnwrap(h.transport.live.first)
    let beginAt = try XCTUnwrap(waiting.beginAt)
    let attempts = h.entry("a")?.attempts
    h.clock += 400

    h.becomeActive()
    XCTAssertTrue(waiting.cancelled)
    let replacement = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(replacement.beginAt, beginAt, "the same begin date")
    XCTAssertEqual(replacement.header("X-Request-Id"), waiting.header("X-Request-Id"))
    XCTAssertEqual(h.entry("a")?.attempts, attempts)
    XCTAssertEqual(h.entry("a")?.state, .queued)
    XCTAssertEqual(h.row("a")?["nextAttemptAt"] as? Double, h.clock + 600)
  }

  func testReplacedTaskThatLandsAnywaySettlesOnce() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    h.becomeActive()
    let replacement = try XCTUnwrap(h.transport.live.first)
    // The old task finished before its cancel took effect.
    h.complete(background)
    XCTAssertEqual(h.entry("a")?.state, .completed)
    XCTAssertEqual(h.sink.settled.count, 1)
    XCTAssertTrue(replacement.cancelled, "the replacement does not send it again")
  }

  func testChunkedPartsCreatedInTheBackgroundAreReplaced() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let old1 = try XCTUnwrap(partTask(1))
    let old2 = try XCTUnwrap(partTask(2))

    h.becomeActive()
    XCTAssertTrue(old1.cancelled)
    XCTAssertTrue(old2.cancelled)
    let new1 = try XCTUnwrap(partTask(1))
    let new2 = try XCTUnwrap(partTask(2))
    XCTAssertEqual(h.map.meta(forKey: new1.key)?.foreground, true)
    XCTAssertEqual(h.map.meta(forKey: new2.key)?.foreground, true)
    XCTAssertEqual(h.transport.created.count, 5, "an accepted part is not sent again")

    h.deliverCancel(old1)
    h.deliverCancel(old2)
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertEqual(h.entry("cap")?.state, .running)
    XCTAssertEqual(h.transport.live.count, 2, "the cancels start nothing")

    h.complete(new1)
    h.complete(new2)
    XCTAssertEqual(h.entry("cap")?.state, .completed)
  }

  func testReplacedPartThatLandsAnywayStopsItsReplacement() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    let old0 = try XCTUnwrap(partTask(0))
    h.becomeActive()
    let new0 = try XCTUnwrap(partTask(0))
    h.complete(old0)
    XCTAssertEqual(h.entry("cap")?.parts[0].accepted, true)
    XCTAssertTrue(new0.cancelled, "no second PUT of part 0")
  }

  func testDelayedPartRetryKeepsWhatIsLeftOfItsBackoff() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    h.complete(try XCTUnwrap(partTask(0)), status: 503)
    let waiting = try XCTUnwrap(partTask(0))
    let beginAt = try XCTUnwrap(waiting.beginAt)
    h.clock += 400
    h.becomeActive()
    XCTAssertTrue(waiting.cancelled)
    XCTAssertEqual(partTask(0)?.beginAt, beginAt)
  }

  func testPausedEntryIsLeftAlone() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", key: "notes")).get()
    h.pause(keys: ["notes"])
    let created = h.transport.created.count
    h.becomeActive()
    XCTAssertEqual(h.transport.created.count, created)
    XCTAssertEqual(h.entry("a")?.state, .paused)
  }

  func testRelaunchIntoTheForegroundReplacesTasksFromAnOlderBuild() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let old = try XCTUnwrap(h.transport.live.first)
    // A build before the tag wrote no `foreground`.
    var meta = try XCTUnwrap(h.map.meta(forKey: old.key))
    meta.foreground = nil
    h.map.set(meta, forKey: old.key)
    let survivor = FakeTask(key: old.key, description: old.taskDescription, request: old.request)

    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: [survivor])
    // didBecomeActive can come before the reconcile: nothing is replaced
    // until the daemon's tasks are matched.
    fresh.coordinator.appBecameActive()
    fresh.drain()
    XCTAssertFalse(survivor.cancelled)
    fresh.boot()
    XCTAssertTrue(survivor.cancelled, "adopted, then replaced")
    let replacement = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.map.meta(forKey: replacement.key)?.foreground, true)
    XCTAssertEqual(replacement.header("X-Request-Id"), old.header("X-Request-Id"))
  }
}
