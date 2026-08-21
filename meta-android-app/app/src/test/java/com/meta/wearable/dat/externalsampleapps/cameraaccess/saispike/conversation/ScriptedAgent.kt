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

  /**
   * Whether `POST abort` actually stops the run — the SERVER's half of a stop, and not a given.
   *
   * True is the contract as documented. False is what a device log showed on 2026-08-20: a 2xx came
   * back and the agent carried on through two more tool calls and a full answer. A double that can
   * only model the happy version cannot be used to prove the client survives the other one.
   */
  var abortStopsTheRun: Boolean = true

  /**
   * Events the agent PRODUCED after this device stopped following — whether they got through or not.
   *
   * Its job is to keep the negative assertion honest. "The user was never told" passes just as well
   * when the agent quietly stopped producing, which is not the case under test: the whole scenario is
   * a server that ignored the abort and carried on. A scenario asserts this is non-zero first, so the
   * silence it then checks is a silence that had to be enforced.
   */
  var producedAfterAbandon: Int = 0
    private set

  /** Of those, the ones that actually reached the FSM. Must stay at zero. */
  var deliveriesAfterAbandon: Int = 0
    private set

  /** False once [abandonTurn] has run, until the next forward opens a turn of its own. */
  private var following = false
  private var abandoned = false

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
    val task = requestOf(message)
    if (running != null) overlapped += task
    startTask(task)
    // Returns once ACCEPTED, not once done — the FSM holds its mutex across this call and needs to be
    // out of it before the events this task is about to produce arrive. Scheduling them on the clock
    // rather than delivering them here is what reproduces that.
  }

  /**
   * The user's words, with the context the bridge appends stripped back off.
   *
   * `taskText` fences everything that is not the request into `[Context, …]` blocks after a blank
   * line — the clock on every task, the location fix on the ones that asked for it. Recording the
   * whole envelope as the task would make [started] and [overlapped] answer a question no scenario
   * is asking, and would break every one of them the day a new block is added. So the double records
   * what the user said, which is what "the agent started this task" is supposed to mean.
   *
   * Program matching still runs against the FULL message on purpose: a program that wants to key on
   * something in the envelope can, and a `contains` on the request works either way.
   */
  private fun requestOf(message: String) = message.substringBefore("\n\n[Context,").trimEnd()

  /**
   * The CLIENT's half of a stop: this device is no longer listening to the turn.
   *
   * Deliberately does nothing to the run — the agent's beats go on being produced, exactly as a
   * server that ignored the abort would. They simply stop reaching the FSM, and each one that would
   * have is counted so a scenario can insist the silence is real rather than incidental.
   *
   * Keeping this separate from `post("abort")` is the point. Conflating the two is how the original
   * bug hid: this double stopped the run and the reader in one atomic free action, so "an aborted
   * task is not reported as done" passed while production did neither.
   */
  override fun abandonTurn() {
    if (!following) return
    log("[agent] client stopped following the turn")
    abandoned = true
    following = false
  }

  override suspend fun post(path: String, body: JSONObject): JSONObject {
    calls += AgentCall(path, body = body)
    if (path == "abort" && abortStopsTheRun) abortRunning()
    return when (path) {
      "new-session" -> JSONObject().put("sessionId", "S-harness")
      "abort" -> JSONObject().put("aborted", abortStopsTheRun)
      else -> JSONObject().put("ok", true)
    }
  }

  private fun startTask(text: String) {
    running = text
    started += text
    // Its own turn, its own stream — a previous abandon says nothing about this one, which is what
    // makes a `running`-scoped interrupt able to drop one task and hear the next one normally.
    following = true
    abandoned = false
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
                if (abandoned) producedAfterAbandon++
                if (!following) {
                  log("[agent] produced ${beat.event::class.simpleName} — nobody is listening")
                  return@scheduleSuspending
                }
                if (abandoned) deliveriesAfterAbandon++
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
