import XCTest
@testable import RNBGUCore

final class RetryClassifierTests: XCTestCase {
  private func classify(_ status: Int? = nil, body: String? = nil, error: NSError? = nil,
                        accept: [UploadOutcome.AcceptRule] = [], exempt: [Int] = [404],
                        transient: [UploadOutcome.TransientRule] = [], hits: [String: Int] = [:],
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
  private let bodyStoppedKey = "400|\"error\":\"Bad Request\""

  func testTransientRuleMatchesByStatusOrStatusAndBody() {
    let notFound = UploadOutcome.TransientRule(status: 404)
    let any400 = UploadOutcome.TransientRule(status: 400)
    XCTAssertEqual(classify(404, exempt: [], transient: [notFound]), .transient(rule: notFound))
    XCTAssertEqual(classify(400, body: #"{"status":400,"error":"Bad Request"}"#, transient: [bodyStopped]), .transient(rule: bodyStopped))
    XCTAssertEqual(classify(400, body: #"{"message":"doc.comment must not be blank"}"#, transient: [bodyStopped]), .terminalHttp,
                   "a different body falls through: not exempt, so terminal")
    XCTAssertEqual(classify(400, body: "x", transient: [bodyStopped, any400]), .transient(rule: any400), "only the second rule matches")
    XCTAssertEqual(classify(400, body: #"{"error":"Bad Request"}"#, transient: [any400, bodyStopped]), .transient(rule: any400),
                   "both match: the first rule in list order wins")
  }

  func testARuleExcusesMaxRetriesResponsesAndTheNextOneSettles() {
    let body = #"{"error":"Bad Request"}"#
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: [:]), .transient(rule: bodyStopped))
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: [bodyStoppedKey: 1]), .transient(rule: bodyStopped))
    XCTAssertEqual(classify(400, body: body, transient: [bodyStopped], hits: [bodyStoppedKey: 2]), .terminalHttp)
    let any400 = UploadOutcome.TransientRule(status: 400)
    XCTAssertEqual(classify(400, body: body, transient: [any400], hits: ["400": 1_000]), .transient(rule: any400), "no cap")
  }

  func testCountsFollowTheRuleIdentityNotItsPlaceInTheList() {
    let notFound = UploadOutcome.TransientRule(status: 404, maxRetries: 48)
    let stopped = UploadOutcome.TransientRule(status: 400, bodyIncludes: "X", maxRetries: 3)
    // 40 hits under [{404, 48}]. An update puts a 400 rule in front.
    let hits = ["404": 40]
    XCTAssertEqual(classify(400, body: "X", transient: [stopped, notFound], hits: hits), .transient(rule: stopped))
    XCTAssertEqual(classify(404, transient: [stopped, notFound], hits: hits), .transient(rule: notFound))
    XCTAssertEqual(classify(400, body: "X", transient: [notFound, stopped], hits: hits), .transient(rule: stopped), "reordered")
    XCTAssertEqual(classify(404, transient: [notFound, stopped], hits: hits), .transient(rule: notFound), "reordered")
    XCTAssertEqual(classify(400, body: "X", transient: [stopped, notFound], hits: ["0": 40]), .transient(rule: stopped),
                   "a list position is not a key")
  }

  func testAChangedCapAppliesToTheCountSoFar() {
    let hits = ["404": 2]
    XCTAssertEqual(classify(404, transient: [.init(status: 404, maxRetries: 2)], hits: hits), .terminalHttp)
    XCTAssertEqual(classify(404, transient: [.init(status: 404, maxRetries: 5)], hits: hits),
                   .transient(rule: .init(status: 404, maxRetries: 5)), "a raised cap retries again")
    XCTAssertEqual(classify(404, transient: [.init(status: 404, maxRetries: 1)], hits: hits), .terminalHttp, "a lowered cap settles")
    let other = UploadOutcome.TransientRule(status: 404, bodyIncludes: "gone", maxRetries: 2)
    XCTAssertEqual(classify(404, body: "gone", transient: [other], hits: hits), .transient(rule: other),
                   "a changed bodyIncludes is a new rule with its own count")
  }

  func testAMatchedRulePastItsCapIsTerminalEvenWhenExemptListsTheStatus() {
    let capped = UploadOutcome.TransientRule(status: 404, maxRetries: 1)
    XCTAssertEqual(classify(404, exempt: [404], transient: [capped], hits: [:]), .transient(rule: capped))
    XCTAssertEqual(classify(404, exempt: [404], transient: [capped], hits: ["404": 1]), .terminalHttp)
    XCTAssertEqual(classify(404, exempt: [404], hits: ["404": 9]), .transient(rule: nil), "exempt alone is uncounted")
  }

  func testTheStandardTableDecidesBeforeAnyRule() {
    XCTAssertEqual(classify(429, transient: [.init(status: 429, maxRetries: 1)], hits: ["429": 5]), .transient(rule: nil))
    XCTAssertEqual(classify(401, transient: [.init(status: 401)]), .auth)
    XCTAssertEqual(classify(409, body: "dup", accept: [.init(status: 409)], transient: [.init(status: 409)]), .accepted)
    XCTAssertEqual(classify(400, body: #"{"error":"Bad Request"}"#, transient: [bodyStopped], now: 200, expiresAt: 100), .expired,
                   "a rule's retry past expiresAt still expires")
  }

  private struct IdentityVector: Decodable {
    struct Case: Decodable {
      let rule: UploadOutcome.TransientRule
      let identity: String
    }
    let identities: [Case]
  }

  /// The repo-root vector the Android tests read too.
  func testTransientRuleIdentityMatchesTheSharedVector() throws {
    let url = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
      .appendingPathComponent("test-vectors/transient-rule-identity.json")
    let vector = try JSONDecoder().decode(IdentityVector.self, from: Data(contentsOf: url))
    XCTAssertGreaterThanOrEqual(vector.identities.count, 8)
    for c in vector.identities { XCTAssertEqual(c.rule.identity, c.identity) }
    // Two rules share an identity only when status and bodyIncludes are equal.
    let groups = Dictionary(grouping: vector.identities, by: \.identity)
    for (identity, cases) in groups {
      XCTAssertEqual(Set(cases.map { "\($0.rule.status) \($0.rule.bodyIncludes ?? "<nil>")" }).count, 1, identity)
    }
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
