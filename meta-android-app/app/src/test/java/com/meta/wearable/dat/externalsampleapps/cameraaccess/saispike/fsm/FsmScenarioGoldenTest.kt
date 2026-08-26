/* sai-fi — voice concierge. */

// The guard on `fsm-scenarios.json`. This file only ever READS.
//
// `FsmGoldenTest` runs the catalog and checks each scenario's own assertions. This checks something
// different and narrower: that the COMMITTED fixture still describes what the catalog actually does.
// Without it the fixture would go stale the moment a scenario changed, and the iOS port would then be
// held to a spec Android no longer meets — passing its gate while diverging.
//
// Regenerate with SAI_REGEN_GOLDENS=1 (see RegenerateGoldensTest) and read the diff. A trace diff is a
// behaviour change; treat it like one.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.renderFile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsmScenarioGoldenTest {

  private fun loadText(): String =
      checkNotNull(javaClass.getResourceAsStream("/parity/fsm-scenarios.json")) {
            "missing golden /parity/fsm-scenarios.json — rewrite the goldens with " +
                "SAI_REGEN_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun --tests " +
                "\"*RegenerateGoldensTest*\""
          }
          .bufferedReader()
          .use { it.readText() }

  @Test
  fun theWholeFile_stillMatchesByteForByte() {
    // The catalog IS the generator's input, so a fresh render and the committed file must agree
    // exactly. This is the check that catches a scenario added, removed, renamed or behaviourally
    // changed without a regeneration.
    assertEquals(loadText(), renderFile(fsmScenarios()))
  }

  @Test
  fun everyScenario_isPresentAndNamed() {
    val fixtures = JSONArray(loadText())
    assertEquals("the fixture must carry the whole catalog", PORTED_SCENARIO_COUNT, fixtures.length())

    val names = (0 until fixtures.length()).map { fixtures.getJSONObject(it).getString("name") }
    assertEquals("names must be in catalog order", GOLDEN_SCENARIOS.map { it.name }, names)
    assertEquals("duplicate scenario names", names.size, names.toSet().size)
  }

  @Test
  fun everyScenario_carriesStepsAndATrace() {
    // A scenario serialised with no steps, or with an empty trace, would pass a byte comparison
    // against an equally empty fixture and assert nothing on the other port.
    val fixtures = JSONArray(loadText())
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val name = f.getString("name")
      assertTrue("$name has no steps", f.getJSONArray("steps").length() > 0)
      val trace = f.getJSONObject("trace")
      assertTrue("$name has no final state", trace.has("state"))
      assertTrue("$name has no status", trace.getString("status").isNotEmpty())
    }
  }

  @Test
  fun noScenario_serialisesAnOpaqueStep() {
    // `Step.Do` is unserialisable by design and the generator throws on one. This asserts the
    // catalog has none left, so the iOS gate sees all 63 rather than however many happened to be
    // expressible.
    val fixtures = JSONArray(loadText())
    val known = setOf("user", "agent", "effects", "advanceMs", "addPhoto", "failNextForward")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val steps = f.getJSONArray("steps")
      for (j in 0 until steps.length()) {
        val kind = steps.getJSONObject(j).getString("kind")
        assertTrue("${f.getString("name")} step $j has unknown kind '$kind'", kind in known)
      }
    }
  }
}
