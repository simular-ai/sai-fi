/* sai-fi — voice concierge. */

// The catalog. Ported from cloud-api `core/golden/scenarios.ts`, scenario for scenario.
//
// Names match the TypeScript EXACTLY — they reconcile against
// docs/plans/golden-catalog-inventory.md in the server repo, and a renamed scenario reads as a
// dropped one. The `guards` string says what regression each pins; most record a failure seen on a
// real device, and it is the reason the scenario is not redundant with a unit test.
//
// Assert the EFFECT and STATE layer, never phrasing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Bumped as scenarios land. Must reach 62 before the TypeScript catalog is deleted. */
const val PORTED_SCENARIO_COUNT = 12

private fun forwardTexts(ctx: GoldenCtx) =
    ctx.agent.callsTo("forwardTask").map { it.args["text"] as String }

private fun callLog(ctx: GoldenCtx) = ctx.agent.calls.map { it.method }

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
            // waiting is the same question with the same stakes, so the guard counts both — and the
            // read-back must distinguish them, because a user cannot choose informedly if a task
            // that has not begun is described as underway.
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
              assertEquals(listOf("forwardTask", "takePendingAttachments", "queueTask"), callLog(ctx))
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
            name = "S5 approve",
            guards = "spoken yes → resolveApproval approved",
            steps =
                listOf(
                    Step.User("book a table"),
                    Step.Agent(approval("a1")),
                    Step.User("yes"),
                ),
            assert = { ctx ->
              val call = ctx.resolveCall()
              assertEquals("a1", call?.args?.get("id"))
              assertEquals(ApprovalDecision.APPROVED, call?.args?.get("decision"))
              assertNull(ctx.state.pendingApprovalId)
            },
        ),
        Scenario(
            name = "S6 deny",
            guards = "spoken no → resolveApproval denied",
            steps =
                listOf(
                    Step.User("book a table"),
                    Step.Agent(approval("a1")),
                    Step.User("no thanks"),
                ),
            assert = { ctx ->
              assertEquals(ApprovalDecision.DENIED, ctx.resolveCall()?.args?.get("decision"))
              assertNull(ctx.state.pendingApprovalId)
            },
        ),
        Scenario(
            name = "S9 link-only",
            guards = "never voice-resolved, even if approve called",
            steps =
                listOf(
                    Step.User("log me in"),
                    Step.Agent(approval("a1", isLinkOnly = true, approvalType = "service_auth")),
                    effects(effect("approve")),
                ),
            assert = { ctx ->
              assertNull(
                  "a link-only card is the user's to complete in the browser", ctx.resolveCall())
              assertNull(ctx.state.pendingApprovalId)
            },
        ),
        Scenario(
            name = "S10 out-of-band resolution",
            guards = "GUI resolved → clear, do not re-resolve",
            steps =
                listOf(
                    Step.User("book a table"),
                    Step.Agent(approval("a1")),
                    Step.Agent(AgentEvent.ApprovalResolved("a1", "approved")),
                ),
            assert = { ctx ->
              assertNull("already resolved elsewhere", ctx.resolveCall())
              assertNull(ctx.state.pendingApprovalId)
              assertEquals(Mode.WORKING, ctx.state.mode)
            },
        ),
        Scenario(
            name = "S28 error resets mode",
            guards = "agent error → idle, and the failure is spoken",
            steps =
                listOf(
                    Step.User("take a screenshot"),
                    Step.Agent(AgentEvent.Error("the browser crashed")),
                ),
            assert = { ctx ->
              assertEquals(Mode.IDLE, ctx.state.mode)
              assertEquals(emptyList<String>(), ctx.state.inFlight)
              assertTrue(ctx.spokenHas("Ran into an error."))
            },
        ),
    )
