/* sai-fi — voice concierge. */

// The turn/nudge gate: whether a nudge reaches the model, when, and whether it is lost.
//
// Ported from Android `LiveTurnGateTest.kt`. Log strings are asserted verbatim: ON_DEVICE_CHECK.md
// tells a human to grep for `→ nudge:` / `← nudge:` / `✗ nudge:` while wearing the glasses, so those
// strings are a contract with the person running the check, not incidental debug output.

import Foundation

private final class GateFixture: @unchecked Sendable {
  var now: Int64 = 1_000
  lazy var gate = LiveTurnGate(now: { [unowned self] in self.now })

  /// Bring the session up: connected and past setupComplete, which is the normal resting state.
  @discardableResult
  func ready() -> GateFixture {
    _ = gate.onConnect()
    _ = gate.onSetupComplete()
    return self
  }

  /// Put the model mid-utterance, the way a transcript delta does on a real call.
  @discardableResult
  func speaking() -> GateFixture {
    _ = gate.onSaiTranscript("I'm on it")
    return self
  }
}

private extension Array where Element == GateAction {
  var sent: [String] {
    compactMap { if case .sendTurn(let t) = $0 { t } else { nil } }
  }
  var logs: [String] {
    compactMap { if case .log(let t) = $0 { t } else { nil } }
  }
  var transcripts: [String] {
    compactMap { if case .saiTranscript(let t) = $0 { t } else { nil } }
  }
}

private func call(_ name: String) -> JsonObject {
  JsonObject(["kind": name, "text": "order it"])
}

func liveTurnGateChecks() -> [Check] {
  [
    // ── The two recorded bugs ───────────────────────────────────────────────

    Check(name: "a barge-in then a session replacement drops the held nudge loudly, not silently") {
      let f = GateFixture().ready().speaking()
      let held = f.gate.injectNudge("complete", "[agent] the task finished")
      return firstFailure([
        expectEqual(held.logs, ["→ nudge: complete — held until the turn ends"], "held log"),
        expectTrue(held.sent.isEmpty, "nothing should go out mid-utterance"),
      ]).map { $0 } ?? {
        _ = f.gate.onInterrupted()
        let reconnect = f.gate.onConnect()
        if let fail = expectEqual(
          reconnect.logs, ["✗ nudge: dropping complete — session replaced"], "reconnect log")
        {
          return fail
        }
        _ = f.gate.onSetupComplete()
        let later = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
        return expectTrue(later.sent.isEmpty, "a dropped nudge must not resurface in a later session")
      }()
    },

    Check(name: "a generation that ends without a turn does not flush a held nudge into its own speech") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.injectNudge("complete", "[agent] the task finished")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: false)
      return firstFailure([
        expectTrue(ended.sent.isEmpty, "must not SendTurn on generationComplete"),
        expectTrue(!ended.contains(.turnComplete), "no TurnComplete either"),
        expectTrue(f.gate.isModelSpeaking, "it is still on the floor until turnComplete"),
      ]).map { $0 } ?? {
        let flushed = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
        return firstFailure([
          expectTrue(
            flushed.sent.count == 1 && flushed.sent[0].hasSuffix("[agent] the task finished"),
            "delivered on the real turn boundary"),
          expectEqual(
            flushed.logs, ["← nudge: delivering complete (held during the turn)"], "flush log"),
        ])
      }()
    },

    // ── The in-flight window (device 2026-08-20) ────────────────────────────

    Check(name: "a nudge sent moments after another is held, not fired into the turn it would cut off") {
      let f = GateFixture().ready()
      if let fail = expectEqual(f.gate.injectNudge("greeting", "greeting").sent, ["greeting"], "greeting goes out")
      {
        return fail
      }
      if let fail = expectFalse(f.gate.isModelSpeaking, "no frame has arrived") { return fail }
      f.now += 200
      let wake = f.gate.injectNudge("speak:machine-state", "waking")
      return firstFailure([
        expectTrue(wake.sent.isEmpty, "must not go out into an in-flight turn"),
        expectEqual(
          wake.logs, ["→ nudge: speak:machine-state — held until the turn ends"], "held log"),
      ]).map { $0 } ?? {
        let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
        return expectTrue(
          ended.sent.count == 1 && ended.sent[0].hasSuffix("waking"),
          "delivered on the turn boundary")
      }()
    },

    Check(name: "a tool call holds the line the FSM speaks in reply to it") {
      let f = GateFixture().ready()
      f.gate.onToolCall()
      if let fail = expectFalse(f.gate.isModelSpeaking, "no frame has arrived") { return fail }
      let queued = f.gate.injectNudge("speak:queue-position", "[system] say you'll get to it after")
      return firstFailure([
        expectTrue(queued.sent.isEmpty, "must not cut off the turn that made the call"),
        expectEqual(
          queued.logs, ["→ nudge: speak:queue-position — held until the turn ends"], "held log"),
        expectTrue(
          f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: false).sent.isEmpty,
          "generationComplete after a tool call must not flush"),
      ]).map { $0 } ?? {
        let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
        return expectTrue(
          ended.sent.count == 1 && ended.sent[0].hasSuffix("say you'll get to it after"),
          "delivered when the spoken turn ends")
      }()
    },

    Check(name: "a queue-position held while Sai already spoke is dropped, not flushed into its own sentence") {
      let f = GateFixture().ready()
      f.gate.onToolCall()
      _ = f.gate.injectNudge(
        "speak:queue-position", "[system] Say verbatim: I'll start that as soon as I'm done")
      if let fail = expectTrue(
        f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: false).sent.isEmpty,
        "generationComplete must not flush")
      {
        return fail
      }
      _ = f.gate.onSaiTranscript("I'll start that as soon as the downloads are done.")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: true)
      return firstFailure([
        expectTrue(ended.sent.isEmpty, "must not send a client turn into its own sentence"),
        expectEqual(
          ended.logs,
          ["✗ nudge: dropping speak:queue-position — Sai already said it this turn"],
          "drop log"),
      ])
    },

    Check(name: "a cancel line held while Sai already spoke is dropped the same way") {
      let f = GateFixture().ready()
      f.gate.onToolCall()
      _ = f.gate.injectNudge("speak", "[system] Say verbatim: that one hadn't started yet")
      _ = f.gate.onSaiTranscript("No problem, that one hadn't started yet.")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      return firstFailure([
        expectTrue(ended.sent.isEmpty, "dropped, not flushed"),
        expectEqual(
          ended.logs, ["✗ nudge: dropping speak — Sai already said it this turn"], "drop log"),
      ])
    },

    Check(name: "a completion still flushes after Sai spoke, even if a queue-position is dropped") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.injectNudge("speak:queue-position", "queue-fallback-line")
      _ = f.gate.injectNudge("complete", "[agent] the downloads finished")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      guard ended.sent.count == 1 else { return "expected one SendTurn, got \(ended.sent.count)" }
      return firstFailure([
        expectTrue(ended.sent[0].contains("the downloads finished"), "completion still goes out"),
        expectFalse(ended.sent[0].contains("queue-fallback-line"), "verbatim fallback dropped"),
        expectTrue(
          ended.logs.contains(where: { $0.contains("dropping speak:queue-position") }),
          "the drop is logged"),
      ])
    },

    Check(name: "a completion arriving after generationComplete is held until the turn actually ends") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: false)
      if let fail = expectTrue(f.gate.isModelSpeaking, "generationComplete must not open the floor") {
        return fail
      }
      let arriving = f.gate.injectNudge("complete", "[agent] the downloads finished")
      return firstFailure([
        expectTrue(arriving.sent.isEmpty, "held"),
        expectEqual(arriving.logs, ["→ nudge: complete — held until the turn ends"], "held log"),
      ]).map { $0 } ?? {
        let flushed = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
        return expectTrue(
          flushed.sent.count == 1 && flushed.sent[0].contains("the downloads finished"),
          "flushed on turnComplete")
      }()
    },

    Check(name: "a tool call cannot wedge the gate either") {
      let f = GateFixture().ready()
      f.gate.onToolCall()
      f.now += LiveTurnGate.awaitModelMs
      return expectEqual(f.gate.injectNudge("later", "later").sent, ["later"], "deadline expired")
    },

    Check(name: "the in-flight window expires, so a turn that produced nothing cannot wedge the gate") {
      let f = GateFixture().ready()
      _ = f.gate.injectNudge("greeting", "greeting")
      f.now += LiveTurnGate.awaitModelMs
      return expectEqual(f.gate.injectNudge("later", "later").sent, ["later"], "deadline expired")
    },

    Check(name: "a frame from the model closes the window early") {
      let f = GateFixture().ready()
      _ = f.gate.injectNudge("greeting", "greeting")
      _ = f.gate.onGenerationOrTurnEnd(generationEnded: true, turnEnded: true)
      f.now += 10
      return expectEqual(f.gate.injectNudge("next", "next").sent, ["next"], "window closed with the turn")
    },

    Check(name: "the opening greeting is sent even if another turn is already in flight") {
      let f = GateFixture().ready()
      _ = f.gate.injectNudge("notice", "waking")
      f.now += 200
      return expectEqual(f.gate.injectNudge("greeting", "greeting").sent, ["greeting"], "greeting is the exception")
    },

    Check(name: "the opening greeting is sent only once per session") {
      let f = GateFixture().ready()
      if let fail = expectEqual(f.gate.injectNudge("greeting", "greeting").sent, ["greeting"], "first") {
        return fail
      }
      let again = f.gate.injectNudge("greeting", "greeting again")
      return firstFailure([
        expectTrue(again.sent.isEmpty, "second greeting is not sent"),
        expectEqual(again.logs, ["→ nudge: greeting — already sent this session"], "already-sent log"),
      ])
    },

    Check(name: "a greeting injected before setup survives a reconnect") {
      let f = GateFixture()
      _ = f.gate.onConnect()
      if let fail = expectTrue(f.gate.injectNudge("greeting", "greeting").sent.isEmpty, "held until ready") {
        return fail
      }
      _ = f.gate.onConnect()
      let setup = f.gate.onSetupComplete()
      return expectTrue(
        setup.sent.contains(where: { $0.contains("greeting") }),
        "the new session must still get the greeting")
    },

    // ── Nudge gating ────────────────────────────────────────────────────────

    Check(name: "an idle ready session sends a nudge straight through") {
      let f = GateFixture().ready()
      let actions = f.gate.injectNudge("notice", "[agent] your machine is waking")
      return firstFailure([
        expectEqual(actions.logs, ["→ nudge: notice"], "log"),
        expectEqual(actions.sent, ["[agent] your machine is waking"], "sent"),
      ])
    },

    Check(name: "a low-value nudge marked dropIfBusy is dropped rather than queued behind the turn") {
      let f = GateFixture().ready().speaking()
      let actions = f.gate.injectNudge("step-failed", "[agent] a step failed", dropIfBusy: true)
      return firstFailure([
        expectEqual(actions.logs, ["→ nudge: step-failed — dropped (mid-utterance)"], "drop log"),
        expectTrue(actions.sent.isEmpty, "not sent"),
        expectTrue(
          f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true).sent.isEmpty,
          "dropped means dropped"),
      ])
    },

    Check(name: "several nudges held across one turn are delivered once, in order, as a single turn") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.injectNudge("complete", "first")
      _ = f.gate.injectNudge("notice", "second")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      guard ended.sent.count == 1 else { return "expected one SendTurn, got \(ended.sent.count)" }
      let sent = ended.sent[0]
      return firstFailure([
        expectTrue(
          sent.hasPrefix("[system] What follows arrived while you were still speaking"),
          "held-nudge preamble"),
        expectTrue(sent.hasSuffix("first\n\nsecond"), "the bodies keep their order, after the preamble"),
        expectEqual(
          ended.logs, ["← nudge: delivering complete, notice (held during the turn)"], "flush log"),
      ])
    },

    Check(name: "a nudge held behind a turn is warned that the turn may already have covered it") {
      let held = GateFixture().ready().speaking()
      _ = held.gate.injectNudge("complete", "[agent] the task finished")
      let flushed = held.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true).sent
      guard flushed.count == 1 else { return "expected one flushed SendTurn" }
      if let fail = expectTrue(flushed[0].contains("do NOT say it again"), "preamble on the flush path") {
        return fail
      }
      let prompt = GateFixture().ready()
      let direct = prompt.gate.injectNudge("complete", "[agent] the task finished").sent
      guard direct.count == 1 else { return "expected one direct SendTurn" }
      return expectEqual(direct[0], "[agent] the task finished", "no preamble on the prompt path")
    },

    Check(name: "a nudge injected before setup is held for the session, not fired at a socket that cannot take it") {
      let f = GateFixture()
      _ = f.gate.onConnect()
      let early = f.gate.injectNudge("muted", "[system] you are muted")
      return firstFailure([
        expectEqual(early.logs, ["→ nudge: muted — held until the session is ready"], "held log"),
        expectTrue(early.sent.isEmpty, "not sent yet"),
      ]).map { $0 } ?? {
        let setup = f.gate.onSetupComplete()
        return firstFailure([
          expectEqual(setup.sent, ["[system] you are muted"], "delivered at setup"),
          expectTrue(
            setup.logs.contains("← nudge: delivering muted (held until the session was ready)"),
            "delivery log"),
        ])
      }()
    },

    // ── Session state (mute) ────────────────────────────────────────────────

    Check(name: "sticky session state is re-asserted on every new session") {
      let f = GateFixture().ready()
      _ = f.gate.injectSessionState("muted", "[system] you are muted", sticky: true)
      _ = f.gate.onConnect()
      let setup = f.gate.onSetupComplete()
      return firstFailure([
        expectEqual(setup.sent, ["[system] you are muted"], "re-asserted"),
        expectTrue(
          setup.logs.contains("→ nudge: muted (re-asserted for this session)"),
          "re-assert log"),
      ])
    },

    Check(name: "unmuting clears the sticky state so the next session is not told it is muted") {
      let f = GateFixture().ready()
      _ = f.gate.injectSessionState("muted", "[system] you are muted", sticky: true)
      _ = f.gate.injectSessionState("unmuted", "[system] you are unmuted", sticky: false)
      _ = f.gate.onConnect()
      let setup = f.gate.onSetupComplete()
      return expectTrue(setup.sent.isEmpty, "a cleared state must not be re-asserted")
    },

    Check(name: "session state injected before setup is not also buffered, so it arrives once") {
      let f = GateFixture()
      _ = f.gate.onConnect()
      _ = f.gate.injectSessionState("muted", "[system] you are muted", sticky: true)
      let setup = f.gate.onSetupComplete()
      return expectEqual(setup.sent, ["[system] you are muted"], "exactly once")
    },

    // ── Barge-in ────────────────────────────────────────────────────────────

    Check(name: "a barge-in flushes playback and discards stragglers for the window, then stops") {
      let f = GateFixture().ready().speaking()
      let actions = f.gate.onInterrupted()
      return firstFailure([
        expectTrue(actions.contains(.flushPlayback), "flushes playback"),
        expectFalse(f.gate.isModelSpeaking, "an interrupted turn is over"),
        expectTrue(f.gate.shouldDiscardAudio(), "stragglers arrive for a beat"),
      ]).map { $0 } ?? {
        f.now += LiveTurnGate.interruptDiscardMs - 1
        if let fail = expectTrue(f.gate.shouldDiscardAudio(), "still inside the window") { return fail }
        f.now += 2
        return expectFalse(f.gate.shouldDiscardAudio(), "past the window, real audio must play again")
      }()
    },

    Check(name: "a completion landing during a barge-in is delivered, not held behind a turn nobody will end") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.onInterrupted()
      let actions = f.gate.injectNudge("complete", "[agent] the task finished")
      return firstFailure([
        expectEqual(actions.sent, ["[agent] the task finished"], "goes out"),
        expectEqual(actions.logs, ["→ nudge: complete"], "sent log"),
      ])
    },

    Check(name: "a fresh connect clears a stale discard window") {
      let f = GateFixture().ready().speaking()
      _ = f.gate.onInterrupted()
      if let fail = expectTrue(f.gate.shouldDiscardAudio(), "precondition") { return fail }
      _ = f.gate.onConnect()
      return expectFalse(f.gate.shouldDiscardAudio(), "a new session must not start by throwing its first audio away")
    },

    // ── Transcript assembly ─────────────────────────────────────────────────

    Check(name: "a placeholder turn is withheld and reported once, rather than spoken") {
      let f = GateFixture().ready()
      let withheld = f.gate.onSaiTranscript("Empty-Response")
      if let fail = expectTrue(withheld.transcripts.isEmpty, "a mechanical placeholder is not speech") {
        return fail
      }
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      return expectEqual(
        ended.logs.filter { $0.hasPrefix("✗ dropped") },
        ["✗ dropped a placeholder turn (\"Empty-Response\") — not speech"],
        "reported once")
    },

    Check(name: "a turn that only looks placeholder-shaped at first is released in full") {
      let f = GateFixture().ready()
      if let fail = expectTrue(f.gate.onSaiTranscript("Empty").transcripts.isEmpty, "first fragment withheld") {
        return fail
      }
      let released = f.gate.onSaiTranscript("-handed, sorry")
      return firstFailure([
        expectEqual(released.transcripts, ["Empty-handed, sorry"], "opening word not lost"),
        expectTrue(f.gate.didSpeakThisTurn, "real speech marks the turn as spoken"),
      ])
    },

    Check(name: "a turn that heard the user and said nothing is reported") {
      let f = GateFixture().ready()
      _ = f.gate.onUserTranscript("is that the one from yesterday?")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      return expectTrue(
        ended.logs.contains("— no reply to that (Sai may have judged it wasn't meant for it) —"),
        "silence is reported")
    },

    Check(name: "silence a nudge asked for is not reported as Sai ignoring the user") {
      let f = GateFixture().ready()
      _ = f.gate.injectNudge("muted", "[system] you are muted")
      _ = f.gate.onUserTranscript("something overheard")
      let ended = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      return expectFalse(
        ended.logs.contains(where: { $0.hasPrefix("— no reply") }),
        "the silence is ours, not Sai's")
    },

    Check(name: "turn state resets at the turn boundary") {
      let f = GateFixture().ready()
      _ = f.gate.onSaiTranscript("all done")
      if let fail = expectTrue(f.gate.didSpeakThisTurn, "precondition") { return fail }
      _ = f.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      return expectFalse(f.gate.didSpeakThisTurn, "a new turn starts having said nothing")
    },

    // ── Tasks that wait on a photo ──────────────────────────────────────────

    Check(name: "a task that asked for the photo is held, and told plainly it has not started") {
      let f = GateFixture().ready()
      f.gate.onCaptureStarted()
      let routing = f.gate.routeTaskCall(
        name: "forwardToAgent", effect: call("forwardToAgent"), wantsPhoto: true, hasCapture: false)
      guard case .heldForPhoto(let response, let log) = routing else {
        return "expected HeldForPhoto, got \(routing)"
      }
      let note = response.optString("note")
      return firstFailure([
        expectEqual(response.optString("result"), "held-for-photo", "result"),
        expectEqual(note, CaptureNotes.heldForPhoto, "note is the pinned wording"),
        expectTrue(note.contains("NOT started yet"), "NOT started yet"),
        expectTrue(note.contains("Do not claim it is running"), "do not claim running"),
        expectTrue(note.contains("do not speak this note"), "the wait itself must stay silent"),
        expectEqual(
          log, "⏸ holding forwardToAgent (it asked for the photo) until the capture resolves",
          "holding log"),
      ])
    },

    Check(name: "a task that did not ask for the photo goes out during a capture, and says so") {
      let f = GateFixture().ready()
      f.gate.onCaptureStarted()
      let routing = f.gate.routeTaskCall(
        name: "forwardToAgent", effect: call("forwardToAgent"), wantsPhoto: false, hasCapture: false)
      guard case .emit(let log) = routing else { return "expected Emit, got \(routing)" }
      return expectEqual(
        log, "→ effect: forwardToAgent (during a capture, but it didn't ask for the photo)",
        "during-capture log")
    },

    Check(name: "a capture in the same batch holds the task even before the capture is flagged in flight") {
      let f = GateFixture().ready()
      let routing = f.gate.routeTaskCall(
        name: "forwardToAgent", effect: call("forwardToAgent"), wantsPhoto: true, hasCapture: true)
      if case .heldForPhoto = routing { return nil }
      return "expected HeldForPhoto, got \(routing)"
    },

    Check(name: "settling a capture releases the tasks that were waiting on it") {
      let f = GateFixture().ready()
      f.gate.onCaptureStarted()
      _ = f.gate.routeTaskCall(
        name: "forwardToAgent", effect: call("forwardToAgent"), wantsPhoto: true, hasCapture: false)
      _ = f.gate.routeTaskCall(
        name: "enqueue", effect: call("enqueue"), wantsPhoto: true, hasCapture: false)
      let released = f.gate.onCaptureSettled()
      return firstFailure([
        expectEqual(released.names, ["forwardToAgent", "enqueue"], "names in arrival order"),
        expectEqual(released.effects.count, 2, "two effects"),
        expectFalse(f.gate.isCaptureInFlight, "flag cleared"),
        expectEqual(f.gate.onCaptureSettled().effects.count, 0, "taken, not copied"),
      ])
    },

    Check(name: "several calls coalescing onto one capture speak its outcome once") {
      let f = GateFixture().ready()
      f.gate.onCaptureStarted()
      f.gate.onCaptureStarted()
      return firstFailure([
        expectTrue(f.gate.claimOutcomeNudge(), "the first responder speaks"),
        expectFalse(f.gate.claimOutcomeNudge(), "the second must not tell Sai the same thing twice"),
      ])
    },

    Check(name: "a new capture may speak its own outcome") {
      let f = GateFixture().ready()
      f.gate.onCaptureStarted()
      if let fail = expectTrue(f.gate.claimOutcomeNudge(), "first capture") { return fail }
      _ = f.gate.onCaptureSettled()
      f.gate.onCaptureStarted()
      return expectTrue(f.gate.claimOutcomeNudge(), "a fresh capture is a fresh outcome")
    },
  ]
}
