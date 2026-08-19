/* sai-fi — voice concierge. */

// Stopping work, end to end: the model decides, the FSM asks first, and only then does anything die.
//
// The golden catalog pins the interrupt DECISION (S4, S4b–S4e, S46) against a fake agent. What it
// cannot pin is the join, and the gap this file exists for was a specific one: across the whole suite,
// every assertion about `abort` was a NEGATIVE one — `callsTo("abort").isEmpty()`, proving a barge-in
// or a reorder did not cancel anything. `ScriptedAgent.abortRunning` existed and nothing ever drove
// it. The path that actually stops a task therefore ran, off-device, exactly never.
//
// The two rules worth the join, both of which the user hears:
//   · an interrupt with work in flight ASKS before killing anything — one running and one waiting is
//     an ambiguous "stop", and guessing stops the wrong one silently;
//   · a stopped task is NOT a finished task. Reporting an abort as a completion is how "all done"
//     gets said about work that never ran.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.isWorking
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbortConversationTest {

  private val EMAIL = "check my email"
  private val TABLE = "book a table for two on Friday"

  /**
   * A model that starts work, queues a second ask, and asks-then-stops on "stop".
   *
   * The two-step is the shape the FSM requires and the shape a person uses: "stop" with two things
   * outstanding is a question, and the answer arrives as a second interrupt.
   */
  private fun stoppingBrain() =
      ScriptedBrain.of(
          whenSaid("email") { _, _ ->
            BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL)))
          },
          whenSaid("table") { _, state ->
            if (state.isWorking()) BrainTurn("that's next", callsOf(fc("enqueue", "task" to TABLE)))
            else BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to TABLE)))
          },
          whenSaid("stop") { _, _ -> BrainTurn("", callsOf(fc("interrupt"))) },
          whenSaid("all of it") { _, _ -> BrainTurn("", callsOf(fc("interrupt"))) },
          whenNudged("[agent]") { input, _ -> BrainTurn("that's done — $input") },
      )

  /** Long enough that a stop lands mid-flight rather than after the fact. */
  private fun task(summary: String, doneAfterMs: Long) =
      listOf(
          AgentBeat(20, AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(doneAfterMs, AgentEvent.Complete(summary)),
      )

  private fun harness() =
      ConversationHarness(stoppingBrain()).apply {
        agent.programs += AgentProgram({ it.contains("email") }, task("3 new emails", 5_000))
        agent.programs += AgentProgram({ it.contains("table") }, task("table booked", 5_000))
      }

  @Test
  fun `stopping everything really aborts the running task, and the queue goes with it`() =
      runBlocking {
        val h = harness()
        h.start()
        h.user(EMAIL)
        h.advance(100)
        h.user(TABLE) // one running, one waiting
        h.advance(100)

        h.user("stop") // ambiguous with two outstanding, so this asks rather than kills
        h.advance(100)
        assertTrue(
            "an interrupt with work outstanding must ask before killing anything",
            h.agent.callsTo("abort").isEmpty())

        h.user("all of it")
        h.advance(200)

        // The positive path, which nothing exercised before this test: it really reached the transport.
        assertFalse("the abort never reached the agent", h.agent.callsTo("abort").isEmpty())
        assertTrue("the queue must go too, or the next task starts after the stop", h.state.queue.isEmpty())

        h.settle()

        // A stopped task is not a finished one. The agent double cancels the beats of an aborted task,
        // so a summary reaching the user here means either the abort did not land or the FSM reported
        // it as a completion — both of which are the same lie to the user.
        assertFalse("an aborted task was reported as done", h.saidSomethingLike("3 new emails"))
        assertFalse("the queued task ran anyway", h.saidSomethingLike("table booked"))
        assertEquals("only the first task ever started", listOf(EMAIL), h.agent.started)
      }

  @Test
  fun `a stop with nothing running starts nothing and leaves the FSM idle`() = runBlocking {
    val h = harness()
    h.start()

    h.user("stop") // nothing in flight at all
    h.advance(200)

    assertTrue("nothing should have been started", h.agent.started.isEmpty())
    assertFalse("the FSM must not be left believing work is in flight", h.state.isWorking())

    // The abort DOES still go out, deliberately unasserted as absent: `applyInterrupt` only asks when
    // more than one thing is outstanding, so with nothing outstanding it aborts unconditionally. That
    // is defensible — this device's view of the agent can be stale, and "stop" should stop whatever is
    // really there — but it does mean a stop here can reach work this call never started. Pinning it
    // either way would be pinning a decision that belongs to the interrupt contract, not to this test.
    assertEquals(listOf("abort"), h.agent.calls.map { it.method }.filter { it == "abort" })
  }
}
