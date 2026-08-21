/* sai-fi — voice concierge. */

// LiveTurnGate — what the Live session does with a nudge, a tool call, and a turn boundary.
//
// Extracted from GeminiLiveClient, which kept this state inline and could therefore never be tested:
// that class is a WebSocket, an android.util.Base64 and an android.util.Log, so no JVM test could
// reach the decisions buried in it. The decisions are the part that matters — they decide whether a
// completion ever reaches the user — and two bugs are on record here:
//
//   - a nudge held for a turn that never ended (a barge-in, then a token-expiry reconnect) died
//     without a trace, which is one candidate cause for a completion the user never heard;
//   - flushing a held nudge on `generationComplete` (not `turnComplete`) barged Sai off its own
//     sentence: the function-call generation ends before it speaks, and a client turn is interrupt.
//
// Both are barge-in ⇄ queue interactions, and neither had a test until this class existed. Same
// precedent as AgentEventRouter: a pure decision, so it can be tested without a device.
//
// **Nothing here does I/O.** Every method returns the [GateAction]s the caller should perform, in
// order, and GeminiLiveClient is the interpreter that performs them. That keeps the ordering between
// a log line and the turn it describes explicit rather than incidental — and it is what lets a test
// (or the conversation harness) run the whole machine with no socket at all.
//
// The field modifiers are a deliberate LITERAL copy of what GeminiLiveClient had: the same
// `@Volatile`s, the same `synchronized` blocks on the same collections, the same AtomicBoolean. The
// reader thread / main thread split did not change, so neither should the memory semantics — this is
// a move, not a rewrite.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** One thing the gate wants its caller to do. Perform them in the order returned. */
sealed interface GateAction {
  /** Send [text] to the model as a complete client turn. */
  data class SendTurn(val text: String) : GateAction

  /** Write [text] to the UI/event log. These strings are load-bearing — ON_DEVICE_CHECK greps them. */
  data class Log(val text: String) : GateAction

  /** Emit a transcript delta from Sai (role `sai`). */
  data class SaiTranscript(val text: String) : GateAction

  /** Emit a transcript delta from the user (role `you`). */
  data class UserTranscript(val text: String) : GateAction

  /** The model's turn ended — finalize the current transcript entry. */
  data object TurnComplete : GateAction

  /** The user barged in — flush queued playback immediately. */
  data object FlushPlayback : GateAction
}

/** What [LiveTurnGate.routeTaskCall] decided about a task-starting tool call. */
sealed interface TaskRouting {
  /** Forward it now, as an effect. [log] names the one case the narrowed gate can get wrong. */
  data class Emit(val log: String) : TaskRouting

  /** Held until the capture resolves; answer the model with this truthful tool response. */
  data class HeldForPhoto(val response: JSONObject, val log: String) : TaskRouting
}

/** Everything released when a capture settles. */
data class ReleasedTasks(val effects: JSONArray, val names: List<String>)

class LiveTurnGate(private val now: () -> Long = { System.currentTimeMillis() }) {

  // Nudge gating: server-pushed nudges make the model talk; firing one mid-utterance cuts it off, so
  // defer until the turn ends (or drop it, for a low-value nudge the caller marks dropIfBusy).
  @Volatile private var modelSpeaking = false
  /**
   * A client turn has been sent and the model has not answered it yet.
   *
   * [modelSpeaking] only goes true once the model's FIRST frame arrives, which leaves a few hundred ms
   * in which a turn is genuinely in flight and the gate believes nothing is happening. A second nudge
   * injected into that window cuts off the turn the first one started. Device 2026-08-20: the wake
   * announcement fired ~200 ms after the greeting on a hibernated machine and produced `— barge-in —`
   * before Sai had made a sound, on every call.
   *
   * Held as a DEADLINE rather than a flag on purpose. The recorded failure in this file's header is a
   * `modelSpeaking` that stuck true and silently deferred every later nudge for the rest of the call;
   * a timestamp cannot stick, so the worst case here is a nudge held for [AWAIT_MODEL_MS] and then
   * sent, rather than one held forever.
   */
  @Volatile private var awaitingModelUntil = 0L
  // A glasses capture is running. Task-starting effects are held for its duration — not just within
  // the batch that triggered it, since the model can now speak first and forward a beat later.
  @Volatile private var captureInFlight = false
  // Whether the CURRENT capture's outcome has already been relayed to the model — several tool calls
  // can share one capture and one result.
  private val outcomeNudged = AtomicBoolean(false)
  // Did this turn hear the user, and did Sai answer? A turn with the first and not the second is Sai
  // correctly ignoring speech that wasn't for it — worth a log line, since silence otherwise reads
  // as a fault. Reset at each turn boundary.
  @Volatile private var heardUserSinceLastTurn = false
  @Volatile private var spokeThisTurn = false
  /**
   * Sai's transcript for the CURRENT turn, and whatever of it we are withholding.
   *
   * A turn whose entire text is a mechanical placeholder ("Empty-Response", "No response received.")
   * is not speech — see [isPlaceholderSpeech]. Deltas arrive in fragments, so the test has to run
   * against the accumulated turn rather than each delta: `Empty-` on its own matches nothing. What is
   * withheld is kept, so a turn that STARTS placeholder-shaped and then turns into real speech is
   * released in full instead of losing its opening words.
   */
  @Volatile private var saiTurn = ""
  @Volatile private var withheld = ""
  /** A nudge in this turn explicitly asked Sai not to speak, so silence here is instructed, not judged. */
  @Volatile private var silenceWasRequested = false
  private val heldTaskEffects = mutableListOf<JSONObject>()
  private val heldTaskNames = mutableListOf<String>()
  // Set on barge-in: ignore audio of the interrupted turn that is still arriving.
  @Volatile private var discardAudioUntil = 0L
  // Deferred nudges keep their kind alongside the body, so the log can name what was held.
  private val deferredNudges = mutableListOf<Pair<String, String>>()
  // Nudges injected before setupComplete, replayed the moment it lands. Distinct from
  // deferredNudges (which waits on a TURN ending, not on the session existing): a call can be muted
  // in the second before it connects, and that state has to survive the wait.
  private val preConnectNudges = mutableListOf<Pair<String, String>>()
  /**
   * A nudge describing session-level STATE rather than an event — mute is the only one. Every fresh
   * Live session (initial connect, token-expiry reconnect, resume-after-pause) starts knowing nothing,
   * so this is re-asserted at each setupComplete and needs no pre-connect buffering: being told at
   * setup IS the delivery. Null when there is no such state to carry.
   */
  @Volatile private var sessionState: Pair<String, String>? = null
  // setupComplete has landed on the CURRENT socket, so a client turn is deliverable. Cleared on
  // connect and close: a nudge injected between them would be sent ahead of the setup frame.
  @Volatile private var ready = false
  /** The opening greeting already went out on THIS socket. Cleared on connect so a reconnect can retry. */
  @Volatile private var greetingSentThisSession = false

  // ── Queries ──────────────────────────────────────────────────────────────────────────────────────

  /** Mid-utterance: a nudge sent now would cut the model off. */
  val isModelSpeaking: Boolean
    get() = modelSpeaking

  /** A capture is running, so a task that asked for the photo must wait for it. */
  val isCaptureInFlight: Boolean
    get() = captureInFlight

  /** Did Sai produce speech in the current turn? The hang-up guard reads this. */
  val didSpeakThisTurn: Boolean
    get() = spokeThisTurn

  /**
   * Straggler audio from a turn the user just barged in on should be dropped.
   *
   * Evaluate ONCE per serverContent frame and reuse the answer for every part in it, as the original
   * did — re-reading the clock per part could split one frame across the window boundary.
   */
  fun shouldDiscardAudio(): Boolean = now() < discardAudioUntil

  // ── Session lifecycle ────────────────────────────────────────────────────────────────────────────

  /**
   * A fresh socket is being opened (initial connect, or a reconnect replacing the old one).
   *
   * Fresh session ⇒ fresh turn state, which is also correct on a reconnect: the old turn is gone.
   */
  fun onConnect(): List<GateAction> {
    modelSpeaking = false
    awaitingModelUntil = 0L
    ready = false
    greetingSentThisSession = false
    discardAudioUntil = 0L
    saiTurn = ""
    withheld = ""
    val actions = mutableListOf<GateAction>()
    synchronized(deferredNudges) {
      // Say what is being thrown away. A nudge held for a turn that never ended (a barge-in, then a
      // token-expiry reconnect) died here without a trace, which is one candidate cause for a
      // completion the user never heard.
      //
      // The greeting is not dropped: it is the turn that starts the call, and a reconnect before Sai
      // has spoken is exactly when it has to go out again. preConnectNudges already survive; move a
      // held greeting there so setupComplete delivers it instead of leaving the new session silent.
      val greeting = deferredNudges.filter { it.first == "greeting" }
      val others = deferredNudges.filter { it.first != "greeting" }
      deferredNudges.clear()
      if (others.isNotEmpty()) {
        actions +=
            GateAction.Log(
                "✗ nudge: dropping ${others.joinToString(", ") { it.first }} — session replaced")
      }
      if (greeting.isNotEmpty()) {
        synchronized(preConnectNudges) { preConnectNudges.addAll(greeting) }
        actions += GateAction.Log("→ nudge: greeting — carried to the new session")
      }
    }
    // preConnectNudges deliberately SURVIVES a reconnect: it holds session-level state (mute) that a
    // fresh Live session needs re-asserted anyway, and the reconnect is exactly when it's re-injected.
    return actions
  }

  /**
   * setupComplete landed: client turns are deliverable from here.
   *
   * Session state goes out BEFORE anything that was waiting on the session — the greeting is injected
   * from onReady, and a mute asserted while connecting has to reach the model first, or Sai is told
   * to greet, then told to be silent, and obeys the last thing it read.
   */
  fun onSetupComplete(): List<GateAction> {
    ready = true
    val actions = mutableListOf<GateAction>()
    actions += GateAction.Log("live: setup complete — start talking")
    sessionState?.let {
      actions += injectNudge("${it.first} (re-asserted for this session)", it.second)
    }
    actions += flushPreConnectNudges()
    return actions
  }

  /** The socket is being closed deliberately. */
  fun onClose() {
    ready = false
  }

  // ── Model output ─────────────────────────────────────────────────────────────────────────────────

  /**
   * The user barged in.
   *
   * Flushing the track only empties what's already queued. Audio chunks of the interrupted turn that
   * were ALREADY in flight keep arriving in the next few messages, get written, and refill it — so
   * Sai talked straight through the barge-in even though the interrupt fired. Drop the stragglers for
   * a beat. Safe against clipping the reply: the model only starts speaking again after end-of-speech
   * plus silenceDurationMs (1.2 s), well past this window.
   */
  fun onInterrupted(): List<GateAction> {
    modelSpeaking = false
    awaitingModelUntil = 0L
    discardAudioUntil = now() + INTERRUPT_DISCARD_MS
    return listOf(GateAction.FlushPlayback)
  }

  /** A user-speech transcription delta arrived. */
  fun onUserTranscript(text: String): List<GateAction> {
    heardUserSinceLastTurn = true
    return listOf(GateAction.UserTranscript(text))
  }

  /**
   * Forward a transcript delta from Sai, unless the turn so far is only a placeholder.
   *
   * Withholding rather than dropping matters: the test is against the accumulated turn, so the first
   * fragment of a real sentence can look placeholder-shaped for one frame ("Empty" before
   * "Empty-handed, sorry"). Anything held back is released the moment the turn stops matching.
   */
  fun onSaiTranscript(delta: String): List<GateAction> {
    saiTurn += delta
    if (isPlaceholderSpeech(saiTurn)) {
      withheld += delta
      return emptyList()
    }
    val out = withheld + delta
    withheld = ""
    modelSpeaking = true
    awaitingModelUntil = 0L
    spokeThisTurn = true
    return listOf(GateAction.SaiTranscript(out))
  }

  /** An audio part was accepted for playback (not discarded) — the model is mid-turn. */
  fun onAudioAccepted() {
    modelSpeaking = true
    awaitingModelUntil = 0L // it answered; `modelSpeaking` is the accurate gate from here
  }

  /**
   * A tool call arrived — which is PROOF the model is mid-turn, and the gate had no other way to know.
   *
   * This is the hole every `— barge-in —` in the 2026-08-20 device log came through. A `toolCall`
   * frame is emitted DURING a generation and normally arrives BEFORE the model's first audio or
   * transcript, so at that instant `modelSpeaking` is still false; and the turn was started by the
   * user speaking, not by a client turn, so `awaitingModelUntil` was never armed either. The FSM then
   * reacts to that very tool call — synchronously, by design — and its spoken line goes out as a
   * client turn straight into the gap. Every "on it, I'll get to the other thing after" the user heard
   * was cut off by the queue-position line that was meant to follow it.
   *
   * It also covers the model RESUMING after the tool response, which is the same window a second time:
   * the response goes out microseconds from here, and the deadline is generous enough to span both.
   *
   * A deadline rather than `modelSpeaking = true`, for the reason in [awaitingModelUntil]'s KDoc: a
   * tool call is not a promise of speech (an `endCall` may be the end of the conversation), and a flag
   * set here and never cleared would defer every later nudge for the rest of the call — the exact
   * failure recorded in this file's header.
   */
  fun onToolCall() {
    awaitingModelUntil = now() + AWAIT_MODEL_MS
  }

  /**
   * A generation or a turn ended.
   *
   * Only [turnEnded] is a flush point. `generationComplete` is not: Gemini Live treats a client turn
   * as barge-in, and a generation ending is often the function-call generation, which completes
   * BEFORE the model speaks the ack. Flushing then cuts that ack off. Device 2026-08-20, three
   * times in one call:
   *
   *   speak:queue-position held → generationComplete → "Sure, I'll start…" cut off → same line again;
   *   speak (cancel) held → "No problem, that one hadn't started yet" cut off → same line again;
   *   complete sent because generationComplete had already cleared [modelSpeaking] while it was
   *   still reading the download result.
   *
   * The comment that used to live here ("audio already queued still plays in order") is false on
   * this API. Completions wait for `turnComplete` (or interrupt / reconnect). A late result is
   * better than Sai interrupting itself.
   */
  fun onGenerationOrTurnEnd(generationEnded: Boolean, turnEnded: Boolean): List<GateAction> {
    val actions = mutableListOf<GateAction>()
    if (turnEnded) {
      // Snapshot BEFORE clearing: a verbatim `speak` / `speak:*` held for this turn is a fallback
      // for silence. If Sai already produced audio or a transcript, flushing that line as a client
      // turn barges it off its own sentence and it says the same thing twice.
      val alreadySpoke = spokeThisTurn || modelSpeaking
      modelSpeaking = false
      awaitingModelUntil = 0L
      actions += flushNudges(alreadySpoke)
      actions += GateAction.TurnComplete
    } else if (generationEnded) {
      // Deliberately a no-op on the gate. Do not clear [modelSpeaking] or [awaitingModelUntil]:
      // generationComplete after a tool call is exactly when that window has to cover speech that
      // has not started yet. Clearing it is how a held nudge walked into its next sentence.
    }
    // A turn that heard the user and said nothing is worth a line: an ignored side conversation used
    // to look exactly like a swallowed utterance or a wedged session.
    //
    // It states the FACT and not a motive, because the first version guessed at one and guessed wrong:
    // it read "stayed silent — judged it wasn't for Sai" over a turn where Sai had been explicitly
    // TOLD to stay silent (the ask-first completion nudge) and then answered the user perfectly well
    // in the very next turn. A log line that invents a reason is worse than one that reports what
    // happened, so it now reports what happened — and says nothing at all when we know a nudge asked
    // for the silence, since in that case the silence is ours, not Sai's.
    if (turnEnded) {
      // A withheld placeholder that never became speech: say so once, here, rather than per delta.
      // Silent suppression would trade a visible wrong line for an invisible one, and this is the
      // evidence that says whether the model or the API produced it (see isPlaceholderSpeech).
      if (withheld.isNotEmpty()) {
        actions += GateAction.Log("✗ dropped a placeholder turn (\"${withheld.trim()}\") — not speech")
      }
      if (heardUserSinceLastTurn && !spokeThisTurn && !silenceWasRequested) {
        actions += GateAction.Log("— no reply to that (Sai may have judged it wasn't meant for it) —")
      }
      heardUserSinceLastTurn = false
      spokeThisTurn = false
      silenceWasRequested = false
      saiTurn = ""
      withheld = ""
    }
    return actions
  }

  // ── Nudges (agent → model), gated so they don't cut the model off mid-sentence ───────────────────

  /**
   * Inject a nudge as a user turn. Deferred if the model is speaking (unless [dropIfBusy]).
   *
   * [kind] is a short tag for the log — "complete", "muted", "capture-retry". EVERY outcome is logged,
   * because a nudge has four of them (sent, held, dropped, discarded) and until this existed the log
   * showed none: an agent event appeared as `✓ done: …` and then whatever Sai said next, with no way
   * to tell a nudge Sai ignored from one that never reached it.
   *
   * The nudge BODY is deliberately never logged: it carries agent-derived text (summaries, page
   * content) and this log is mirrored to a projector.
   */
  fun injectNudge(kind: String, turns: String, dropIfBusy: Boolean = false): List<GateAction> {
    // `awaitingModelUntil` covers the window a turn is in flight but has produced nothing yet — see
    // its KDoc. Without it two nudges sent a few hundred ms apart are not gated against each other at
    // all, and the second interrupts the first.
    //
    // The OPENING GREETING is the exception. It is the turn that starts the call; holding it behind
    // another in-flight turn (or behind a turn that never ends) is how a call connects and then sits
    // silent. Device 2026-08-20: no `→ nudge: greeting` SendTurn, then no hello either — Gemini Live
    // stays quiet until it gets a client turn. Wake/notices wait; the greeting does not.
    val isGreeting = kind == "greeting"
    if (!isGreeting && (modelSpeaking || now() < awaitingModelUntil)) {
      if (dropIfBusy) {
        return listOf(GateAction.Log("→ nudge: $kind — dropped (mid-utterance)"))
      }
      // A tagged line replaces a held one about the same subject. Two nudges are only ever merged
      // into one turn, so without this the model is handed two "say this verbatim" commands at once
      // and reads out both — and when the second exists precisely because the first stopped being
      // true, that is a contradiction in a single breath. See VoiceChannel.say's `supersedes`.
      val replaced =
          synchronized(deferredNudges) {
            val stale = kind.contains(':') && deferredNudges.any { it.first == kind }
            if (stale) deferredNudges.removeAll { it.first == kind }
            deferredNudges.add(kind to turns)
            stale
          }
      return listOf(
          GateAction.Log(
              if (replaced) "→ nudge: $kind — held until the turn ends (replacing the stale one)"
              else "→ nudge: $kind — held until the turn ends"))
    }
    // A client turn before setup completes is not deliverable: the socket may not exist yet, and even
    // an open one must receive the setup frame first, so anything sent ahead of it is at best racing
    // that frame. HOLD it and send it the moment setup lands, rather than dropping it: muting during
    // the second or two a call takes to connect used to leave the model unaware it was muted for the
    // whole call, because MUTED_NUDGE went to a null socket and nothing said so.
    if (!ready) {
      // Session state needs no buffer entry — setupComplete re-asserts it by definition, and buffering
      // it too would deliver the same instruction twice at the start of the call.
      if (sessionState?.first == kind) {
        return listOf(GateAction.Log("→ nudge: $kind — will be asserted when the session is ready"))
      }
      synchronized(preConnectNudges) { preConnectNudges.add(kind to turns) }
      return listOf(GateAction.Log("→ nudge: $kind — held until the session is ready"))
    }
    if (isGreeting && greetingSentThisSession) {
      return listOf(GateAction.Log("→ nudge: greeting — already sent this session"))
    }
    // These are the nudges that ask for silence; a quiet turn after one of them is obedience, and
    // must not be reported as Sai judging the speech wasn't for it.
    if (kind.startsWith("muted") || kind.startsWith("complete (ask-first")) silenceWasRequested = true
    // Armed as the turn goes out, so the next nudge is gated against it even though the model has not
    // said anything yet.
    if (isGreeting) greetingSentThisSession = true
    awaitingModelUntil = now() + AWAIT_MODEL_MS
    return listOf(GateAction.Log("→ nudge: $kind"), GateAction.SendTurn(turns))
  }

  /**
   * Inject a nudge AND record whether it describes state the next Live session must be told about.
   *
   * `sticky = true` keeps it (mute); `false` clears whatever was kept (unmute). This replaces the
   * caller having to re-assert mute itself on every connect — it did, from the greeting path, which
   * is why the greeting could then override it.
   */
  fun injectSessionState(kind: String, turns: String, sticky: Boolean): List<GateAction> {
    sessionState = if (sticky) kind to turns else null
    return injectNudge(kind, turns)
  }

  /** Deliver anything injected before the session was ready, oldest first, as one turn. */
  private fun flushPreConnectNudges(): List<GateAction> {
    val pending =
        synchronized(preConnectNudges) {
          if (preConnectNudges.isEmpty()) return emptyList()
          val kinds = preConnectNudges.joinToString(", ") { it.first }
          val joined = preConnectNudges.joinToString("\n\n") { it.second }
          preConnectNudges.clear()
          kinds to joined
        }
    if (pending.first.split(", ").any { it.trim() == "greeting" }) greetingSentThisSession = true
    return listOf(
        GateAction.Log("← nudge: delivering ${pending.first} (held until the session was ready)"),
        GateAction.SendTurn(pending.second),
    )
  }

  /** FSM `say` lines: a fallback if the model stayed quiet. Not new information. */
  private fun isVerbatimSpeak(kind: String) = kind == "speak" || kind.startsWith("speak:")

  private fun flushNudges(alreadySpoke: Boolean = false): List<GateAction> {
    val droppedKinds = mutableListOf<String>()
    val pending =
        synchronized(deferredNudges) {
          if (alreadySpoke) {
            // Every LiveVoiceChannel.say is kind `speak` or `speak:…`. Those lines exist so a silent
            // turn still acknowledges the queue/cancel; if it already said it, sending them is the
            // self-interrupt in the device log. Completions / instructs stay — they are new facts.
            val drop = deferredNudges.filter { isVerbatimSpeak(it.first) }
            if (drop.isNotEmpty()) {
              droppedKinds += drop.map { it.first }
              deferredNudges.removeAll { isVerbatimSpeak(it.first) }
            }
          }
          if (deferredNudges.isEmpty()) return@synchronized null
          val kinds = deferredNudges.joinToString(", ") { it.first }
          val joined = deferredNudges.joinToString("\n\n") { it.second }
          deferredNudges.clear()
          kinds to joined
        }
    val actions = mutableListOf<GateAction>()
    if (droppedKinds.isNotEmpty()) {
      actions +=
          GateAction.Log(
              "✗ nudge: dropping ${droppedKinds.joinToString(", ")} — Sai already said it this turn")
    }
    if (pending == null) return actions
    // Same window as a direct inject: a flush IS a client turn, and the nudge that arrives right after
    // one must not cut off the turn the flush just started.
    awaitingModelUntil = now() + AWAIT_MODEL_MS
    actions += GateAction.Log("← nudge: delivering ${pending.first} (held during the turn)")
    actions += GateAction.SendTurn(HELD_PREAMBLE + "\n\n" + pending.second)
    return actions
  }

  /**
   * Prepended to anything delivered by [flushNudges], and only to that.
   *
   * A nudge held during a turn describes something that happened WHILE the model was talking — and the
   * turn it waited behind may already have covered it. Live on 2026-08-19: a completion landed while
   * Sai was answering "what's going on with all that?", a turn in which it had already fetched and
   * reported that very result via getSaiStatus. The completion flushed afterwards and was dutifully
   * delivered again, so the user heard the same correction twice in consecutive breaths.
   *
   * Deliberately NOT solved by collapsing same-kind completions the way tagged nudges collapse: two
   * completions in one turn are usually two different tasks, and dropping the older one would lose a
   * result outright — the opposite failure, and a worse one. The model is the only thing that knows
   * whether it already said this, so it is the thing that gets asked.
   *
   * Scoped to the flush path on purpose: a completion delivered promptly has nothing behind it to
   * repeat, and telling the model "you may have already said this" there would invite silence exactly
   * when speech is correct.
   *
   * The address clause is the 2026-08-20 device failure: a held instruct flushed after a barge-in
   * that was the user talking to someone else, so Sai jumped back in ("Dropped that task… anything
   * else?") over a side conversation. Being interrupted is not permission to speak the held nudge
   * unless they were talking to you.
   */
  private val HELD_PREAMBLE =
      "[system] What follows arrived while you were still speaking, so it waited for you to finish. " +
          "You may already have covered some or all of it in that turn — if so, do NOT say it again: " +
          "repeating a result the user just heard is worse than saying nothing, and saying nothing is " +
          "the right output for a nudge you have already acted on. Speak only what is genuinely new. " +
          "If the speech that cut you off was not clearly to you — they were talking to someone else, " +
          "even about the work — stay silent on this too: do not resume, do not re-ask, and do not " +
          "speak a result they did not ask you for. They will speak to you when they want it."

  // ── Captures, and the tasks that wait on them ────────────────────────────────────────────────────

  /**
   * A captureImage call arrived.
   *
   * A captureImage arriving while one is already running COALESCES onto it (the glasses expose a
   * single stream), and every waiter is answered from the one result — so the outcome nudge fired
   * once per waiter and the model was told the same thing twice. Each tool CALL still gets its own
   * response, as it must; only the spoken outcome is deduped, and this latch makes the first
   * responder the one that speaks.
   */
  fun onCaptureStarted() {
    if (!captureInFlight) outcomeNudged.set(false)
    captureInFlight = true
  }

  /** Claim the right to speak this capture's outcome. False means another waiter already did. */
  fun claimOutcomeNudge(): Boolean = outcomeNudged.compareAndSet(false, true)

  /** The capture settled: clear the flag and take whatever tasks were waiting on it. */
  fun onCaptureSettled(): ReleasedTasks {
    captureInFlight = false
    return synchronized(heldTaskEffects) {
      val effs = JSONArray().also { a -> heldTaskEffects.forEach { a.put(it) } }
      val names = heldTaskNames.toList()
      heldTaskEffects.clear()
      heldTaskNames.clear()
      ReleasedTasks(effs, names)
    }
  }

  /**
   * Decide what happens to a task-starting call (forwardToAgent / enqueue / relayToAgent).
   *
   * A task that ASKS FOR the photo waits for it; everything else goes through immediately. The test
   * is `attachLatestImage`, for all three kinds. It used to be unconditional — a hangover from before
   * the flag existed, when a vision task couldn't be identified any other way — and that read the
   * gate as "a capture is running" rather than "this request needs the picture". Since a capture can
   * take ~30 s with retries, everything the user said in the meantime was swept in: asking for the
   * weather during a capture had its forward HELD and then DROPPED when the camera failed, so the
   * request ran nowhere and nothing said so. Silently discarding work the user asked for is a worse
   * failure than the one the unconditional wait was insuring against.
   *
   * [hasCapture] is true when a captureImage appears ANYWHERE in the same batch, because the calls
   * are processed in arrival order and the forward can precede the captureImage that
   * [isCaptureInFlight] would otherwise report.
   */
  fun routeTaskCall(name: String, effect: JSONObject, wantsPhoto: Boolean, hasCapture: Boolean): TaskRouting {
    if (wantsPhoto && (hasCapture || captureInFlight)) {
      synchronized(heldTaskEffects) {
        heldTaskEffects.add(effect)
        heldTaskNames.add(name)
      }
      return TaskRouting.HeldForPhoto(
          // Answered immediately (truthfully) rather than deferred — an unanswered call in the batch
          // would keep the model mute for the whole capture.
          response =
              JSONObject()
                  .put("result", "held-for-photo")
                  .put("note", CaptureNotes.HELD_FOR_PHOTO),
          log = "⏸ holding $name (it asked for the photo) until the capture resolves",
      )
    }
    // Name the one case the narrowed gate can get wrong: a task that IS about the photo but never set
    // the flag goes out blind. The prompt and rubric are what prevent it; this line is how we'd find
    // out they didn't.
    return TaskRouting.Emit(
        log =
            if (hasCapture || captureInFlight)
                "→ effect: $name (during a capture, but it didn't ask for the photo)"
            else "→ effect: $name")
  }

  companion object {
    // How long to ignore model audio after a barge-in. Covers chunks of the interrupted turn that
    // were already in flight; comfortably shorter than the pause before the model's next reply
    // (end-of-speech + silenceDurationMs).
    const val INTERRUPT_DISCARD_MS = 700L
    /**
     * How long a sent client turn is treated as in flight before the gate stops waiting for a reply.
     *
     * Covers the round trip to a first frame with room to spare; past it the gate assumes the turn
     * produced nothing and lets the next nudge through, rather than holding it indefinitely on a
     * promise the model never kept. Deliberately generous against the ~200 ms that caused the device
     * failure and deliberately finite, for the reason in [awaitingModelUntil]'s KDoc.
     */
    const val AWAIT_MODEL_MS = 3_000L
  }
}
