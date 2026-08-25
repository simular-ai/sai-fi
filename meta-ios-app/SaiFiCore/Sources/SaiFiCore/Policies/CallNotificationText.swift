/* sai-fi — voice concierge. */

// What the call notification says, given the call's state.
//
// Split from the Notification objects on purpose: this part is a pure function of three booleans and
// a string, so it is readable — and assertable — without a framework. Pause dominates mute in every
// line: while paused there are no mic frames, so no keepalives, so the server's idle guard treats a
// long pause exactly like a walked-away call and ends it.
//
// Ported from Android `CallNotifications.kt` (`CallNotificationText`).

public enum CallNotificationText {
  public static func title(muted: Bool, paused: Bool) -> String {
    if paused { return "Sai is paused" }
    if muted { return "Sai is muted (still listening)" }
    return "Sai is listening"
  }

  public static func body(muted: Bool, paused: Bool, machineLabel: String) -> String {
    if paused { return "Paused — Sai can't hear you (a long pause ends the call)" }
    if muted { return "Muted — still listening, won't speak" }
    return "Listening — \(machineLabel)"
  }

  /// Label of the secondary action. While paused, "Mute" would do nothing worth offering.
  public static func secondaryAction(muted: Bool, paused: Bool) -> String {
    if paused { return "Resume" }
    if muted { return "Unmute" }
    return "Mute"
  }
}
