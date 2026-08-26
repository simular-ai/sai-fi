/*
 * sai-fi — UI tests against the concierge shell.
 *
 * `--ui-testing` skips the Google sign-in gate (see AppModel.refreshAuthState) and starts
 * MockDeviceKit's test server, so these can drive DAT registration without Firebase.
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
  private var pairedDeviceId: String?
  // swiftlint:enable implicitly_unwrapped_optional

  override func setUpWithError() throws {
    continueAfterFailure = false

    try? FileManager.default.removeItem(atPath: portFilePath)

    app.launchArguments = ["--ui-testing"]
    app.launchEnvironment["MWDAT_TEST_SERVER_PORT_FILE"] = portFilePath
    app.launch()

    mockClient = MockDeviceTestClient(portFilePath: portFilePath)
    XCTAssertTrue(mockClient.waitForServer(timeout: 10), "Test server should be running")
  }

  override func tearDownWithError() throws {
    if let pairedDeviceId {
      mockClient.unpairDevice(deviceId: pairedDeviceId)
    }
  }

  /// `--ui-testing` skips the sign-in gate, so Home is the first screen.
  @MainActor
  func testLaunchShowsConciergeHome() {
    XCTAssertTrue(
      app.staticTexts["Sai-Fi"].waitForExistence(timeout: 10),
      "Home header should be visible")
    XCTAssertTrue(
      app.buttons["Register glasses"].waitForExistence(timeout: 10),
      "Unregistered Home should offer Register glasses")
  }

  /// Register via the fake handler, then pair a mock device. The Connection card should leave
  /// "not registered" once DAT reports REGISTERED.
  @MainActor
  func testRegisterGlassesViaMock() {
    let register = app.buttons["Register glasses"]
    XCTAssertTrue(register.waitForExistence(timeout: 10))
    register.tap()

    let deviceId = mockClient.pairDevice()
    XCTAssertNotNil(deviceId, "pairDevice should return a deviceId")
    pairedDeviceId = deviceId

    let registered = app.staticTexts["glasses-registration"]
    XCTAssertTrue(registered.waitForExistence(timeout: 15), "Registration line should exist")
    let becameRegistered = NSPredicate(format: "label == %@", "Meta DAT registration: registered")
    expectation(for: becameRegistered, evaluatedWith: registered)
    waitForExpectations(timeout: 15)
  }

  @MainActor
  func testDeviceStateReflectsPairedDevices() {
    let state0 = mockClient.getDeviceState()
    XCTAssertNotNil(state0, "getDeviceState should return a response")
    XCTAssertEqual(state0?["pairedDeviceCount"] as? Int, 0, "Should have 0 paired devices initially")

    let register = app.buttons["Register glasses"]
    XCTAssertTrue(register.waitForExistence(timeout: 10))
    register.tap()

    let deviceId = mockClient.pairDevice()
    XCTAssertNotNil(deviceId)
    pairedDeviceId = deviceId

    let state1 = mockClient.getDeviceState()
    XCTAssertEqual(state1?["pairedDeviceCount"] as? Int, 1, "Should have 1 paired device")

    if let id = pairedDeviceId {
      mockClient.unpairDevice(deviceId: id)
    }
    pairedDeviceId = nil

    let state2 = mockClient.getDeviceState()
    XCTAssertEqual(state2?["pairedDeviceCount"] as? Int, 0, "Should have 0 paired devices after unpairing")
  }
}
