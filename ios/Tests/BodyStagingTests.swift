import XCTest
@testable import RNBGUCore

final class BodyStagingTests: XCTestCase {
  private var root: URL!
  private var dir: URL!

  override func setUp() {
    root = makeTempDir()
    dir = root.appendingPathComponent("entry")
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
  }

  private func part(_ s: Int64, _ e: Int64) -> QueueEntry.Part {
    QueueEntry.Part(url: "https://s3.test", headers: [:], start: s, end: e, accepted: false, rejections: 0)
  }

  func testDataBodyIsCanonicalJSON() throws {
    let staged = try BodyStaging.stage(.data(json: #"{"a":1}"#), parts: [], into: dir, fallbackBlob: nil)
    XCTAssertEqual(staged.contentType, "application/json")
    XCTAssertFalse(staged.forceContentType)
    XCTAssertTrue(staged.relativePath.hasPrefix("body-"))
    XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent(staged.relativePath)), #"{"a":1}"#)
    XCTAssertEqual(staged.totalBytes, 7)
    XCTAssertFalse(FileIO.exists(dir.appendingPathComponent(staged.relativePath + ".tmp")))
  }

  func testBodilessIsZeroBytes() throws {
    let staged = try BodyStaging.stage(.none, parts: [], into: dir, fallbackBlob: nil)
    XCTAssertEqual(FileIO.size(dir.appendingPathComponent(staged.relativePath)), 0)
    XCTAssertNil(staged.contentType)
  }

  func testFormBodyParsesBack() throws {
    let photo = root.appendingPathComponent("photo.jpg")
    writeFile(photo, "JPEGBYTES")
    let staged = try BodyStaging.stage(.form([
      .init(name: "request", contentType: "application/json", string: #"{"id":"p1"}"#, path: nil, fileName: nil),
      .init(name: "image", contentType: "image/jpeg", string: nil, path: "file://" + photo.path, fileName: nil),
    ]), parts: [], into: dir, fallbackBlob: nil)
    XCTAssertTrue(staged.forceContentType)
    let ct = try XCTUnwrap(staged.contentType)
    XCTAssertTrue(ct.hasPrefix("multipart/form-data; boundary="))
    let boundary = String(ct.dropFirst("multipart/form-data; boundary=".count))
    let text = try String(contentsOf: dir.appendingPathComponent(staged.relativePath))
    let sections = text.components(separatedBy: "--\(boundary)")
    XCTAssertEqual(sections.count, 4) // preamble, two fields, closing
    XCTAssertTrue(sections[1].contains(#"Content-Disposition: form-data; name="request""#))
    XCTAssertTrue(sections[1].contains("\r\n\r\n{\"id\":\"p1\"}\r\n"))
    XCTAssertTrue(sections[2].contains(#"name="image"; filename="photo.jpg""#), "filename defaults to the last path component")
    XCTAssertTrue(sections[2].contains("Content-Type: image/jpeg\r\n\r\nJPEGBYTES\r\n"))
    XCTAssertEqual(sections[3], "--\r\n")
    XCTAssertEqual(staged.totalBytes, Int64(text.utf8.count))
  }

  func testMissingFormFileThrowsBeforeAnyWrite() {
    XCTAssertThrowsError(try BodyStaging.stage(.form([
      .init(name: "image", contentType: "image/jpeg", string: nil, path: "/nope/missing.jpg", fileName: nil),
    ]), parts: [], into: dir, fallbackBlob: nil)) { error in
      XCTAssertEqual(error as? StagingError, .fileMissing("/nope/missing.jpg"))
    }
    let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
    XCTAssertTrue(files.isEmpty)
  }

  func testFileIsCopied() throws {
    let src = root.appendingPathComponent("a.bin")
    writeFile(src, bytes: 64)
    let staged = try BodyStaging.stage(.file(path: src.path), parts: [], into: dir, fallbackBlob: nil)
    XCTAssertEqual(staged.totalBytes, 64)
    XCTAssertTrue(FileIO.exists(src), "a single file body is copied, not moved")
    XCTAssertEqual(try Data(contentsOf: src), try Data(contentsOf: dir.appendingPathComponent(staged.relativePath)))
    XCTAssertThrowsError(try BodyStaging.stage(.file(path: "/nope"), parts: [], into: dir, fallbackBlob: nil)) {
      XCTAssertEqual($0 as? StagingError, .fileMissing("/nope"))
    }
  }

  func testPartsMoveTheSource() throws {
    let src = root.appendingPathComponent("video.mp4")
    writeFile(src, bytes: 20)
    let staged = try BodyStaging.stage(.parts(file: src.path), parts: [part(0, 10), part(10, 20)],
                                       into: dir, fallbackBlob: nil)
    XCTAssertFalse(FileIO.exists(src), "a chunked file is moved")
    XCTAssertTrue(staged.relativePath.hasPrefix("blob-"))
    XCTAssertEqual(staged.totalBytes, 20)
    XCTAssertFalse(staged.adopted)
  }

  func testPartsTilingErrorLeavesTheSource() {
    let src = root.appendingPathComponent("video.mp4")
    writeFile(src, bytes: 20)
    XCTAssertThrowsError(try BodyStaging.stage(.parts(file: src.path), parts: [part(0, 10)], into: dir,
                                               fallbackBlob: nil)) { error in
      guard case .invalid = error as? StagingError else { return XCTFail("expected invalid, got \(error)") }
    }
    XCTAssertTrue(FileIO.exists(src), "the check runs before the move")
  }

  // A crash between the move and the entry save left the bytes as a blob.
  func testPartsAdoptExistingBlobAfterACrash() throws {
    writeFile(dir.appendingPathComponent("blob-crashed"), bytes: 20)
    let staged = try BodyStaging.stage(.parts(file: root.appendingPathComponent("gone.mp4").path),
                                       parts: [part(0, 20)], into: dir, fallbackBlob: "blob-crashed")
    XCTAssertEqual(staged.relativePath, "blob-crashed")
    XCTAssertTrue(staged.adopted)
    XCTAssertThrowsError(try BodyStaging.stage(.parts(file: "/gone"), parts: [part(0, 20)], into: dir,
                                               fallbackBlob: nil)) {
      XCTAssertEqual($0 as? StagingError, .fileMissing("/gone"))
    }
  }

  func testFileURLAcceptsBothForms() {
    XCTAssertEqual(BodyStaging.fileURL("/var/a b.txt").path, "/var/a b.txt")
    XCTAssertEqual(BodyStaging.fileURL("file:///var/a%20b.txt").path, "/var/a b.txt")
    XCTAssertEqual(BodyStaging.fileURL("file:///var/a b.txt").path, "/var/a b.txt")
  }
}
