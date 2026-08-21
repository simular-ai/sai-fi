/* sai-fi — voice concierge. */

// The contract check: does cloud-api still send what the FSM expects?
//
//   SAI_LIVE_AGENT=1 SAI_API_URL=… SAI_MACHINE_ID=… SAI_ID_TOKEN=… \
//     ./gradlew :app:testDebugUnitTest --tests "*LiveAgentTest*" --rerun
//
// (`--rerun` because Gradle does not treat environment variables as task inputs, so a second run
// with different settings is UP-TO-DATE and reports success without running anything.)
//
// **This bills a real agent** — `SAI_AGENT_SANDBOX` was reverted and no longer exists — so it is a
// named subset, run deliberately before a release or after a cloud-api change to the event stream.
// Never on a branch build.
//
// The assertions are INVARIANTS, not sequences. Real latency is whatever the agent does, so pinning
// an event order here would fail on a slow machine and teach everyone to ignore it. Sequence pinning
// belongs in the scripted tier, where it is deterministic. What this asserts is what must hold
// however slow the day is: the task was really created, events really came back, the FSM understood
// them, and nothing was reported done that never completed.
//
// A failure here is about cloud-api's event stream, not about the FSM — and a cold machine, an
// expired token or a quota is not a failure at all. Read the reason before reading a regression.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenNudged
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ScriptedBrain.Companion.whenSaid
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveAgentTest {

  private fun requireOptIn(): LiveAgentConfig {
    assumeTrue(
        "set SAI_LIVE_AGENT=1 to run the live-agent contract check (it wakes a VM and bills a real agent)",
        System.getenv("SAI_LIVE_AGENT") == "1")
    val config = LiveAgentConfig.fromEnv()
    // Deliberately a hard failure once the tier is switched ON: having opted in, a missing token is
    // a mistake to correct, not a check to skip past quietly.
    return config.getOrElse { throw IllegalStateException(it.message, it) }
  }

  /**
   * One real task, end to end.
   *
   * The scripted BRAIN is intentional — this tier is about the wire, and putting a live model in
   * front of it would mean a red could be either side. One variable at a time.
   */
  @Test
  fun `a real task reaches the agent and its events come back in a shape the FSM understands`() {
    val config = requireOptIn()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val task = "say hello and stop — this is an automated contract check"

    val brain =
        ScriptedBrain.of(
            whenSaid("contract check") { _, _ ->
              BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to task)))
            },
            whenNudged("[agent]") { input, _ -> BrainTurn("done — $input") },
        )

    runBlocking {
      val h = ConversationHarness(brain)
      val live =
          LiveAgent(config, scope, deliver = { h.deliverAgentEvent(it) }, log = { println("    $it") })
      h.useTransport(live)
      h.start()
      h.user(task)

      // Real time, generously: a cold machine can take a while to wake, and a timeout here should
      // read as "the agent never answered", not as a behaviour finding.
      val finished =
          withTimeoutOrNull(TURN_TIMEOUT_MS) {
            live.awaitTurn()
            true
          }

      println("\n--- live run ---")
      println("started:  ${live.started}")
      println("received: ${live.received.map { it::class.simpleName }}")
      println("errors:   ${live.errors}")
      println("heard:    ${h.heard()}")

      // Refusals first, and named. The FSM catches a failed forward and apologises to the user,
      // which is right on a device and useless here: reported as "the task never reached the agent",
      // an expired token sends the operator hunting a contract change. Nearly every red on this tier
      // is a credential or a cold machine.
      assertTrue(
          "the request was refused before it ever reached the agent: ${live.errors}. A 401 is an " +
              "expired SAI_ID_TOKEN; a 403 is a machine that is not this user's. Neither is a " +
              "contract change.",
          live.errors.isEmpty())
      assertTrue(
          "the agent never finished the turn within ${TURN_TIMEOUT_MS / 1000}s — a cold machine or " +
              "an unreachable server, not a behaviour finding",
          finished == true)
      assertTrue("the task never reached the agent", live.started.contains(task))
      assertTrue(
          "no events came back — the stream opened and delivered nothing, which is the shape a " +
              "changed SSE envelope takes",
          live.received.isNotEmpty())
      // The mapper turned the wire into something the FSM acts on. An unknown envelope maps to
      // nothing at all, so a stream of events that produced no terminal state is the drift signal.
      assertTrue(
          "nothing terminal came back (${live.received.map { it::class.simpleName }}) — the turn " +
              "ended without a complete or an error the mapper recognised",
          live.received.any { it is AgentEvent.Complete || it is AgentEvent.Error })
    }
  }

  private companion object {
    const val TURN_TIMEOUT_MS = 240_000L
  }
}
