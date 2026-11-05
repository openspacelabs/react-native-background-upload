import XCTest
@testable import RNBGUCore

final class RetryClassifierTests: XCTestCase {
  private func classify(_ status: Int? = nil, body: String? = nil, error: NSError? = nil,
                        accept: [UploadOutcome.AcceptRule] = [], exempt: [Int] = [404],
                        transient: [UploadOutcome.TransientRule] = [], hits: [Int] = [],
                        fileExists: Bool = true, now: Double = 0,
                        expiresAt: Double = 100) -> RetryClassifier.Class {
    var policy = RetryPolicy.defaults
    policy.exempt = exempt
    policy.transient = transient
    return RetryClassifier.classify(.init(
      statusCode: status, body: body, error: error, accept: accept, policy: policy,
      fileExists: fileExists, now: now, expiresAt: expiresAt, hits: hits))
  }

  private let bodyStopped = UploadOutcome.TransientRule(status: 400, bodyIncludes: "\"error\":\"Bad Request\"", maxRetries: 2)

  func testTransientRuleMatchesByStatusOrStatusAndBody() {
    XCTAssertEqual(classify(404, exempt: [], transient: [.init(status: 404)]), .transient(rule: 0))
    XCTAssertEqual(classify(400, body: #"{"status":400,"error":"Bad Request"}"#, transient: [bodyStopped]), .transient(rule: 0))
    XCTAssertEqual(classify(400, body: #"{"message":"doc.comment must not be blank"}"#, transient: [bodyStopped]), .terminalHttp,
                   "a different body falls through: not exempt, so terminal")
    XCTAssertEqual(classify(400, body: "x", transient: [bodyStopped, .init(status: 400)]), .transient(rule: 1), "only the second rule matches")
    XCTAssertEqual(classify(400, body: #"{"error":"Bad Request"}"#, transient: [.init(status: 400), bodyStopped]), .transient(rule: 0),
                   "both match: the first rule in list order wins")
  }

  func testARuleExcusesMaxRetriesResponsesAndTheNextOneSettles() {
    let body = #"{"error":"Bad Request"}"#
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: []), .transient(rule: 0))
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: [1]), .transient(rule: 0))
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: [2]), .terminalHttp)
    XCTAssertEqual(classify(400, body: body, transient: [.init(status: 400)], hits: [1_000]), .transient(rule: 0), "no cap")
  }

  func testAMatchedRulePastItsCapIsTerminalEvenWhenExemptListsTheStatus() {
    let capped = UploadOutcome.TransientRule(status: 404, maxRetries: 1)
    XCTAssertEqual(classify(404, exempt: [404], transient: [capped], hits: []), .transient(rule: 0))
    XCTAssertEqual(classify(404, exempt: [404], transient: [capped], hits: [1]), .terminalHttp)
    XCTAssertEqual(classify(404, exempt: [404], hits: [9]), .transient(rule: nil), "exempt alone is uncounted")
  }

  func testTheStandardTableDecidesBeforeAnyRule() {
    XCTAssertEqual(classify(429, transient: [.init(status: 429, maxRetries: 1)], hits: [5]), .transient(rule: nil))
    XCTAssertEqual(classify(401, transient: [.init(status: 401)]), .auth)
    XCTAssertEqual(classify(409, body: "dup", accept: [.init(status: 409)], transient: [.init(status: 409)]), .accepted)
    XCTAssertEqual(classify(400, body: #"{"error":"Bad Request"}"#, transient: [bodyStopped], now: 200, expiresAt: 100), .expired,
                   "a rule's retry past expiresAt still expires")
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
    XCTAssertEqual(classify(408), .transient(rule: nil))
    XCTAssertEqual(classify(429), .transient(rule: nil))
    XCTAssertEqual(classify(500), .transient(rule: nil))
    XCTAssertEqual(classify(503), .transient(rule: nil))
    XCTAssertEqual(classify(404), .transient(rule: nil), "404 is exempt by default")
    XCTAssertEqual(classify(404, exempt: []), .terminalHttp, "the chunked part-404 case")
    XCTAssertEqual(classify(400), .terminalHttp)
    XCTAssertEqual(classify(422), .terminalHttp)
    XCTAssertEqual(classify(304), .terminalHttp)
  }

  func testErrors() {
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)),
                   .transient(rule: nil))
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut)), .transient(rule: nil))
    let fileError = NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist)
    XCTAssertEqual(classify(error: fileError, fileExists: true), .transient(rule: nil), "unreadable now, maybe not later")
    XCTAssertEqual(classify(error: fileError, fileExists: false), .fileMissing)
    XCTAssertEqual(classify(error: NSError(domain: "Other", code: 1)), .transient(rule: nil))
  }

  func testPastExpiresAtOnlyATransientResultIsExpired() {
    XCTAssertEqual(classify(200, now: 100, expiresAt: 100), .accepted)
    XCTAssertEqual(classify(503, now: 100, expiresAt: 100), .expired)
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut),
                            now: 200, expiresAt: 100), .expired)
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist),
                            fileExists: true, now: 200, expiresAt: 100), .expired)
    XCTAssertEqual(classify(401, now: 101, expiresAt: 100), .auth, "a real response keeps its class")
    XCTAssertEqual(classify(400, now: 101, expiresAt: 100), .terminalHttp)
    XCTAssertEqual(classify(error: NSError(domain: NSURLErrorDomain, code: NSURLErrorFileDoesNotExist),
                            fileExists: false, now: 200, expiresAt: 100), .fileMissing)
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
