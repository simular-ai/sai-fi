/* sai-fi — voice concierge. */

// The rubric and the transcripts that grade against it, checked without spending a model call.
//
// Both judged tiers are opt-in and cost quota, so without this nothing in CI would notice that the
// rubric had been truncated, or that a scenario names a rule that no longer exists, or that a
// transcript targets a rule the judge is not allowed to grade. None of those fail loudly — they
// quietly grade less than you think, and the scorecard still says green.
//
// So the whole wiring between the catalogue and the things that read it is asserted here, for free.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalDataTest {

  private val rubric = EvalData.rubric()
  private val ruleIds = rubric.map { it.id }.toSet()

  @Test
  fun `the catalogue is all there`() {
    // Pinned counts, so a truncated file or a half-finished edit is a failure rather than a quiet
    // reduction in coverage. Update these deliberately when the catalogue grows.
    assertEquals("rules", 33, rubric.size)
    assertEquals("transcripts", 35, TRANSCRIPTS.size)
  }

  @Test
  fun `rule ids are unique — a duplicate would silently shadow a rule`() {
    assertEquals(rubric.size, ruleIds.size)
  }

  @Test
  fun `every rule has both a statement and a fail example to anchor the judge`() {
    rubric.forEach {
      assertTrue("${it.id} has no rule text", it.rule.isNotEmpty())
      assertTrue("${it.id} has no failExample", it.failExample.isNotEmpty())
    }
  }

  @Test
  fun `some rule is marked notJudged, so the marker still means something`() {
    // If the marker were dropped, a rule the judge structurally cannot see would start being
    // graded, and fail every run for a reason that has nothing to do with the concierge.
    assertTrue(rubric.any { it.notJudged != null })
  }

  @Test
  fun `every rule the loop scenarios name still exists`() {
    // The scenarios reference rules by id across a repository boundary, so a rename upstream would
    // otherwise surface as a crash inside an opt-in tier nobody runs on a branch. EvalData.rule
    // throws with the re-publish instruction; this is what makes CI say so first.
    listOf(
            "queued-not-underway",
            "no-fabricated-progress",
            "first-person",
            "reorder-is-not-a-cancellation",
            "no-tool-narration",
            "no-fabricated-completion",
        )
        .forEach { EvalData.rule(it) }
  }

  // ── the wiring between the catalogue and the transcripts that grade against it ────────────────
  // Every one of these is free, and each covers a way the judged tier can quietly grade less than it
  // appears to. They came from cloud-api's `rubric.test.ts`, and they only became assertable here
  // once the transcripts arrived: while they lived in the other repo, this side could see the rubric
  // but not the things reading it.

  @Test
  fun `every transcript targets a rule that exists`() {
    TRANSCRIPTS.forEach { t ->
      t.targets.forEach { id ->
        assertTrue("transcript \"${t.name}\" targets unknown rule \"$id\"", id in ruleIds)
      }
    }
  }

  /**
   * The regression `rubric.test.ts` was written for. Both `notJudged` reasons produced a red that
   * said nothing about the product: `voice-before-capture` ("text-mode") cannot be observed in text
   * mode at all, and `screen-vs-camera` ("deterministic") was re-litigated by a grader that quoted a
   * silence its own rule text calls irrelevant. A scorecard with a permanent red stops being read,
   * which is worse than the check not existing.
   */
  @Test
  fun `no transcript targets a rule the judge does not grade`() {
    val notJudged = rubric.filter { it.notJudged != null }.map { it.id }.toSet()
    assertTrue("the marker must have teeth", notJudged.isNotEmpty())
    TRANSCRIPTS.forEach { t ->
      val offending = t.targets.filter { it in notJudged }
      assertTrue(
          "transcript \"${t.name}\" targets non-judged rule(s): $offending", offending.isEmpty())
    }
  }

  /**
   * A rule marked "deterministic" claims a tool expectation covers it instead — so a transcript that
   * targets nothing must at least assert some tool. Otherwise "covered deterministically" silently
   * means "not covered at all", which is the trade the marker exists to avoid.
   */
  @Test
  fun `a transcript that targets no rule still asserts some tool`() {
    assertTrue(
        "the deterministic marker must be in use",
        rubric.any { it.notJudged == "deterministic" })
    val bare =
        TRANSCRIPTS.filter { t ->
          val e = t.expectTools
          t.targets.isEmpty() && (e == null || e.includes.isEmpty() && e.excludes.isEmpty())
        }
    assertTrue("transcript asserts neither a rule nor any tool: ${bare.map { it.name }}", bare.isEmpty())
  }

  @Test
  fun `every transcript has something to check`() {
    TRANSCRIPTS.forEach { t ->
      val e = t.expectTools
      val expectsTools =
          e != null &&
              (e.includes.isNotEmpty() ||
                  e.excludes.isNotEmpty() ||
                  e.atMost.isNotEmpty() ||
                  e.excludesArgText.isNotEmpty() ||
                  e.flags.isNotEmpty())
      assertTrue("transcript \"${t.name}\" asserts nothing", t.targets.isNotEmpty() || expectsTools)
    }
  }

  @Test
  fun `transcript names are unique, since EVAL_ONLY selects by name substring`() {
    val names = TRANSCRIPTS.map { it.name }
    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun `no transcript both includes and excludes the same tool`() {
    TRANSCRIPTS.forEach { t ->
      val both = (t.expectTools?.includes ?: emptyList()).filter { it in (t.expectTools?.excludes ?: emptyList()) }
      assertTrue("transcript \"${t.name}\" both includes and excludes: $both", both.isEmpty())
    }
  }
}
