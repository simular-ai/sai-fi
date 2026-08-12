/* sai-fi — voice concierge. */

// The effect grammar: the bounded set of things the model is allowed to make happen.
//
// The conversation is open-ended; the capabilities are not. Every user utterance and every agent
// event cashes out as effects from this list, which is what makes the model's output validatable
// instead of trusted. [parseEffect] is that boundary — an effect the model invents, or gets the
// shape of wrong, is DROPPED rather than guessed at.
//
// Ported from cloud-api `services/concierge/voice/core/effects.ts`. Parsing is deliberately
// tolerant in the same places the TS is and strict in the same places it is; the asymmetries are
// noted per-case below, because "cleaning them up" changes what the model can do.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import org.json.JSONArray
import org.json.JSONObject

sealed interface Effect {
  /** Speak to the user, verbatim. */
  data class Say(val text: String) : Effect

  /**
   * Park on a user reply.
   *
   * A pure state signal — it does NOT speak. The client's Live model has already voiced the
   * question; speaking it here would double it up and interrupt the model mid-sentence. The
   * `question` payload is carried for the record and is not used by the FSM.
   */
  data class AskAndWait(val question: String, val waitingFor: WaitReason) : Effect

  /** Start work. The only effect that ever begins a task. */
  data class ForwardToAgent(val text: String) : Effect

  /** Steer the running turn — an answer or a correction, not new work. */
  data class RelayToAgent(val answer: String) : Effect

  data object Approve : Effect

  data object ApproveAlways : Effect

  /**
   * `reason` is parsed and then never used — the agent is told `denied` and nothing else. Kept
   * because the model supplies it and dropping it at parse time would silently change the wire
   * contract.
   */
  data class Deny(val reason: String? = null) : Effect

  /** Resolve a `choice` approval. Values are checked against what was offered. */
  data class ChooseOption(val values: List<String>) : Effect

  /** Hold a task in the FSM only — no durable doc, no agent traffic. */
  data class Enqueue(val task: String, val urgency: Urgency) : Effect

  /** Stop the running turn and drop the queue. Has no scope. */
  data object Interrupt : Effect

  /** Drop a waiting task. `task` absent means all of them. */
  data class CancelQueued(val task: String? = null) : Effect

  /** Start a waiting task now. `task` absent is only unambiguous when exactly one waits. */
  data class SendQueuedNow(val task: String? = null) : Effect

  data class SetState(val mode: Mode) : Effect

  /** Rotate onto a fresh conversation. Refused while anything is outstanding. */
  data object ResetSession : Effect

  data object Noop : Effect
}

/** Non-empty string, or null. The TS `str()` guard — empty strings are rejected everywhere. */
private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { it.isNotEmpty() }

/**
 * Validate an untrusted `{ kind, ... }` object from the model into a typed [Effect].
 *
 * Returns null on any shape or enum violation so the caller drops it rather than trusting it. An
 * unknown `kind` is null too — a newer model inventing a capability does not get to exercise it.
 */
fun parseEffect(raw: JSONObject?): Effect? {
  if (raw == null) return null
  return when (raw.optString("kind")) {
    "say" -> raw.str("text")?.let { Effect.Say(it) }
    "askAndWait" -> {
      val question = raw.str("question")
      val waitingFor = WaitReason.fromWire(raw.str("waitingFor"))
      if (question != null && waitingFor != null) Effect.AskAndWait(question, waitingFor) else null
    }
    "forwardToAgent" -> raw.str("text")?.let { Effect.ForwardToAgent(it) }
    "relayToAgent" -> raw.str("answer")?.let { Effect.RelayToAgent(it) }
    "approve" -> Effect.Approve
    "approveAlways" -> Effect.ApproveAlways
    "chooseOption" -> {
      // Non-strings and empty strings are filtered out rather than rejecting the call; null only
      // when nothing survives. A partly-malformed pick list still resolves what it can.
      val arr = raw.optJSONArray("values") ?: return null
      val values =
          (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? String)?.takeIf { it.isNotEmpty() }
          }
      if (values.isEmpty()) null else Effect.ChooseOption(values)
    }
    "deny" -> Effect.Deny(raw.str("reason"))
    // An absent or unrecognised urgency defaults to normal — NOT a rejection.
    "enqueue" -> raw.str("task")?.let { Effect.Enqueue(it, Urgency.fromWire(raw.str("urgency"))) }
    "interrupt" -> Effect.Interrupt
    "cancelQueued" -> Effect.CancelQueued(raw.str("task"))
    "sendQueuedNow" -> Effect.SendQueuedNow(raw.str("task"))
    "setState" -> Mode.fromWire(raw.str("mode"))?.let { Effect.SetState(it) }
    "resetSession" -> Effect.ResetSession
    "noop" -> Effect.Noop
    else -> null
  }
}

/** Validate a batch, dropping any malformed effects. A non-array input yields an empty list. */
fun parseEffects(raw: JSONArray?): List<Effect> {
  if (raw == null) return emptyList()
  return (0 until raw.length()).mapNotNull { i -> parseEffect(raw.opt(i) as? JSONObject) }
}
