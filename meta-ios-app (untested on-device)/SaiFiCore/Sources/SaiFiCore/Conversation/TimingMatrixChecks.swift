/* sai-fi — voice concierge. */

// The same conversation, at different speeds.
//
// Every barge-in ⇄ queue bug on record is a race: a completion that lands one moment too early is
// held for a turn that then never ends; one that lands a moment too late arrives after the user gave
// up. A single-timing test picks one point on that line and says nothing about the rest of it — and
// the point it picks is usually the one the author found convenient, which is rarely the awkward one.
//
// So the assertions here are INVARIANTS — things that must be true whatever the timing — and each is
// checked across a grid of them. Virtual time makes this nearly free: the whole matrix runs in
// milliseconds because nothing actually waits.
//
// Ported from Android `conversation/TimingMatrixTest.kt`.

import Foundation

private let email = "check my email"
private let table = "book a table for two on Friday"

private struct Timing: CustomStringConvertible {
  let speakingMs: Int64
  let taskMs: Int64
  var description: String { "speaking=\(speakingMs)ms task=\(taskMs)ms" }
}

private let matrix: [Timing] = [
  Timing(speakingMs: 200, taskMs: 100),
  Timing(speakingMs: 200, taskMs: 900),
  Timing(speakingMs: 800, taskMs: 400),
  Timing(speakingMs: 800, taskMs: 800),
  Timing(speakingMs: 2_500, taskMs: 600),
  Timing(speakingMs: 2_500, taskMs: 4_000),
  Timing(speakingMs: 50, taskMs: 50),
]

private func timingTask(summary: String, ms: Int64) -> [AgentBeat] {
  [
    AgentBeat(afterMs: min(20, ms), event: .status(.processing)),
    AgentBeat(afterMs: ms, event: .complete(summary: summary)),
  ]
}

private func twoTaskHarness(_ t: Timing) -> ConversationHarness {
  let brain = ScriptedBrain.of(
    ScriptedBrain.whenSaid("email") { _, _ in
      BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
    },
    ScriptedBrain.whenSaid("table") { _, _ in
      BrainTurn(speech: "right after this one", calls: callsOf(fc("enqueue", ["task": table])))
    },
    ScriptedBrain.whenNudged("[agent]") { input, _ in
      BrainTurn(speech: "that's done — \(input)")
    })
  let h = ConversationHarness(brain: brain, speakingMs: t.speakingMs)
  h.agent.programs += [
    AgentProgram(match: { $0.contains("email") }, beats: timingTask(summary: "3 new emails", ms: t.taskMs)),
    AgentProgram(match: { $0.contains("table") }, beats: timingTask(summary: "table booked", ms: t.taskMs)),
  ]
  return h
}

private func acrossTimings(_ body: (Timing) async throws -> String?) async -> String? {
  var failures: [String] = []
  for t in matrix {
    do {
      if let detail = try await body(t) {
        failures.append("  [\(t)] \(detail)")
      }
    } catch {
      failures.append("  [\(t)] \(error)")
    }
  }
  if failures.isEmpty { return nil }
  return "the same conversation behaves differently depending on timing — "
    + "\(failures.count)/\(matrix.count) cells failed:\n" + failures.joined(separator: "\n")
}

func timingMatrixChecks() -> [Check] {
  [
    Check(name: "a queued task drains and reports, whatever the timing") {
      await acrossTimings { t in
        let h = twoTaskHarness(t)
        await h.start()
        await h.user(email)
        try await h.advance(t.taskMs / 4)
        await h.user(table)
        try await h.settle()
        return firstFailure([
          expectEqual(h.agent.started, [email, table], "tasks ran as \(h.agent.started), expected both in order"),
          expectTrue(h.agent.overlapped.isEmpty, "two tasks ran at once: \(h.agent.overlapped)"),
          expectTrue(h.saidSomethingLike("3 new emails"), "the first result never reached the user"),
          expectTrue(h.saidSomethingLike("table booked"), "the second result never reached the user"),
          expectFalse(h.status().contains("NOT STARTED YET"), "the queue never emptied: \(h.status())"),
        ])
      }
    },
    Check(name: "a barge-in never costs the result, whatever the timing") {
      await acrossTimings { t in
        let brain = ScriptedBrain.of(
          ScriptedBrain.whenSaid("email") { _, _ in
            BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
          },
          ScriptedBrain.whenSaid("weather") { _, _ in
            BrainTurn(speech: "it's clear out")
          },
          ScriptedBrain.whenNudged("[agent]") { input, _ in
            BrainTurn(speech: "that's done — \(input)")
          })
        let h = ConversationHarness(brain: brain, speakingMs: t.speakingMs)
        h.agent.programs += [
          AgentProgram(match: { _ in true }, beats: timingTask(summary: "3 new emails", ms: t.taskMs)),
        ]
        await h.start()
        await h.user(email)
        try await h.advance(t.taskMs / 2)
        await h.bargeIn("hang on — what's the weather?")
        try await h.settle()
        return firstFailure([
          expectTrue(h.saidSomethingLike("it's clear out"), "the interrupting question went unanswered"),
          expectTrue(
            h.saidSomethingLike("3 new emails"),
            "cutting Sai off cost the result — ON_DEVICE_CHECK §7. Heard: \(h.heard())"),
          expectTrue(h.agent.callsTo("abort").isEmpty, "a barge-in aborted the running task"),
        ])
      }
    },
    Check(name: "an interrupt at any instant of the task still lets the result through") {
      let taskMs: Int64 = 500
      var failures: [String] = []
      for at in [Int64]([0, 50, 200, 450, 490, 499, 500, 501, 550, 900]) {
        let brain = ScriptedBrain.of(
          ScriptedBrain.whenSaid("email") { _, _ in
            BrainTurn(speech: "on it", calls: callsOf(fc("forwardToAgent", ["text": email])))
          },
          ScriptedBrain.whenSaid("weather") { _, _ in
            BrainTurn(speech: "it's clear out")
          },
          ScriptedBrain.whenNudged("[agent]") { input, _ in
            BrainTurn(speech: "that's done — \(input)")
          })
        let h = ConversationHarness(brain: brain, speakingMs: 700)
        h.agent.programs += [
          AgentProgram(match: { _ in true }, beats: timingTask(summary: "3 new emails", ms: taskMs)),
        ]
        await h.start()
        await h.user(email)
        try await h.advance(at)
        await h.bargeIn("hang on — what's the weather?")
        try await h.settle()
        if !h.saidSomethingLike("3 new emails") {
          failures.append("  interrupt at \(at)ms (task completes at \(taskMs)ms) — result lost")
        }
      }
      if failures.isEmpty { return nil }
      return "an interrupt landing at certain moments loses the result:\n" + failures.joined(separator: "\n")
    },
  ]
}
