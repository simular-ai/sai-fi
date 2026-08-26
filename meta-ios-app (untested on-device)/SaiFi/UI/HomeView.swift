/* sai-fi — Home: the connection, the machine, and the call. */

// Three cards in the order a call needs them: Connection, Machine, Call.
// Ported from Android `ui/HomeScreen.kt`.

import MWDATCore
import SaiFiCore
import SwiftUI

struct HomeView: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  private var s: CallCoordinator.State { app.call.state }

  var body: some View {
    VStack(spacing: 0) {
      ScreenHeader(title: "Sai-Fi", state: s)
      ScrollView {
        VStack(spacing: 12) {
          connectionCard
          machineCard
          callCard
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 24)
      }
    }
    .background(colors.background)
    .onChange(of: s.machineId) { _, id in
      if s.active, let id {
        if let m = app.machines.first(where: { $0.machineId == id }) {
          app.selectedMachine = m
        }
      }
    }
  }

  private var connectionCard: some View {
    Section(title: "Connection") {
      HStack(alignment: .top, spacing: 8) {
        VStack(alignment: .leading, spacing: 4) {
          Text("Meta DAT registration: \(app.glassesReg?.saiLabel ?? "checking…")")
            .font(.footnote)
            .accessibilityIdentifier("glasses-registration")
          Text(
            "Glasses: "
              + {
                switch app.glassesLinked {
                case true: "connected"
                case false: "not connected"
                case nil: "checking…"
                }
              }()
          )
          .font(.footnote)
          Text("Audio: \(s.routeStatus.isEmpty ? "phone" : s.routeStatus)")
            .font(.footnote)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        if app.glassesReg != .registered {
          Button("Register glasses") { app.registerGlasses() }
            .buttonStyle(.saiOutlined)
        } else if !app.glassesCameraGranted {
          Button("Grant glasses camera") { app.requestGlassesCamera() }
            .buttonStyle(.saiOutlined)
            .disabled(app.glassesLinked == false)
        }
      }
      if app.glassesReg == .registered && !app.glassesCameraGranted && app.glassesLinked == false {
        Text("Turn glasses on to grant camera (Meta AI needs a linked device).")
          .font(.footnote)
      }
      if app.glassesReg != .registered {
        Hint(
          summary: "Registration is what lets Sai-Fi reach your glasses.",
          detail:
            "It runs through the Meta AI app, which needs Developer Mode on, and it is "
            + "separate from Bluetooth pairing and from signing in to Sai. Only ONE "
            + "third-party app can be registered at a time, so doing this unregisters "
            + "whatever else you had.")
      }
      SectionErrorAffordance(
        title: "Glasses error",
        message: app.glassesError,
        open: app.glassesErrorOpen,
        onOpen: { app.glassesErrorOpen = true },
        onDismiss: { app.glassesErrorOpen = false })
    }
  }

  private var machineCard: some View {
    Section(title: "Machine") {
      let dropdownEnabled = app.machinesFetchOk && !app.machines.isEmpty
      let displayedLabel =
        s.active
        ? (s.machineLabel ?? app.selectedMachine?.label ?? "")
        : (app.selectedMachine?.label ?? "")
      let machineLabel =
        app.machinesFetchOk ? "Machine (\(app.machines.count) found)" : "Machine"
      Menu {
        ForEach(app.machines, id: \.machineId) { m in
          Button(m.label) {
            app.selectedMachine = m
            Prefs.setMachineId(m.machineId)
            if s.active { app.call.switchMachine(machineId: m.machineId) }
          }
        }
      } label: {
        HStack {
          VStack(alignment: .leading, spacing: 2) {
            Text(machineLabel).font(.caption).foregroundStyle(colors.mutedForeground)
            Text(displayedLabel.isEmpty ? (dropdownEnabled ? "Select machine" : "—") : displayedLabel)
              .foregroundStyle(dropdownEnabled ? colors.foreground : colors.mutedForeground)
          }
          Spacer()
          Image(systemName: "chevron.up.chevron.down")
            .font(.footnote)
            .foregroundStyle(colors.mutedForeground)
        }
        .padding(12)
        .overlay(
          RoundedRectangle(cornerRadius: 8, style: .continuous)
            .strokeBorder(colors.borderStrong, lineWidth: 1))
      }
      .disabled(!dropdownEnabled)
      HStack {
        if !app.machinesInfo.isEmpty {
          Text(app.machinesInfo).font(.footnote)
        }
        Spacer()
        Button("Reload") { app.loadMachines() }
          .buttonStyle(.saiOutlined)
      }
      SectionErrorAffordance(
        title: "Machines error",
        message: app.machinesError,
        open: app.machinesErrorOpen,
        onOpen: { app.machinesErrorOpen = true },
        onDismiss: { app.machinesErrorOpen = false })
    }
  }

  private var callCard: some View {
    Section(title: "Call") {
      CallControls(app: app)
      SectionErrorAffordance(
        title: "Sign-in error",
        message: app.authError,
        open: app.authErrorOpen,
        onOpen: { app.authErrorOpen = true },
        onDismiss: { app.authErrorOpen = false })
      if s.active {
        if s.paused {
          Hint(
            summary: "Paused — the mic is off, so Sai hears nothing. A long pause ends the call."
              + (s.saiMuted ? " Sai will come back muted when you resume." : ""))
        } else if s.saiMuted {
          Hint(
            summary:
              "Muted — Sai still hears you and keeps working, it just won't speak. "
              + "Anything that finishes while muted is held and offered after you unmute.")
        }
        Hint(summary: "Folding the glasses, taking them off, or losing Bluetooth ends the call.")
        if s.capturing {
          Text(s.capture == nil ? "Taking a photo…" : "Taking a new photo…")
            .font(.footnote)
            .foregroundStyle(colors.brand)
        }
        if let capture = s.capture { CaptureThumbnail(capture: capture) }
      } else if !app.machinesFetchOk {
        Hint(
          summary: app.machinesError != nil
            ? "Couldn't reach your machines. \(app.machinesInfo)".trimmingCharacters(in: .whitespaces)
            : "Sai needs your machine list before it can start a call.",
          detail:
            "The list comes from the Sai API, so this needs a working connection to it. "
            + "Nothing about the glasses matters yet.")
      } else if app.selectedMachine == nil {
        Hint(summary: "Pick a machine in Machine above before starting a call.")
      } else if app.glassesLinked == false {
        Hint(
          summary: "Glasses aren't connected — the call runs on phone audio.",
          detail:
            "The call will run on phone/Bluetooth audio, but the temple button and photo "
            + "capture won't work until the glasses are on, unfolded, in range, and "
            + "registered. A \"no eligible device\" error means the glasses aren't paired "
            + "for this app yet.")
      } else if !app.glassesCameraGranted {
        Hint(
          summary: "Camera isn't granted — Sai can't see or take photos on this call.",
          detail:
            "Audio and the temple button work. Anything that needs the glasses camera — "
            + "\"take a photo\", or a question about what you're looking at — will fail "
            + "until you use \"Grant glasses camera\" in Connection above.")
      }
      if !s.status.isEmpty {
        Text(s.status)
          .font(.body)
          .foregroundStyle(colors.mutedForeground)
      }
    }
  }
}

private struct CallControls: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  private var s: CallCoordinator.State { app.call.state }

  var body: some View {
    VStack(spacing: 8) {
      HStack(spacing: 8) {
        Button {
          app.call.capturePhoto()
        } label: {
          label("camera.fill", "Capture view")
        }
        .buttonStyle(.saiOutlined)
        .disabled(!(s.active && app.glassesReg == .registered && app.glassesCameraGranted))

        Button {
          app.call.toggleMute()
        } label: {
          label(s.saiMuted ? "speaker.wave.2.fill" : "speaker.slash.fill",
                s.saiMuted ? "Unmute Sai" : "Mute Sai")
        }
        .buttonStyle(.saiFilled)
        .disabled(!(s.active && !s.paused))
      }
      HStack(spacing: 8) {
        Button {
          app.call.togglePause()
        } label: {
          label(s.paused ? "mic.fill" : "mic.slash.fill",
                s.paused ? "Resume call" : "Pause call")
        }
        .buttonStyle(.saiOutlined)
        .disabled(!s.active)

        if s.active {
          Button {
            app.call.stop()
          } label: {
            label("phone.down.fill", "End call")
          }
          .buttonStyle(.saiDestructive)
        } else {
          Button {
            app.onStartClicked()
          } label: {
            label("phone.fill", "Start call")
          }
          .buttonStyle(.saiFilled)
          .disabled(!(app.machinesFetchOk && app.selectedMachine != nil))
        }
      }
    }
  }

  private func label(_ system: String, _ title: String) -> some View {
    HStack(spacing: 6) {
      Image(systemName: system)
      Text(title)
    }
    .frame(maxWidth: .infinity)
    .frame(height: 48)
  }
}

extension ButtonStyle where Self == SaiOutlinedButtonStyle {
  static var saiOutlined: SaiOutlinedButtonStyle { SaiOutlinedButtonStyle() }
}

extension ButtonStyle where Self == SaiFilledButtonStyle {
  static var saiFilled: SaiFilledButtonStyle { SaiFilledButtonStyle() }
}

extension ButtonStyle where Self == SaiDestructiveButtonStyle {
  static var saiDestructive: SaiDestructiveButtonStyle { SaiDestructiveButtonStyle() }
}

struct SaiOutlinedButtonStyle: ButtonStyle {
  @Environment(\.saiColors) private var colors
  @Environment(\.isEnabled) private var isEnabled
  func makeBody(configuration: ButtonStyle.Configuration) -> some View {
    configuration.label
      .font(.body.weight(.medium))
      .padding(.horizontal, 12)
      .padding(.vertical, 8)
      .foregroundStyle((isEnabled ? colors.foreground : colors.mutedForeground).opacity(configuration.isPressed ? 0.7 : 1))
      .overlay(
        RoundedRectangle(cornerRadius: 8, style: .continuous)
          .strokeBorder(colors.borderStrong, lineWidth: 1))
  }
}

struct SaiFilledButtonStyle: ButtonStyle {
  @Environment(\.saiColors) private var colors
  @Environment(\.isEnabled) private var isEnabled
  func makeBody(configuration: ButtonStyle.Configuration) -> some View {
    configuration.label
      .font(.body.weight(.medium))
      .foregroundStyle(colors.primaryForeground.opacity(isEnabled ? 1 : 0.45))
      .background(colors.primary.opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4))
      .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
  }
}

struct SaiDestructiveButtonStyle: ButtonStyle {
  @Environment(\.saiColors) private var colors
  func makeBody(configuration: ButtonStyle.Configuration) -> some View {
    configuration.label
      .font(.body.weight(.medium))
      .foregroundStyle(colors.destructiveForeground)
      .background(colors.destructive.opacity(configuration.isPressed ? 0.85 : 1))
      .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
  }
}
