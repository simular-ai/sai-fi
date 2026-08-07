/*
 * sai-fi — voice concierge.
 */

// The first coverage the hang-up decision has ever had. Its failure mode is cutting the user off
// mid-sentence with another human, and until this file the only way to exercise it was to have a
// real conversation on real glasses and hope.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HangupPolicyTest {

  private fun decide(
      spokeThisTurn: Boolean = false,
      lastUserSpeechAt: Long = 0L,
      lastSaiSpeechAt: Long = 0L,
      lastSaiText: String = "",
      muted: Boolean = false,
      guardUsed: Boolean = false,
  ) =
      HangupPolicy.decide(
          spokeThisTurn, lastUserSpeechAt, lastSaiSpeechAt, lastSaiText, muted, guardUsed)

  // ── the call ends ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `she spoke this turn and the user has talked — end after the goodbye lands`() {
    assertEquals(
        HangupAction.EndAfterGoodbye,
        decide(spokeThisTurn = true, lastUserSpeechAt = 5_000L),
    )
  }

  @Test
  fun `a goodbye from an earlier turn still counts, if it came after the user last spoke`() {
    assertEquals(
        HangupAction.EndAfterGoodbye,
        decide(lastUserSpeechAt = 5_000L, lastSaiSpeechAt = 6_000L, lastSaiText = "bye then"),
    )
  }

  @Test
  fun `muted, there is no goodbye to hear — end immediately, no window`() {
    assertEquals(
        HangupAction.EndNow,
        decide(spokeThisTurn = true, lastUserSpeechAt = 5_000L, muted = true),
    )
  }

  // ── the call is held ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `the user has never spoken — a farewell it heard was not aimed at it`() {
    val action = decide(spokeThisTurn = true, lastUserSpeechAt = 0L)
    assertTrue(action is HangupAction.HoldAndAsk)
    assertEquals("the user hasn't said anything this call", (action as HangupAction.HoldAndAsk).why)
  }

  @Test
  fun `she has not spoken since the user's last turn — no goodbye was answered`() {
    val action = decide(lastUserSpeechAt = 9_000L, lastSaiSpeechAt = 4_000L, lastSaiText = "sure")
    assertTrue(action is HangupAction.HoldAndAsk)
    assertEquals(
        "she hasn't spoken since the user's last turn — no goodbye",
        (action as HangupAction.HoldAndAsk).why,
    )
  }

  @Test
  fun `a later timestamp with empty text is a turn that produced nothing, not a sign-off`() {
    // The regression this guards: timing alone would read an empty turn as a farewell.
    assertTrue(
        decide(lastUserSpeechAt = 5_000L, lastSaiSpeechAt = 6_000L, lastSaiText = "  ")
            is HangupAction.HoldAndAsk,
    )
  }

  @Test
  fun `muted and unconfirmed — hold, but do not ask, because asking cannot be heard`() {
    val action = decide(lastUserSpeechAt = 0L, muted = true)
    assertTrue(action is HangupAction.HoldSilently)
    assertEquals("the user hasn't said anything this call", (action as HangupAction.HoldSilently).why)
  }

  // ── the guard fires once ──────────────────────────────────────────────────────────────────────

  @Test
  fun `the second endCall goes through — saying hang up twice means it`() {
    assertEquals(HangupAction.EndAfterGoodbye, decide(lastUserSpeechAt = 0L, guardUsed = true))
  }

  @Test
  fun `the second endCall while muted ends now rather than opening a window`() {
    assertEquals(HangupAction.EndNow, decide(lastUserSpeechAt = 0L, muted = true, guardUsed = true))
  }

  // ── the nudge wording ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `the held nudge says the call is still open and asks rather than assumes`() {
    val nudge = (decide(lastUserSpeechAt = 0L) as HangupAction.HoldAndAsk).nudge
    assertTrue(nudge.startsWith("[system]"))
    assertTrue(nudge.contains("STILL OPEN"))
    assertTrue(nudge.contains("did you want me to hang up?"))
    // It must not say goodbye again — a second sign-off reads as a second hang-up attempt.
    assertTrue(nudge.contains("Do not say goodbye"))
  }

  @Test
  fun `the cancelled nudge tells her not to sign off twice`() {
    assertTrue(HangupPolicy.CANCELLED_NUDGE.startsWith("[system]"))
    assertTrue(HangupPolicy.CANCELLED_NUDGE.contains("STILL OPEN"))
    assertTrue(HangupPolicy.CANCELLED_NUDGE.contains("Do not say goodbye again"))
    // The overheard-farewell case is the whole reason the window is cancellable.
    assertTrue(HangupPolicy.CANCELLED_NUDGE.contains("aimed at someone else"))
  }

  // ── cancelling a hangup in flight ─────────────────────────────────────────────────────────────

  @Test
  fun `no hangup pending — nothing to cancel`() {
    assertEquals(false, HangupPolicy.shouldCancel(openedAt = 0L, now = 10_000L, stragglerGuardMs = 600L))
  }

  @Test
  fun `speech inside the straggler guard is the goodbye's own transcription, not a barge-in`() {
    // Cancelling on this would make a genuine "hang up" impossible: her farewell reopens the call.
    assertEquals(
        false, HangupPolicy.shouldCancel(openedAt = 10_000L, now = 10_300L, stragglerGuardMs = 600L))
  }

  @Test
  fun `speech after the guard means the user was not done`() {
    assertEquals(
        true, HangupPolicy.shouldCancel(openedAt = 10_000L, now = 10_700L, stragglerGuardMs = 600L))
  }

  @Test
  fun `the guard boundary itself cancels`() {
    assertEquals(
        true, HangupPolicy.shouldCancel(openedAt = 10_000L, now = 10_600L, stragglerGuardMs = 600L))
  }
}
