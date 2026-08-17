/* sai-fi — voice concierge. */

// The queue, end to end: the model decides, the FSM admits, the agent runs, and the user is told.
//
// The golden catalog already pins each of those in isolation. What it cannot pin is the JOIN, because
// the events it replays were written by hand on the assumption that the drain fired. Here the agent
// only produces events for tasks something actually forwarded, so "Sai said it would do that next,
// and then it ran, and then the user heard about it" is an emergent property of the loop rather than
// a premise of the fixture.
//
// That is the failure ON_DEVICE_CHECK §6a calls the highest risk on the whole device:
//   "if Sai says 'I'll do that next' and then it never runs, the drain never fired."

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

class QueueConversationTest {

  private val EMAIL = "check my email"
  private val TABLE = "book a table for two on Friday"

  /** A model that forwards the first ask, queues the second, and relays what comes back. */
  private fun twoTaskBrain() =
      ScriptedBrain.of(
          whenSaid("email") { _, _ ->
            BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL)))
          },
          whenSaid("table") { _, state ->
            if (state.isWorking())
                BrainTurn(
                    "I'll start that as soon as this one's done",
                    callsOf(fc("enqueue", "task" to TABLE)))
            else BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to TABLE)))
          },
          // A completion nudge: relay it. Without this the model would hear the result and say
          // nothing, and "the user was told" would be untestable.
          whenNudged("[agent]") { input, _ -> BrainTurn("that's done — $input") },
      )

  /**
   * A task, as the agent actually reports one: it goes `processing` first, then finishes.
   *
   * The opening status is not decoration — it is what tells the activity log a task has begun, and
   * `getSaiStatus` answers "Still working" from that. A programme that jumps straight to `complete`
   * describes a task nobody ever started.
   */
  private fun task(summary: String, doneAfterMs: Long) =
      listOf(
          AgentBeat(20, AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(doneAfterMs, AgentEvent.Complete(summary)),
      )

  private fun harness(brain: ScriptedBrain = twoTaskBrain()) =
      ConversationHarness(brain).apply {
        agent.programs += AgentProgram({ it.contains("email") }, task("3 new emails", 600))
        agent.programs += AgentProgram({ it.contains("table") }, task("table booked", 400))
      }

  @Test
  fun `a task queued behind a running one actually runs, and its result reaches the user`() =
      runBlocking {
        val h = harness()
        h.start()

        h.user(EMAIL)
        h.advance(100) // the first task is under way

        h.user(TABLE) // asked while the first is still running
        assertTrue(
            "the second ask must be admitted to the queue, not folded into the running turn",
            h.saidSomethingLike("as soon as this one's done"))
        assertEquals("nothing should have started it yet", listOf(EMAIL), h.agent.started)

        h.settle()

        // The drain fired: the agent really started the second task, and only after the first.
        assertEquals(listOf(EMAIL, TABLE), h.agent.started)
        assertTrue("one task at a time", h.agent.overlapped.isEmpty())

        // And the user heard about both, which is the half a queue test usually forgets.
        assertTrue("the first result was never spoken", h.saidSomethingLike("3 new emails"))
        assertTrue("the second result was never spoken", h.saidSomethingLike("table booked"))
      }

  @Test
  fun `a queued task is not called underway while it is still waiting`() = runBlocking {
    val h = harness()
    h.start()
    h.user(EMAIL)
    h.advance(100)
    h.user(TABLE)

    // What getSaiStatus would tell the model right now — the projection it answers questions from.
    // It does not name the running task on purpose (the activity lines carry that); what it must do
    // is account for the waiting one separately, and say plainly that it has not begun.
    val status = h.status()

    assertTrue("something should be reported as running: $status", status.contains("Still working"))
    assertTrue("the waiting task should be named: $status", status.contains(TABLE))
    assertTrue(
        "a waiting task must be described as not started, not as underway: $status",
        status.contains("NOT STARTED YET"))
    assertTrue(
        "and the model must be told not to call it underway: $status",
        status.contains("never as underway"))
  }

  @Test
  fun `the queue stops being mentioned the moment it drains`() = runBlocking {
    val h = harness()
    h.start()
    h.user(EMAIL)
    h.advance(100)
    h.user(TABLE)
    assertTrue(h.status().contains("NOT STARTED YET"))

    h.settle()

    assertFalse(
        "nothing is waiting any more, so nothing should still be listed: ${h.status()}",
        h.status().contains("NOT STARTED YET"))
  }

  @Test
  fun `two queued behind one run in the order they were asked for`() = runBlocking {
    val third = "water the plants"
    val brain =
        ScriptedBrain.of(
            whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
            whenSaid("table") { _, _ -> BrainTurn("after this", callsOf(fc("enqueue", "task" to TABLE))) },
            whenSaid("plants") { _, _ -> BrainTurn("after that", callsOf(fc("enqueue", "task" to third))) },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )
    val h = harness(brain)
    h.agent.programs += AgentProgram({ it.contains("plants") }, task("plants watered", 300))

    h.start()
    h.user(EMAIL)
    h.advance(100)
    h.user(TABLE)
    h.user(third)

    h.settle()

    assertEquals("FIFO, and every one of them run", listOf(EMAIL, TABLE, third), h.agent.started)
    assertTrue(h.saidSomethingLike("plants watered"))
  }

  @Test
  fun `cancelling a queued task stops it running at all`() = runBlocking {
    val brain =
        ScriptedBrain.of(
            // "forget" first: the user's cancellation ("forget the table booking") also contains
            // "table", and first-match-wins would otherwise queue it a second time.
            whenSaid("forget") { _, _ ->
              BrainTurn("dropped it", callsOf(fc("cancelQueued", "task" to TABLE)))
            },
            whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
            whenSaid("table") { _, _ -> BrainTurn("after this", callsOf(fc("enqueue", "task" to TABLE))) },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )
    val h = harness(brain)
    h.start()
    h.user(EMAIL)
    h.advance(100)
    h.user(TABLE)
    h.user("actually, forget the table booking")

    h.settle()

    assertEquals("a cancelled task must never start", listOf(EMAIL), h.agent.started)
    assertFalse(
        "and must not still be listed once it is gone: ${h.status()}",
        h.status().contains(TABLE))
  }

  @Test
  fun `reordering starts the waiting task without stopping the running one`() = runBlocking {
    val brain =
        ScriptedBrain.of(
            whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
            whenSaid("table") { _, _ -> BrainTurn("after this", callsOf(fc("enqueue", "task" to TABLE))) },
            whenSaid("first") { _, _ ->
              BrainTurn("starting that now", callsOf(fc("sendQueuedNow", "task" to TABLE)))
            },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )
    val h = harness(brain)
    h.start()
    h.user(EMAIL)
    h.advance(100)
    h.user(TABLE)
    h.user("do the Friday booking first")
    h.settle()

    assertTrue("the promoted task must actually start", h.agent.started.contains(TABLE))
    // A reorder is not a cancellation: nothing should have aborted the running task to make room.
    assertTrue(
        "nothing needed to stop, so nothing should have been aborted",
        h.agent.callsTo("abort").isEmpty())
    assertTrue("the running task still finished", h.saidSomethingLike("3 new emails"))
  }

  @Test
  fun `a forward the agent refuses is admitted to, not silently swallowed`() = runBlocking {
    val h = harness()
    h.agent.failNextSend = true
    h.start()

    h.user(EMAIL)
    h.settle()

    assertEquals("nothing started", emptyList<String>(), h.agent.started)
    assertFalse(
        "a task that never started must not be reported as running",
        h.state.isWorking())
    // The user has to learn it did not go — silence here is the failure the FSM exists to prevent.
    assertTrue(
        "the user was told nothing about a task that never started: ${h.heard()}",
        h.heard().isNotBlank())
  }
}
