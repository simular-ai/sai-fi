/* sai-fi — voice concierge. */

// The agent-orchestration FSM's state, and the pure transitions over it.
//
// This tracks what the concierge is doing *with respect to the agent* — idle, clarifying a task
// before forwarding it, waiting on the agent, negotiating priority. Conversational memory is NOT
// here: it lives in the Live model's own context.
//
// Everything in this file is pure. No coroutines, no clock, no ports — a state and an input go in, a
// new state comes out. That is what makes the 62 golden scenarios runnable as plain JVM tests, and
// it is the same split GlassesLink uses (pure steps + a thin driver on top).
//
// Ported from cloud-api `services/concierge/voice/core/state.ts`. The comments explaining WHY a rule
// exists came with it — most of them record a failure seen on a real device, and dropping them is
// how the rule gets "tidied" back into the bug.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** What the concierge is doing with respect to the agent. */
enum class Mode(val wire: String) {
  IDLE("idle"),
  CLARIFYING("clarifying"),
  WORKING("working"),
  AWAITING_USER("awaiting-user"),
  NEGOTIATING("negotiating");

  companion object {
    fun fromWire(v: String?): Mode? = entries.firstOrNull { it.wire == v }
  }
}

/** Why we're awaiting a user reply — shapes how the next utterance is read. */
enum class WaitReason(val wire: String) {
  CLARIFICATION("clarification"),
  APPROVAL("approval"),
  INPUT("input"),
  URGENCY("urgency");

  companion object {
    fun fromWire(v: String?): WaitReason? = entries.firstOrNull { it.wire == v }
  }
}

enum class Urgency(val wire: String) {
  NORMAL("normal"),
  URGENT("urgent");

  companion object {
    /** An absent or unrecognised urgency is `normal` — never a rejection. Mirrors parseEffect. */
    fun fromWire(v: String?): Urgency = entries.firstOrNull { it.wire == v } ?: NORMAL
  }
}

/** A photo captured for a task, bound to it rather than left on the bridge. */
data class TaskAttachment(
    val path: String,
    val name: String,
    val mime: String,
    val size: Long,
    val downloadUrl: String? = null,
    val fileId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class QueuedTask(
    val text: String,
    val urgency: Urgency,
    /**
     * The photos captured for THIS task, taken off the bridge when it was queued.
     *
     * A held task cannot leave its photo in the bridge's stash: the stash is drained by whatever
     * writes next, so the picture would either ride someone else's request or come back with a
     * second one attached. Binding it here is what makes "queued" safe for a vision task.
     */
    val attachments: List<TaskAttachment>? = null,
    /**
     * The durable pending doc this entry mirrors, once it has one.
     *
     * With the queue in Firestore the FSM entry is a DISPLAY copy: the agent decides when the task
     * runs, and this id is how the two are matched up — to recognise the task in
     * `queued-task-started`, and to cancel it by deleting the right doc. Absent only for an entry
     * the model enqueued directly (`enqueue`), which never became a durable doc.
     */
    val pendingId: String? = null,
)

/** An option offered by a pending `choice` request. */
data class ApprovalOption(val value: String, val label: String)

data class ConciergeState(
    val mode: Mode = Mode.IDLE,
    /** Agent approval awaiting a spoken decision. */
    val pendingApprovalId: String? = null,
    /**
     * What the pending request actually asks, so it can be put back to the user.
     *
     * The question is voiced once, when the request arrives. If the user then says something that
     * is not an answer to it, the only way to re-ask is to still have it — otherwise the model
     * re-invents the question, or drops it.
     */
    val pendingApprovalPrompt: String? = null,
    /**
     * The options a pending `choice` request offered — values guard `chooseOption`, and the LABELS
     * are what gets read back when the user asks what the choices are.
     *
     * Values alone were kept here until 2026-07-31, which is half of why "what are the options?"
     * became a round-trip to an agent that was blocked on this very request: there was nothing
     * human-readable to answer from.
     */
    val pendingApprovalOptions: List<ApprovalOption>? = null,
    /** Whether the pending `choice` accepts a free-form "something else" answer. */
    val pendingApprovalAllowOther: Boolean? = null,
    /** The pending approval is link-only (browser-completed) — not voice-resolvable. */
    val pendingApprovalLinkOnly: Boolean? = null,
    /**
     * The pending approval's type, so we can tell the two kinds of "link-only" apart.
     * `service_connect` / `service_auth` genuinely need a browser; `user_input` only needs TEXT,
     * which a voice session supplies fine — see `relayResolvesApproval`.
     */
    val pendingApprovalType: String? = null,
    /** What the next user utterance answers, when mode is awaiting-user-ish. */
    val awaiting: WaitReason? = null,
    /** Tasks held while the agent is busy. */
    val queue: List<QueuedTask> = emptyList(),
    /**
     * The user's requests forwarded into the CURRENT agent turn, oldest first — i.e. exactly what an
     * `interrupt` would stop. Every forward lands in the same chat session (a machine has one), so
     * one turn routinely carries several separate requests, while `abort()` has no scope and stops
     * all of them. Tracking them is what lets the FSM notice that a "cancel" is ambiguous before it
     * destroys work the user never mentioned.
     */
    val inFlight: List<String> = emptyList(),
    /**
     * We've already asked the user whether a cancellation means everything or just one thing, so the
     * next `interrupt` is them having answered "everything" — it goes through.
     */
    val interruptScopeAsked: Boolean? = null,
)

// NOTE: there is deliberately NO sessionId here. Session identity belongs to the bridge — the id
// returned by forwardTask is discarded, and resetSession rotates it inside the bridge. A copy in the
// FSM would be a second answer to "which conversation is this?", free to disagree with the first.

fun initialState(): ConciergeState = ConciergeState()

fun ConciergeState.withMode(mode: Mode): ConciergeState = copy(mode = mode)

/**
 * Enqueue a held task. Urgent tasks jump to the front of the queue.
 *
 * "The front of the queue" means THIS list, which since the durable queue landed is only the whole
 * truth for entries with no `pendingId`. A task admitted through `forwardToAgent` lives in
 * `pending_user_messages` and the agent drains those in its own order; reordering the display copy
 * cannot change that. In practice they do not mix — admission always passes `normal`, and only the
 * model-driven `enqueue` effect passes `urgent` — but if they ever did, the spoken order and the
 * real one could disagree. Moving a DURABLE task up is `sendQueuedNow`, which actually starts it.
 */
fun ConciergeState.enqueue(
    text: String,
    urgency: Urgency = Urgency.NORMAL,
    attachments: List<TaskAttachment>? = null,
    pendingId: String? = null,
): ConciergeState {
  val item =
      QueuedTask(
          text = text,
          urgency = urgency,
          // Empty is stored as absent, matching the TS spread — an empty list and no list must not
          // be two different things downstream.
          attachments = attachments?.takeIf { it.isNotEmpty() },
          pendingId = pendingId?.takeIf { it.isNotEmpty() },
      )
  return copy(queue = if (urgency == Urgency.URGENT) listOf(item) + queue else queue + item)
}

/**
 * Drop everything waiting, without running any of it.
 *
 * For "stop all of it": an abort ends the running turn, and the queue has to go with it, or the
 * cancellation starts the next task instead of stopping anything. Separate from [endTurn] on
 * purpose — a turn ending NORMALLY should still release the queue.
 */
fun ConciergeState.clearQueue(): ConciergeState = if (queue.isEmpty()) this else copy(queue = emptyList())

/** Drop one waiting task by its position, for a scoped cancel of something not yet started. */
fun ConciergeState.removeQueued(index: Int): ConciergeState =
    if (index < 0 || index >= queue.size) this
    else copy(queue = queue.filterIndexed { i, _ -> i != index })

/**
 * Move a held task into the running turn, by the durable doc's id.
 *
 * The agent starts queued work on its own schedule now, so this is the FSM catching up with a
 * decision it did not make: the entry leaves the queue and becomes what `interrupt` would stop.
 * Unknown ids are ignored — a task started from another surface is not ours to claim.
 *
 * Deliberately does NOT touch `mode`.
 */
fun ConciergeState.startQueued(pendingId: String): ConciergeState {
  val index = queue.indexOfFirst { it.pendingId == pendingId }
  if (index < 0) return this
  val item = queue[index]
  return copy(
      queue = queue.filterIndexed { i, _ -> i != index },
      inFlight = inFlight + item.text,
  )
}

/**
 * A task has actually reached the agent and is now running.
 *
 * One named transition instead of the copy that used to sit at each of the six sites where a task
 * starts — those had DRIFTED: three cleared `awaiting` and `interruptScopeAsked`, three cleared
 * neither or only one. Both leftovers are wrong once a new task is underway:
 * - `awaiting` says the FSM is waiting on the user for something, while `mode` says it is working.
 *   Nothing can be true of both.
 * - `interruptScopeAsked` means "we already asked which task to stop". It was asked about the
 *   PREVIOUS turn's requests, so carrying it into a new one lets the next `interrupt` abort fresh
 *   work without asking — the exact failure [endTurn] clears it to prevent.
 *
 * Callers needing a different mode (e.g. a task that starts while parked) set it after; this is the
 * common shape, not a straitjacket.
 */
fun ConciergeState.startTurn(text: String): ConciergeState =
    beginTask(text).copy(mode = Mode.WORKING, awaiting = null, interruptScopeAsked = null)

/** Record a request forwarded into the current agent turn. */
fun ConciergeState.beginTask(text: String): ConciergeState = copy(inFlight = inFlight + text)

/**
 * The turn is over (completed, errored, or aborted) — nothing is in flight any more.
 *
 * Clears the scope question with it: it was asked about THIS turn's requests, and a flag outliving
 * them would let the next `interrupt` abort a fresh turn's work without asking.
 *
 * Deliberately does NOT touch `mode` or the queue.
 */
fun ConciergeState.endTurn(): ConciergeState = copy(inFlight = emptyList(), interruptScopeAsked = null)

/** True while the agent is actively working. */
fun ConciergeState.isWorking(): Boolean = mode == Mode.WORKING

/**
 * True when there is outstanding work, so the conversation must not be rotated away.
 *
 * Keyed on the three things that OUTLIVE a rotation rather than on `mode`: a running turn's stream
 * and a held task's pending doc both belong to the session being replaced, and an unanswered
 * approval belongs to the turn that raised it. `mode` would be the wrong test — it can read `idle`
 * with a task still queued, which is precisely the case that must refuse.
 */
fun ConciergeState.hasOutstandingWork(): Boolean =
    inFlight.isNotEmpty() || queue.isNotEmpty() || pendingApprovalId != null

/**
 * Everything describing the request we were waiting on, cleared in one go.
 *
 * One function rather than written out at each of the six sites that finish an approval: they had
 * already drifted (the out-of-band branch never cleared `pendingApprovalType`), and a field left
 * behind is a stale answer to "what is the user being asked?" — which is exactly what the re-ask
 * nudge reads.
 */
fun ConciergeState.noPendingApproval(): ConciergeState =
    copy(
        pendingApprovalId = null,
        pendingApprovalPrompt = null,
        pendingApprovalLinkOnly = null,
        pendingApprovalOptions = null,
        pendingApprovalAllowOther = null,
        pendingApprovalType = null,
    )
