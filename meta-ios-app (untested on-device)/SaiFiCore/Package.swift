// swift-tools-version: 6.0
//
// SaiFiCore — the pure half of the sai-fi iOS client.
//
// Everything in here is Foundation-only: no MWDAT, no AVFoundation, no SwiftUI, no UIKit. That is
// the same boundary `docs/VOICE_FSM.md` §10 draws on Android, where the FSM stays
// dispatcher-agnostic so the 63-scenario golden catalog can run as plain JVM tests. Keeping it a
// separate SwiftPM package makes the boundary structural rather than a convention, and it means the
// gate runs on any machine with a Swift toolchain — no Xcode, no simulator boot.
//
//   swift test                 # the gate, under a test framework
//   swift run saifi-check      # the same gate, with no test framework at all
//
// The second entry point exists because a Command Line Tools install has neither XCTest nor
// Testing, and a gate you cannot run is not a gate.

import PackageDescription

let package = Package(
  name: "SaiFiCore",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [
    .library(name: "SaiFiCore", targets: ["SaiFiCore"]),
    .executable(name: "saifi-check", targets: ["saifi-check"]),
  ],
  targets: [
    .target(
      name: "SaiFiCore",
      resources: [.process("Resources")]
    ),
    .executableTarget(
      name: "saifi-check",
      dependencies: ["SaiFiCore"]
    ),
    .testTarget(
      name: "SaiFiCoreTests",
      dependencies: ["SaiFiCore"],
      resources: [.process("Resources")]
    ),
  ]
)
