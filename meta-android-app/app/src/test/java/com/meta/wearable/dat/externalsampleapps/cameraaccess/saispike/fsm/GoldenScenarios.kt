/* sai-fi — voice concierge. */

// The catalog. Ported from cloud-api `core/golden/scenarios.ts`, scenario for scenario.
//
// Names AND steps match the TypeScript exactly. Names reconcile against
// docs/plans/golden-catalog-inventory.md in the server repo; steps matter just as much, because a
// scenario reconstructed from its name alone passes while asserting something the server never
// tested. That is a false green, and the name check cannot see it.
//
// Assert the EFFECT and STATE layer, never phrasing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Bumped as scenarios land. Must reach 62 before the TypeScript catalog is deleted. */
const val PORTED_SCENARIO_COUNT = 18

private fun forwardTexts(ctx: GoldenCtx) =
    ctx.agent.callsTo("forwardTask").map { it.args["text"] as String }

/**
 * The call log, with `takePendingAttachments` filtered out.
 *
 * The TS fake does not record it; ours does, because the take-before-write ordering is an invariant
 * worth seeing. Filtering here keeps these assertions comparable to the server's line for line —
 * the ordering itself is asserted directly in ConciergeTest.
 */
private fun callLog(ctx: GoldenCtx) =
    ctx.agent.calls.map { it.method }.filter { it != "takePendingAttachments" }

val GOLDEN_SCENARIOS: List<Scenario> =
    listOf(
        Scenario(
            name = "S1 happy path",
            guards = "forward → progress → complete",
            steps =
                listOf(
                    Step.User("take a screenshot of the homepage"),
                    Step.Agent(AgentEvent.Status(AgentStatus.PROCESSING)),
                    Step.Agent(AgentEvent.Progress("opening browser", tool = "browser")),
                    Step.Agent(AgentEvent.Complete("Grabbed the screenshot.")),
                ),
            assert = { ctx ->
              assertEquals(listOf("forwardTask"), callLog(ctx))
              assertEquals(listOf("take a screenshot of the homepage"), forwardTexts(ctx))
              assertEquals(Mode.IDLE, ctx.state.mode)
              assertTrue(ctx.spokenHas("Grabbed the screenshot."))
            },
        ),
        Scenario(
            name = "S2 clarify",
            guards = "holds task, asks once, forwards enriched",
            steps = listOf(Step.User("fix it")),
            assert = { ctx ->
              assertEquals("nothing forwarded yet", emptyList<String>(), callLog(ctx))
              assertEquals(Mode.CLARIFYING, ctx.state.mode)
              assertTrue(ctx.spokenHas("What should I fix?"))
            },
        ),
        Scenario(
            name = "S2b clarify → answer",
            guards = "answer forwards the enriched task",
            steps = listOf(Step.User("fix it"), Step.User("the login button")),
            assert = { ctx ->
              assertEquals(listOf("forwardTask"), callLog(ctx))
              assertEquals(listOf("fix the login button"), forwardTexts(ctx))
              assertEquals(Mode.WORKING, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S3 negotiate + queue drain",
            guards = "busy → ask → enqueue → drain on idle",
            steps =
                listOf(
                    Step.User("take a screenshot"),
                    Step.User("also check my email"),
                    Step.User("after this one"),
                    Step.Agent(AgentEvent.Complete("done")),
                ),
            assert = { ctx ->
              assertEquals(listOf("take a screenshot", "check email"), forwardTexts(ctx))
              assertEquals(Mode.WORKING, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S4 interrupt + switch",
            guards = "switch now → abort + forward new",
            steps =
                listOf(
                    Step.User("take a screenshot"),
                    Step.User("also check my email"),
                    Step.User("do it now"),
                ),
            assert = { ctx ->
              assertTrue(callLog(ctx).contains("abort"))
              assertEquals("check email", forwardTexts(ctx).last())
            },
        ),
        Scenario(
            name = "S4b interrupt asks before killing one running and one queued",
            guards = "device 2026-07-30 — \"cancel\" for one task aborted an unrelated one, silently",
            // The original bug: both requests landed in the SAME agent turn, so the abort behind
            // `interrupt` stopped both. On device the second was a restaurant booking and the first
            // an unread-email check asked for two turns earlier and never mentioned again; "it's
            // okay, cancel" took out both, and nothing said so.
            //
            // Admission changed the SHAPE of that ambiguity without removing it: the booking is now
            // queued rather than folded in, so inFlight never reaches two. One running plus one
            // waiting is the same question with the same stakes — and the read-back must
            // distinguish them, because a user cannot choose informedly if a task that has not begun
            // is described as underway.
            steps =
                listOf(
                    effects(
                        effect(
                            "forwardToAgent",
                            "text" to "check my unread emails and Slack messages")),
                    effects(
                        effect("forwardToAgent", "text" to "book a table for tonight at 7pm for six")),
                    effects(effect("interrupt")),
                ),
            assert = { ctx ->
              // Queued DURABLY, not aborted — the ask must not destroy either one.
              assertEquals(listOf("forwardTask", "queueTask"), callLog(ctx))
              assertTrue(ctx.spokenHas("check my unread emails and Slack messages"))
              assertTrue(ctx.spokenHas("book a table for tonight at 7pm for six"))
              assertTrue("named as not underway", ctx.spokenHas("hasn't started yet"))
              assertEquals("the email check survives the ask", Mode.WORKING, ctx.state.mode)
              assertEquals(listOf("check my unread emails and Slack messages"), ctx.state.inFlight)
              assertEquals(
                  listOf("book a table for tonight at 7pm for six"),
                  ctx.state.queue.map { it.text })
              assertEquals(true, ctx.state.interruptScopeAsked)
            },
        ),
        Scenario(
            name = "S4c a second interrupt stops everything",
            guards = "the scope ask is one-shot — \"all of it\" must not be refused forever",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "check my email")),
                    effects(effect("forwardToAgent", "text" to "book a table")),
                    effects(effect("interrupt")), // asks
                    effects(effect("interrupt")), // the user answered "everything"
                ),
            assert = { ctx ->
              assertEquals(1, callLog(ctx).count { it == "abort" })
              assertEquals("the turn is over — no more 'still working'", Mode.IDLE, ctx.state.mode)
              assertEquals(emptyList<String>(), ctx.state.inFlight)
              assertNull(ctx.state.interruptScopeAsked)
            },
        ),
        Scenario(
            name = "S4d a scoped cancel relays instead of aborting",
            guards = "the intended path for \"cancel one of them\" — the rest keeps running",
            // The running task is the one being cancelled, so a relay is exactly right: the agent is
            // told to drop that part and carry on. Only ONE forward reached the agent — the second
            // request is queued — so the relay names work the agent actually knows about.
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "check my email")),
                    effects(effect("forwardToAgent", "text" to "book a table")),
                    effects(
                        effect(
                            "relayToAgent",
                            "answer" to "stop checking the email — the booking can still wait")),
                ),
            assert = { ctx ->
              assertEquals(listOf("forwardTask", "queueTask", "steer"), callLog(ctx)) // no abort
              assertEquals(Mode.WORKING, ctx.state.mode)
              // A relay is never queued and never disturbs the queue — it is about the RUNNING turn.
              assertEquals(listOf("book a table"), ctx.state.queue.map { it.text })
            },
        ),
        Scenario(
            name = "S4e interrupt closes the turn out",
            guards = "abort emits no agent event — the FSM must not sit in `working` forever",
            // The stranding half of the same device bug: `interrupt` used to abort and change
            // nothing, so no complete/error/idle could ever arrive (the reader is torn down). The
            // FSM stayed `working`, the approval stayed pending, and the queued task could never
            // drain — a drain needs `idle`. She spent the rest of the call saying she was "still
            // waiting" for a dead task.
            //
            // The queue going too is the INVERSE of the old behaviour. An abort used to RELEASE the
            // queue, which was right while the only way to be queued was to be blocked behind an
            // approval; with admission queueing by default it inverts into "stop" starting the next
            // task seconds after she confirms everything is stopped.
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "book a table")),
                    Step.Agent(approval("a-cancel", title = "Book the table at LINO")),
                    effects(
                        effect("enqueue", "task" to "check my email", "urgency" to "normal")),
                    effects(effect("interrupt")), // asks: one running, one queued
                    effects(effect("interrupt")), // "all of it"
                ),
            assert = { ctx ->
              assertEquals(listOf("forwardTask", "abort", "resolveApproval"), callLog(ctx))
              // The card the aborted turn was blocked on is denied, not left to time out and be
              // announced minutes later as an unanswered request.
              assertEquals("a-cancel", ctx.resolveCall()?.args?.get("id"))
              assertEquals(ApprovalDecision.DENIED, ctx.resolveCall()?.args?.get("decision"))
              // Nothing was started BY a cancellation — the strongest evidence "stop" meant stop.
              assertEquals(1, ctx.agent.callsTo("forwardTask").size)
              assertEquals(emptyList<String>(), ctx.state.inFlight)
              assertEquals("dropped with the turn, not released", 0, ctx.state.queue.size)
              assertNull(ctx.state.pendingApprovalId)
              assertEquals(Mode.IDLE, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S5 approve",
            guards = "spoken yes → resolveApproval approved",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "send the report")),
                    Step.Agent(approval("ap1", title = "send the report email")),
                    Step.User("yes, go ahead"),
                ),
            assert = { ctx ->
              assertEquals("ap1", ctx.resolveCall()?.args?.get("id"))
              assertEquals(ApprovalDecision.APPROVED, ctx.resolveCall()?.args?.get("decision"))
              assertNull(ctx.state.pendingApprovalId)
              assertEquals(Mode.WORKING, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S6 deny",
            guards = "spoken no → resolveApproval denied",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "delete all drafts")),
                    Step.Agent(approval("ap2", title = "delete all drafts")),
                    Step.User("no, do not do that"),
                ),
            assert = { ctx ->
              assertEquals("ap2", ctx.resolveCall()?.args?.get("id"))
              assertEquals(ApprovalDecision.DENIED, ctx.resolveCall()?.args?.get("decision"))
            },
        ),
        Scenario(
            name = "S7 approveAlways",
            guards = "approved_always resolution",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "run some js")),
                    Step.Agent(
                        approval(
                            "ap3",
                            approvalType = "exec",
                            allowAlways = true,
                            title = "run some JavaScript")),
                    effects(effect("approveAlways")),
                ),
            assert = { ctx ->
              assertEquals("ap3", ctx.resolveCall()?.args?.get("id"))
              assertEquals(
                  ApprovalDecision.APPROVED_ALWAYS, ctx.resolveCall()?.args?.get("decision"))
              assertNull(ctx.state.pendingApprovalId)
            },
        ),
        Scenario(
            name = "S8 choice (askChoice)",
            guards = "chooseOption, never approve/deny",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "log in")),
                    Step.Agent(
                        approval(
                            "sel1",
                            approvalType = "choice",
                            title = "Which method?",
                            options =
                                listOf(
                                    ApprovalOption("sms", "Text"),
                                    ApprovalOption("app", "Authenticator")))),
                    effects(effect("chooseOption", "values" to jsonArrayOf("sms"))),
                ),
            assert = { ctx ->
              val call = ctx.resolveCall()
              assertEquals("sel1", call?.args?.get("id"))
              assertEquals(ApprovalDecision.APPROVED, call?.args?.get("decision"))
              assertEquals(
                  "sms", (call?.args?.get("selection") as? ApprovalSelection)?.selectedOption)
            },
        ),
        Scenario(
            name = "S8b an un-offered choice is corrected to the MODEL, not read aloud",
            guards =
                "the correction is an instruction — it must never reach `say`, which is spoken verbatim",
            // The rejection guard was right and its wording was routed wrong: the correction went
            // out via `say`, which the client wraps in "say this to the user, verbatim", so the user
            // heard "call chooseOption with the exact option value" — a function name, read aloud.
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "log in")),
                    Step.Agent(
                        approval(
                            "sel-bad",
                            approvalType = "choice",
                            title = "Which method?",
                            options =
                                listOf(
                                    ApprovalOption("sms", "Text"),
                                    ApprovalOption("app", "Authenticator")))),
                    effects(effect("chooseOption", "values" to jsonArrayOf("carrier-pigeon"))),
                ),
            assert = { ctx ->
              assertNull("nothing chosen", ctx.resolveCall())
              assertTrue(ctx.instructedHas("chooseOption"))
              // The tool name must appear in NOTHING the user hears.
              assertFalse(ctx.spokenHas("chooseOption"))
              assertEquals("still answerable", "sel-bad", ctx.state.pendingApprovalId)
            },
        ),
        Scenario(
            name = "S9 link-only",
            guards = "never voice-resolved, even if approve called",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "connect gmail")),
                    Step.Agent(
                        approval(
                            "ap-link",
                            approvalType = "user_input",
                            isLinkOnly = true,
                            title = "enter your password")),
                    effects(effect("approve")),
                ),
            assert = { ctx ->
              assertNull(ctx.resolveCall())
              assertNull(ctx.state.pendingApprovalId)
              assertNull(ctx.state.pendingApprovalLinkOnly)
            },
        ),
        Scenario(
            name = "S10 out-of-band resolution",
            guards = "GUI resolved → clear, do not re-resolve",
            steps =
                listOf(
                    effects(effect("forwardToAgent", "text" to "x")),
                    Step.Agent(approval("ap4")),
                    Step.Agent(AgentEvent.ApprovalResolved("ap4", "approved")),
                ),
            assert = { ctx ->
              assertNull(ctx.resolveCall())
              assertNull(ctx.state.pendingApprovalId)
              assertEquals(Mode.WORKING, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S11 pre-timeout ping",
            guards = "warns before a pending request expires",
            steps =
                listOf(
                    Step.Do { ctx ->
                      ctx.concierge.handleAgentEvent(
                          approval("ap5", expiresAt = ctx.timer.now + 25_000))
                    },
                    Step.AdvanceMs(5_000), // lead is 20s → fires ~5s in
                ),
            assert = { ctx -> assertTrue(ctx.spokenHas("about to time out")) },
        ),
        Scenario(
            name = "S16 askAndWait is a state signal",
            guards = "sets awaiting, speaks nothing (no double-speak)",
            steps =
                listOf(
                    effects(
                        effect(
                            "askAndWait",
                            "question" to "Which file?",
                            "waitingFor" to "clarification"))),
            assert = { ctx ->
              assertEquals(emptyList<String>(), ctx.voice.spoken)
              assertEquals(Mode.CLARIFYING, ctx.state.mode)
              assertEquals(WaitReason.CLARIFICATION, ctx.state.awaiting)
            },
        ),
    )
