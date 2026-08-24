/* sai-fi — voice concierge. */

// The gate, under a test framework.
//
// `SaiFiCore` registers its checks as data (see `Support/Checks.swift`) precisely so there can be two
// entry points over one set: this file, for Xcode and CI, and `swift run saifi-check`, for a machine
// that has only Command Line Tools and therefore neither XCTest nor Testing. Nothing is asserted
// twice — this just surfaces each registered check as its own test so a failure names itself in the
// Xcode test navigator instead of arriving as one blob.
//
// The `canImport` guard is what lets `swift build` succeed on a toolchain with no XCTest at all.

#if canImport(XCTest)
import XCTest
@testable import SaiFiCore

final class GateTests: XCTestCase {

  /// The fixtures ship as resources of THIS target, so read them from this bundle rather than from
  /// the source tree — that is what makes the gate work from a built test bundle on CI.
  private var fixtures: ParityFixtures {
    guard let url = Bundle.module.url(forResource: "parity/speech", withExtension: "json") else {
      // Falling back to the source tree keeps a local `swift test` working if resource processing
      // ever flattens the directory; a genuinely missing file still fails inside the checks.
      return .fromSourceTree()
    }
    return ParityFixtures(directory: url.deletingLastPathComponent())
  }

  func testEveryRegisteredCheckPasses() async throws {
    let checks = allChecks(fixtures: fixtures)
    XCTAssertFalse(checks.isEmpty, "the gate registered no checks at all")

    var failures: [String] = []
    for check in checks {
      if let detail = await check.run() {
        failures.append("\(check.name): \(detail)")
      }
    }
    XCTAssertTrue(
      failures.isEmpty,
      "\(failures.count) of \(checks.count) checks failed:\n" + failures.joined(separator: "\n"))
  }

  /// A gate that silently stops registering checks is the failure mode this whole design is built
  /// against — the same argument as `FsmGoldenTest`'s PORTED_SCENARIO_COUNT on the Android side.
  func testTheGateStillHasEveryCheck() {
    XCTAssertGreaterThanOrEqual(
      checkCount(fixtures: fixtures), 153,
      "the check registry shrank — a check was removed rather than fixed")
  }
}
#endif
