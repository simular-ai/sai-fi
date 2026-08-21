/*
 * sai-fi — voice concierge (leaving work behind).
 */

// Two ways to walk away from work in progress — hanging up, and changing machines — and one rule for
// both: ask first, and say where the results will be instead.
//
// A pure decision, so it can be tested without a device. The reason it is shared rather than written
// twice: the two paths lose work in exactly the same way, and the one that had no ask at all lost it
// silently. `applyMachineSwitch` builds a fresh VoiceSession, so the FSM — queue, in-flight turn,
// pending approval — is replaced wholesale. Work the user was PROMISED OUT LOUD disappears with
// nothing said, which is the hazard docs/VOICE_FSM.md §7 records for a dropped call, reachable by an
// ordinary voice command.
//
// Note what this does NOT do: it never stops anything. A task left running keeps running on the
// machine it started on, and the honest thing is to say so and point at the app, not to abort work
// nobody asked to cancel.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.hasOutstandingWork
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.readBackList

/** Where the user is going, which is all that differs between the two questions. */
enum class Leaving {
  /** The call is ending. Results have nowhere to arrive. */
  CALL,
  /** The machine is changing. The old machine keeps working; this call stops hearing about it. */
  MACHINE,
}

/** What [LeavingWorkPolicy.decide] concluded. */
sealed interface LeavingWorkAction {
  /** Do not go yet. [nudge] is model-facing: it carries the facts and asks for a decision. */
  data class Ask(val nudge: String) : LeavingWorkAction

  /** Nothing outstanding, or the user has already been asked. Go. */
  data object Proceed : LeavingWorkAction
}

object LeavingWorkPolicy {

  /**
   * Ask before leaving work behind — once.
   *
   * One-shot for the same reason `applyInterrupt`'s scope question is: a user who says "hang up" or
   * "switch to my laptop" twice means it, and a question that cannot be got past is a trap. The caller
   * owns the flag so the two paths do not consume each other's ask.
   *
   * @param muted nothing asked can be heard, so there is no question to put — the caller decides what
   *   to do with that, because the right answer differs: a hang-up should proceed silently, and a
   *   switch should proceed and be reported on unmute.
   */
  fun decide(
      state: ConciergeState,
      leaving: Leaving,
      alreadyAsked: Boolean,
      muted: Boolean,
  ): LeavingWorkAction {
    if (alreadyAsked || muted) return LeavingWorkAction.Proceed
    if (!state.hasOutstandingWork()) return LeavingWorkAction.Proceed
    return LeavingWorkAction.Ask(nudge(state, leaving))
  }

  /**
   * The facts, and what to do with them. Model-facing, like `HangupPolicy.UNCONFIRMED_NUDGE` — the
   * content varies with what is outstanding, so the phrasing is the model's and only the facts are
   * ours.
   *
   * Running and queued are named SEPARATELY, the same rule `interruptScopeQuestion` follows: one is
   * work in progress the user may not want to lose, the other has not happened at all, and reading
   * them as one list describes a queued task as underway.
   */
  private fun nudge(state: ConciergeState, leaving: Leaving): String {
    // Each clause stands on its own, and the subject of the first is YOU — the model. Written as
    // fragments hung off one lead-in ("they are …") they came out misattributing Sai's work to the
    // user, and an approval-only case rendered as "They are there's a request waiting on their answer".
    val clauses = mutableListOf<String>()
    if (state.inFlight.isNotEmpty()) {
      clauses += "you're still working on ${readBackList(state.inFlight)}"
    }
    if (state.queue.isNotEmpty()) {
      clauses += "${readBackList(state.queue.map { it.text })} hasn't started yet"
    }
    if (state.pendingApprovalId != null) {
      clauses += "a request is waiting on their answer"
    }

    // Past participle: this slots into "you have NOT …", where a gerund reads as "you have not hanging
    // up".
    val going =
        when (leaving) {
          Leaving.CALL -> "hung up"
          Leaving.MACHINE -> "moved to another machine"
        }
    // The part that makes the answer actionable, and the part that was missing: a task left running is
    // not lost, it is just somewhere else. Without this the choice reads as "abandon it or stay", and
    // the user picks staying for work they could simply have read later.
    val whereItGoes =
        when (leaving) {
          Leaving.CALL ->
              "Anything left running keeps going on their machine — they just won't hear the result " +
                  "here, and can pick it up in the Sai app."
          Leaving.MACHINE ->
              "Anything left running keeps going on the machine they're leaving — this call won't " +
                  "hear the result once you move, and they can pick it up in the Sai app."
        }

    return "[system] NOTHING has happened yet — you have NOT $going, and you must not until they " +
        "answer. Right now: ${clauses.joinToString("; ")}. $whereItGoes " +
        "Tell them what's outstanding in ONE short line, and ask whether to stop it first or leave " +
        "it running. Then do what they say: to stop it, call interrupt; to leave it, ask again and " +
        "it will go through. Do not describe any of it as finished."
  }
}
