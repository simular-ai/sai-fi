/* sai-fi — Settings: the account, the one conversation setting, and developer mode. */

// A settings LIST, not another dashboard. Ported from Android `ui/SettingsScreen.kt`.

import SaiFiCore
import SwiftUI

struct SettingsView: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  private var s: CallCoordinator.State { app.call.state }

  var body: some View {
    VStack(spacing: 0) {
      ScreenHeader(title: "Settings", state: s)
      ScrollView {
        VStack(alignment: .leading, spacing: 20) {
          account
          conversation
          advanced
          troubleshooting
          buildFooter
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 32)
      }
    }
    .background(colors.background)
  }

  private var account: some View {
    VStack(alignment: .leading, spacing: 10) {
      GroupHeader(title: "Account")
      Text(app.userEmail ?? "Signed in")
        .font(.body)
        .lineLimit(1)
      Text("Signed in with Google")
        .font(.footnote)
        .foregroundStyle(colors.mutedForeground)
      Button("Sign out") { app.signOut() }
        .buttonStyle(.saiOutlined)
        .disabled(s.active)
      if s.active { Hint(summary: "End the call to sign out.") }
      SectionErrorAffordance(
        title: "Account error",
        message: app.authError,
        open: app.authErrorOpen,
        onOpen: { app.authErrorOpen = true },
        onDismiss: { app.authErrorOpen = false })
    }
  }

  private var conversation: some View {
    VStack(alignment: .leading, spacing: 10) {
      GroupHeader(title: "Conversation")
      if s.active {
        Text("Ask-first after \(app.askFirstThresholdSec)s")
        Hint(summary: "Settings lock during a call.")
      } else {
        AskFirstField(app: app)
        Hint(
          summary: "How long Sai works before checking back with you.",
          detail:
            "Below this, Sai finishes the job and tells you when it's done. Above it, Sai "
            + "comes back to confirm before carrying on — so a short value means more "
            + "interruptions and more control, and a long one means fewer of both. Zero "
            + "means check with you about everything.")
      }
    }
  }

  private var advanced: some View {
    VStack(alignment: .leading, spacing: 10) {
      GroupHeader(title: "Advanced")
      HStack(alignment: .center, spacing: 12) {
        VStack(alignment: .leading, spacing: 4) {
          Text("Developer mode")
          Text(
            "Shows the Logs tab: the live transcript, the raw event stream, and a text "
              + "composer for talking to Sai without speaking."
          )
          .font(.footnote)
          .foregroundStyle(colors.mutedForeground)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        Toggle("", isOn: Binding(get: { app.devMode }, set: { app.onDevModeChanged($0) }))
          .labelsHidden()
          .tint(colors.green)
      }
    }
  }

  private var troubleshooting: some View {
    VStack(alignment: .leading, spacing: 10) {
      GroupHeader(title: "Troubleshooting")
      Faq(
        question: "Sai can't take photos, or says the camera was denied",
        answer:
          "Meta AI can grant the camera just once, and a pending glasses firmware update can "
          + "take it away until the update finishes — so a grant that worked yesterday may "
          + "not hold today. Open the Meta AI app, let any glasses update complete, then come "
          + "back to Home: \"Grant glasses camera\" reappears under Connection once this app "
          + "notices the permission is gone. If it is greyed out, the glasses aren't linked — "
          + "turn them on first, because Meta AI can't grant a permission for a device it "
          + "can't see.")
      Faq(
        question: "Sai never hears me, or I never hear Sai",
        answer:
          "Check the Audio line on Home for the route it picked. Glasses audio needs the "
          + "glasses connected before you start the call; otherwise it uses the phone. If "
          + "only one direction is broken it is almost always the route, not the call — end "
          + "it, reconnect the glasses, and start again.")
      Faq(
        question: "Sai talks over itself, or stops for no reason",
        answer:
          "Its own voice is reaching the microphone. Turn the volume down, and if a laptop or "
          + "speaker is playing the call aloud, point it away from you. On the phone route, "
          + "wired headphones rule it out entirely.")
      Faq(
        question: "Sai says my computer hasn't picked something up",
        answer:
          "The machine is asleep or offline. Sai wakes it when it can, and a cloud machine "
          + "takes about a minute — but a machine of your own that is switched off can't be "
          + "woken remotely at all. Check it in the Sai app.")
      Faq(
        question: "I asked for something and never heard back",
        answer:
          "If you were quiet for a while, Sai holds the result rather than interrupting — say "
          + "anything and it will offer it. \"Ask first after\" above is that delay; raise it "
          + "and Sai reports straight away instead.")
      Faq(
        question: "The call ended on its own",
        answer:
          "Five minutes of silence ends a call to save battery, and so does an hour of talking. "
          + "Sai says which before it hangs up. Tap to start again.")
      Faq(
        question: "Nothing works, and I want to report it",
        answer:
          "The two lines at the bottom of this screen are what a bug report needs: the build, "
          + "and the server it is talking to. Turn on Developer mode above and the Logs tab "
          + "keeps a transcript you can read back.")
    }
  }

  private var buildFooter: some View {
    let info = Bundle.main.infoDictionary
    let version = info?["CFBundleShortVersionString"] as? String ?? "?"
    let build = info?["CFBundleVersion"] as? String ?? "?"
    #if DEBUG
    let kind = "debug"
    #else
    let kind = "release"
    #endif
    return VStack(alignment: .leading, spacing: 4) {
      Rectangle()
        .fill(colors.border)
        .frame(height: 1)
        .padding(.bottom, 8)
      Text("sai-fi \(version) (\(build)) · \(kind)")
        .font(.footnote)
        .foregroundStyle(colors.mutedForeground)
      Text(Secrets.saiApiUrl)
        .font(.footnote)
        .foregroundStyle(colors.mutedForeground)
        .lineLimit(1)
      if !Secrets.saiVersionTag.isEmpty {
        Text("pinned to \(Secrets.saiVersionTag)")
          .font(.footnote)
          .foregroundStyle(colors.mutedForeground)
      }
    }
  }
}

private struct Faq: View {
  @Environment(\.saiColors) private var colors
  let question: String
  let answer: String
  @State private var open = false

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Button {
        open.toggle()
      } label: {
        HStack(alignment: .center) {
          Text(question)
            .font(.body)
            .foregroundStyle(colors.foreground)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
          Text(open ? "−" : "+")
            .font(.body)
            .foregroundStyle(colors.mutedForeground)
        }
      }
      .buttonStyle(.plain)
      if open {
        Text(answer)
          .font(.footnote)
          .foregroundStyle(colors.mutedForeground)
      }
    }
    .padding(.vertical, 6)
  }
}

private struct AskFirstField: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  @FocusState private var focused: Bool
  @State private var commits = 0
  @State private var edited = false
  @State private var showSaved = false

  var body: some View {
    let current = Int(app.askFirstThresholdSec)
    VStack(alignment: .leading, spacing: 8) {
      HStack(spacing: 8) {
        Button {
          app.nudgeAskFirstSec(up: false)
          edited = false
          commits += 1
        } label: {
          Image(systemName: "minus")
            .frame(width: 48, height: 48)
        }
        .buttonStyle(.saiOutlined)
        .disabled(current.map { $0 <= AskFirst.minSec } ?? false)
        .accessibilityLabel("Less time")

        TextField("Ask first after (s)", text: Binding(
          get: { app.askFirstThresholdSec },
          set: {
            edited = true
            app.onAskFirstSecChanged($0)
          }
        ))
        .keyboardType(.numberPad)
        .focused($focused)
        .textFieldStyle(.roundedBorder)
        .onSubmit { commit() }
        .onChange(of: focused) { _, on in
          if !on && edited { commit() }
        }

        Button {
          app.nudgeAskFirstSec(up: true)
          edited = false
          commits += 1
        } label: {
          Image(systemName: "plus")
            .frame(width: 48, height: 48)
        }
        .buttonStyle(.saiOutlined)
        .disabled(current.map { $0 >= AskFirst.maxSec } ?? false)
        .accessibilityLabel("More time")
      }
      if showSaved {
        HStack(spacing: 4) {
          Image(systemName: "checkmark")
            .font(.caption)
            .foregroundStyle(colors.green)
          Text("Saved")
            .font(.subheadline.weight(.medium))
            .foregroundStyle(colors.green)
        }
      }
    }
    .onChange(of: commits) { _, _ in
      guard commits > 0 else { return }
      showSaved = true
      Task {
        try? await Task.sleep(for: .milliseconds(1_600))
        showSaved = false
      }
    }
  }

  private func commit() {
    app.commitAskFirstSec()
    edited = false
    commits += 1
    focused = false
  }
}
