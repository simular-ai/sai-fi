/* sai-fi — voice concierge. */

// Barge-in, where it meets the queue.
//
// Barge-in has two halves. The ACOUSTIC half — AEC, VAD sensitivity, the noise gate, whether the
// glasses SCO route self-triggers — is not automatable and stays an on-device check by ear. The
// PROTOCOL half is: an interrupt ends the turn, opens a discard window, and decides the fate of
// anything the client was holding to say. That half is what these cover, and it is where the
// expensive failure lives:
//
//   ON_DEVICE_CHECK §7 — "Cutting Sai off must not cost you the result: it should still arrive
//   after the exchange."
//
// Ported from Android `conversation/BargeInConversationTest.kt`.

import Foundation

private let task = "check my email"

private func bargeBrain() -> ScriptedBrain {
  ScriptedBrain.of(
    ScriptedBrain.whenSaid("email") { _, _ in
      BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": task])))
    },
    ScriptedBrain.whenSaid("rundown") { _, _ in
      BrainTurn(speech: "here's a long rundown of everything I can do, starting with the first thing")
    },
    ScriptedBrain.whenSaid("weather") { _, _ in
      BrainTurn(speech: "it's clear out")
    },
    ScriptedBrain.whenNudged("[agent]") { input, _ in
      BrainTurn(speech: "your task finished — \(input)")
    })
}

private func bargeHarness() -> ConversationHarness {
  let h = ConversationHarness(brain: bargeBrain())
  h.agent.programs += [
    AgentProgram(match: { _ in true }, beats: conversationTask(summary: "3 new emails", doneAfterMs: 600)),
  ]
  return h
}

func bargeInConversationChecks() -> [Check] {
  [
    Check(name: "a result that lands while Sai is mid-sentence is held, then delivered") {
      let h = bargeHarness()
      h.speakingMs = 2_000
      await h.start()
      await h.user(task)
      try await h.settle()
      return firstFailure([
        expectTrue(h.logHas("held until the turn ends"), "the completion should have been held for the turn"),
        expectTrue(h.logHas("← nudge: delivering complete"), "and then actually delivered"),
        expectTrue(h.saidSomethingLike("3 new emails"), "so the user hears the result"),
      ])
    },
    Check(name: "cutting Sai off does not cost the result") {
      let h = bargeHarness()
      h.speakingMs = 2_000
      await h.start()
      await h.user(task)
      try await h.advance(700)
      if let fail = expectTrue(
        h.logHas("held until the turn ends"), "precondition: the completion is being held")
      { return fail }
      await h.bargeIn("actually, what's the weather?")
      try await h.settle()
      return firstFailure([
        expectTrue(h.logHas("— barge-in —"), "the barge-in was registered"),
        expectTrue(h.saidSomethingLike("it's clear out"), "the new question is answered"),
        expectTrue(
          h.saidSomethingLike("3 new emails"),
          "the result was lost when the user cut Sai off — the exact §7 failure: \(h.heard())"),
      ])
    },
    Check(name: "an interrupt ends the turn, so the next result is not queued behind a turn nobody will end") {
      let h = bargeHarness()
      h.speakingMs = 5_000
      await h.start()
      await h.user("give me a rundown")
      try await h.advance(100)
      if let fail = expectTrue(h.gate.isModelSpeaking, "precondition: Sai is mid-utterance") {
        return fail
      }
      _ = h.gate.onInterrupted()
      let injected = h.gate.injectNudge("complete", "[agent] done")
      let sent = injected.contains { if case .sendTurn = $0 { return true }; return false }
      return firstFailure([
        expectFalse(h.gate.isModelSpeaking, "the abandoned turn is over"),
        expectTrue(h.gate.shouldDiscardAudio(), "and its stragglers are dropped for a beat"),
        expectTrue(sent, "a result arriving right after an interrupt must not be held"),
      ])
    },
    Check(name: "the abandoned reply does not resume after the barge-in is answered") {
      let h = bargeHarness()
      h.speakingMs = 5_000
      await h.start()
      await h.user("give me a rundown")
      try await h.advance(100)
      await h.bargeIn("stop — what's the weather?")
      try await h.settle()
      let rundowns = h.transcript.filter { $0.speaker == "sai" && $0.text.contains("rundown") }.count
      return firstFailure([
        expectTrue(h.saidSomethingLike("it's clear out"), "the new question is answered"),
        expectEqual(rundowns, 1, "the abandoned answer resumed after the interruption"),
      ])
    },
    Check(name: "a task interrupted mid-report keeps running and still reports") {
      let h = bargeHarness()
      h.speakingMs = 1_500
      await h.start()
      await h.user(task)
      try await h.advance(100)
      await h.bargeIn("hang on — what's the weather?")
      try await h.settle()
      return firstFailure([
        expectTrue(h.agent.callsTo("abort").isEmpty, "nothing should have been aborted"),
        expectEqual(h.agent.started, [task], "the task ran exactly once"),
        expectTrue(h.saidSomethingLike("3 new emails"), "and its result still arrived"),
      ])
    },
    Check(name: "a barge-in followed by a reconnect loses the held result — but says so") {
      let h = bargeHarness()
      h.speakingMs = 2_000
      await h.start()
      await h.user(task)
      try await h.advance(700)
      if let fail = expectTrue(h.logHas("held until the turn ends"), "held") { return fail }
      _ = h.gate.onInterrupted()
      await h.reconnect()
      return firstFailure([
        expectTrue(
          h.logHas("✗ nudge: dropping complete"),
          "a lost completion must be named, not vanish: \(h.log.suffix(6))"),
        expectTrue(h.logHas("session replaced"), "session replaced"),
      ])
    },
    Check(name: "muting holds a result and unmuting offers it once") {
      let h = bargeHarness()
      await h.start()
      await h.user(task)
      await h.setMuted(true)
      try await h.settle()
      if let fail = expectFalse(
        h.saidSomethingLike("3 new emails"),
        "nothing should be spoken while muted: \(h.heard())")
      { return fail }
      await h.setMuted(false)
      try await h.settle()
      let offerings = h.transcript.filter { $0.speaker == "sai" && $0.text.contains("3 new emails") }.count
      return firstFailure([
        expectTrue(h.saidSomethingLike("3 new emails"), "the held result is offered after unmuting"),
        expectEqual(offerings, 1, "and offered once, not replayed as a pile"),
      ])
    },
  ]
}
