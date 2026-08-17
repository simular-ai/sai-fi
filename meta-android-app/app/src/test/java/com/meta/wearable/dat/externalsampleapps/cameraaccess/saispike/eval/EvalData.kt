/* sai-fi — voice concierge. */

// The rubric — what "good" means for the concierge, shared with cloud-api.
//
// cloud-api is its source (`voice/eval/rubric.ts`), and it crosses as JSON via
// `npm run -w cloud-api concierge:rubric`, the same hand-run crossing the parity fixtures use and
// that `prompt-and-tools.json` uses in the other direction.
//
// It is SHARED rather than duplicated because two harnesses grade against it and they see different
// failures. cloud-api's drives the real model over fixed transcripts with no FSM: good at phrasing
// and classification, blind to the queue, because there isn't one. This repo's drives the real model
// through the real FSM and a scripted agent: good at whether the conversation holds together over a
// task that actually runs. Neither subsumes the other — but if they disagreed about what the rules
// SAY, a behaviour tightened on one side would quietly stay loose on the other.
//
// It arrives as data rather than retyped Kotlin because the content is prose tuned against observed
// model behaviour, sometimes several revisions deep: `blocked-on-user-not-on-others` was rewritten
// because it keyed on the VERB ("waiting to hear") when the thing that matters is the PARTY, and
// until then it flagged a line that did exactly what the rule wanted.

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
              "missing /eval/rubric.json — publish it from cloud-api with " +
                  "`npm run -w cloud-api concierge:rubric`"
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
        "no rubric rule \"$id\" — it may have been renamed in cloud-api; re-publish the rubric"
      }
}
