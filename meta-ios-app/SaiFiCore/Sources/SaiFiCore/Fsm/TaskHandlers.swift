/* sai-fi — voice concierge. */

// forwardToAgent and relayToAgent — starting work, and steering work already underway.
//
// The admission rule lives here: a task arriving while the agent is busy or blocked is HELD, not
// folded into the running turn. That is the difference between "I'll do that next" being true and
// being a guess.
//
// Ported from the Android `fsm/TaskHandlers.kt`, which came from cloud-api
// `services/concierge/voice/core/effect-handlers/tasks.ts`. An `extension Concierge` rather than a
// free function taking a ctx — see Concierge.swift for where `EffectCtx` went.

import Foundation

extension Concierge {

  /// Start a task, or hold it.
  ///
  /// Admission: held when an approval is pending OR anything is already in flight. Both mean the
  /// agent's turn is not free, and folding a second request into it is how one task's context leaks
  /// into another's.
  ///
  /// Holding is a purely local act — the server is not told, and nothing but `maybeDrainQueue` will
  /// ever start a held task. That is what keeps the spoken order honest, and it is also why a dropped
  /// call loses the promise.
  func applyForwardToAgent(text: String) async {
    let heldBehindApproval = state.pendingApprovalId != nil

    if heldBehindApproval || !state.inFlight.isEmpty {
      // Taken off the bridge now, not when the task drains: the stash belongs to whoever writes next,
      // so a held task that leaves its photo there would drain with someone else's picture attached.
      let attachments = await agent.takePendingAttachments()
      state = state.enqueue(text: text, urgency: .normal, attachments: attachments)
      // Nothing was sent, so there is nothing to fail and nothing to apologise for. The task exists
      // here and only here until `maybeDrainQueue` forwards it.
      let line = heldBehindApproval
        ? QUEUED_BEHIND_APPROVAL
        : queuedBehindTask(running: state.inFlight[state.inFlight.count - 1])
      await voice.say(text: line, supersedes: QUEUE_POSITION)
      return
    }

    // Idle: forward it now. Deliberately no attachments argument — the adapter drains its own stash
    // on the immediate path.
    do {
      _ = try await agent.forwardTask(text: text, attachments: nil)
    } catch {
      // State is otherwise untouched so the request can simply be repeated; in particular startTurn
      // never runs, so the FSM stays idle rather than believing in a task that never started.
      log("forwardTask failed: \(error)")
      state.interruptScopeAsked = nil
      await voice.say(text: COULD_NOT_START_TASK, supersedes: nil)
      return
    }
    state = state.startTurn(text)
  }

  /// Steer the running turn — an answer, a correction, or a scoped cancellation.
  ///
  /// Never queued: admission holds NEW work only. A follow-up about the task already running is not
  /// new work, and holding it until the turn ends would answer a question after the thing that asked
  /// it.
  func applyRelayToAgent(answer: String) async {
    do {
      try await agent.steer(text: answer)
    } catch {
      // Android lets this throw, which skips the approval-clear and mode change. A swallowed
      // failure here would resolve a card the agent never heard the answer to.
      log("steer failed: \(error)")
      return
    }

    if relayResolvesApproval() {
      let id = state.pendingApprovalId!
      clearApprovalTimer()
      // Best-effort: a failure here must not undo the steer, which has already landed.
      do {
        try await agent.resolveApproval(id: id, decision: .approved, selection: nil)
      } catch {
        log("could not resolve the approval the relay answered: \(error)")
      }
      // No confirmation is spoken. Asking "shall I go ahead?" about something the user just answered
      // is the double-confirm this rule exists to prevent.
      state = state.noPendingApproval()
    }

    // A relay is how a SCOPED cancellation reaches the agent, so it retires any open scope question.
    state.interruptScopeAsked = nil

    if state.pendingApprovalId != nil {
      // The agent is parked inside the approval and will not read the steer until it resolves. Say so
      // to the MODEL — the frame stays awaiting-user, deliberately, so the next utterance still counts
      // as the answer.
      log("relay went into a turn blocked on an approval")
      await voice.instruct(text: relayIntoBlockedTurnNudge(state))
      return
    }

    state.mode = .working
    state.awaiting = nil
  }
}
