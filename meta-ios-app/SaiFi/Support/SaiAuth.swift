/* sai-fi — user authentication (Google Sign-In → Firebase ID token). */

// SaiAuth — the app's user credential for the Sai API. The user signs in with Google through
// GoogleSignIn; Firebase Auth turns that into a session, and we send a fresh Firebase **ID token**
// as the `Authorization: Bearer` on every cloud-api call. The API verifies the ID token exactly as
// for the web/desktop app — no compiled-in key.
//
// Setup (once, in Secrets.xcconfig — values from the simular Firebase **iOS** app, not the Android
// ones): firebase_app_id, firebase_api_key, firebase_project_id, web_client_id (the *Web* OAuth
// client id, passed as GoogleSignIn's serverClientID so Firebase accepts the token). There is no
// GoogleService-Info.plist, matching Android's decision to have no google-services.json.
//
// Ported from Android `SaiAuth.kt`.

import FirebaseAuth
import FirebaseCore
import Foundation
import GoogleSignIn
import UIKit

enum SaiAuth {
  /// True once the Firebase config is present (Secrets.xcconfig filled in).
  static var isConfigured: Bool { Secrets.firebaseConfigured }

  /// Initialize Firebase from xcconfig. Idempotent; no-op if unconfigured.
  static func initialize() {
    guard isConfigured, FirebaseApp.app() == nil else {
      configureGoogleSignIn()
      return
    }
    let options = FirebaseOptions(
      googleAppID: Secrets.firebaseAppID,
      gcmSenderID: Secrets.firebaseGcmSenderID.isEmpty ? "0" : Secrets.firebaseGcmSenderID)
    options.apiKey = Secrets.firebaseApiKey
    options.projectID = Secrets.firebaseProjectID
    FirebaseApp.configure(options: options)
    configureGoogleSignIn()
  }

  private static func configureGoogleSignIn() {
    let ios = Secrets.iosClientID
    guard !ios.isEmpty else { return }
    GIDSignIn.sharedInstance.configuration = GIDConfiguration(
      clientID: ios,
      serverClientID: Secrets.webClientID.isEmpty ? nil : Secrets.webClientID)
  }

  static func isSignedIn() -> Bool {
    guard FirebaseApp.app() != nil else { return false }
    return Auth.auth().currentUser != nil
  }

  static func email() -> String? {
    guard FirebaseApp.app() != nil else { return nil }
    return Auth.auth().currentUser?.email
  }

  /// Show the Google sign-in sheet, then exchange the returned Google ID token for a Firebase
  /// session. Throws on cancellation/failure. Needs a presenting view controller — the sheet is UI.
  @MainActor
  static func signInWithGoogle(presenting viewController: UIViewController) async throws {
    if GIDSignIn.sharedInstance.configuration == nil { configureGoogleSignIn() }
    guard GIDSignIn.sharedInstance.configuration != nil else {
      throw SaiAuthError.missingIosClientID
    }
    let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: viewController)
    guard let idToken = result.user.idToken?.tokenString else {
      throw SaiAuthError.missingGoogleIDToken
    }
    let credential = GoogleAuthProvider.credential(
      withIDToken: idToken,
      accessToken: result.user.accessToken.tokenString)
    _ = try await Auth.auth().signIn(with: credential)
  }

  /// A **fresh** Firebase ID token for the Bearer header, or nil if signed out / unconfigured.
  /// Fetch one per cloud-api call that starts a session — ID tokens expire ~1h and `getIDToken()`
  /// auto-refreshes when needed.
  ///
  /// `false` = don't force a network round-trip; the SDK still refreshes an expired token by itself,
  /// which is what every caller wants.
  static func idToken() async -> String? {
    guard FirebaseApp.app() != nil, let user = Auth.auth().currentUser else { return nil }
    return try? await user.getIDToken()
  }

  /// Sign out of Firebase and GoogleSignIn so the next sign-in re-prompts.
  static func signOut() {
    if FirebaseApp.app() != nil {
      try? Auth.auth().signOut()
    }
    GIDSignIn.sharedInstance.signOut()
  }

  /// GoogleSignIn's URL callback. True if this URL was theirs.
  static func handleURL(_ url: URL) -> Bool {
    GIDSignIn.sharedInstance.handle(url)
  }
}

enum SaiAuthError: Error, LocalizedError {
  case missingIosClientID
  case missingGoogleIDToken

  var errorDescription: String? {
    switch self {
    case .missingIosClientID:
      return "ios_client_id is empty — set it in Secrets.xcconfig and rebuild"
    case .missingGoogleIDToken:
      return "Google Sign-In returned no ID token"
    }
  }
}

@MainActor
enum KeyWindow {
  /// The top-most presented controller, which GoogleSignIn needs to present its sheet.
  static var topController: UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
    var vc = window?.rootViewController
    while let presented = vc?.presentedViewController { vc = presented }
    return vc
  }
}
