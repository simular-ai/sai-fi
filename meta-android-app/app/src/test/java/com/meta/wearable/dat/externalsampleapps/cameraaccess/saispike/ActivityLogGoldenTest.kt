/* sai-fi — voice concierge. */

// The golden test for `ActivityLog`. This file only ever READS.
//
// Replays the scripted event sequences from `GoldenFixtures.kt` on the same fixed clock and asserts
// `statusText()` / `msSinceTaskStart()` still match the committed JSON. Drift in the timing math or
// the activity-line rendering breaks a test rather than a call.
//
// `ActivityLogTest` covers the class's own behaviour on hand-written sequences. What is pinned HERE
// is the exact wording `getSaiStatus` answers with, which is the one thing about a task's state the
// user can hear — and the scenarios are a catalogue of the ways it has been wrong before: a
// cancellation reported as a new task, a question answered in the desktop app still reported as
// blocking, a drained queue still announced as waiting.
//
// Rewrite the goldens with `SAI_REGEN_GOLDENS=1 ... --tests "*RegenerateGoldensTest*"`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogGoldenTest {
  private fun loadFixtures(name: String): JSONArray {
    val text =
        checkNotNull(javaClass.getResourceAsStream("/parity/$name")) {
              "missing golden /parity/$name — rewrite the goldens with SAI_REGEN_GOLDENS=1 " +
                  "./gradlew :app:testDebugUnitTest --tests \"*RegenerateGoldensTest*\""
            }
            .bufferedReader()
            .use { it.readText() }
    return JSONArray(text)
  }

  @Test
  fun activityLog_statusAndTiming_matchGoldens() {
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
            "msSinceTaskStart drift for '$name'", expected.getLong("msSinceTaskStart"), actualMs)
      }
    }
  }

  /**
   * The two readings come apart on purpose: what the user HEARS carries no elapsed time, because a
   * spoken "~3s" is both useless and wrong by the time it is said, while the gate that decides
   * whether to interrupt still needs the number.
   */
  @Test
  fun statusText_dropsElapsedTime_butTheGateStillTracksIt() {
    val fixtures = loadFixtures("activity-log-status.json")
    var seen: JSONObject? = null
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      if (f.getString("name") == "running: processing + progress") seen = f.getJSONObject("expected")
    }
    val expected = checkNotNull(seen) { "the 'running: processing + progress' scenario is gone" }

    val statusText = expected.getString("statusText")
    assertTrue(statusText.contains("Still working"))
    assertFalse(
        "elapsed time is dropped from statusText", Regex("~\\d+s").containsMatchIn(statusText))
    assertEquals(3_000L, expected.getLong("msSinceTaskStart"))
  }

  /**
   * A finished result has to still be here after the next task has filled the buffer, and it has to
   * be attributed to the task that produced it.
   *
   * Both halves are load-bearing and they pull against each other. Missing, the result is gone and
   * `getSaiStatus` answers a question about a finished task with the progress of a different one —
   * which is how a held result became "the task stopped" on 2026-08-20. Unattributed, it is worse
   * than missing: the summary of the email check gets read back as the outcome of the booking that
   * started after it.
   */
  @Test
  fun aFinishedResultOutlivesTheBuffer_andSaysWhichTaskItBelongsTo() {
    val fixtures = loadFixtures("activity-log-status.json")
    var seen: JSONObject? = null
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      if (f.getString("name") == "a finished result outlives the buffer the next task fills") {
        seen = f.getJSONObject("expected")
      }
    }
    val statusText =
        checkNotNull(seen) { "the buffer-overflow scenario is gone" }.getString("statusText")

    assertTrue("the result itself", statusText.contains("3 unread, all newsletters"))
    assertTrue("named as an earlier task's", statusText.contains("AN EARLIER TASK"))
    assertTrue("and the running one is still reported", statusText.contains("Still working"))
    assertFalse(
        "it really has scrolled out of the buffer — otherwise this proves nothing",
        statusText.contains("finished: 3 unread"))
  }
}
