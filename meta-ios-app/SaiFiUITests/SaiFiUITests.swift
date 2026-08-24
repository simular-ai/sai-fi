/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import MWDATMockDeviceTestClient
import XCTest

final class SaiFiUITests: XCTestCase {
  var portFilePath: String {
    NSTemporaryDirectory() + "mwdat_test_server_port.txt"
  }
  private let app = XCUIApplication()
  // swiftlint:disable implicitly_unwrapped_optional
  private var mockClient: MockDeviceTestClient!
  private var pairedDeviceId: String!
  // swiftlint:enable implicitly_unwrapped_optional

  override func setUpWithError() throws {
    continueAfterFailure = false

    // Remove any stale port file from a previous run so readPort() waits
    // for the new server to write its port instead of returning the old one.
    try? FileManager.default.removeItem(atPath: portFilePath)

    app.launchArguments = ["--ui-testing"]
    app.launchEnvironment["MWDAT_TEST_SERVER_PORT_FILE"] = portFilePath
    app.launch()

    // Initialize the client *after* launch so the server has time to write the port file.
    mockClient = MockDeviceTestClient(portFilePath: portFilePath)
    XCTAssertTrue(mockClient.waitForServer(timeout: 10), "Test server should be running")
  }

  override func tearDownWithError() throws {
    if pairedDeviceId != nil {
      mockClient.unpairDevice(deviceId: pairedDeviceId)
      pairedDeviceId = nil
    }
  }

  // MARK: - Helpers

  /// Taps "Connect my glasses" to trigger registration via the fake handler,
  /// dismisses the getting-started sheet, and waits for the streaming screen
  /// to be fully ready for device connections.
  private func registerViaUI() {
    let connectButton = app.buttons["Connect my glasses"]
    XCTAssertTrue(connectButton.waitForExistence(timeout: 10), "Should start on HomeScreenView")
    connectButton.tap()

    // Dismiss the getting-started sheet if it appears after registration.
    // In some environments (e.g. RE) the sheet may be skipped and the app
    // transitions directly to the stream screen.
    let continueButton = app.buttons["Continue"]
    if continueButton.waitForExistence(timeout: 5) {
      continueButton.tap()
    }

    // Wait for the NonStreamView to be fully rendered with device monitoring active
    let waitingText = app.staticTexts["Waiting for an active device"]
    XCTAssertTrue(waitingText.waitForExistence(timeout: 15), "Should show waiting state before a device is paired")
  }

  /// Pairs a device with default camera resources via the test server.
  private func pairDeviceWithCameraResources() {
    registerViaUI()

    let deviceId = mockClient.pairDevice()
    XCTAssertNotNil(deviceId, "pairDevice should return a deviceId")
    pairedDeviceId = deviceId

    mockClient.setCameraFeed(deviceId: pairedDeviceId, resourceName: "plant", ext: "mp4")
    mockClient.setCapturedImage(deviceId: pairedDeviceId, resourceName: "plant", ext: "png")
  }

  /// Waits for the "Start streaming" button to exist and be enabled (mock device active).
  @discardableResult
  private func waitForStartStreamingEnabled(timeout: TimeInterval = 15) -> XCUIElement {
    let startButton = app.buttons["Start streaming"]
    XCTAssertTrue(startButton.waitForExistence(timeout: timeout), "Start streaming button should appear")

    let predicate = NSPredicate(format: "isEnabled == true")
    expectation(for: predicate, evaluatedWith: startButton)
    waitForExpectations(timeout: timeout)

    return startButton
  }

  /// Waits for the "Start streaming" button to exist and be disabled (device inactive).
  @discardableResult
  private func waitForStartStreamingDisabled(timeout: TimeInterval = 15) -> XCUIElement {
    let startButton = app.buttons["Start streaming"]
    XCTAssertTrue(startButton.waitForExistence(timeout: timeout), "Start streaming button should appear")

    let predicate = NSPredicate(format: "isEnabled == false")
    expectation(for: predicate, evaluatedWith: startButton)
    waitForExpectations(timeout: timeout)

    return startButton
  }

  /// Starts streaming and waits for the StreamView to appear.
  private func startStreaming(timeout: TimeInterval = 15) {
    let startButton = waitForStartStreamingEnabled(timeout: timeout)
    startButton.tap()

    let stopButton = app.buttons["Stop streaming"]
    XCTAssertTrue(stopButton.waitForExistence(timeout: timeout), "Stop streaming button should appear after starting")
  }

  // MARK: - Device Pairing & Navigation Tests

  /// Verifies that launching without pairing a device shows the home screen.
  @MainActor
  func testLaunchWithoutDeviceShowsHomeScreen() {
    let connectButton = app.buttons["Connect my glasses"]
    XCTAssertTrue(
      connectButton.waitForExistence(timeout: 10),
      "HomeScreenView should show 'Connect my glasses' when no device is paired"
    )
  }

  /// Verifies that registering and pairing a device transitions the UI from the home screen
  /// to the stream screen with an active device.
  @MainActor
  func testRegisterAndPairTransitionsToStreamScreen() {
    pairDeviceWithCameraResources()
    waitForStartStreamingEnabled()
  }

  /// Verifies that the device state query reflects the correct number of paired devices.
  @MainActor
  func testDeviceStateReflectsPairedDevices() {
    // Initially no devices paired
    let state0 = mockClient.getDeviceState()
    XCTAssertNotNil(state0, "getDeviceState should return a response")
    XCTAssertEqual(state0?["pairedDeviceCount"] as? Int, 0, "Should have 0 paired devices initially")

    // Register and pair a device
    pairDeviceWithCameraResources()

    let state1 = mockClient.getDeviceState()
    XCTAssertNotNil(state1, "getDeviceState should return a response after pairing")
    XCTAssertEqual(state1?["pairedDeviceCount"] as? Int, 1, "Should have 1 paired device")

    // Unpair
    mockClient.unpairDevice(deviceId: pairedDeviceId)
    pairedDeviceId = nil

    let state2 = mockClient.getDeviceState()
    XCTAssertNotNil(state2, "getDeviceState should return a response after unpairing")
    XCTAssertEqual(state2?["pairedDeviceCount"] as? Int, 0, "Should have 0 paired devices after unpairing")
  }

  // MARK: - Device Activity Tests

  /// Verifies that doff makes the device inactive (disables streaming button)
  /// and don reactivates it.
  @MainActor
  func testDoffMakesDeviceInactiveAndDonReactivates() {
    pairDeviceWithCameraResources()
    waitForStartStreamingEnabled()

    // Doff the device → should become inactive
    mockClient.doff(deviceId: pairedDeviceId)
    waitForStartStreamingDisabled()

    // Don the device → should become active again
    mockClient.don(deviceId: pairedDeviceId)
    waitForStartStreamingEnabled()
  }

  /// Verifies that powering off makes the device inactive and powering on
  /// with don reactivates it.
  @MainActor
  func testPowerCycleAffectsDeviceActivity() {
    pairDeviceWithCameraResources()
    waitForStartStreamingEnabled()

    // Power off → device becomes inactive
    XCTAssertTrue(mockClient.powerOff(deviceId: pairedDeviceId), "Power off should succeed")
    waitForStartStreamingDisabled()

    // Power on + don → device becomes active again
    XCTAssertTrue(mockClient.powerOn(deviceId: pairedDeviceId), "Power on should succeed")
    XCTAssertTrue(mockClient.don(deviceId: pairedDeviceId), "Don should succeed")
    waitForStartStreamingEnabled()
  }

  // MARK: - Streaming Tests

  /// Verifies the complete start → stop streaming flow.
  // TestRail: C1599889064, C1602923640, C1602923646
  @MainActor
  func testStartAndStopStreaming() {
    pairDeviceWithCameraResources()
    startStreaming()

    // Stop streaming
    let stopButton = app.buttons["Stop streaming"]
    stopButton.tap()

    // Should return to NonStreamView
    let startButton = app.buttons["Start streaming"]
    XCTAssertTrue(startButton.waitForExistence(timeout: 10), "Should return to NonStreamView after stopping")
    XCTAssertTrue(app.staticTexts["Stream Your Glasses Camera"].exists, "NonStreamView title should reappear")
  }

  /// Verifies photo capture shows a preview and can be dismissed while continuing to stream.
  // TestRail: C1619609872, C1619610952
  @MainActor
  func testPhotoCaptureAndDismiss() {
    pairDeviceWithCameraResources()
    startStreaming()

    // Tap the capture button
    let captureButton = app.buttons["capture_photo_button"]
    XCTAssertTrue(captureButton.waitForExistence(timeout: 10), "Capture button should be visible during streaming")
    captureButton.tap()

    // Photo preview should appear
    let closeButton = app.buttons["close_preview_button"]
    XCTAssertTrue(closeButton.waitForExistence(timeout: 15), "Photo preview close button should appear after capture")

    // Dismiss the preview
    closeButton.tap()

    // Should still be streaming after dismissing preview
    let stopButton = app.buttons["Stop streaming"]
    XCTAssertTrue(stopButton.waitForExistence(timeout: 10), "Should still be streaming after dismissing photo preview")

    // Stop streaming
    stopButton.tap()

    // Should return to NonStreamView
    let startButton = app.buttons["Start streaming"]
    XCTAssertTrue(startButton.waitForExistence(timeout: 10), "Should return to NonStreamView after stopping")
  }

  /// Verifies that folding the glasses while streaming causes streaming to stop.
  @MainActor
  func testFoldDuringStreamingStopsStream() {
    pairDeviceWithCameraResources()
    startStreaming()

    // Fold the glasses → streaming should stop (hinges closed)
    XCTAssertTrue(mockClient.fold(deviceId: pairedDeviceId), "Fold command should succeed")

    // Fold triggers a hingesClosed error alert — dismiss it so the view hierarchy settles.
    let alertOK = app.alerts.buttons["OK"]
    if alertOK.waitForExistence(timeout: 15) {
      alertOK.tap()
    }

    // Should return to NonStreamView with the button disabled (device is folded).
    waitForStartStreamingDisabled()
  }
}
