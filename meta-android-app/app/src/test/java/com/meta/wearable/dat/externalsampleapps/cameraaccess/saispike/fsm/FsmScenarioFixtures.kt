/* sai-fi — voice concierge. */

// The 63-scenario golden catalog, as a PORTABLE fixture.
//
// WHY THIS EXISTS. `docs/CONCIERGE_CLIENT_PROTOCOL.md` §8 tells a port implementing this client in
// another language that "sai-fi's FsmGoldenTest runs 63 scenarios that pin what the FSM does with
// every input sequence that has ever mattered. If you write your own, that catalog is the spec worth
// copying." `meta-ios-app (untested on-device)/SaiFiCore` is that port, and this file is how the catalog crosses: the
// STEPS are serialised, and so is a canonical TRACE of everything observable that the steps produced.
// The Swift FSM replays the steps and compares the trace.
//
// Writing it out rather than transcribing `GoldenScenarios.kt` into Swift is the whole point. A
// transcription is a second copy of the spec, free to drift from the first — which is exactly the
// failure the vendored TypeScript fixtures were supposed to catch and did not. One catalog, two
// runners.
//
// WHAT THE TRACE PINS, AND WHY IT IS STRICTER THAN THE ASSERTIONS. Each scenario's `assert` lambda
// checks the handful of properties that scenario is about. The trace records EVERYTHING observable:
// every bridge call with its arguments, every spoken line and its supersede tag, every model-facing
// instruction, every session projection, the whole final state, and what `getSaiStatus` would return.
// So the fixture is a superset of what the Kotlin asserts — deliberately, because for a PORT the
// question is not "did the important bits match" but "did anything at all differ".
//
// The cost of that strictness is honest: an intentional wording change now touches this file as well
// as the string goldens. That is the same trade the rest of `parity/` already makes.
//
// Regenerate with SAI_REGEN_GOLDENS=1 — see RegenerateGoldensTest.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ActivityLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.Jv
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.jarr
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.jbool
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.jnum
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.jobj
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.jstr
import kotlinx.coroutines.runBlocking

/** Every scenario, with its steps and the trace running them produces. */
fun fsmScenarios(): List<Jv> = GOLDEN_SCENARIOS.map { scenario ->
  jobj(
      "name" to jstr(scenario.name),
      "guards" to jstr(scenario.guards),
      "steps" to jarr(scenario.steps.map { stepJson(it) }),
      "trace" to traceOf(scenario),
  )
}

// ── the steps, serialised ────────────────────────────────────────────────────

private fun stepJson(step: Step): Jv =
    when (step) {
      is Step.User -> jobj("kind" to jstr("user"), "utterance" to jstr(step.utterance))
      is Step.Agent -> jobj("kind" to jstr("agent"), "event" to fullAgentEventJson(step.event))
      is Step.Effects ->
          jobj("kind" to jstr("effects"), "raw" to rawEffectsJson(step.raw))
      is Step.AdvanceMs -> jobj("kind" to jstr("advanceMs"), "ms" to jnum(step.ms))
      is Step.AddPhoto -> jobj("kind" to jstr("addPhoto"), "name" to jstr(step.name))
      is Step.FailNextForward -> jobj("kind" to jstr("failNextForward"))
      is Step.Do ->
          // Deliberately loud. A lambda cannot cross to the other port, so a scenario using one would
          // silently be missing from the iOS gate — which is the failure this whole file exists to
          // prevent. Express it as a declarative Step instead.
          error(
              "Step.Do cannot be serialised — add a declarative Step kind for it in GoldenHarness.kt " +
                  "so the scenario reaches the iOS port too")
    }

/**
 * The COMPLETE event, unlike `agentEventJson`.
 *
 * `agentEventJson` renders what the device's ActivityLog reads and drops the approval fields the log
 * has no use for — options, questions, expiry. A replay needs all of them, so this is a second,
 * lossless serialisation rather than a change to the first: that one is pinned by
 * `activity-log-status.json` and must not drift to suit this.
 */
private fun fullAgentEventJson(e: AgentEvent): Jv =
    when (e) {
      is AgentEvent.Text -> jobj("type" to jstr("text"), "text" to jstr(e.text))
      is AgentEvent.Progress ->
          jobj(
              "type" to jstr("progress"),
              "text" to jstr(e.text),
              "tool" to (e.tool?.let { jstr(it) } ?: Jv.Nul),
              "failed" to jbool(e.failed))
      is AgentEvent.Status -> jobj("type" to jstr("status"), "status" to jstr(e.status.wire))
      is AgentEvent.Complete ->
          jobj("type" to jstr("complete"), "summary" to (e.summary?.let { jstr(it) } ?: Jv.Nul))
      is AgentEvent.Error -> jobj("type" to jstr("error"), "text" to jstr(e.text))
      is AgentEvent.Notice ->
          jobj(
              "type" to jstr("notice"),
              "text" to jstr(e.text),
              "kind" to (e.kind?.let { jstr(it) } ?: Jv.Nul))
      is AgentEvent.ApprovalResolved ->
          jobj(
              "type" to jstr("approval-resolved"),
              "id" to jstr(e.id),
              "status" to jstr(e.status))
      is AgentEvent.SessionState ->
          jobj(
              "type" to jstr("session-state"),
              "running" to (e.running?.let { jstr(it) } ?: Jv.Nul),
              "blockedOn" to (e.blockedOn?.let { jstr(it) } ?: Jv.Nul),
              "queued" to jarr(e.queued.map { jstr(it) }))
      is AgentEvent.ApprovalRequest ->
          jobj(
              "type" to jstr("approval-request"),
              "id" to jstr(e.id),
              "title" to jstr(e.title),
              "description" to jstr(e.description),
              "approvalType" to jstr(e.approvalType),
              "isLinkOnly" to jbool(e.isLinkOnly),
              "options" to (e.options?.let { opts -> jarr(opts.map { optionJson(it) }) } ?: Jv.Nul),
              "questions" to
                  (e.questions?.let { qs -> jarr(qs.map { questionJson(it) }) } ?: Jv.Nul),
              "multiple" to (e.multiple?.let { jbool(it) } ?: Jv.Nul),
              "allowOther" to (e.allowOther?.let { jbool(it) } ?: Jv.Nul),
              "expiresAt" to (e.expiresAt?.let { jnum(it) } ?: Jv.Nul))
    }

private fun optionJson(o: ApprovalOption): Jv =
    jobj("value" to jstr(o.value), "label" to jstr(o.label))

private fun questionJson(q: ApprovalQuestion): Jv =
    jobj(
        "options" to jarr(q.options.map { optionJson(it) }),
        "multiple" to jbool(q.multiple),
        "allowOther" to jbool(q.allowOther))

/** A raw effect batch, re-read out of the org.json the catalog built it as. */
private fun rawEffectsJson(raw: org.json.JSONArray): Jv =
    jarr(
        (0 until raw.length()).map { i ->
          val o = raw.getJSONObject(i)
          jobj(
              *o.keys()
                  .asSequence()
                  .sorted() // stable: org.json's HashMap order is not
                  .map { key -> key to scalarJson(o.get(key)) }
                  .toList()
                  .toTypedArray())
        })

private fun scalarJson(v: Any?): Jv =
    when (v) {
      null, org.json.JSONObject.NULL -> Jv.Nul
      is String -> jstr(v)
      is Boolean -> jbool(v)
      is Int -> jnum(v.toLong())
      is Long -> jnum(v)
      is org.json.JSONArray -> jarr((0 until v.length()).map { scalarJson(v.get(it)) })
      else -> jstr(v.toString())
    }

// ── the trace ────────────────────────────────────────────────────────────────

/**
 * Run one scenario and record everything observable.
 *
 * Deliberately the SAME wiring as `FsmGoldenTest.run` — the virtual timer is the FSM's clock so an
 * absolute `expiresAt` and the delay computed from it agree, and the ActivityLog is the real one fed
 * the real projections. A trace produced against different wiring would pin the wiring, not the FSM.
 */
private fun traceOf(scenario: Scenario): Jv = runBlocking {
  val agent = FakeAgent()
  val voice = FakeChannel()
  val engine = FakeEngine(goldenBrain)
  val timer = VirtualTimer()
  val published = mutableListOf<AgentEvent.SessionState>()
  val activityLog = ActivityLog(now = { timer.now })

  val concierge =
      Concierge(
          agent,
          voice,
          engine,
          timer,
          onSessionState = {
            published += it
            activityLog.record(sessionStateJson(it))
          },
          now = { timer.now })
  concierge.onApprovalTimeoutFired = { runBlocking { concierge.onApprovalTimeoutWarning() } }

  for (step in scenario.steps) {
    when (step) {
      is Step.User -> concierge.handleUserUtterance(step.utterance)
      is Step.Agent -> {
        activityLog.record(agentEventJson(step.event))
        concierge.handleAgentEvent(step.event)
      }
      is Step.Effects -> concierge.applyClientEffects(step.raw)
      is Step.AdvanceMs -> timer.advance(step.ms)
      is Step.AddPhoto -> agent.addPendingAttachment(photo(step.name))
      is Step.FailNextForward -> agent.failForwardTask()
      is Step.Do -> error("Step.Do cannot be traced — see stepJson")
    }
  }

  jobj(
      "calls" to jarr(agent.calls.map { callJson(it) }),
      "spoken" to jarr(voice.spoken.map { jstr(it) }),
      "supersedes" to jarr(voice.supersedeTags.map { it?.let { t -> jstr(t) } ?: Jv.Nul }),
      "instructed" to jarr(voice.instructed.map { jstr(it) }),
      "sessionStates" to
          jarr(
              published.map {
                jobj(
                    "running" to (it.running?.let { r -> jstr(r) } ?: Jv.Nul),
                    "blockedOn" to (it.blockedOn?.let { b -> jstr(b) } ?: Jv.Nul),
                    "queued" to jarr(it.queued.map { qq -> jstr(qq) }))
              }),
      "state" to stateJson(concierge.getState()),
      "status" to jstr(activityLog.statusText()),
      "pendingTimers" to jnum(timer.pending.toLong()),
  )
}

/** One bridge call. Attachments are reduced to their names — the rest of a TaskAttachment is fixed. */
private fun callJson(call: BridgeCall): Jv {
  val entries = mutableListOf<Pair<String, Jv>>("method" to jstr(call.method))
  (call.args["text"] as? String)?.let { entries += "text" to jstr(it) }
  if (call.args.containsKey("attachments")) {
    @Suppress("UNCHECKED_CAST")
    val atts = call.args["attachments"] as? List<TaskAttachment>
    entries += "attachments" to (atts?.let { a -> jarr(a.map { jstr(it.name) }) } ?: Jv.Nul)
  }
  (call.args["id"] as? String)?.let { entries += "id" to jstr(it) }
  (call.args["decision"] as? ApprovalDecision)?.let { entries += "decision" to jstr(it.wire) }
  if (call.args.containsKey("selection")) {
    val sel = call.args["selection"] as? ApprovalSelection
    entries +=
        "selection" to
            (sel?.let { s -> jarr(s.selections.map { g -> jarr(g.map { jstr(it) }) }) } ?: Jv.Nul)
  }
  return Jv.Obj(entries)
}

/** The whole final state, in a fixed key order. */
private fun stateJson(s: ConciergeState): Jv =
    jobj(
        "mode" to jstr(s.mode.wire),
        "awaiting" to (s.awaiting?.let { jstr(it.wire) } ?: Jv.Nul),
        "inFlight" to jarr(s.inFlight.map { jstr(it) }),
        "queue" to
            jarr(
                s.queue.map { q ->
                  jobj(
                      "text" to jstr(q.text),
                      "urgency" to jstr(q.urgency.wire),
                      "attachments" to
                          (q.attachments?.let { a -> jarr(a.map { jstr(it.name) }) } ?: Jv.Nul))
                }),
        "pendingApprovalId" to (s.pendingApprovalId?.let { jstr(it) } ?: Jv.Nul),
        "pendingApprovalPrompt" to (s.pendingApprovalPrompt?.let { jstr(it) } ?: Jv.Nul),
        "pendingApprovalType" to (s.pendingApprovalType?.let { jstr(it) } ?: Jv.Nul),
        "pendingApprovalLinkOnly" to (s.pendingApprovalLinkOnly?.let { jbool(it) } ?: Jv.Nul),
        "pendingApprovalOptions" to
            (s.pendingApprovalOptions?.let { o -> jarr(o.map { optionJson(it) }) } ?: Jv.Nul),
        "pendingApprovalQuestions" to
            (s.pendingApprovalQuestions?.let { qs -> jarr(qs.map { questionJson(it) }) } ?: Jv.Nul),
        "pendingApprovalAllowOther" to
            (s.pendingApprovalAllowOther?.let { jbool(it) } ?: Jv.Nul),
        "interruptScopeAsked" to (s.interruptScopeAsked?.let { jbool(it) } ?: Jv.Nul),
        "resetConfirmAsked" to (s.resetConfirmAsked?.let { jbool(it) } ?: Jv.Nul),
        "abortedTurn" to jbool(s.abortedTurn),
    )
