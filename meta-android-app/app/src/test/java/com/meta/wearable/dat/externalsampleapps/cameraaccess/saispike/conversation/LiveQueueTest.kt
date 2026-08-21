/* sai-fi — voice concierge. */

// The queue and the stop button, against a real agent.
//
//   SAI_LIVE_AGENT=1 SAI_API_URL=… SAI_MACHINE_ID=… SAI_ID_TOKEN=… \
//     ./gradlew :app:testDebugUnitTest --tests "*LiveQueueTest*" --rerun
//
// **This bills a real agent.** Same tier and same rules as LiveAgentTest, which covers one plain task;
// what is here is the two paths that a plain task never touches, and that until now had never run
// against anything but a fake:
//
//   · APPENDING — a second ask arriving while the first is genuinely still running. Every existing
//     queue test drives the clock, so the "still running" part was a premise. Here it is real latency:
//     the second turn is issued without waiting for the first, and the agent takes as long as it takes.
//   · ABORT, then START NEW — the paths whose whole job is to stop work. The golden catalog pins the
//     decision against a fake; `resetSession` had only a unit test. The most recent bug in that area
//     ("start fresh" rotated the terminal's conversation rather than this one) was a wire bug, which is
//     precisely the kind this tier exists to catch and the pure tiers structurally cannot.
//
// Assertions are INVARIANTS, not sequences, for the reason LiveAgentTest gives: real latency is
// whatever the agent does, and a test that pins timing here teaches everyone to ignore it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.isWorking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveQueueTest {

  private fun requireOptIn(): LiveAgentConfig {
    assumeTrue(
        "set SAI_LIVE_AGENT=1 to run the live queue/abort checks (they wake a VM and bill a real agent)",
        System.getenv("SAI_LIVE_AGENT") == "1")
    return LiveAgentConfig.fromEnv().getOrElse { throw IllegalStateException(it.message, it) }
  }

  private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun refusalGuard(live: LiveAgent) =
      assertTrue(
          "the request was refused before it reached the agent: ${live.errors}. A 401 is an expired " +
              "SAI_ID_TOKEN, a 403 a machine that is not this user's — neither is a contract change.",
          live.errors.isEmpty())

  /**
   * A second ask, while the first is really still running.
   *
   * The FIRST assertion is the one that could not be made off-device: at the moment the second ask
   * lands, the agent is mid-task for reasons of physics rather than because a fixture said so. If the
   * admission rule folded the second request into the running turn, or the drain never fired, real
   * latency is what exposes it.
   */
  @Test
  fun `a second ask while a real task runs is queued, and runs when the first finishes`() {
    val config = requireOptIn()
    val first = "list the files in the home directory — this is an automated contract check"
    val second = "what is 19 times 23 — this is an automated contract check"

    val brain =
        ScriptedBrain.of(
            whenSaid("home directory") { _, _ ->
              BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to first)))
            },
            // The admission decision is the model's, and it reads the FSM's own view of whether
            // anything is running — so this is also a check that the view is right in real time.
            whenSaid("19 times 23") { _, state ->
              if (state.isWorking())
                  BrainTurn("I'll do that next", callsOf(fc("enqueue", "task" to second)))
              else BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to second)))
            },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )

    runBlocking {
      val h = ConversationHarness(brain)
      val live =
          LiveAgent(config, scope(), deliver = { h.deliverAgentEvent(it) }, log = { println("    $it") })
      h.useTransport(live)
      h.start()

      h.user("can you list the files in the home directory?")
      // Deliberately NO wait: the point is that the next ask arrives while the agent is still working.
      h.user("oh and what is 19 times 23?")

      println("    [test] at the moment of the second ask: started=${live.started} queued=${h.state.queue.size}")
      refusalGuard(live)
      assertEquals(
          "the second ask was folded into the running turn instead of being queued — or the first " +
              "never started. Real latency means the agent was still working when it arrived.",
          listOf(first),
          live.started)
      assertTrue("the second ask reached nothing at all", h.state.queue.isNotEmpty())
      assertTrue("the user was not told it would wait", h.saidSomethingLike("next"))

      // Both turns, at whatever pace the day allows.
      val ran =
          withTimeoutOrNull(BOTH_TURNS_TIMEOUT_MS) {
            live.awaitTurn() // the first
            while (live.started.size < 2) live.awaitTurn()
            live.awaitTurn() // the drained one
            true
          }

      println("    [test] started: ${live.started}")
      assertTrue(
          "the agent never got through both turns within ${BOTH_TURNS_TIMEOUT_MS / 1000}s — a cold " +
              "machine or an unreachable server, not a behaviour finding",
          ran == true)
      assertEquals("the queued task ran out of order, or never ran", listOf(first, second), live.started)
      assertTrue("something is still waiting after both turns ended", h.state.queue.isEmpty())
    }
  }

  /**
   * Stopping a real task, and then rotating onto a fresh conversation.
   *
   * One thing outstanding is unambiguous, so a single interrupt aborts rather than asking. What this
   * proves that a fake cannot: the operation really reached `POST abort` and the server accepted it,
   * and `resetSession` really reached `POST new-session` — the endpoint whose most recent bug was that
   * it rotated the wrong conversation.
   */
  @Test
  fun `stopping a real task aborts it on the wire, and a fresh session can then be started`() {
    val config = requireOptIn()
    val task = "count slowly from 1 to 40, one line each — this is an automated contract check"

    val brain =
        ScriptedBrain.of(
            whenSaid("count") { _, _ ->
              BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to task)))
            },
            whenSaid("stop") { _, _ -> BrainTurn("stopping", callsOf(fc("interrupt"))) },
            whenSaid("start fresh") { _, _ -> BrainTurn("", callsOf(fc("resetSession"))) },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )

    runBlocking {
      val h = ConversationHarness(brain)
      val live =
          LiveAgent(config, scope(), deliver = { h.deliverAgentEvent(it) }, log = { println("    $it") })
      h.useTransport(live)
      h.start()

      h.user("please count slowly to forty")
      refusalGuard(live)
      assertEquals("the task never started, so there is nothing to stop", listOf(task), live.started)

      h.user("stop")

      assertTrue("the abort never reached the wire: posts=${live.posts}", live.posts.contains("abort"))
      // No agent event will follow, because `abort()` stops this device following the turn — so the
      // handler has to close the turn out itself. If it does not, the FSM sits in `working` for the
      // rest of the call and every later task queues behind a turn that will never end.
      assertFalse("the FSM was left believing the aborted task is still running", h.state.isWorking())
      assertTrue("the queue survived a stop-everything", h.state.queue.isEmpty())

      // SETTLE, and this is the one tier that could have caught the 2026-08-20 bug and did not:
      // everything above was asserted the instant the abort returned, while the real turn was still
      // mid-count. The failure was entirely in what happened NEXT — the reader nobody closed carried
      // on delivering, and the count's completion was announced as a result. Nothing here can wait
      // for the turn to "finish" (it was aborted, so the join returns at once); a fixed settle is
      // what gives the phantom events time to arrive if they are going to.
      val postsAtAbort = live.posts.size
      val eventsAtAbort = live.received.size
      delay(SETTLE_AFTER_ABORT_MS)
      assertEquals(
          "events arrived from a turn this device had stopped following: " +
              "${live.received.drop(eventsAtAbort).map { it::class.simpleName }}",
          eventsAtAbort,
          live.received.size)
      assertEquals("something posted after the abort", postsAtAbort, live.posts.size)

      // …and only now, with nothing outstanding, will a reset go through: `applyResetSession` refuses
      // while work is in flight rather than orphaning it. TWICE, because the first call only asks —
      // a rotation cannot be undone, and a misheard "forget it" used to be enough to spend one.
      h.user("start fresh")
      assertFalse(
          "the first resetSession rotated without confirming: posts=${live.posts}",
          live.posts.contains("new-session"))
      h.user("yes, start fresh")
      assertTrue(
          "new-session never reached the wire: posts=${live.posts}", live.posts.contains("new-session"))
      refusalGuard(live)

      println("    [test] posts: ${live.posts}")
      assertFalse(
          "an aborted task was reported to the user as finished",
          h.saidSomethingLike("done —"))
    }
  }

  private companion object {
    /** Two real turns, one of which may wake a hibernated machine. */
    const val BOTH_TURNS_TIMEOUT_MS = 420_000L

    /**
     * How long to give a stopped turn to prove it is really stopped.
     *
     * Generous on purpose, and the task it waits on counts to forty one line at a time: the point is
     * that the events would still be coming if the reader were still attached. A shorter window
     * would pass for the same reason the original assertions did — by looking before anything had a
     * chance to go wrong.
     */
    const val SETTLE_AFTER_ABORT_MS = 8_000L
  }
}
