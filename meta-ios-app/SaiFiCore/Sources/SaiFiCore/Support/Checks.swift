/* sai-fi — voice concierge. */

// The gate's registry.
//
// Every check is a pure function returning nil on success or a reason on failure, registered here so
// two entry points can run the identical set: `swift test` (XCTest / Testing, under Xcode or a full
// toolchain) and `swift run saifi-check` (no test framework at all). The Android gate can only be
// run through Gradle with the whole Android SDK resolved; this one runs anywhere Swift does, which
// is the point.
//
// A check named here and left unimplemented is worse than no check, so `checkCount()` is asserted
// against by the test target — the same trick `FsmGoldenTest` uses with PORTED_SCENARIO_COUNT to
// stop a shrinking catalog going green quietly.

import Foundation

public struct CheckFailure: Sendable {
  public let name: String
  public let detail: String
  public init(name: String, detail: String) {
    self.name = name
    self.detail = detail
  }
}

/// One registered check: a name, and a body that returns a failure reason or nil.
public struct Check: Sendable {
  public let name: String
  public let run: @Sendable () async -> String?
  public init(name: String, run: @escaping @Sendable () async -> String?) {
    self.name = name
    self.run = run
  }
}

/// Where the parity fixtures live.
///
/// They are TEST resources — the Android↔iOS gate, not something the app should carry — so they are
/// not in the library bundle and cannot be reached through `Bundle.module`. The test target passes
/// its own bundle directory; `saifi-check`, which has no bundle at all, passes nil and gets the
/// checked-out path derived from `#filePath`. That is fine for a developer tool and deliberately not
/// fine for anything shipped: a release build never runs these.
public struct ParityFixtures: Sendable {
  let directory: URL

  public init(directory: URL) { self.directory = directory }

  /// Resolve from the source tree.
  ///
  /// `#filePath` is used INSIDE the body, deliberately. As a default argument it would resolve at the
  /// CALL SITE — which is how the XCTest target ended up looking for the fixtures under
  /// `meta-ios-app/Tests/…` instead of `meta-ios-app/SaiFiCore/Tests/…`, while `saifi-check` worked by
  /// accident because its default expression happens to live in this file. Anchoring it here makes the
  /// answer independent of who asks.
  ///
  /// This file is `<pkg>/Sources/SaiFiCore/Support/Checks.swift`, so four parents up is the package root.
  public static func fromSourceTree() -> ParityFixtures {
    let here = URL(fileURLWithPath: #filePath)
    let packageRoot = here
      .deletingLastPathComponent()  // Support
      .deletingLastPathComponent()  // SaiFiCore
      .deletingLastPathComponent()  // Sources
      .deletingLastPathComponent()  // <pkg>
    return ParityFixtures(
      directory: packageRoot
        .appendingPathComponent("Tests/SaiFiCoreTests/Resources/parity", isDirectory: true))
  }

  /// The cases in one fixture file. Throws a readable reason rather than returning nil, because "the
  /// gate found no cases" and "the gate passed" must never look the same.
  public func load(_ name: String) throws -> [JsonObject] {
    let url = directory.appendingPathComponent(name)
    let data = try Data(contentsOf: url)
    guard let array = JsonArray(data: data) else {
      throw FixtureError.notAnArray(name: name, path: url.path)
    }
    let cases = array.objects()
    guard !cases.isEmpty else { throw FixtureError.empty(name: name, path: url.path) }
    return cases
  }
}

public enum FixtureError: Error, CustomStringConvertible {
  case notAnArray(name: String, path: String)
  case empty(name: String, path: String)

  public var description: String {
    switch self {
    case .notAnArray(let name, let path): return "\(name) at \(path) is not a JSON array"
    case .empty(let name, let path): return "\(name) at \(path) has no cases"
    }
  }
}

/// Everything the gate runs, in a stable order.
public func allChecks(fixtures: ParityFixtures = .fromSourceTree()) -> [Check] {
  var checks: [Check] = []
  checks += stateChecks()
  checks += effectsChecks()
  checks += speechChecks(fixtures)
  checks += conciergeProtocolChecks(fixtures)
  checks += activityLogChecks(fixtures)
  checks += fsmScenarioChecks(fixtures)
  checks += voiceProfileChecks()
  checks += policyChecks()
  checks += liveTurnGateChecks()
  checks += remainingPureChecks()
  return checks
}

public func checkCount(fixtures: ParityFixtures = .fromSourceTree()) -> Int {
  allChecks(fixtures: fixtures).count
}

public func runAllChecks(fixtures: ParityFixtures = .fromSourceTree()) async -> [CheckFailure] {
  var failures: [CheckFailure] = []
  for check in allChecks(fixtures: fixtures) {
    if let detail = await check.run() {
      failures.append(CheckFailure(name: check.name, detail: detail))
    }
  }
  return failures
}

// MARK: - assertion helpers
//
// Deliberately tiny and string-returning rather than throwing: a check that reports WHICH value it
// saw is the difference between a five-minute fix and a bisect.

func expectEqual<T: Equatable>(_ actual: T, _ expected: T, _ what: String) -> String? {
  actual == expected ? nil : "\(what): expected \(expected), got \(actual)"
}

func expectTrue(_ actual: Bool, _ what: String) -> String? {
  actual ? nil : "\(what): expected true"
}

func expectFalse(_ actual: Bool, _ what: String) -> String? {
  actual ? "\(what): expected false" : nil
}

/// First failure wins, so one check reports one reason.
func firstFailure(_ results: [String?]) -> String? {
  results.compactMap { $0 }.first
}
