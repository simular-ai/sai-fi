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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private val maxCallMs: Long? = DEFAULT_MAX_CALL_MS,
    private val idleMs: Long? = DEFAULT_IDLE_MS,
    private val onLog: (String) -> Unit = {},
) {
  companion object {
    /** Mirrors the server guard's old defaults: an hour of call, five minutes of silence. */
    const val DEFAULT_MAX_CALL_MS = 60L * 60_000
    const val DEFAULT_IDLE_MS = 5L * 60_000
  }
  /**
   * A call gets a conversation of its own.
   *
   * Without this every call shares one dedicated `api` session that grows forever, and that is a
   * standing hazard rather than a tidiness problem: anything that lands in the transcript is read
   * back as the agent's own prior turns on every later call, so one bad turn is not a bad turn, it
   * is a permanent change of behaviour. That is not hypothetical — a stubbed reply written during
   * local testing was still being imitated by the real agent days later, on a machine doing real
   * work, and no amount of fixing the code that wrote it helped, because the fix does not reach the
   * data. A call boundary bounds the blast radius to one call.
   *
   * Started lazily and awaited by the FIRST forward rather than fired at `start()`: a call that
   * never asks for anything should not mint a session, and rotating in the background would race
   * the first turn into the OLD session, which is the one case this exists to prevent.
   *
   * Never fatal. A failed rotation (offline, or the server's 20/hour cap) leaves the call on the
   * previous session, which is exactly today's behaviour — degraded, not broken. Auth failures are
   * deliberately not routed through [endCallIfRejected]; the first real send reports those, and it
   * reports them better.
   */
  private val ownSession: Deferred<Unit> by lazy {
    scope.async {
      runCatching {
            VoiceChannelClient.postOperation(
                baseUrl,
                token(),
                "new-session",
                // `channel` is required, not cosmetic: the route defaults to `cli`, so omitting it
                // rotates the TERMINAL's conversation and leaves this one exactly where it was.
                JSONObject().put("machineId", machineId).put("channel", "api"))
          }
          .onFailure {
            onLog("[voice] kept the previous session — could not start a fresh one (${it.message})")
          }
      Unit
    }
  }

  private val transport =
      object : VoiceTransport {
        override suspend fun sendMessage(
            machineId: String,
            message: String,
            attachments: JSONArray?,
            follow: Boolean,
        ) {
          // Before the first forward, never after: `ownSession` completes once per call.
          ownSession.await()
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

        override suspend fun post(path: String, body: JSONObject): JSONObject =
            endCallIfRejected {
              VoiceChannelClient.postOperation(
                  baseUrl, token(), path, body.put("machineId", machineId))
            }
      }

  val bridge = HttpAgentBridge(machineId, transport, onLog)

  private val concierge =
      Concierge(
          agent = bridge,
          voice = LiveVoiceChannel(speak),
          engine = ClientBrain,
          timer = HandlerTimer(),
          log = onLog,
      )

  private var turnJob: Job? = null
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
    turnJob?.cancel()
    turnJob =
        scope.launch {
          try {
            // Reaching the agent again clears any earlier failure. Reported on the way IN rather
            // than held for the whole call, because between turns there is nothing connected to
            // report the state of.
            onConnectionChange(true)
            stream.read(
                onEvent = { event ->
                  // The log and the nudge router see every event; the FSM decides what it means.
                  onAgentEvent(event)
                  concierge.handleAgentEvent(event)
                },
                onLog = onLog,
            )
            // Deliberately NOT reported as disconnected here. A turn ending normally is the common
            // case and leaves nothing connected by design; flagging it would light the chip — which
            // outranks paused and muted — for the whole gap until the next task.
          } catch (e: Exception) {
            onLog("[voice] turn stream dropped: ${e.message}")
            onConnectionChange(false)
          }
          // Whatever ended the stream, the turn is over as far as this device can tell. Told to the
          // FSM as an error rather than a completion: a dropped stream is not a finished task, and
          // reporting it as one is how "all done" gets said about work that may still be running.
          if (active && concierge.getState().isWorking()) {
            concierge.handleAgentEvent(AgentEvent.Error(TURN_STREAM_LOST))
          }
        }
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
    turnJob?.cancel()
    turnJob = null
    guard.dispose()
    concierge.stop()
  }

  /** What the FSM currently believes, for tests and the activity view. */
  fun state() = concierge.getState()
}
