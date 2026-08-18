/* sai-fi — voice concierge. */

// The demo flow: ONE call, the real model, a real agent, mirrored to the presenter.
//
//   SAI_DEMO=1 SAI_PRESENTER=1 GEMINI_API_KEY=… \
//   SAI_CONCIERGE_URL=… SAI_MACHINE_ID=… SAI_ID_TOKEN=… \
//     ./gradlew :app:testDebugUnitTest --tests "*DemoFlowTest*" --rerun
//
// Everything is real except the microphone and the camera: the model is Gemini running the prompt
// and tools the app ships, the FSM is the app's, the bridge and its wire are the app's, and the
// agent is a real machine doing real work. What is stood in for is the audio — the user's turns are
// injected as text at speaking pace — and the glasses camera, which uploads a labelled frame through
// the real upload endpoint.
//
// **One call, not a series.** Each beat depends on the state the last one left: a task is queued only
// because another is running, the status question is only interesting with two things outstanding,
// and the goodbye only tests anything with work still in flight. Restarting between beats would
// destroy the very thing being shown, and it is also how a demo ends up proving less than it appears
// to — every step passing in isolation while the sequence has never once been run end to end.
//
// Paced for watching, not for speed. The user's lines stream in at ordinary speech (~150 wpm) rather
// than appearing whole, because a line that lands complete and instantly is the tell that a rig is
// driving it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval.LiveBrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class DemoFlowTest {

  private val apiKey = System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY") ?: ""
  private val model = System.getenv("EVAL_MODEL") ?: "gemini-3-flash-preview"

  @Test
  fun `the glasses demo, one conversation, live agent`() {
    assumeTrue("set SAI_DEMO=1 to run the demo flow (it drives a real agent)", System.getenv("SAI_DEMO") == "1")
    assumeTrue("set GEMINI_API_KEY", apiKey.isNotEmpty())
    val config = LiveAgentConfig.fromEnv().getOrElse { throw IllegalStateException(it.message, it) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var h: ConversationHarness

    val brain =
        LiveBrain(
            apiKey = apiKey,
            model = model,
            resolveLocalTool = { name, _ ->
              when (name) {
                "getSaiStatus" -> JSONObject().put("status", h.status())
                // The camera. Uploads the frame and leaves it on the bridge, so the next forward
                // carries it exactly as a real capture would — the immediate path drains the stash.
                "captureImage" ->
                    runBlocking {
                      runCatching {
                            val a = DummyCamera.capture(config.baseUrl, config.idToken)
                            h.stashAttachment(a)
                            println("    [camera] uploaded a simulated frame (${a.name})")
                            JSONObject()
                                .put("result", "captured")
                                .put(
                                    "note",
                                    "The photo is saved on the device and has NOT been sent " +
                                        "anywhere; it goes only when a request carries it.")
                          }
                          .getOrElse {
                            JSONObject().put("result", "failed").put("error", it.message ?: "camera error")
                          }
                    }
                else -> JSONObject().put("result", "ok")
              }
            },
            log = { println("    $it") },
        )

    h = ConversationHarness(brain, speakingMs = 600)
    val live =
        LiveAgent(config, scope, deliver = { h.deliverAgentEvent(it) }, log = { println("    $it") })
    h.useTransport(live)

    runBlocking {
      h.start()
      h.beat(1_500)

      // 1 — the greeting. Sai speaks first, unprompted, exactly as it does when a call opens.
      say(h, "Hey Sai, are you there?")
      settle(h, live, 6_000)

      // 2 — a real task on a real machine, and the approval it trips.
      //
      //     The first run of this demo had no approval beat, and it distorted everything after it:
      //     the listing tripped a guardrail, Sai asked "would you like me to approve that?", the
      //     script ignored the question and moved on, and the task sat blocked while the rest of the
      //     conversation talked around a result that never came. A demo that cannot answer a
      //     guardrail cannot show a task finishing.
      say(h, "Can you check what's in my downloads folder?")
      settle(h, live, 45_000)
      answerAnyApproval(h, live)

      // 3 — a second ask while the first is still going. This is the queue, and it only exists
      //     because beat 2 left something running.
      say(h, "Oh, and also — what time is it right now?")
      h.beat(2_000)

      // 4 — with two outstanding, ask what is happening. The interesting part is that they are
      //     accounted for separately.
      say(h, "What's going on with all that?")
      settle(h, live, 45_000)

      // 5 — the camera.
      say(h, "Have a look at this and tell me what it says.")
      settle(h, live, 45_000)

      // 6 — barge-in, mid-answer.
      h.beat(800)
      bargeIn(h, "Sorry — actually, what can you do for me?")
      settle(h, live, 20_000)

      // 7 — the goodbye.
      say(h, "That's everything, thanks — bye.")
      settle(h, live, 15_000)

      println("\n=== the conversation ===")
      h.transcript.forEach { println("  ${it.speaker.padEnd(6)} ${it.text}") }
      println("\n=== what the agent ran ===")
      live.started.forEach { println("  • $it") }
      println("\nevents off the wire: ${live.received.map { it::class.simpleName }}")
      if (live.errors.isNotEmpty()) println("errors: ${live.errors}")
    }
  }

  /**
   * If the agent is waiting on a guardrail, answer it the way a wearer would.
   *
   * Checked rather than scripted blind: whether a given task trips an approval depends on the
   * machine's settings, so a fixed "yes, go ahead" beat would be an answer to nothing on a machine
   * with guardrails off — and the model, told yes for no reason, has to invent what it is agreeing to.
   */
  private suspend fun answerAnyApproval(h: ConversationHarness, live: LiveAgent) {
    if (h.state.pendingApprovalId == null) {
      println("    (no approval was raised — nothing to answer)")
      return
    }
    say(h, "Yes, go ahead — you have my approval.")
    settle(h, live, 60_000)
  }

  /** A user turn, at speaking pace, with a breath before it. */
  private suspend fun say(h: ConversationHarness, line: String) {
    h.beat(900)
    println("\n>>> $line")
    h.user(line)
  }

  private suspend fun bargeIn(h: ConversationHarness, line: String) {
    println("\n>>> (cutting in) $line")
    h.bargeIn(line)
  }

  /**
   * Let the agent get on with it.
   *
   * Real time, not the virtual clock: a live agent takes as long as it takes, and the demo is
   * watchable precisely because those pauses are real. Bounded so a cold machine cannot hang the run.
   */
  private suspend fun settle(h: ConversationHarness, live: LiveAgent, budgetMs: Long) {
    withTimeoutOrNull(budgetMs) { live.awaitTurn() }
    h.beat(1_200)
  }
}
