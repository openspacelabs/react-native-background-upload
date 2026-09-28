import XCTest
@testable import RNBGUCore

/// State transitions of simple (one-body) entries, over the fake transport.
final class CoordinatorSimpleTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
    h.boot()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  private func onlyTask(file: StaticString = #filePath, line: UInt = #line) -> FakeTask {
    XCTAssertEqual(h.transport.live.count, 1, file: file, line: line)
    return h.transport.live.last!
  }

  // MARK: - Create and complete

  func testEnqueueWritesAheadThenIssues() throws {
    XCTAssertEqual(try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "t1"])).get(), "a")
    XCTAssertEqual(h.sink.stateNames, ["queued", "running"])
    let task = onlyTask()
    XCTAssertEqual(task.request.httpMethod, "POST")
    XCTAssertEqual(task.header("Authorization"), "t1")
    XCTAssertEqual(task.header("Content-Type"), "application/json")
    XCTAssertNotNil(task.header("X-Request-Id"))
    XCTAssertEqual(try String(contentsOf: task.file!), #"{"x":1}"#)
    let stored = try XCTUnwrap(h.store.load("a"))
    XCTAssertEqual(stored.state, .running)
    XCTAssertEqual(stored.attempts, 1)
    XCTAssertEqual(stored.lastRequestId, task.header("X-Request-Id"))
    XCTAssertEqual(h.map.meta(forKey: task.key)?.purpose, .attempt)
  }

  func testExistingContentTypeInAnyCaseIsKept() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["content-type": "application/vnd.x+json"])).get()
    XCTAssertEqual(onlyTask().header("Content-Type"), "application/vnd.x+json")
  }

  func testCompletedJournalsThenEmitsAndAckForgets() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 201, body: #"{"ok":true}"#, headers: ["X-A": "1"])
    XCTAssertEqual(h.sink.stateNames.last, "completed")
    XCTAssertEqual(h.sink.attempts.last?["outcome"] as? String, "completed")
    let settled = try XCTUnwrap(h.sink.settled.last)
    XCTAssertEqual(settled["kind"] as? String, "completed")
    XCTAssertEqual(settled["deliveries"] as? Int, 1)
    XCTAssertEqual(settled["url"] as? String, "https://api.test/x")
    XCTAssertEqual(settled["method"] as? String, "POST")
    XCTAssertEqual(settled["attempts"] as? Int, 1)
    let response = try XCTUnwrap(settled["response"] as? [String: Any])
    XCTAssertEqual(response["status"] as? Int, 201)
    XCTAssertEqual(response["body"] as? String, #"{"ok":true}"#)
    let eventId = try XCTUnwrap(settled["eventId"] as? String)
    XCTAssertNotNil(h.journal.load(eventId), "journaled")
    XCTAssertEqual(h.row("a")?["state"] as? String, "completed", "row stays until the ack")

    h.ack([eventId, "unknown"])
    XCTAssertNil(h.row("a"))
    XCTAssertFalse(FileIO.exists(h.store.dir("a")), "row and bytes go after the ack")
    h.ack([eventId]) // idempotent
  }

  func testUnacknowledgedCountsDeliveries() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask())
    XCTAssertEqual(h.unacknowledged().first?["deliveries"] as? Int, 2)
    XCTAssertEqual(h.unacknowledged().first?["deliveries"] as? Int, 3)
  }

  func testSettleWithNoListenerIsJournaledAtZeroAndTheFirstDrainReturnsOne() throws {
    h.sink.listening = false // headless: no JS listener yet
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask())
    XCTAssertTrue(h.sink.settled.isEmpty, "not emitted live")
    let eventId = try XCTUnwrap(h.journal.unacknowledged().first?.eventId)
    XCTAssertEqual(h.journal.load(eventId)?.deliveries, 0)
    // Rule 7 with no listener: nothing emitted, nothing counted.
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertEqual(h.journal.load(eventId)?.deliveries, 0)
    XCTAssertEqual(h.unacknowledged().first?["deliveries"] as? Int, 1, "the first delivery")
    // The drain marks the listener: the next settle goes out live at 1.
    _ = try h.enqueue(h.dataRaw(id: "b")).get()
    h.complete(onlyTask())
    XCTAssertEqual(h.sink.settled.last?["id"] as? String, "b")
    XCTAssertEqual(h.sink.settled.last?["deliveries"] as? Int, 1)
  }

  func testFailedJournalWriteWithNoListenerKeepsZeroForTheDrain() throws {
    h.sink.listening = false
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    setReadOnly(h.journal.root, true)
    defer { setReadOnly(h.journal.root, false) }
    h.complete(onlyTask())
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertEqual(h.coordinator.pendingJournal.values.first?.deliveries, 0)
    XCTAssertEqual(h.unacknowledged().first?["deliveries"] as? Int, 1)
  }

  // MARK: - Same-id rules

  func testRule7CompletedUnackedReemitsWithoutRunning() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask())
    let first = h.sink.settled.count
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertEqual(h.sink.settled.count, first + 1)
    XCTAssertEqual(h.sink.settled.last?["deliveries"] as? Int, 2)
    XCTAssertEqual(h.sink.settled.last?["eventId"] as? String, h.sink.settled[first - 1]["eventId"] as? String)
    XCTAssertTrue(h.transport.live.isEmpty, "no second run")
  }

  func testRule3SameBodyWhileRunningReplacesHeadersAndVars() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    var raw = h.dataRaw(id: "a", headers: ["Authorization": "new"])
    raw["varsJson"] = #"{"n":2}"#
    _ = try h.enqueue(raw).get()
    XCTAssertEqual(h.transport.created.count, 1, "the in-flight task keeps its request")
    XCTAssertEqual(h.entry("a")?.headers["Authorization"], "new")
    XCTAssertEqual((h.row("a")?["vars"] as? [String: Int])?["n"], 2)
    XCTAssertEqual(h.entry("a")?.attempts, 1, "a live resume keeps the attempt ordinal")
  }

  func testADifferentUrlOrMethodIsADifferentBody() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    guard case .failure(let e) = h.enqueue(h.dataRaw(id: "a", url: "https://api.test/other")) else {
      return XCTFail("a new url on a running entry")
    }
    XCTAssertEqual(e.code, "E_RUNNING")
    h.complete(onlyTask(), status: 503) // now waiting, not running
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["method": "PUT"])).get()
    let entry = try XCTUnwrap(h.entry("a"))
    XCTAssertEqual(entry.generation, 2, "replaced, not resumed")
    XCTAssertEqual(entry.method, "PUT")
    XCTAssertEqual(onlyTask().request.httpMethod, "PUT")
  }

  func testSameBodyOnAWaitingRetryRetriesNowWithTheSameAttempt() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    XCTAssertNotNil(waiting.beginAt)
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "new"])).get()
    XCTAssertTrue(waiting.cancelled)
    let now = onlyTask()
    XCTAssertNil(now.beginAt, "no wait")
    XCTAssertEqual(now.header("X-Request-Id"), waiting.header("X-Request-Id"), "the waiting attempt never ran")
    XCTAssertEqual(now.header("Authorization"), "new")
    XCTAssertEqual(h.entry("a")?.attempts, 2)
    XCTAssertEqual(h.entry("a")?.state, .running)
    XCTAssertNil(h.entry("a")?.nextAttemptAt)
    h.deliverCancel(waiting)
    XCTAssertEqual(h.entry("a")?.state, .running, "the replaced task's cancel does nothing")
  }

  func testRule5DifferentBodyWhileRunningRejects() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    guard case .failure(let e) = h.enqueue(h.dataRaw(id: "a", data: ["x": 2])) else { return XCTFail() }
    XCTAssertEqual(e.code, "E_RUNNING")
    XCTAssertEqual(h.entry("a")?.bodyFingerprint, h.store.load("a")?.bodyFingerprint, "entry untouched")
  }

  func testRule4DifferentBodyWhileWaitingReplacesAndSupersedes() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 503)
    let delayed = onlyTask()
    XCTAssertNotNil(delayed.beginAt)
    let oldBody = try XCTUnwrap(h.entry("a")?.bodyPath)
    _ = try h.enqueue(h.dataRaw(id: "a", data: ["x": 2])).get()
    XCTAssertTrue(delayed.cancelled)
    XCTAssertEqual(h.map.meta(forKey: delayed.key)?.purpose, .superseded)
    let e = try XCTUnwrap(h.entry("a"))
    XCTAssertEqual(e.generation, 2)
    XCTAssertEqual(e.attempts, 1)
    XCTAssertFalse(FileIO.exists(h.store.fileURL("a", oldBody)), "the old body is deleted after the save")
    let fresh = onlyTask()
    XCTAssertEqual(try String(contentsOf: fresh.file!), #"{"x":2}"#)
    // The superseded task's cancel callback produces nothing.
    let attempts = h.sink.attempts.count
    h.deliverCancel(delayed)
    XCTAssertEqual(h.sink.attempts.count, attempts)
    XCTAssertEqual(h.entry("a")?.state, .running)
  }

  func testRule6CancelledUnackedReopensUnderAFreshGeneration() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.cancel("a")
    let cancelled = try XCTUnwrap(h.sink.settled.last)
    XCTAssertEqual(cancelled["cancelReason"] as? String, "user")
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertEqual(h.entry("a")?.generation, 2)
    XCTAssertEqual(h.entry("a")?.state, .running)
    h.ack([cancelled["eventId"] as! String])
    XCTAssertNotNil(h.row("a"), "the old generation's ack does not forget the reopened entry")
  }

  func testErrorEntryReopensOnSameBody() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 400, body: "bad")
    XCTAssertEqual(h.entry("a")?.state, .error)
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "http")
    XCTAssertEqual((error["response"] as? [String: Any])?["status"] as? Int, 400)
    h.ack([h.sink.settled.last!["eventId"] as! String])
    XCTAssertEqual(h.row("a")?["state"] as? String, "error", "an acked error keeps the row")
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertEqual(h.entry("a")?.state, .running)
    XCTAssertEqual(h.entry("a")?.generation, 2)
  }

  func testMissingFileRejectsFileMissingAndParseErrorRejectsInvalid() {
    guard case .failure(let missing) = h.enqueue(h.raw(id: "f", descriptor: [
      "url": "https://a.test", "file": "/does/not/exist"])) else { return XCTFail() }
    XCTAssertEqual(missing.code, "E_FILE_MISSING")
    XCTAssertNil(h.row("f"))
    guard case .failure(let bad) = h.enqueue(["id": "b", "key": "k", "descriptor": ["url": "https://a.test"]])
    else { return XCTFail() }
    XCTAssertEqual(bad.code, "E_INVALID", "no expiresAt")
  }

  // MARK: - Cancel

  func testCancelLiveThenSettled() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    h.cancel("a")
    XCTAssertTrue(task.cancelled)
    XCTAssertEqual(h.sink.stateNames.last, "cancelled")
    XCTAssertEqual(h.sink.settled.last?["kind"] as? String, "cancelled")
    let attempts = h.sink.attempts.count
    h.deliverCancel(task)
    XCTAssertEqual(h.sink.attempts.count, attempts, "the library's own cancel is not an attempt")
    XCTAssertEqual(h.sink.settled.count, 1, "one outcome")
    h.ack([h.sink.settled.last!["eventId"] as! String])
    XCTAssertNil(h.row("a"))
  }

  func testCancelSettledForgetsNowWithItsEvents() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 400)
    h.cancel("a")
    XCTAssertNil(h.row("a"))
    XCTAssertFalse(FileIO.exists(h.store.dir("a")))
    XCTAssertTrue(h.journal.unacknowledged().isEmpty)
    h.cancel("unknown") // no-op
  }

  func testCancelWhoseJournalWriteFailsRejectsStorageAndChangesNothing() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    let states = h.sink.states.count, progress = h.sink.progress.count, timers = h.timers.count
    setReadOnly(h.journal.root, true)
    defer { setReadOnly(h.journal.root, false) }
    let rejected = try XCTUnwrap(h.cancel("a"))
    XCTAssertEqual(rejected.code, "E_STORAGE")
    XCTAssertTrue(rejected.message.contains("'a'"), rejected.message)
    XCTAssertEqual(h.entry("a")?.state, .running)
    XCTAssertEqual(h.store.load("a")?.state, .running)
    XCTAssertNil(h.entry("a")?.settledEventId)
    XCTAssertFalse(task.cancelled, "the work keeps running")
    XCTAssertEqual(h.sink.states.count, states)
    XCTAssertEqual(h.sink.progress.count, progress)
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertTrue(h.coordinator.pendingJournal.isEmpty)
    XCTAssertEqual(h.timers.count, timers, "no journal retry is scheduled")
    XCTAssertTrue(h.unacknowledged().isEmpty)
  }

  func testCancelAgainAfterTheJournalIsWritableSettlesWithOneRecord() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    setReadOnly(h.journal.root, true)
    XCTAssertEqual(h.cancel("a")?.code, "E_STORAGE")
    setReadOnly(h.journal.root, false)
    XCTAssertNil(h.cancel("a"))
    XCTAssertTrue(task.cancelled)
    XCTAssertEqual(h.entry("a")?.state, .cancelled)
    XCTAssertEqual(h.sink.settled.count, 1)
    let records = h.journal.unacknowledged()
    XCTAssertEqual(records.count, 1, "one record")
    XCTAssertEqual(records.first?.kind, .cancelled)
    XCTAssertEqual(records.first?.eventId, h.sink.settled.first?["eventId"] as? String)
    XCTAssertTrue(h.coordinator.pendingJournal.isEmpty)
  }

  func testCancelSettledWhoseDirectoryCannotMoveRejectsStorageAndKeepsAll() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 400)
    setReadOnly(h.store.root, true)
    defer { setReadOnly(h.store.root, false) }
    XCTAssertEqual(h.cancel("a")?.code, "E_STORAGE")
    XCTAssertEqual(h.row("a")?["state"] as? String, "error")
    XCTAssertEqual(h.store.load("a")?.state, .error)
    XCTAssertEqual(h.journal.unacknowledged().count, 1)
  }

  func testCancelSettledWhoseEventsCannotBeDeletedPutsTheRowBack() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 400)
    setReadOnly(h.journal.root, true)
    XCTAssertEqual(h.cancel("a")?.code, "E_STORAGE")
    XCTAssertEqual(h.row("a")?["state"] as? String, "error")
    XCTAssertEqual(h.store.load("a")?.state, .error, "the directory is back")
    XCTAssertEqual(h.journal.unacknowledged().count, 1)
    setReadOnly(h.journal.root, false)
    XCTAssertNil(h.cancel("a"))
    XCTAssertNil(h.row("a"))
    XCTAssertFalse(FileIO.exists(h.store.dir("a")))
    XCTAssertTrue(h.journal.unacknowledged().isEmpty)
    XCTAssertTrue(h.store.setAsideDirectories().isEmpty, "nothing left aside")
  }

  // MARK: - Retry, backoff, expiry

  func testTransientSchedulesADelayedTask() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 503)
    XCTAssertEqual(h.sink.attempts.last?["outcome"] as? String, "error")
    XCTAssertEqual(h.sink.attempts.last?["httpCode"] as? Int, 503)
    let e = try XCTUnwrap(h.entry("a"))
    XCTAssertEqual(e.state, .queued)
    XCTAssertEqual(e.nextAttemptAt, h.clock + 1_000)
    XCTAssertEqual(h.row("a")?["nextAttemptAt"] as? Double, h.clock + 1_000)
    let retry = onlyTask()
    XCTAssertEqual(retry.beginAt, Date(timeIntervalSince1970: (h.clock + 1_000) / 1000))
    XCTAssertEqual(e.attempts, 2)
    h.complete(retry, status: 503)
    XCTAssertEqual(h.entry("a")?.nextAttemptAt, h.clock + 2_000, "backoff doubles")
    h.complete(onlyTask(), status: 200)
    XCTAssertEqual(h.sink.settled.last?["attempts"] as? Int, 3)
  }

  func testDelayedBeginMovesToRunningWithCurrentHeaders() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    h.complete(onlyTask(), status: 500)
    let delayed = onlyTask()
    h.updateHeaders(["authorization": "fresh"])
    let request = try XCTUnwrap(h.coordinator.taskWillBegin(key: delayed.key, description: delayed.taskDescription))
    XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "fresh")
    XCTAssertEqual(request.value(forHTTPHeaderField: "X-Request-Id"), delayed.header("X-Request-Id"))
    XCTAssertEqual(h.entry("a")?.state, .running)
    XCTAssertNil(h.entry("a")?.nextAttemptAt)
    XCTAssertEqual(h.map.meta(forKey: delayed.key)?.headerGeneration, 1)
  }

  func testDelayedBeginIsCancelledWhenTheEntryMovedOn() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 500)
    let delayed = onlyTask()
    h.pause()
    XCTAssertNil(h.coordinator.taskWillBegin(key: delayed.key, description: delayed.taskDescription))
  }

  func testFirstProgressOfADelayedTaskMovesItToRunning() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 500)
    let delayed = onlyTask()
    h.coordinator.taskProgress(key: delayed.key, description: delayed.taskDescription, sent: 3, expected: 7)
    h.drain()
    XCTAssertEqual(h.entry("a")?.state, .running)
    XCTAssertEqual(h.sink.progress.last?["bytesSent"] as? Int64, 3)
    XCTAssertEqual(h.sink.progress.last?["totalBytes"] as? Int64, 7)
  }

  func testSystemCancelIsNoAttemptAndARetry() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.deliverCancel(onlyTask())
    XCTAssertTrue(h.sink.attempts.isEmpty, "a cancel is not an attempt")
    XCTAssertTrue(h.sink.settled.isEmpty, "never a cancelled outcome")
    XCTAssertEqual(h.entry("a")?.state, .queued)
    XCTAssertNotNil(onlyTask().beginAt)
  }

  func testBackoffPastExpiresAtSettlesExpired() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 500])).get()
    h.complete(onlyTask(), status: 503)
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "expired")
    XCTAssertTrue(FileIO.exists(h.store.dir("a")), "bytes stay")
  }

  func testExpiryTimerSettlesAWaitingEntry() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 60_000])).get()
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    h.advance(60_200)
    XCTAssertEqual(h.entry("a")?.state, .error)
    XCTAssertTrue(waiting.cancelled)
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "expired")
  }

  func testExpiryLeavesARunningAttemptToItsOwnResult() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 60_000])).get()
    let task = onlyTask()
    h.advance(60_200)
    XCTAssertEqual(h.entry("a")?.state, .running, "not settled mid-flight")
    XCTAssertFalse(task.cancelled)
    h.complete(task, status: 400)
    let error = try XCTUnwrap(h.sink.settled.last?["error"] as? [String: Any])
    XCTAssertEqual(error["errorKind"] as? String, "http", "a real response keeps its kind")

    _ = try h.enqueue(h.dataRaw(id: "b", extra: ["expiresAt": h.clock + 60_000])).get()
    let second = onlyTask()
    h.advance(60_200)
    h.complete(second, status: 503)
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "expired",
                   "a transient result past expiresAt is expired")
  }

  func testAnExpiredEntryThatParksStillExpires() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 60_000])).get()
    let task = onlyTask()
    h.advance(60_200) // passes while running
    h.complete(task, status: 401)
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    h.advance(100)
    XCTAssertEqual(h.entry("a")?.state, .error)
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "expired")
  }

  func testPausedEntryCrossingExpiresAtSettlesAtResume() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 60_000])).get()
    _ = try h.enqueue(h.dataRaw(id: "b", extra: ["expiresAt": h.clock + 60_000])).get()
    h.complete(h.transport.live.first { $0.taskDescription?.contains("\"b\"") == true }!, status: 401)
    h.pause()
    h.advance(60_200)
    XCTAssertEqual(h.entry("a")?.state, .paused, "a pause does not expire")
    XCTAssertEqual(h.entry("b")?.state, .paused)
    XCTAssertTrue(h.sink.settled.isEmpty)
    h.resume()
    XCTAssertEqual(h.entry("a")?.state, .error)
    XCTAssertEqual(h.entry("b")?.state, .error, "a parked entry too")
    XCTAssertEqual(h.sink.settled.compactMap { ($0["error"] as? [String: Any])?["errorKind"] as? String },
                   ["expired", "expired"])
    XCTAssertTrue(h.transport.live.isEmpty, "nothing issues")
  }

  func testExpiredAtIssue() throws {
    h.pause()
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["expiresAt": h.clock + 10])).get()
    h.clock += 20
    h.resume()
    XCTAssertEqual(h.entry("a")?.state, .error)
  }

  func testFileMissingAtCompletionIsTerminal() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    try FileManager.default.removeItem(at: task.file!)
    h.complete(task, error: NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist))
    XCTAssertEqual((h.sink.settled.last?["error"] as? [String: Any])?["errorKind"] as? String, "file")
  }

  func testUnreadableFileIsTransient() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), error: NSError(domain: NSURLErrorDomain, code: NSURLErrorNoPermissionsToReadFile))
    XCTAssertEqual(h.entry("a")?.state, .queued)
    XCTAssertEqual(h.sink.attempts.last?["errorKind"] as? String, "file")
  }

  func testAcceptRuleWithBodyIncludes() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", extra: ["accept": [["status": 409, "bodyIncludes": "already completed"]]])).get()
    h.complete(onlyTask(), status: 409, body: "upload already completed")
    XCTAssertEqual(h.entry("a")?.state, .completed)
  }

  func testDefault404IsTransientAndExemptOverride() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 404)
    XCTAssertEqual(h.entry("a")?.state, .queued)
    h.coordinator.configure(["retry": ["terminalHttp": ["exempt": []]]])
    h.drain()
    _ = try h.enqueue(h.dataRaw(id: "b")).get()
    h.complete(h.transport.live.last!, status: 404)
    XCTAssertEqual(h.entry("b")?.state, .error)
  }

  // MARK: - Auth

  func testAuthParksAndUpdateHeadersResumes() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "expired"])).get()
    _ = try h.enqueue(h.dataRaw(id: "b", headers: ["Authorization": "expired"])).get()
    let tasks = h.transport.live
    let statesBefore = h.sink.states.count
    tasks.forEach { h.complete($0, status: 401) }
    XCTAssertEqual(h.sink.states.count, statesBefore + 2, "one state event per parked entry")
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    XCTAssertTrue(h.transport.live.isEmpty, "no retry while parked")
    h.updateHeaders(["authorization": "fresh"])
    XCTAssertEqual(h.transport.live.count, 2)
    XCTAssertTrue(h.transport.live.allSatisfy { $0.header("Authorization") == "fresh" })
    XCTAssertEqual(h.entry("a")?.headers, ["authorization": "fresh"], "the old spelling is replaced")
    XCTAssertEqual(h.entry("a")?.state, .running)
  }

  func testSameBodyOnAParkedEntryKeepsCountingAttempts() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "expired"])).get()
    h.complete(onlyTask(), status: 401)
    XCTAssertEqual(h.entry("a")?.attempts, 1)
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "fresh"])).get()
    XCTAssertEqual(h.entry("a")?.attempts, 2, "the same generation: no reset")
    XCTAssertEqual(ChunkedEngine.parseRequestDescription(onlyTask().taskDescription)?.attempt, 2)
  }

  func testAuthUnderAnOlderGenerationReissuesAtOnce() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    let first = onlyTask()
    h.updateHeaders(["Authorization": "new"])
    h.complete(first, status: 401)
    XCTAssertEqual(h.entry("a")?.state, .running, "not parked")
    let second = onlyTask()
    XCTAssertEqual(second.header("Authorization"), "new")
    XCTAssertNil(second.beginAt, "no backoff")
  }

  // MARK: - Pause, resume, wifi

  func testPauseProducesNoOutcomeAndResumeReissues() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    h.pause()
    XCTAssertTrue(task.cancelled)
    XCTAssertEqual(h.map.meta(forKey: task.key)?.purpose, .pause)
    XCTAssertEqual(h.entry("a")?.state, .paused)
    h.deliverCancel(task)
    XCTAssertTrue(h.sink.settled.isEmpty)
    XCTAssertEqual(h.sink.attempts.count, 0)
    // A new enqueue while paused is created paused and issues nothing.
    _ = try h.enqueue(h.dataRaw(id: "b")).get()
    XCTAssertEqual(h.entry("b")?.state, .paused)
    XCTAssertTrue(h.transport.live.isEmpty)
    h.resume()
    XCTAssertEqual(h.transport.live.count, 2)
    XCTAssertEqual(h.entry("a")?.attempts, 2)
  }

  func testPausedSettingSurvivesRelaunch() throws {
    h.pause()
    h.relaunch()
    h.boot()
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertEqual(h.entry("a")?.state, .paused)
  }

  func testPauseKeepsAuthParkingAcrossResume() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 403)
    h.pause()
    XCTAssertEqual(h.entry("a")?.state, .paused)
    h.resume()
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    XCTAssertTrue(h.transport.live.isEmpty)
  }

  func testAcceptedCompletionRacingAPauseSettles() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    h.pause()
    h.complete(task, status: 200) // the response landed before the cancel took effect
    XCTAssertEqual(h.entry("a")?.state, .completed)
  }

  func testWifiOnlyPicksTheSessionAndMovesAWaitingRetry() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    XCTAssertFalse(onlyTask().wifiOnly)
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    h.setWifiOnly(true)
    XCTAssertTrue(waiting.cancelled)
    XCTAssertTrue(onlyTask().wifiOnly)
    XCTAssertNotNil(onlyTask().beginAt, "the wait carries over")
    _ = try h.enqueue(h.dataRaw(id: "b")).get()
    XCTAssertTrue(h.transport.live.last!.wifiOnly)
  }

  func testWifiToggleKeepsTheWaitingAttemptOrdinalAndBackoff() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    XCTAssertEqual(h.entry("a")?.attempts, 2)
    h.setWifiOnly(true)
    h.setWifiOnly(false)
    XCTAssertEqual(h.entry("a")?.attempts, 2, "no HTTP attempt ran")
    XCTAssertEqual(h.store.load("a")?.attempts, 2)
    XCTAssertEqual(onlyTask().header("X-Request-Id"), waiting.header("X-Request-Id"))
    h.complete(onlyTask(), status: 503)
    XCTAssertEqual(h.entry("a")?.nextAttemptAt, h.clock + 2_000, "the exponent follows real attempts")
  }

  func testSupersededTaskThatBeginsLateIsCancelled() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    h.setWifiOnly(true)
    XCTAssertNil(h.coordinator.taskWillBegin(key: waiting.key, description: waiting.taskDescription),
                 "same ordinal, but replaced")
    XCTAssertEqual(h.entry("a")?.state, .queued)
  }

  // MARK: - Write-ahead failures

  func testFailedAttemptSaveCreatesNoTaskAndIssuesAfterABackoff() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let dir = h.store.dir("a")
    setReadOnly(dir, true)
    defer { setReadOnly(dir, false) }
    let created = h.transport.created.count
    h.complete(onlyTask(), status: 503)
    XCTAssertEqual(h.transport.created.count, created, "no task without the saved attempt")
    XCTAssertEqual(h.entry("a")?.state, .queued)
    XCTAssertEqual(h.entry("a")?.attempts, 1, "the index matches the disk")
    XCTAssertEqual(h.store.load("a")?.attempts, 1)
    setReadOnly(dir, false)
    h.advance(1_000)
    let retry = onlyTask()
    XCTAssertEqual(h.store.load("a")?.attempts, 2)
    XCTAssertEqual(h.store.load("a")?.lastRequestId, retry.header("X-Request-Id"))
  }

  // MARK: - Outcomes whose journal file is missing

  func testFailedJournalWriteStillEmitsAndTheAckForgets() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    setReadOnly(h.journal.root, true)
    defer { setReadOnly(h.journal.root, false) }
    h.complete(onlyTask())
    let settled = try XCTUnwrap(h.sink.settled.last)
    let eventId = try XCTUnwrap(settled["eventId"] as? String)
    XCTAssertNil(h.journal.load(eventId))
    XCTAssertEqual(settled["deliveries"] as? Int, 1)
    XCTAssertEqual(h.unacknowledged().first?["eventId"] as? String, eventId, "kept in memory")
    h.ack([eventId])
    XCTAssertNil(h.row("a"))
    XCTAssertFalse(FileIO.exists(h.store.dir("a")))
  }

  func testFailedJournalWriteIsRetriedUntilItLands() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    setReadOnly(h.journal.root, true)
    h.complete(onlyTask())
    let eventId = try XCTUnwrap(h.sink.settled.last?["eventId"] as? String)
    h.advance(Double(QueueCoordinator.journalRetryMs)) // still read-only: waits twice as long
    XCTAssertNil(h.journal.load(eventId))
    setReadOnly(h.journal.root, false)
    h.advance(Double(QueueCoordinator.journalRetryMs * 2))
    XCTAssertEqual(h.journal.load(eventId)?.deliveries, 1)
    XCTAssertTrue(h.coordinator.pendingJournal.isEmpty)
  }

  func testRelaunchForgetsSettledRowsWhoseOutcomeFileIsGone() throws {
    _ = try h.enqueue(h.dataRaw(id: "done")).get()
    h.complete(onlyTask())
    _ = try h.enqueue(h.dataRaw(id: "gone")).get()
    h.cancel("gone")
    _ = try h.enqueue(h.dataRaw(id: "bad")).get()
    h.complete(h.transport.live.last!, status: 400)
    // A crash between the ack's delete and the forget, for each row.
    h.journal.ack(h.sink.settled.compactMap { $0["eventId"] as? String })
    h.relaunch()
    h.boot()
    XCTAssertNil(h.row("done"))
    XCTAssertFalse(FileIO.exists(h.store.dir("done")), "its bytes go too")
    XCTAssertNil(h.row("gone"))
    XCTAssertEqual(h.row("bad")?["state"] as? String, "error", "an error row stays until cancel()")
  }

  func testAckOfAPrunedEventStillForgetsTheRow() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask())
    let eventId = try XCTUnwrap(h.sink.settled.last?["eventId"] as? String)
    h.journal.ack([eventId]) // the file is gone, as a prune would leave it
    h.ack([eventId])
    XCTAssertNil(h.row("a"))
  }

  func testInvalidInputRejectsInvalidAndStoresNothing() {
    for d: [String: Any] in [
      ["url": "ftp://a.test/x"],
      ["url": "https://a.test/x", "headers": ["Authorization": "t\r\nX: 1"]],
      ["url": "https://a.test/x", "method": "GET", "dataJson": "{}"],
    ] {
      guard case .failure(let e) = h.enqueue(h.raw(id: "bad", descriptor: d)) else { return XCTFail("\(d)") }
      XCTAssertEqual(e.code, "E_INVALID")
    }
    XCTAssertNil(h.row("bad"))
    XCTAssertFalse(FileIO.exists(h.store.dir("bad")))
  }

  func testUpdateHeadersRejectsALineBreakAndChangesNothing() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    var code: String?
    h.coordinator.updateHeaders(["Authorization": "new\r\n"], resolve: {}, reject: { c, _ in code = c })
    h.drain()
    XCTAssertEqual(code, "E_INVALID")
    XCTAssertEqual(h.entry("a")?.headers["Authorization"], "old")
    XCTAssertEqual(h.coordinator.settings.headerGeneration, 0)
  }

  func testNullValuedKeysSurviveOnTheBodyTheRowAndTheOutcome() throws {
    _ = try h.enqueue(h.raw(id: "a", vars: ["status": NSNull(), "n": 1], descriptor: [
      "url": "https://api.test/x", "dataJson": #"{"status":null}"#])).get()
    let task = onlyTask()
    XCTAssertEqual(try String(contentsOf: task.file!), #"{"status":null}"#)
    let vars = try XCTUnwrap(h.row("a")?["vars"] as? [String: Any])
    XCTAssertTrue(vars["status"] is NSNull, "the key is there, as JS null")
    h.complete(task)
    let settledVars = try XCTUnwrap(h.sink.settled.last?["vars"] as? [String: Any])
    XCTAssertTrue(settledVars["status"] is NSNull)
    XCTAssertEqual(settledVars["n"] as? Int, 1)
  }

  func testDataNullSendsTheJSONNullBody() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", data: NSNull())).get()
    let task = onlyTask()
    XCTAssertEqual(try String(contentsOf: task.file!), "null")
    XCTAssertEqual(task.header("Content-Type"), "application/json")
  }

  func testRowsCarryLiveBytesAndAFailureKeepsThem() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let task = onlyTask()
    h.coordinator.taskProgress(key: task.key, description: task.taskDescription, sent: 3, expected: 7)
    h.drain()
    XCTAssertEqual(h.row("a")?["bytesSent"] as? Int64, 3)
    XCTAssertEqual(h.store.load("a")?.bytesSent, 0, "progress is memory only")
    h.complete(task, status: 503)
    XCTAssertEqual(h.row("a")?["bytesSent"] as? Int64, 0, "a new attempt starts from 0")
    let retry = onlyTask()
    h.coordinator.taskProgress(key: retry.key, description: retry.taskDescription, sent: 5, expected: 7)
    h.drain()
    h.complete(retry, status: 400)
    XCTAssertEqual(h.sink.settled.last?["bytesSent"] as? Int64, 5, "the failed attempt's live bytes")
  }

  // MARK: - Rows  // MARK: - Rows

  func testGetRequestsRows() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    let row = try XCTUnwrap(h.row("a"))
    XCTAssertEqual(row["key"] as? String, "k")
    XCTAssertEqual(row["state"] as? String, "running")
    XCTAssertEqual(row["attempts"] as? Int, 1)
    XCTAssertNil(row["nextAttemptAt"])
    XCTAssertEqual((row["vars"] as? [String: Int])?["n"], 1)
    XCTAssertEqual(row["totalBytes"] as? Int64, 7)
  }
}
