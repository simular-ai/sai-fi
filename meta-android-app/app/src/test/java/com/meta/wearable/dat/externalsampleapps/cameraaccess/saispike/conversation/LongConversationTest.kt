/* sai-fi — voice concierge. */

// One long call, where the interesting failures actually live.
//
// The other scenarios are multi-turn but short — two or three user turns each, enough to isolate one
// behaviour. That is the right shape for pinning a rule, and the wrong shape for finding the bugs
// that need STATE to accumulate: a queue two deep that then gets interrupted, a completion that lands
// while muted after a barge-in, a drain that has to survive all of it. Every one of those needs a
// conversation with a history, and a scenario that restarts between beats destroys the very thing it
// is meant to exercise.
//
// A live demo run made the case: every individual beat had passed in isolation, and the sequence had
// never been run end to end. The first time it was, three separate things went wrong that no
// single-beat scenario could have surfaced.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongConversationTest {

  private val EMAIL = "check my email"
  private val TABLE = "book a table for two on Friday"
  private val PLANTS = "remind me to water the plants"

  private fun task(summary: String, ms: Long) =
      listOf(
          AgentBeat(20, AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(ms, AgentEvent.Complete(summary)),
      )

  private fun brain() =
      ScriptedBrain.of(
          // Ordered so the more specific intents win: "forget the table" also contains "table".
          whenSaid("forget") { _, _ -> BrainTurn("dropped it", callsOf(fc("cancelQueued", "task" to PLANTS))) },
          whenSaid("weather") { _, _ -> BrainTurn("it's clear and mild out") },
          whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
          whenSaid("table") { _, _ -> BrainTurn("sure, after this", callsOf(fc("forwardToAgent", "text" to TABLE))) },
          whenSaid("plants") { _, _ -> BrainTurn("that too", callsOf(fc("forwardToAgent", "text" to PLANTS))) },
          whenSaid("going on") { _, state -> BrainTurn("here's where things stand") },
          whenNudged("[agent]") { input, _ -> BrainTurn("update — $input") },
      )

  /**
   * Twelve turns, one call, state carried the whole way.
   *
   * Every assertion is about something that could only go wrong BECAUSE of what came before it.
   */
  @Test
  fun `a long call carries its queue through a barge-in, a mute and a cancellation`() = runBlocking {
    val h =
        ConversationHarness(brain(), speakingMs = 900).apply {
          agent.programs += AgentProgram({ it.contains("email") }, task("3 new emails", 2_000))
          agent.programs += AgentProgram({ it.contains("table") }, task("table booked", 1_200))
          agent.programs += AgentProgram({ it.contains("plants") }, task("reminder set", 800))
        }
    h.start()

    // 1–2. A task starts, and a second is asked for while it runs. The queue exists from here on.
    h.user(EMAIL)
    h.advance(300)
    h.user(TABLE)
    assertTrue("the second ask must be queued, not started", h.agent.started == listOf(EMAIL))

    // 3. A third, so the queue is two deep — the depth the short scenarios never reach.
    h.user(PLANTS)
    assertEquals("still only the first has started", listOf(EMAIL), h.agent.started)
    assertTrue("both waiting tasks should be visible", h.status().contains(TABLE) && h.status().contains(PLANTS))

    // 4. Barge in, mid-everything. The queue must survive an interrupted turn.
    h.bargeIn("hang on — what's the weather?")
    assertTrue("the interruption is answered", h.saidSomethingLike("clear and mild"))
    assertTrue("and the queue is still intact", h.status().contains(TABLE))

    // 5. Mute, and let the FIRST task finish while muted — far enough that the drain starts the
    //    second, not so far that it also starts the third. Its result must be held, not lost.
    h.setMuted(true)
    h.advance(2_200)
    assertTrue("the drain should have started the second task", h.agent.started.contains(TABLE))

    // 6. Cancel the third while the second is running — a cancellation applied to a queue a drain
    //    has already reordered. The precondition is asserted because getting it wrong is silent:
    //    cancel a task that already started and `cancelQueued` correctly does nothing, so the test
    //    passes for the wrong reason or fails for a bug that is not there.
    assertTrue(
        "precondition: the third must still be WAITING when it is cancelled, or this proves nothing",
        h.status().contains(PLANTS) && !h.agent.started.contains(PLANTS))
    h.user("actually, forget the plants")

    // 7. Unmute. What was held is offered, once.
    h.setMuted(false)
    h.settle()

    // The whole point: after all of that, the queue was still honoured.
    assertEquals(
        "the cancelled task must never run, and the rest must run in order",
        listOf(EMAIL, TABLE),
        h.agent.started)
    assertTrue("one at a time throughout", h.agent.overlapped.isEmpty())
    assertTrue("the first result survived the mute", h.saidSomethingLike("3 new emails"))
    assertTrue("the second result arrived too", h.saidSomethingLike("table booked"))
    assertTrue(
        "nothing should still be waiting at the end: ${h.status()}",
        !h.status().contains("NOT STARTED YET"))

    // A conversation of real length, not two turns wearing a long name.
    assertTrue("this should be a long exchange, not a stub: ${h.transcript.size}", h.transcript.size >= 12)
  }

  /**
   * The same shape, but the interruption lands in the worst place: between a task finishing and its
   * result being spoken.
   */
  @Test
  fun `a result held behind a turn survives being interrupted twice`() = runBlocking {
    val h =
        ConversationHarness(brain(), speakingMs = 2_500).apply {
          agent.programs += AgentProgram({ true }, task("3 new emails", 1_000))
        }
    h.start()
    h.user(EMAIL)
    h.advance(1_200) // completion lands mid-utterance and is held

    h.bargeIn("wait — what's the weather?")
    h.advance(200)
    h.bargeIn("sorry, one more time — the weather?")
    h.settle()

    assertTrue("the last question is answered", h.saidSomethingLike("clear and mild"))
    assertTrue(
        "two interruptions in a row must not lose the result: ${h.heard()}",
        h.saidSomethingLike("3 new emails"))
  }
}
