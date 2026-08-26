/* sai-fi — voice concierge. */

// interrupt, and resetSession.
//
// `interrupt` stops the running turn. How far that reaches is the effect's one argument: by default the
// queue goes with it ("stop", "stop it all"), and `scope = running` drops only what is actually running
// so the waiting list carries on. Cancelling PART of a running task is still relayToAgent's job;
// cancelling one QUEUED thing is cancelQueued, which never reaches the agent at all.
//
// Ported from the Android `fsm/InterruptHandler.kt`, which came from cloud-api
// `services/concierge/voice/core/effect-handlers/interrupt.ts` and `session.ts`. The scope, and the
// confirmation in front of `resetSession`, are this client's own — both were added after a device call
// in which "forget it" rotated the conversation and stopped nothing.

import Foundation

extension Concierge {

  /// Stop the running turn, and — unless the scope narrows it — the queue with it.
  ///
  /// Asks first when more than one thing is outstanding — and the counter is running PLUS queued, not
  /// inFlight alone. Since admission holds a second request rather than folding it in, "one running,
  /// one queued" is the same question with the same stakes. A scoped `running` interrupt skips that
  /// question, because the user already answered it: the waiting list is explicitly being kept.
  ///
  /// The scope question is ONE-SHOT: the flag is set before speaking and is the guard's own second
  /// condition, so a second `interrupt` while it is set reads as the user having answered "all of it"
  /// and goes straight through.
  func applyInterrupt(scope: InterruptScope) async {
    if scope == .running {
      await applyDropRunning()
      return
    }

    let outstanding = state.inFlight.count + state.queue.count
    if outstanding > 1, state.interruptScopeAsked != true {
      log("interrupt is ambiguous over \(outstanding) outstanding — asking")
      state.interruptScopeAsked = true
      await voice.say(
        text: interruptScopeQuestion(running: state.inFlight, queued: state.queue.map(\.text)),
        supersedes: nil)
      // Nothing else happens: no abort, no state change beyond the flag.
      return
    }

    try? await agent.abort()
    // Before the state clear — it reads pendingApprovalId.
    await denyApprovalKilledByAbort()
    clearApprovalTimer()

    // No agent event SHOULD follow, because `abort()` stops this device following the turn — that is a
    // CONSEQUENCE of what abort does, and until 2026-08-20 it was merely asserted: nothing tore the
    // reader down, so an aborted turn was read to its natural end and its result was reported to the
    // user as if they had never said stop. The turn still has to be closed out here, because nothing
    // else will do it and the FSM would sit in `working` forever.
    //
    // `abortedTurn` is the belt to that braces: the agent is only ASKED to stop, over a round trip, and
    // a result already in flight can still land. Anything that does is about work the user just
    // cancelled, and must not be read to them.
    state = state.endTurn().clearQueue().noPendingApproval()
    state.mode = .idle
    state.awaiting = nil
    state.abortedTurn = true
  }

  /// "Forget that one" — drop what is RUNNING and let the waiting list carry on.
  ///
  /// The queue is deliberately untouched: that is the whole difference from the unscoped abort, and it
  /// is why the spoken line names what starts next. The drain itself is not done here — `applyEffects`
  /// runs it after every batch, and `mode` is left at IDLE for it to find, so the next task starts the
  /// same way it would have if this turn had simply finished.
  ///
  /// `abort()` has no scope of its own, so a turn carrying MORE than one of the user's requests cannot
  /// honour this one: stopping "the running task" would silently stop the other as well. The FSM says
  /// so rather than picking, the same way an ambiguous cancel asks instead of guessing.
  private func applyDropRunning() async {
    if state.inFlight.isEmpty {
      log("scoped interrupt with nothing running — nothing aborted")
      await voice.instruct(text: nothingRunningNudge(queued: state.queue.map(\.text)))
      return
    }
    if state.inFlight.count > 1 {
      log("scoped interrupt over \(state.inFlight.count) requests in one turn — cannot scope")
      await voice.instruct(text: cannotDropOneOfManyNudge(inFlight: state.inFlight))
      return
    }

    let stopped = state.inFlight
    try? await agent.abort()
    // Before the state clear — it reads pendingApprovalId.
    await denyApprovalKilledByAbort()
    clearApprovalTimer()

    // As above: the abort stops this device following the turn, so no event will close it out and the
    // FSM would sit in `working` forever. The abandon is per-TURN, not per-call, which is what makes
    // this scope work at all: the task drained a moment later opens its own stream and is heard from
    // normally — and `startTurn` clearing `abortedTurn` is the same fact in the guard's terms.
    // `clearQueue` is the one call the unscoped path makes that this one must not.
    state = state.endTurn().noPendingApproval()
    state.mode = .idle
    state.awaiting = nil
    state.abortedTurn = true

    await voice.say(
      text: stoppedRunningLine(stopped: stopped, queued: state.queue.map(\.text)),
      supersedes: QUEUE_POSITION)
  }

  /// Rotate onto a fresh conversation.
  ///
  /// Refused while anything is outstanding: a held task and a live turn both belong to the session
  /// being replaced, and an unanswered approval belongs to the turn that raised it. Rotating under them
  /// orphans work nobody is reading.
  ///
  /// Not a way to stop work — the refusal names what is in the way instead.
  ///
  /// And CONFIRMED before it runs, once. This is the only irreversible effect in the grammar, and its
  /// trigger words overlap with the most disposable thing a user says: on a real call a bare "forget
  /// it" — about a question Sai had just asked — arrived here as `resetSession` and cleared the
  /// conversation. The prompt says not to do that; the prompt is not a guarantee, and there is no undo
  /// behind it. So the first call asks, and only the second rotates.
  func applyResetSession() async {
    if state.hasOutstandingWork() {
      log(
        "reset refused — inFlight=\(state.inFlight.count) queued=\(state.queue.count) "
          + "approval=\(state.pendingApprovalId != nil)")
      await voice.say(text: cannotResetWhileBusy(state), supersedes: nil)
      return
    }

    // Model-facing, and it mutates nothing but the flag: the model asks in its own words, and if the
    // user meant "drop the last thing" it says so and never calls this again. A `say` here would put
    // the question to the user while leaving the model believing the wipe had happened.
    if state.resetConfirmAsked != true {
      log("reset held — confirming with the user first")
      state.resetConfirmAsked = true
      await voice.instruct(text: CONFIRM_RESET_NUDGE)
      return
    }

    switch await agent.resetSession() {
    case .ok:
      // Nothing else is cleared: the guard already established that inFlight, queue and the approval
      // are all empty. The confirmation IS cleared — it was spent on this rotation, and a second wipe
      // deserves to be asked about again. The activity log follows from the same projection, hence
      // republish rather than reset.
      state.resetConfirmAsked = nil
      await publishSessionState()
      await voice.say(text: ROTATED, supersedes: nil)
    case .rateLimited:
      await voice.say(text: RESET_RATE_LIMITED, supersedes: nil)
    case .failed:
      await voice.say(text: RESET_FAILED, supersedes: nil)
    }
  }
}
