import XCTest
@testable import RNBGUCore

final class EnqueueParserTests: XCTestCase {
  private func raw(_ descriptor: [String: Any], vars: Any = ["a": 1]) -> [String: Any] {
    var d = descriptor
    if d["expiresAt"] == nil { d["expiresAt"] = 2_000_000_000_000.0 }
    return ["id": "id-1", "key": "k", "vars": vars, "descriptor": d]
  }

  func testDataBodyDefaultsToPost() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "data": ["b": 2, "a": 1]]))
    XCTAssertEqual(p.method, "POST")
    guard case .data(let json) = p.body else { return XCTFail("expected data") }
    XCTAssertEqual(json, #"{"a":1,"b":2}"#)
    XCTAssertEqual(p.varsJSON, #"{"a":1}"#)
  }

  func testBodilessDescriptor() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "method": "DELETE"]))
    guard case .none = p.body else { return XCTFail("expected none") }
    XCTAssertEqual(p.method, "DELETE")
    XCTAssertEqual(p.fingerprint, "none")
  }

  func testNullVarsAndNSNullFieldsAreAbsent() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "file": NSNull(), "form": NSNull()],
                                        vars: NSNull()))
    XCTAssertEqual(p.varsJSON, "null")
    guard case .none = p.body else { return XCTFail("NSNull must read as absent") }
  }

  func testDataNullIsAJSONNullBody() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "data": NSNull(), "file": NSNull()]))
    guard case .data(let json) = p.body else { return XCTFail("data: null is a body") }
    XCTAssertEqual(json, "null")
    XCTAssertEqual(p.fingerprint, "data:" + JSONText.sha256("null"))
    XCTAssertThrowsError(try EnqueueParser.parse(raw(["url": "https://a.test/x", "data": NSNull(),
                                                      "file": "/tmp/a"])), "two body kinds")
  }

  func testFormAndFile() throws {
    let form = try EnqueueParser.parse(raw(["url": "https://a.test/x", "form": [
      ["name": "request", "contentType": "application/json", "string": "{}"],
      ["name": "image", "contentType": "image/jpeg", "path": "/tmp/a.jpg"],
    ]]))
    guard case .form(let fields) = form.body else { return XCTFail("expected form") }
    XCTAssertEqual(fields.count, 2)
    XCTAssertEqual(fields[1].path, "/tmp/a.jpg")

    let file = try EnqueueParser.parse(raw(["url": "https://a.test/x", "file": "file:///tmp/a.bin"]))
    guard case .file(let path) = file.body else { return XCTFail("expected file") }
    XCTAssertEqual(path, "file:///tmp/a.bin")
    XCTAssertEqual(file.fingerprint, "file:file:///tmp/a.bin")
  }

  func testMissingUrlWithoutPartsThrows() {
    XCTAssertThrowsError(try EnqueueParser.parse(raw(["data": ["a": 1]])))
  }

  func testPartsNeedNoUrlAndValidateRanges() throws {
    let ok = try EnqueueParser.parse(raw(["file": "/tmp/f", "parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 10]],
    ]]))
    XCTAssertNil(ok.url)
    XCTAssertEqual(ok.parts.count, 1)
    XCTAssertThrowsError(try EnqueueParser.parse(raw(["file": "/tmp/f", "parts": [
      ["url": "https://s3.test/1", "range": ["start": 5, "end": 5]],
    ]])))
    XCTAssertThrowsError(try EnqueueParser.parse(raw(["parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 5]],
    ]])), "parts requires file")
  }

  func testTwoBodyKindsThrow() {
    XCTAssertThrowsError(try EnqueueParser.parse(raw(["url": "https://a.test", "data": [:], "file": "/tmp/a"])))
  }

  func testHeadersKeepStringsAndNumbersOnly() {
    let h = EnqueueParser.headers(["A": "x", "B": 3, "C": NSNull(), "D": ["nested": 1]])
    XCTAssertEqual(h, ["A": "x", "B": "3"])
  }

  func testDataFingerprintIgnoresKeyOrder() throws {
    let a = try EnqueueParser.parse(raw(["url": "https://a.test", "data": ["x": 1, "y": ["b": 1, "a": 2]]]))
    let b = try EnqueueParser.parse(raw(["url": "https://a.test", "data": ["y": ["a": 2, "b": 1], "x": 1]]))
    let c = try EnqueueParser.parse(raw(["url": "https://a.test", "data": ["x": 2]]))
    XCTAssertEqual(a.fingerprint, b.fingerprint)
    XCTAssertNotEqual(a.fingerprint, c.fingerprint)
  }

  func testFormFingerprintComparesFieldsAsSent() throws {
    let f1: [[String: Any]] = [["name": "a", "contentType": "t", "path": "/p1"]]
    let f2: [[String: Any]] = [["name": "a", "contentType": "t", "path": "/p2"]]
    let a = try EnqueueParser.parse(raw(["url": "https://a.test", "form": f1]))
    let b = try EnqueueParser.parse(raw(["url": "https://a.test", "form": f1]))
    let c = try EnqueueParser.parse(raw(["url": "https://a.test", "form": f2]))
    XCTAssertEqual(a.fingerprint, b.fingerprint)
    XCTAssertNotEqual(a.fingerprint, c.fingerprint)
  }

  func testPartsFingerprintIgnoresFilePath() throws {
    let parts: [[String: Any]] = [["url": "https://s3.test/1", "range": ["start": 0, "end": 4]]]
    let a = try EnqueueParser.parse(raw(["file": "/tmp/one", "parts": parts]))
    let b = try EnqueueParser.parse(raw(["file": "/tmp/two", "parts": parts]))
    let c = try EnqueueParser.parse(raw(["file": "/tmp/one", "parts": [
      ["url": "https://s3.test/other", "range": ["start": 0, "end": 4]]]]))
    XCTAssertEqual(a.fingerprint, b.fingerprint)
    XCTAssertNotEqual(a.fingerprint, c.fingerprint)
  }

  func testRetryOverrideParse() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test", "retry": ["terminalHttp": ["exempt": []]]]))
    XCTAssertEqual(p.retry, RetryOverride(exempt: []))
    XCTAssertEqual(RetryPolicy.resolve([nil, p.retry]).exempt, [])
    XCTAssertEqual(RetryPolicy.resolve([nil, p.retry]).baseMs, 1_000)
  }
}

final class QueueEntryTests: XCTestCase {
  private func parsed(_ descriptor: [String: Any], id: String = "e1", vars: Any = ["v": 1]) -> ParsedEnqueue {
    var d = descriptor
    if d["expiresAt"] == nil { d["expiresAt"] = 5_000.0 }
    return try! EnqueueParser.parse(["id": id, "key": "k", "vars": vars, "descriptor": d])
  }

  private let staged = StagedBody(kind: .parts, relativePath: "blob-1", contentType: nil,
                                  forceContentType: false, totalBytes: 20, adopted: false)

  private func chunked() -> QueueEntry {
    let p = parsed(["file": "/f", "parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 10]],
      ["url": "https://s3.test/2", "range": ["start": 10, "end": 20]],
    ]])
    return QueueEntry.created(from: p, staged: staged, headerGeneration: 3, paused: false, now: 100)
  }

  func testCreatedDefaults() {
    let e = chunked()
    XCTAssertEqual(e.state, .queued)
    XCTAssertEqual(e.generation, 1)
    XCTAssertEqual(e.headerGeneration, 3)
    XCTAssertEqual(e.totalBytes, 20)
    XCTAssertEqual(e.bodyPath, "blob-1")
    let paused = QueueEntry.created(from: parsed(["url": "https://a.test"]), staged: staged,
                                    headerGeneration: 0, paused: true, now: 1)
    XCTAssertEqual(paused.state, .paused)
  }

  func testResumedKeepsAcceptedAndGeneration() {
    var e = chunked().withPartAccepted(0)
    e.parts[1].rejections = 4
    e.attempts = 7
    e.generation = 2
    let incoming = parsed(["file": "/f", "headers": ["Authorization": "new"], "expiresAt": 9_000.0, "parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 10]],
      ["url": "https://s3.test/2", "headers": ["X": "y"], "range": ["start": 10, "end": 20]],
    ]], vars: ["v": 2])
    let reset = e.resumed(with: incoming, resetBudget: true, now: 200)
    XCTAssertTrue(reset.parts[0].accepted)
    XCTAssertEqual(reset.parts[1].rejections, 0)
    XCTAssertEqual(reset.parts[1].headers, ["X": "y"])
    XCTAssertEqual(reset.attempts, 0)
    XCTAssertEqual(reset.generation, 2)
    XCTAssertEqual(reset.expiresAt, 9_000)
    XCTAssertEqual(reset.headers, ["Authorization": "new"])
    XCTAssertEqual(reset.varsJSON, #"{"v":2}"#)
    XCTAssertEqual(reset.createdAt, e.createdAt)
    let kept = e.resumed(with: incoming, resetBudget: false, now: 200)
    XCTAssertEqual(kept.attempts, 7)
    XCTAssertEqual(kept.parts[1].rejections, 4)
  }

  func testReplacedBumpsGenerationAndIncarnation() {
    var e = chunked().withPartAccepted(0)
    e.state = .error
    e.settledEventId = "ev"
    e.attempts = 5
    let next = e.replaced(with: parsed(["url": "https://a.test/new", "data": ["z": 1]]),
                          staged: StagedBody(kind: .data, relativePath: "body-2", contentType: "application/json",
                                             forceContentType: false, totalBytes: 7, adopted: false),
                          now: 300)
    XCTAssertEqual(next.generation, e.generation + 1)
    XCTAssertNotEqual(next.incarnation, e.incarnation)
    XCTAssertEqual(next.state, .queued)
    XCTAssertEqual(next.attempts, 0)
    XCTAssertNil(next.settledEventId)
    XCTAssertEqual(next.bodyKind, .data)
    XCTAssertTrue(next.parts.isEmpty)
    XCTAssertEqual(next.createdAt, e.createdAt)
  }

  func testRowOmitsNilNextAttemptAtAndCarriesVars() {
    var e = chunked()
    var row = e.row(vars: ["v": 1])
    XCTAssertNil(row["nextAttemptAt"])
    XCTAssertEqual((row["vars"] as? [String: Int])?["v"], 1)
    XCTAssertEqual(row["state"] as? String, "queued")
    e.nextAttemptAt = 1234
    e.state = .awaitingAuth
    row = e.row(vars: NSNull())
    XCTAssertEqual(row["nextAttemptAt"] as? Double, 1234)
    XCTAssertEqual(row["state"] as? String, "awaiting-auth")
    XCTAssertTrue(row["vars"] is NSNull)
  }

  func testTilesExactly() {
    func part(_ s: Int64, _ e: Int64) -> QueueEntry.Part {
      QueueEntry.Part(url: "u", headers: [:], start: s, end: e, accepted: false, rejections: 0)
    }
    XCTAssertTrue(QueueEntry.tilesExactly([part(0, 5), part(5, 10)], size: 10))
    XCTAssertTrue(QueueEntry.tilesExactly([part(5, 10), part(0, 5)], size: 10), "order-independent")
    XCTAssertFalse(QueueEntry.tilesExactly([part(0, 4), part(5, 10)], size: 10), "gap")
    XCTAssertFalse(QueueEntry.tilesExactly([part(0, 6), part(5, 10)], size: 10), "overlap")
    XCTAssertFalse(QueueEntry.tilesExactly([part(0, 5), part(5, 11)], size: 10), "past end")
    XCTAssertFalse(QueueEntry.tilesExactly([part(0, 5)], size: 10), "short")
    XCTAssertFalse(QueueEntry.tilesExactly([], size: 0))
  }

  func testHeaderMergeIsCaseInsensitive() {
    let merged = HeaderMerge.merge(["authorization": "old", "A": "1"], ["Authorization": "new"])
    XCTAssertEqual(merged, ["Authorization": "new", "A": "1"])
    XCTAssertEqual(HeaderMerge.value("content-type", in: ["Content-Type": "x"]), "x")
  }

  func testEntryRoundTripsThroughJSON() throws {
    let e = chunked().withPartAccepted(1)
    let back = try JSONDecoder().decode(QueueEntry.self, from: try QueueStore.encode(e))
    XCTAssertEqual(back, e)
  }
}
