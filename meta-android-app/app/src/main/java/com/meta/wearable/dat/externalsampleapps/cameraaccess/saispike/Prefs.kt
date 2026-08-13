/*
 * sai-fi — small persisted app preferences.
 */

// Prefs — a thin SharedPreferences wrapper. Two kinds of thing live here: the last-selected machine
// (so the app defaults to it on the next (auto-)launch instead of a hardcoded machineId — set from
// the UI picker, on call start, and on a voice `switchMachine`, so "talk to concierge to switch" also
// sticks), and the two user settings the Settings tab owns. The one-shot prompt flags are the
// remainder.
//
// Plain Context-taking getters/setters, no Flow: callers read eagerly and mirror into Compose state
// themselves. There is one reader per value and it reads at startup, so an observable wrapper would
// be machinery around a single call.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.content.Context

object Prefs {
  // Kept from the app's earlier name: the file name is the on-disk key for every existing install's
  // prefs, so renaming it silently drops the stored machine selection and both prompt flags. It
  // changes when the applicationId does (the package rename), where a fresh install is the point.
  private const val FILE = "sai_glasses"
  private const val KEY_MACHINE_ID = "machineId"
  private const val KEY_GLASSES_CAMERA_AUTO_PROMPTED = "glassesCameraAutoPrompted"
  private const val KEY_GLASSES_CAMERA_GRANTED = "glassesCameraGranted"
  private const val KEY_LOCATION_AUTO_PROMPTED = "locationAutoPrompted"
  private const val KEY_DEV_MODE = "devMode"
  private const val KEY_ASK_FIRST_SEC = "askFirstSec"

  /** The ask-first default, matching `CallController.StartParams.askFirstThresholdMs` (15 s). */
  const val DEFAULT_ASK_FIRST_SEC = 15

  private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** The last machine the user selected (picker/start/voice-switch), or null if none yet. */
  fun machineId(context: Context): String? = sp(context).getString(KEY_MACHINE_ID, null)

  fun setMachineId(context: Context, machineId: String) {
    sp(context).edit().putString(KEY_MACHINE_ID, machineId).apply()
  }

  /**
   * True after we've auto-opened the Meta AI camera permission sheet once. Stops cold starts from
   * re-redirecting when the async DAT status check hasn't settled (or reports not-granted briefly).
   * Manual "Grant glasses camera" still works.
   */
  fun glassesCameraAutoPrompted(context: Context): Boolean =
      sp(context).getBoolean(KEY_GLASSES_CAMERA_AUTO_PROMPTED, false)

  fun setGlassesCameraAutoPrompted(context: Context, value: Boolean) {
    sp(context).edit().putBoolean(KEY_GLASSES_CAMERA_AUTO_PROMPTED, value).apply()
  }

  /**
   * Remembers that the DAT glasses-camera grant was actually obtained (a `Granted` result from the
   * permission flow, or a `checkPermissionStatus` that returned `Granted`).
   *
   * It exists because `Wearables.checkPermissionStatus(CAMERA)` is eventually-consistent and lags the
   * grant badly — it kept answering `Denied` for seconds *after* a successful grant, even across an app
   * restart. Trusting that laggy read alone flip-flopped the grant back to un-granted and sent the user
   * back through Meta AI. Seeding the UI from this flag instead means a grant we already have stays
   * shown while DAT catches up. Cleared only by a fresh install (the whole prefs file goes).
   */
  fun glassesCameraGranted(context: Context): Boolean =
      sp(context).getBoolean(KEY_GLASSES_CAMERA_GRANTED, false)

  fun setGlassesCameraGranted(context: Context, value: Boolean) {
    sp(context).edit().putBoolean(KEY_GLASSES_CAMERA_GRANTED, value).apply()
  }

  /**
   * True once the Android location sheet has been shown, whatever the user answered.
   *
   * Location is asked for exactly ONCE, at sign-in — not per call, and never mid-conversation. A
   * user who declines is not nagged again; Android settings is the way back, same as the camera.
   */
  fun locationAutoPrompted(context: Context): Boolean =
      sp(context).getBoolean(KEY_LOCATION_AUTO_PROMPTED, false)

  fun setLocationAutoPrompted(context: Context, value: Boolean) {
    sp(context).edit().putBoolean(KEY_LOCATION_AUTO_PROMPTED, value).apply()
  }

  /**
   * Developer mode: reveals the Logs tab and the in-call message composer.
   *
   * Off by default in EVERY build, debug included. This is deliberately not `BuildConfig.DEBUG`,
   * which is what used to gate the Logs tab: a build type answers "was this compiled for
   * development", and the question being asked here is "does the person holding the phone want
   * operator detail". Those came apart in both directions — a release build handed to someone had no
   * way to read a log at all, and a debug build shown on stage put a Logs tab in front of an
   * audience.
   */
  fun devMode(context: Context): Boolean = sp(context).getBoolean(KEY_DEV_MODE, false)

  fun setDevMode(context: Context, value: Boolean) {
    sp(context).edit().putBoolean(KEY_DEV_MODE, value).apply()
  }

  /**
   * How long Sai works before checking back, in seconds, as chosen in Settings.
   *
   * Persisted because it is a setting: it was in-memory `mutableStateOf("15")` on the Activity, so
   * every launch silently reset whatever the user had chosen. Stored parsed (`Int`); the UI keeps its
   * own `String` while typing so a half-deleted field doesn't have to mean a number.
   */
  fun askFirstSec(context: Context): Int =
      sp(context).getInt(KEY_ASK_FIRST_SEC, DEFAULT_ASK_FIRST_SEC)

  fun setAskFirstSec(context: Context, value: Int) {
    sp(context).edit().putInt(KEY_ASK_FIRST_SEC, value).apply()
  }
}
