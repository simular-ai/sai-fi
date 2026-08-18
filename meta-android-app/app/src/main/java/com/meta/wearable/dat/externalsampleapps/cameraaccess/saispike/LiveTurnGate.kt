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
//   - a turn that produced a generation and no turn-end frame left `modelSpeaking` stuck true and
//     EVERY later nudge deferred behind it, silently, for the rest of the call.
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

  /** Release these held task effects to the concierge. */
  data class ReleaseEffects(val effects: JSONArray) : GateAction
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

  // ── Queries ──────────────────────────────────────────────────────────────────────────────────────

  /** Mid-utterance: a nudge sent now would cut the model off. */
  val isModelSpeaking: Boolean
    get() = modelSpeaking

  /** setupComplete has landed, so a client turn is deliverable. */
  val isReady: Boolean
    get() = ready

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
    ready = false
    discardAudioUntil = 0L
    saiTurn = ""
    withheld = ""
    val actions = mutableListOf<GateAction>()
    synchronized(deferredNudges) {
      // Say what is being thrown away. A nudge held for a turn that never ended (a barge-in, then a
      // token-expiry reconnect) died here without a trace, which is one candidate cause for a
      // completion the user never heard.
      if (deferredNudges.isNotEmpty()) {
        actions +=
            GateAction.Log(
                "✗ nudge: dropping ${deferredNudges.joinToString(", ") { it.first }} — session replaced")
      }
      deferredNudges.clear()
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
    spokeThisTurn = true
    return listOf(GateAction.SaiTranscript(out))
  }

  /** An audio part was accepted for playback (not discarded) — the model is mid-turn. */
  fun onAudioAccepted() {
    modelSpeaking = true
  }

  /**
   * A generation or a turn ended.
   *
   * `generationComplete` also ends the model's output — it just doesn't end the TURN. Both are flush
   * points for nudge gating, and taking either one closes a hole: a held nudge was only ever released
   * on `turnComplete`, so a turn that produced a generation and no turn-end frame left `modelSpeaking`
   * stuck true and EVERY later nudge deferred behind it, silently, for the rest of the call. A
   * completion the user never hears is exactly that shape. Safe to flush here: nothing further is
   * being generated, and audio already queued still plays in order.
   */
  fun onGenerationOrTurnEnd(generationEnded: Boolean, turnEnded: Boolean): List<GateAction> {
    val actions = mutableListOf<GateAction>()
    if (generationEnded || turnEnded) {
      modelSpeaking = false
      actions += flushNudges() // deliver anything held back during the turn
      // Only a real turn boundary ends the transcript entry; a generation boundary mid-turn does not.
      if (turnEnded) actions += GateAction.TurnComplete
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
    if (modelSpeaking) {
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
    // These are the nudges that ask for silence; a quiet turn after one of them is obedience, and
    // must not be reported as Sai judging the speech wasn't for it.
    if (kind.startsWith("muted") || kind.startsWith("complete (ask-first")) silenceWasRequested = true
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
    return listOf(
        GateAction.Log("← nudge: delivering ${pending.first} (held until the session was ready)"),
        GateAction.SendTurn(pending.second),
    )
  }

  private fun flushNudges(): List<GateAction> {
    val pending =
        synchronized(deferredNudges) {
          if (deferredNudges.isEmpty()) return emptyList()
          val kinds = deferredNudges.joinToString(", ") { it.first }
          val joined = deferredNudges.joinToString("\n\n") { it.second }
          deferredNudges.clear()
          kinds to joined
        }
    return listOf(
        GateAction.Log("← nudge: delivering ${pending.first} (held during the turn)"),
        GateAction.SendTurn(pending.second),
    )
  }

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
                  .put(
                      "note",
                      "NOT started yet — waiting for the glasses photo so the task has it. It will " +
                          "start by itself the moment the photo lands, or be cancelled if the " +
                          "capture fails. Do not claim it is running.",
                  ),
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
  }
}
