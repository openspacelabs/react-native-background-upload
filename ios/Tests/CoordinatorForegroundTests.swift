import XCTest
@testable import RNBGUCore

/// Tasks created while the app is not active: iOS defers them at its own
/// discretion, so the coordinator replaces the ones that have not started
/// when the app becomes active.
final class CoordinatorForegroundTests: XCTestCase {
  private var h: Harness!

  /// A backoff long enough that what is left of it outlasts replaceDelayMs.
  private let slowRetry: [String: Any] = ["retry": ["backoff": ["baseMs": 60_000]]]

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

  /// The app becomes active, and the replace delay passes.
  private func activate() {
    h.becomeActive()
    h.advance(Double(QueueCoordinator.replaceDelayMs))
  }

  // MARK: - Simple requests

  func testTaskCreatedInTheBackgroundIsReplacedWhenTheAppBecomesActive() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: background.key)?.foreground, false)

    activate()
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

  func testNothingIsReplacedBeforeTheDelay() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    h.becomeActive()
    h.advance(Double(QueueCoordinator.replaceDelayMs - 1))
    XCTAssertFalse(task.cancelled)
  }

  func testACompletionThatLandsDuringTheDelayIsNotSentAgain() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    h.becomeActive()
    // The daemon sent the body while the app was suspended. Its completion
    // reaches the process after didBecomeActive.
    h.complete(task)
    h.advance(Double(QueueCoordinator.replaceDelayMs))
    XCTAssertEqual(h.entry("a")?.state, .completed)
    XCTAssertEqual(h.transport.created.count, 1)
  }

  func testAReturnBeforeTheDelayWaitsTheFullDelayAgain() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    h.becomeActive()
    h.advance(500)
    h.resignActive()
    h.becomeActive()
    h.advance(Double(QueueCoordinator.replaceDelayMs - 1))
    XCTAssertFalse(task.cancelled, "the first activation's timer does not run")
    h.advance(1)
    XCTAssertTrue(task.cancelled)
  }

  func testTaskCreatedWhileActiveIsKept() throws {
    h.becomeActive()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: task.key)?.foreground, true)
    activate()
    XCTAssertFalse(task.cancelled)
    XCTAssertEqual(h.transport.created.count, 1)
  }

  func testTaskThatReportedBytesIsKept() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    task.bytesSent = 3
    activate()
    XCTAssertFalse(task.cancelled)
    XCTAssertEqual(h.transport.created.count, 1)
  }

  func testTaskWithAResponseIsKept() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    task.hasResponse = true
    activate()
    XCTAssertFalse(task.cancelled, "the server answered: its completion is coming")
  }

  func testEmptyBodyIsNeverReplaced() throws {
    _ = try h.enqueue(h.raw(id: "get", descriptor: ["url": "https://api.test/ping", "method": "GET"])).get()
    let task = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.entry("get")?.totalBytes, 0)
    activate()
    XCTAssertFalse(task.cancelled, "0 bytes sent cannot tell whether it went")
  }

  func testTaskCreatedAfterResigningActiveCountsAsBackground() throws {
    h.becomeActive()
    h.resignActive()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(h.map.meta(forKey: task.key)?.foreground, false)
    activate()
    XCTAssertTrue(task.cancelled)
    XCTAssertEqual(h.transport.live.count, 1)
  }

  func testDelayedRetryKeepsWhatIsLeftOfItsBackoff() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: slowRetry)).get()
    h.complete(try XCTUnwrap(h.transport.live.first), status: 503)
    let waiting = try XCTUnwrap(h.transport.live.first)
    let beginAt = try XCTUnwrap(waiting.beginAt)
    let attempts = h.entry("a")?.attempts

    activate()
    XCTAssertTrue(waiting.cancelled)
    let replacement = try XCTUnwrap(h.transport.live.first)
    XCTAssertEqual(replacement.beginAt, beginAt, "the same begin date")
    XCTAssertEqual(replacement.header("X-Request-Id"), waiting.header("X-Request-Id"))
    XCTAssertEqual(h.entry("a")?.attempts, attempts)
    XCTAssertEqual(h.entry("a")?.state, .queued)
    XCTAssertEqual(h.row("a")?["nextAttemptAt"] as? Double, h.clock + 58_000)
  }

  func testReplacedTaskThatLandsAnywaySettlesOnce() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    activate()
    let replacement = try XCTUnwrap(h.transport.live.first)
    // The old task finished before its cancel took effect.
    h.complete(background)
    XCTAssertEqual(h.entry("a")?.state, .completed)
    XCTAssertEqual(h.sink.settled.count, 1)
    XCTAssertTrue(replacement.cancelled, "the replacement does not send it again")
    // The replacement's cancel callback adds nothing.
    h.deliverCancel(replacement)
    XCTAssertEqual(h.sink.settled.count, 1)
  }

  func testReplacedTaskThatLandsAfterTheReplacementFailedStillCompletes() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    activate()
    h.complete(try XCTUnwrap(h.transport.live.first), status: 503)
    XCTAssertEqual(h.entry("a")?.attempts, 2, "the replacement's retry is a new attempt")
    let retry = try XCTUnwrap(h.transport.live.first)
    // The old attempt's body had landed: no third send.
    h.complete(background)
    XCTAssertEqual(h.entry("a")?.state, .completed)
    XCTAssertTrue(retry.cancelled)
  }

  func testReplacedTaskThatLandsWhileParkedForAuthCompletes() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    activate()
    h.complete(try XCTUnwrap(h.transport.live.first), status: 401)
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    // The old copy's body had landed: new headers must not send it again.
    h.complete(background)
    XCTAssertEqual(h.entry("a")?.state, .completed)
    XCTAssertEqual(h.sink.settled.count, 1)
  }

  func testReplacedTaskThatLandsAfterTheEntrySettledReportsNothing() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    activate()
    h.complete(try XCTUnwrap(h.transport.live.first))
    let attempts = h.sink.attempts.count
    h.complete(background)
    XCTAssertEqual(h.sink.attempts.count, attempts, "no second attempt event")
    XCTAssertEqual(h.sink.settled.count, 1)
  }

  func testReplacedTaskThatBeginsLateIsCancelled() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: slowRetry)).get()
    h.complete(try XCTUnwrap(h.transport.live.first), status: 503)
    let waiting = try XCTUnwrap(h.transport.live.first)
    activate()
    XCTAssertNil(h.coordinator.taskWillBegin(key: waiting.key, description: waiting.taskDescription))
    XCTAssertEqual(h.entry("a")?.state, .queued, "the replacement still waits")
  }

  /// Progress the old task reported before its replacement, still queued
  /// for the coordinator when the replacement runs.
  private func replaceWithProgressQueued(from task: FakeTask, sent: Int64) {
    h.coordinator.activity.isActive = true
    h.queue.suspend()
    h.coordinator.taskProgress(key: task.key, description: task.taskDescription, sent: sent, expected: 10)
    // Nothing else runs while the queue is suspended.
    h.coordinator.replaceBackgroundTasks()
    h.queue.resume()
    h.drain()
  }

  func testProgressFromAReplacedTaskIsIgnored() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let background = try XCTUnwrap(h.transport.live.first)
    replaceWithProgressQueued(from: background, sent: 5)
    XCTAssertTrue(background.cancelled)
    XCTAssertTrue(h.sink.progress.isEmpty)
    XCTAssertEqual(h.row("a")?["bytesSent"] as? Int64, 0)
  }

  // MARK: - Chunked parts

  func testChunkedPartsCreatedInTheBackgroundAreReplaced() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    h.complete(try XCTUnwrap(partTask(0)))
    let old1 = try XCTUnwrap(partTask(1))
    let old2 = try XCTUnwrap(partTask(2))

    activate()
    XCTAssertTrue(old1.cancelled)
    XCTAssertTrue(old2.cancelled)
    let new1 = try XCTUnwrap(partTask(1))
    let new2 = try XCTUnwrap(partTask(2))
    XCTAssertEqual(h.map.meta(forKey: new1.key)?.foreground, true)
    XCTAssertEqual(h.map.meta(forKey: new2.key)?.foreground, true)
    XCTAssertNotEqual(new1.header("X-Request-Id"), old1.header("X-Request-Id"), "a part replacement is a new attempt")
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
    activate()
    let new0 = try XCTUnwrap(partTask(0))
    h.complete(old0)
    XCTAssertEqual(h.entry("cap")?.parts[0].accepted, true)
    XCTAssertTrue(new0.cancelled, "no second PUT of part 0")
  }

  func testDelayedPartRetryKeepsWhatIsLeftOfItsBackoff() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3, extra: slowRetry)).get()
    h.complete(try XCTUnwrap(partTask(0)), status: 503)
    let waiting = try XCTUnwrap(partTask(0))
    let beginAt = try XCTUnwrap(waiting.beginAt)
    activate()
    XCTAssertTrue(waiting.cancelled)
    XCTAssertEqual(partTask(0)?.beginAt, beginAt)
  }

  func testAPartLeftOverByAFailedReplaceKeepsItsBackoff() throws {
    let retry: [String: Any] = ["retry": ["backoff": ["baseMs": 60_000, "maxMs": 600_000]]]
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3, extra: retry)).get()
    // Two rejections: part 1 waits 120 s, longer than the refill's backoff.
    h.complete(try XCTUnwrap(partTask(1)), status: 503)
    h.complete(try XCTUnwrap(partTask(1)), status: 503)
    let beginAt = try XCTUnwrap(partTask(1)?.beginAt)
    // The session cannot open part 0's file once, which stops the batch
    // before part 1.
    var failed = false
    h.transport.failUpload = { _ in
      defer { failed = true }
      return failed ? nil : NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)
    }
    activate()
    XCTAssertNil(partTask(1), "part 1 waits for the refill")
    h.advance(60_000)
    XCTAssertNotNil(partTask(0))
    XCTAssertEqual(partTask(1)?.beginAt, beginAt, "the refill keeps what is left of part 1's backoff")
  }

  func testProgressFromAReplacedPartIsIgnored() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 30, parts: 3)).get()
    let old0 = try XCTUnwrap(partTask(0))
    replaceWithProgressQueued(from: old0, sent: 9)
    XCTAssertTrue(old0.cancelled)
    XCTAssertTrue(h.sink.progress.isEmpty)
    XCTAssertEqual(h.row("cap")?["bytesSent"] as? Int64, 0)
  }

  // MARK: - Relaunch

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
    fresh.advance(Double(QueueCoordinator.replaceDelayMs))
    XCTAssertFalse(survivor.cancelled)
    fresh.boot()
    XCTAssertFalse(survivor.cancelled, "adopted; replaced only after the delay")
    fresh.advance(Double(QueueCoordinator.replaceDelayMs))
    XCTAssertTrue(survivor.cancelled)
    let replacement = try XCTUnwrap(fresh.transport.live.first)
    XCTAssertEqual(fresh.map.meta(forKey: replacement.key)?.foreground, true)
    XCTAssertEqual(replacement.header("X-Request-Id"), old.header("X-Request-Id"))
  }

  func testRelaunchNeverAdoptsAReplacedTask() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let old = try XCTUnwrap(h.transport.live.first)
    activate()
    let replacement = try XCTUnwrap(h.transport.live.first)
    // The process died before the daemon applied the cancel: both are live.
    let oldSurvivor = FakeTask(key: old.key, description: old.taskDescription, request: old.request)
    let newSurvivor = FakeTask(key: replacement.key, description: replacement.taskDescription,
                               request: replacement.request)
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: [oldSurvivor, newSurvivor])
    fresh.boot()
    XCTAssertTrue(oldSurvivor.cancelled)
    XCTAssertFalse(newSurvivor.cancelled)
    fresh.complete(newSurvivor)
    XCTAssertEqual(fresh.entry("a")?.state, .completed)
  }

  func testRelaunchNeverAdoptsAReplacedPart() throws {
    _ = try h.enqueue(h.chunkedRaw(id: "cap", size: 20, parts: 2)).get()
    let old0 = try XCTUnwrap(partTask(0))
    let old1 = try XCTUnwrap(partTask(1))
    activate()
    let new0 = try XCTUnwrap(partTask(0))
    let new1 = try XCTUnwrap(partTask(1))
    // Old tasks listed first, so a first-wins adoption would pick them.
    let survivors = [old0, old1, new0, new1].map {
      FakeTask(key: $0.key, description: $0.taskDescription, request: $0.request)
    }
    let fresh = Harness(root: h.root)
    fresh.relaunch(daemonTasks: survivors)
    fresh.boot()
    XCTAssertTrue(survivors[0].cancelled)
    XCTAssertTrue(survivors[1].cancelled)
    XCTAssertFalse(survivors[2].cancelled)
    XCTAssertFalse(survivors[3].cancelled)
    fresh.complete(survivors[2])
    fresh.complete(survivors[3])
    XCTAssertEqual(fresh.entry("cap")?.state, .completed)
  }
}
