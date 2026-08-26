/* sai-fi — the sign-in gate. */

// The whole screen when nobody is signed in, and the only thing reachable from it. No tabs, no
// bottom bar, no scroll.
//
// Ported from Android `ui/SignInScreen.kt`.

import SwiftUI

private enum GoogleButton {
  static let lightContainer = Color(hex: 0xFFFFFF)
  static let lightLabel = Color(hex: 0x1F1F1F)
  static let lightBorder = Color(hex: 0x747775)
  static let darkContainer = Color(hex: 0x131314)
  static let darkLabel = Color(hex: 0xE3E3E3)
  static let darkBorder = Color(hex: 0x8E918F)
}

struct SignInView: View {
  @Environment(\.saiColors) private var colors
  @Environment(\.colorScheme) private var colorScheme
  @Bindable var app: AppModel

  var body: some View {
    VStack(spacing: 0) {
      Spacer()
      Image(.saiLogo)
        .resizable()
        .scaledToFit()
        .frame(width: 96, height: 96)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
      Spacer().frame(height: 20)
      Text("Sai-Fi").font(.largeTitle.weight(.semibold))
      Spacer().frame(height: 12)
      Rectangle()
        .fill(colors.green)
        .frame(width: 56, height: 2)
      Spacer().frame(height: 12)
      Text("Sai on your glasses.")
        .font(.body)
        .foregroundStyle(colors.mutedForeground)
        .multilineTextAlignment(.center)
      Spacer().frame(height: 36)

      if SaiAuth.isConfigured {
        GoogleSignInButton(action: app.signIn)
      } else {
        GoogleSignInButton(action: {}, enabled: false)
        Spacer().frame(height: 12)
        Text(
          "This build has no Firebase configuration. Set firebase_app_id, firebase_api_key, "
            + "firebase_project_id and web_client_id in Secrets.xcconfig, then rebuild."
        )
        .font(.footnote)
        .foregroundStyle(colors.mutedForeground)
        .multilineTextAlignment(.center)
      }
      #if DEBUG
      Spacer().frame(height: 16)
      Button("Continue without account") { app.continueWithoutAccount() }
        .font(.subheadline.weight(.medium))
        .foregroundStyle(colors.brand)
      Text("Simulator / MockDeviceKit — Gemini still needs GEMINI_API_KEY. Agent calls need a real sign-in.")
        .font(.caption)
        .foregroundStyle(colors.mutedForeground)
        .multilineTextAlignment(.center)
      #endif

      Spacer().frame(height: 16)
      SectionErrorAffordance(
        title: "Sign-in error",
        message: app.authError,
        open: app.authErrorOpen,
        onOpen: { app.authErrorOpen = true },
        onDismiss: { app.authErrorOpen = false })
      Spacer()
    }
    .padding(.horizontal, 32)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(colors.background)
  }
}

/// "Sign in with Google", to Google's spec: their container/label/border per mode, their mark at
/// 18pt, a pill, and a 40pt minimum height. Label is the system font rather than Roboto Medium.
private struct GoogleSignInButton: View {
  @Environment(\.colorScheme) private var colorScheme
  let action: () -> Void
  var enabled: Bool = true

  var body: some View {
    let dark = colorScheme == .dark
    let container = dark ? GoogleButton.darkContainer : GoogleButton.lightContainer
    let label = dark ? GoogleButton.darkLabel : GoogleButton.lightLabel
    let border = dark ? GoogleButton.darkBorder : GoogleButton.lightBorder
    Button(action: action) {
      HStack(spacing: 12) {
        GoogleGMark().frame(width: 18, height: 18)
        Text("Sign in with Google")
          .font(.body.weight(.medium))
      }
      .frame(maxWidth: 320)
      .frame(minHeight: 40)
      .foregroundStyle(label.opacity(enabled ? 1 : 0.45))
    }
    .buttonStyle(.plain)
    .background(container.opacity(enabled ? 1 : 0.45))
    .clipShape(Capsule())
    .overlay(Capsule().strokeBorder(border.opacity(enabled ? 1 : 0.45), lineWidth: 1))
    .disabled(!enabled)
  }
}
