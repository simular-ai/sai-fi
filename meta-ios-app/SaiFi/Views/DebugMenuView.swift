#if DEBUG

import MWDATMockDevice
import SwiftUI

/// DEBUG overlay: MockDeviceKit, the Simulator stand-in for Ray-Ban Meta glasses.
struct DebugMenuView: View {
  @Bindable var debugMenuViewModel: DebugMenuViewModel
  var onMockGlassesChanged: () -> Void

  var body: some View {
    Button {
      debugMenuViewModel.showDebugMenu = true
    } label: {
      VStack(spacing: 2) {
        Image(systemName: "ladybug.fill")
          .font(.title2)
          .foregroundStyle(.white)
          .frame(width: 44, height: 44)
          .background(Color.accentColor)
          .clipShape(Circle())
          .shadow(radius: 4)
        Text("Mock glasses")
          .font(.caption2.weight(.semibold))
          .foregroundStyle(.primary)
          .padding(.horizontal, 6)
          .padding(.vertical, 2)
          .background(.thinMaterial, in: Capsule())
      }
    }
    .buttonStyle(.plain)
    .accessibilityLabel("Mock glasses")
    .accessibilityIdentifier("debug_menu_button")
    .padding(.trailing, 12)
    .padding(.bottom, 24)
    .sheet(isPresented: $debugMenuViewModel.showDebugMenu, onDismiss: onMockGlassesChanged) {
      MockDeviceKitView(
        viewModel: debugMenuViewModel.mockDeviceKitViewModel,
        onGlassesChanged: onMockGlassesChanged
      )
    }
  }
}

#endif
