@testable import SaiFi
import Foundation
import MWDATCamera
import MWDATCore
import MWDATMockDevice
import XCTest

/// Shared MockDeviceKit harness for Glasses/ tests. Mirrors the sample's pairing sequence plus
/// `don()`, which the plan's §4 probes require. Waits on DeviceSessionManager.hasActiveDevice —
/// a fresh AutoDeviceSelector is not eligible until its stream is observed.
@MainActor
enum GlassesTestSupport {
  struct Harness {
    var glasses: MockGlasses
    var camera: MockCameraKit
    var captouch: MockCaptouchKit
    var sessionManager: DeviceSessionManager
  }

  static func bootMockGlasses() async throws -> Harness {
    try? Wearables.configure()
    if !MockDeviceKit.shared.isEnabled {
      MockDeviceKit.shared.enable(
        config: MockDeviceKitConfig(initiallyRegistered: true, initialPermissionsGranted: true)
      )
    }
    let glasses = try MockDeviceKit.shared.pairGlasses(model: .rayBanMeta)
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
    let sessionManager = DeviceSessionManager(wearables: Wearables.shared)
    let ready = await waitUntil(timeout: 15) { sessionManager.hasActiveDevice }
    if !ready {
      XCTFail("Mock glasses never became eligible (devices=\(Wearables.shared.devices))")
    }
    return Harness(
      glasses: glasses,
      camera: glasses.services.camera,
      captouch: glasses.services.captouch,
      sessionManager: sessionManager
    )
  }

  static func shutdownMockGlasses(_ harness: Harness?) {
    harness?.sessionManager.cleanup()
  }

  static func plantVideoURL(file: StaticString = #filePath, line: UInt = #line) -> URL? {
    guard let url = Bundle.main.url(forResource: "plant", withExtension: "mp4") else {
      XCTFail("plant.mp4 missing from SaiFi test resources", file: file, line: line)
      return nil
    }
    return url
  }

  static func plantImageURL(file: StaticString = #filePath, line: UInt = #line) -> URL? {
    guard let url = Bundle.main.url(forResource: "plant", withExtension: "png") else {
      XCTFail("plant.png missing from SaiFi test resources", file: file, line: line)
      return nil
    }
    return url
  }

  static func waitUntil(
    timeout: TimeInterval,
    file: StaticString = #filePath,
    line: UInt = #line,
    condition: @escaping () -> Bool
  ) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(timeout)
    while !condition() {
      if ContinuousClock.now >= deadline {
        XCTFail("Condition not met within \(timeout) seconds", file: file, line: line)
        return false
      }
      try? await Task.sleep(for: .milliseconds(50))
    }
    return true
  }

  static func waitForStarted(_ session: DeviceSession, timeout: TimeInterval = 6) async -> Bool {
    if session.state == .started { return true }
    let states = session.stateStream()
    if session.state == .started { return true }
    return await withTaskGroup(of: Bool.self) { group in
      group.addTask {
        for await st in states {
          if st == .started { return true }
          if st == .stopped { return session.state == .started }
        }
        return session.state == .started
      }
      group.addTask {
        try? await Task.sleep(for: .seconds(timeout))
        return session.state == .started
      }
      let result = await group.next() ?? false
      group.cancelAll()
      return result || session.state == .started
    }
  }

  static func streamConfig() -> StreamConfiguration {
    StreamConfiguration(videoCodec: .raw, resolution: .medium, frameRate: 24)
  }
}
