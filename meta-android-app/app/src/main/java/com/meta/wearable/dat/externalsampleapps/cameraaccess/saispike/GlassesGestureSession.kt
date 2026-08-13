/*
 * sai-fi — voice concierge (glasses temple-button control).
 */

// GlassesGestureSession — opens a DAT DeviceSession purely to react to the glasses temple gestures, so
// the temple can start/stop the call. A DAT session is capability-agnostic (display is just one
// attachable capability, which we deliberately don't attach — we have no display glasses).
//
// IMPORTANT — DAT 0.8 gives a third-party app NO gesture/touch/wear-state API. The gestures are NOT
// delivered as events we can bind; they are hardwired to session lifecycle, and all we observe is the
// resulting DeviceSessionState transition. Confirmed against the official DAT docs (Wearables MCP):
// "Users can pause, resume, or stop your session by closing the hinges, taking the glasses off, or
// tapping the glasses." The complete set of temple gestures is only three, and none is remappable:
//
//   • tap                → PAUSED ⇄ STARTED   (pause/resume the call)
//   • tap-and-hold       → STOPPED            (stops the session → we end the call)
//   • doff / fold / drop → STOPPED            (indistinguishable from tap-and-hold: same signal)
//   • two-finger "back"  → ends a DISPLAY session only (display capability + display hardware; N/A here)
//
// There is NO double-tap, swipe, or drag exposed, and tap-and-hold cannot be told apart from a fold/
// doff (both are just STOPPED with no distinguishing reason). So we CANNOT bind a third distinct action
// (e.g. photo capture) to a glasses gesture — manual capture lives on the phone UI / voice instead.
// See docs/SAI_GLASSES_APP.md §"Glasses gestures".
//
// This runs ALONGSIDE the call (not the camera stream), so it outlives a paused call and a tap can
// resume it. NOTE (on-device to-do): the docs describe gesture→state handling "during an active
// stream/session"; confirm a session with NO capability attached still delivers these transitions —
// if it doesn't, attach a throwaway camera stream purely to keep it live.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GlassesGestureSession(
    private val scope: CoroutineScope,
    /** Temple tap — toggle the call (pause ⇄ resume). */
    private val onTap: () -> Unit,
    /** Temple tap-and-hold, or the glasses were removed/folded — end the call. */
    private val onStop: () -> Unit,
    private val onLog: (String) -> Unit,
) {
  private var session: DeviceSession? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var prev: DeviceSessionState? = null

  /** The live DAT session (for attaching a one-shot camera stream), or null if none is open. */
  fun deviceSession(): DeviceSession? = session

  /** Best-effort: open a session for gestures. No-ops (with a log) if no glasses are registered. */
  fun start() {
    if (session != null) return
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { created ->
          session = created
          errorJob =
              scope.launch {
                created.errors.collect {
                  // The call's audio is independent of this DAT session, so a session error only
                  // disables the temple button + camera, not the call. "No eligible device" means
                  // the glasses aren't paired/eligible for this app (on, unfolded, in range, registered).
                  onLog(
                      "glasses session error: ${it.description} — temple button/camera unavailable; the call continues on phone/Bluetooth audio",
                  )
                }
              }
          stateJob =
              scope.launch {
                created.state.collect { st ->
                  val p = prev
                  prev = st
                  // Log the transition the moment it ARRIVES, before acting on it. A temple-tap mute
                  // during a capture appeared minutes late in the log, and without an arrival line
                  // there was no way to tell a late DELIVERY from a late reaction. Our own half of that
                  // is fixed (the photo decode no longer blocks this dispatcher — see GlassesCamera);
                  // if a tap is still late with this line in place, the queueing is DAT's.
                  if (p != null) onLog("glasses: session $p → $st")
                  when (st) {
                    DeviceSessionState.STARTED -> {
                      if (p == null) onLog("glasses: session started — temple button live")
                      else if (p == DeviceSessionState.PAUSED) onTap() // tap → resume
                    }
                    DeviceSessionState.PAUSED -> onTap() // tap → pause
                    DeviceSessionState.STOPPED -> onStop() // tap-and-hold / doff / fold
                    else -> {} // STARTING / STOPPING — transient
                  }
                }
              }
          created.start()
        }
        .onFailure { error, _ ->
          onLog("glasses: no session (register glasses to use the temple button) — ${error.description}")
        }
  }

  fun stop() {
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    prev = null
    runCatching { session?.stop() }
    session = null
  }
}
