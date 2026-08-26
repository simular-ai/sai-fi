/* sai-fi — voice concierge. */

// LiveTurnGate — what the Live session does with a nudge, a tool call, and a turn boundary.
//
// Extracted from GeminiLiveClient, which kept this state inline and could therefore never be tested:
// that class is a WebSocket, a Base64 and a Log, so no test could reach the decisions buried in it.
// The decisions are the part that matters — they decide whether a completion ever reaches the user —
// and two bugs are on record here:
//
//   - a nudge held for a turn that never ended (a barge-in, then a token-expiry reconnect) died
//     without a trace, which is one candidate cause for a completion the user never heard;
//   - flushing a held nudge on `generationComplete` (not `turnComplete`) barged Sai off its own
//     sentence: the function-call generation ends before it speaks, and a client turn is interrupt.
//
// Both are barge-in ⇄ queue interactions, and neither had a test until this class existed.
//
// **Nothing here does I/O.** Every method returns the `GateAction`s the caller should perform, in
// order. That keeps the ordering between a log line and the turn it describes explicit — and it is
// what lets a test run the whole machine with no socket at all.
//
// Not an actor: the Android tests are synchronous, and `onSetupComplete` → `injectNudge` would
// deadlock a non-reentrant lock if both took it. One `OSAllocatedUnfairLock` around an internal
// state, with locked helpers for the nested paths.
//
// Ported from Android `LiveTurnGate.kt`.

import Foundation
import os

/// One thing the gate wants its caller to do. Perform them in the order returned.
public enum GateAction: Equatable, Sendable {
  /// Send `text` to the model as a complete client turn.
  case sendTurn(String)
  /// Write `text` to the UI/event log. These strings are load-bearing — ON_DEVICE_CHECK greps them.
  case log(String)
  /// Emit a transcript delta from Sai (role `sai`).
  case saiTranscript(String)
  /// Emit a transcript delta from the user (role `you`).
  case userTranscript(String)
  /// The model's turn ended — finalize the current transcript entry.
  case turnComplete
  /// The user barged in — flush queued playback immediately.
  case flushPlayback
}

/// What `LiveTurnGate.routeTaskCall` decided about a task-starting tool call.
public enum TaskRouting: Sendable {
  /// Forward it now, as an effect. `log` names the one case the narrowed gate can get wrong.
  case emit(log: String)
  /// Held until the capture resolves; answer the model with this truthful tool response.
  case heldForPhoto(response: JsonObject, log: String)
}

/// Everything released when a capture settles.
public struct ReleasedTasks: Sendable {
  public var effects: [JsonObject]
  public var names: [String]
}

public final class LiveTurnGate: @unchecked Sendable {

  /// How long to ignore model audio after a barge-in. Covers chunks of the interrupted turn that
  /// were already in flight; comfortably shorter than the pause before the model's next reply
  /// (end-of-speech + silenceDurationMs).
  public static let interruptDiscardMs: Int64 = 700
  /// How long a sent client turn is treated as in flight before the gate stops waiting for a reply.
  ///
  /// Covers the round trip to a first frame with room to spare; past it the gate assumes the turn
  /// produced nothing and lets the next nudge through, rather than holding it indefinitely on a
  /// promise the model never kept. Deliberately generous against the ~200 ms that caused the device
  /// failure and deliberately finite, for the reason in `awaitingModelUntil`'s comment.
  public static let awaitModelMs: Int64 = 3_000

  private struct State {
    var modelSpeaking = false
    /// A client turn has been sent and the model has not answered it yet. Held as a DEADLINE rather
    /// than a flag: a timestamp cannot stick true, so the worst case is a nudge held for
    /// `awaitModelMs` and then sent, rather than one held forever.
    var awaitingModelUntil: Int64 = 0
    var captureInFlight = false
    var outcomeNudged = false
    var heardUserSinceLastTurn = false
    var spokeThisTurn = false
    var saiTurn = ""
    var withheld = ""
    var silenceWasRequested = false
    var heldTaskEffects: [JsonObject] = []
    var heldTaskNames: [String] = []
    var discardAudioUntil: Int64 = 0
    var deferredNudges: [(String, String)] = []
    var preConnectNudges: [(String, String)] = []
    var sessionState: (String, String)?
    var ready = false
    var greetingSentThisSession = false
  }

  private let lock = OSAllocatedUnfairLock(initialState: State())
  private let now: () -> Int64

  public init(now: @escaping () -> Int64 = {
    Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
  }) {
    self.now = now
  }

  // ── Queries ────────────────────────────────────────────────────────────────

  /// Mid-utterance: a nudge sent now would cut the model off.
  public var isModelSpeaking: Bool { lock.withLock { $0.modelSpeaking } }

  /// A capture is running, so a task that asked for the photo must wait for it.
  public var isCaptureInFlight: Bool { lock.withLock { $0.captureInFlight } }

  /// Did Sai produce speech in the current turn? The hang-up guard reads this.
  public var didSpeakThisTurn: Bool { lock.withLock { $0.spokeThisTurn } }

  /// Straggler audio from a turn the user just barged in on should be dropped.
  ///
  /// Evaluate ONCE per serverContent frame and reuse the answer for every part in it — re-reading
  /// the clock per part could split one frame across the window boundary.
  public func shouldDiscardAudio() -> Bool {
    let t = now()
    return lock.withLock { t < $0.discardAudioUntil }
  }

  // ── Session lifecycle ──────────────────────────────────────────────────────

  /// A fresh socket is being opened (initial connect, or a reconnect replacing the old one).
  ///
  /// Fresh session ⇒ fresh turn state, which is also correct on a reconnect: the old turn is gone.
  public func onConnect() -> [GateAction] {
    lock.withLock { state in
      state.modelSpeaking = false
      state.awaitingModelUntil = 0
      state.ready = false
      state.greetingSentThisSession = false
      state.discardAudioUntil = 0
      state.saiTurn = ""
      state.withheld = ""
      var actions: [GateAction] = []
      // Say what is being thrown away. A nudge held for a turn that never ended (a barge-in, then a
      // token-expiry reconnect) died here without a trace.
      //
      // The greeting is not dropped: it is the turn that starts the call, and a reconnect before Sai
      // has spoken is exactly when it has to go out again. preConnectNudges already survive; move a
      // held greeting there so setupComplete delivers it instead of leaving the new session silent.
      let greeting = state.deferredNudges.filter { $0.0 == "greeting" }
      let others = state.deferredNudges.filter { $0.0 != "greeting" }
      state.deferredNudges.removeAll()
      if !others.isEmpty {
        let names = others.map(\.0).joined(separator: ", ")
        actions.append(.log("✗ nudge: dropping \(names) — session replaced"))
      }
      if !greeting.isEmpty {
        state.preConnectNudges.append(contentsOf: greeting)
        actions.append(.log("→ nudge: greeting — carried to the new session"))
      }
      // preConnectNudges deliberately SURVIVES a reconnect: it holds session-level state (mute) that
      // a fresh Live session needs re-asserted anyway, and the reconnect is exactly when it's
      // re-injected.
      return actions
    }
  }

  /// setupComplete landed: client turns are deliverable from here.
  ///
  /// Session state goes out BEFORE anything that was waiting on the session — the greeting is
  /// injected from onReady, and a mute asserted while connecting has to reach the model first, or
  /// Sai is told to greet, then told to be silent, and obeys the last thing it read.
  public func onSetupComplete() -> [GateAction] {
    lock.withLock { state in
      state.ready = true
      var actions: [GateAction] = [.log("live: setup complete — start talking")]
      if let session = state.sessionState {
        actions += injectNudgeLocked(
          kind: "\(session.0) (re-asserted for this session)",
          turns: session.1,
          dropIfBusy: false,
          state: &state)
      }
      actions += flushPreConnectNudgesLocked(&state)
      return actions
    }
  }

  /// The socket is being closed deliberately.
  public func onClose() {
    lock.withLock { $0.ready = false }
  }

  // ── Model output ───────────────────────────────────────────────────────────

  /// The user barged in.
  ///
  /// Flushing the track only empties what's already queued. Audio chunks of the interrupted turn that
  /// were ALREADY in flight keep arriving in the next few messages, get written, and refill it — so
  /// Sai talked straight through the barge-in even though the interrupt fired. Drop the stragglers
  /// for a beat.
  public func onInterrupted() -> [GateAction] {
    let t = now()
    return lock.withLock { state in
      state.modelSpeaking = false
      state.awaitingModelUntil = 0
      state.discardAudioUntil = t + Self.interruptDiscardMs
      return [.flushPlayback]
    }
  }

  /// A user-speech transcription delta arrived.
  public func onUserTranscript(_ text: String) -> [GateAction] {
    lock.withLock { state in
      state.heardUserSinceLastTurn = true
      return [.userTranscript(text)]
    }
  }

  /// Forward a transcript delta from Sai, unless the turn so far is only a placeholder.
  ///
  /// Withholding rather than dropping matters: the test is against the accumulated turn, so the
  /// first fragment of a real sentence can look placeholder-shaped for one frame ("Empty" before
  /// "Empty-handed, sorry"). Anything held back is released the moment the turn stops matching.
  public func onSaiTranscript(_ delta: String) -> [GateAction] {
    lock.withLock { state in
      state.saiTurn += delta
      if isPlaceholderSpeech(state.saiTurn) {
        state.withheld += delta
        return []
      }
      let out = state.withheld + delta
      state.withheld = ""
      state.modelSpeaking = true
      state.awaitingModelUntil = 0
      state.spokeThisTurn = true
      return [.saiTranscript(out)]
    }
  }

  /// An audio part was accepted for playback (not discarded) — the model is mid-turn.
  public func onAudioAccepted() {
    lock.withLock { state in
      state.modelSpeaking = true
      state.awaitingModelUntil = 0
    }
  }

  /// A tool call arrived — which is PROOF the model is mid-turn, and the gate had no other way to
  /// know.
  ///
  /// A deadline rather than `modelSpeaking = true`: a tool call is not a promise of speech (an
  /// `endCall` may be the end of the conversation), and a flag set here and never cleared would
  /// defer every later nudge for the rest of the call.
  public func onToolCall() {
    let deadline = now() + Self.awaitModelMs
    lock.withLock { $0.awaitingModelUntil = deadline }
  }

  /// A generation or a turn ended.
  ///
  /// Only `turnEnded` is a flush point. `generationComplete` is not: Gemini Live treats a client
  /// turn as barge-in, and a generation ending is often the function-call generation, which
  /// completes BEFORE the model speaks the ack. Flushing then cuts that ack off.
  public func onGenerationOrTurnEnd(generationEnded: Bool, turnEnded: Bool) -> [GateAction] {
    lock.withLock { state in
      var actions: [GateAction] = []
      if turnEnded {
        // Snapshot BEFORE clearing: a verbatim `speak` / `speak:*` held for this turn is a fallback
        // for silence. If Sai already produced audio or a transcript, flushing that line as a
        // client turn barges it off its own sentence and it says the same thing twice.
        let alreadySpoke = state.spokeThisTurn || state.modelSpeaking
        state.modelSpeaking = false
        state.awaitingModelUntil = 0
        actions += flushNudgesLocked(alreadySpoke: alreadySpoke, state: &state)
        actions.append(.turnComplete)
      } else if generationEnded {
        // Deliberately a no-op on the gate. Do not clear modelSpeaking or awaitingModelUntil:
        // generationComplete after a tool call is exactly when that window has to cover speech that
        // has not started yet.
      }
      if turnEnded {
        if !state.withheld.isEmpty {
          let shown = state.withheld.trimmingCharacters(in: .whitespacesAndNewlines)
          actions.append(.log("✗ dropped a placeholder turn (\"\(shown)\") — not speech"))
        }
        if state.heardUserSinceLastTurn && !state.spokeThisTurn && !state.silenceWasRequested {
          actions.append(.log("— no reply to that (Sai may have judged it wasn't meant for it) —"))
        }
        state.heardUserSinceLastTurn = false
        state.spokeThisTurn = false
        state.silenceWasRequested = false
        state.saiTurn = ""
        state.withheld = ""
      }
      return actions
    }
  }

  // ── Nudges ─────────────────────────────────────────────────────────────────

  /// Inject a nudge as a user turn. Deferred if the model is speaking (unless `dropIfBusy`).
  ///
  /// `kind` is a short tag for the log — "complete", "muted", "capture-retry". EVERY outcome is
  /// logged. The nudge BODY is deliberately never logged: it carries agent-derived text and this
  /// log is mirrored to a projector.
  public func injectNudge(_ kind: String, _ turns: String, dropIfBusy: Bool = false) -> [GateAction] {
    lock.withLock { state in
      injectNudgeLocked(kind: kind, turns: turns, dropIfBusy: dropIfBusy, state: &state)
    }
  }

  /// Inject a nudge AND record whether it describes state the next Live session must be told about.
  ///
  /// `sticky = true` keeps it (mute); `false` clears whatever was kept (unmute).
  public func injectSessionState(_ kind: String, _ turns: String, sticky: Bool) -> [GateAction] {
    lock.withLock { state in
      state.sessionState = sticky ? (kind, turns) : nil
      return injectNudgeLocked(kind: kind, turns: turns, dropIfBusy: false, state: &state)
    }
  }

  private func injectNudgeLocked(
    kind: String,
    turns: String,
    dropIfBusy: Bool,
    state: inout State
  ) -> [GateAction] {
    let isGreeting = kind == "greeting"
    if !isGreeting && (state.modelSpeaking || now() < state.awaitingModelUntil) {
      if dropIfBusy {
        return [.log("→ nudge: \(kind) — dropped (mid-utterance)")]
      }
      // A tagged line replaces a held one about the same subject. Two nudges are only ever merged
      // into one turn, so without this the model is handed two "say this verbatim" commands at once
      // and reads out both.
      let stale = kind.contains(":") && state.deferredNudges.contains { $0.0 == kind }
      if stale { state.deferredNudges.removeAll { $0.0 == kind } }
      state.deferredNudges.append((kind, turns))
      return [
        .log(
          stale
            ? "→ nudge: \(kind) — held until the turn ends (replacing the stale one)"
            : "→ nudge: \(kind) — held until the turn ends")
      ]
    }
    if !state.ready {
      if state.sessionState?.0 == kind {
        return [.log("→ nudge: \(kind) — will be asserted when the session is ready")]
      }
      state.preConnectNudges.append((kind, turns))
      return [.log("→ nudge: \(kind) — held until the session is ready")]
    }
    if isGreeting && state.greetingSentThisSession {
      return [.log("→ nudge: greeting — already sent this session")]
    }
    if kind.hasPrefix("muted") || kind.hasPrefix("complete (ask-first") {
      state.silenceWasRequested = true
    }
    if isGreeting { state.greetingSentThisSession = true }
    state.awaitingModelUntil = now() + Self.awaitModelMs
    return [.log("→ nudge: \(kind)"), .sendTurn(turns)]
  }

  /// Deliver anything injected before the session was ready, oldest first, as one turn.
  private func flushPreConnectNudgesLocked(_ state: inout State) -> [GateAction] {
    if state.preConnectNudges.isEmpty { return [] }
    let kinds = state.preConnectNudges.map(\.0).joined(separator: ", ")
    let joined = state.preConnectNudges.map(\.1).joined(separator: "\n\n")
    state.preConnectNudges.removeAll()
    if kinds.split(separator: ", ").contains(where: { $0.trimmingCharacters(in: .whitespaces) == "greeting" }) {
      state.greetingSentThisSession = true
    }
    return [
      .log("← nudge: delivering \(kinds) (held until the session was ready)"),
      .sendTurn(joined),
    ]
  }

  /// FSM `say` lines: a fallback if the model stayed quiet. Not new information.
  private static func isVerbatimSpeak(_ kind: String) -> Bool {
    kind == "speak" || kind.hasPrefix("speak:")
  }

  private func flushNudgesLocked(alreadySpoke: Bool, state: inout State) -> [GateAction] {
    var droppedKinds: [String] = []
    if alreadySpoke {
      let drop = state.deferredNudges.filter { Self.isVerbatimSpeak($0.0) }
      if !drop.isEmpty {
        droppedKinds += drop.map(\.0)
        state.deferredNudges.removeAll { Self.isVerbatimSpeak($0.0) }
      }
    }
    var actions: [GateAction] = []
    if !droppedKinds.isEmpty {
      actions.append(
        .log("✗ nudge: dropping \(droppedKinds.joined(separator: ", ")) — Sai already said it this turn"))
    }
    if state.deferredNudges.isEmpty { return actions }
    let kinds = state.deferredNudges.map(\.0).joined(separator: ", ")
    let joined = state.deferredNudges.map(\.1).joined(separator: "\n\n")
    state.deferredNudges.removeAll()
    state.awaitingModelUntil = now() + Self.awaitModelMs
    actions.append(.log("← nudge: delivering \(kinds) (held during the turn)"))
    actions.append(.sendTurn(Self.heldPreamble + "\n\n" + joined))
    return actions
  }

  /// Prepended to anything delivered by `flushNudges`, and only to that.
  ///
  /// A nudge held during a turn describes something that happened WHILE the model was talking — and
  /// the turn it waited behind may already have covered it. Deliberately NOT solved by collapsing
  /// same-kind completions: two completions in one turn are usually two different tasks.
  static let heldPreamble =
    "[system] What follows arrived while you were still speaking, so it waited for you to finish. "
    + "You may already have covered some or all of it in that turn — if so, do NOT say it again: "
    + "repeating a result the user just heard is worse than saying nothing, and saying nothing is "
    + "the right output for a nudge you have already acted on. Speak only what is genuinely new. "
    + "If the speech that cut you off was not clearly to you — they were talking to someone else, "
    + "even about the work — stay silent on this too: do not resume, do not re-ask, and do not "
    + "speak a result they did not ask you for. They will speak to you when they want it."

  // ── Captures, and the tasks that wait on them ──────────────────────────────

  /// A captureImage call arrived.
  ///
  /// A captureImage arriving while one is already running COALESCES onto it, and every waiter is
  /// answered from the one result — so the outcome nudge fired once per waiter and the model was
  /// told the same thing twice. Each tool CALL still gets its own response; only the spoken outcome
  /// is deduped.
  public func onCaptureStarted() {
    lock.withLock { state in
      if !state.captureInFlight { state.outcomeNudged = false }
      state.captureInFlight = true
    }
  }

  /// Claim the right to speak this capture's outcome. False means another waiter already did.
  public func claimOutcomeNudge() -> Bool {
    lock.withLock { state in
      if state.outcomeNudged { return false }
      state.outcomeNudged = true
      return true
    }
  }

  /// The capture settled: clear the flag and take whatever tasks were waiting on it.
  public func onCaptureSettled() -> ReleasedTasks {
    lock.withLock { state in
      state.captureInFlight = false
      let released = ReleasedTasks(effects: state.heldTaskEffects, names: state.heldTaskNames)
      state.heldTaskEffects.removeAll()
      state.heldTaskNames.removeAll()
      return released
    }
  }

  /// Decide what happens to a task-starting call (forwardToAgent / enqueue / relayToAgent).
  ///
  /// A task that ASKS FOR the photo waits for it; everything else goes through immediately. The test
  /// is `attachLatestImage`, for all three kinds.
  public func routeTaskCall(
    name: String,
    effect: JsonObject,
    wantsPhoto: Bool,
    hasCapture: Bool
  ) -> TaskRouting {
    lock.withLock { state in
      if wantsPhoto && (hasCapture || state.captureInFlight) {
        state.heldTaskEffects.append(effect)
        state.heldTaskNames.append(name)
        return .heldForPhoto(
          response: JsonObject([
            "result": "held-for-photo",
            "note": CaptureNotes.heldForPhoto,
          ]),
          log: "⏸ holding \(name) (it asked for the photo) until the capture resolves")
      }
      let log: String
      if hasCapture || state.captureInFlight {
        log = "→ effect: \(name) (during a capture, but it didn't ask for the photo)"
      } else {
        log = "→ effect: \(name)"
      }
      return .emit(log: log)
    }
  }
}
