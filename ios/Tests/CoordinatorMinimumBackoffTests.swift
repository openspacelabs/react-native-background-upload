import XCTest
@testable import RNBGUCore

/// No retry starts sooner than RetryClassifier.minBackoffMs (1 s) after the
/// failure, whatever `retry.backoff` says. A baseMs or maxMs of 0, NaN or
/// an infinity must neither remove the wait nor trap.
final class CoordinatorMinimumBackoffTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
    h.boot()
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: h.root)
  }

  /// The backoff overrides each test runs with. A request's own `retry`
  /// is saved in its entry, and JSON has no NaN or infinity: such a
  /// request cannot be saved. So those values come from configure(), which
  /// applies them in memory even when settings.json cannot hold them.
  private let policies: [(name: String, backoff: [String: Any], configure: Bool)] = [
    ("zero", ["baseMs": 0, "maxMs": 0], false),
    ("negative", ["baseMs": -5, "maxMs": -5], false),
    ("zero base", ["baseMs": 0], true),
    ("NaN base", ["baseMs": Double.nan], true),
    ("NaN max", ["baseMs": 0, "maxMs": Double.nan], true),
    ("infinite", ["baseMs": Double.infinity, "maxMs": Double.infinity], true),
  ]

  /// Applies the policy and returns the descriptor fields that carry it.
  private func apply(_ backoff: [String: Any], configure: Bool) -> [String: Any] {
    let retry: [String: Any] = ["retry": ["backoff": backoff]]
    h.coordinator.configure(configure ? retry : [:])
    h.drain()
    return configure ? [:] : retry
  }

  private func onlyTask(file: StaticString = #filePath, line: UInt = #line) throws -> FakeTask {
    XCTAssertEqual(h.transport.live.count, 1, file: file, line: line)
    return try XCTUnwrap(h.transport.live.first, file: file, line: line)
  }

  /// Nothing is sent for 999 ms, then the retry is sent (or begins).
  private func assertNoTaskForOneSecond(_ name: String, expected: Int = 1,
                                        file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertTrue(h.transport.live.isEmpty, "\(name): no task at once", file: file, line: line)
    h.advance(999)
    XCTAssertTrue(h.transport.live.isEmpty, "\(name): no task before 1 s", file: file, line: line)
    h.advance(1)
    XCTAssertEqual(h.transport.live.count, expected, "\(name): the retry after 1 s", file: file, line: line)
  }

  func testTheBackoffIsNeverUnderOneSecondAndNeverTraps() {
    let values: [Double] = [0, -1, 0.5, .nan, .infinity, -.infinity]
    for base in values {
      for max in values {
        for jitter in [0, 0.2, 1, 5, .nan] {
          for r in [0.0, 0.5, 0.999] {
            var p = RetryPolicy.defaults
            p.baseMs = base
            p.maxMs = max
            p.jitter = jitter
            let ms = RetryClassifier.backoffMs(attempt: 3, policy: p, random: { r })
            XCTAssertGreaterThanOrEqual(ms, 1_000, "base \(base), max \(max), jitter \(jitter), random \(r)")
          }
        }
      }
    }
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 3, policy: .defaults, random: { 0.5 }), 4_000,
                   "a normal policy is unchanged")
  }

  /// scheduleRetry: a transient result of a simple request.
  func testASimpleRetryWaitsAtLeastOneSecond() throws {
    for (name, backoff, configure) in policies {
      let extra = apply(backoff, configure: configure)
      _ = try h.enqueue(h.dataRaw(id: name, extra: extra)).get()
      h.complete(try onlyTask(), status: 503)
      XCTAssertEqual(h.entry(name)?.state, .queued, name)
      let begin = try XCTUnwrap(try onlyTask().beginAt?.timeIntervalSince1970, name) * 1000
      XCTAssertGreaterThanOrEqual(begin, h.clock + 1_000, name)
      XCTAssertEqual(h.entry(name)?.nextAttemptAt, begin, name)
      _ = h.cancel(name)
    }
  }

  /// scheduleRetry's floor: a delayed task that ended before its own begin
  /// date waits in memory, with no task, for at least one second.
  func testAFailureBeforeTheBeginDateWaitsAtLeastOneSecond() throws {
    for (name, backoff, configure) in policies {
      let extra = apply(backoff, configure: configure)
      _ = try h.enqueue(h.dataRaw(id: name, extra: extra)).get()
      h.complete(try onlyTask(), status: 503)
      h.complete(try onlyTask(), status: 503) // before its begin date
      XCTAssertEqual(h.entry(name)?.attempts, 2, name)
      assertNoTaskForOneSecond(name)
      _ = h.cancel(name)
    }
  }

  /// deferIssue: the attempt could not be saved. The issue waits at least
  /// one second before it tries again.
  func testAFailedAttemptSaveWaitsAtLeastOneSecond() throws {
    for (name, backoff, configure) in policies {
      let extra = apply(backoff, configure: configure)
      _ = try h.enqueue(h.dataRaw(id: name, extra: extra)).get()
      h.pause()
      let dir = h.store.dir(name)
      setReadOnly(dir, true)
      h.resume()
      setReadOnly(dir, false)
      XCTAssertEqual(h.store.load(name)?.attempts, 1, "\(name): the attempt was not saved")
      assertNoTaskForOneSecond(name)
      XCTAssertEqual(h.store.load(name)?.attempts, 2, name)
      _ = h.cancel(name)
    }
  }

  /// retryPart: a transient result of a chunked part.
  func testAPartRetryWaitsAtLeastOneSecond() throws {
    for (name, backoff, configure) in policies {
      let extra = apply(backoff, configure: configure)
      _ = try h.enqueue(h.chunkedRaw(id: name, parts: 1, extra: extra)).get()
      h.drainAll()
      h.complete(try onlyTask(), status: 503)
      XCTAssertEqual(h.entry(name)?.state, .running, name)
      let begin = try XCTUnwrap(try onlyTask().beginAt?.timeIntervalSince1970, name) * 1000
      XCTAssertGreaterThanOrEqual(begin, h.clock + 1_000, name)
      _ = h.cancel(name)
    }
  }

  /// refillLater: the part attempts could not be saved. The refill waits at
  /// least one second before it tries again.
  func testAFailedPartSaveWaitsAtLeastOneSecond() throws {
    for (name, backoff, configure) in policies {
      let extra = apply(backoff, configure: configure)
      _ = try h.enqueue(h.chunkedRaw(id: name, parts: 1, extra: extra)).get()
      h.drainAll()
      h.pause()
      let dir = h.store.dir(name)
      setReadOnly(dir, true)
      h.resume()
      h.drainAll()
      setReadOnly(dir, false)
      assertNoTaskForOneSecond(name)
      _ = h.cancel(name)
    }
  }
}
