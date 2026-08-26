/* sai-fi — small persisted app preferences. */

// Prefs — a thin UserDefaults wrapper. Two kinds of thing live here: the last-selected machine
// (so the app defaults to it on the next launch instead of a hardcoded machineId — set from the UI
// picker, on call start, and on a voice `switchMachine`, so "talk to concierge to switch" also
// sticks), and the two user settings the Settings tab owns. The one-shot prompt flags are the
// remainder.
//
// Callers read eagerly and mirror into SwiftUI state themselves. There is one reader per value and
// it reads at startup, so an observable wrapper would be machinery around a single call.
//
// Ported from Android `Prefs.kt`. Same key names. The suite is `sai_glasses` — the Android file
// name — so a rename here would silently drop the stored machine selection the way renaming that
// file would.

import Foundation

public enum Prefs {
  /// Kept from the app's earlier name: the suite is the on-disk key, matching Android's filename.
  public static let suiteName = "sai_glasses"

  static var store: UserDefaults = UserDefaults(suiteName: suiteName) ?? .standard

  private static let keyMachineId = "machineId"
  private static let keyGlassesCameraAutoPrompted = "glassesCameraAutoPrompted"
  private static let keyGlassesCameraGranted = "glassesCameraGranted"
  private static let keyLocationAutoPrompted = "locationAutoPrompted"
  private static let keyDevMode = "devMode"
  private static let keyAskFirstSec = "askFirstSec"

  /// The ask-first default, matching `CallController.StartParams.askFirstThresholdMs` (15 s).
  public static let defaultAskFirstSec = 15

  /// The last machine the user selected (picker/start/voice-switch), or nil if none yet.
  public static var machineId: String? {
    store.string(forKey: keyMachineId)
  }

  public static func setMachineId(_ machineId: String) {
    store.set(machineId, forKey: keyMachineId)
  }

  /// True after we've auto-opened the Meta AI camera permission sheet once. Stops cold starts from
  /// re-redirecting when the async DAT status check hasn't settled (or reports not-granted briefly).
  /// Manual "Grant glasses camera" still works.
  public static var glassesCameraAutoPrompted: Bool {
    store.bool(forKey: keyGlassesCameraAutoPrompted)
  }

  public static func setGlassesCameraAutoPrompted(_ value: Bool) {
    store.set(value, forKey: keyGlassesCameraAutoPrompted)
  }

  /// Remembers that the DAT glasses-camera grant was actually obtained.
  ///
  /// It exists because `checkPermissionStatus(.camera)` is eventually-consistent and lags the grant
  /// badly. Seeding the UI from this flag instead means a grant we already have stays shown while
  /// DAT catches up. Cleared only by a fresh install (the whole suite goes).
  public static var glassesCameraGranted: Bool {
    store.bool(forKey: keyGlassesCameraGranted)
  }

  public static func setGlassesCameraGranted(_ value: Bool) {
    store.set(value, forKey: keyGlassesCameraGranted)
  }

  /// True once the location sheet has been shown, whatever the user answered.
  ///
  /// Location is asked for exactly ONCE, at sign-in — not per call, and never mid-conversation. A
  /// user who declines is not nagged again; Settings is the way back, same as the camera.
  public static var locationAutoPrompted: Bool {
    store.bool(forKey: keyLocationAutoPrompted)
  }

  public static func setLocationAutoPrompted(_ value: Bool) {
    store.set(value, forKey: keyLocationAutoPrompted)
  }

  /// Developer mode: reveals the Logs tab and the in-call message composer.
  ///
  /// Off by default in EVERY build, debug included. This is deliberately not `#if DEBUG`, which is
  /// what used to gate the Logs tab: a build type answers "was this compiled for development", and
  /// the question being asked here is "does the person holding the phone want operator detail".
  public static var devMode: Bool {
    store.bool(forKey: keyDevMode)
  }

  public static func setDevMode(_ value: Bool) {
    store.set(value, forKey: keyDevMode)
  }

  /// How long Sai works before checking back, in seconds, as chosen in Settings.
  ///
  /// Persisted because it is a setting: an in-memory default silently reset whatever the user had
  /// chosen. Stored parsed (`Int`); the UI keeps its own `String` while typing so a half-deleted
  /// field doesn't have to mean a number.
  public static var askFirstSec: Int {
    if store.object(forKey: keyAskFirstSec) == nil { return defaultAskFirstSec }
    return store.integer(forKey: keyAskFirstSec)
  }

  public static func setAskFirstSec(_ value: Int) {
    store.set(value, forKey: keyAskFirstSec)
  }
}
