/* sai-fi — voice concierge. */

// Barge-in, where it meets the queue.
//
// Barge-in has two halves. The ACOUSTIC half — AEC, VAD sensitivity, the noise gate, whether the
// glasses SCO route self-triggers — is not automatable and stays an on-device check by ear. The
// PROTOCOL half is: an interrupt ends the turn, opens a discard window, and decides the fate of
// anything the client was holding to say. That half is what these cover, and it is where the
// expensive failure lives:
//
//   ON_DEVICE_CHECK §7 — "Cutting Sai off must not cost you the result: it should still arrive
//   after the exchange."
//
// A result the user never hears is worse than a clumsy interruption, and it is silent, so nobody
// reports it as a bug — they just stop trusting the thing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInConversationTest {

  private val TASK = "check my email"

  private fun task(summary: String, doneAfterMs: Long) =
      listOf(
          AgentBeat(20, AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(doneAfterMs, AgentEvent.Complete(summary)),
      )

  /** Forwards on request, relays completions, and answers anything else briefly. */
  private fun brain() =
      ScriptedBrain.of(
          whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to TASK))) },
          whenSaid("rundown") { _, _ ->
            BrainTurn("here's a long rundown of everything I can do, starting with the first thing")
          },
          whenSaid("weather") { _, _ -> BrainTurn("it's clear out") },
          whenNudged("[agent]") { input, _ -> BrainTurn("your task finished — $input") },
      )

  private fun harness() =
      ConversationHarness(brain()).apply {
        agent.programs += AgentProgram({ true }, task("3 new emails", 600))
      }

  @Test
  fun `a result that lands while Sai is mid-sentence is held, then delivered`() = runBlocking {
    val h = harness()
    h.speakingMs = 2_000 // a long answer, so the completion lands inside it
    h.start()

    h.user(TASK)
    h.settle()

    assertTrue("the completion should have been held for the turn", h.logHas("held until the turn ends"))
    assertTrue("and then actually delivered", h.logHas("← nudge: delivering complete"))
    assertTrue("so the user hears the result", h.saidSomethingLike("3 new emails"))
  }

  @Test
  fun `cutting Sai off does not cost the result`() = runBlocking {
    val h = harness()
    h.speakingMs = 2_000
    h.start()

    h.user(TASK) // Sai says "on it" and starts a long-ish turn
    h.advance(700) // the task completes mid-utterance, so the nudge is held

    assertTrue("precondition: the completion is being held", h.logHas("held until the turn ends"))

    // The user talks over Sai before that turn ever ended.
    h.bargeIn("actually, what's the weather?")
    h.settle()

    assertTrue("the barge-in was registered", h.logHas("— barge-in —"))
    assertTrue("the new question is answered", h.saidSomethingLike("it's clear out"))
    // The whole point: the result survives the interruption.
    assertTrue(
        "the result was lost when the user cut Sai off — the exact §7 failure: ${h.heard()}",
        h.saidSomethingLike("3 new emails"))
  }

  @Test
  fun `an interrupt ends the turn, so the next result is not queued behind a turn nobody will end`() =
      runBlocking {
        val h = harness()
        h.speakingMs = 5_000 // Sai is mid-monologue and would be for a long time
        h.start()
        h.user("give me a rundown")
        h.advance(100)
        assertTrue("precondition: Sai is mid-utterance", h.gate.isModelSpeaking)

        // The interrupt alone, before the user's next words land.
        h.gate.onInterrupted()

        assertFalse("the abandoned turn is over", h.gate.isModelSpeaking)
        assertTrue("and its stragglers are dropped for a beat", h.gate.shouldDiscardAudio())
        // So a completion arriving now goes straight out, rather than waiting for a turn boundary
        // the barge-in already consumed — the wait that used to lose it.
        assertTrue(
            "a result arriving right after an interrupt must not be held",
            h.gate.injectNudge("complete", "[agent] done").any { it is
                com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.GateAction.SendTurn })
      }

  @Test
  fun `the abandoned reply does not resume after the barge-in is answered`() = runBlocking {
    val h = harness()
    h.speakingMs = 5_000
    h.start()
    h.user("give me a rundown")
    h.advance(100)

    h.bargeIn("stop — what's the weather?")
    h.settle()

    assertTrue("the new question is answered", h.saidSomethingLike("it's clear out"))
    // The rundown was cut off and must stay cut off: it is said once, not restarted afterwards.
    assertEquals(
        "the abandoned answer resumed after the interruption",
        1,
        h.transcript.count { it.speaker == "sai" && it.text.contains("rundown") })
  }

  @Test
  fun `a task interrupted mid-report keeps running and still reports`() = runBlocking {
    val h = harness()
    h.speakingMs = 1_500
    h.start()

    h.user(TASK)
    h.advance(100)
    h.bargeIn("hang on — what's the weather?")
    h.settle()

    // Barging in is not a cancellation: nothing should have aborted the task.
    assertTrue("nothing should have been aborted", h.agent.callsTo("abort").isEmpty())
    assertEquals("the task ran exactly once", listOf(TASK), h.agent.started)
    assertTrue("and its result still arrived", h.saidSomethingLike("3 new emails"))
  }

  @Test
  fun `a barge-in followed by a reconnect loses the held result — but says so`() = runBlocking {
    val h = harness()
    h.speakingMs = 2_000
    h.start()

    h.user(TASK)
    h.advance(700) // completion held mid-utterance
    assertTrue(h.logHas("held until the turn ends"))

    // The barge-in ends the turn without flushing, and the token expires before the next boundary.
    h.gate.onInterrupted()
    h.reconnect()

    // This IS a loss — the recorded bug. What must never happen again is losing it in silence, so
    // the check is that the log names exactly what went missing.
    assertTrue(
        "a lost completion must be named, not vanish: ${h.log.takeLast(6)}",
        h.logHas("✗ nudge: dropping complete"))
    assertTrue(h.logHas("session replaced"))
  }

  @Test
  fun `muting holds a result and unmuting offers it once`() = runBlocking {
    val h = harness()
    h.start()

    h.user(TASK)
    h.setMuted(true)
    h.settle()

    assertFalse(
        "nothing should be spoken while muted: ${h.heard()}",
        h.saidSomethingLike("3 new emails"))

    h.setMuted(false)
    h.settle()

    assertTrue("the held result is offered after unmuting", h.saidSomethingLike("3 new emails"))
    assertEquals(
        "and offered once, not replayed as a pile",
        1,
        h.transcript.count { it.speaker == "sai" && it.text.contains("3 new emails") })
  }
}
