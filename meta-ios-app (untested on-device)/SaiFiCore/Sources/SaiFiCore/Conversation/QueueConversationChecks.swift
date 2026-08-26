/* sai-fi — voice concierge. */

// The queue, end to end: the model decides, the FSM admits, the agent runs, and the user is told.
//
// The golden catalog already pins each of those in isolation. What it cannot pin is the JOIN, because
// the events it replays were written by hand on the assumption that the drain fired. Here the agent
// only produces events for tasks something actually forwarded, so "Sai said it would do that next,
// and then it ran, and then the user heard about it" is an emergent property of the loop rather than
// a premise of the fixture.
//
// That is the failure ON_DEVICE_CHECK §6a calls the highest risk on the whole device:
//   "if Sai says 'I'll do that next' and then it never runs, the drain never fired."
//
// Ported from Android `conversation/QueueConversationTest.kt`.

import Foundation

private let email = "check my email"
private let table = "book a table for two on Friday"

private func twoTaskBrain() -> ScriptedBrain {
  ScriptedBrain.of(
    ScriptedBrain.whenSaid("email") { _, _ in
      BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
    },
    ScriptedBrain.whenSaid("table") { _, state in
      if state.isWorking() {
        return BrainTurn(
          speech: "I'll start that as soon as this one's done",
          calls: callsOf(fc("enqueue", ["task": table])))
      }
      return BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": table])))
    },
    ScriptedBrain.whenNudged("[agent]") { input, _ in
      BrainTurn(speech: "that's done — \(input)")
    })
}

private func queueHarness(_ brain: ScriptedBrain = twoTaskBrain()) -> ConversationHarness {
  let h = ConversationHarness(brain: brain)
  h.agent.programs += [
    AgentProgram(match: { $0.contains("email") }, beats: conversationTask(summary: "3 new emails", doneAfterMs: 600)),
    AgentProgram(match: { $0.contains("table") }, beats: conversationTask(summary: "table booked", doneAfterMs: 400)),
  ]
  return h
}

func queueConversationChecks() -> [Check] {
  [
    Check(name: "a task queued behind a running one actually runs, and its result reaches the user") {
      let h = queueHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      if let fail = expectTrue(
        h.saidSomethingLike("as soon as this one's done"),
        "the second ask must be admitted to the queue, not folded into the running turn")
      { return fail }
      if let fail = expectEqual(h.agent.started, [email], "nothing should have started it yet") {
        return fail
      }
      try await h.settle()
      return firstFailure([
        expectEqual(h.agent.started, [email, table], "drain order"),
        expectTrue(h.agent.overlapped.isEmpty, "one task at a time"),
        expectTrue(h.saidSomethingLike("3 new emails"), "the first result was never spoken"),
        expectTrue(h.saidSomethingLike("table booked"), "the second result was never spoken"),
      ])
    },
    Check(name: "a queued task is not called underway while it is still waiting") {
      let h = queueHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      let status = h.status()
      return firstFailure([
        expectTrue(status.contains("Still working"), "something should be reported as running: \(status)"),
        expectTrue(status.contains(table), "the waiting task should be named: \(status)"),
        expectTrue(
          status.contains("NOT STARTED YET"),
          "a waiting task must be described as not started, not as underway: \(status)"),
        expectTrue(
          status.contains("never as underway"),
          "and the model must be told not to call it underway: \(status)"),
      ])
    },
    Check(name: "the queue stops being mentioned the moment it drains") {
      let h = queueHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      if let fail = expectTrue(h.status().contains("NOT STARTED YET"), "queued") { return fail }
      try await h.settle()
      return expectFalse(
        h.status().contains("NOT STARTED YET"),
        "nothing is waiting any more, so nothing should still be listed: \(h.status())")
    },
    Check(name: "two queued behind one run in the order they were asked for") {
      let third = "water the plants"
      let brain = ScriptedBrain.of(
        ScriptedBrain.whenSaid("email") { _, _ in
          BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
        },
        ScriptedBrain.whenSaid("table") { _, _ in
          BrainTurn(speech: "after this", calls: callsOf(fc("enqueue", ["task": table])))
        },
        ScriptedBrain.whenSaid("plants") { _, _ in
          BrainTurn(speech: "after that", calls: callsOf(fc("enqueue", ["task": third])))
        },
        ScriptedBrain.whenNudged("[agent]") { input, _ in
          BrainTurn(speech: "done — \(input)")
        })
      let h = queueHarness(brain)
      h.agent.programs += [
        AgentProgram(match: { $0.contains("plants") }, beats: conversationTask(summary: "plants watered", doneAfterMs: 300)),
      ]
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      await h.user(third)
      try await h.settle()
      return firstFailure([
        expectEqual(h.agent.started, [email, table, third], "FIFO, and every one of them run"),
        expectTrue(h.saidSomethingLike("plants watered"), "third result"),
      ])
    },
    Check(name: "cancelling a queued task stops it running at all") {
      let brain = ScriptedBrain.of(
        ScriptedBrain.whenSaid("forget") { _, _ in
          BrainTurn(speech: "dropped it", calls: callsOf(fc("cancelQueued", ["task": table])))
        },
        ScriptedBrain.whenSaid("email") { _, _ in
          BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
        },
        ScriptedBrain.whenSaid("table") { _, _ in
          BrainTurn(speech: "after this", calls: callsOf(fc("enqueue", ["task": table])))
        },
        ScriptedBrain.whenNudged("[agent]") { input, _ in
          BrainTurn(speech: "done — \(input)")
        })
      let h = queueHarness(brain)
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      await h.user("actually, forget the table booking")
      try await h.settle()
      return firstFailure([
        expectEqual(h.agent.started, [email], "a cancelled task must never start"),
        expectFalse(
          h.status().contains(table),
          "and must not still be listed once it is gone: \(h.status())"),
      ])
    },
    Check(name: "reordering starts the waiting task without stopping the running one") {
      let brain = ScriptedBrain.of(
        ScriptedBrain.whenSaid("email") { _, _ in
          BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
        },
        ScriptedBrain.whenSaid("table") { _, _ in
          BrainTurn(speech: "after this", calls: callsOf(fc("enqueue", ["task": table])))
        },
        ScriptedBrain.whenSaid("first") { _, _ in
          BrainTurn(speech: "starting that now", calls: callsOf(fc("sendQueuedNow", ["task": table])))
        },
        ScriptedBrain.whenNudged("[agent]") { input, _ in
          BrainTurn(speech: "done — \(input)")
        })
      let h = queueHarness(brain)
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      await h.user("do the Friday booking first")
      try await h.settle()
      return firstFailure([
        expectTrue(h.agent.started.contains(table), "the promoted task must actually start"),
        expectTrue(h.agent.callsTo("abort").isEmpty, "nothing needed to stop, so nothing should have been aborted"),
        expectTrue(h.saidSomethingLike("3 new emails"), "the running task still finished"),
      ])
    },
    Check(name: "promoting a task replaces the line that said it would wait, rather than saying both") {
      let brain = ScriptedBrain.of(
        ScriptedBrain.whenSaid("first") { _, _ in
          BrainTurn(speech: "sure", calls: callsOf(fc("sendQueuedNow", ["task": table])))
        },
        ScriptedBrain.whenSaid("email") { _, _ in
          BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
        },
        ScriptedBrain.whenSaid("table") { _, _ in
          BrainTurn(speech: "okay", calls: callsOf(fc("forwardToAgent", ["text": table])))
        },
        ScriptedBrain.whenNudged("[agent]") { input, _ in
          BrainTurn(speech: "done — \(input)")
        })
      let h = queueHarness(brain)
      h.speakingMs = 2_000
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      try await h.advance(50)
      await h.user("do the Friday booking first")
      try await h.advance(3_000)
      let told = h.heard()
      return firstFailure([
        expectFalse(
          told.contains("as soon as i'm done"),
          "the stale line was spoken alongside the new one, in one breath: \(told)"),
        expectTrue(h.logHas("replacing the stale one"), "and the replacement should be visible in the log"),
        expectTrue(
          h.logHas("dropping speak:queue-position"),
          "the replaced line must be dropped, not barged in: \(h.log.joined(separator: " | "))"),
      ])
    },
    Check(name: "a completion held with a queue-position still goes out after Sai spoke") {
      let h = queueHarness()
      await h.start()
      _ = h.gate.onSaiTranscript("talking")
      _ = h.gate.injectNudge("speak:queue-position", "first")
      _ = h.gate.injectNudge("complete", "second")
      let flushed = h.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true)
      let sent = flushed.compactMap { action -> String? in
        if case .sendTurn(let text) = action { return text }
        return nil
      }
      guard sent.count == 1 else { return "expected one send, got \(sent)" }
      return firstFailure([
        expectTrue(sent[0].contains("second"), "the completion should survive: \(sent)"),
        expectFalse(sent[0].contains("first"), "queue-position must not ride along after it already spoke"),
      ])
    },
    Check(name: "a forward the agent refuses is admitted to, not silently swallowed") {
      let h = queueHarness()
      h.agent.failNextSend = true
      await h.start()
      await h.user(email)
      try await h.settle()
      let working = await h.state().isWorking()
      return firstFailure([
        expectEqual(h.agent.started, [String](), "nothing started"),
        expectFalse(working, "a task that never started must not be reported as running"),
        expectFalse(h.heard().isEmpty, "the user was told nothing about a task that never started: \(h.heard())"),
      ])
    },
  ]
}
