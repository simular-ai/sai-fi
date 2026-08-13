/* sai-fi — voice concierge. */

// The three seams, faked. These are what let the whole state machine be driven in a plain JUnit run
// with no network and no Android — and they are what the golden scenarios will be written against.
//
// FakeChannel records `say` and `instruct` SEPARATELY on purpose. The two are not interchangeable
// (one is spoken verbatim, one never reaches the user), and every recorded regression in this area
// was a line going out on the wrong one. A fake that merged them could not catch that.
//
// FakeAgent has no queue of its own. It used to, because the server held one and the two could
// disagree — the agent starting a task the FSM still believed was waiting. The queue is local now,
// so the FSM's own queue IS the whole truth and a second copy here could only lie about it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** One recorded call to the bridge. */
data class BridgeCall(val method: String, val args: Map<String, Any?> = emptyMap())

class FakeAgent(
    var forwardFails: Boolean = false,
    var resetOutcome: ResetOutcome = ResetOutcome.OK,
    var resolveFails: Boolean = false,
) : AgentBridge {
  val calls = mutableListOf<BridgeCall>()
  var stash: List<TaskAttachment> = emptyList()

  fun callsTo(method: String) = calls.filter { it.method == method }

  override suspend fun forwardTask(text: String, attachments: List<TaskAttachment>?): String {
    calls += BridgeCall("forwardTask", mapOf("text" to text, "attachments" to attachments))
    if (forwardFails) throw RuntimeException("forward failed")
    return "S-test"
  }

  /** The next immediate forward fails — the machine is unreachable, or the write is rejected. */
  fun failForwardTask() {
    forwardFails = true
  }

  override fun takePendingAttachments(): List<TaskAttachment> {
    calls += BridgeCall("takePendingAttachments")
    val taken = stash
    stash = emptyList()
    return taken
  }

  /** A glasses capture landing on the bridge, waiting for whatever writes next. */
  fun addPendingAttachment(attachment: TaskAttachment) {
    stash = stash + attachment
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
