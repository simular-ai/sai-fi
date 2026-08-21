package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_AWAKE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKE_FAILED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake announcement rules, pinned away from the Android service that applies them.
 *
 * Each of these is a way of saying something untrue about a computer the wearer cannot see, so the
 * rules are worth a test each rather than a comment each.
 */
class WakePolicyTest {

  private fun speak(d: WakeAnnouncement): WakeAnnouncement.Speak {
    assertTrue("expected a spoken line, got $d", d is WakeAnnouncement.Speak)
    return d as WakeAnnouncement.Speak
  }

  private fun silent(d: WakeAnnouncement): WakeAnnouncement.Silent {
    assertTrue("expected silence, got $d", d is WakeAnnouncement.Silent)
    return d as WakeAnnouncement.Silent
  }

  @Test
  fun announcesAWakeItDispatched() {
    val d =
        WakePolicy.onWakeRequested(
            startingUp = true,
            muted = false,
            audible = true,
            status = "hibernated",
            canWake = true,
            dispatched = true,
        )
    assertEquals(MACHINE_WAKING, speak(d).line)
    assertTrue("a wake it announced is a wake it must watch", speak(d).watch)
  }

  @Test
  fun announcesAMachineAlreadyMidWake_whichDispatchedNothing() {
    // The reason to branch on `startingUp` and not `waking`: the server correctly declines to
    // dispatch a second wake for a machine already coming up, and the user is still owed the minute.
    // Reading `waking` alone leaves the wake silent — which is the bug this whole path exists to fix,
    // reintroduced one field over.
    val d =
        WakePolicy.onWakeRequested(
            startingUp = true,
            muted = false,
            audible = true,
            status = "wakingup",
            canWake = true,
            dispatched = false,
        )
    assertEquals(MACHINE_WAKING, speak(d).line)
  }

  @Test
  fun saysNothingAboutAMachineThatCannotBeWoken() {
    // The honesty case. Asleep, no wake path, so MACHINE_WAKING's "about a minute" would be a
    // promise nothing can keep — and the wearer has no screen to correct it with.
    val d =
        WakePolicy.onWakeRequested(
            startingUp = false,
            muted = false,
            audible = true,
            status = "hibernated",
            canWake = false,
            dispatched = false,
        )
    assertTrue(silent(d).why.contains("canWake=false"))
  }

  @Test
  fun saysNothingAboutAMachineThatWasAlreadyAwake() {
    val d =
        WakePolicy.onWakeRequested(
            startingUp = false,
            muted = false,
            audible = true,
            status = "active",
            canWake = true,
            dispatched = false,
        )
    silent(d)
  }

  @Test
  fun dropsTheOpeningLineWhileMuted() {
    // Dropped, not held — the same call AgentEventRouter makes for a `notice`. "Waking up, about a
    // minute" is true for about a minute; replayed on unmute it describes a world that has moved on.
    val d =
        WakePolicy.onWakeRequested(
            startingUp = true,
            muted = true,
            audible = true,
            status = "hibernated",
            canWake = true,
            dispatched = true,
        )
    assertTrue(silent(d).why.contains("muted"))
  }

  @Test
  fun saysNothingWithNoLiveSessionToSayItThrough() {
    val d =
        WakePolicy.onWakeRequested(
            startingUp = true,
            muted = false,
            audible = false,
            status = "hibernated",
            canWake = true,
            dispatched = true,
        )
    silent(d)
  }

  @Test
  fun reportsTheMachineComingUp() {
    val d = WakePolicy.onWatchEnded(active = true, muted = false, audible = true)
    assertEquals(MACHINE_AWAKE, speak(d).line)
    assertTrue("the watch is over either way", !speak(d).watch)
  }

  @Test
  fun reportsAWakeThatNeverLanded() {
    // `waking = true` only ever meant DISPATCHED — vm-service is fire-and-forget and nothing polls
    // it — so this is the client's own timeout, and the one honest thing left to say.
    val d = WakePolicy.onWatchEnded(active = false, muted = false, audible = true)
    assertEquals(MACHINE_WAKE_FAILED, speak(d).line)
  }

  @Test
  fun dropsTheOutcomeWhileMuted_evenTheFailure() {
    // Tempting to let this one through: it is the only actionable line here. But the user chose
    // silence, and three minutes on "it didn't come back" is as stale as the line that opened the wait
    // — the machine may well be up by now.
    assertTrue(
        silent(WakePolicy.onWatchEnded(active = false, muted = true, audible = true))
            .why
            .contains("muted"))
    silent(WakePolicy.onWatchEnded(active = true, muted = true, audible = true))
  }
}
