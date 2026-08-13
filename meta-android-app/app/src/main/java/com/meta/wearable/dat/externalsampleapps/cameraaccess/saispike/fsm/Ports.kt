/* sai-fi — voice concierge. */

// The two seams the FSM talks through, and the agent's event union.
//
// AgentBridge is the write side (start work, hold it, cancel it, resolve an approval); VoiceChannel
// is how anything reaches the user. Keeping them interfaces is what lets the golden scenarios drive
// the whole state machine against fakes, with no network and no Android.
//
// `say` and `instruct` are NOT interchangeable and the difference is audible — see VoiceChannel.
//
// Ported from cloud-api `services/concierge/voice/ports/`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** Agent status values. `summarizing` and `aborting` both still count as working. */
enum class AgentStatus(val wire: String) {
  PROCESSING("processing"),
  SUMMARIZING("summarizing"),
  ABORTING("aborting"),
  IDLE("idle"),
  ERROR("error");

  companion object {
    fun fromWire(v: String?): AgentStatus? = entries.firstOrNull { it.wire == v }
  }
}

/** Agent → user events, as the FSM sees them. */
sealed interface AgentEvent {
  /** Streamed assistant answer text (final-turn). */
  data class Text(val text: String) : AgentEvent

  /**
   * Mid-turn narration / tool progress. Deliberately NOT surfaced to the user.
   *
   * `failed` marks a STEP that failed while the task carries on — not an `error`, which is terminal,
   * but the one kind of progress the concierge must hear about: without it she has no idea anything
   * went wrong and fills the silence with a result she never received.
   */
  data class Progress(val text: String, val tool: String? = null, val failed: Boolean = false) :
      AgentEvent

  data class ApprovalRequest(
      val id: String,
      val title: String,
      val description: String,
      val approvalType: String,
      val isLinkOnly: Boolean,
      val allowAlways: Boolean,
      /** Present for `select` approvals — every option across every question, flattened. */
      val options: List<ApprovalOption>? = null,
      /**
       * The same options still grouped BY QUESTION, when the card asks more than one thing.
       *
       * `options` is what the model picks from and what gets read back, and a spoken pick carries no
       * question index — but the agent resolves a choice positionally, one group per question. This
       * is the only thing that can put a flat answer back into the right slots. See
       * [groupSelections].
       */
      val questions: List<ApprovalQuestion>? = null,
      val multiple: Boolean? = null,
      /** Whether the select also accepts a free-form "something else" answer. */
      val allowOther: Boolean? = null,
      /** When the request auto-expires (ms epoch), for the pre-timeout ping. */
      val expiresAt: Long? = null,
  ) : AgentEvent

  /** A pending request was resolved out-of-band (the app, or another channel). */
  data class ApprovalResolved(val id: String, val status: String) : AgentEvent

  data class Status(val status: AgentStatus) : AgentEvent

  data class Complete(val summary: String? = null) : AgentEvent

  data class Error(val text: String) : AgentEvent

  /** The FSM's own projection, echoed back for the client's activity log. */
  data class SessionState(
      val running: String? = null,
      val blockedOn: String? = null,
      val queued: List<String> = emptyList(),
  ) : AgentEvent

  /**
   * A system reply from the router about DELIVERY, not about the work — the machine was hibernated
   * and is waking, the agent is offline, the linked machine is gone.
   *
   * Not an `error` (nothing failed) and not `progress` (silent by design), so it is its own kind: the
   * one thing that must be relayed before the task has produced anything at all. The voice channel's
   * reply used to be a no-op sink, so a woken VM meant a silent minute with no explanation.
   */
  data class Notice(val text: String) : AgentEvent
}

/**
 * How the concierge resolves a pending approval.
 *
 * The wire values are the agent API's `response` field, which is a plain yes/no/always — not the
 * `approved` / `denied` status the approval doc ends up carrying.
 */
enum class ApprovalDecision(val wire: String) {
  APPROVED("yes"),
  APPROVED_ALWAYS("always"),
  DENIED("no"),
}

/** One question on a `choice` card, with what it offered. */
data class ApprovalQuestion(
    val options: List<ApprovalOption>,
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
)

/**
 * How to resolve a `choice` approval: the picked values, ONE GROUP PER QUESTION, in the card's own
 * order.
 *
 * Positional, and the agent requires a non-empty group for every question — a partial answer is
 * refused rather than half-applied. That refusal is the desired outcome: it surfaces as a rejected
 * resolution the model is told to re-present, instead of a card approved with a question silently
 * unanswered.
 */
data class ApprovalSelection(val selections: List<List<String>>)

enum class ResetOutcome {
  OK,
  RATE_LIMITED,
  FAILED,
}

interface AgentBridge {
  /**
   * Forward a new task; returns the agent chat session it landed in.
   *
   * `attachments` is for a task that was HELD: a queued task carries the photos captured for it,
   * because by the time it drains the adapter's own stash may hold someone else's. Omit it on the
   * immediate path and the adapter drains its stash as before.
   */
  suspend fun forwardTask(text: String, attachments: List<TaskAttachment>? = null): String

  /**
   * Detach the photos captured for the task about to be queued, so nothing later picks them up.
   *
   * The stash is drained by whatever writes next. That is right when the write happens immediately
   * and wrong the moment a task is held — the photo would drain with the wrong request attached.
   */
  fun takePendingAttachments(): List<TaskAttachment>

  /** Send a mid-turn message to steer / supply input to a running turn. */
  suspend fun steer(text: String)

  /** Abort the running turn. Has no scope — it stops every request in flight. */
  suspend fun abort()

  /**
   * Rotate onto a fresh chat session.
   *
   * Returns why it didn't happen rather than throwing: the two failures the user needs told apart are
   * "you've done this a lot lately" and "it broke".
   */
  suspend fun resetSession(): ResetOutcome

  suspend fun resolveApproval(
      id: String,
      decision: ApprovalDecision,
      selection: ApprovalSelection? = null,
  )
}

/**
 * How the concierge reaches the user, and the model driving them.
 *
 * [say] and [instruct] are NOT interchangeable, and the difference is audible. The client wraps a
 * `say` in "say this to the user, verbatim", so anything sent that way is heard word for word: it has
 * to BE the sentence, never a description of what to do. Text meant for the model — "this didn't
 * work, here's what to do instead" — goes through [instruct], which reaches it as context. Sent the
 * wrong way round, the user hears function names and stage directions read aloud.
 */
interface VoiceChannel {
  /** Speak within the active turn. LITERAL speech — the user hears it verbatim. */
  suspend fun say(text: String)

  /**
   * Tell the MODEL something — a correction, or a fact it needs before its next move. Reaches it as
   * context, so the words themselves are never spoken; what the user hears is the model's own reply.
   */
  suspend fun instruct(text: String)
}
