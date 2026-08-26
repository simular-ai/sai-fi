/*
 * sai-fi — voice concierge.
 */

// Main entry point. Wearables.configure, SaiAuth.initialize, then the Sai shell. The CameraAccess
// sample screens are no longer the user-facing app; DEBUG still overlays MockDeviceKit plus the
// HFP / Live harness buttons.

import Foundation
import MWDATCore
import SwiftUI

#if DEBUG
import MWDATMockDevice
#endif

@main
struct SaiFiApp: App {
  #if DEBUG
  @State private var debugMenuViewModel: DebugMenuViewModel
  #endif
  @State private var appModel: AppModel
  @Environment(\.scenePhase) private var scenePhase

  init() {
    // The XCTest *host* is this @main. Leave DAT/Firebase alone so unit tests can
    // `Wearables.configure()` themselves — a failed configure here used to poison
    // `Wearables.shared` for the suite. UI tests launch a separate process that must still
    // configure; they pass `--ui-testing` and may inherit XCTest env vars from the runner.
    let isUnitTestHost =
      ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
      && !ProcessInfo.processInfo.arguments.contains("--ui-testing")
    var ready = false
    if !isUnitTestHost {
      do {
        try Wearables.configure()
        ready = true
      } catch {
        #if DEBUG
        NSLog("[SaiFi] Failed to configure Wearables SDK: \(error)")
        #endif
      }
      SaiAuth.initialize()
    }

    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
      MockDeviceKit.shared.enable(config: MockDeviceKitConfig(initiallyRegistered: false))
      let portFilePath = ProcessInfo.processInfo.environment["MWDAT_TEST_SERVER_PORT_FILE"]
      Task {
        try await MockDeviceKit.shared.startTestServer(portFilePath: portFilePath)
      }
    }
    self._debugMenuViewModel = State(
      wrappedValue: DebugMenuViewModel(mockDeviceKit: MockDeviceKit.shared))
    #endif

    _appModel = State(wrappedValue: AppModel(datReady: ready))
  }

  var body: some Scene {
    WindowGroup {
      SaiTheme {
        RootView(app: appModel)
          .onOpenURL { appModel.handleOpenURL($0) }
          .onChange(of: scenePhase) { _, phase in
            if phase == .active { appModel.sceneBecameActive() }
          }
          #if DEBUG
          .overlay(alignment: .bottomTrailing) {
            DebugMenuView(
              debugMenuViewModel: debugMenuViewModel,
              onMockGlassesChanged: { appModel.refreshAfterMockSetup() }
            )
          }
          #endif
      }
    }
  }
}
