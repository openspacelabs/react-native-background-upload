import XCTest
@testable import RNBGUCore

/// setWifiOnly skips an unchanged write, and every E_STORAGE rejection
/// carries the underlying error after its message.
final class StorageCauseTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
    h.boot()
  }

  override func tearDown() {
    setReadOnly(h.store.root, false)
    setReadOnly(h.journal.root, false)
    try? FileManager.default.removeItem(at: h.root)
  }

  private var settingsFile: URL { h.store.root.appendingPathComponent("settings.json") }

  /// Runs one module method and returns its rejection, or nil when it resolved.
  private func rejection(_ call: (@escaping () -> Void, @escaping (String, String) -> Void) -> Void) -> EnqueueError? {
    var rejected: EnqueueError?
    call({}, { rejected = EnqueueError(code: $0, message: $1) })
    h.drain()
    return rejected
  }

  // MARK: - setWifiOnly with an unchanged value

  func testSetWifiOnlyWithTheSameValueWritesOnce() throws {
    h.setWifiOnly(true)
    XCTAssertTrue(FileIO.exists(settingsFile))
    // A second write would create the file again.
    try FileManager.default.removeItem(at: settingsFile)
    h.setWifiOnly(true)
    XCTAssertFalse(FileIO.exists(settingsFile), "the same value writes nothing")
    XCTAssertTrue(h.coordinator.settings.wifiOnly)
  }

  func testSetWifiOnlyWithAChangedValueStillWrites() {
    h.setWifiOnly(true)
    h.setWifiOnly(false)
    XCTAssertFalse(h.store.loadSettings().wifiOnly)
    h.setWifiOnly(true)
    XCTAssertTrue(h.store.loadSettings().wifiOnly)
  }

  func testSetWifiOnlyWithTheSameValueResolvesWhenTheSettingsCannotBeSaved() {
    h.setWifiOnly(true)
    setReadOnly(h.store.root, true)
    XCTAssertNil(rejection { self.h.coordinator.setWifiOnly(true, resolve: $0, reject: $1) })
  }

  // MARK: - The cause in the message

  func testFailedSettingsSaveRejectsWithTheCause() throws {
    h.pause(keys: ["capture"]) // so the resume below changes the settings
    setReadOnly(h.store.root, true)
    let calls: [(String, EnqueueError?)] = [
      ("setWifiOnly", rejection { self.h.coordinator.setWifiOnly(true, resolve: $0, reject: $1) }),
      ("updateHeaders", rejection { self.h.coordinator.updateHeaders(["A": "b"], resolve: $0, reject: $1) }),
      ("pause", rejection { self.h.coordinator.pause([:], resolve: $0, reject: $1) }),
      ("resume", rejection { self.h.coordinator.resume(["keys": ["capture"]], resolve: $0, reject: $1) }),
    ]
    for (method, rejected) in calls {
      let r = try XCTUnwrap(rejected, "\(method) resolved")
      XCTAssertEqual(r.code, "E_STORAGE")
      // EACCES: the read-only directory refuses settings.json.tmp.
      XCTAssertTrue(r.message.hasPrefix(
        "\(method): cannot save the queue settings (cause: NSPOSIXErrorDomain 13; cannot create settings.json.tmp"),
                    r.message)
    }
  }

  func testCancelWhoseJournalWriteFailsRejectsWithTheCause() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    setReadOnly(h.journal.root, true)
    let r = try XCTUnwrap(h.cancel("a"))
    XCTAssertEqual(r.code, "E_STORAGE")
    XCTAssertTrue(r.message.hasPrefix("cancel: cannot journal the outcome of 'a'; nothing changed (cause: "), r.message)
    XCTAssertTrue(r.message.contains("NSPOSIXErrorDomain 13"), r.message)
  }

  func testFailedEntrySaveKeepsTheDescriptionAndAddsTheCode() throws {
    setReadOnly(h.store.root, true) // the entry directory cannot be made
    guard case .failure(let r) = h.enqueue(h.dataRaw(id: "a")) else { return XCTFail("enqueue resolved") }
    XCTAssertEqual(r.code, "E_STORAGE")
    XCTAssertTrue(r.message.hasPrefix("enqueue: cannot create the entry directory: "), r.message)
    XCTAssertTrue(r.message.hasSuffix("(cause: NSCocoaErrorDomain 513; POSIX 13)"), r.message)
  }

  // MARK: - The formatter

  func testCauseNamesTheDomainCodeUnderlyingPOSIXCodeAndDescription() {
    let full = NSError(domain: NSCocoaErrorDomain, code: 640, userInfo: [
      NSLocalizedDescriptionKey: "no space",
      NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: 28),
    ])
    XCTAssertEqual(FileIO.cause(full), " (cause: NSCocoaErrorDomain 640; POSIX 28; no space)")
    XCTAssertEqual(FileIO.cause(full, description: false), " (cause: NSCocoaErrorDomain 640; POSIX 28)")
    XCTAssertEqual(FileIO.cause(FileIO.posixError(28, "cannot create x")),
                   " (cause: NSPOSIXErrorDomain 28; cannot create x: errno 28 (No space left on device))")
  }
}
