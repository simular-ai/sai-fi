/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

@testable import SaiFi
import Foundation
import MWDATCore
import MWDATMockDevice
import Observation
import SwiftUI
import XCTest

@MainActor
final class ViewModelIntegrationTests: XCTestCase {

  private var mockDevice: MockGlasses?
  private var cameraKit: MockCameraKit?
  private var viewModel: StreamSessionViewModel?

  override func setUp() async throws {
    try await super.setUp()
    try? Wearables.configure()

    MockDeviceKit.shared.enable()

    // Pair mock device and set up camera kit
    let pairedMockDevice = try MockDeviceKit.shared.pairGlasses(model: .rayBanMeta)
    mockDevice = pairedMockDevice
    cameraKit = pairedMockDevice.services.camera

    // Power on and unfold the device to make it available
    pairedMockDevice.powerOn()
    pairedMockDevice.unfold()

    // Wait for device to be available in Wearables
    try await Task.sleep(nanoseconds: 1_000_000_000)
  }

  override func tearDown() async throws {
    viewModel?.endSession()
    viewModel = nil
    MockDeviceKit.shared.disable()
    mockDevice = nil
    cameraKit = nil
    try await super.tearDown()
  }

  // MARK: - Video Streaming Flow Tests

  func testVideoStreamingFlow() async throws {
    guard let camera = cameraKit else {
      XCTFail("Mock device and camera should be available")
      return
    }

    guard let videoURL = Bundle.main.url(forResource: "plant", withExtension: "mp4")
    else {
      XCTFail("Test resources not found")
      return
    }

    // Setup camera feed
    camera.setCameraFeed(fileURL: videoURL)

    let viewModel = StreamSessionViewModel(wearables: Wearables.shared)
    self.viewModel = viewModel

    // Wait for the mock device to be detected
    await observeUntil(timeout: 5) { viewModel.hasActiveDevice }

    // Initially not streaming
    XCTAssertEqual(viewModel.streamingStatus, .stopped)
    XCTAssertFalse(viewModel.isStreaming)
    XCTAssertFalse(viewModel.hasReceivedFirstFrame)
    XCTAssertNil(viewModel.currentVideoFrame)

    // Start streaming session
    await viewModel.handleStartStreaming()

    // Wait for streaming to establish
    await observeUntil(timeout: 10) {
      viewModel.isStreaming && viewModel.hasReceivedFirstFrame && viewModel.currentVideoFrame != nil
    }

    // Verify streaming is active and receiving frames
    XCTAssertTrue(viewModel.isStreaming)
    XCTAssertTrue(viewModel.hasReceivedFirstFrame)
    XCTAssertNotNil(viewModel.currentVideoFrame)
    XCTAssertTrue([.streaming, .waiting].contains(viewModel.streamingStatus))

    // Stop streaming
    viewModel.stopSession()

    // Wait for session to stop
    await observeUntil(timeout: 5) { !viewModel.isStreaming }

    // Verify streaming stopped (allow for final states to be stopped or waiting)
    XCTAssertFalse(viewModel.isStreaming)
    XCTAssertTrue([.stopped, .waiting].contains(viewModel.streamingStatus))
  }

  // MARK: - Photo Capture Flow Tests

  func testStreamingAndPhotoCaptureFlow() async throws {
    guard let camera = cameraKit else {
      XCTFail("Mock device and camera should be available")
      return
    }

    guard let videoURL = Bundle.main.url(forResource: "plant", withExtension: "mp4"),
      let imageURL = Bundle.main.url(forResource: "plant", withExtension: "png")
    else {
      XCTFail("Test resources not found")
      return
    }

    // Setup camera feed
    camera.setCameraFeed(fileURL: videoURL)
    camera.setCapturedImage(fileURL: imageURL)

    let viewModel = StreamSessionViewModel(wearables: Wearables.shared)
    self.viewModel = viewModel

    // Wait for the mock device to be detected
    await observeUntil(timeout: 5) { viewModel.hasActiveDevice }

    // Initially not streaming
    XCTAssertEqual(viewModel.streamingStatus, .stopped)
    XCTAssertFalse(viewModel.isStreaming)
    XCTAssertFalse(viewModel.hasReceivedFirstFrame)
    XCTAssertNil(viewModel.currentVideoFrame)

    // Start streaming session
    await viewModel.handleStartStreaming()

    // Wait for streaming to establish
    await observeUntil(timeout: 10) {
      viewModel.isStreaming && viewModel.hasReceivedFirstFrame && viewModel.currentVideoFrame != nil
    }

    // Verify streaming is active and receiving frames
    XCTAssertTrue(viewModel.isStreaming)
    XCTAssertTrue(viewModel.hasReceivedFirstFrame)
    XCTAssertNotNil(viewModel.currentVideoFrame)
    XCTAssertTrue([.streaming, .waiting].contains(viewModel.streamingStatus))

    // Capture photo while streaming
    viewModel.capturePhoto()
    await observeUntil(timeout: 10) { viewModel.capturedPhoto != nil }

    // Verify photo captured while maintaining stream (allow for some timing flexibility)
    XCTAssertTrue(viewModel.capturedPhoto != nil)
    XCTAssertTrue(viewModel.showPhotoPreview)
    XCTAssertTrue(viewModel.isStreaming)

    // Dismiss photo and stop streaming
    viewModel.dismissPhotoPreview()
    XCTAssertFalse(viewModel.showPhotoPreview)
    XCTAssertNil(viewModel.capturedPhoto)

    viewModel.stopSession()
    await observeUntil(timeout: 5) { !viewModel.isStreaming }

    XCTAssertFalse(viewModel.isStreaming)
    XCTAssertTrue([.stopped, .waiting].contains(viewModel.streamingStatus))
  }
}

// MARK: - Test Helpers

/// Thread-safe one-shot flag for protecting continuation resumption.
private final class ResumeOnce: @unchecked Sendable {
  private let lock = NSLock()
  private var resumed = false
  func tryResume() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard !resumed else { return false }
    resumed = true
    return true
  }
}

/// Reactively waits for a condition on @Observable objects to become true.
/// Uses `withObservationTracking` to wake up immediately on property changes
/// instead of polling on a fixed interval.
@MainActor
private func observeUntil(
  timeout: TimeInterval,
  file: StaticString = #filePath,
  line: UInt = #line,
  condition: @escaping () -> Bool
) async {
  guard !condition() else { return }

  let deadline = ContinuousClock.now + .seconds(timeout)

  while !condition() {
    guard ContinuousClock.now < deadline else {
      XCTFail("Condition not met within \(timeout) seconds", file: file, line: line)
      return
    }

    await withUnsafeContinuation { cont in
      let once = ResumeOnce()

      withObservationTracking {
        _ = condition()
      } onChange: {
        if once.tryResume() { cont.resume() }
      }

      // Periodic fallback so we can re-evaluate the deadline
      Task {
        try? await Task.sleep(for: .milliseconds(100))
        if once.tryResume() { cont.resume() }
      }
    }
  }
}
