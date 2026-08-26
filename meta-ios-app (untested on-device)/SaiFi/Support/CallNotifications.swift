/* sai-fi — voice concierge. */

// The call's notification: on iOS, only the dismissible "why it ended" one.
//
// There is no ongoing-call notification analogue. Background survival is `UIBackgroundModes: audio`
// plus a continuously active `AVAudioSession`. A Live Activity is the real analogue and is
// explicitly out of scope. The wording for an ongoing card still lives in SaiFiCore's
// `CallNotificationText` so the strings stay pinned; this type just posts the ended-reason banner.
//
// Ported from Android `CallNotifications.kt` (the `endedReason` half).

import Foundation
import UserNotifications

enum CallNotifications {
  static let categoryId = "sai_voice_call_ended"

  /// Why the call ended (out of credits / voice off / access denied).
  ///
  /// For a screen-free user with no Live audio left to speak the reason, this is the only durable
  /// surface there is.
  static func endedReason(_ reason: String) async {
    let center = UNUserNotificationCenter.current()
    let settings = await center.notificationSettings()
    guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    else { return }

    let content = UNMutableNotificationContent()
    content.title = "Sai voice call ended"
    content.body = reason
    content.sound = .default
    content.categoryIdentifier = categoryId

    let request = UNNotificationRequest(
      identifier: "sai.ended.\(UUID().uuidString)",
      content: content,
      trigger: nil)
    try? await center.add(request)
  }

  /// Ask once, at a moment the user is looking at the phone (sign-in / first call). Failure is
  /// silent: a declined prompt just means the ended-reason banner will not appear.
  static func requestAuthorizationIfNeeded() async {
    let center = UNUserNotificationCenter.current()
    let settings = await center.notificationSettings()
    guard settings.authorizationStatus == .notDetermined else { return }
    _ = try? await center.requestAuthorization(options: [.alert, .sound])
  }
}
