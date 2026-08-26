@testable import SaiFi
import Foundation
import MWDATCamera
import MWDATCore
import MWDATMockDevice
import XCTest

@MainActor
final class MockDeviceTests: XCTestCase {
  private static var sharedHarness: GlassesTestSupport.Harness?
  private var harness: GlassesTestSupport.Harness?
  private var gesture: GlassesGestureSession?

  override func setUp() async throws {
    try await super.setUp()
    if Self.sharedHarness == nil {
      Self.sharedHarness = try await GlassesTestSupport.bootMockGlasses()
    }
    harness = Self.sharedHarness
    MockDeviceKit.shared.permissions.set(.camera, .granted)
  }

  override func tearDown() async throws {
    if let gesture {
      await gesture.stopAndWait()
    }
    gesture = nil
    await harness?.sessionManager.stopCurrentSessionAndWait()
    MockDeviceKit.shared.permissions.set(.camera, .granted)
    harness = nil
    try await super.tearDown()
  }

  override class func tearDown() {
    sharedHarness?.sessionManager.cleanup()
    sharedHarness = nil
    MockDeviceKit.shared.disable()
    super.tearDown()
  }

  // MARK: - §4 probes (Phase 1 gate)

  /// (a) Can a capability-less gesture session and a camera session coexist?
  /// Probe: `createSession` twice, one with `addStream`. If `sessionAlreadyExists`, one session +
  /// addStream on demand.
  func testSection4a_concurrentSessions() async throws {
    guard let harness else {
      XCTFail("Mock glasses should be available")
      return
    }
    let wearables = Wearables.shared
    let gestureSession = try await harness.sessionManager.getSession()
    XCTAssertEqual(gestureSession.state, .started)

    // Second selector must be observing too — a fresh AutoDeviceSelector reports
    // noEligibleDevice even when the device is live (that's DeviceSessionManager).
    let cameraManager = DeviceSessionManager(wearables: wearables)
    _ = await GlassesTestSupport.waitUntil(timeout: 5) { cameraManager.hasActiveDevice }

    var second: DeviceSession?
    var secondError: DeviceSessionError?
    do {
      second = try await cameraManager.getSession()
    } catch let error as DeviceSessionError {
      secondError = error
    } catch {
      XCTFail("unexpected error creating second session: \(error)")
      cameraManager.cleanup()
      return
    }

    let concurrentAllowed = second != nil && second !== gestureSession
    XCTAssertEqual(
      concurrentAllowed,
      GlassesDatPolicy.concurrentSessionsAllowed,
      "§4(a) changed — update GlassesDatPolicy.concurrentSessionsAllowed and the type comments. secondError=\(String(describing: secondError))"
    )

    if let second, concurrentAllowed {
      let stream = try second.addStream(config: GlassesTestSupport.streamConfig())
      XCTAssertNotNil(stream, "addStream on the camera session should succeed when two sessions coexist")
      stream?.stop()
      second.stop()
    } else {
      // One session for the call, addStream on demand. The refusal may surface as
      // sessionAlreadyExists *or* noEligibleDevice depending on the selector.
      XCTAssertNotNil(secondError, "second session should be refused, got a session=\(second != nil)")
      let stream = try gestureSession.addStream(config: GlassesTestSupport.streamConfig())
      XCTAssertNotNil(stream, "addStream on the gesture session must work when a second session is refused")
      stream?.stop()
    }
    cameraManager.cleanup()
  }

  /// (b) How does a stream get released without `removeStream()`?
  /// Probe: `stop()` the stream, `addStream` again on the same session, see whether frames flow.
  /// If not, `session.stop()` after every capture.
  func testSection4b_streamReleaseWithoutRemoveStream() async throws {
    guard let harness else {
      XCTFail("Mock glasses should be available")
      return
    }
    guard let videoURL = GlassesTestSupport.plantVideoURL() else { return }
    harness.camera.setCameraFeed(fileURL: videoURL)

    let session = try await harness.sessionManager.getSession()

    let first = try session.addStream(config: GlassesTestSupport.streamConfig())
    guard let first else {
      XCTFail("addStream returned nil")
      session.stop()
      return
    }
    let firstFrames = FrameCounter()
    let firstToken = first.videoFramePublisher.listen { _ in firstFrames.increment() }
    first.start()
    let gotFirst = await GlassesTestSupport.waitUntil(timeout: 10) { firstFrames.count > 0 }
    XCTAssertTrue(gotFirst, "first stream should deliver frames")
    first.stop()
    _ = await GlassesTestSupport.waitUntil(timeout: 3) { first.state != .streaming }
    await firstToken.cancel()

    // iOS 0.8 has no removeStream(). This is the measurement: does addStream after stop() work?
    var readdError: Error?
    var second: MWDATCamera.Stream?
    do {
      second = try session.addStream(config: GlassesTestSupport.streamConfig())
    } catch {
      readdError = error
    }
    var framesAfterReadd = false
    if let second {
      let counter = FrameCounter()
      let token = second.videoFramePublisher.listen { _ in counter.increment() }
      second.start()
      framesAfterReadd = await GlassesTestSupport.waitUntil(timeout: 10) { counter.count > 0 }
      await token.cancel()
      second.stop()
    }

    let released = second != nil && framesAfterReadd && readdError == nil
    XCTAssertEqual(
      released,
      GlassesDatPolicy.streamStopReleasesSlot,
      "§4(b) changed — update GlassesDatPolicy.streamStopReleasesSlot and the GlassesCamera comments. readdError=\(String(describing: readdError)) second=\(second != nil) frames=\(framesAfterReadd)"
    )
    if !released {
      session.stop()
    } else {
      session.stop()
    }
  }

  // MARK: - Gestures

  func testCaptouchTap_muteToggle() async throws {
    guard let harness else {
      XCTFail("Mock glasses should be available")
      return
    }
    let taps = Counter()
    let stops = Counter()
    let logs = LogBox()
    let session = GlassesGestureSession(
      wearables: Wearables.shared,
      onTap: { taps.increment() },
      onStop: { stops.increment() },
      onLog: { logs.append($0) }
    )
    gesture = session
    await session.start()

    let live = await GlassesTestSupport.waitUntil(timeout: 6) {
      session.deviceSession()?.state == .started
        || logs.entries.contains { $0.contains("temple button live") }
    }
    XCTAssertTrue(live, "gesture session should start. logs=\(logs.entries)")

    harness.captouch.tap()
    let tapped = await GlassesTestSupport.waitUntil(timeout: 5) { taps.count >= 1 }
    XCTAssertTrue(tapped, "captouch.tap() should fire onTap (mute toggle). logs=\(logs.entries)")
    XCTAssertEqual(stops.count, 0, "tap must not end the call")
    XCTAssertTrue(
      logs.entries.contains { $0.contains("glasses: session") && $0.contains("→") },
      "transition must be logged the moment it arrives, before acting. logs=\(logs.entries)"
    )
  }

  func testCaptouchTapAndHold_endCall() async throws {
    guard let harness else {
      XCTFail("Mock glasses should be available")
      return
    }
    let taps = Counter()
    let stops = Counter()
    let session = GlassesGestureSession(
      wearables: Wearables.shared,
      onTap: { taps.increment() },
      onStop: { stops.increment() },
      onLog: { _ in }
    )
    gesture = session
    await session.start()
    let live = await GlassesTestSupport.waitUntil(timeout: 6) { session.deviceSession()?.state == .started }
    XCTAssertTrue(live)

    harness.captouch.tapAndHold()
    let stopped = await GlassesTestSupport.waitUntil(timeout: 5) { stops.count >= 1 }
    XCTAssertTrue(stopped, "captouch.tapAndHold() should fire onStop (end call)")
  }

  // MARK: - Camera

  func testSetCapturedImage_returnsJPEG() async throws {
    guard let harness else {
      XCTFail("Mock glasses should be available")
      return
    }
    guard let videoURL = GlassesTestSupport.plantVideoURL(),
      let imageURL = GlassesTestSupport.plantImageURL()
    else { return }
    harness.camera.setCameraFeed(fileURL: videoURL)
    harness.camera.setCapturedImage(fileURL: imageURL)

    let session = GlassesGestureSession(
      wearables: Wearables.shared,
      onTap: {},
      onStop: {},
      onLog: { _ in }
    )
    gesture = session
    await session.start()
    guard await GlassesTestSupport.waitUntil(timeout: 6, condition: { session.deviceSession()?.state == .started }),
      let deviceSession = session.deviceSession()
    else {
      XCTFail("gesture session did not start")
      return
    }

    let logs = LogBox()
    let result = await GlassesCamera.capture(
      session: deviceSession,
      wearables: Wearables.shared,
      onLog: { logs.append($0) }
    )
    switch result {
    case .success(let photo):
      XCTAssertGreaterThan(photo.jpeg.count, 0)
      XCTAssertEqual(photo.jpeg[0], 0xFF)
      XCTAssertEqual(photo.jpeg[1], 0xD8)
      XCTAssertGreaterThan(photo.width, 0)
      XCTAssertGreaterThan(photo.height, 0)
    case .failure(let failure):
      XCTFail("expected JPEG still, got \(failure.message) — \(failure.detail). logs=\(logs.entries)")
    }
  }

  func testPermissionDenied_doesNotHang() async throws {
    guard harness != nil else {
      XCTFail("Mock glasses should be available")
      return
    }
    MockDeviceKit.shared.permissions.set(.camera, .denied)

    let session = GlassesGestureSession(
      wearables: Wearables.shared,
      onTap: {},
      onStop: {},
      onLog: { _ in }
    )
    gesture = session
    await session.start()
    guard await GlassesTestSupport.waitUntil(timeout: 6, condition: { session.deviceSession()?.state == .started }),
      let deviceSession = session.deviceSession()
    else {
      XCTFail("gesture session did not start")
      return
    }

    let logs = LogBox()
    let result = await withTaskGroup(of: GlassesCamera.Result?.self) { group in
      group.addTask {
        await GlassesCamera.capture(
          session: deviceSession,
          wearables: Wearables.shared,
          onLog: { logs.append($0) }
        )
      }
      group.addTask {
        try? await Task.sleep(for: .seconds(8))
        return nil
      }
      let first = await group.next() ?? nil
      group.cancelAll()
      return first
    }
    guard let result else {
      XCTFail("capture hung after camera permission denial. logs=\(logs.entries)")
      return
    }
    switch result {
    case .success:
      XCTFail("denied camera permission must not return a still")
    case .failure(let failure):
      XCTAssertTrue(
        failure.message.contains("camera access") || failure.detail.contains("Denied"),
        "denial should surface as a typed failure, not hang. \(failure.message) | \(failure.detail)"
      )
      XCTAssertFalse(failure.streamStarted)
    }
    XCTAssertTrue(
      logs.entries.contains { $0.contains("FAILED (no permission)") },
      "must copy the Kotlin permission-denied log. logs=\(logs.entries)"
    )
  }

  func testUpdateAffordances_sampleWording() {
    XCTAssertEqual(GlassesUpdateAffordance.firmware, "Update firmware")
    XCTAssertEqual(GlassesUpdateAffordance.appOnGlasses, "Update app on glasses")
  }
}

// MARK: - Tiny test boxes

private final class Counter: @unchecked Sendable {
  private let lock = NSLock()
  private var value = 0
  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return value
  }
  func increment() {
    lock.lock()
    value += 1
    lock.unlock()
  }
}

private final class FrameCounter: @unchecked Sendable {
  private let lock = NSLock()
  private var value = 0
  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return value
  }
  func increment() {
    lock.lock()
    value += 1
    lock.unlock()
  }
}

private final class LogBox: @unchecked Sendable {
  private let lock = NSLock()
  private var lines: [String] = []
  var entries: [String] {
    lock.lock()
    defer { lock.unlock() }
    return lines
  }
  func append(_ line: String) {
    lock.lock()
    lines.append(line)
    lock.unlock()
  }
}
