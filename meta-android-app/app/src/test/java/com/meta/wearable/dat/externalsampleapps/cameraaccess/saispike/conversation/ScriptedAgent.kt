/* sai-fi — voice concierge. */

// An agent on the other end of the real bridge.
//
// It implements [VoiceTransport], the seam UNDER HttpAgentBridge, so the bridge itself, its six
// methods mapping onto four `/v1/agents/*` operations, and the event plumbing are all the real ones.
// Faking AgentBridge instead would bypass exactly the layer a wire bug lives in.
//
// **What it replies is a function of what was forwarded**, not a fixed script the test wrote out in
// advance. That is the whole point: the golden catalog already pins "given these events, the FSM does
// this", and it cannot catch a drain that never fires, because the events it replays were written by
// hand on the assumption that it did. Here a queued task produces events only if something actually
// forwards it, so "the drain fired and the result reached the user" is an emergent property.
//
// It runs ONE task at a time, like the real thing: a machine has one agent. A forward arriving while
// one is running is recorded as such, which is how the queue's correctness becomes observable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceTransport
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import org.json.JSONArray
import org.json.JSONObject

/** One thing the agent does, some milliseconds into a turn. */
data class AgentBeat(val afterMs: Long, val event: AgentEvent)

/**
 * How the agent behaves for a task whose text matches [match].
 *
 * [beats] are relative to the moment the task starts. The last one is normally a `Complete`, and a
 * programme with no terminal event models a task that never finishes — which is a case worth having.
 */
data class AgentProgram(val match: (String) -> Boolean, val beats: List<AgentBeat>)

/** Everything a scenario can assert about what reached the agent. */
data class AgentCall(val method: String, val text: String? = null, val body: JSONObject? = null)

class ScriptedAgent(
    private val clock: HarnessClock,
    /** Delivers an event to the FSM, exactly as VoiceSession.followTurn does. */
    private val deliver: suspend (AgentEvent) -> Unit,
    private val log: (String) -> Unit = {},
) : VoiceTransport {

  /** Programmes are tried in order; the first match wins. */
  val programs = mutableListOf<AgentProgram>()

  /** A default for anything unmatched: acknowledge, then finish. */
  var fallback: List<AgentBeat> =
      listOf(
          AgentBeat(50, AgentEvent.Status(com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus.PROCESSING)),
          AgentBeat(500, AgentEvent.Complete("done")),
      )

  /** Every call that reached the agent, in order. */
  val calls = mutableListOf<AgentCall>()

  /** Task texts in the order the agent actually STARTED them. The queue's ground truth. */
  val started = mutableListOf<String>()

  /** Set when a forward should be refused, as an unreachable machine would. */
  var failNextSend: Boolean = false

  /** Tasks forwarded while another was still running — a queue bug, if the FSM let it happen. */
  val overlapped = mutableListOf<String>()

  private var running: String? = null
  private var runningBeats: MutableList<com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Cancellable> =
      mutableListOf()

  /** True while a task is in flight, so a scenario can assert the agent really is busy. */
  val isBusy: Boolean
    get() = running != null

  override suspend fun sendMessage(
      machineId: String,
      message: String,
      attachments: JSONArray?,
      follow: Boolean,
  ) {
    calls += AgentCall(if (follow) "forward" else "steer", text = message)
    if (failNextSend) {
      failNextSend = false
      log("[agent] refusing $message")
      throw RuntimeException("the machine is unreachable")
    }
    // A steer lands in a turn already being read: it produces no stream of its own, which is exactly
    // why HttpAgentBridge passes follow=false for it.
    if (!follow) return
    if (running != null) overlapped += message
    startTask(message)
    // Returns once ACCEPTED, not once done — the FSM holds its mutex across this call and needs to be
    // out of it before the events this task is about to produce arrive. Scheduling them on the clock
    // rather than delivering them here is what reproduces that.
  }

  override suspend fun post(path: String, body: JSONObject): JSONObject {
    calls += AgentCall(path, body = body)
    return when (path) {
      "new-session" -> JSONObject().put("sessionId", "S-harness")
      else -> JSONObject().put("ok", true)
    }
  }

  private fun startTask(text: String) {
    running = text
    started += text
    log("[agent] started: $text")
    val beats = programs.firstOrNull { it.match(text) }?.beats ?: fallback
    runningBeats =
        beats
            .map { beat ->
              clock.scheduleSuspending(beat.afterMs) {
                if (beat.event is AgentEvent.Complete || beat.event is AgentEvent.Error) {
                  running = null
                  log("[agent] finished: $text")
                }
                deliver(beat.event)
              }
            }
            .toMutableList()
  }

  /**
   * Stop the running task without finishing it — what `abort` does.
   *
   * Cancels its remaining beats rather than letting them land, because an aborted task does not go on
   * emitting progress and then report itself complete. Reporting a stopped task as finished is a
   * failure the golden catalog already names (`abort ≠ done`), and an agent double that kept
   * emitting would manufacture it here.
   */
  fun abortRunning() {
    runningBeats.forEach { it.cancel() }
    runningBeats.clear()
    running?.let { log("[agent] aborted: $it") }
    running = null
  }

  fun callsTo(method: String) = calls.filter { it.method == method }
}
