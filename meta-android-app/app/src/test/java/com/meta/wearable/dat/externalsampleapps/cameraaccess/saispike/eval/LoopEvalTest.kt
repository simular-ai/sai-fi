/* sai-fi — voice concierge. */

// The judged tier: the real model, driving the real FSM, graded against the shared rubric.
//
//   SAI_CONVERSATION_EVAL=1 GEMINI_API_KEY=… \
//     ./gradlew :app:testDebugUnitTest --tests "*LoopEvalTest*" --rerun
//
// `--rerun` is not optional. Gradle does not treat environment variables as task inputs, so a second
// invocation with different EVAL_ settings is UP-TO-DATE and reports success without running
// anything — which looks exactly like a fast green run.
//
// Env: EVAL_MODEL (concierge stand-in, default gemini-3.1-flash-lite-preview)
//      JUDGE_MODEL (grader, default gemini-3.5-flash-lite)
//      EVAL_PRINT=1 dumps each captured conversation
//
// **This complements `TranscriptEvalTest`; it does not replace it.** That one drives the model over 33
// fixed transcripts with no FSM: broad coverage of phrasing and classification, but its queue is a
// fiction — `forwardToAgent` resolves to a canned `ok`, and the `session-state` a scenario reacts to
// was written by hand. So it can grade whether the model SAYS the right thing about a waiting task,
// but not whether the task was really waiting. This one runs far fewer conversations through the
// real queue, where the answer is a fact rather than a premise. Both grade against the same
// `eval/rubric.json`, so a behaviour tightened in one cannot stay loose in the other.
//
// **Read the model before you read the failures.** The default stand-in is a tier BELOW what the
// glasses run, so it under-reports prompt quality; the recorded measurements have
// `queued-not-underway` and `reorder-is-not-a-cancellation` failing on lite and passing on
// `gemini-3-flash-preview`. Both are exercised here. Re-run a red on a flash-class model before concluding anything about the
// prompt — and if a rule fires on a line you would have been happy to hear on the glasses, suspect
// the rule's wording first.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.AgentBeat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.AgentProgram
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.ConversationHarness
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.Line
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LoopEvalTest {

  private val apiKey: String =
      System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY") ?: ""
  private val evalModel: String = System.getenv("EVAL_MODEL") ?: "gemini-3.1-flash-lite-preview"
  private val judgeModel: String = System.getenv("JUDGE_MODEL") ?: "gemini-3.5-flash-lite"

  /**
   * Two switches, not one.
   *
   * Gating on the API key alone would mean anyone with `GEMINI_API_KEY` exported — which is everyone
   * who has ever run the app locally — silently spends quota on every unit-suite run. The opt-in has
   * to be something you typed on purpose.
   */
  private fun requireOptIn() {
    assumeTrue(
        "set SAI_CONVERSATION_EVAL=1 to run the judged loop eval (it costs model quota)",
        System.getenv("SAI_CONVERSATION_EVAL") == "1")
    assumeTrue("set GEMINI_API_KEY to run the judged loop eval", apiKey.isNotEmpty())
  }

  /** A conversation to run live, and the rubric rules it is meant to exercise. */
  private data class LoopScenario(
      val name: String,
      val targets: List<String>,
      /**
       * Whether the agent should only ever have one task in flight.
       *
       * True for the ordinary queue, where a second ask waits its turn. FALSE for a deliberate
       * reorder: `sendQueuedNow` starts the waiting task WITHOUT stopping the running one — golden
       * S49, and the `reorder-is-not-a-cancellation` rule says so in as many words ("Both can
       * proceed"). Asserting one-at-a-time there would report the designed behaviour as a bug.
       */
      val oneTaskAtATime: Boolean = true,
      /**
       * A log fragment that must appear, proving the model actually reached for the tool.
       *
       * ON_DEVICE_CHECK §6c/§6d turn on exactly this evidence: "`→ effect: cancelQueued` ABSENT from
       * logcat means Sai said yes and never called the tool — the booking will still run". A
       * cheerful "sure, doing that now" followed by nothing changing is the failure, and no judge
       * reading the words alone can tell it from the real thing.
       */
      val requiresEffect: String? = null,
      val play: suspend (ConversationHarness) -> Unit,
  )

  private fun task(summary: String, ms: Long) =
      listOf(
          AgentBeat(20, AgentEvent.Status(AgentStatus.PROCESSING)),
          AgentBeat(ms, AgentEvent.Complete(summary)),
      )

  private val EMAIL = "check my email"
  private val TABLE = "book me a table for two on Friday"

  private val SCENARIOS =
      listOf(
          LoopScenario(
              name = "a second ask while one is running — really queued, not merely described as such",
              // The queue here is real: the FSM holds it and the agent has not started it. On the
              // server side these same rules are graded against a hand-written session-state.
              targets = listOf("queued-not-underway", "no-fabricated-progress", "first-person"),
          ) { h ->
            h.user(EMAIL)
            h.advance(200)
            h.user(TABLE)
            h.advance(100)
            h.user("how's it going?")
            h.settle()
          },
          LoopScenario(
              name = "moving the waiting task up — a reorder, not a trade",
              targets = listOf("reorder-is-not-a-cancellation", "queued-not-underway", "first-person"),
              // Both run here, deliberately — see `oneTaskAtATime`.
              oneTaskAtATime = false,
              requiresEffect = "sendQueuedNow",
          ) { h ->
            h.user(EMAIL)
            h.advance(200)
            h.user(TABLE)
            h.advance(100)
            h.user("actually, do the Friday booking first")
            h.settle()
          },
          LoopScenario(
              name = "a result reported after the user cut in",
              targets = listOf("no-tool-narration", "first-person", "no-fabricated-completion"),
          ) { h ->
            h.user(EMAIL)
            h.advance(200)
            h.bargeIn("hang on — what else can you do?")
            h.settle()
          },
      )

  @Test
  fun `the conversation holds up when the model drives the real loop`() {
    requireOptIn()

    val judge = Judge(apiKey, judgeModel)
    println("running ${SCENARIOS.size} loop scenarios on $evalModel (judge: $judgeModel)")

    var passed = 0
    var judgedPassed = 0
    val failures = mutableListOf<String>() // structural — these fail the build
    val judgedFailed = mutableListOf<String>() // judged — reported, never asserted
    val ungraded = mutableListOf<String>()

    for (s in SCENARIOS) {
      println("\n=== ${s.name} ===")
      lateinit var harness: ConversationHarness
      try {
        runBlocking {
          harness = newHarness()
          harness.start()
          s.play(harness)
        }
      } catch (e: DailyQuotaExhausted) {
        ungraded += "${s.name}: ${e.message}"
        println("  ⚠ ${e.message}")
        break
      } catch (e: Exception) {
        ungraded += "${s.name}: run error — ${e.message}"
        println("  ⚠ run error: ${e.message}")
        continue
      }

      val rendered = render(harness.transcript)
      if (System.getenv("EVAL_PRINT") == "1") println("--- transcript ---\n$rendered\n---")

      // A structural fact first, judge-free: whatever the model SAID, the work has to match. A
      // conversation that reads perfectly about tasks that never ran is precisely the failure the
      // server-side eval cannot see, because over there nothing runs at all.
      if (s.oneTaskAtATime) {
        if (harness.agent.overlapped.isNotEmpty()) {
          failures += "${s.name} / one-task-at-a-time — ${harness.agent.overlapped} ran concurrently"
          println("  ✗ one-task-at-a-time — ${harness.agent.overlapped}")
        } else {
          passed++
          println("  ✓ one-task-at-a-time")
        }
      }
      // Anything the model accepted must actually have reached the agent. "I'll do that next"
      // followed by nothing is the on-device check's highest-risk failure, and it is invisible to a
      // judge reading only the words.
      if (harness.agent.started.isEmpty()) {
        failures += "${s.name} / work-actually-ran — the model talked, but no task ever started"
        println("  ✗ work-actually-ran — nothing started")
      } else {
        passed++
        println("  ✓ work-actually-ran (${harness.agent.started.size} task(s))")
      }
      s.requiresEffect?.let { effect ->
        if (harness.logHas("effect: $effect")) {
          passed++
          println("  ✓ called $effect")
        } else {
          failures +=
              "${s.name} / called $effect — the model agreed to it in words and never called the " +
                  "tool, so nothing changed"
          println("  ✗ called $effect — never called; whatever it said, nothing changed")
        }
      }

      for (id in s.targets) {
        val rule = EvalData.rule(id)
        if (rule.notJudged != null) continue
        val verdict = judge.grade(rendered, rule)
        when {
          verdict.errored -> {
            ungraded += "${s.name} / $id: ${verdict.offending}"
            println("  ⚠ $id — ${verdict.offending}")
          }
          verdict.pass -> {
            judgedPassed++
            println("  ✓ $id")
          }
          else -> {
            judgedFailed += "${s.name} / $id — ${verdict.offending}"
            println("  ✗ $id — ${verdict.offending}")
          }
        }
      }
    }

    println(
        "\nRESULTS — structural: $passed passed, ${failures.size} failed" +
            "; judged: $judgedPassed passed, ${judgedFailed.size} flagged" +
            (if (ungraded.isNotEmpty()) ", ${ungraded.size} not graded (infra)" else "") +
            " (${SCENARIOS.size} loop scenarios, model $evalModel)")
    if (judgedFailed.isNotEmpty()) {
      println(
          "\nJudged flags are a SCORE, not a verdict — compare them against your last run on the " +
              "same model before concluding anything:")
      judgedFailed.forEach { println("  ✗ $it") }
      println(
          "  Re-run a flag on a flash-class model (EVAL_MODEL=gemini-3-flash-preview) before " +
              "touching the prompt. Measured here on 2026-08-17: `queued-not-underway` on the " +
              "queue scenario fails on lite and PASSES on flash — capability, not prompt.")
    }
    if (ungraded.isNotEmpty()) {
      // A check that never ran is unproven, not passing. Kept apart from a real flag so a flaky
      // network never reads as a behaviour regression.
      println("\nNot graded (infra — quota or network, never a behaviour finding):")
      ungraded.forEach { println("  ⚠ $it") }
    }

    // ONLY the structural checks fail the build. The judged ones are printed and not asserted, on
    // purpose: they are scores from a non-deterministic grader running a model tier below the one
    // the glasses use, so a red is as often capability or rule-wording as it is a regression.
    // Wiring them to the exit code makes the tier permanently red, and a check that is always red is
    // a check everyone learns to skip — which costs more than the coverage is worth. The structural
    // half has no such excuse: either the task ran or it did not.
    assertTrue(
        "${failures.size} structural failure(s) — the conversation did not match the work:\n" +
            failures.joinToString("\n") { "  $it" },
        failures.isEmpty())
  }

  private fun newHarness(): ConversationHarness {
    lateinit var h: ConversationHarness
    val brain =
        LiveBrain(
            apiKey = apiKey,
            model = evalModel,
            // getSaiStatus reads the REAL activity log, so what the model is told about the queue is
            // a fact about this run rather than a fixture.
            resolveLocalTool = { name, _ ->
              when (name) {
                "getSaiStatus" -> JSONObject().put("status", h.status())
                else -> JSONObject().put("result", "ok")
              }
            },
            log = { println("    $it") },
        )
    h = ConversationHarness(brain, speakingMs = 900)
    h.agent.programs += AgentProgram({ it.contains("email", true) }, task("3 new emails, nothing urgent", 1_500))
    h.agent.programs += AgentProgram({ it.contains("table", true) || it.contains("book", true) }, task("table booked for two on Friday at 7", 1_200))
    return h
  }

  /**
   * Render for the judge, in the vocabulary the judge prompt already describes: the concierge's own
   * lines, and "agent:" for internal context it received.
   */
  private fun render(transcript: List<Line>): String =
      transcript.joinToString("\n") { l ->
        when (l.speaker) {
          "sai" -> "concierge: ${l.text}"
          "you" -> "user: ${l.text}"
          else -> "agent: ${l.text}"
        }
      }
}
