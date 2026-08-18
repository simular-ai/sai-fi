package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the ActivityLog port (feeds getSaiStatus + the ask-first gate). Uses the injectable
 * clock so elapsed/steps and msSinceTaskStart are deterministic — the honesty rules ("elapsed only, no
 * ETA") and the wait-timing gate depend on this being right.
 */
class ActivityLogTest {
  private var clock = 0L

  private fun log() = ActivityLog(now = { clock })

  private fun status(s: String) = JSONObject().put("type", "status").put("status", s)

  private fun progress(text: String) = JSONObject().put("type", "progress").put("text", text)

  @Test
  fun msSinceTaskStart_nullBeforeAnyTask() {
    assertNull(log().msSinceTaskStart())
    assertTrue(log().statusText().contains("No activity reported yet"))
  }

  /**
   * statusText() feeds getSaiStatus, i.e. the model. It reports STEPS and running/finished, and
   * deliberately no longer reports elapsed time: a duration in the model's context is a duration it
   * reads aloud ("I've been working about 77 seconds"), which is both robotic and — since the
   * concierge can't actually predict anything from it — fabricated-sounding. msSinceTaskStart() is
   * unaffected: the ask-first gate still needs the real number, it just isn't spoken.
   */
  @Test
  fun stepsAndRunState_whileRunning_thenFinished() {
    val a = log()
    clock = 1_000
    a.record(status("processing"))
    a.record(progress("opening browser"))
    clock = 4_000
    assertEquals(3_000L, a.msSinceTaskStart())
    val running = a.statusText()
    assertTrue(running.contains("Still working"))
    assertTrue(running.contains("1 step"))
    assertFalse("statusText must not hand the model a duration", running.contains("3s"))
    clock = 6_000
    a.record(status("idle")) // ends the task
    assertEquals(5_000L, a.msSinceTaskStart()) // endedAt(6000) − startedAt(1000)
    val finished = a.statusText()
    assertTrue(finished.contains("Finished after"))
    assertFalse("statusText must not hand the model a duration", finished.contains("5s"))
  }

  @Test
  fun complete_endsTheTask() {
    val a = log()
    clock = 0
    a.record(status("processing"))
    clock = 2_000
    a.record(JSONObject().put("type", "complete").put("summary", "done"))
    assertEquals(2_000L, a.msSinceTaskStart()) // still tracked for the ask-first gate
    assertTrue(a.statusText().contains("Finished after"))
  }

  @Test
  fun reset_clears() {
    val a = log()
    clock = 100
    a.record(status("processing"))
    a.reset()
    assertNull(a.msSinceTaskStart())
    assertTrue(a.statusText().contains("No activity reported yet"))
  }
}
