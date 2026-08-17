/* sai-fi — voice concierge. */

// The closed loop, with everything real except the brain and the agent.
//
//     brain ──tool calls──▶ LiveTurnGate ──effects──▶ Concierge ──▶ HttpAgentBridge
//       ▲                        │                       │              │
//       │                   defer/inject                 │       ScriptedAgent (VoiceTransport)
//       │                        ▲                       ▼              │
//       └───nudges / [system]────┴── AgentEventRouter ◀── agent events ──┘
//
// Layer 1 (the golden catalog) fakes the brain; layer 2 (the eval) fakes the FSM and the agent. The
// failures that live in the JOIN are invisible to both, and they are the ones the on-device check
// calls its highest risk — "if Sai says 'I'll do that next' and then it never runs, the drain never
// fired". Nothing in either layer can catch that, because in one the drain is scripted and in the
// other there is no queue at all.
//
// What is REAL here: Concierge (the FSM), HttpAgentBridge and its transport seam, LiveTurnGate,
// LiveVoiceChannel, AgentEventRouter, HeldNudgeQueue, ActivityLog, and the effect-parse boundary
// (`applyClientEffects`) the model's tool calls actually go through.
//
// What is not: the model (a [Brain]) and the agent (a [ScriptedAgent]).
//
// The client-policy block below — activity log, nudge routing, mute holding — is deliberately a
// mirror of CallService's `onAgentEvent`. That code is welded to a Service and cannot be reached
// from a JVM test; keeping the mirror small and pointing at the original is the cheaper half of the
// trade. If the two drift, the on-device check is what notices, so any change there belongs here too.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ActivityLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.AgentEventRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.GateAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.HeldNudgeQueue
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.HttpAgentBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.LiveTurnGate
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.LiveVoiceChannel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.NudgeAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.TaskRouting
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.agentEventToJson
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ClientBrain
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Concierge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import org.json.JSONArray
import org.json.JSONObject

/** A line in the conversation, as a person listening to the call would experience it. */
data class Line(val speaker: String, val text: String)

/** Defaults mirroring CallService's. */
private const val DEFAULT_ASK_FIRST_MS = 45_000L
private const val STEP_FAILURE_NUDGE_INTERVAL_MS = 60_000L

class ConversationHarness(
    private val brain: Brain,
    val clock: HarnessClock = HarnessClock(),
    /** How long the model's speech occupies the channel. The window in which a nudge gets deferred. */
    var speakingMs: Long = 800L,
) {
  val log = mutableListOf<String>()

  private fun log(line: String) {
    log += line
  }

  val gate = LiveTurnGate { clock.now }
  val activityLog = ActivityLog { clock.now }
  private val heldNudges = HeldNudgeQueue()

  val agent = ScriptedAgent(clock, deliver = { onAgentEvent(it) }, log = ::log)

  /** Everything the user would have heard, plus what the agent said, in order. */
  val transcript = mutableListOf<Line>()

  /** Every session-state projection the FSM published. */
  val sessionStates = mutableListOf<AgentEvent.SessionState>()

  var muted: Boolean = false
    private set

  private var lastUserSpeechAt = 0L
  private var lastTaskForwardAt = 0L
  private var lastStepFailureNudgeAt = 0L

  private val bridge = HttpAgentBridge("M-harness", agent, ::log)

  val concierge =
      Concierge(
          agent = bridge,
          voice = LiveVoiceChannel { kind, text -> pushToModel(kind, text) },
          engine = ClientBrain,
          timer = clock,
          onSessionState = { s ->
            sessionStates += s
            activityLog.record(agentEventToJson(s))
          },
          now = { clock.now },
          log = ::log,
      )

  val state: ConciergeState
    get() = concierge.getState()

  /** What `getSaiStatus` would answer right now — the same renderer the device uses. */
  fun status(): String = activityLog.statusText()

  // ── Driving the conversation ─────────────────────────────────────────────────────────────────────

  /** Bring the Live session up. */
  suspend fun start() {
    runGate(gate.onConnect())
    runGate(gate.onSetupComplete())
  }

  /** The user says something. */
  suspend fun user(utterance: String) {
    lastUserSpeechAt = clock.now
    transcript += Line("you", utterance)
    gate.onUserTranscript(utterance)
    modelTurn(utterance)
  }

  /**
   * The user talks over the model.
   *
   * Two things happen on a real barge-in and both matter: the server VAD raises `interrupted`, which
   * ends the model's turn and opens the discard window, and then the new utterance is a fresh turn.
   */
  suspend fun bargeIn(utterance: String) {
    runGate(gate.onInterrupted())
    log("— barge-in —")
    user(utterance)
  }

  /** Mute / unmute, which on the device is the temple button or the on-screen control. */
  suspend fun setMuted(value: Boolean) {
    muted = value
    if (value) {
      runGate(gate.injectSessionState("muted", "[system] you are muted", sticky = true))
    } else {
      runGate(gate.injectSessionState("unmuted", "[system] you are unmuted", sticky = false))
      // Anything that finished while muted is offered now — once, not replayed as a pile.
      heldNudges.drain().forEach { runGate(gate.injectNudge(it.kind, it.nudge)) }
    }
  }

  /** A token-expiry reconnect mid-call. */
  suspend fun reconnect() {
    runGate(gate.onConnect())
    runGate(gate.onSetupComplete())
  }

  /** Let time pass, delivering whatever the agent had scheduled. */
  suspend fun advance(ms: Long) = clock.advance(ms)

  /** Run until the agent has nothing left to say. */
  suspend fun settle() = clock.drain()

  // ── The model's half ─────────────────────────────────────────────────────────────────────────────

  /**
   * Give the model a turn, and put whatever it decided through the real boundaries.
   *
   * The order matters and mirrors the device: the model's speech opens the turn (so a nudge arriving
   * now is deferred by the gate), its tool calls go through the same routing GeminiLiveClient
   * applies, and the turn closes [speakingMs] later — which is when anything held gets released.
   */
  private suspend fun modelTurn(input: String) {
    val turn = brain.turn(input, state)
    if (!turn.speech.isNullOrBlank()) {
      runGate(gate.onSaiTranscript(turn.speech))
      transcript += Line("sai", turn.speech)
    }
    routeCalls(turn.calls)
    // The turn ends after the model has finished speaking, not instantly: the gap is the window in
    // which a completion landing mid-sentence is held, which is the race worth testing.
    clock.scheduleSuspending(if (turn.speech.isNullOrBlank()) 0 else speakingMs) {
      runGate(gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true))
    }
  }

  /** The tool-call half of GeminiLiveClient.handleToolCall, minus the socket. */
  private suspend fun routeCalls(calls: JSONArray) {
    val effects = JSONArray()
    val hasCapture =
        (0 until calls.length()).any { calls.getJSONObject(it).optString("name") == "captureImage" }
    for (i in 0 until calls.length()) {
      val c = calls.getJSONObject(i)
      val name = c.optString("name")
      val effect = fcToEffect(c)
      when (name) {
        "getSaiStatus" -> log("→ tool: getSaiStatus → ${status()}")
        "forwardToAgent",
        "enqueue",
        "relayToAgent" -> {
          val wantsPhoto = c.optJSONObject("args")?.optBoolean("attachLatestImage") == true
          when (val routing = gate.routeTaskCall(name, effect, wantsPhoto, hasCapture)) {
            is TaskRouting.HeldForPhoto -> log(routing.log)
            is TaskRouting.Emit -> {
              effects.put(effect)
              log(routing.log)
            }
          }
        }
        else -> {
          effects.put(effect)
          log("→ effect: $name")
        }
      }
    }
    if (effects.length() > 0) {
      lastTaskForwardAt = clock.now
      concierge.applyClientEffects(effects)
    }
  }

  /** `{ kind: name, ...args }` — the concierge effect shape, as GeminiLiveClient builds it. */
  private fun fcToEffect(fc: JSONObject): JSONObject {
    val eff = JSONObject().put("kind", fc.optString("name"))
    fc.optJSONObject("args")?.let { args -> args.keys().forEach { eff.put(it, args.get(it)) } }
    return eff
  }

  /** The FSM's voice out, wired the way CallService wires it: everything goes through the gate. */
  private fun pushToModel(kind: String, text: String) {
    // Queued rather than run inline: `say` is called from inside the FSM's mutex, and giving the
    // model a turn here would re-enter it.
    clock.scheduleSuspending(0) { runGate(gate.injectNudge(kind, text)) }
  }

  // ── The client's own policy on agent events (mirrors CallService.onAgentEvent) ────────────────────

  private suspend fun onAgentEvent(e: AgentEvent) {
    concierge.handleAgentEvent(e)

    val json = agentEventToJson(e)
    activityLog.record(json)
    if (e is AgentEvent.Complete || e is AgentEvent.Error) {
      transcript += Line("agent", json.optString("summary").ifEmpty { json.optString("text") })
    }

    val action =
        AgentEventRouter.route(
            event = json,
            muted = muted,
            userQuietMs =
                when {
                  lastUserSpeechAt == 0L -> Long.MAX_VALUE
                  lastTaskForwardAt > lastUserSpeechAt -> lastTaskForwardAt - lastUserSpeechAt
                  else -> clock.now - lastUserSpeechAt
                },
            askFirstThresholdMs = DEFAULT_ASK_FIRST_MS,
            sinceLastStepFailureMs =
                if (lastStepFailureNudgeAt == 0L) Long.MAX_VALUE
                else clock.now - lastStepFailureNudgeAt,
            stepFailureIntervalMs = STEP_FAILURE_NUDGE_INTERVAL_MS,
        )
    when (action) {
      is NudgeAction.Ignore -> {}
      is NudgeAction.Drop -> log("→ nudge: ${action.why}")
      is NudgeAction.InjectStepFailure -> {
        lastStepFailureNudgeAt = clock.now
        runGate(gate.injectNudge("step-failed", action.nudge))
      }
      is NudgeAction.Inject -> runGate(gate.injectNudge(action.kind, action.nudge))
      is NudgeAction.Hold ->
          if (!heldNudges.add(action.kind, action.nudge)) {
            log("→ nudge: ${action.kind} — discarded while muted (stale by the time it could be heard)")
          }
    }
  }

  // ── The gate's action interpreter ────────────────────────────────────────────────────────────────

  /**
   * Perform what the gate decided — the same job GeminiLiveClient.run does, except that a turn sent
   * to the model comes back here as another model turn, which is what closes the loop.
   */
  private suspend fun runGate(actions: List<GateAction>) {
    for (action in actions) {
      when (action) {
        is GateAction.Log -> log(action.text)
        is GateAction.SendTurn -> modelTurn(action.text)
        is GateAction.SaiTranscript -> {}
        is GateAction.UserTranscript -> {}
        is GateAction.TurnComplete -> {}
        is GateAction.FlushPlayback -> {}
        is GateAction.ReleaseEffects -> concierge.applyClientEffects(action.effects)
      }
    }
  }

  // ── Assertion helpers ────────────────────────────────────────────────────────────────────────────

  /** Everything the user heard, lowercased and joined — for "was this ever said" checks. */
  fun heard(): String = transcript.filter { it.speaker == "sai" }.joinToString(" | ") { it.text }.lowercase()

  fun saidSomethingLike(vararg fragments: String) = fragments.any { heard().contains(it.lowercase()) }

  fun logHas(fragment: String) = log.any { it.contains(fragment) }
}
