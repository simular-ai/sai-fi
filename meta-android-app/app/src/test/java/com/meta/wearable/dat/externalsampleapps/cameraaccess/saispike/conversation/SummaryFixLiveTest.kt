/* sai-fi — voice concierge. Live-agent check: a text-delta finish is actually reported. */
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

class SummaryFixLiveTest {
  @Test
  fun `a real task that answers in text deltas is actually reported to the user`() {
    assumeTrue(System.getenv("SAI_LIVE_AGENT") == "1")
    val config = LiveAgentConfig.fromEnv().getOrElse { throw IllegalStateException(it.message, it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val task = "reply with exactly the words banana pancakes, do not use any tools"

    val brain =
        ScriptedBrain.of(
            whenSaid("banana") { _, _ -> BrainTurn("on it", callsOf(fc("forwardToAgent", "text" to task))) },
            // Relay whatever the completion nudge carries — which, before the fix, was an
            // instruction to say nothing came back.
            whenNudged("[agent]") { input, _ -> BrainTurn("the agent says: $input") },
        )

    runBlocking {
      val h = ConversationHarness(brain)
      val live = LiveAgent(config, scope, deliver = { h.deliverAgentEvent(it) }, log = { println("    $it") })
      h.useTransport(live)
      h.start()
      h.user(task)
      withTimeoutOrNull(120_000) { live.awaitTurn() }

      val completes = live.received.filterIsInstance<AgentEvent.Complete>()
      println("\n=== events: ${live.received.map { it::class.simpleName }}")
      println("=== complete.summary: ${completes.map { it.summary }}")
      println("=== heard: ${h.heard()}")
      if (live.errors.isNotEmpty()) println("=== errors: ${live.errors}")

      assertTrue("nothing was refused: ${live.errors}", live.errors.isEmpty())
      assertTrue("a completion arrived", completes.isNotEmpty())
      assertTrue(
          "THE FIX: the completion must carry the agent's answer, not an empty summary — got ${completes.map { it.summary }}",
          completes.any { !it.summary.isNullOrBlank() })
      assertTrue(
          "and the concierge must NOT have been told nothing came back: ${h.heard()}",
          !h.heard().contains("without reporting"))
    }
  }
}
