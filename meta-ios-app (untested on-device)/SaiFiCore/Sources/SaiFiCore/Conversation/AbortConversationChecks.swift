/* sai-fi — voice concierge. */

// Stopping work, end to end: the model decides, the FSM asks first, and only then does anything die.
//
// The golden catalog pins the interrupt DECISION (S4, S4b–S4e, S46) against a fake agent. What it
// cannot pin is the join, and the gap this file exists for was a specific one: across the whole suite,
// every assertion about `abort` was a NEGATIVE one — `callsTo("abort").isEmpty()`, proving a barge-in
// or a reorder did not cancel anything. `ScriptedAgent.abortRunning` existed and nothing ever drove
// it. The path that actually stops a task therefore ran, off-device, exactly never.
//
// Ported from Android `conversation/AbortConversationTest.kt`.

import Foundation

private let email = "check my email"
private let table = "book a table for two on Friday"

private func stoppingBrain() -> ScriptedBrain {
  ScriptedBrain.of(
    ScriptedBrain.whenSaid("email") { _, _ in
      BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
    },
    ScriptedBrain.whenSaid("table") { _, state in
      if state.isWorking() {
        return BrainTurn(speech: "that's next", calls: callsOf(fc("enqueue", ["task": table])))
      }
      return BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": table])))
    },
    ScriptedBrain.whenSaid("stop") { _, _ in
      BrainTurn(speech: "", calls: callsOf(fc("interrupt")))
    },
    ScriptedBrain.whenSaid("all of it") { _, _ in
      BrainTurn(speech: "", calls: callsOf(fc("interrupt")))
    },
    ScriptedBrain.whenNudged("[agent]") { input, _ in
      BrainTurn(speech: "that's done — \(input)")
    })
}

private func abortHarness() -> ConversationHarness {
  let h = ConversationHarness(brain: stoppingBrain())
  h.agent.programs += [
    AgentProgram(match: { $0.contains("email") }, beats: conversationTask(summary: "3 new emails", doneAfterMs: 5_000)),
    AgentProgram(match: { $0.contains("table") }, beats: conversationTask(summary: "table booked", doneAfterMs: 5_000)),
  ]
  return h
}

func abortConversationChecks() -> [Check] {
  [
    Check(name: "stopping everything really aborts the running task, and the queue goes with it") {
      let h = abortHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user(table)
      try await h.advance(100)
      await h.user("stop")
      try await h.advance(100)
      if let fail = expectTrue(
        h.agent.callsTo("abort").isEmpty,
        "an interrupt with work outstanding must ask before killing anything")
      { return fail }
      await h.user("all of it")
      try await h.advance(200)
      if let fail = expectFalse(h.agent.callsTo("abort").isEmpty, "the abort never reached the agent") {
        return fail
      }
      let queueEmpty = await h.state().queue.isEmpty
      if let fail = expectTrue(
        queueEmpty, "the queue must go too, or the next task starts after the stop")
      { return fail }
      try await h.settle()
      return firstFailure([
        expectFalse(h.saidSomethingLike("3 new emails"), "an aborted task was reported as done"),
        expectFalse(h.saidSomethingLike("table booked"), "the queued task ran anyway"),
        expectEqual(h.agent.started, [email], "only the first task ever started"),
      ])
    },
    Check(name: "a server that ignores the abort still cannot report the stopped task") {
      let h = abortHarness()
      h.agent.abortStopsTheRun = false
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user("stop")
      try await h.advance(100)
      if let fail = firstFailure([
        expectFalse(h.agent.callsTo("abort").isEmpty, "the abort never reached the agent"),
        expectFalse(
          await h.state().isWorking(),
          "the FSM was left believing the aborted task is still running"),
      ]) { return fail }
      try await h.advance(10_000)
      try await h.settle()
      return firstFailure([
        expectTrue(
          h.agent.producedAfterAbandon > 0,
          "the agent stopped producing, so the silence below proves nothing — the case under test is a "
            + "server that ignored the abort and carried on"),
        expectEqual(h.agent.deliveriesAfterAbandon, 0, "events from an abandoned turn reached the FSM"),
        expectFalse(
          h.saidSomethingLike("3 new emails"), "a stopped task's result was read out to the user"),
        expectFalse(h.status().contains("3 new emails"), "a stopped task was reported as finished"),
        expectFalse(await h.state().isWorking(), "the FSM was restarted by a phantom event"),
      ])
    },
    Check(name: "a result that outruns the abort is not read to the user") {
      let h = abortHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user("stop")
      try await h.advance(100)
      if let fail = expectFalse(h.agent.callsTo("abort").isEmpty, "the abort never reached the agent") {
        return fail
      }
      await h.deliverAgentEvent(.complete(summary: "3 new emails"))
      try await h.settle()
      return firstFailure([
        expectFalse(h.saidSomethingLike("3 new emails"), "the answer to a cancelled request was read out"),
        expectFalse(h.status().contains("3 new emails"), "a cancelled task was reported as finished"),
        expectFalse(await h.state().isWorking(), "a phantom completion restarted the FSM"),
      ])
    },
    Check(name: "the next task's result is still read, so the abort does not deafen the call") {
      let h = abortHarness()
      await h.start()
      await h.user(email)
      try await h.advance(100)
      await h.user("stop")
      try await h.advance(100)
      await h.user(table)
      try await h.advance(100)
      try await h.advance(10_000)
      try await h.settle()
      return firstFailure([
        expectEqual(h.agent.started, [email, table], "the new task never started"),
        expectTrue(
          h.saidSomethingLike("table booked"),
          "the new task's result was swallowed with the old one"),
      ])
    },
    Check(name: "a stop with nothing running starts nothing and leaves the FSM idle") {
      let h = abortHarness()
      await h.start()
      await h.user("stop")
      try await h.advance(200)
      let abortCalls = h.agent.calls.map(\.method).filter { $0 == "abort" }
      return firstFailure([
        expectTrue(h.agent.started.isEmpty, "nothing should have been started"),
        expectFalse(await h.state().isWorking(), "the FSM must not be left believing work is in flight"),
        expectEqual(abortCalls, ["abort"], "abort still goes out"),
      ])
    },
  ]
}
