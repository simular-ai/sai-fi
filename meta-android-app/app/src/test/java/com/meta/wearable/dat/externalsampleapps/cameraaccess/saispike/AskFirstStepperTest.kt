package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ask-first stepper's arithmetic.
 *
 * Worth pinning because the field next to the stepper accepts typed values, so the stepper has to cope
 * with a `current` that is not on its own grid — and "off by one step" in either direction is invisible
 * until someone taps + and watches the number go somewhere they didn't ask for.
 */
class AskFirstStepperTest {

  @Test
  fun `stepping up from the grid moves one full step`() {
    assertEquals(20, steppedAskFirstSec(15, up = true))
    assertEquals(5, steppedAskFirstSec(0, up = true))
  }

  @Test
  fun `stepping down from the grid moves one full step`() {
    assertEquals(10, steppedAskFirstSec(15, up = false))
    assertEquals(0, steppedAskFirstSec(5, up = false))
  }

  @Test
  fun `stepping off the grid snaps to the next round number, not current plus step`() {
    // A typed 17: + means 20, and - means 15. Not 22 and not 12.
    assertEquals(20, steppedAskFirstSec(17, up = true))
    assertEquals(15, steppedAskFirstSec(17, up = false))
  }

  @Test
  fun `repeated taps converge on the grid and stay there`() {
    var v = 17
    repeat(3) { v = steppedAskFirstSec(v, up = true) }
    assertEquals(30, v) // 17 → 20 → 25 → 30
    repeat(3) { v = steppedAskFirstSec(v, up = false) }
    assertEquals(15, v) // 30 → 25 → 20 → 15
  }

  @Test
  fun `zero is reachable and is the floor, because it means ask about everything`() {
    assertEquals(0, steppedAskFirstSec(ASK_FIRST_MIN_SEC, up = false))
    assertEquals(0, steppedAskFirstSec(3, up = false))
  }

  @Test
  fun `the ceiling holds against a typed value past it`() {
    assertEquals(ASK_FIRST_MAX_SEC, steppedAskFirstSec(ASK_FIRST_MAX_SEC, up = true))
    assertEquals(ASK_FIRST_MAX_SEC, steppedAskFirstSec(9_999, up = true))
    // Down from past the ceiling still clamps, rather than stepping 9999 → 9995.
    assertEquals(ASK_FIRST_MAX_SEC, steppedAskFirstSec(9_999, up = false))
  }
}
