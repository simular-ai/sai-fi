/*
 * sai-fi — voice concierge (whether a machine's state is worth saying out loud).
 */

// Whether a wake is worth announcing, and which line to use. A pure decision, so it can be tested
// without a device — the same reason GreetingGate, HangupPolicy and AgentEventRouter live apart from
// CallService.
//
// The rules read as obvious and are not: each one is a way of saying something untrue about a
// computer the wearer cannot see.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_AWAKE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKE_FAILED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKING

/** What to do about a machine's state, having asked the server to wake it. */
sealed interface WakeAnnouncement {
  /** Say [line], verbatim, and then watch for the machine to come up if [watch] is true. */
  data class Speak(val line: String, val watch: Boolean) : WakeAnnouncement

  /** Nothing to say. [why] is for the log — these are decisions, not omissions. */
  data class Silent(val why: String) : WakeAnnouncement
}

object WakePolicy {
  /**
   * Whether the wake we just asked for is worth telling the user about.
   *
   * @param startingUp the server says the machine is coming up — see [WakeOutcome.startingUp]
   * @param muted the user has muted Sai, so nothing said now can be heard
   * @param audible there is a Live session to say it through
   */
  fun onWakeRequested(
      startingUp: Boolean,
      muted: Boolean,
      audible: Boolean,
      status: String?,
      canWake: Boolean,
      dispatched: Boolean,
  ): WakeAnnouncement {
    // The honesty case, and the reason `startingUp` exists rather than the client deriving it from
    // `status`: a hibernated machine that cannot be woken is asleep and staying that way, and
    // MACHINE_WAKING promises about a minute. Never said for a machine that is not coming back.
    if (!startingUp) {
      return WakeAnnouncement.Silent(
          "nothing to announce (status=${status ?: "unknown"} canWake=$canWake dispatched=$dispatched)")
    }
    // Muted, these are dropped rather than held — the same call AgentEventRouter makes for a `notice`,
    // for the same reason: "the computer is waking up, about a minute" is true for about a minute, and
    // replayed on unmute it describes a world that has moved on.
    if (muted) return WakeAnnouncement.Silent("muted — a wake line is stale by the time it is audible")
    if (!audible) return WakeAnnouncement.Silent("no live session to say it through")
    return WakeAnnouncement.Speak(MACHINE_WAKING, watch = true)
  }

  /**
   * The end of the watch: the machine came up, or it never did.
   *
   * Kept on the same object as [onWakeRequested] because the mute rule has to match at both ends. It
   * would be tempting to let the failure through while muted — it is the one line here that is
   * actionable — but the user chose silence, and three minutes later "it didn't come back" is as stale
   * as the line that opened the wait.
   */
  fun onWatchEnded(active: Boolean, muted: Boolean, audible: Boolean): WakeAnnouncement {
    if (muted) return WakeAnnouncement.Silent("muted — the outcome of a wake is stale too")
    if (!audible) return WakeAnnouncement.Silent("no live session to say it through")
    return WakeAnnouncement.Speak(
        if (active) MACHINE_AWAKE else MACHINE_WAKE_FAILED,
        watch = false,
    )
  }
}
