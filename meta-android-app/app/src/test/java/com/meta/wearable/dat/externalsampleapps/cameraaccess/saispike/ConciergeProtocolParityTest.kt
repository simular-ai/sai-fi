package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-port parity tests (see docs/SAI_GLASSES_APP.md §8.1). These consume the JSON fixtures
 * generated FROM the TypeScript source of truth (cloud-api's `voice/core/nudges.ts`, via
 * `contract/fixtures.ts`) and assert the Kotlin port produces byte-identical output. If the Kotlin
 * nudge helpers ever drift from the TS originals, one of these breaks — not a live demo.
 *
 * Regenerate the fixtures with: `npm run -w cloud-api concierge:fixtures`.
 */
class ConciergeProtocolParityTest {
  private fun loadFixtures(name: String): JSONArray {
    val text =
        checkNotNull(javaClass.getResourceAsStream("/parity/$name")) {
              "missing parity fixture /parity/$name — run `npm run -w cloud-api concierge:fixtures`"
            }
            .bufferedReader()
            .use { it.readText() }
    return JSONArray(text)
  }

  @Test
  fun describeAgentEvent_matchesTsFixtures() {
    val fixtures = loadFixtures("agent-event-nudges.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val input = f.getJSONObject("input")
      val expected = f.getString("expected")
      assertEquals("describeAgentEvent drift for '${f.getString("name")}'", expected, describeAgentEvent(input))
    }
  }

  @Test
  fun describeCompleteAskFirst_matchesTsFixtures() {
    val fixtures = loadFixtures("complete-ask-first.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val input = f.getJSONObject("input")
      val expected = f.getString("expected")
      assertEquals(
          "describeCompleteAskFirst drift for '${f.getString("name")}'",
          expected,
          describeCompleteAskFirst(input),
      )
    }
  }

  @Test
  fun renderAgentActivity_matchesTsFixtures() {
    val fixtures = loadFixtures("agent-activity-render.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val input = f.getJSONObject("input")
      val expected = f.getString("expected")
      assertEquals("renderAgentActivity drift for '${f.getString("name")}'", expected, renderAgentActivity(input))
    }
  }

  @Test
  fun nudgeConstants_matchTsConstants() {
    val fixtures = loadFixtures("constants.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      when (f.getString("name")) {
        "APPROVAL_TIMEOUT_NUDGE" -> assertEquals(f.getString("expected"), APPROVAL_TIMEOUT_NUDGE)
        "GREETING_NUDGE" -> assertEquals(f.getString("expected"), GREETING_NUDGE)
        "MUTED_NUDGE" -> assertEquals(f.getString("expected"), MUTED_NUDGE)
        "UNMUTED_NUDGE" -> assertEquals(f.getString("expected"), UNMUTED_NUDGE)
      }
    }
  }
}
