/* sai-fi — voice concierge. */

// The golden catalog's harness: a scripted brain, a scenario shape, and the runner.
//
// The catalog is the concierge's behaviour spec as DATA. Each scenario is a fixed input sequence
// driven through the real Concierge against fakes — no model, no network — with an expected
// effect/state/voice trace. A change that alters a trace fails loudly.
//
// Assert the EFFECT and STATE layer, never phrasing: the live model's wording varies, and phrasing
// quality is the eval's job, not this catalog's.
//
// Ported from cloud-api `core/golden/scenarios.ts`. The scenario NAMES are load-bearing — they
// reconcile against docs/plans/golden-catalog-inventory.md in the server repo, which is how a
// dropped scenario stays visible.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ActivityLog

import org.json.JSONArray
import org.json.JSONObject

/**
 * One scripted "brain" standing in for the live voice model, covering every move the scenarios
 * exercise. Keyed on state + utterance, deterministic.
 *
 * Speech rides on `say`; `askAndWait` is the state signal only.
 */
val goldenBrain: (DecisionInput, ConciergeState) -> List<Effect> = { input, state ->
  when (input) {
    is DecisionInput.ApprovalTimeout -> listOf(Effect.Say("heads up — this is about to time out"))

    is DecisionInput.Agent ->
        when (val e = input.event) {
          is AgentEvent.ApprovalRequest -> {
            val options = e.options
            when {
              // A CHOICE is not a yes/no: present the options and ask which — never a bare "okay to
              // proceed?". Resolve via chooseOption, not approve/deny.
              e.approvalType == "choice" && !options.isNullOrEmpty() ->
                  listOf(
                      Effect.Say("You can pick: ${options.joinToString(", ") { it.label }}. Which one?"),
                      Effect.AskAndWait("Which one?", WaitReason.INPUT))
              // A link-only step can't be taken by voice — point the user at the app and DON'T
              // resolve; the browser completes it out of band.
              e.isLinkOnly ->
                  listOf(Effect.Say("Go ahead and enter that in the app — I can't take it by voice."))
              else ->
                  listOf(
                      Effect.Say("Okay to go ahead?"),
                      Effect.AskAndWait("Okay to go ahead?", WaitReason.APPROVAL))
            }
          }
          is AgentEvent.Complete -> listOf(Effect.Say(e.summary ?: "All done."))
          is AgentEvent.Error -> listOf(Effect.Say("Ran into an error."))
          is AgentEvent.ApprovalResolved -> listOf(Effect.Say("Got it — already handled."))
          else -> listOf(Effect.Noop)
        }

    is DecisionInput.User -> {
      val u = input.utterance.lowercase()
      when {
        state.mode == Mode.AWAITING_USER && state.awaiting == WaitReason.APPROVAL ->
            if (u.contains("yes") || u.contains("go ahead")) listOf(Effect.Approve)
            else listOf(Effect.Deny("user declined"))

        state.mode == Mode.CLARIFYING -> listOf(Effect.ForwardToAgent("fix $u"))

        state.mode == Mode.NEGOTIATING ->
            if (u.contains("now"))
                listOf(
                    Effect.Say("switching now"),
                    Effect.Interrupt,
                    Effect.ForwardToAgent("check email"))
            else
                listOf(
                    Effect.Say("sure, right after this"),
                    Effect.Enqueue("check email", Urgency.NORMAL),
                    Effect.SetState(Mode.WORKING))

        u == "fix it" ->
            listOf(
                Effect.Say("What should I fix?"),
                Effect.AskAndWait("What should I fix?", WaitReason.CLARIFICATION))

        // A "relay: …" utterance steers the RUNNING turn rather than starting a fresh task. Also
        // fires while awaiting-user: when the agent asked a free-text question via an approval, the
        // user simply answering it IS a steer — the yes/no branch above already claimed the case
        // where the approval is a real approve/deny.
        u.startsWith("relay:") &&
            (state.mode == Mode.WORKING || state.mode == Mode.AWAITING_USER) ->
            listOf(
                Effect.RelayToAgent(
                    input.utterance.substring(input.utterance.indexOf(':') + 1).trim()))

        state.mode == Mode.WORKING ->
            listOf(
                Effect.Say("I'm mid-task — switch now, or after?"),
                Effect.AskAndWait("Now or after?", WaitReason.URGENCY))

        else -> listOf(Effect.ForwardToAgent(input.utterance))
      }
    }
  }
}

/** Build an approval-request event with the catalog's defaults. */
fun approval(
    id: String,
    title: String = "do the thing",
    description: String = "",
    approvalType: String = "action",
    isLinkOnly: Boolean = false,
    options: List<ApprovalOption>? = null,
    multiple: Boolean? = null,
    allowOther: Boolean? = null,
    expiresAt: Long? = null,
) =
    AgentEvent.ApprovalRequest(
        id = id,
        title = title,
        description = description,
        approvalType = approvalType,
        isLinkOnly = isLinkOnly,
        options = options,
        multiple = multiple,
        allowOther = allowOther,
        expiresAt = expiresAt,
    )

/** A glasses capture sitting on the bridge, waiting for the next write. */
fun photo(name: String) =
    TaskAttachment(
        path = "uploads/$name",
        name = name,
        mime = "image/jpeg",
        size = 1024,
        downloadUrl = "https://storage.example/$name",
    )

/**
 * An agent event as the wire JSON the device's ActivityLog reads.
 *
 * The FSM speaks in typed events; ActivityLog was written against the raw frames, and keeping it on
 * those is deliberate — it is fixture-pinned against the server and must not drift to suit us.
 */
fun agentEventJson(e: AgentEvent): JSONObject =
    when (e) {
      is AgentEvent.Text -> JSONObject().put("type", "text").put("text", e.text)
      is AgentEvent.Progress ->
          JSONObject().put("type", "progress").put("text", e.text).apply {
            e.tool?.let { put("tool", it) }
            if (e.failed) put("failed", true)
          }
      is AgentEvent.Status -> JSONObject().put("type", "status").put("status", e.status.wire)
      is AgentEvent.Complete ->
          JSONObject().put("type", "complete").apply { e.summary?.let { put("summary", it) } }
      is AgentEvent.Error -> JSONObject().put("type", "error").put("text", e.text)
      is AgentEvent.Notice -> JSONObject().put("type", "notice").put("text", e.text)
      is AgentEvent.ApprovalRequest ->
          JSONObject()
              .put("type", "approval-request")
              .put("id", e.id)
              .put("title", e.title)
              .put("description", e.description)
              .put("approvalType", e.approvalType)
              .put("isLinkOnly", e.isLinkOnly)
      is AgentEvent.ApprovalResolved ->
          JSONObject().put("type", "approval-resolved").put("id", e.id).put("status", e.status)
      is AgentEvent.SessionState -> sessionStateJson(e)
    }

fun sessionStateJson(s: AgentEvent.SessionState): JSONObject =
    JSONObject().put("type", "session-state").apply {
      s.running?.let { put("running", it) }
      s.blockedOn?.let { put("blockedOn", it) }
      put("queued", JSONArray().apply { s.queued.forEach { put(it) } })
    }

/** A timer with a virtual clock, so `advanceMs` steps are deterministic. */
class VirtualTimer(var now: Long = 0L) : Timer {
  private data class Entry(val dueAt: Long, val action: () -> Unit)

  private val entries = mutableListOf<Entry>()

  override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
    val entry = Entry(now + delayMs, action)
    entries += entry
    return Cancellable { entries.remove(entry) }
  }

  /** Move the clock, firing anything that comes due. */
  fun advance(ms: Long) {
    now += ms
    val due = entries.filter { it.dueAt <= now }
    entries.removeAll(due)
    due.forEach { it.action() }
  }

  val pending: Int
    get() = entries.size
}

/** Everything a scenario's assertions can reach. */
class GoldenCtx(
    val agent: FakeAgent,
    val voice: FakeChannel,
    val concierge: Concierge,
    /** Every session-state projection the FSM published, in order. */
    val sessionStates: MutableList<AgentEvent.SessionState>,
    val timer: VirtualTimer,
    /**
     * The REAL ActivityLog, fed the same projections the device would get.
     *
     * `getSaiStatus` is answered on the device from this, so a scenario asserting what the user can
     * be told has to go through the real renderer — a stub would let the FSM publish a projection
     * the log cannot actually express.
     */
    val activityLog: ActivityLog,
) {
  val state: ConciergeState
    get() = concierge.getState()

  fun spokenHas(s: String) = voice.spoken.any { it.contains(s) }

  fun instructedHas(s: String) = voice.instructed.any { it.contains(s) }

  /** What `getSaiStatus` would return on the device right now. */
  fun status(): String = activityLog.statusText()

  fun resolveCall() = agent.calls.firstOrNull { it.method == "resolveApproval" }
}

sealed interface Step {
  data class User(val utterance: String) : Step

  data class Agent(val event: AgentEvent) : Step

  /** Raw effects, exactly as the client's model would send them — through the parse boundary. */
  data class Effects(val raw: JSONArray) : Step

  data class AdvanceMs(val ms: Long) : Step

  /** Escape hatch for anything the shapes above can't express. */
  data class Do(val block: suspend (GoldenCtx) -> Unit) : Step
}

/** `effects(effect("forwardToAgent", "text" to "..."))` — the raw shape, not the parsed one. */
fun effects(vararg objs: JSONObject) = Step.Effects(JSONArray().apply { objs.forEach { put(it) } })

fun effect(kind: String, vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply {
      put("kind", kind)
      pairs.forEach { (k, v) -> put(k, v) }
    }

fun jsonArrayOf(vararg values: String): JSONArray = JSONArray().apply { values.forEach { put(it) } }

data class Scenario(
    val name: String,
    val guards: String,
    val steps: List<Step>,
    val assert: (GoldenCtx) -> Unit,
)
