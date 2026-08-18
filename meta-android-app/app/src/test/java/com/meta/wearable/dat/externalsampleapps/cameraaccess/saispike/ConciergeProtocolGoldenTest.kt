/* sai-fi — voice concierge. */

// The golden tests for the spoken/model-facing strings. This file only ever READS.
//
// Two things are checked, and they catch different mistakes:
//
//  1. every committed fixture still equals what the helper produces, case by case, so a reworded
//     nudge names itself in the failure;
//  2. the whole file still equals what the generator would write, byte for byte, so a case ADDED to
//     `GoldenFixtures.kt` and never regenerated is caught too — the per-case loop cannot see a case
//     that is missing from the JSON.
//
// Rewrite them with `SAI_REGEN_GOLDENS=1 ... --tests "*RegenerateGoldensTest*"` and commit the diff.
//
// These were `ConciergeProtocolParityTest` while cloud-api held a second, canonical implementation
// in TypeScript and the JSON was how the two ports were held equal. There is one implementation now
// — this one — so what the files pin is the WORDING: every string here is load-bearing, most of them
// found by hearing them fail on a device, and a golden makes a change to one a visible diff in a
// review instead of something a user discovers on the glasses.
//
// Beyond the byte comparison sit the assertions about what the strings SAY, which is the part a
// fixture diff cannot tell you: a diff shows the greeting changed, not that it stopped telling the
// model to speak first.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConciergeProtocolGoldenTest {
  private fun loadText(name: String): String =
      checkNotNull(javaClass.getResourceAsStream("/parity/$name")) {
            "missing golden /parity/$name — rewrite the goldens with SAI_REGEN_GOLDENS=1 " +
                "./gradlew :app:testDebugUnitTest --tests \"*RegenerateGoldensTest*\""
          }
          .bufferedReader()
          .use { it.readText() }

  private fun loadFixtures(name: String): JSONArray = JSONArray(loadText(name))

  /** The expected string for a named case, from the committed JSON. */
  private fun expected(file: String, name: String): String {
    val fixtures = loadFixtures(file)
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      if (f.getString("name") == name) return f.getString("expected")
    }
    throw AssertionError("no fixture named '$name' in $file")
  }

  @Test
  fun describeAgentEvent_matchesGoldens() {
    val fixtures = loadFixtures("agent-event-nudges.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      assertEquals(
          "describeAgentEvent drift for '${f.getString("name")}'",
          f.getString("expected"),
          describeAgentEvent(f.getJSONObject("input")))
    }
  }

  @Test
  fun describeCompleteAskFirst_matchesGoldens() {
    val fixtures = loadFixtures("complete-ask-first.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      assertEquals(
          "describeCompleteAskFirst drift for '${f.getString("name")}'",
          f.getString("expected"),
          describeCompleteAskFirst(f.getJSONObject("input")))
    }
  }

  @Test
  fun renderAgentActivity_matchesGoldens() {
    val fixtures = loadFixtures("agent-activity-render.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      assertEquals(
          "renderAgentActivity drift for '${f.getString("name")}'",
          f.getString("expected"),
          renderAgentActivity(f.getJSONObject("input")))
    }
  }

  @Test
  fun nudgeConstants_matchGoldens() {
    val fixtures = loadFixtures("constants.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val e = f.getString("expected")
      when (val name = f.getString("name")) {
        "APPROVAL_TIMEOUT_NUDGE" -> assertEquals(e, APPROVAL_TIMEOUT_NUDGE)
        "GREETING_NUDGE" -> assertEquals(e, GREETING_NUDGE)
        "MUTED_NUDGE" -> assertEquals(e, MUTED_NUDGE)
        "UNMUTED_NUDGE" -> assertEquals(e, UNMUTED_NUDGE)
        else -> throw AssertionError("unknown constant '$name' in constants.json")
      }
    }
  }

  /**
   * The case-by-case tests above compare each committed fixture to the helper. They cannot see a
   * case that exists in `GoldenFixtures.kt` and not in the JSON — that one is simply never visited.
   * This walks the other direction.
   */
  @Test
  fun everyGoldenFileIsCurrent() {
    for ((file, build) in GOLDEN_FILES) {
      assertEquals(
          "$file is stale — rewrite the goldens with SAI_REGEN_GOLDENS=1 and commit the diff",
          renderFile(build()),
          loadText(file))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // What the strings SAY. A byte diff shows the greeting changed; it does not show that it stopped
  // telling the model to speak first.
  // ---------------------------------------------------------------------------------------------

  /**
   * Fencing invariant: any nudge carrying agent-derived text keeps that text inside the fence, and
   * the fence opens only AFTER a bracketed instruction to the model, so an injection payload cannot
   * read as an instruction. `[agent]` on most of them, `[context — …]` on the failed-step nudge; what
   * the invariant is about is the ordering, not which marker was chosen.
   *
   * cloud-api's version of this asserted `indexOf("[agent]") < indexOf(fence)`, which passes
   * trivially when the marker is absent: -1 is less than everything. It therefore said nothing at
   * all about the one nudge that does not say `[agent]`.
   */
  @Test
  fun everyNudgeCarryingAgentText_fencesIt() {
    val fixtures = loadFixtures("agent-event-nudges.json")
    for (i in 0 until fixtures.length()) {
      val f = fixtures.getJSONObject(i)
      val name = f.getString("name")
      val e = f.getString("expected")
      if (!e.contains("\"\"\"")) continue
      assertTrue("'$name' must open with an instruction to the model", e.startsWith("["))
      assertTrue(
          "the instruction must close before the fenced text opens, for '$name'",
          e.indexOf("]") in 1 until e.indexOf("\"\"\""))
    }
  }

  @Test
  fun describeAgentEventNudges_sayWhatTheyMust() {
    val file = "agent-event-nudges.json"
    assertEquals("", expected(file, "text (internal, silent)"))
    assertEquals("", expected(file, "progress (internal, silent)"))

    val failed = expected(file, "progress failed (step failure — context, not speech)")
    assertTrue(failed.contains("do NOT speak about this unless the user asks"))
    assertTrue(failed.contains("you have NO result yet"))
    // fenced, like every other agent string
    assertTrue(failed.contains("\"\"\"tool execution failed\"\"\""))

    assertTrue(expected(file, "approval-request select single").contains("chooseOption"))
    assertTrue(
        expected(file, "complete with prompt-injection summary")
            .contains("\"\"\"$INJECTION\"\"\""))

    // A notice must be relayed, and must not be mistaken for a result — those two clauses are the
    // whole point of the kind existing, so they are asserted rather than left to the wording.
    val notice = expected(file, "notice (waking VM)")
    assertTrue(notice.contains("tell them now"))
    assertTrue(notice.contains("NOT a result"))
    assertTrue(
        notice.contains(
            "\"\"\"The agent is waking up and will get to your message in about a minute.\"\"\""))
  }

  @Test
  fun askFirstNudge_demandsSilenceThenOneShortOffer() {
    // The ask-first nudge demands SILENCE while waiting (a spoken "(I'll wait until you're free)"
    // aside is heard word for word, which defeats the point), then ONE short offer.
    val e = expected("complete-ask-first.json", "ask-first with summary")
    assertTrue(e.contains("Say NOTHING at all right now"))
    assertTrue(e.contains("SPOKEN ALOUD"))
    assertTrue(e.contains("\"\"\"Your report is ready.\"\"\""))
  }

  @Test
  fun renderAgentActivity_producesTheOneLinerForm() {
    assertEquals("status: processing", expected("agent-activity-render.json", "status"))
  }

  @Test
  fun systemNudgeConstants_sayWhatTheyMust() {
    assertTrue(APPROVAL_TIMEOUT_NUDGE.contains("[system]"))

    // The greeting must tell the model to speak first and not wait for the user.
    assertTrue(GREETING_NUDGE.contains("[system]"))
    assertTrue(GREETING_NUDGE.lowercase().contains("greet the user first"))
    assertTrue(GREETING_NUDGE.lowercase().contains("don't wait for them to speak"))

    // Muting must stop her SPEAKING without stopping her listening or working — and must not be
    // acknowledged aloud (a spoken "(I'll stay quiet)" is the exact failure this guards).
    assertTrue(MUTED_NUDGE.contains("[system]"))
    assertTrue(MUTED_NUDGE.lowercase().contains("produce no speech"))
    assertTrue(MUTED_NUDGE.lowercase().contains("do not acknowledge this message"))
    assertTrue(MUTED_NUDGE.lowercase().contains("still listening and still working"))
    // ...and must name the empty turn as the correct output. Without that, a model told to produce
    // no speech writes a token into the gap: "Empty-Response" / "No response received." both
    // reached the transcript (and the presenter's conversation column) straight after this nudge.
    assertTrue(MUTED_NUDGE.lowercase().contains("empty turn is the correct output"))
    assertTrue(MUTED_NUDGE.contains("Empty-Response"))
    assertTrue(MUTED_NUDGE.contains("No response received"))

    // Unmuting must not trigger a recap — held results are replayed separately, via ask-first.
    assertTrue(UNMUTED_NUDGE.contains("[system]"))
    assertTrue(UNMUTED_NUDGE.lowercase().contains("do not recap"))
  }
}
