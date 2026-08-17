/* sai-fi — voice concierge. */

// The shared rubric, checked without spending a single model call.
//
// The judged tier is opt-in and costs quota, so without this nothing in CI would notice that the
// vendored rubric arrived truncated, or that a rule a loop scenario names had been renamed upstream.
// Neither fails loudly — they quietly grade less than you think, and the scorecard still says green.
//
// cloud-api owns the rubric and its own invariants (`rubric.test.ts`, which also checks the things
// only make sense there — that every transcript targets a real rule, and none targets a non-judged
// one). These are the checks that still mean something on this side of the crossing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalDataTest {

  private val rubric = EvalData.rubric()
  private val ruleIds = rubric.map { it.id }.toSet()

  @Test
  fun `the exported data is all there`() {
    // Pinned counts, so a truncated or half-copied export is a failure rather than a quiet
    // reduction in coverage. Update these deliberately when the catalog grows.
    assertEquals("rules", 31, rubric.size)
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
    // cloud-api owns this distinction; if the marker vanished in a re-publish, a rule the judge
    // cannot see would start being graded and fail every run.
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
}
