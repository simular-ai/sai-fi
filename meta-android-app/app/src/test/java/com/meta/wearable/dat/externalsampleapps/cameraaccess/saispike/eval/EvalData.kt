/* sai-fi — voice concierge. */

// The rubric — what "good" means for the concierge. `resources/eval/rubric.json` is its source.
//
// TWO HARNESSES READ IT, and they see different failures. `TranscriptEvalTest` drives the real model
// over fixed transcripts with no FSM: good at phrasing and classification, blind to the queue,
// because in that harness there isn't one. `LoopEvalTest` drives the real model through the real FSM
// and a scripted agent: good at whether the conversation holds together over a task that actually
// runs. Neither subsumes the other, and they must agree on what the rules SAY, or a behaviour
// tightened in one stays loose in the other.
//
// It stays JSON rather than becoming Kotlin string constants because the content is prose tuned
// against observed model behaviour, sometimes several revisions deep: `blocked-on-user-not-on-others`
// was rewritten because it keyed on the VERB ("waiting to hear") when the thing that matters is the
// PARTY, and until then it flagged a line that did exactly what the rule wanted. Thirty-one rules of
// that read better as a document than as escaped literals, and it is graded, never executed.
//
// It used to be generated in cloud-api (`voice/eval/rubric.ts`) and hand-copied here. Nothing
// checked the copy. It is written and reviewed here now, and the harness that read it from over
// there came with it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import org.json.JSONArray

/** One behaviour the concierge must uphold, graded by a judge model. */
data class Rule(
    val id: String,
    val rule: String,
    val failExample: String,
    /**
     * Why this rule is not graded, when it isn't. It stays in the catalog either way — it states
     * intended behaviour, and a deleted rule is a behaviour nobody is watching.
     *
     *  - `text-mode` — a text-mode harness structurally cannot see it, because text mode answers a
     *    tool-triggering turn with a functionCall and no text part. Verify on device.
     *  - `deterministic` — an effect expectation already asserts exactly this, judge-free. A second,
     *    non-deterministic opinion on a settled question only adds false reds.
     */
    val notJudged: String?,
)

object EvalData {

  fun rubric(): List<Rule> {
    val text =
        checkNotNull(EvalData::class.java.getResourceAsStream("/eval/rubric.json")) {
              "missing /eval/rubric.json — it lives at " +
                  "app/src/test/resources/eval/rubric.json"
            }
            .bufferedReader()
            .readText()
    val arr = JSONArray(text)
    return (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      Rule(
          id = o.getString("id"),
          rule = o.getString("rule"),
          failExample = o.getString("failExample"),
          notJudged = o.optString("notJudged").ifEmpty { null },
      )
    }
  }

  /** Look one up, failing loudly: a scenario naming a rule that no longer exists grades nothing. */
  fun rule(id: String): Rule =
      requireNotNull(rubric().firstOrNull { it.id == id }) {
        "no rubric rule \"$id\" — check the id against resources/eval/rubric.json"
      }
}
