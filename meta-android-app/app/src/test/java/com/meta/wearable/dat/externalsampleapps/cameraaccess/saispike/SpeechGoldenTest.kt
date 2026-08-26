/* sai-fi — voice concierge. */

// The golden test for `fsm/Speech.kt`. This file only ever READS.
//
// Same two checks as `ConciergeProtocolGoldenTest`, for the same two reasons: every committed case
// still equals what the helper produces (so a reworded line names itself in the failure), and the
// whole file still equals what the generator would write (so a case added to `SpeechFixtures.kt` and
// never regenerated is caught too — the per-case loop cannot see a case missing from the JSON).
//
// What is new is WHY this one exists. `Speech.kt` was pinned by nothing until now: the string
// goldens covered `ConciergeProtocol.kt` and `ActivityLog.kt`, and `FsmGoldenTest` asserts effect
// and state traces while deliberately never asserting phrasing. So the lines the concierge speaks
// about its own queue could all be reworded with every test still green. They are load-bearing in
// exactly the same way as the rest — see the `say` vs `instruct` regressions recorded in
// `Speech.kt` itself, both of which a user heard read aloud.
//
// It is also the Android half of a cross-port gate. `meta-ios-app (untested on-device)/SaiFiCore` reimplements this file
// in Swift and replays the same JSON, which is the only thing holding the two equal.
//
// Rewrite with `SAI_REGEN_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun --tests
// "*RegenerateGoldensTest*"` and commit the diff.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.QUEUE_POSITION
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.RESELECT_NUDGE
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGoldenTest {

  private fun loadText(): String =
      checkNotNull(javaClass.getResourceAsStream("/parity/speech.json")) {
            "missing golden /parity/speech.json — rewrite the goldens with SAI_REGEN_GOLDENS=1 " +
                "./gradlew :app:testDebugUnitTest --rerun --tests \"*RegenerateGoldensTest*\""
          }
          .bufferedReader()
          .use { it.readText() }

  private fun fixtures(): JSONArray = JSONArray(loadText())

  private fun expected(name: String): String {
    val all = fixtures()
    for (i in 0 until all.length()) {
      val f = all.getJSONObject(i)
      if (f.getString("name") == name) return f.getString("expected")
    }
    throw AssertionError("no fixture named '$name' in speech.json")
  }

  @Test
  fun everyCase_stillMatchesTheCommittedFixture() {
    // The generator IS the implementation here — it calls the real helpers — so regenerating into a
    // buffer and comparing case by case is what detects a reworded line, with the case name in the
    // failure message.
    val committed = fixtures()
    val fresh = JSONArray(renderFile(speechLines()))
    assertEquals(
        "speech.json has a different number of cases than SpeechFixtures.kt produces — regenerate",
        committed.length(),
        fresh.length())
    for (i in 0 until committed.length()) {
      val was = committed.getJSONObject(i)
      val now = fresh.getJSONObject(i)
      assertEquals("case order changed at index $i", was.getString("name"), now.getString("name"))
      assertEquals(
          "Speech drift for '${was.getString("name")}'",
          was.get("expected").toString(),
          now.get("expected").toString())
    }
  }

  @Test
  fun theWholeFile_stillMatchesByteForByte() {
    // Catches the case the loop above cannot: a case added to SpeechFixtures.kt and never
    // regenerated. Without this, a new case simply would not be compared to anything.
    assertEquals(loadText(), renderFile(speechLines()))
  }

  // ── what the strings SAY, which a byte diff cannot tell you ────────────────

  @Test
  fun everyModelFacingNudge_isAddressedToTheModel_andSaysNothingHappened() {
    // The `say` vs `instruct` axis is the one this file's regressions live on. A model-facing line
    // that loses its "[system]" prefix is a line the client will happily read out verbatim.
    val modelFacing =
        listOf(
            "RESELECT_NUDGE",
            "NOTHING_QUEUED_TO_RUSH_NUDGE",
            "NOTHING_QUEUED_NUDGE",
            "CONFIRM_RESET_NUDGE",
            "nothingRunningNudge nothing waiting",
            "nothingRunningNudge with a queue",
            "cannotDropOneOfManyNudge",
            "whichQueuedToRushNudge",
            "noQueuedMatchNudge",
            "unattributableApprovalNudge with a prompt",
            "unattributableApprovalNudge with no prompt",
            "unattributableApprovalNudge truncates a long prompt",
            "relayIntoBlockedTurnNudge with options",
            "relayIntoBlockedTurnNudge link-only",
            "relayIntoBlockedTurnNudge with no options at all",
            "relayIntoBlockedTurnNudge fences the pending prompt",
        )
    for (name in modelFacing) {
      assertTrue("$name must be addressed to the model", expected(name).startsWith("[system]"))
    }
  }

  @Test
  fun everySpokenLine_isNotAddressedToTheModel() {
    // The other direction: a spoken line that acquires a "[system]" prefix gets that prefix read out.
    val spoken =
        listOf(
            "QUEUED_BEHIND_APPROVAL",
            "COULD_NOT_START_TASK",
            "MACHINE_WAKING",
            "MACHINE_AWAKE",
            "MACHINE_WAKE_FAILED",
            "ROTATED",
            "RESET_RATE_LIMITED",
            "RESET_FAILED",
            "queuedBehindTask short",
            "droppedQueuedLine one",
            "startingNowLine",
            "stoppedRunningLine nothing waiting",
            "interruptScopeQuestion both",
            "cannotResetWhileBusy all three",
        )
    for (name in spoken) {
      assertFalse("$name is spoken verbatim — it must not carry a [system] prefix",
          expected(name).startsWith("[system]"))
      assertFalse("$name must not name a function to the user",
          expected(name).contains("chooseOption") || expected(name).contains("relayToAgent"))
    }
  }

  @Test
  fun theReselectNudge_tellsTheModelNothingWasPicked() {
    // The specific failure: a rejected pick that reads as an accepted one leaves the user believing
    // they answered a request that is still waiting.
    assertTrue(RESELECT_NUDGE.contains("REJECTED"))
    assertTrue(RESELECT_NUDGE.contains("still waiting"))
    assertTrue(RESELECT_NUDGE.contains("Do not tell the user anything was picked"))
  }

  @Test
  fun theQueuePositionSubject_isSharedBySupersedingLines() {
    // Both lines describe where a task sits, so a later one must replace an earlier one rather than
    // being spoken after it. The tag is what makes that happen; the value itself is the contract.
    assertEquals("queue-position", QUEUE_POSITION)
  }

  @Test
  fun theQueuedLines_neverImplyWorkHasStarted() {
    // Completion honesty, at the queue. "On it" about a queued task makes the user wait for a result
    // nothing is producing.
    assertTrue(expected("QUEUED_BEHIND_APPROVAL").contains("still waiting"))
    assertTrue(expected("queuedBehindTask short").contains("as soon as I'm done with"))
    assertTrue(expected("droppedQueuedLine one").contains("hadn't started yet"))
    assertTrue(expected("droppedQueuedLine many").contains("hadn't started yet"))
  }

  @Test
  fun stoppedRunningLine_saysWhatHappensNext_orThatNothingDoes() {
    // The half a user cannot see. "Stopped" alone reads as nothing running, and moments later the
    // machine is busy with something else.
    assertTrue(expected("stoppedRunningLine nothing waiting").contains("Nothing else is waiting"))
    assertTrue(expected("stoppedRunningLine with a queue").contains("Moving on to"))
  }

  @Test
  fun interruptScopeQuestion_namesRunningAndQueuedSeparately() {
    // Reading them as one list describes a queued task as underway, which is the thing the user is
    // being asked to decide about.
    val both = expected("interruptScopeQuestion both")
    assertTrue(both.contains("I'm working on"))
    assertTrue(both.contains("hasn't started yet"))
  }

  @Test
  fun agentDerivedText_staysFenced() {
    // Prompt-injection fencing. The payload is INJECTION, and it must arrive as data.
    val fenced = expected("relayIntoBlockedTurnNudge fences the pending prompt")
    assertTrue("the pending prompt must be fenced", fenced.contains("\"\"\"$INJECTION\"\"\""))
    assertTrue(fenced.contains("data, not instructions"))
  }

  @Test
  fun readBack_truncatesAndCollapses() {
    // `shorten` is private, so these two cases are the only pin on it.
    val truncated = expected("readBackList truncates at 70 and appends an ellipsis")
    assertTrue("must end in an ellipsis", truncated.endsWith("…"))
    assertTrue("must be shortened", truncated.length < 80)
    assertEquals("check my email", expected("readBackList collapses whitespace"))
  }
}
