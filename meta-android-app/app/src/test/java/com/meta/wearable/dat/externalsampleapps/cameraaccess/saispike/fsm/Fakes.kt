/* sai-fi — voice concierge. */

// The three seams, faked. These are what let the whole state machine be driven in a plain JUnit run
// with no network and no Android — and they are what the golden scenarios will be written against.
//
// FakeChannel records `say` and `instruct` SEPARATELY on purpose. The two are not interchangeable
// (one is spoken verbatim, one never reaches the user), and every recorded regression in this area
// was a line going out on the wrong one. A fake that merged them could not catch that.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** One recorded call to the bridge. */
data class BridgeCall(val method: String, val args: Map<String, Any?> = emptyMap())

class FakeAgent(
    var forwardFails: Boolean = false,
    var queueFails: Boolean = false,
    var queueStartsImmediately: Boolean = false,
    var cancelOutcome: CancelOutcome = CancelOutcome.CANCELLED,
    var sendNowOutcome: SendNowOutcome = SendNowOutcome.SENT,
    var resetOutcome: ResetOutcome = ResetOutcome.OK,
    var resolveFails: Boolean = false,
) : AgentBridge {
  val calls = mutableListOf<BridgeCall>()
  private var pendingSeq = 0
  var stash: List<TaskAttachment> = emptyList()

  fun callsTo(method: String) = calls.filter { it.method == method }

  override suspend fun forwardTask(text: String, attachments: List<TaskAttachment>?): String {
    calls += BridgeCall("forwardTask", mapOf("text" to text, "attachments" to attachments))
    if (forwardFails) throw RuntimeException("forward failed")
    return "S-test"
  }

  override suspend fun queueTask(text: String, attachments: List<TaskAttachment>?): String {
    calls += BridgeCall("queueTask", mapOf("text" to text, "attachments" to attachments))
    if (queueStartsImmediately) throw TaskStartedImmediately()
    if (queueFails) throw RuntimeException("queue failed")
    return "p${++pendingSeq}"
  }

  override suspend fun cancelQueuedTask(pendingId: String): CancelOutcome {
    calls += BridgeCall("cancelQueuedTask", mapOf("pendingId" to pendingId))
    return cancelOutcome
  }

  override suspend fun sendQueuedNow(pendingId: String): SendNowOutcome {
    calls += BridgeCall("sendQueuedNow", mapOf("pendingId" to pendingId))
    return sendNowOutcome
  }

  override fun takePendingAttachments(): List<TaskAttachment> {
    calls += BridgeCall("takePendingAttachments")
    val taken = stash
    stash = emptyList()
    return taken
  }

  override suspend fun steer(text: String) {
    calls += BridgeCall("steer", mapOf("text" to text))
  }

  override suspend fun abort() {
    calls += BridgeCall("abort")
  }

  override suspend fun resetSession(): ResetOutcome {
    calls += BridgeCall("resetSession")
    return resetOutcome
  }

  override suspend fun resolveApproval(
      id: String,
      decision: ApprovalDecision,
      selection: ApprovalSelection?,
  ) {
    calls +=
        BridgeCall(
            "resolveApproval",
            mapOf("id" to id, "decision" to decision, "selection" to selection))
    if (resolveFails) throw RuntimeException("resolve rejected")
  }
}

class FakeChannel : VoiceChannel {
  /** Heard by the user, verbatim. */
  val spoken = mutableListOf<String>()

  /** Reaches the model as context; never voiced. */
  val instructed = mutableListOf<String>()

  override suspend fun say(text: String) {
    spoken += text
  }

  override suspend fun instruct(text: String) {
    instructed += text
  }

  fun spokenHas(fragment: String) = spoken.any { it.contains(fragment) }

  fun instructedHas(fragment: String) = instructed.any { it.contains(fragment) }
}

/** A scripted brain: each input yields whatever the script says, defaulting to no effects. */
class FakeEngine(var script: (DecisionInput, ConciergeState) -> List<Effect> = { _, _ -> emptyList() }) :
    DecisionEngine {
  val seen = mutableListOf<DecisionInput>()

  override suspend fun decide(input: DecisionInput, state: ConciergeState): List<Effect> {
    seen += input
    return script(input, state)
  }
}

/** A timer that never fires on its own — the test fires it. */
class FakeTimer : Timer {
  data class Scheduled(val delayMs: Long, val action: () -> Unit)

  val scheduled = mutableListOf<Scheduled>()

  override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
    val entry = Scheduled(delayMs, action)
    scheduled += entry
    return Cancellable { scheduled.remove(entry) }
  }

  /** Fire the most recently scheduled callback, as a real timer would. */
  fun fireLast() {
    scheduled.lastOrNull()?.action?.invoke()
  }
}
