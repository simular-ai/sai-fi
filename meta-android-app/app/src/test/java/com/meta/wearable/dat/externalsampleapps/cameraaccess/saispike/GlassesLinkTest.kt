/*
 * sai-fi — voice concierge.
 */

// When silence from DAT counts as "no glasses", and when it does not. These cases are the regression
// guard for a bug that has now shipped twice in different forms: the camera grant greyed out on a
// fresh install, because "DAT says nothing is connected" and "DAT has not spoken yet" were the same
// value.
//
// Two layers: the step-method cases pin the rule in isolation; the `observe` cases pin the
// coroutine shape that actually runs in the Activity — a StateFlow seeded with "nothing", which
// emits synchronously on subscribe. Without those, the step methods can stay green while the
// collector maps the seed straight to `false` and the dead button is back.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesLinkTest {

  @Test
  fun `nothing reported yet is unknown, not disconnected`() {
    // The case that matters. Subscribing to Wearables.devices hands us a seeded empty set
    // synchronously — before ACDC has had any chance to report a device — so this first
    // nothing-reported must NOT resolve to false. It is what keeps the grant button alive on the run
    // that needs it.
    val link = GlassesLink()
    assertNull(link.onNothingReported())
    assertFalse("nothing has been established yet", link.hasAnswered)
  }

  @Test
  fun `silence becomes an affirmative no once the settle window passes`() {
    val link = GlassesLink()
    assertNull(link.onNothingReported())
    assertFalse(link.onSettleElapsed())
    assertTrue(link.hasAnswered)
  }

  @Test
  fun `a readable device is an answer immediately, in either state`() {
    // No settle wait when DAT actually speaks: it has told us, so we report it.
    assertTrue(GlassesLink().onLinkState(true))
    assertFalse(GlassesLink().onLinkState(false))
    assertTrue(GlassesLink().apply { onLinkState(false) }.hasAnswered)
  }

  @Test
  fun `once DAT has spoken, a device going away is reported at once`() {
    // No second settle window: an empty set from a DAT that has been talking to us is a real
    // "the glasses went away", not silence. Making the user wait 5s to be told the obvious — and
    // showing "checking…" over glasses they just folded — would be the opposite error.
    val link = GlassesLink()
    assertTrue(link.onLinkState(true))
    assertFalse("DAT has answered before, so absence is now meaningful", link.onNothingReported()!!)
  }

  @Test
  fun `a concluded absence stays concluded`() {
    // After the window has been waited out once, we do not fall back to "checking…" every time the
    // empty set is re-reported — that would flicker the link line and re-enable the grant button
    // against a DAT that has already answered.
    val link = GlassesLink()
    assertNull(link.onNothingReported())
    assertFalse(link.onSettleElapsed())
    assertFalse(link.onNothingReported()!!)
  }

  @Test
  fun `the link can recover after being concluded absent`() {
    val link = GlassesLink()
    link.onNothingReported()
    link.onSettleElapsed()
    assertTrue("glasses powered on later", link.onLinkState(true))
    assertFalse("and folded again", link.onNothingReported()!!)
  }

  @Test
  fun `the settle window is generous, because the two errors are not symmetric`() {
    // Concluding "no glasses" early greys out the grant on powered-on glasses (the original bug);
    // concluding late only shows "checking…" a beat longer, with a button that re-probes for real.
    // Pinned so a future tightening has to argue with this comment first.
    assertEquals(5_000L, GlassesLink.SETTLE_MS)
    assertTrue(GlassesLink.SETTLE_MS >= 3_000L)
  }

  // ── observe (the coroutine shape the Activity actually runs) ──────────────────────────────────

  @Test
  fun `a seeded-empty StateFlow stays null until the settle window, not false on subscribe`() =
      runTest {
        // THE bug. Wearables.devices is MutableStateFlow(emptySet()), so collect gets "nothing"
        // synchronously. Mapping that to false is what greys the grant on a fresh install — and it is
        // exactly what a collector that ignores GlassesLink (or calls onSettleElapsed immediately)
        // would do. Virtual time lets us assert the null holds for the whole window without sleeping.
        val readings = MutableStateFlow<Boolean?>(null)
        val published = mutableListOf<Boolean?>()
        backgroundScope.launch { GlassesLink().observe(readings, published::add) }

        runCurrent()
        assertEquals(
            "seeded empty must publish null, not false — otherwise the tri-state never survives",
            listOf(null),
            published)

        advanceTimeBy(GlassesLink.SETTLE_MS - 1)
        runCurrent()
        assertEquals("still unknown one tick before settle", listOf(null), published)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("only now is absence an answer", listOf(null, false), published)
      }

  @Test
  fun `a device mid-settle cancels the wait and does not get overwritten by a late false`() =
      runTest {
        // collectLatest is load-bearing. Without it, a device that appears at t=1s would publish
        // true, then the original settle delay would still fire at t=5s and stomp connected →
        // disconnected. The grant greys itself out on glasses that just finished linking.
        val readings = MutableStateFlow<Boolean?>(null)
        val published = mutableListOf<Boolean?>()
        backgroundScope.launch { GlassesLink().observe(readings, published::add) }

        runCurrent()
        assertEquals(listOf(null), published)

        readings.value = true
        runCurrent()
        assertEquals(listOf(null, true), published)

        advanceTimeBy(GlassesLink.SETTLE_MS * 2)
        runCurrent()
        assertEquals(
            "settle was cancelled — a late false must not overwrite connected",
            listOf(null, true),
            published)
      }

  @Test
  fun `once answered, an empty reading publishes false with no second settle wait`() = runTest {
    val readings = MutableStateFlow<Boolean?>(true)
    val published = mutableListOf<Boolean?>()
    backgroundScope.launch { GlassesLink().observe(readings, published::add) }

    runCurrent()
    assertEquals(listOf(true), published)

    readings.value = null
    runCurrent()
    assertEquals(listOf(true, false), published)

    advanceTimeBy(GlassesLink.SETTLE_MS * 2)
    runCurrent()
    assertEquals(
        "already answered — no settle delay, no extra publish", listOf(true, false), published)
  }
}
