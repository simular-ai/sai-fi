/*
 * sai-fi — the seam between the Activity and its Compose UI.
 */

// The control surface's state and actions, as seen by the composables. VoiceConciergeActivity
// implements this; ConciergeScreen renders it.
//
// Why an interface rather than passing ~16 parameters and ~6 lambdas: the state stays exactly where
// it already lives, as `mutableStateOf` fields on the Activity. Compose reads them through these
// property getters, and a snapshot read through a getter is still a snapshot read — recomposition
// works unchanged. That made moving the UI out of the Activity a near-mechanical move instead of a
// state-hoisting rewrite, which is what it would have taken to get the same result with plain
// parameters.
//
// The split between `val` and `var` here is the real contract: `var` is exactly the state the UI is
// allowed to write. Everything else is the Activity's to own.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.Machine

interface ConciergeUi {
  // ── Account ────────────────────────────────────────────────────────────────────────────────────
  val signedIn: Boolean
  val userEmail: String?

  // ── Machines ───────────────────────────────────────────────────────────────────────────────────
  val machines: SnapshotStateList<Machine>
  var selectedMachine: Machine?
  /** Last load succeeded — gates the dropdown. */
  val machinesFetchOk: Boolean
  /** Short non-error status ("Loading…", "No machines found"). */
  val machinesInfo: String

  // ── Glasses ────────────────────────────────────────────────────────────────────────────────────
  val glassesReg: RegistrationState?
  /** DAT reports a device with LinkState.CONNECTED (powered on / in range). */
  val glassesLinked: Boolean
  val glassesCameraGranted: Boolean

  // ── Settings ───────────────────────────────────────────────────────────────────────────────────
  var askFirstThresholdSec: String

  // ── Location ───────────────────────────────────────────────────────────────────────────────────
  /**
   * Our own reason-for-location dialog, shown once at sign-in and immediately before the system
   * permission sheet. It exists because Android's sheet cannot carry app-supplied text.
   */
  val locationRationaleOpen: Boolean

  // ── Errors (full text behind a reopenable dialog, never truncated inline) ──────────────────────
  val authError: String?
  var authErrorOpen: Boolean
  val machinesError: String?
  var machinesErrorOpen: Boolean
  val glassesError: String?
  var glassesErrorOpen: Boolean

  // ── Actions ────────────────────────────────────────────────────────────────────────────────────
  fun signIn()

  fun signOut()

  fun loadMachines()

  fun registerGlasses()

  fun requestGlassesCamera()

  /** Answer to [locationRationaleOpen]: true hands off to the system sheet, false stops there. */
  fun onLocationRationale(proceed: Boolean)

  fun onStartClicked()
}
