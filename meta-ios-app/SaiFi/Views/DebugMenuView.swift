#if DEBUG

import MWDATMockDevice
import SwiftUI

/// DEBUG overlay. Three buttons, trailing edge:
///   • ladybug — MockDeviceKit (the Simulator stand-in for Ray-Ban Meta glasses)
///   • antenna — Gemini Live harness (Mac mic, no FSM)
///   • waveform — HFP duplex spike (meant for a real phone; no Bluetooth here)
struct DebugMenuView: View {
  @Bindable var debugMenuViewModel: DebugMenuViewModel
  var onMockGlassesChanged: () -> Void

  var body: some View {
    VStack(spacing: 10) {
      debugButton(
        system: "ladybug.fill",
        title: "Mock glasses",
        identifier: "debug_menu_button"
      ) {
        debugMenuViewModel.showDebugMenu = true
      }
      debugButton(
        system: "antenna.radiowaves.left.and.right.circle.fill",
        title: "Live harness",
        identifier: "live_harness_button"
      ) {
        debugMenuViewModel.showLiveHarness = true
      }
      debugButton(
        system: "waveform.circle.fill",
        title: "HFP spike",
        identifier: "hfp_spike_button"
      ) {
        debugMenuViewModel.showHfpSpike = true
      }
    }
    .padding(.trailing, 12)
    .padding(.bottom, 24)
    .sheet(isPresented: $debugMenuViewModel.showDebugMenu, onDismiss: onMockGlassesChanged) {
      MockDeviceKitView(
        viewModel: debugMenuViewModel.mockDeviceKitViewModel,
        onGlassesChanged: onMockGlassesChanged
      )
    }
    .sheet(isPresented: $debugMenuViewModel.showHfpSpike) {
      HfpSpikeView()
    }
    .sheet(isPresented: $debugMenuViewModel.showLiveHarness) {
      LiveHarnessView()
    }
  }

  private func debugButton(
    system: String,
    title: String,
    identifier: String,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      VStack(spacing: 2) {
        Image(systemName: system)
          .font(.title2)
          .foregroundStyle(.white)
          .frame(width: 44, height: 44)
          .background(Color.accentColor)
          .clipShape(Circle())
          .shadow(radius: 4)
        Text(title)
          .font(.caption2.weight(.semibold))
          .foregroundStyle(.primary)
          .padding(.horizontal, 6)
          .padding(.vertical, 2)
          .background(.thinMaterial, in: Capsule())
      }
    }
    .buttonStyle(.plain)
    .accessibilityLabel(title)
    .accessibilityIdentifier(identifier)
  }
}

#endif
