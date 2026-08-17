/* sai-fi — voice concierge. */

// The same conversation, at different speeds.
//
// Every barge-in ⇄ queue bug on record is a race: a completion that lands one moment too early is
// held for a turn that then never ends; one that lands a moment too late arrives after the user gave
// up. A single-timing test picks one point on that line and says nothing about the rest of it — and
// the point it picks is usually the one the author found convenient, which is rarely the awkward one.
//
// So the assertions here are INVARIANTS — things that must be true whatever the timing — and each is
// checked across a grid of them. Virtual time makes this nearly free: the whole matrix runs in
// milliseconds because nothing actually waits.
//
// A cell failing while its neighbours pass is the finding. The failure message names the cell, since
// "it works at 800 ms and breaks at 600" is most of the diagnosis.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingMatrixTest {

  private val EMAIL = "check my email"
  private val TABLE = "book a table for two on Friday"

  /**
   * How long Sai's speech occupies the channel, and how long each task takes.
   *
   * The interesting cells are the ones where the second value lands INSIDE the first: that is the
   * window in which a completion is held, and holding is where results go missing.
   */
  data class Timing(val speakingMs: Long, val taskMs: Long) {
    override fun toString() = "speaking=${speakingMs}ms task=${taskMs}ms"
  }

  private val MATRIX =
      listOf(
          Timing(speakingMs = 200, taskMs = 100), // the agent beats Sai's sentence
          Timing(speakingMs = 200, taskMs = 900), // it lands well after
          Timing(speakingMs = 800, taskMs = 400), // squarely mid-sentence — the held case
          Timing(speakingMs = 800, taskMs = 800), // dead on the turn boundary
          Timing(speakingMs = 2_500, taskMs = 600), // deep inside a long monologue
          Timing(speakingMs = 2_500, taskMs = 4_000), // a slow task under a long monologue
          Timing(speakingMs = 50, taskMs = 50), // everything at once
      )

  private fun task(summary: String, ms: Long) =
      listOf(
          AgentBeat(minOf(20, ms), AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(ms, AgentEvent.Complete(summary)),
      )

  private fun twoTaskHarness(t: Timing): ConversationHarness {
    val brain =
        ScriptedBrain.of(
            whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
            whenSaid("table") { _, _ ->
              BrainTurn("right after this one", callsOf(fc("enqueue", "task" to TABLE)))
            },
            whenNudged("[agent]") { input, _ -> BrainTurn("that's done — $input") },
        )
    return ConversationHarness(brain, speakingMs = t.speakingMs).apply {
      agent.programs += AgentProgram({ it.contains("email") }, task("3 new emails", t.taskMs))
      agent.programs += AgentProgram({ it.contains("table") }, task("table booked", t.taskMs))
    }
  }

  /** Run [body] at every timing and report every cell that broke, not merely the first. */
  private fun acrossTimings(body: suspend (Timing) -> Unit) {
    val failures = mutableListOf<String>()
    for (t in MATRIX) {
      try {
        runBlocking { body(t) }
      } catch (e: Throwable) {
        failures += "  [$t] ${e.message?.lines()?.firstOrNull()}"
      }
    }
    assertTrue(
        "the same conversation behaves differently depending on timing — " +
            "${failures.size}/${MATRIX.size} cells failed:\n${failures.joinToString("\n")}",
        failures.isEmpty(),
    )
  }

  @Test
  fun `a queued task drains and reports, whatever the timing`() = acrossTimings { t ->
    val h = twoTaskHarness(t)
    h.start()
    h.user(EMAIL)
    h.advance(t.taskMs / 4) // partway into the first task, whatever its length
    h.user(TABLE)
    h.settle()

    check(h.agent.started == listOf(EMAIL, TABLE)) {
      "tasks ran as ${h.agent.started}, expected both in order"
    }
    check(h.agent.overlapped.isEmpty()) { "two tasks ran at once: ${h.agent.overlapped}" }
    check(h.saidSomethingLike("3 new emails")) { "the first result never reached the user" }
    check(h.saidSomethingLike("table booked")) { "the second result never reached the user" }
    check(!h.status().contains("NOT STARTED YET")) { "the queue never emptied: ${h.status()}" }
  }

  @Test
  fun `a barge-in never costs the result, whatever the timing`() = acrossTimings { t ->
    val brain =
        ScriptedBrain.of(
            whenSaid("email") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL))) },
            whenSaid("weather") { _, _ -> BrainTurn("it's clear out") },
            whenNudged("[agent]") { input, _ -> BrainTurn("that's done — $input") },
        )
    val h =
        ConversationHarness(brain, speakingMs = t.speakingMs).apply {
          agent.programs += AgentProgram({ true }, task("3 new emails", t.taskMs))
        }
    h.start()
    h.user(EMAIL)
    h.advance(t.taskMs / 2) // cut in halfway through the task, wherever that falls in the sentence
    h.bargeIn("hang on — what's the weather?")
    h.settle()

    check(h.saidSomethingLike("it's clear out")) { "the interrupting question went unanswered" }
    check(h.saidSomethingLike("3 new emails")) {
      "cutting Sai off cost the result — ON_DEVICE_CHECK §7. Heard: ${h.heard()}"
    }
    check(h.agent.callsTo("abort").isEmpty()) { "a barge-in aborted the running task" }
  }

  /**
   * Sweep the interrupt across a task's whole lifetime, one millisecond band at a time.
   *
   * The matrix above samples a handful of points; this walks the interrupt right through the
   * completion, which is the collision that actually loses results — the interrupt landing in the
   * same instant as the event that was about to be spoken.
   */
  @Test
  fun `an interrupt at any instant of the task still lets the result through`() {
    val taskMs = 500L
    val failures = mutableListOf<String>()
    for (at in listOf(0L, 50L, 200L, 450L, 490L, 499L, 500L, 501L, 550L, 900L)) {
      val brain =
          ScriptedBrain.of(
              whenSaid("email") { _, _ ->
                BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to EMAIL)))
              },
              whenSaid("weather") { _, _ -> BrainTurn("it's clear out") },
              whenNudged("[agent]") { input, _ -> BrainTurn("that's done — $input") },
          )
      val h =
          ConversationHarness(brain, speakingMs = 700).apply {
            agent.programs += AgentProgram({ true }, task("3 new emails", taskMs))
          }
      runBlocking {
        h.start()
        h.user(EMAIL)
        h.advance(at)
        h.bargeIn("hang on — what's the weather?")
        h.settle()
      }
      if (!h.saidSomethingLike("3 new emails")) {
        failures += "  interrupt at ${at}ms (task completes at ${taskMs}ms) — result lost"
      }
    }
    assertTrue(
        "an interrupt landing at certain moments loses the result:\n${failures.joinToString("\n")}",
        failures.isEmpty(),
    )
  }
}
