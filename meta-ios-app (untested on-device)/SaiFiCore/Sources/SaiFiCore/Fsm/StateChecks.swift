/* sai-fi — voice concierge. */

// Checks for `State.swift` — the pure transitions.
//
// Ported from `fsm/StateTest.kt`. Each one names the failure it prevents, in the house style: a
// check that only asserts a value tells you nothing when it goes red.

import Foundation

func stateChecks() -> [Check] {
  [
    Check(name: "enqueue appends a normal task") {
      let s = initialState().enqueue(text: "a").enqueue(text: "b")
      return firstFailure([
        expectEqual(s.queue.map(\.text), ["a", "b"], "queue order"),
        expectEqual(s.queue[0].urgency, .normal, "default urgency"),
      ])
    },

    Check(name: "enqueue urgent jumps the queue") {
      // Moving an urgent task up is the whole point of the flag; appending it would make the
      // spoken order and the real order disagree, and the queue never leaves this device so
      // there is nothing else to reconcile against.
      let s = initialState().enqueue(text: "a").enqueue(text: "urgent", urgency: .urgent)
      return expectEqual(s.queue.map(\.text), ["urgent", "a"], "urgent task position")
    },

    Check(name: "enqueue stores empty attachments as absent") {
      // An empty list and no list must not be two different things downstream.
      let s = initialState().enqueue(text: "a", attachments: [])
      return expectTrue(s.queue[0].attachments == nil, "empty attachments normalised to nil")
    },

    Check(name: "startTurn clears every one-shot flag") {
      // The six sites that used to inline this copy had drifted; each leftover is a stale answer
      // to a question the new turn re-asks.
      var s = initialState()
      s.awaiting = .approval
      s.interruptScopeAsked = true
      s.resetConfirmAsked = true
      s.abortedTurn = true
      let next = s.startTurn("task")
      return firstFailure([
        expectEqual(next.mode, .working, "mode"),
        expectTrue(next.awaiting == nil, "awaiting cleared"),
        expectTrue(next.interruptScopeAsked == nil, "interruptScopeAsked cleared"),
        expectTrue(next.resetConfirmAsked == nil, "resetConfirmAsked cleared"),
        expectFalse(next.abortedTurn, "abortedTurn cleared"),
        expectEqual(next.inFlight, ["task"], "task recorded in flight"),
      ])
    },

    Check(name: "endTurn leaves mode and queue alone") {
      // A turn ending normally must still release the queue, so endTurn deliberately does not
      // touch it — clearQueue is the separate, abort-only transition.
      let s = initialState().enqueue(text: "held").startTurn("task").endTurn()
      return firstFailure([
        expectEqual(s.inFlight, [], "inFlight cleared"),
        expectTrue(s.interruptScopeAsked == nil, "scope question cleared with the turn"),
        expectEqual(s.mode, .working, "mode untouched"),
        expectEqual(s.queue.map(\.text), ["held"], "queue untouched"),
      ])
    },

    Check(name: "hasOutstandingWork sees a queued task while idle") {
      // The case `mode` would get wrong: idle, but with work promised out loud.
      let s = initialState().enqueue(text: "held")
      return firstFailure([
        expectEqual(s.mode, .idle, "mode is idle"),
        expectTrue(s.hasOutstandingWork(), "queued task still counts as outstanding"),
      ])
    },

    Check(name: "removeQueued ignores an out-of-range index") {
      let s = initialState().enqueue(text: "a")
      return firstFailure([
        expectEqual(s.removeQueued(-1).queue.count, 1, "negative index"),
        expectEqual(s.removeQueued(7).queue.count, 1, "index past the end"),
        expectEqual(s.removeQueued(0).queue.count, 0, "valid index"),
      ])
    },

    Check(name: "InterruptScope defaults to everything") {
      // "Stop" with no qualifier means stop. Narrowing an unrecognised scope would leave work
      // running that the user believes they stopped.
      return firstFailure([
        expectEqual(InterruptScope.fromWire(nil), .everything, "absent"),
        expectEqual(InterruptScope.fromWire("nonsense"), .everything, "unrecognised"),
        expectEqual(InterruptScope.fromWire(" RUNNING "), .running, "trimmed and lowercased"),
      ])
    },

    Check(name: "Urgency defaults to normal rather than rejecting") {
      return firstFailure([
        expectEqual(Urgency.fromWire(nil), .normal, "absent"),
        expectEqual(Urgency.fromWire("asap"), .normal, "unrecognised"),
        expectEqual(Urgency.fromWire("urgent"), .urgent, "recognised"),
      ])
    },

    Check(name: "noPendingApproval clears all seven fields") {
      var s = initialState()
      s.pendingApprovalId = "a1"
      s.pendingApprovalPrompt = "?"
      s.pendingApprovalLinkOnly = true
      s.pendingApprovalOptions = [ApprovalOption(value: "v", label: "l")]
      s.pendingApprovalQuestions = [ApprovalQuestion(options: [])]
      s.pendingApprovalAllowOther = true
      s.pendingApprovalType = "user_input"
      let next = s.noPendingApproval()
      return firstFailure([
        expectTrue(next.pendingApprovalId == nil, "id"),
        expectTrue(next.pendingApprovalPrompt == nil, "prompt"),
        expectTrue(next.pendingApprovalLinkOnly == nil, "linkOnly"),
        expectTrue(next.pendingApprovalOptions == nil, "options"),
        expectTrue(next.pendingApprovalQuestions == nil, "questions"),
        expectTrue(next.pendingApprovalAllowOther == nil, "allowOther"),
        expectTrue(next.pendingApprovalType == nil, "type"),
      ])
    },

    Check(name: "groupSelections sends a value to the first question that offered it") {
      let q1 = ApprovalQuestion(options: [ApprovalOption(value: "a", label: "A")])
      let q2 = ApprovalQuestion(options: [ApprovalOption(value: "a", label: "A"),
                                          ApprovalOption(value: "b", label: "B")])
      return expectEqual(
        groupSelections(values: ["a", "b"], questions: [q1, q2]),
        [["a"], ["b"]],
        "first-offering wins")
    },

    Check(name: "groupSelections routes free text to the first allowOther question") {
      let q1 = ApprovalQuestion(options: [ApprovalOption(value: "a", label: "A")])
      let q2 = ApprovalQuestion(options: [], allowOther: true)
      return expectEqual(
        groupSelections(values: ["something else"], questions: [q1, q2]),
        [[], ["something else"]],
        "free text placement")
    },

    Check(name: "groupSelections leaves an unanswered question empty") {
      // Deliberate: the agent refuses the whole resolution rather than applying half of it, and
      // that refusal is what gets the model to ask again. Inventing a pick answers for the user.
      let q1 = ApprovalQuestion(options: [ApprovalOption(value: "a", label: "A")])
      let q2 = ApprovalQuestion(options: [ApprovalOption(value: "b", label: "B")])
      return expectEqual(
        groupSelections(values: ["a"], questions: [q1, q2]),
        [["a"], []],
        "unanswered question stays empty")
    },

    Check(name: "groupSelections with no questions is one flat group") {
      return expectEqual(
        groupSelections(values: ["a", "b"], questions: nil),
        [["a", "b"]],
        "single-question card")
    },
  ]
}
