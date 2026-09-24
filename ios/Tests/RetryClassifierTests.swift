import XCTest
@testable import RNBGUCore

final class RetryClassifierTests: XCTestCase {
  private func classify(_ status: Int? = nil, body: String? = nil, error: NSError? = nil,
                        accept: [UploadOutcome.AcceptRule] = [], exempt: [Int] = [404],
                        part: Bool = false, fileExists: Bool = true, now: Double = 0,
                        expiresAt: Double = 100) -> RetryClassifier.Class {
    var policy = RetryPolicy.defaults
    policy.exempt = exempt
    return RetryClassifier.classify(.init(
      statusCode: status, body: body, error: error, accept: accept, policy: policy,
      isChunkedPart: part, fileExists: fileExists, now: now, expiresAt: expiresAt))
  }

  func testTable() {
    XCTAssertEqual(classify(200), .accepted)
    XCTAssertEqual(classify(204), .accepted)
    XCTAssertEqual(classify(409, body: "upload already completed",
                            accept: [.init(status: 409, bodyIncludes: "already completed")]), .accepted)
    XCTAssertEqual(classify(409, body: "conflict", accept: [.init(status: 409, bodyIncludes: "already completed")]),
                   .terminalHttp)
    XCTAssertEqual(classify(401), .auth)
    XCTAssertEqual(classify(403), .auth)
    XCTAssertEqual(classify(408), .transient)
    XCTAssertEqual(classify(429), .transient)
    XCTAssertEqual(classify(500), .transient)
    XCTAssertEqual(classify(503), .transient)
    XCTAssertEqual(classify(404), .transient, "404 is exempt by default")
    XCTAssertEqual(classify(404, exempt: [], part: true), .terminalHttp, "the chunked part-404 case")
    XCTAssertEqual(classify(400), .terminalHttp)
    XCTAssertEqual(classify(422), .terminalHttp)
    XCTAssertEqual(classify(304), .terminalHttp)
  }

  func testErrors() {
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)),
                   .transient)
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)), .transient)
    let fileError = NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist)
    XCTAssertEqual(classify(error: fileError, fileExists: true), .fileUnreadable)
    XCTAssertEqual(classify(error: fileError, fileExists: false), .fileMissing)
    XCTAssertEqual(classify(error: NSError(domain: "Other", code: 1)), .transient)
  }

  func testExpiredWinsOverEverythingButAccepted() {
    XCTAssertEqual(classify(200, now: 100, expiresAt: 100), .accepted)
    XCTAssertEqual(classify(503, now: 100, expiresAt: 100), .expired)
    XCTAssertEqual(classify(401, now: 101, expiresAt: 100), .expired)
    XCTAssertEqual(classify(400, now: 101, expiresAt: 100), .expired)
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut),
                            now: 200, expiresAt: 100), .expired)
  }

  func testBackoff() {
    let p = RetryPolicy.defaults
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 1, policy: p, random: { 0.5 }), 1_000)
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 2, policy: p, random: { 0.5 }), 2_000)
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 3, policy: p, random: { 0.5 }), 4_000)
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 40, policy: p, random: { 0.5 }), 7_200_000, "capped at max")
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 1, policy: p, random: { 0 }), 800, "jitter low bound")
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 1, policy: p, random: { 1 }), 1_200, "jitter high bound")
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 0, policy: p, random: { 0.5 }), 1_000, "attempt < 1 clamps")
    var noJitter = p
    noJitter.jitter = 0
    XCTAssertEqual(RetryClassifier.backoffMs(attempt: 1000, policy: noJitter, random: { 0.9 }), 7_200_000)
  }

  func testErrorKind() {
    XCTAssertEqual(RetryClassifier.errorKind(for: NSError(domain: NSCocoaErrorDomain, code: NSFileNoSuchFileError)), "file")
    XCTAssertEqual(RetryClassifier.errorKind(for: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)), "network")
    XCTAssertEqual(RetryClassifier.errorKind(for: NSError(domain: "X", code: 1)), "unknown")
  }

  func testPolicyResolveLayers() {
    let resolved = RetryPolicy.resolve([RetryOverride(baseMs: 10, exempt: [404, 409]), RetryOverride(baseMs: 20)])
    XCTAssertEqual(resolved, RetryPolicy(baseMs: 20, maxMs: 7_200_000, jitter: 0.2, exempt: [404, 409]))
  }
}
