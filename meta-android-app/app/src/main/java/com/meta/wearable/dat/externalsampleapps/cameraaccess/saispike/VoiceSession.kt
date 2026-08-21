/* sai-fi — voice concierge. */

// One call's concierge: the FSM, its two ports, and the turn streams that drive it.
//
// This replaces ConciergeSocket. The socket carried effects UP to a server-side FSM and brought
// speak/instruct back DOWN; now the FSM is here, so effects go straight into it and the only thing
// arriving from the server is agent events.
//
// What that removes is a whole class of round trip: an interrupt used to be a WS frame, a server
// decision and a directive back. It is now a function call.
//
// There is no persistent connection either. A turn's events arrive on the response to the message
// that started it, so this session is CONNECTED ONLY WHILE THE AGENT IS WORKING — which is why
// there is no reconnect loop here any more, and why a reset is just a POST. What it costs is
// anything that happens between turns: an approval resolved in the desktop app while nothing is
// running is not heard.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Cancellable
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.CostGuard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.CostGuardReason
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Concierge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.DecisionEngine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.DecisionInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Effect
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.isWorking
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Timer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.VoiceChannel
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Told to the FSM when a turn's stream ends without the agent saying how it went.
 *
 * Phrased as a fact about THIS DEVICE's knowledge, not about the task: the agent may well still be
 * working, and the one thing that must not be said is that it finished. The model turns this into
 * whatever it wants to tell the user; what matters here is that it never hears "done".
 */
const val TURN_STREAM_LOST =
    "lost the connection to the agent partway through, so the outcome of that task is unknown — " +
        "it may still be running"

/**
 * The FSM's voice out, wired to the Live model.
 *
 * `say` is wrapped in "say this verbatim" and `instruct` is not — that difference is the whole point
 * of the two methods, and collapsing them is how a user ends up hearing a function name read aloud.
 */
class LiveVoiceChannel(
    private val speak: (kind: String, text: String) -> Unit,
) : VoiceChannel {
  override suspend fun say(text: String, supersedes: String?) {
    // The subject rides in the KIND, which is already the gate's key for a nudge — so superseding
    // needs no new channel between here and there.
    val kind = if (supersedes != null) "speak:$supersedes" else "speak"
    speak(kind, "[system] Say to the user, briefly and verbatim: \"$text\"")
  }

  override suspend fun instruct(text: String) {
    // Injected as sent, with no wrapper: the words themselves are never spoken, and what the user
    // hears is the model's own reply to them.
    speak("instruct", text)
  }
}

/**
 * The brain, as the FSM sees it.
 *
 * There is nothing to decide here: the Live model on this device already decided, and its tool calls
 * ARE the effects. The FSM asks for a decision at points where a server-side brain would have been
 * consulted; on this path those return nothing and the model acts on its own.
 */
object ClientBrain : DecisionEngine {
  override suspend fun decide(input: DecisionInput, state: com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState): List<Effect> = emptyList()
}

/** A Timer on the main looper, for the FSM's approval ping. */
class HandlerTimer(private val handler: Handler = Handler(Looper.getMainLooper())) : Timer {
  override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
    val r = Runnable { action() }
    handler.postDelayed(r, delayMs)
    return Cancellable { handler.removeCallbacks(r) }
  }
}

/**
 * Everything one call needs on the concierge side.
 *
 * Owns the FSM, the bridge, and the reader that feeds agent events into it. Reconnect lives here
 * too: the stream is a long-lived SSE connection, and a drop mid-call is expected rather than
 * exceptional.
 *
 * A call does NOT own a conversation. This used to mint a fresh `api` session on its first forward,
 * to bound how far a bad turn could travel — the hazard is real, and `docs/VOICE_FSM.md` keeps the
 * story. But a call ends far more easily than a conversation does: five quiet minutes, folding the
 * glasses, or the model hearing a goodbye. Every one of those minted a page the user had not asked
 * for, and one conversation could span four of them. The session is the SERVER's, resolved through
 * its `{uid}_{machineId}_{channel}` pointer, and the only thing that rotates it is the user saying
 * so — [HttpAgentBridge.resetSession], which works now and is what escaping a poisoned transcript
 * costs. Nothing here should rotate on its own initiative.
 */
class VoiceSession(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
    val machineId: String,
    private val scope: CoroutineScope,
    private val speak: (kind: String, text: String) -> Unit,
    /** Every agent event, for the activity log and the nudge router. */
    private val onAgentEvent: (AgentEvent) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onPermanentFailure: (Int) -> Unit,
    /**
     * A cost bound tripped — the call must end.
     *
     * These used to be WS close codes 4001/4002 from the server's guard. With no socket there is no
     * server-side notion of this call at all, so the bound is enforced HERE. It has to keep existing:
     * an open microphone costs money whether or not anyone is still wearing the glasses.
     */
    private val onCostGuard: (CostGuardReason) -> Unit = {},
    /**
     * Stop the DEVICE's share of the running turn — handed to the bridge, and called on abort.
     *
     * A turn is not only the request the server is working on: a glasses capture runs here, on this
     * phone, for as long as the camera takes. Aborting the remote half alone is what left the camera
     * grinding through its retries after the user said "stop", with the photo landing for a task that
     * had already been dropped.
     */
    private val abortLocalWork: () -> Unit = {},
    private val maxCallMs: Long? = DEFAULT_MAX_CALL_MS,
    private val idleMs: Long? = DEFAULT_IDLE_MS,
    private val onLog: (String) -> Unit = {},
) {
  companion object {
    /** Mirrors the server guard's old defaults: an hour of call, five minutes of silence. */
    const val DEFAULT_MAX_CALL_MS = 60L * 60_000
    const val DEFAULT_IDLE_MS = 5L * 60_000
  }
  private val transport =
      object : VoiceTransport {
        override suspend fun sendMessage(
            machineId: String,
            message: String,
            attachments: JSONArray?,
            follow: Boolean,
        ) {
          val stream =
              endCallIfRejected {
                VoiceChannelClient.openMessageStream(
                    baseUrl = baseUrl,
                    bearerToken = token(),
                    machineId = machineId,
                    message = message,
                    attachments = attachments,
                )
              }
          // A steer lands in a turn already being read; reading its stream too would deliver every
          // event of that turn a second time.
          if (!follow) {
            stream.discard()
            return
          }
          followTurn(stream)
        }

        override fun abandonTurn() = stopFollowingTurn("it was aborted")

        override suspend fun post(path: String, body: JSONObject): JSONObject =
            endCallIfRejected {
              VoiceChannelClient.postOperation(
                  baseUrl, token(), path, body.put("machineId", machineId))
            }
      }

  val bridge = HttpAgentBridge(machineId, transport, onLog, abortLocalWork)

  private val concierge =
      Concierge(
          agent = bridge,
          voice = LiveVoiceChannel(speak),
          engine = ClientBrain,
          timer = HandlerTimer(),
          log = onLog,
      )

  private var turnJob: Job? = null
  /**
   * The connection [turnJob] is reading, held so it can be disconnected.
   *
   * Cancelling the job does not end the read: it is parked in a blocking `readLine` on a connection
   * with no read timeout, and coroutine cancellation cannot interrupt that. Only closing the
   * connection can, which is what [VoiceChannelClient.TurnStream.discard] does.
   */
  private var turnStream: VoiceChannelClient.TurnStream? = null
  /**
   * Which turn the reader below is allowed to speak for. Bumped by every teardown and every new turn.
   *
   * Cancelling [turnJob] is not enough on its own, and the gap is not theoretical: an event can be
   * past its `ensureActive` check and suspended on the FSM's mutex — the very mutex an abort holds
   * while it runs — so it resumes after the interrupt and applies against a state that has already
   * been cleared. A generation is checked at the point of USE, which is the only place late enough.
   */
  @Volatile private var turnGeneration = 0
  @Volatile private var active = false

  private val guard =
      CostGuard(
          maxMs = maxCallMs,
          idleMs = idleMs,
          timer = HandlerTimer(),
          onExpire = { reason ->
            onLog("[voice] cost guard tripped: ${reason.wire}")
            onCostGuard(reason)
          },
      )

  /**
   * Register genuine interaction.
   *
   * Deliberately NOT called for every agent event: a long task emits progress for minutes with
   * nobody in the room, and counting that as activity is exactly the walked-away call the idle bound
   * exists to end. Effects and the user's own speech are the signals that someone is still here.
   */
  fun touch() = guard.touch()

  @Volatile private var lastResponseTokens = 0

  /**
   * Live usage totals, as the model reports them (cumulative).
   *
   * Only a rise in RESPONSE tokens counts as activity. Input tokens grow continuously while a
   * microphone is merely open, so treating them as a live conversation is exactly the walked-away
   * call the idle bound exists to end. A decrease means the Live session restarted, so the baseline
   * follows it down rather than going negative.
   */
  fun onUsage(promptTokens: Int, responseTokens: Int) {
    if (responseTokens < lastResponseTokens) {
      lastResponseTokens = responseTokens
      return
    }
    if (responseTokens > lastResponseTokens) {
      lastResponseTokens = responseTokens
      guard.touch()
    }
  }

  init {
    // The FSM owns the pre-expiry ping; re-entering it has to go back through the lock.
    concierge.onApprovalTimeoutFired = { scope.launch { concierge.onApprovalTimeoutWarning() } }
  }

  /** A fresh Firebase ID token per attempt — a long call outlives the ~1h one it started with. */
  private suspend fun token(): String = tokenProvider() ?: error("no auth token")

  fun start() {
    active = true
  }

  /**
   * Read a turn's events until it ends, off the FSM's lock.
   *
   * Launched rather than awaited: the send that produced this stream is running inside the FSM's
   * mutex, and every event here needs that same mutex. Awaiting would deadlock the call on its own
   * first task.
   *
   * A drop is NOT reconnected. The stream belongs to one turn, and there is no way to rejoin a turn
   * already in progress — so the honest thing is to stop following it. The FSM would otherwise sit
   * in `working` forever, so the turn is closed out here on the way past.
   *
   * Nothing here reports a PERMANENT rejection, because the status was already checked when the
   * stream was opened — see [endCallIfRejected], which is the write path and the only place a 401 or
   * a 403 can now surface.
   */
  private fun followTurn(stream: VoiceChannelClient.TurnStream) {
    stopFollowingTurn("a newer turn superseded it")
    turnStream = stream
    val gen = turnGeneration
    turnJob =
        scope.launch {
          try {
            // Reaching the agent again clears any earlier failure. Reported on the way IN rather
            // than held for the whole call, because between turns there is nothing connected to
            // report the state of.
            onConnectionChange(true)
            stream.read(
                onEvent = { event ->
                  // A turn this device has stopped following must not be heard from. `onAgentEvent`
                  // does not suspend, so without this check it ran — a line in the activity log and
                  // a nudge in front of the model — before the suspending call below noticed the
                  // cancellation. One stale event from an abandoned turn is enough to make Sai
                  // report the wrong task's result.
                  currentCoroutineContext().ensureActive()
                  // The generation as well, because `ensureActive` cannot see an event that is
                  // already past it and parked on the FSM's mutex — which is the mutex an abort
                  // holds while it runs. Both sinks are gated, not just the FSM: `onAgentEvent` is
                  // the one that writes the activity log and puts the nudge in front of the model,
                  // and on 2026-08-20 it is the one that actually spoke a stopped task's result.
                  if (gen != turnGeneration) {
                    onLog("[voice] dropped ${event::class.simpleName} from an abandoned turn")
                    return@read
                  }
                  // And the same two sinks again for the turn the user ABORTED, which the generation
                  // above cannot catch: not following a stream stops what we would have read FROM it,
                  // but a message posted into a turn the server never actually stopped is folded in as
                  // a steer and replays that turn's events onto the stream we ARE reading. The abort
                  // is a request over a round trip, not a guarantee — so the answer to a cancelled
                  // question can arrive by a route nothing abandoned.
                  if (concierge.disownsAgentEvents()) {
                    onLog("[voice] dropped ${event::class.simpleName} from an aborted turn")
                    return@read
                  }
                  // The log and the nudge router see every event; the FSM decides what it means.
                  onAgentEvent(event)
                  concierge.handleAgentEvent(event)
                },
                onLog = onLog,
            )
            // Deliberately NOT reported as disconnected here. A turn ending normally is the common
            // case and leaves nothing connected by design; flagging it would light the chip — which
            // outranks paused and muted — for the whole gap until the next task.
          } catch (e: CancellationException) {
            // Superseded or torn down — not a drop. Reported as one it lit the disconnected chip and
            // then told the FSM its live turn had been lost, on the way to starting a new one.
            throw e
          } catch (e: Exception) {
            // Same generation test, and it is load-bearing here rather than tidy: `discard()` is
            // what unblocks the parked `readLine`, and it does so by breaking the socket — so an
            // intentional teardown surfaces as an IOException whenever it beats the cancellation
            // through. Reported as a drop it would light the disconnected chip and then tell the FSM
            // the turn was LOST, which says "it may still be running" about work we just stopped.
            if (gen != turnGeneration) throw CancellationException("turn abandoned")
            onLog("[voice] turn stream dropped: ${e.message}")
            onConnectionChange(false)
          }
          // Whatever ended the stream, the turn is over as far as this device can tell. Told to the
          // FSM as an error rather than a completion: a dropped stream is not a finished task, and
          // reporting it as one is how "all done" gets said about work that may still be running.
          if (active && gen == turnGeneration && concierge.getState().isWorking()) {
            concierge.handleAgentEvent(AgentEvent.Error(TURN_STREAM_LOST))
          }
          // Only if it is still ours: a newer turn may already have taken the slot.
          if (turnStream === stream) turnStream = null
        }
  }

  /**
   * Stop reading the turn in flight: cancel the reader, hang up on the connection, retire the
   * generation.
   *
   * All three, because each covers what the others cannot. Cancelling the job does not end the read —
   * it is parked in a blocking `readLine` on a connection with no read timeout — so the socket stayed
   * open and an IO thread stayed parked until the server got round to ending the turn itself.
   * Closing the connection does not stop an event already parsed and waiting on the FSM's mutex,
   * which is what the generation is for.
   *
   * This is the teardown `applyInterrupt` has always said happened. It did not, and the cost was a
   * task the user had stopped being read to its natural end and reported as finished.
   */
  private fun stopFollowingTurn(why: String) {
    if (turnJob == null && turnStream == null) return
    onLog("[voice] stopped following the turn — $why")
    turnGeneration++
    turnJob?.cancel()
    turnJob = null
    turnStream?.discard()
    turnStream = null
  }

  /**
   * End the call on a rejection that will not fix itself, and re-throw either way.
   *
   * The persistent stream used to be where a 401 or a 403 surfaced, and it ended the call. There is
   * no persistent stream now: every request is a write, and a write that fails is caught by the FSM,
   * which apologises and carries on. Without this, an expired credential or a machine that is no
   * longer the user's would make every task fail identically, forever, with the call still up.
   *
   * Re-throws regardless — the FSM still needs to hear that this particular task did not start, and
   * swallowing it would leave the user with silence instead of an apology.
   */
  private suspend fun <T> endCallIfRejected(block: suspend () -> T): T =
      try {
        block()
      } catch (e: ConciergeHttpException) {
        if (ReconnectPolicy.isPermanent(e.status)) {
          onLog("[voice] rejected permanently (${e.status}) — ending the call")
          onPermanentFailure(e.status)
        }
        throw e
      }

  /** The model's tool calls, straight into the FSM. No round trip. */
  fun applyEffects(effects: JSONArray) {
    guard.touch() // a model effect means a live conversation
    scope.launch {
      runCatching { concierge.applyClientEffects(effects) }
          .onFailure { onLog("[voice] effects failed: ${it.message}") }
    }
  }

  fun close() {
    active = false
    stopFollowingTurn("the call ended")
    guard.dispose()
    concierge.stop()
  }

  /** What the FSM currently believes, for tests and the activity view. */
  fun state() = concierge.getState()
}
