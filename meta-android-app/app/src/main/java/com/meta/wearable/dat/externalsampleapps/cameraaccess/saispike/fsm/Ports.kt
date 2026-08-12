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
      /** Present for `select` approvals — the options to choose from. */
      val options: List<ApprovalOption>? = null,
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

  data class QueuedTaskStarted(val pendingId: String) : AgentEvent

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

/** How the concierge resolves a pending approval. */
enum class ApprovalDecision(val wire: String) {
  APPROVED("approved"),
  APPROVED_ALWAYS("approved_always"),
  DENIED("denied"),
}

/**
 * How to resolve a `select` approval — the chosen option value(s).
 *
 * Exactly one pick uses the singular field and two-or-more uses the plural; a single-element list is
 * never sent as `selectedOptions`. That shape is what `askChoice` reads back, and writing the answer
 * anywhere else approves the card while silently dropping what the user chose.
 */
data class ApprovalSelection(
    val selectedOption: String? = null,
    val selectedOptions: List<String>? = null,
)

/** What actually happened to a held task we tried to drop. */
enum class CancelOutcome {
  CANCELLED,
  ALREADY_STARTED,
}

/** What actually happened to a held task we tried to escalate. */
enum class SendNowOutcome {
  SENT,
  ALREADY_STARTED,
}

enum class ResetOutcome {
  OK,
  RATE_LIMITED,
  FAILED,
}

/**
 * `queueTask` found the session idle, so the task STARTED instead of being held.
 *
 * Thrown rather than returned so a caller cannot mistake it for a queued task and go on to promise
 * the user it is waiting, or hold a `pendingId` that will never exist.
 */
class TaskStartedImmediately :
    Exception("the session was idle — the task started instead of queueing")

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
   * Hold a task so it runs as its OWN turn once the agent is free — durably.
   *
   * Unlike [forwardTask] this starts nothing: the AGENT drains it at its next turn boundary. It
   * therefore survives a dropped call, which an in-memory queue does not — and a queued task the
   * user was promised out loud is the worst thing to lose on a reconnect.
   *
   * @throws TaskStartedImmediately when the session turned out idle and it started instead.
   */
  suspend fun queueTask(text: String, attachments: List<TaskAttachment>? = null): String

  /**
   * Drop a held task before it runs.
   *
   * Never reports a cancellation that did not happen: this races the agent's drain by construction,
   * and guessing would mean telling the user something is off the list while it books their table.
   */
  suspend fun cancelQueuedTask(pendingId: String): CancelOutcome

  /**
   * Escalate a held task into the RUNNING turn without stopping anything — "do that first".
   *
   * The only other escalation available is abort-and-restart, which destroys the running task to make
   * room: a bad trade for a user who asked for a reorder, not a cancellation.
   */
  suspend fun sendQueuedNow(pendingId: String): SendNowOutcome

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
