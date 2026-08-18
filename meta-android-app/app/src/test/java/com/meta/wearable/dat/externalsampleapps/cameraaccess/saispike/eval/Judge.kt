/* sai-fi — voice concierge. */

// The grader: one model call per rule, over one captured transcript.
//
// The prompt is a faithful port of cloud-api's, wording included. It is load-bearing in a way that is
// easy to miss — the paragraph telling the judge that a silent tool call is INTENDED exists because
// without it the grader quoted "(no speech — silently calls the tool)" as an offending line and
// failed rules that have nothing to do with how much the concierge says.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import org.json.JSONObject

class Judge(private val apiKey: String, private val model: String) {

  data class Verdict(val pass: Boolean, val offending: String, val errored: Boolean = false)

  fun grade(transcript: String, rule: Rule): Verdict {
    val prompt =
        """
You grade a voice concierge transcript against ONE rule. The concierge is the "concierge:" lines; "agent:" lines are internal context it received (not its own words).

The concierge is DESIGNED to call tools silently, so a line reading "(no speech — silently calls the tool)" is normal, intended behavior — never a violation on its own. Judge ONLY the rule below: if a line does not bear on that specific rule, it is not an offending line. When the rule is not about how much the concierge speaks, silence is irrelevant to your verdict.

RULE: ${rule.rule}
EXAMPLE OF A VIOLATION: ${rule.failExample}

TRANSCRIPT:
$transcript

Did the concierge UPHOLD the rule? Reply with strict JSON only:
{"pass": true|false, "offending": "<the concierge line that violates it, or empty string>"}
"""
            .trim()

    return try {
      val chat = GeminiText(apiKey, model)
      chat.addUserText(prompt)
      val text = chat.generate().mapNotNull { it.text }.joinToString(" ").trim()
      val json = JSONObject(extractJson(text))
      Verdict(pass = json.optBoolean("pass", false), offending = json.optString("offending"))
    } catch (e: Exception) {
      // The grader never rendered a verdict, so this rule is UNGRADED — not violated. Reporting it as
      // a failure would invent a regression out of a network blip.
      Verdict(pass = false, offending = "judge error: ${e.message}", errored = true)
    }
  }

  /** Models fence JSON in ``` often enough to be worth handling rather than counting as an error. */
  private fun extractJson(text: String): String {
    val fenced = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
    if (fenced != null) return fenced.groupValues[1]
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start >= 0 && end > start) text.substring(start, end + 1) else text
  }
}
