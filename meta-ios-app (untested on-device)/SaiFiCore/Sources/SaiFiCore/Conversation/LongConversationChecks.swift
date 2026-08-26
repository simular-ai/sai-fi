/* sai-fi — voice concierge. */

// One long call, where the interesting failures actually live.
//
// The other scenarios are multi-turn but short — two or three user turns each, enough to isolate one
// behaviour. That is the right shape for pinning a rule, and the wrong shape for finding the bugs
// that need STATE to accumulate: a queue two deep that then gets interrupted, a completion that lands
// while muted after a barge-in, a drain that has to survive all of it. Every one of those needs a
// conversation with a history, and a scenario that restarts between beats destroys the very thing it
// is meant to exercise.
//
// Ported from Android `conversation/LongConversationTest.kt`.

import Foundation

private let email = "check my email"
private let table = "book a table for two on Friday"
private let plants = "remind me to water the plants"

private func longBrain() -> ScriptedBrain {
  ScriptedBrain.of(
    ScriptedBrain.whenSaid("forget") { _, _ in
      BrainTurn(speech: "dropped it", calls: callsOf(fc("cancelQueued", ["task": plants])))
    },
    ScriptedBrain.whenSaid("weather") { _, _ in
      BrainTurn(speech: "it's clear and mild out")
    },
    ScriptedBrain.whenSaid("email") { _, _ in
      BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
    },
    ScriptedBrain.whenSaid("table") { _, _ in
      BrainTurn(speech: "sure, after this", calls: callsOf(fc("forwardToAgent", ["text": table])))
    },
    ScriptedBrain.whenSaid("plants") { _, _ in
      BrainTurn(speech: "that too", calls: callsOf(fc("forwardToAgent", ["text": plants])))
    },
    ScriptedBrain.whenSaid("going on") { _, _ in
      BrainTurn(speech: "here's where things stand")
    },
    ScriptedBrain.whenNudged("[agent]") { input, _ in
      BrainTurn(speech: "update — \(input)")
    })
}

func longConversationChecks() -> [Check] {
  [
    Check(name: "a long call carries its queue through a barge-in, a mute and a cancellation") {
      let h = ConversationHarness(brain: longBrain(), speakingMs: 900)
      h.agent.programs += [
        AgentProgram(match: { $0.contains("email") }, beats: conversationTask(summary: "3 new emails", doneAfterMs: 2_000)),
        AgentProgram(match: { $0.contains("table") }, beats: conversationTask(summary: "table booked", doneAfterMs: 1_200)),
        AgentProgram(match: { $0.contains("plants") }, beats: conversationTask(summary: "reminder set", doneAfterMs: 800)),
      ]
      await h.start()
      await h.user(email)
      try await h.advance(300)
      await h.user(table)
      if let fail = expectEqual(h.agent.started, [email], "the second ask must be queued, not started") {
        return fail
      }
      await h.user(plants)
      if let fail = firstFailure([
        expectEqual(h.agent.started, [email], "still only the first has started"),
        expectTrue(
          h.status().contains(table) && h.status().contains(plants),
          "both waiting tasks should be visible"),
      ]) { return fail }
      await h.bargeIn("hang on — what's the weather?")
      if let fail = firstFailure([
        expectTrue(h.saidSomethingLike("clear and mild"), "the interruption is answered"),
        expectTrue(h.status().contains(table), "and the queue is still intact"),
      ]) { return fail }
      await h.setMuted(true)
      try await h.advance(2_200)
      if let fail = expectTrue(
        h.agent.started.contains(table), "the drain should have started the second task")
      { return fail }
      if let fail = expectTrue(
        h.status().contains(plants) && !h.agent.started.contains(plants),
        "precondition: the third must still be WAITING when it is cancelled, or this proves nothing")
      { return fail }
      await h.user("actually, forget the plants")
      await h.setMuted(false)
      try await h.settle()
      return firstFailure([
        expectEqual(
          h.agent.started, [email, table],
          "the cancelled task must never run, and the rest must run in order"),
        expectTrue(h.agent.overlapped.isEmpty, "one at a time throughout"),
        expectTrue(h.saidSomethingLike("3 new emails"), "the first result survived the mute"),
        expectTrue(h.saidSomethingLike("table booked"), "the second result arrived too"),
        expectFalse(
          h.status().contains("NOT STARTED YET"),
          "nothing should still be waiting at the end: \(h.status())"),
        expectTrue(
          h.transcript.count >= 12,
          "this should be a long exchange, not a stub: \(h.transcript.count)"),
      ])
    },
    Check(name: "a result held behind a turn survives being interrupted twice") {
      let h = ConversationHarness(brain: longBrain(), speakingMs: 2_500)
      h.agent.programs += [
        AgentProgram(match: { _ in true }, beats: conversationTask(summary: "3 new emails", doneAfterMs: 1_000)),
      ]
      await h.start()
      await h.user(email)
      try await h.advance(1_200)
      await h.bargeIn("wait — what's the weather?")
      try await h.advance(200)
      await h.bargeIn("sorry, one more time — the weather?")
      try await h.settle()
      return firstFailure([
        expectTrue(h.saidSomethingLike("clear and mild"), "the last question is answered"),
        expectTrue(
          h.saidSomethingLike("3 new emails"),
          "two interruptions in a row must not lose the result: \(h.heard())"),
      ])
    },
  ]
}
