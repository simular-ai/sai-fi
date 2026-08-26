/* sai-fi — voice concierge. */

// The golden catalog runner.
//
// One JUnit case per scenario. A scenario that quietly fails to be added would otherwise be
// invisible: the suite would simply be green with less in it. PORTED_SCENARIO_COUNT pins the size.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ActivityLog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FsmGoldenTest(private val scenario: Scenario) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun scenarios(): List<Scenario> = GOLDEN_SCENARIOS
  }

  @Test
  fun run() = runBlocking {
    val agent = FakeAgent()
    val voice = FakeChannel()
    val engine = FakeEngine(goldenBrain)
    val timer = VirtualTimer()
    val published = mutableListOf<AgentEvent.SessionState>()
    val activityLog = ActivityLog(now = { timer.now })

    // The FSM's clock IS the virtual timer's, so an absolute `expiresAt` and the delay computed
    // from it agree. Wired to real time, an advanceMs step would never reach the deadline.
    val concierge =
        Concierge(
            agent,
            voice,
            engine,
            timer,
            onSessionState = {
              published += it
              activityLog.record(sessionStateJson(it))
            },
            now = { timer.now })
    val ctx = GoldenCtx(agent, voice, concierge, published, timer, activityLog)
    concierge.onApprovalTimeoutFired = { runBlocking { concierge.onApprovalTimeoutWarning() } }

    for (step in scenario.steps) {
      when (step) {
        is Step.User -> concierge.handleUserUtterance(step.utterance)
        is Step.Agent -> {
          // The device feeds its ActivityLog from the same agent events, so status assertions see
          // what the user could actually be told.
          activityLog.record(agentEventJson(step.event))
          concierge.handleAgentEvent(step.event)
        }
        is Step.Effects -> concierge.applyClientEffects(step.raw)
        is Step.AdvanceMs -> timer.advance(step.ms)
        is Step.AddPhoto -> agent.addPendingAttachment(photo(step.name))
        is Step.FailNextForward -> agent.failForwardTask()
        is Step.Do -> step.block(ctx)
      }
    }

    scenario.assert(ctx)
  }
}

/** The catalog is unique by name, and the count is pinned — same guard the server's suite has. */
class GoldenCatalogTest {
  @Test
  fun `every scenario has a unique name`() {
    val names = GOLDEN_SCENARIOS.map { it.name }
    assertEquals("duplicate scenario names", names.size, names.toSet().size)
  }

  @Test
  fun `the catalog is the size it is meant to be`() {
    // Raise this as scenarios land. A catalog that silently shrinks is a suite that goes green
    // with less in it.
    assertEquals(
        "ported scenario count",
        PORTED_SCENARIO_COUNT,
        GOLDEN_SCENARIOS.size)
  }
}
