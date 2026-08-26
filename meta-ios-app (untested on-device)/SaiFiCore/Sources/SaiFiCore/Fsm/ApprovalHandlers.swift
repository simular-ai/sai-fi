/* sai-fi — voice concierge. */

// approve / deny / chooseOption.
//
// The security invariant is here: a pick must be an option that was actually offered. The selection is
// handed to the agent as the user's TRUSTED choice, so a value that was never on the table —
// hallucinated by the model, mistranscribed from speech — must not be able to resolve a guardrail.
// `allowOther` is the one exception, and it is explicit.
//
// `denyApprovalKilledByAbort` also lives here. It was the last survivor of the Kotlin's `Races.kt`,
// whose other two guards existed only because the agent could start a task this FSM was holding. It
// cannot any more — the queue never leaves the device — but an abort can still kill the turn an
// approval belonged to, and that one is not a race with the queue at all.
//
// Ported from the Android `fsm/ApprovalHandlers.kt`, which came from cloud-api
// `services/concierge/voice/core/effect-handlers/approvals.ts` and `races.ts`.

import Foundation

extension Concierge {

  /// Resolve a pending approval with a plain decision.
  ///
  /// With nothing pending this is a model misfire and is IGNORED — silently, with no state change and
  /// nothing sent to the agent. Faking progress here would be worse: a task starts via forwardToAgent,
  /// never via approve.
  func applyApprovalDecision(_ effect: Effect) async {
    guard let id = state.pendingApprovalId else {
      log("approval decision with nothing pending — ignoring")
      return
    }

    let decision: ApprovalDecision
    if case .deny = effect {
      decision = .denied
    } else {
      decision = .approved
    }

    // Link-only cards are completed by the user in the browser; the server rejects a resolution for
    // them. The FSM state still clears — the concierge is no longer waiting on a spoken answer.
    if state.pendingApprovalLinkOnly != true {
      // Do not swallow: a failed POST must keep the request PENDING, or the call deadlocks waiting
      // for an answer it believes it already gave. Android lets this throw for the same reason.
      do {
        try await agent.resolveApproval(id: id, decision: decision, selection: nil)
      } catch {
        log("approval resolve failed: \(error)")
        return
      }
    }

    clearApprovalTimer()
    state = state.noPendingApproval()
    state.mode = .working
    state.awaiting = nil
  }

  /// Resolve a `choice` approval with the option(s) the user picked.
  ///
  /// A rejected pick — at this guard or at the bridge's own write boundary — keeps the request PENDING
  /// and its timer running, and tells the model to re-present. It never says anything to the user: the
  /// client has already tool-acked the call, so without a nudge the model would go on to confirm a pick
  /// that never happened.
  func applyChooseOption(values: [String]) async {
    guard let id = state.pendingApprovalId else {
      log("chooseOption with nothing pending — ignoring")
      return
    }

    if let offered = state.pendingApprovalOptions, state.pendingApprovalAllowOther != true {
      // Exact string equality against the option VALUE — not the label, not case-insensitive, not
      // trimmed. A single un-offered value rejects the whole call.
      let bad = values.filter { v in !offered.contains(where: { $0.value == v }) }
      if !bad.isEmpty {
        log("chooseOption rejected, not offered: \(bad)")
        await voice.instruct(text: RESELECT_NUDGE)
        return
      }
    }

    // Grouped per question on the way out — a spoken pick carries no question index, and the agent
    // resolves them positionally.
    let selection = ApprovalSelection(
      selections: groupSelections(values: values, questions: state.pendingApprovalQuestions))

    do {
      try await agent.resolveApproval(id: id, decision: .approved, selection: selection)
    } catch {
      // The agent rejected it for something this guard cannot see — most likely a question left
      // unanswered, which it refuses rather than half-applying. Keep the pending state AND the timer
      // so the choice is still resolvable, and tell the model to re-present.
      log("bridge rejected the selection: \(error)")
      await voice.instruct(text: RESELECT_NUDGE)
      return
    }

    clearApprovalTimer()
    state = state.noPendingApproval()
    state.mode = .working
    state.awaiting = nil
  }

  /// The abort killed the turn an approval belonged to.
  ///
  /// `denied` is the honest status: the user stopped the task, they did not agree to it. Without this
  /// the card can only expire, and the user hears "that request timed out" about work they cancelled
  /// minutes ago.
  ///
  /// Link-only cards are never resolved from here — they are the user's to finish in the app.
  /// Best-effort: the abort has already landed and a failure here must not undo it. Mutates no state;
  /// the callers do their own clear.
  func denyApprovalKilledByAbort() async {
    guard let id = state.pendingApprovalId else { return }
    if state.pendingApprovalLinkOnly == true { return }
    do {
      try await agent.resolveApproval(id: id, decision: .denied, selection: nil)
    } catch {
      log("could not deny the approval the abort killed: \(error)")
    }
  }
}
