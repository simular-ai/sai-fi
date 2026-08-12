/* sai-fi — voice concierge. */

// The golden catalog runner.
//
// One JUnit case per scenario, named exactly as the server's catalog names it, so the two can be
// reconciled mechanically — see docs/plans/golden-catalog-inventory.md in the server repo. A
// scenario that quietly fails to be ported would otherwise be invisible: the suite would simply be
// green with less in it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

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

    val concierge = Concierge(agent, voice, engine, timer, onSessionState = { published += it })
    val ctx = GoldenCtx(agent, voice, concierge, published, timer)
    concierge.onApprovalTimeoutFired = { runBlocking { concierge.onApprovalTimeoutWarning() } }

    for (step in scenario.steps) {
      when (step) {
        is Step.User -> concierge.handleUserUtterance(step.utterance)
        is Step.Agent -> concierge.handleAgentEvent(step.event)
        is Step.Effects -> concierge.applyClientEffects(step.raw)
        is Step.AdvanceMs -> timer.advance(step.ms)
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
    // Raise this as scenarios land; it must reach 62 before the TypeScript catalog is deleted.
    // Reconcile with docs/plans/golden-catalog-inventory.md.
    assertEquals(
        "ported scenario count — see golden-catalog-inventory.md",
        PORTED_SCENARIO_COUNT,
        GOLDEN_SCENARIOS.size)
  }
}
