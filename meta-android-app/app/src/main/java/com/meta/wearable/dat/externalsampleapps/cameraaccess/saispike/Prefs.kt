/*
 * sai-fi — small persisted app preferences.
 */

// Prefs — a thin SharedPreferences wrapper. Currently just the last-selected machine, so the app
// defaults to it on the next (auto-)launch instead of a hardcoded machineId. Set from the UI picker,
// on call start, and on a voice `switchMachine`, so "talk to concierge to switch" also sticks.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.content.Context

object Prefs {
  // Kept from the app's earlier name: the file name is the on-disk key for every existing install's
  // prefs, so renaming it silently drops the stored machine selection and both prompt flags. It
  // changes when the applicationId does (the package rename), where a fresh install is the point.
  private const val FILE = "sai_glasses"
  private const val KEY_MACHINE_ID = "machineId"
  private const val KEY_GLASSES_CAMERA_AUTO_PROMPTED = "glassesCameraAutoPrompted"
  private const val KEY_LOCATION_AUTO_PROMPTED = "locationAutoPrompted"

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
}
