/* sai-fi — voice concierge. */

// Intrinsic FSM updates — what an agent event means regardless of what the model decides to say about
// it. These are the transitions that must be right even when the brain emits no effects at all, which
// is why they live apart from the effect handlers.
//
// The `switch` here is deliberately exhaustive with NO default branch. That exhaustiveness is what
// forced `approvalResolved` and `sessionState` to be answered for rather than silently ignored — a new
// event kind should be a compile error, not a no-op nobody chose.
//
// Ported from the Android `fsm/AgentIngest.kt`, which came from cloud-api
// `services/concierge/voice/core/agent-ingest.ts`.

import Foundation

/// The approval timer, as the ingest sees it. Owned by the orchestrator.
public protocol IngestTimers {
  /// Arm the pre-expiry ping. A nil expiry means the request never auto-expires — no timer.
  func scheduleApprovalTimeout(expiresAt: Int64?)

  func clearApprovalTimer()
}

public func ingestAgentEvent(
  state: ConciergeState,
  event: AgentEvent,
  timers: IngestTimers,
  log: (String) -> Void = { _ in }
) -> ConciergeState {
  switch event {

  case .status(let status):
    return applyAgentStatus(state, status)

  // Internal agent activity the user doesn't hear — deliberately does NOT reset the dead-air backoff,
  // so "still working" check-ins keep spacing out over a long task instead of resetting to the base
  // interval.
  case .progress, .text:
    return state

  // News about delivery, not about the turn. It arrives from inside the write, before the stream that
  // would move the FSM even exists, so there is no mode change to make here — the `status: processing`
  // that follows still starts the turn.
  case .notice:
    return state

  // Resolved out-of-band (the app, or another channel). Intrinsically a no-op: the FSM's own pending
  // state is cleared by whatever ends the turn, and clearing it here would race the effect handler
  // that resolved it.
  case .approvalResolved:
    return state

  // The FSM's own projection of itself. It travels on the AgentEvent frame because that is the frame
  // the client's ActivityLog reads, not because it ever arrives FROM the agent — so ingesting one
  // would be the FSM reacting to its own echo.
  case .sessionState:
    return state

  case .approvalRequest(let request):
    // Logged on ARRIVAL, before anything acts on it, because which branch this took is the first
    // question asked of every approval bug. On 2026-07-31 a request whose card showed options reached
    // us with none — the concierge had nothing to read back. If options go missing again, this line
    // says whether they arrived.
    log(
      "approval-request \(request.id): type=\(request.approvalType) "
        + "options=\(request.options?.count ?? 0) linkOnly=\(request.isLinkOnly) "
        + "allowOther=\(request.allowOther == true)")
    timers.scheduleApprovalTimeout(expiresAt: request.expiresAt)

    var next = state
    next.mode = .awaitingUser
    next.pendingApprovalId = request.id
    next.pendingApprovalPrompt = request.description.isEmpty ? request.title : request.description
    next.pendingApprovalLinkOnly = request.isLinkOnly
    next.pendingApprovalType = request.approvalType
    next.pendingApprovalOptions = request.options
    next.pendingApprovalQuestions = request.questions
    next.pendingApprovalAllowOther = request.allowOther
    // Link-only requests can't be resolved by voice — the user completes them in the browser — so the
    // next utterance is NOT their answer.
    next.awaiting = request.isLinkOnly ? nil : .approval
    return next

  case .complete, .error:
    timers.clearApprovalTimer()
    // Terminal for the whole turn, so every request it carried is finished — even if we are parked in
    // awaiting-user on an approval that will now never be answered.
    //
    // Unconditional on purpose. This used to be guarded by `mode == working` and never cleared the
    // approval, so a turn that ended with one outstanding left the FSM parked in awaiting-user with a
    // dead pendingApprovalId — and both stick for the rest of the call. Draining the queue needs
    // `idle`, so queued work never ran (including work promised out loud), and every later forward
    // was held behind an approval nobody could answer. Nothing started again, ever. The turn is over:
    // say so unconditionally.
    var next = state.endTurn().noPendingApproval()
    next.mode = .idle
    next.awaiting = nil
    return next
  }
}

/// What the agent's coarse status means for the session's mode.
public func applyAgentStatus(_ state: ConciergeState, _ status: AgentStatus) -> ConciergeState {
  switch status {
  case .processing, .summarizing, .aborting:
    return state.withMode(.working)

  // Don't clobber awaiting-user (a pending approval) with idle — and while parked there the turn is
  // blocked, not over, so its in-flight requests stand.
  //
  // Guarded here and NOT guarded for complete/error above. That asymmetry is the design: a stray idle
  // must not end a turn that is merely blocked, while a completion genuinely ends one.
  case .idle, .error:
    return state.mode == .working ? state.endTurn().withMode(.idle) : state
  }
}
