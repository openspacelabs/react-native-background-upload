import XCTest
@testable import RNBGUCore

/// A failed settings save does not fail the caller. The value applies in
/// memory at once, and the save is tried again at the next settings call and
/// at the next app activation.
final class SettingsSaveTests: XCTestCase {
  private var h: Harness!

  override func setUp() {
    h = Harness()
    h.boot()
  }

  override func tearDown() {
    setReadOnly(h.store.root, false)
    try? FileManager.default.removeItem(at: h.root)
  }

  private var settingsFile: URL { h.store.root.appendingPathComponent("settings.json") }
  private var saved: QueueSettings { h.store.loadSettings() }

  /// Runs one module method and returns its rejection, or nil when it resolved.
  private func rejection(_ call: (@escaping () -> Void, @escaping (String, String) -> Void) -> Void) -> EnqueueError? {
    var rejected: EnqueueError?
    call({}, { rejected = EnqueueError(code: $0, message: $1) })
    h.drain()
    return rejected
  }

  private func onlyTask(file: StaticString = #filePath, line: UInt = #line) -> FakeTask {
    XCTAssertEqual(h.transport.live.count, 1, file: file, line: line)
    return h.transport.live.last!
  }

  /// The settings file cannot be written. Entry directories that already
  /// exist stay writable.
  private func failSettingsSaves() { setReadOnly(h.store.root, true) }
  private func allowSettingsSaves() { setReadOnly(h.store.root, false) }

  // MARK: - The caller does not fail

  func testSetWifiOnlyResolvesAndAppliesWhenTheSaveFails() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    h.complete(onlyTask(), status: 503)
    let waiting = onlyTask()
    failSettingsSaves()
    XCTAssertNil(rejection { self.h.coordinator.setWifiOnly(true, resolve: $0, reject: $1) })
    XCTAssertTrue(h.coordinator.settings.wifiOnly, "this session uses the new value")
    XCTAssertTrue(waiting.cancelled, "the waiting retry moves to the Wi-Fi session")
    XCTAssertTrue(onlyTask().wifiOnly)
    XCTAssertFalse(saved.wifiOnly, "the saved copy is behind")
  }

  func testUpdateHeadersResolvesAndResumesParkedEntriesWhenTheSaveFails() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    h.complete(onlyTask(), status: 401)
    XCTAssertEqual(h.entry("a")?.state, .awaitingAuth)
    failSettingsSaves()
    XCTAssertNil(rejection { self.h.coordinator.updateHeaders(["Authorization": "new"], resolve: $0, reject: $1) })
    XCTAssertEqual(h.coordinator.settings.headerGeneration, 1)
    XCTAssertEqual(h.entry("a")?.headers["Authorization"], "new")
    XCTAssertEqual(onlyTask().header("Authorization"), "new", "the parked entry sends again with the new headers")
    XCTAssertEqual(saved.headerGeneration, 0, "the saved copy is behind")
  }

  func testPauseAndResumeResolveAndMoveEntriesWhenTheSaveFails() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    failSettingsSaves()
    XCTAssertNil(rejection { self.h.coordinator.pause([:], resolve: $0, reject: $1) })
    XCTAssertEqual(h.entry("a")?.state, .paused)
    XCTAssertTrue(h.transport.live.isEmpty)
    XCTAssertNil(rejection { self.h.coordinator.resume([:], resolve: $0, reject: $1) })
    XCTAssertEqual(h.entry("a")?.state, .running)
  }

  func testConfigureAppliesWhenTheSaveFails() {
    failSettingsSaves()
    h.coordinator.configure(["retry": ["backoff": ["baseMs": 500]]])
    h.drain()
    XCTAssertEqual(h.coordinator.settings.retry?.baseMs, 500)
    XCTAssertNil(saved.retry)
  }

  func testAnInvalidHeaderStillRejectsAndChangesNothing() throws {
    _ = try h.enqueue(h.dataRaw(id: "a", headers: ["Authorization": "old"])).get()
    failSettingsSaves()
    let r = try XCTUnwrap(rejection { self.h.coordinator.updateHeaders(["Authorization": "new\r\n"], resolve: $0, reject: $1) })
    XCTAssertEqual(r.code, "E_INVALID")
    XCTAssertEqual(h.coordinator.settings.headerGeneration, 0)
    XCTAssertEqual(h.entry("a")?.headers["Authorization"], "old")
  }

  // MARK: - The save is tried again

  func testTheNextSettingsCallSavesTheValueThatWasBehind() {
    failSettingsSaves()
    h.updateHeaders(["Authorization": "new"])
    allowSettingsSaves()
    h.setWifiOnly(false) // unchanged, but the saved copy is behind
    XCTAssertEqual(saved.headerGeneration, 1)
  }

  func testTheNextActivationSavesTheValueThatWasBehind() {
    failSettingsSaves()
    h.setWifiOnly(true)
    allowSettingsSaves()
    h.becomeActive()
    XCTAssertTrue(saved.wifiOnly)
  }

  func testARetryThatFailsAgainKeepsTheValueAndTriesLater() {
    failSettingsSaves()
    h.setWifiOnly(true)
    h.becomeActive()
    XCTAssertTrue(h.coordinator.settings.wifiOnly)
    XCTAssertFalse(saved.wifiOnly)
    allowSettingsSaves()
    h.becomeActive()
    XCTAssertTrue(saved.wifiOnly)
  }

  func testActivationWritesNothingWhenTheSavedCopyIsCurrent() throws {
    h.setWifiOnly(true)
    try FileManager.default.removeItem(at: settingsFile)
    h.becomeActive()
    XCTAssertFalse(FileIO.exists(settingsFile), "nothing was behind, so nothing is written")
  }

  // MARK: - After a relaunch

  func testTheHeaderGenerationDoesNotGoBackAfterARelaunch() throws {
    _ = try h.enqueue(h.dataRaw(id: "a")).get()
    failSettingsSaves()
    h.updateHeaders(["Authorization": "t1"]) // entry a saves generation 1; settings.json keeps 0
    allowSettingsSaves()
    XCTAssertEqual(h.store.load("a")?.headerGeneration, 1)
    h.relaunch()
    h.boot()
    XCTAssertEqual(h.coordinator.settings.headerGeneration, 0, "the relaunch reads the older saved value")
    h.updateHeaders(["Authorization": "t2"])
    // A task sent under generation 1 (t1) that gets a 401 must see newer headers.
    XCTAssertEqual(h.coordinator.settings.headerGeneration, 2)
    XCTAssertEqual(h.entry("a")?.headerGeneration, 2)
  }
}
