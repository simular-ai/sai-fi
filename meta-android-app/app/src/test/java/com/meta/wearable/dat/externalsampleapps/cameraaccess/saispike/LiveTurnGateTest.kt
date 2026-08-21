/* sai-fi — voice concierge. */

// The turn/nudge gate: whether a nudge reaches the model, when, and whether it is lost.
//
// This machine had no test at all until it was extracted from GeminiLiveClient, and two bugs are on
// record in it. Each gets a named test here (`a barge-in then a session replacement…` and `a
// generation that ends without a turn does not flush…`), and each was checked by reintroducing the
// bug and watching the test go red. A test for a bug that cannot fail is not a test.
//
// Log strings are asserted verbatim in places. That is deliberate: ON_DEVICE_CHECK.md tells a human
// to grep for `→ nudge:` / `← nudge:` / `✗ nudge:` while wearing the glasses, so those strings are a
// contract with the person running the check, not incidental debug output.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTurnGateTest {

  /** A gate with a clock the test drives. */
  private class Fixture {
    var now = 1_000L
    val gate = LiveTurnGate { now }

    /** Bring the session up: connected and past setupComplete, which is the normal resting state. */
    fun ready(): Fixture {
      gate.onConnect()
      gate.onSetupComplete()
      return this
    }

    /** Put the model mid-utterance, the way a transcript delta does on a real call. */
    fun speaking(): Fixture {
      gate.onSaiTranscript("I'm on it")
      assertTrue("precondition: the model should be mid-utterance", gate.isModelSpeaking)
      return this
    }
  }

  private fun List<GateAction>.sent() = filterIsInstance<GateAction.SendTurn>().map { it.text }

  private fun List<GateAction>.logs() = filterIsInstance<GateAction.Log>().map { it.text }

  private fun List<GateAction>.transcripts() =
      filterIsInstance<GateAction.SaiTranscript>().map { it.text }

  // ── The two recorded bugs ────────────────────────────────────────────────────────────────────────

  @Test
  fun `a barge-in then a session replacement drops the held nudge loudly, not silently`() {
    val f = Fixture().ready().speaking()

    // A task finishes while Sai is still talking, so the completion is held for the turn.
    val held = f.gate.injectNudge("complete", "[agent] the task finished")
    assertEquals(listOf("→ nudge: complete — held until the turn ends"), held.logs())
    assertTrue("nothing should go out mid-utterance", held.sent().isEmpty())

    // The user barges in, and then the token expires and the session is replaced before the turn
    // ever ended. The completion cannot be delivered — but it must not vanish without a trace.
    f.gate.onInterrupted()
    val reconnect = f.gate.onConnect()

    assertEquals(
        listOf("✗ nudge: dropping complete — session replaced"),
        reconnect.logs(),
    )
    // And it is really gone rather than lurking to be delivered into an unrelated later turn.
    f.gate.onSetupComplete()
    val laterTurnEnd = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(
        "a dropped nudge must not resurface in a later session",
        laterTurnEnd.sent().isEmpty(),
    )
  }

  @Test
  fun `a generation that ends without a turn does not flush a held nudge into its own speech`() {
    val f = Fixture().ready().speaking()

    f.gate.injectNudge("complete", "[agent] the task finished")

    // `generationComplete` with no `turnComplete` used to flush here. That is barge-in: Gemini Live
    // treats a client turn as interrupt, and this generation is often the function-call one, which
    // ends before it has said the sentence the nudge is trying not to talk over.
    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = false)

    assertTrue("must not SendTurn on generationComplete", ended.sent().isEmpty())
    assertTrue(ended.filterIsInstance<GateAction.TurnComplete>().isEmpty())
    assertTrue("it is still on the floor until turnComplete", f.gate.isModelSpeaking)

    // And the completion is not lost — it waits for the real turn boundary.
    val flushed = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(flushed.sent().single().endsWith("[agent] the task finished"))
    assertEquals(
        listOf("← nudge: delivering complete (held during the turn)"),
        flushed.logs(),
    )
  }

  // ── The in-flight window (device 2026-08-20) ─────────────────────────────────────────────────────

  @Test
  fun `a nudge sent moments after another is held, not fired into the turn it would cut off`() {
    // The greeting-plus-wake failure, exactly as it happened: the wake announcement landed ~200 ms
    // after the greeting on a hibernated machine, `modelSpeaking` was still false because no frame had
    // arrived yet, so the second nudge went straight out and interrupted the turn the first started.
    // `— barge-in —` before Sai had made a sound, on every call.
    val f = Fixture().ready()

    assertEquals(listOf("greeting"), f.gate.injectNudge("greeting", "greeting").sent())
    assertFalse("no frame has arrived, so this is not what protects the turn", f.gate.isModelSpeaking)

    f.now += 200
    val wake = f.gate.injectNudge("speak:machine-state", "waking")
    assertTrue("must not go out into an in-flight turn", wake.sent().isEmpty())
    assertEquals(listOf("→ nudge: speak:machine-state — held until the turn ends"), wake.logs())

    // And it is delivered on the turn boundary, not lost.
    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(ended.sent().single().endsWith("waking"))
  }

  @Test
  fun `a tool call holds the line the FSM speaks in reply to it`() {
    // The 2026-08-20 device call, where every single one of Sai's sentences was marked `— cut off —`.
    // The user speaks, so the turn is the MODEL's and no client turn armed the window; the model emits
    // `forwardToAgent` before it has produced a frame, so `modelSpeaking` is false too. The FSM then
    // answers that very forward with the queue-position line, and it went out into the turn that was
    // about to say "I'm on it" — the gate had no way to know a generation was underway.
    val f = Fixture().ready()
    f.gate.onToolCall()
    assertFalse("no frame has arrived, so this is not what protects the turn", f.gate.isModelSpeaking)

    val queued = f.gate.injectNudge("speak:queue-position", "[system] say you'll get to it after")
    assertTrue("must not cut off the turn that made the call", queued.sent().isEmpty())
    assertEquals(
        listOf("→ nudge: speak:queue-position — held until the turn ends"),
        queued.logs(),
    )

    // The function-call generation completing is NOT the moment to send it — that is the barge-in
    // in the device log. It waits for the spoken turn to actually end.
    assertTrue(
        "generationComplete after a tool call must not flush into the ack that has not started",
        f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = false).sent().isEmpty(),
    )

    // Delivered when the model finishes the sentence it was making the call in aid of — the whole
    // point being that it is late, not lost. (Sai did not speak this turn, so the FSM line is the
    // only acknowledgment the user will hear.)
    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(ended.sent().single().endsWith("say you'll get to it after"))
  }

  @Test
  fun `a queue-position held while Sai already spoke is dropped, not flushed into its own sentence`() {
    // Device 2026-08-20: the model said "I'll start that as soon as the downloads are done", the
    // FSM's verbatim queue line flushed on generationComplete, barged it off, and it said it again.
    val f = Fixture().ready()
    f.gate.onToolCall()
    f.gate.injectNudge("speak:queue-position", "[system] Say verbatim: I'll start that as soon as I'm done")
    // The function-call generation ends first, with no speech yet. Flushing here is the interrupt.
    assertTrue(f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = false).sent().isEmpty())
    f.gate.onSaiTranscript("I'll start that as soon as the downloads are done.")

    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = true)
    assertTrue("must not send a client turn into its own sentence", ended.sent().isEmpty())
    assertEquals(
        listOf("✗ nudge: dropping speak:queue-position — Sai already said it this turn"),
        ended.logs(),
    )
  }

  @Test
  fun `a cancel line held while Sai already spoke is dropped the same way`() {
    val f = Fixture().ready()
    f.gate.onToolCall()
    f.gate.injectNudge("speak", "[system] Say verbatim: that one hadn't started yet")
    f.gate.onSaiTranscript("No problem, that one hadn't started yet.")

    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(ended.sent().isEmpty())
    assertEquals(
        listOf("✗ nudge: dropping speak — Sai already said it this turn"),
        ended.logs(),
    )
  }

  @Test
  fun `a completion still flushes after Sai spoke, even if a queue-position is dropped`() {
    val f = Fixture().ready().speaking()
    f.gate.injectNudge("speak:queue-position", "queue-fallback-line")
    f.gate.injectNudge("complete", "[agent] the downloads finished")
    val ended = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(ended.sent().single().contains("the downloads finished"))
    assertFalse(ended.sent().single().contains("queue-fallback-line"))
    assertTrue(ended.logs().any { it.contains("dropping speak:queue-position") })
  }

  @Test
  fun `a completion arriving after generationComplete is held until the turn actually ends`() {
    // The third barge-in in the same device call: generationComplete had cleared modelSpeaking, so
    // the completion went out as `→ nudge: complete` while it was still reading the download result.
    val f = Fixture().ready().speaking()
    f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = false)
    assertTrue("generationComplete must not open the floor", f.gate.isModelSpeaking)

    val arriving = f.gate.injectNudge("complete", "[agent] the downloads finished")
    assertTrue(arriving.sent().isEmpty())
    assertEquals(listOf("→ nudge: complete — held until the turn ends"), arriving.logs())

    val flushed = f.gate.onGenerationOrTurnEnd(generationEnded = false, turnEnded = true)
    assertTrue(flushed.sent().single().contains("the downloads finished"))
  }

  @Test
  fun `a tool call cannot wedge the gate either`() {
    // Same deadline, same reason: `endCall` is a tool call with no speech behind it, and a flag set
    // here and never cleared would defer every later nudge for the rest of the call.
    val f = Fixture().ready()
    f.gate.onToolCall()

    f.now += LiveTurnGate.AWAIT_MODEL_MS
    assertEquals(listOf("later"), f.gate.injectNudge("later", "later").sent())
  }

  @Test
  fun `the in-flight window expires, so a turn that produced nothing cannot wedge the gate`() {
    // The counterpart to the recorded `modelSpeaking` bug: a hold that cannot expire is how every
    // later nudge dies silently. This one is a deadline, so the worst case is one held nudge.
    val f = Fixture().ready()
    f.gate.injectNudge("greeting", "greeting")

    f.now += LiveTurnGate.AWAIT_MODEL_MS
    assertEquals(listOf("later"), f.gate.injectNudge("later", "later").sent())
  }

  @Test
  fun `a frame from the model closes the window early`() {
    // The window is a fallback for silence, not a fixed delay: once the model demonstrably answered,
    // `modelSpeaking` is the accurate gate and the deadline must stop mattering.
    val f = Fixture().ready()
    f.gate.injectNudge("greeting", "greeting")
    f.gate.onGenerationOrTurnEnd(generationEnded = true, turnEnded = true)

    f.now += 10 // well inside AWAIT_MODEL_MS
    assertEquals(listOf("next"), f.gate.injectNudge("next", "next").sent())
  }

  @Test
  fun `the opening greeting is sent even if another turn is already in flight`() {
    // Holding the greeting behind a turn that never ends is a connected call that never speaks.
    val f = Fixture().ready()
    f.gate.injectNudge("notice", "waking")
    f.now += 200
    assertEquals(listOf("greeting"), f.gate.injectNudge("greeting", "greeting").sent())
  }

  @Test
  fun `the opening greeting is sent only once per session`() {
    val f = Fixture().ready()
    assertEquals(listOf("greeting"), f.gate.injectNudge("greeting", "greeting").sent())
    val again = f.gate.injectNudge("greeting", "greeting again")
    assertTrue(again.sent().isEmpty())
    assertEquals(listOf("→ nudge: greeting — already sent this session"), again.logs())
  }

  @Test
  fun `a greeting injected before setup survives a reconnect`() {
    val f = Fixture()
    f.gate.onConnect()
    assertTrue(f.gate.injectNudge("greeting", "greeting").sent().isEmpty())
    f.gate.onConnect()
    val setup = f.gate.onSetupComplete()
    assertTrue("the new session must still get the greeting", setup.sent().any { it.contains("greeting") })
  }

  // ── Nudge gating ─────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `an idle ready session sends a nudge straight through`() {
    val f = Fixture().ready()
    val actions = f.gate.injectNudge("notice", "[agent] your machine is waking")
    assertEquals(listOf("→ nudge: notice"), actions.logs())
    assertEquals(listOf("[agent] your machine is waking"), actions.sent())
  }

  @Test
  fun `a low-value nudge marked dropIfBusy is dropped rather than queued behind the turn`() {
    val f = Fixture().ready().speaking()
    val actions = f.gate.injectNudge("step-failed", "[agent] a step failed", dropIfBusy = true)
    assertEquals(listOf("→ nudge: step-failed — dropped (mid-utterance)"), actions.logs())
    assertTrue(actions.sent().isEmpty())
    // Dropped means dropped: the turn ending must not deliver it late.
    assertTrue(f.gate.onGenerationOrTurnEnd(false, turnEnded = true).sent().isEmpty())
  }

  @Test
  fun `several nudges held across one turn are delivered once, in order, as a single turn`() {
    val f = Fixture().ready().speaking()
    f.gate.injectNudge("complete", "first")
    f.gate.injectNudge("notice", "second")

    val ended = f.gate.onGenerationOrTurnEnd(false, turnEnded = true)

    // Still ONE turn with both bodies in order — now behind the held-nudge preamble, which is what
    // stops a result the model already delivered in the turn just ended being read out a second time.
    val sent = ended.sent().single()
    assertTrue(sent.startsWith("[system] What follows arrived while you were still speaking"))
    assertTrue("the bodies keep their order, after the preamble", sent.endsWith("first\n\nsecond"))
    assertEquals(
        listOf("← nudge: delivering complete, notice (held during the turn)"),
        ended.logs(),
    )
  }

  @Test
  fun `a nudge held behind a turn is warned that the turn may already have covered it`() {
    // Live, 2026-08-19: a completion landed while Sai was answering "what's going on with all that?" —
    // a turn in which it had already fetched and reported that same result through getSaiStatus. The
    // completion flushed when the turn ended and was delivered anyway, so the user heard the same
    // correction twice in consecutive breaths.
    val held = Fixture().ready().speaking()
    held.gate.injectNudge("complete", "[agent] the task finished")
    val flushed = held.gate.onGenerationOrTurnEnd(false, turnEnded = true).sent().single()
    assertTrue(flushed.contains("do NOT say it again"))

    // …and NOT on the prompt path: a nudge delivered while the model is idle has no turn behind it to
    // have repeated, and telling it "you may have already said this" there invites silence exactly
    // where speech is correct.
    val prompt = Fixture().ready()
    val direct = prompt.gate.injectNudge("complete", "[agent] the task finished").sent().single()
    assertEquals("[agent] the task finished", direct)
  }

  @Test
  fun `a nudge injected before setup is held for the session, not fired at a socket that cannot take it`() {
    val f = Fixture()
    f.gate.onConnect() // connected, but setupComplete has not landed

    val early = f.gate.injectNudge("muted", "[system] you are muted")
    assertEquals(listOf("→ nudge: muted — held until the session is ready"), early.logs())
    assertTrue(early.sent().isEmpty())

    val setup = f.gate.onSetupComplete()
    assertEquals(listOf("[system] you are muted"), setup.sent())
    assertTrue(
        setup.logs().contains("← nudge: delivering muted (held until the session was ready)"),
    )
  }

  // ── Session state (mute) ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `sticky session state is re-asserted on every new session`() {
    val f = Fixture().ready()
    f.gate.injectSessionState("muted", "[system] you are muted", sticky = true)

    // A token-expiry reconnect: the fresh session knows nothing, so mute has to be restated.
    f.gate.onConnect()
    val setup = f.gate.onSetupComplete()

    assertEquals(listOf("[system] you are muted"), setup.sent())
    assertTrue(setup.logs().contains("→ nudge: muted (re-asserted for this session)"))
  }

  @Test
  fun `unmuting clears the sticky state so the next session is not told it is muted`() {
    val f = Fixture().ready()
    f.gate.injectSessionState("muted", "[system] you are muted", sticky = true)
    f.gate.injectSessionState("unmuted", "[system] you are unmuted", sticky = false)

    f.gate.onConnect()
    val setup = f.gate.onSetupComplete()

    assertTrue("a cleared state must not be re-asserted", setup.sent().isEmpty())
  }

  @Test
  fun `session state injected before setup is not also buffered, so it arrives once`() {
    val f = Fixture()
    f.gate.onConnect()
    // Mute during the second or two the call takes to connect.
    f.gate.injectSessionState("muted", "[system] you are muted", sticky = true)

    val setup = f.gate.onSetupComplete()

    // Exactly once — via the re-assertion, not also via the pre-connect buffer.
    assertEquals(listOf("[system] you are muted"), setup.sent())
  }

  // ── Barge-in ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a barge-in flushes playback and discards stragglers for the window, then stops`() {
    val f = Fixture().ready().speaking()

    val actions = f.gate.onInterrupted()

    assertTrue(actions.contains(GateAction.FlushPlayback))
    assertFalse("an interrupted turn is over", f.gate.isModelSpeaking)
    assertTrue("stragglers arrive for a beat after the interrupt", f.gate.shouldDiscardAudio())

    f.now += LiveTurnGate.INTERRUPT_DISCARD_MS - 1
    assertTrue("still inside the window", f.gate.shouldDiscardAudio())

    f.now += 2
    assertFalse("past the window, real audio must play again", f.gate.shouldDiscardAudio())
  }

  @Test
  fun `a completion landing during a barge-in is delivered, not held behind a turn nobody will end`() {
    val f = Fixture().ready().speaking()

    // The user cuts Sai off, and the task they were waiting on finishes in that same moment. The
    // interrupt ended the turn, so there is nothing to hold this behind — it must go out.
    f.gate.onInterrupted()
    val actions = f.gate.injectNudge("complete", "[agent] the task finished")

    assertEquals(listOf("[agent] the task finished"), actions.sent())
    assertEquals(listOf("→ nudge: complete"), actions.logs())
  }

  @Test
  fun `a fresh connect clears a stale discard window`() {
    val f = Fixture().ready().speaking()
    f.gate.onInterrupted()
    assertTrue(f.gate.shouldDiscardAudio())

    f.gate.onConnect()

    assertFalse("a new session must not start by throwing its first audio away", f.gate.shouldDiscardAudio())
  }

  // ── Transcript assembly ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `a placeholder turn is withheld and reported once, rather than spoken`() {
    val f = Fixture().ready()

    val withheld = f.gate.onSaiTranscript("Empty-Response")
    assertTrue("a mechanical placeholder is not speech", withheld.transcripts().isEmpty())

    val ended = f.gate.onGenerationOrTurnEnd(false, turnEnded = true)
    assertEquals(
        listOf("✗ dropped a placeholder turn (\"Empty-Response\") — not speech"),
        ended.logs().filter { it.startsWith("✗ dropped") },
    )
  }

  @Test
  fun `a turn that only looks placeholder-shaped at first is released in full`() {
    val f = Fixture().ready()

    // "Empty" alone matches the placeholder test; the sentence it turns into does not, and the
    // opening word must not be lost.
    assertTrue(f.gate.onSaiTranscript("Empty").transcripts().isEmpty())
    val released = f.gate.onSaiTranscript("-handed, sorry")

    assertEquals(listOf("Empty-handed, sorry"), released.transcripts())
    assertTrue("real speech marks the turn as spoken", f.gate.didSpeakThisTurn)
  }

  @Test
  fun `a turn that heard the user and said nothing is reported`() {
    val f = Fixture().ready()
    f.gate.onUserTranscript("is that the one from yesterday?")

    val ended = f.gate.onGenerationOrTurnEnd(false, turnEnded = true)

    assertTrue(
        ended.logs().contains("— no reply to that (Sai may have judged it wasn't meant for it) —"),
    )
  }

  @Test
  fun `silence a nudge asked for is not reported as Sai ignoring the user`() {
    val f = Fixture().ready()
    // The mute nudge explicitly asks for silence, so a quiet turn after it is obedience.
    f.gate.injectNudge("muted", "[system] you are muted")
    f.gate.onUserTranscript("something overheard")

    val ended = f.gate.onGenerationOrTurnEnd(false, turnEnded = true)

    assertFalse(
        "the silence is ours, not Sai's — reporting it invents a fault",
        ended.logs().any { it.startsWith("— no reply") },
    )
  }

  @Test
  fun `turn state resets at the turn boundary`() {
    val f = Fixture().ready()
    f.gate.onSaiTranscript("all done")
    assertTrue(f.gate.didSpeakThisTurn)

    f.gate.onGenerationOrTurnEnd(false, turnEnded = true)

    assertFalse("a new turn starts having said nothing", f.gate.didSpeakThisTurn)
  }

  // ── Tasks that wait on a photo ───────────────────────────────────────────────────────────────────

  private fun call(name: String) = JSONObject().put("kind", name).put("text", "order it")

  @Test
  fun `a task that asked for the photo is held, and told plainly it has not started`() {
    val f = Fixture().ready()
    f.gate.onCaptureStarted()

    val routing =
        f.gate.routeTaskCall("forwardToAgent", call("forwardToAgent"), wantsPhoto = true, hasCapture = false)

    routing as TaskRouting.HeldForPhoto
    assertEquals("held-for-photo", routing.response.getString("result"))
    // The wording is the whole point: a model told only "held" reports the task as running,
    // and a model handed "waiting for the glasses photo" spoke that as camera-wait narration.
    val note = routing.response.getString("note")
    assertEquals(CaptureNotes.HELD_FOR_PHOTO, note)
    assertTrue(note.contains("NOT started yet"))
    assertTrue(note.contains("Do not claim it is running"))
    assertTrue("the wait itself must stay silent", note.contains("do not speak this note"))
    assertEquals("⏸ holding forwardToAgent (it asked for the photo) until the capture resolves", routing.log)
  }

  @Test
  fun `a task that did not ask for the photo goes out during a capture, and says so`() {
    val f = Fixture().ready()
    f.gate.onCaptureStarted()

    // Asking for the weather while the camera is working is not a vision task, and holding it is how
    // it used to get silently dropped when the capture failed.
    val routing =
        f.gate.routeTaskCall("forwardToAgent", call("forwardToAgent"), wantsPhoto = false, hasCapture = false)

    routing as TaskRouting.Emit
    assertEquals("→ effect: forwardToAgent (during a capture, but it didn't ask for the photo)", routing.log)
  }

  @Test
  fun `a capture in the same batch holds the task even before the capture is flagged in flight`() {
    val f = Fixture().ready()
    // The forward can be processed BEFORE the captureImage beside it in the same batch.
    val routing =
        f.gate.routeTaskCall("forwardToAgent", call("forwardToAgent"), wantsPhoto = true, hasCapture = true)
    assertTrue(routing is TaskRouting.HeldForPhoto)
  }

  @Test
  fun `settling a capture releases the tasks that were waiting on it`() {
    val f = Fixture().ready()
    f.gate.onCaptureStarted()
    f.gate.routeTaskCall("forwardToAgent", call("forwardToAgent"), wantsPhoto = true, hasCapture = false)
    f.gate.routeTaskCall("enqueue", call("enqueue"), wantsPhoto = true, hasCapture = false)

    val released = f.gate.onCaptureSettled()

    assertEquals(listOf("forwardToAgent", "enqueue"), released.names)
    assertEquals(2, released.effects.length())
    assertFalse(f.gate.isCaptureInFlight)
    // Taken, not copied — a second settle must not release them again.
    assertEquals(0, f.gate.onCaptureSettled().effects.length())
  }

  @Test
  fun `several calls coalescing onto one capture speak its outcome once`() {
    val f = Fixture().ready()
    f.gate.onCaptureStarted()
    f.gate.onCaptureStarted() // a second captureImage lands on the running one

    assertTrue("the first responder speaks", f.gate.claimOutcomeNudge())
    assertFalse("the second must not tell Sai the same thing twice", f.gate.claimOutcomeNudge())
  }

  @Test
  fun `a new capture may speak its own outcome`() {
    val f = Fixture().ready()
    f.gate.onCaptureStarted()
    assertTrue(f.gate.claimOutcomeNudge())
    f.gate.onCaptureSettled()

    f.gate.onCaptureStarted()

    assertTrue("a fresh capture is a fresh outcome", f.gate.claimOutcomeNudge())
  }
}
