/* sai-fi — voice concierge. */

// Build-time configuration, read at runtime.
//
// The iOS counterpart of Android's `BuildConfig`. The chain is
// `Config.xcconfig` (+ the untracked `Secrets.xcconfig`) → Info.plist → here.
//
// THE FAILURE DISCIPLINE IS COPIED DELIBERATELY. A missing key is never a build error: it becomes an
// empty string, and the code that needs it fails at runtime with a NAMED line — `start failed: no
// gemini_api_key` — rather than a mysterious network error twenty seconds later. That was worth
// keeping because it is what makes a half-configured build diagnosable by the person holding the
// phone rather than only by whoever wrote the build script.
//
// `missing` exists so a screen can say which keys are absent instead of showing a button that cannot
// work — the same thing SignInScreen does on Android when Firebase is unconfigured.

import Foundation

public enum Secrets {

  // ── Meta AI registration ───────────────────────────────────────────────────

  /// `0` while Developer Mode is on, which is the whole local loop.
  public static var metaAppID: String { string("MWDAT", "MetaAppID") }
  public static var clientToken: String { string("MWDAT", "ClientToken") }

  // ── the user's own Gemini key ──────────────────────────────────────────────

  /// The app opens the Live session directly with this. Audio never touches a Simular server, and
  /// there is no server-minted token.
  public static var geminiApiKey: String { string("SaiFi", "GeminiApiKey") }

  // ── the Sai agent API ──────────────────────────────────────────────────────

  /// Reaches the user's AGENT. The voice conversation needs nothing from it.
  public static var saiApiUrl: String { string("SaiFi", "SaiApiUrl") }

  /// Optional. Pins the app to one server revision via `x-sai-version`.
  public static var saiVersionTag: String { string("SaiFi", "SaiVersionTag") }

  // ── Google sign-in ─────────────────────────────────────────────────────────

  public static var firebaseAppID: String { string("SaiFi", "FirebaseAppId") }
  public static var firebaseApiKey: String { string("SaiFi", "FirebaseApiKey") }
  public static var firebaseProjectID: String { string("SaiFi", "FirebaseProjectId") }
  public static var firebaseGcmSenderID: String { string("SaiFi", "FirebaseGcmSenderId") }
  public static var iosClientID: String { string("SaiFi", "IosClientId") }

  /// The OAuth **web** client — GoogleSignIn's `serverClientID`, and the audience for an ID token the
  /// Sai API can verify. Unchanged from Android; everything else here is iOS-specific.
  public static var webClientID: String { string("SaiFi", "WebClientId") }

  // ── diagnosis ──────────────────────────────────────────────────────────────

  /// Firebase's iOS SDK traps on `configure` if `GOOGLE_APP_ID` is an Android id
  /// (`1:…:android:…`). Copied `local.properties` values are not enough.
  public static var firebaseAppIDIsIOS: Bool {
    firebaseAppID.contains(":ios:")
  }

  /// Whether Google sign-in can work at all. Four values, all required — and the app id must
  /// be the **iOS** one, or `FirebaseApp.configure` kills the process.
  public static var firebaseConfigured: Bool {
    firebaseAppIDIsIOS && !firebaseApiKey.isEmpty && !firebaseProjectID.isEmpty
      && !webClientID.isEmpty
  }

  /// The names of the keys a build is missing, for a screen that has to explain itself.
  public static func missing() -> [String] {
    var absent: [String] = []
    if geminiApiKey.isEmpty { absent.append("gemini_api_key") }
    if saiApiUrl.isEmpty { absent.append("sai_api_url") }
    if !firebaseAppIDIsIOS { absent.append("firebase_app_id (iOS)") }
    if firebaseApiKey.isEmpty { absent.append("firebase_api_key") }
    if firebaseProjectID.isEmpty { absent.append("firebase_project_id") }
    if webClientID.isEmpty { absent.append("web_client_id") }
    return absent
  }

  // ── plumbing ───────────────────────────────────────────────────────────────

  /// A string from a dictionary in Info.plist, or "".
  ///
  /// Trimmed, because an xcconfig value that expands to nothing arrives here as whitespace rather than
  /// as an empty string often enough to matter — and " " is not a usable API key but is very much a
  /// non-empty one.
  private static func string(_ section: String, _ key: String) -> String {
    guard
      let dict = Bundle.main.object(forInfoDictionaryKey: section) as? [String: Any],
      let value = dict[key] as? String
    else { return "" }
    return value.trimmingCharacters(in: .whitespacesAndNewlines)
  }
}
