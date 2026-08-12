/* sai-fi — voice concierge. */

// The orchestrator: the one place inputs are serialised and effects are dispatched.
//
// WHY A MUTEX. Every handler is read-state → suspend on I/O → write-state. Without serialisation two
// of them interleave at the suspension point and the second writes over a state the first already
// changed. Concretely: two forwards both see an empty inFlight before either records a turn, and the
// user's restaurant gets booked twice. The server does this with a promise-tail chain; a single
// Mutex is the same guarantee.
//
// This departs from the app's usual idiom, which is thread-confinement to Dispatchers.Main.immediate
// plus @Volatile. That idiom would work at runtime and cannot be unit-tested: Dispatchers.Main has
// no implementation in a plain JUnit run and nothing in this suite installs one. The FSM has 62
// golden scenarios to satisfy, so it stays dispatcher-agnostic and owns its own serialisation.
//
// Ported from cloud-api `services/concierge/voice/core/concierge.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** How long before an approval expires the user gets a heads-up. */
const val APPROVAL_TIMEOUT_LEAD_MS = 20_000L

/** What prompted a decision. */
sealed interface DecisionInput {
  data class User(val utterance: String) : DecisionInput

  data class Agent(val event: AgentEvent) : DecisionInput

  data object ApprovalTimeout : DecisionInput
}

/**
 * The brain. In production this is the client's own Live model, reached by handing it the input and
 * reading back the tool calls it makes; in tests it is a script.
 */
interface DecisionEngine {
  suspend fun decide(input: DecisionInput, state: ConciergeState): List<Effect>
}

/** The only mapping from a wait reason to a mode. */
private val MODE_FOR_WAIT =
    mapOf(
        WaitReason.CLARIFICATION to Mode.CLARIFYING,
        WaitReason.URGENCY to Mode.NEGOTIATING,
        WaitReason.APPROVAL to Mode.AWAITING_USER,
        WaitReason.INPUT to Mode.AWAITING_USER,
    )

class Concierge(
    private val agent: AgentBridge,
    private val voice: VoiceChannel,
    private val engine: DecisionEngine,
    private val timer: Timer,
    /** Optional sink for the session projection the client's activity log reads. */
    private val onSessionState: (suspend (AgentEvent.SessionState) -> Unit)? = null,
    private val log: (String) -> Unit = {},
) {
  private var state: ConciergeState = initialState()
  private val lock = Mutex()
  private var approvalTimer: Cancellable? = null
  private var lastSessionState: String? = null

  fun getState(): ConciergeState = state

  private val timers =
      object : IngestTimers {
        override fun scheduleApprovalTimeout(expiresAt: Long?) {
          this@Concierge.scheduleApprovalTimeout(expiresAt)
        }

        override fun clearApprovalTimer() {
          this@Concierge.clearApprovalTimer()
        }
      }

  private fun ctx(): EffectCtx =
      EffectCtx(
          agent = agent,
          voice = voice,
          getState = { state },
          setState = { state = it },
          clearApprovalTimer = { clearApprovalTimer() },
          relayResolvesApproval = { relayResolvesApproval() },
          publishSessionState = { publishSessionState() },
          log = log,
      )

  // ── entry points (all serialised) ──────────────────────────────────────────

  suspend fun handleUserUtterance(utterance: String): List<Effect> =
      lock.withLock {
        val effects = engine.decide(DecisionInput.User(utterance), state)
        applyEffects(effects)
        effects
      }

  /** The shipped path: the client's model already decided, and these are its tool calls. */
  suspend fun applyClientEffects(raw: JSONArray?): List<Effect> =
      lock.withLock {
        val effects = parseEffects(raw)
        applyEffects(effects)
        effects
      }

  suspend fun handleAgentEvent(event: AgentEvent): List<Effect> =
      lock.withLock {
        // Out-of-band resolution, handled BEFORE ingest. A mismatched id is fully inert — no ingest,
        // no drain, no projection — because it is not our approval to react to.
        if (event is AgentEvent.ApprovalResolved) {
          if (state.pendingApprovalId != event.id) return@withLock emptyList()
          clearApprovalTimer()
          state = state.noPendingApproval().copy(mode = Mode.WORKING, awaiting = null)
          val acked = engine.decide(DecisionInput.Agent(event), state)
          applyEffects(acked)
          return@withLock acked
        }

        state = ingestAgentEvent(state, event, timers, log)

        // After ingest, so the prompt is populated; before the model reacts, because it is about to
        // voice this approval and the one thing it must not do is pin it on the wrong task.
        if (event is AgentEvent.ApprovalRequest && state.inFlight.size > 1) {
          voice.instruct(unattributableApprovalNudge(state.inFlight, state.pendingApprovalPrompt))
        }

        var effects = emptyList<Effect>()
        if (wantsReaction(event)) {
          effects = engine.decide(DecisionInput.Agent(event), state)
          applyEffects(effects)
        }

        maybeDrainQueue()
        publishSessionState()
        effects
      }

  /**
   * The pre-expiry ping fired.
   *
   * The re-check inside the lock is the whole point: between the timer firing and the lock being
   * acquired, an approve or a completion may have cleared the approval.
   */
  suspend fun onApprovalTimeoutWarning(): List<Effect> =
      lock.withLock {
        if (state.pendingApprovalId == null) return@withLock emptyList()
        val effects = engine.decide(DecisionInput.ApprovalTimeout, state)
        applyEffects(effects)
        effects
      }

  fun stop() {
    clearApprovalTimer()
  }

  // ── dispatch ───────────────────────────────────────────────────────────────

  /**
   * Strictly sequential. A batch like [say, forwardToAgent, askAndWait] must apply in order, or the
   * wait mode lands before startTurn sets `working` and is immediately clobbered.
   */
  private suspend fun applyEffects(effects: List<Effect>) {
    for (effect in effects) applyEffect(effect)
    publishSessionState()
  }

  private suspend fun applyEffect(effect: Effect) {
    val c = ctx()
    when (effect) {
      is Effect.Say -> voice.say(effect.text)
      // A pure state signal — it does NOT speak. The client's model already voiced the question;
      // speaking it here would double it up and interrupt the model mid-sentence.
      is Effect.AskAndWait ->
          state =
              state.copy(
                  mode = MODE_FOR_WAIT[effect.waitingFor] ?: state.mode,
                  awaiting = effect.waitingFor)
      is Effect.ForwardToAgent -> applyForwardToAgent(c, effect)
      is Effect.RelayToAgent -> applyRelayToAgent(c, effect)
      is Effect.Approve,
      is Effect.ApproveAlways,
      is Effect.Deny -> applyApprovalDecision(c, effect)
      is Effect.ChooseOption -> applyChooseOption(c, effect)
      is Effect.Enqueue -> applyEnqueue(c, effect)
      is Effect.Interrupt -> applyInterrupt(c)
      is Effect.CancelQueued -> applyCancelQueued(c, effect)
      is Effect.SendQueuedNow -> applySendQueuedNow(c, effect)
      is Effect.SetState -> state = state.withMode(effect.mode)
      is Effect.ResetSession -> applyResetSession(c)
      is Effect.Noop -> Unit
    }
  }

  /**
   * Which events the brain is told about.
   *
   * `progress` only when a step actually failed — otherwise she has no idea anything went wrong and
   * fills the silence with a result she never received. Everything else here is either terminal or
   * something the user must hear about.
   */
  private fun wantsReaction(event: AgentEvent): Boolean =
      when (event) {
        is AgentEvent.Progress -> event.failed
        is AgentEvent.ApprovalRequest,
        is AgentEvent.Complete,
        is AgentEvent.Error,
        is AgentEvent.Notice -> true
        else -> false
      }

  /**
   * Start the next locally-held task, if the agent is free.
   *
   * NEVER forwards an entry with a `pendingId`. Those live in a durable doc that the agent drains
   * itself, so forwarding one here runs the task twice — the single most important invariant in this
   * file. Drains at most one per call.
   */
  private suspend fun maybeDrainQueue() {
    if (state.mode != Mode.IDLE) return
    val index = state.queue.indexOfFirst { it.pendingId == null }
    if (index < 0) return
    val next = state.queue[index]
    state = state.removeQueued(index)
    // Its OWN attachments, never the bridge's current stash.
    agent.forwardTask(next.text, next.attachments)
    state = state.startTurn(next.text)
  }

  /**
   * A relay also resolves the pending approval only for a free-text question with no options.
   *
   * An allowlist, deliberately. It was a denylist once, and an `exec` "Command Approval Required"
   * got silently approved by a relay about a photo.
   */
  private fun relayResolvesApproval(): Boolean {
    if (state.pendingApprovalId == null) return false
    if (!state.pendingApprovalOptions.isNullOrEmpty()) return false
    return state.pendingApprovalType == "user_input"
  }

  // ── the approval timer ─────────────────────────────────────────────────────

  private fun scheduleApprovalTimeout(expiresAt: Long?) {
    clearApprovalTimer()
    if (expiresAt == null) return // no expiry means no ping
    val delay = (expiresAt - System.currentTimeMillis() - APPROVAL_TIMEOUT_LEAD_MS).coerceAtLeast(0)
    approvalTimer = timer.schedule(delay) { onApprovalTimeoutFired() }
  }

  private fun clearApprovalTimer() {
    approvalTimer?.cancel()
    approvalTimer = null
  }

  /**
   * Set by the caller so the timer can re-enter the serialised path.
   *
   * A plain callback rather than a coroutine launch, because the FSM owns no scope — the caller
   * decides where the resulting suspend work runs.
   */
  var onApprovalTimeoutFired: () -> Unit = {}

  // ── the session projection ─────────────────────────────────────────────────

  /**
   * Publish what the client's activity log reads, suppressing an unchanged repeat.
   *
   * Deliberately not per-mutation: a queue that drains within one batch is never announced.
   */
  private suspend fun publishSessionState() {
    val sink = onSessionState ?: return
    val projection =
        AgentEvent.SessionState(
            running = state.inFlight.firstOrNull(),
            blockedOn =
                if (state.mode == Mode.AWAITING_USER) state.pendingApprovalPrompt else null,
            queued = state.queue.map { it.text },
        )
    // Canonical, fixed key order — a JSON object with unstable ordering would defeat the dedupe.
    val canonical =
        JSONObject()
            .put("type", "session-state")
            .apply {
              projection.running?.let { put("running", it) }
              projection.blockedOn?.let { put("blockedOn", it) }
            }
            .put("queued", JSONArray(projection.queued))
            .toString()
    if (canonical == lastSessionState) return
    lastSessionState = canonical
    sink(projection)
  }
}
