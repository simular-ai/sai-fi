/* sai-fi — voice concierge. */

// The behavioural eval over fixed transcripts — the real model, in text mode, graded two ways.
//
//   SAI_TRANSCRIPT_EVAL=1 GEMINI_API_KEY=... \
//     ./gradlew :app:testDebugUnitTest --rerun --tests "*TranscriptEvalTest*"
//
//   Env: EVAL_MODEL (concierge stand-in), JUDGE_MODEL (grader), EVAL_ONLY (name substrings, comma
//        separated), EVAL_PRINT=1 (dump each captured conversation).
//
// OPT-IN, never in CI: it needs a key, costs quota, and is non-deterministic.
//
// TWO GRADERS, and the split is the point:
//
//  1. the rubric (phrasing/UX) — one judge model call per targeted rule: did the concierge violate
//     it, and which line;
//  2. the effect choice (classification) — deterministic, judge-free. Each transcript declares what
//     the model must and must not call, and this checks what it actually did.
//
// THIS COMPLEMENTS `LoopEvalTest`; IT DOES NOT REPLACE IT. This one drives 35 transcripts with no FSM
// and a canned `ok` for every forward: good at phrasing and at classification, blind to the queue,
// because here there isn't one. That one drives a handful of conversations through the real FSM and a
// scripted agent: good at whether the conversation holds together over a task that actually runs.
// The asymmetry is what makes them worth keeping apart — this one can grade whether it SAYS the
// right thing about a waiting task, but not whether the task was really waiting.
//
// It lived in cloud-api (`voice/eval/run.ts` + `harness.ts`) until 2026-08-18. Its subject was always
// this repo's: the prompt, the tools and the nudge wording all ship from here, so over there each had
// to be vendored — and the vendored prompt went stale by an entire retired feature without anything
// noticing, which meant the eval spent months grading a prompt for a tool the product no longer had.
// Here it reads `assets/voice-profile.json` and calls `describeAgentEvent` directly.
//
// WHAT IT CANNOT SEE. Text mode answers a tool-triggering turn with a functionCall part and no text
// part, so it cannot observe speech and a call in the same breath — `voice-before-capture` is marked
// `text-mode` in the rubric and graded on the device instead. Update discipline ("one ack then
// quiet") is not judged here either: it is enforced structurally, so it is a golden's business.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ActivityLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.MUTED_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.UNMUTED_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.describeAgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.describeCompleteAskFirst
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.describePhoneClock
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.shippedProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One thing that happened in a run: who spoke, what they said, and what was called. */
data class CapturedLine(
    val speaker: String,
    val text: String,
    val tools: List<String> = emptyList(),
    /** JSON-ish rendering of each call's arguments, so expectations can assert WHAT was forwarded. */
    val toolArgs: List<String> = emptyList(),
)

class TranscriptEvalTest {

  private val apiKey: String =
      System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY") ?: ""
  private val evalModel: String = System.getenv("EVAL_MODEL") ?: "gemini-3.1-flash-lite-preview"
  private val judgeModel: String = System.getenv("JUDGE_MODEL") ?: "gemini-3.5-flash-lite"

  /**
   * Two switches, not one — the same reasoning as `LoopEvalTest`. Gating on the API key alone would
   * mean anyone with `GEMINI_API_KEY` exported silently spends quota on every unit-suite run. The
   * opt-in has to be something you typed on purpose.
   */
  private fun requireOptIn() {
    assumeTrue(
        "set SAI_TRANSCRIPT_EVAL=1 to run the judged transcript eval (it costs model quota)",
        System.getenv("SAI_TRANSCRIPT_EVAL") == "1")
    assumeTrue("set GEMINI_API_KEY to run the judged transcript eval", apiKey.isNotEmpty())
  }

  @Test
  fun transcriptEval() {
    requireOptIn()

    val judge = Judge(apiKey, judgeModel)
    val rubric = EvalData.rubric()

    // EVAL_ONLY=<substring>[,<substring>] runs just the matching transcripts. A full run makes dozens
    // of model calls, which overruns a free-tier key's daily budget on the flash tiers — this makes
    // validating one fix affordable without dropping to a lite stand-in that calls no tools.
    //
    // Comma is the SEPARATOR, so a pattern cannot contain one — and several transcript names do
    // ("one running, one waiting — ..."). Pasting such a name whole silently splits it into two
    // patterns that match nothing, and the run still looks like it worked: it just selects fewer
    // transcripts. Pick a comma-free fragment, and check the count printed below.
    val only = System.getenv("EVAL_ONLY")?.lowercase()
    val patterns = only?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val selected =
        if (patterns.isEmpty()) TRANSCRIPTS
        else TRANSCRIPTS.filter { t -> patterns.any { t.name.lowercase().contains(it) } }
    if (only != null) {
      println("EVAL_ONLY=$only -> ${selected.size}/${TRANSCRIPTS.size} transcripts")
    }

    var structuralPassed = 0
    val structuralFailures = mutableListOf<String>()
    var judgedPassed = 0
    val judgedFailed = mutableListOf<String>()
    // A check that never ran is unproven, not broken. Folding infra failures into violations turns a
    // flaky network into a phantom behaviour regression and sends you hunting a bug in the prompt.
    val ungraded = mutableListOf<String>()

    for (t in selected) {
      println("\n=== ${t.name} ===")
      val lines =
          try {
            runTranscript(t)
          } catch (e: DailyQuotaExhausted) {
            // Terminal for this model: every remaining transcript would fail the same way, and each
            // attempt costs the next run's budget.
            ungraded += "${t.name}: ${e.message}"
            println("  ! ${e.message}")
            break
          } catch (e: Exception) {
            ungraded += "${t.name}: run error: ${e.message}"
            println("  ! run error: ${e.message}")
            continue
          }

      val transcript = render(lines)
      // EVAL_PRINT=1 dumps the captured conversation. A judge verdict names the offending LINE but
      // not the turn it came from, and the same sentence can be a violation early and correct later
      // ("I'm on that too" is false about a queued task and true once it has been started) — without
      // the transcript there is no way to tell which, so a false red reads exactly like a real one.
      if (System.getenv("EVAL_PRINT") != null) println("--- transcript ---\n$transcript\n---")

      for (rule in rubric.filter { it.id in t.targets }) {
        val v = judge.grade(transcript, rule)
        when {
          v.pass -> {
            judgedPassed++
            println("  + ${rule.id}")
          }
          v.errored -> {
            ungraded += "${t.name} / ${rule.id}: ${v.offending}"
            println("  ! ${rule.id} — ${v.offending}")
          }
          else -> {
            judgedFailed += "${t.name} / ${rule.id}: ${v.offending}"
            println("  - ${rule.id} — ${v.offending}")
          }
        }
      }

      for (c in checkTools(lines, t.expectTools)) {
        if (c.pass) {
          structuralPassed++
          println("  + ${c.label}")
        } else {
          structuralFailures += "${t.name} / ${c.label}: ${c.detail}"
          println("  - ${c.label} — ${c.detail}")
        }
      }
    }

    println(
        "\nRESULTS — effect choice: $structuralPassed passed, ${structuralFailures.size} failed" +
            "; judged: $judgedPassed passed, ${judgedFailed.size} flagged" +
            (if (ungraded.isNotEmpty()) ", ${ungraded.size} not graded (infra)" else "") +
            " (${selected.size} transcripts, model $evalModel)")

    if (judgedFailed.isNotEmpty()) {
      println(
          "\nJudged flags are a SCORE, not a verdict — compare them against your last run on the " +
              "same model before concluding anything:")
      judgedFailed.forEach { println("  - $it") }
      println(
          "  READ THE MODEL BEFORE THE FAILURES. The default is a LITE tier, below the " +
              "gemini-3.1-flash-live-preview the glasses run, so it systematically under-reports " +
              "prompt quality. Measured in cloud-api on 2026-08-07: 121 passed, 7 failed on lite — " +
              "and `queued-not-underway`, `blocked-on-user-not-on-others` and " +
              "`reorder-is-not-a-cancellation` all PASS on gemini-3-flash-preview. Re-run one row " +
              "there (EVAL_ONLY=\"<fragment>\") before touching the prompt.")
      println(
          "  A red can also be the RULE, not the concierge. " +
              "`blocked-on-user-not-on-others` once banned describing the wait as \"hearing back\" " +
              "from anyone, which flagged a line that named the user and handed the question " +
              "straight back — exactly what the rule exists to require. It keyed on the verb when " +
              "the thing that matters is the party. When a rule fires on a line you would have been " +
              "happy to hear on the glasses, suspect the rule's wording first.")
    }
    if (ungraded.isNotEmpty()) {
      println("\nNot graded (infra — quota or network, never a behaviour finding):")
      ungraded.forEach { println("  ! $it") }
    }

    // ONLY the deterministic effect-choice checks fail the build, matching `LoopEvalTest`. The judged
    // ones are printed and not asserted, on purpose: they are scores from a non-deterministic grader
    // running a model tier below the one the glasses use, so a red is as often capability or
    // rule-wording as it is a regression. cloud-api's runner DID gate its exit code on them, and the
    // measured consequence is in the note above — 7 permanent reds on the default model. A check that
    // is always red is a check everyone learns to skip. The effect-choice half has no such excuse:
    // either the model called the tool or it did not.
    assertTrue(
        "${structuralFailures.size} effect-choice failure(s) — the model classified wrongly:\n" +
            structuralFailures.joinToString("\n") { "  $it" },
        structuralFailures.isEmpty())
  }

  // ── the harness ────────────────────────────────────────────────────────────────────────────────

  /**
   * Run one transcript against the real model and return the captured conversation.
   *
   * It uses the SAME `describeAgentEvent` the device renders, and the same prompt and tools the app
   * ships. There used to be a second copy of both in cloud-api, with a comment asking the reader to
   * "keep them roughly in sync" — they were not in sync: the copy had no `notice` case, so every run
   * graded a nudge set the product does not send, and the one event class whose whole purpose is to
   * be relayed was invisible to the judge.
   */
  private fun runTranscript(t: Transcript): List<CapturedLine> {
    val profile = shippedProfile()
    val chat = GeminiText(apiKey, evalModel, log = { println("    $it") })
    chat.systemPrompt = profile.systemPrompt
    chat.tools =
        JSONArray().apply {
          profile.tools.forEach { d ->
            put(
                JSONObject().put("name", d.name).put("description", d.description).apply {
                  d.parameters?.let { put("parametersJsonSchema", it) }
                })
          }
        }

    val activity = ActivityLog()
    val lines = mutableListOf<CapturedLine>()

    fun send(message: String) {
      chat.addUserText(message)
      var parts = chat.generate()
      var round = 0
      while (true) {
        val text = parts.mapNotNull { it.text }.joinToString(" ") { it.trim() }.trim()
        val calls = parts.mapNotNull { it.call }
        // Exactly one history entry per `generate`. Recording the final batch twice is not a
        // cosmetic slip: the doubled text goes to the JUDGE, which reads "On it. On it." as the
        // concierge saying it twice and marks a violation the concierge never committed.
        chat.addModelParts(parts)
        // Record the call even when the turn has NO spoken text. The persona prompt tells the model
        // to call tools silently, so a correct silent forwardToAgent/captureImage arrives as a
        // functionCall part with no text part — gating this on the text dropped exactly those calls,
        // which made every `includes` fail and every `excludes` pass vacuously against an empty set.
        // If a whole run reports "called: none", suspect the harness before the model.
        lines +=
            CapturedLine(
                speaker = "concierge",
                text = text,
                tools = calls.map { it.optString("name") },
                toolArgs = calls.map { (it.optJSONObject("args") ?: JSONObject()).toString() })
        if (calls.isEmpty() || ++round > MAX_TOOL_ROUNDS) break

        chat.addToolResponses(
            calls.map { c ->
              val name = c.optString("name")
              val response =
                  t.toolResults[name]
                      ?: when (name) {
                        "getSaiStatus" -> JSONObject().put("status", activity.statusText())
                        "getLocalTime" -> JSONObject().put("time", describePhoneClock())
                        else -> JSONObject().put("result", "ok")
                      }
              name to response
            })
        parts = chat.generate()
      }
    }

    // Mirror the DEVICE's mute behaviour, because otherwise this harness tests an impossible turn.
    // `CallService` holds a completion nudge while muted (`HeldNudgeQueue`) and replays it on unmute
    // with the ask-first wording — it never injects "tell the user the result" into a muted turn. The
    // harness used to do exactly that, which asks the model to obey two contradictory instructions
    // and then grades it for obeying the more recent one. `silent-while-muted` failed on it
    // permanently, and a check that is always red is a check everyone learns to skip.
    var muted = false
    val held = mutableListOf<String>()

    for (turn in t.turns) {
      when (turn) {
        is Turn.User -> {
          lines += CapturedLine(speaker = "user", text = turn.text)
          send(turn.text)
        }
        is Turn.Sys -> {
          // A client-injected [system] nudge. Labelled `agent` for the judge, which knows that
          // speaker as "internal context it received" — which is exactly what this is.
          lines += CapturedLine(speaker = "agent", text = turn.text)
          send(turn.text)
          if (turn.text == MUTED_NUDGE) muted = true
          if (turn.text == UNMUTED_NUDGE) {
            muted = false
            val replay = held.toList()
            held.clear()
            for (nudge in replay) {
              lines += CapturedLine(speaker = "agent", text = nudge)
              send(nudge)
            }
          }
        }
        is Turn.Agent -> {
          activity.record(turn.event)
          val nudge = describeAgentEvent(turn.event)
          // An internal event — recorded for getSaiStatus, but there is nothing to react to.
          if (nudge.isEmpty()) continue
          if (muted && turn.event.optString("type") == "complete") {
            // Held, not dropped: the device replays it once Sai is audible, using the ask-first
            // wording — offer the result when they are free, rather than announcing into a room it
            // was told not to speak in.
            held += describeCompleteAskFirst(turn.event)
            continue
          }
          lines += CapturedLine(speaker = "agent", text = nudge)
          send(nudge)
        }
      }
    }
    return lines
  }

  /**
   * Render for the judge. A silent tool call has no text, and printing it as a bare empty line
   * ("concierge:  [tools: captureImage]") reads to a judge as a broken or empty utterance and draws
   * spurious violations on unrelated rules — so label it as the deliberate silent action it is.
   */
  private fun render(lines: List<CapturedLine>): String =
      lines.joinToString("\n") { l ->
        val tools = if (l.tools.isNotEmpty()) " [tools: ${l.tools.joinToString(", ")}]" else ""
        if (l.text.isEmpty() && tools.isNotEmpty()) {
          "${l.speaker}: (no speech — silently calls the tool)$tools"
        } else {
          "${l.speaker}: ${l.text}$tools"
        }
      }

  // ── the deterministic half ─────────────────────────────────────────────────────────────────────

  private data class Check(val label: String, val pass: Boolean, val detail: String)

  /**
   * Grade the model's effect CHOICE — which functions it actually called. Aggregated over the whole
   * run so it is robust to wording drift while still catching a wrong effect, e.g. `approve` on a
   * choice.
   */
  private fun checkTools(lines: List<CapturedLine>, exp: ToolExpectation?): List<Check> {
    if (exp == null) return emptyList()
    val allCalls = lines.flatMap { it.tools }.filter { it.isNotEmpty() }
    val called = allCalls.toSet()
    val allArgs = lines.flatMap { it.toolArgs }.joinToString(" ").lowercase()
    val out = mutableListOf<Check>()

    exp.includes.forEach { name ->
      val pass = name in called
      out +=
          Check(
              "calls $name",
              pass,
              if (pass) ""
              else
                  "expected $name (called: ${called.joinToString(", ").ifEmpty { "none" }})")
    }
    exp.excludes.forEach { name ->
      val pass = name !in called
      out += Check("never $name", pass, if (pass) "" else "$name was called but must not be")
    }
    exp.atMost.forEach { (name, max) ->
      val n = allCalls.count { it == name }
      out +=
          Check(
              "at most ${max}x $name",
              n <= max,
              if (n <= max) ""
              else "called ${n}x (max $max) — an extra call means it obeyed something")
    }
    // Pair each call with its OWN args (tools[i] <-> toolArgs[i]). A flag on another tool in the same
    // turn must not count, which is why this cannot go through the flattened `allArgs`.
    fun argsFor(name: String): List<JSONObject> =
        lines.flatMap { l ->
          l.tools.mapIndexedNotNull { i, t ->
            if (t == name) JSONObject(l.toolArgs.getOrElse(i) { "{}" }) else null
          }
        }
    exp.flags.forEach { (name, flags) ->
      flags.forEach { (arg, expected) ->
        val calls = argsFor(name)
        val anySet = calls.any { it.optBoolean(arg, false) }
        out +=
            Check(
                if (expected) "$name sets $arg" else "$name leaves $arg unset",
                anySet == expected,
                when {
                  anySet == expected -> ""
                  expected ->
                      "no $name call set $arg (args: ${calls.joinToString(" ").ifEmpty { "none" }})"
                  else ->
                      "a $name call set $arg when it must not — that request carries the photo"
                })
      }
    }
    exp.excludesArgText.forEach { needle ->
      val pass = !allArgs.contains(needle.lowercase())
      out +=
          Check(
              "no tool arg contains \"$needle\"",
              pass,
              if (pass) ""
              else "a tool call carried \"$needle\" in its arguments: ${allArgs.take(200)}")
    }
    return out
  }

  private companion object {
    /**
     * How many rounds of call-then-answer one turn may take before the harness stops. A model that
     * keeps calling forever is a runaway, not a conversation.
     */
    const val MAX_TOOL_ROUNDS = 6
  }
}
