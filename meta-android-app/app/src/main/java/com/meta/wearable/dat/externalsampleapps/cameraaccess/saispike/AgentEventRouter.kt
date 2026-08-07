/*
 * sai-fi — voice concierge.
 */

// What to do with an agent event that warrants a reaction: say it now, hold it until she's audible,
// or drop it. A pure decision, so it can be tested without a device.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONObject

/** What [AgentEventRouter.route] decided. */
sealed interface NudgeAction {
  /** Nothing to react to (ordinary progress, an internal event). */
  data object Ignore : NudgeAction

  /** Inject now. [kind] is the log label, which names WHY this wording was chosen. */
  data class Inject(val kind: String, val nudge: String) : NudgeAction

  /** A failed step, injected now, and the throttle window restarts from this moment. */
  data class InjectStepFailure(val nudge: String) : NudgeAction

  /** Hold until she is audible again; [HeldNudgeQueue] collapses and replays these. */
  data class Hold(val kind: String, val nudge: String) : NudgeAction

  /** Deliberately not delivered. [why] is for the log — these are decisions, not losses. */
  data class Drop(val why: String) : NudgeAction
}

/**
 * Routes one agent event to a reaction.
 *
 * This was 74 lines inside a constructor argument, reading six fields of a foreground Service, and so
 * unreachable from a JVM test. Every rule below was found by hearing it fail on a real device, and each
 * is the kind of thing that regresses silently:
 *
 *  - **Ask-first is about the USER's silence, not the task's duration.** The gate used to be how long
 *    the task took, which is a different question entirely: a 30-second email summary tripped it while
 *    the user was mid-sentence with her, so she was told "the user has been away a while — say NOTHING"
 *    about someone who had just spoken, obeyed, and the result was never delivered.
 *  - **A failed step is throttled, not suppressed.** A long task can fail several steps while
 *    recovering; one nudge per failure floods the session until she blurts about it. One every 30s
 *    carries the fact (there is no result yet, don't invent one) without a running commentary.
 *  - **Stale-by-nature events are dropped while muted rather than held.** A step failure and a "the
 *    machine is waking, about a minute" notice are both true for about a minute. Replayed on unmute
 *    they describe a world that has moved on, and the completion supersedes them anyway.
 *  - **A completion while muted is HELD with the ask-first wording**, which is exactly the behaviour
 *    wanted on release: say nothing yet, wait for a gap, then offer it in one line.
 */
object AgentEventRouter {

  fun route(
      event: JSONObject,
      muted: Boolean,
      /** Since the user last spoke. [Long.MAX_VALUE] when they never have this call. */
      userQuietMs: Long,
      askFirstThresholdMs: Long,
      /** Since the last step-failure nudge went out. [Long.MAX_VALUE] if none has. */
      sinceLastStepFailureMs: Long,
      stepFailureIntervalMs: Long,
  ): NudgeAction {
    when (event.optString("type")) {
      "progress" -> {
        if (!event.optBoolean("failed", false)) return NudgeAction.Ignore
        if (sinceLastStepFailureMs < stepFailureIntervalMs) {
          return NudgeAction.Drop("step-failed — throttled (told her ${sinceLastStepFailureMs / 1000}s ago)")
        }
        if (muted) return NudgeAction.Drop("not holding step-failed while muted — it will be stale")
        return NudgeAction.InjectStepFailure(describeAgentEvent(event))
      }
      "notice" -> {
        if (muted) return NudgeAction.Drop("not holding notice while muted — it will be stale")
        return NudgeAction.Inject("notice", describeAgentEvent(event))
      }
    }

    val type = event.optString("type")
    val askFirst = type == "complete" && (muted || userQuietMs > askFirstThresholdMs)
    val nudge = if (askFirst) describeCompleteAskFirst(event) else describeAgentEvent(event)
    if (nudge.isEmpty()) return NudgeAction.Ignore

    // The two completion wordings behave very differently — one says "report the result now", the other
    // "say nothing, wait for a gap, then offer it" — so the label has to name WHICH went out and why.
    // Otherwise a completion the user never heard is indistinguishable from one she was correctly told
    // to sit on.
    val kind =
        when {
          !askFirst -> type
          muted -> "complete (ask-first: muted)"
          userQuietMs == Long.MAX_VALUE -> "complete (ask-first: user never spoke)"
          else -> "complete (ask-first: user quiet ${userQuietMs / 1000}s)"
        }
    return if (muted) NudgeAction.Hold(type, nudge) else NudgeAction.Inject(kind, nudge)
  }
}
