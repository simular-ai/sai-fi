package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-port parity tests for the ActivityLog (see docs/SAI_GLASSES_APP.md §8.1). Replays the SAME
 * scripted event sequences on the SAME fixed clock as the TS generator (`contract/fixtures.ts`) and
 * asserts `statusText()` / `msSinceTaskStart()` match the fixtures byte-for-byte. Drift in the timing
 * math or the activity-line rendering breaks a test.
 *
 * Regenerate the fixtures with: `npm run -w cloud-api concierge:fixtures`.
 */
class ActivityLogParityTest {
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
  fun activityLog_statusAndTiming_matchTsFixtures() {
    val fixtures = loadFixtures("activity-log-status.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val name = f.getString("name")
      val input = f.getJSONObject("input")
      val expected = f.getJSONObject("expected")

      var clock = 0L
      val log =
          if (input.isNull("maxLines")) ActivityLog(now = { clock })
          else ActivityLog(maxLines = input.getInt("maxLines"), now = { clock })

      val timeline = input.getJSONArray("timeline")
      for (j in 0 until timeline.length()) {
        val step = timeline.getJSONObject(j)
        clock = step.getLong("at")
        log.record(step.getJSONObject("event"))
      }
      clock = input.getLong("readAt")

      assertEquals("statusText drift for '$name'", expected.getString("statusText"), log.statusText())

      val actualMs = log.msSinceTaskStart()
      if (expected.isNull("msSinceTaskStart")) {
        assertNull("msSinceTaskStart drift for '$name'", actualMs)
      } else {
        assertEquals(
            "msSinceTaskStart drift for '$name'",
            expected.getLong("msSinceTaskStart"),
            actualMs,
        )
      }
    }
  }
}
