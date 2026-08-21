/*
 * sai-fi — voice concierge.
 */

// What to do with an agent event that warrants a reaction: say it now, hold it until Sai is audible,
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

  /** Hold until Sai is audible again; [HeldNudgeQueue] collapses and replays these. */
  data class Hold(val kind: String, val nudge: String) : NudgeAction

  /** Deliberately not delivered. [why] is for the log — these are decisions, not losses. */
  data class Drop(val why: String) : NudgeAction
}

/**
 * How long the user has actually been QUIET, for the ask-first gate.
 *
 * Not simply `now − lastUserSpeechAt`: a user who asked for something and is sitting there waiting
 * for it is silent for exactly as long as the work takes, and reading that as absence is what
 * silences the answer they were waiting to hear. So the clock stops at [workStartedAt] — the moment
 * we began doing something for them, since they last spoke — and everything after it is the wait,
 * not the absence.
 *
 * [workStartedAt] is the FIRST such moment, not the latest, and that distinction is the whole bug it
 * was written for. It used to be the moment the task was forwarded, which is the same instant on the
 * ordinary path — but a vision task is held on the device until the glasses photo lands, so a
 * 40-second capture sat between the user's request and the forward and was counted as 40 seconds of
 * absence. The user had spoken half a second before the camera started.
 *
 * A stale stamp (work that began before they last spoke) is ignored, which is what the `>=` is for:
 * their speech is the newer fact.
 *
 * THE TRADE-OFF, chosen deliberately. A user who asks for something and then genuinely walks away is
 * indistinguishable from one who asks and waits — the silence is identical and nothing else is
 * observable — so this rule fails towards DELIVERING. Someone who left hears a result announced to an
 * empty room; someone who stayed hears the answer they were waiting for. The gate still fires the
 * other way for the two cases where absence is actually evidenced: quiet with nothing outstanding,
 * and never having spoken at all.
 */
fun userQuietMs(now: Long, lastUserSpeechAt: Long, workStartedAt: Long): Long =
    when {
      lastUserSpeechAt == 0L -> Long.MAX_VALUE // never spoke this call
      workStartedAt >= lastUserSpeechAt -> workStartedAt - lastUserSpeechAt
      else -> now - lastUserSpeechAt
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
 *    the user was mid-sentence with Sai, so Sai was told "the user has been away a while — say NOTHING"
 *    about someone who had just spoken, obeyed, and the result was never delivered.
 *  - **A failed step is throttled, not suppressed.** A long task can fail several steps while
 *    recovering; one nudge per failure floods the session until Sai blurts about it. One every 30s
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
          return NudgeAction.Drop("step-failed — throttled (told Sai ${sinceLastStepFailureMs / 1000}s ago)")
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
    // Otherwise a completion the user never heard is indistinguishable from one Sai was correctly told
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
