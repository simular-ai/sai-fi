/* sai-fi — voice concierge. */

// End-to-end over the whole FSM, on the paths where the port is most likely to have gone wrong.
//
// Not the golden catalog — that is 62 scenarios and lands next. This is the subset that proves the
// dispatch, the admission rule, the approval guard and the terminal-event handling all survived the
// translation, so the catalog port starts from something known to work.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConciergeTest {

  private val agent = FakeAgent()
  private val voice = FakeChannel()
  private val engine = FakeEngine()
  private val timer = FakeTimer()

  private fun concierge(
      onSessionState: (suspend (AgentEvent.SessionState) -> Unit)? = null
  ): Concierge = Concierge(agent, voice, engine, timer, onSessionState)

  private fun approvalRequest(
      id: String = "a1",
      options: List<ApprovalOption>? = null,
      isLinkOnly: Boolean = false,
      approvalType: String = "exec",
      allowOther: Boolean? = null,
      expiresAt: Long? = null,
  ) =
      AgentEvent.ApprovalRequest(
          id = id,
          title = "Approval needed",
          description = "Run the script?",
          approvalType = approvalType,
          isLinkOnly = isLinkOnly,
          allowAlways = false,
          options = options,
          allowOther = allowOther,
          expiresAt = expiresAt,
      )

  // ── admission ──────────────────────────────────────────────────────────────

  @Test
  fun `a task arriving while one runs is held, not folded into the running turn`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("book a table")) }
    c.handleUserUtterance("book a table")

    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("email Dana")) }
    c.handleUserUtterance("email Dana")

    assertEquals("only the first task started", 1, agent.callsTo("forwardTask").size)
    assertEquals(listOf("email Dana"), c.getState().queue.map { it.text })
    assertTrue(
        "the user must hear it is waiting, not underway", voice.spokenHas("as soon as I'm done"))
  }

  @Test
  fun `a task held behind an approval says so, rather than naming a running task`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest())

    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("also check the weather")) }
    c.handleUserUtterance("also check the weather")

    assertTrue(voice.spokenHas("still waiting on the request in front of it"))
  }

  @Test
  fun `a queued task takes the photos off the bridge before the durable write`() = runTest {
    val photo = TaskAttachment(path = "u/a.jpg", name = "a.jpg", mime = "image/jpeg", size = 10)
    agent.stash = listOf(photo)

    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("first")) }
    c.handleUserUtterance("first")
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("what is this")) }
    c.handleUserUtterance("what is this")

    val order = agent.calls.map { it.method }
    assertTrue(
        "the stash must be taken when the task is HELD, not when it drains — otherwise the photo " +
            "leaves with whoever writes next: $order",
        order.contains("takePendingAttachments"))
    assertEquals(listOf(photo), c.getState().queue.single().attachments)
  }

  @Test
  fun `holding a task cannot fail, so nothing is ever apologised for`() = runTest {
    // There used to be two tests here, for a durable write failing and for it turning out to have
    // started immediately. Holding a task is a list append now — the agent is not told — so neither
    // outcome exists. What still has to hold is that a held task is never described as running.
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("first")) }
    c.handleUserUtterance("first")
    voice.spoken.clear()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("second")) }
    c.handleUserUtterance("second")

    assertEquals(listOf("second"), c.getState().queue.map { it.text })
    assertEquals("only the first is running", listOf("first"), c.getState().inFlight)
    assertTrue("it is named as waiting, behind something", voice.spokenHas("I'll start that"))
  }

  // ── the approval guard ─────────────────────────────────────────────────────

  @Test
  fun `an un-offered pick is refused, stays pending, and corrects the MODEL not the user`() =
      runTest {
        val c = concierge()
        c.handleAgentEvent(
            approvalRequest(options = listOf(ApprovalOption("marina", "Marina Bay"))))

        engine.script = { _, _ -> listOf(Effect.ChooseOption(listOf("somewhere else"))) }
        c.handleUserUtterance("somewhere else")

        assertTrue(agent.callsTo("resolveApproval").isEmpty())
        assertNotNull("the request must stay answerable", c.getState().pendingApprovalId)
        assertEquals(Mode.AWAITING_USER, c.getState().mode)
        assertTrue(voice.instructedHas("was REJECTED"))
        assertFalse(
            "the correction names a function — the user must never hear it",
            voice.spokenHas("chooseOption"))
      }

  @Test
  fun `allowOther lets a free-form answer through`() = runTest {
    val c = concierge()
    c.handleAgentEvent(
        approvalRequest(options = listOf(ApprovalOption("marina", "Marina Bay")), allowOther = true))

    engine.script = { _, _ -> listOf(Effect.ChooseOption(listOf("anywhere quiet"))) }
    c.handleUserUtterance("anywhere quiet")

    assertEquals(1, agent.callsTo("resolveApproval").size)
    assertNull(c.getState().pendingApprovalId)
  }

  @Test
  fun `a single-question card puts every pick in ONE group`() = runTest {
    // The agent resolves a choice positionally, one group per question. An ordinary card asks one
    // thing, so both picks belong to the same group — splitting them would claim answers to
    // questions that were never asked.
    val opts = listOf(ApprovalOption("a", "A"), ApprovalOption("b", "B"))

    val c1 = concierge()
    c1.handleAgentEvent(approvalRequest(options = opts))
    engine.script = { _, _ -> listOf(Effect.ChooseOption(listOf("a"))) }
    c1.handleUserUtterance("a")
    var sel = agent.callsTo("resolveApproval").last().args["selection"] as ApprovalSelection
    assertEquals(listOf(listOf("a")), sel.selections)

    agent.calls.clear()
    val c2 = concierge()
    // Reset the script first: an approval-request DOES reach the brain, so leaving the single-pick
    // script armed would resolve this request the moment it arrives.
    engine.script = { _, _ -> emptyList() }
    c2.handleAgentEvent(approvalRequest(options = opts))
    engine.script = { _, _ -> listOf(Effect.ChooseOption(listOf("a", "b"))) }
    c2.handleUserUtterance("both")
    sel = agent.callsTo("resolveApproval").last().args["selection"] as ApprovalSelection
    assertEquals(listOf(listOf("a", "b")), sel.selections)
  }

  @Test
  fun `approve with nothing pending is ignored — it is not a way to start work`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.Approve) }
    c.handleUserUtterance("yes")

    assertTrue(agent.calls.isEmpty())
    assertTrue(voice.spoken.isEmpty())
    assertEquals(Mode.IDLE, c.getState().mode)
  }

  @Test
  fun `a link-only approval is never resolved by voice, but stops blocking the FSM`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest(isLinkOnly = true, approvalType = "service_auth"))

    assertNull("the next utterance is not their answer", c.getState().awaiting)

    engine.script = { _, _ -> listOf(Effect.Approve) }
    c.handleUserUtterance("go ahead")

    assertTrue("the server would reject it anyway", agent.callsTo("resolveApproval").isEmpty())
    assertNull(c.getState().pendingApprovalId)
  }

  // ── terminal events ────────────────────────────────────────────────────────

  @Test
  fun `a completion clears an approval that will now never be answered`() = runTest {
    // The wedge this fixes: mode stayed awaiting-user with a dead pendingApprovalId, so the queue
    // never drained and every later forward was held behind an approval nobody could answer.
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("book it")) }
    c.handleUserUtterance("book it")
    engine.script = { _, _ -> emptyList() }
    c.handleAgentEvent(approvalRequest())
    assertEquals(Mode.AWAITING_USER, c.getState().mode)

    c.handleAgentEvent(AgentEvent.Complete("done"))

    assertEquals(Mode.IDLE, c.getState().mode)
    assertNull(c.getState().pendingApprovalId)
    assertTrue(c.getState().inFlight.isEmpty())
  }

  @Test
  fun `a stray idle status does not clobber a pending approval`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest())
    c.handleAgentEvent(AgentEvent.Status(AgentStatus.IDLE))

    assertEquals("blocked is not over", Mode.AWAITING_USER, c.getState().mode)
    assertNotNull(c.getState().pendingApprovalId)
  }

  @Test
  fun `an out-of-band resolution for another id is completely inert`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest(id = "a1"))
    val before = c.getState()

    c.handleAgentEvent(AgentEvent.ApprovalResolved("someone-elses", "approved"))

    assertEquals(before, c.getState())
  }

  // ── interrupt scope ────────────────────────────────────────────────────────

  @Test
  fun `an ambiguous interrupt asks once, then the next one goes straight through`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("book a table")) }
    c.handleUserUtterance("book a table")
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("email Dana")) }
    c.handleUserUtterance("email Dana")

    engine.script = { _, _ -> listOf(Effect.Interrupt) }
    c.handleUserUtterance("cancel")

    assertTrue("nothing may be aborted before the user answers", agent.callsTo("abort").isEmpty())
    assertTrue(voice.spokenHas("stop all of it, or just part of it"))
    assertTrue(c.getState().interruptScopeAsked == true)

    c.handleUserUtterance("all of it")

    assertEquals(1, agent.callsTo("abort").size)
    assertEquals(Mode.IDLE, c.getState().mode)
    assertTrue("the queue goes with it", c.getState().queue.isEmpty())
  }

  @Test
  fun `interrupt deletes durable docs BEFORE aborting`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("first")) }
    c.handleUserUtterance("first")
    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("second")) }
    c.handleUserUtterance("second")
    engine.script = { _, _ -> listOf(Effect.Interrupt) }
    c.handleUserUtterance("cancel")
    c.handleUserUtterance("all of it")

    val order = agent.calls.map { it.method }
    assertTrue(
        "abort first and the agent drains the next doc seconds later — 'stop' would start a task",
        order.indexOf("cancelQueuedTask") < order.indexOf("abort"))
  }

  // ── the drain ──────────────────────────────────────────────────────────────

  @Test
  fun `the drain starts a held task exactly once, however many turn-ending events arrive`() =
      runTest {
        // This used to assert the opposite: the drain had to SKIP an entry the agent held durably,
        // or the task ran twice. Nothing else holds a copy now, so the drain is what starts it —
        // and the doubling risk moved here, to the several events that all end a turn.
        val c = concierge()
        engine.script = { _, _ -> listOf(Effect.ForwardToAgent("first")) }
        c.handleUserUtterance("first")
        engine.script = { _, _ -> listOf(Effect.ForwardToAgent("second")) }
        c.handleUserUtterance("second")
        assertEquals(1, c.getState().queue.size)

        engine.script = { _, _ -> emptyList() }
        c.handleAgentEvent(AgentEvent.Complete())
        c.handleAgentEvent(AgentEvent.Status(AgentStatus.IDLE))
        c.handleAgentEvent(AgentEvent.Complete())

        assertEquals("started once, not once per turn-ending event", 2, agent.callsTo("forwardTask").size)
        assertEquals(0, c.getState().queue.size)
      }

  @Test
  fun `a model-enqueued task has no durable doc, so the drain does start it`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.Enqueue("later thing", Urgency.NORMAL)) }
    c.handleUserUtterance("do that later")
    assertTrue(agent.calls.isEmpty())

    engine.script = { _, _ -> emptyList() }
    c.handleAgentEvent(AgentEvent.Complete())

    assertEquals(listOf("later thing"), agent.callsTo("forwardTask").map { it.args["text"] })
    assertTrue(c.getState().queue.isEmpty())
  }

  // ── dispatch ordering ──────────────────────────────────────────────────────

  @Test
  fun `askAndWait lands after the forward it shares a batch with`() = runTest {
    val c = concierge()
    engine.script = { _, _ ->
      listOf(
          Effect.ForwardToAgent("book it"),
          Effect.AskAndWait("which night?", WaitReason.CLARIFICATION))
    }
    c.handleUserUtterance("book a table")

    assertEquals(
        "applied in order, so the wait mode is not clobbered by startTurn",
        Mode.CLARIFYING,
        c.getState().mode)
    assertEquals(WaitReason.CLARIFICATION, c.getState().awaiting)
  }

  @Test
  fun `askAndWait does not speak — the model already voiced the question`() = runTest {
    val c = concierge()
    engine.script = { _, _ -> listOf(Effect.AskAndWait("which night?", WaitReason.CLARIFICATION)) }
    c.handleUserUtterance("book a table")

    assertTrue("speaking here doubles the question up", voice.spoken.isEmpty())
  }

  // ── the session projection ─────────────────────────────────────────────────

  @Test
  fun `an unchanged projection is published once, not on every mutation`() = runTest {
    val published = mutableListOf<AgentEvent.SessionState>()
    val c = concierge(onSessionState = { published += it })

    engine.script = { _, _ -> listOf(Effect.ForwardToAgent("book it")) }
    c.handleUserUtterance("book it")
    val afterFirst = published.size

    engine.script = { _, _ -> emptyList() }
    c.handleAgentEvent(AgentEvent.Progress("still going"))

    assertEquals("a progress event changes nothing to announce", afterFirst, published.size)
    assertEquals("book it", published.last().running)
  }

  // ── the approval timer ─────────────────────────────────────────────────────

  @Test
  fun `an approval with no expiry gets no ping`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest(expiresAt = null))
    assertTrue(timer.scheduled.isEmpty())
  }

  @Test
  fun `the pre-expiry ping is armed ahead of the deadline`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest(expiresAt = System.currentTimeMillis() + 60_000))

    assertEquals(1, timer.scheduled.size)
    val delay = timer.scheduled.single().delayMs
    assertTrue("must fire before the request expires, got $delay", delay in 1..(60_000 - 19_000))
  }

  @Test
  fun `the timeout warning does nothing if the approval was already answered`() = runTest {
    val c = concierge()
    c.handleAgentEvent(approvalRequest(expiresAt = System.currentTimeMillis() + 60_000))
    engine.script = { _, _ -> listOf(Effect.Approve) }
    c.handleUserUtterance("yes")

    engine.seen.clear()
    c.onApprovalTimeoutWarning()

    assertTrue("re-checked inside the lock — nothing is pending now", engine.seen.isEmpty())
  }
}
