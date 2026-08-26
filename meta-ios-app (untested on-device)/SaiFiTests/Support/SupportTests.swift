/* sai-fi — voice concierge. */

// Prefs keys and PhoneLocation's spoken label, without a device.

@testable import SaiFi
import SaiFiCore
import XCTest

final class PrefsTests: XCTestCase {
  private var previous: UserDefaults!
  private var suite: String!

  override func setUp() {
    super.setUp()
    previous = Prefs.store
    suite = "sai_glasses.test.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    Prefs.store = defaults
  }

  override func tearDown() {
    Prefs.store.removePersistentDomain(forName: suite)
    Prefs.store = previous
    super.tearDown()
  }

  func testAskFirstDefaultsToFifteen() {
    XCTAssertEqual(Prefs.askFirstSec, 15)
  }

  func testAskFirstPersists() {
    Prefs.setAskFirstSec(45)
    XCTAssertEqual(Prefs.askFirstSec, 45)
  }

  func testDevModeIsOffByDefaultEvenInDebug() {
    XCTAssertFalse(Prefs.devMode)
    Prefs.setDevMode(true)
    XCTAssertTrue(Prefs.devMode)
  }

  func testMachineIdStartsUnset() {
    XCTAssertNil(Prefs.machineId)
    Prefs.setMachineId("m-1")
    XCTAssertEqual(Prefs.machineId, "m-1")
  }
}

final class PhoneLocationLabelTests: XCTestCase {
  func testCoarsestUsefulFirstAndNeverAStreet() {
    XCTAssertEqual(
      PhoneLocation.placeLabel(
        subLocality: "Mission District",
        locality: "San Francisco",
        subAdminArea: nil,
        adminArea: "CA",
        country: "United States"),
      "Mission District, San Francisco, CA")
  }

  func testEmptyPartsAreDroppedAndDistinct() {
    XCTAssertEqual(
      PhoneLocation.placeLabel(
        subLocality: "  ",
        locality: "Singapore",
        subAdminArea: "Singapore",
        adminArea: "Singapore",
        country: "Singapore"),
      "Singapore")
  }

  func testLocalityFallsBackToSubAdmin() {
    XCTAssertEqual(
      PhoneLocation.placeLabel(
        subLocality: nil,
        locality: nil,
        subAdminArea: "Brooklyn",
        adminArea: "NY",
        country: "United States"),
      "Brooklyn, NY, United States")
  }

  func testAllEmptyIsNil() {
    XCTAssertNil(
      PhoneLocation.placeLabel(
        subLocality: " ",
        locality: nil,
        subAdminArea: nil,
        adminArea: nil,
        country: nil))
  }

  func testPlaceCarriesCapturedAtOntoTaskLocation() {
    let place = Place(lat: 1.3, lon: 103.8, capturedAt: 1_787_248_800_000)
    XCTAssertEqual(place.toTaskLocation().capturedAt, 1_787_248_800_000)
  }
}

final class ThemeTokenTests: XCTestCase {
  func testPrimaryIsNeutralNotGreen() {
    XCTAssertNotEqual(SaiColors.light.primary, SaiColors.green)
    XCTAssertNotEqual(SaiColors.dark.primary, SaiColors.green)
  }

  func testLightAndDarkAreDistinctBackgrounds() {
    XCTAssertNotEqual(SaiColors.light.background, SaiColors.dark.background)
  }
}
