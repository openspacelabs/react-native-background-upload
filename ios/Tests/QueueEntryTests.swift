import XCTest
@testable import RNBGUCore

final class EnqueueParserTests: XCTestCase {
  /// The bridge form: vars and data as JSON text. A `data` key in
  /// `descriptor` becomes `dataJson`.
  private func raw(_ descriptor: [String: Any], vars: Any = ["a": 1]) -> [String: Any] {
    var d = descriptor
    if d["expiresAt"] == nil { d["expiresAt"] = 2_000_000_000_000.0 }
    if let data = d.removeValue(forKey: "data") { d["dataJson"] = jsonText(data) }
    return ["id": "id-1", "key": "k", "varsJson": jsonText(vars), "descriptor": d]
  }

  private func invalid(_ raw: [String: Any], file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertThrowsError(try EnqueueParser.parse(raw), file: file, line: line) {
      XCTAssertTrue($0 is ParseError, file: file, line: line)
    }
  }

  func testDataBodyDefaultsToPost() throws {
    var r = raw(["url": "https://a.test/x"])
    var d = r["descriptor"] as! [String: Any]
    d["dataJson"] = #"{"b":2,"a":1}"#
    r["descriptor"] = d
    let p = try EnqueueParser.parse(r)
    XCTAssertEqual(p.method, "POST")
    guard case .data(let json) = p.body else { return XCTFail("expected data") }
    XCTAssertEqual(json, #"{"b":2,"a":1}"#, "the text JS built is the body")
    XCTAssertEqual(p.varsJSON, #"{"a":1}"#)
  }

  func testBodilessDescriptor() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "method": "DELETE"]))
    guard case .none = p.body else { return XCTFail("expected none") }
    XCTAssertEqual(p.method, "DELETE")
    XCTAssertTrue(p.fingerprint.hasPrefix("none"))
  }

  func testNullVarsAndNSNullFieldsAreAbsent() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "file": NSNull(), "form": NSNull(),
                                         "dataJson": NSNull()], vars: NSNull()))
    XCTAssertEqual(p.varsJSON, "null")
    guard case .none = p.body else { return XCTFail("NSNull must read as absent") }
  }

  func testNullValuedKeysSurviveAsText() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "data": ["status": NSNull()]],
                                        vars: ["a": NSNull()]))
    XCTAssertEqual(p.varsJSON, #"{"a":null}"#)
    guard case .data(let json) = p.body else { return XCTFail("expected data") }
    XCTAssertEqual(json, #"{"status":null}"#)
  }

  func testDataJsonNullIsAJSONNullBody() throws {
    let p = try EnqueueParser.parse(raw(["url": "https://a.test/x", "data": NSNull(), "file": NSNull()]))
    guard case .data(let json) = p.body else { return XCTFail("dataJson \"null\" is a body") }
    XCTAssertEqual(json, "null")
    invalid(raw(["url": "https://a.test/x", "data": NSNull(), "file": "/tmp/a"]))
  }

  func testObjectVarsOrDataAndBadJSONTextAreInvalid() {
    invalid(["id": "i", "key": "k", "vars": ["a": 1], "descriptor": ["url": "https://a.test", "expiresAt": 1]])
    var withData = raw(["url": "https://a.test"])
    var d = withData["descriptor"] as! [String: Any]
    d["data"] = ["x": 1]
    withData["descriptor"] = d
    invalid(withData)
    var badVars = raw(["url": "https://a.test"])
    badVars["varsJson"] = "{nope"
    invalid(badVars)
    d = raw(["url": "https://a.test"])["descriptor"] as! [String: Any]
    d["dataJson"] = "{nope"
    invalid(["id": "i", "key": "k", "varsJson": "null", "descriptor": d])
  }

  func testGetWithABodyIsInvalid() throws {
    invalid(raw(["url": "https://a.test", "method": "GET", "data": ["a": 1]]))
    invalid(raw(["url": "https://a.test", "method": "get", "file": "/tmp/a"]))
    XCTAssertNoThrow(try EnqueueParser.parse(raw(["url": "https://a.test", "method": "GET"])))
    XCTAssertNoThrow(try EnqueueParser.parse(raw(["url": "https://a.test", "method": "DELETE", "data": ["a": 1]])))
  }

  func testSchemeMustBeHttpOrHttps() throws {
    invalid(raw(["url": "ftp://a.test/x"]))
    invalid(raw(["url": "file:///tmp/x"]))
    invalid(raw(["url": "https:///nohost"]))
    invalid(raw(["file": "/tmp/f", "parts": [["url": "javascript://a.test/1", "range": ["start": 0, "end": 1]]]]))
    XCTAssertNoThrow(try EnqueueParser.parse(raw(["url": "HTTP://a.test/x"])))
  }

  func testHeaderNamesAndValuesAreValidatedWithoutEchoingTheValue() {
    invalid(raw(["url": "https://a.test", "headers": ["Bad Name": "x"]]))
    invalid(raw(["url": "https://a.test", "headers": ["": "x"]]))
    invalid(raw(["url": "https://a.test", "headers": ["X-A": "secret\r\nX-Injected: 1"]]))
    invalid(raw(["url": "https://a.test", "headers": ["X-A": "a\nb"]]))
    invalid(raw(["file": "/tmp/f", "parts": [["url": "https://s3.test/1", "headers": ["X": "a\rb"],
                                               "range": ["start": 0, "end": 1]]]]))
    invalid(raw(["url": "https://a.test", "form": [["name": "a", "contentType": "t\r\nX: 1", "string": "s"]]]))
    XCTAssertThrowsError(try EnqueueParser.headers(["Authorization": "Bearer tok\r\n"])) {
      XCTAssertFalse($0.localizedDescription.contains("tok"), "the value never reaches the message")
      XCTAssertTrue($0.localizedDescription.contains("Authorization"))
    }
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
    XCTAssertTrue(file.fingerprint.hasPrefix("file:file:///tmp/a.bin"))
  }

  func testMissingUrlWithoutPartsThrows() {
    invalid(raw(["data": ["a": 1]]))
  }

  func testPartsNeedNoUrlAndValidateRanges() throws {
    let ok = try EnqueueParser.parse(raw(["file": "/tmp/f", "parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 10]],
    ]]))
    XCTAssertNil(ok.url)
    XCTAssertEqual(ok.parts.count, 1)
    invalid(raw(["file": "/tmp/f", "parts": [
      ["url": "https://s3.test/1", "range": ["start": 5, "end": 5]],
    ]]))
    invalid(raw(["parts": [
      ["url": "https://s3.test/1", "range": ["start": 0, "end": 5]],
    ]]))
  }

  func testTwoBodyKindsThrow() {
    invalid(raw(["url": "https://a.test", "data": [:], "file": "/tmp/a"]))
  }

  func testHeadersKeepStringsAndNumbersOnly() throws {
    let h = try EnqueueParser.headers(["A": "x", "B": 3, "C": NSNull(), "D": ["nested": 1]])
    XCTAssertEqual(h, ["A": "x", "B": "3"])
  }

  func testDataFingerprintIgnoresKeyOrder() throws {
    func withText(_ text: String) -> [String: Any] {
      var r = raw(["url": "https://a.test"])
      var d = r["descriptor"] as! [String: Any]
      d["dataJson"] = text
      r["descriptor"] = d
      return r
    }
    let a = try EnqueueParser.parse(withText(#"{"x":1,"y":{"b":1,"a":2}}"#))
    let b = try EnqueueParser.parse(withText(#"{"y":{"a":2,"b":1},"x":1}"#))
    let c = try EnqueueParser.parse(withText(#"{"x":2}"#))
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
    if let data = d.removeValue(forKey: "data") { d["dataJson"] = jsonText(data) }
    return try! EnqueueParser.parse(["id": id, "key": "k", "varsJson": jsonText(vars), "descriptor": d])
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
