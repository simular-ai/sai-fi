/* sai-fi — voice concierge. */

// interrupt, and resetSession.
//
// `interrupt` has no scope: it aborts the running turn AND drops the queue. Cancelling ONE running
// thing is relayToAgent's job; cancelling one QUEUED thing is cancelQueued, which never reaches the
// agent at all. This is the "stop everything" case, which is why it asks first when more than one
// thing is outstanding.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/interrupt.ts` and
// `session.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/**
 * Stop the running turn and drop the queue.
 *
 * Asks first when more than one thing is outstanding — and the counter is running PLUS queued, not
 * inFlight alone. Since admission holds a second request rather than folding it in, "one running,
 * one queued" is the same question with the same stakes.
 *
 * The scope question is ONE-SHOT: the flag is set before speaking and is the guard's own second
 * condition, so a second `interrupt` while it is set reads as the user having answered "all of it"
 * and goes straight through.
 */
suspend fun applyInterrupt(ctx: EffectCtx) {
  val outstanding = ctx.state.inFlight.size + ctx.state.queue.size
  if (outstanding > 1 && ctx.state.interruptScopeAsked != true) {
    ctx.log("interrupt is ambiguous over $outstanding outstanding — asking")
    ctx.state = ctx.state.copy(interruptScopeAsked = true)
    ctx.voice.say(interruptScopeQuestion(ctx.state.inFlight, ctx.state.queue.map { it.text }))
    // Nothing else happens: no abort, no durable delete, no other state change.
    return
  }

  // Durable docs are deleted BEFORE the abort. The other way round, the abort ends the turn and the
  // agent drains the next queued doc seconds later — so "stop" would launch the next task. The
  // `started` half is ignored here because the abort on the next line stops it anyway.
  val toDrop = ctx.state.queue.toList()
  if (toDrop.isNotEmpty()) {
    val result = dropDurably(ctx, toDrop)
    ctx.log("interrupt dropped ${result.dropped.size} held task(s)")
  }

  ctx.agent.abort()
  // Before the state clear — it reads pendingApprovalId.
  denyApprovalKilledByAbort(ctx)
  ctx.clearApprovalTimer()

  // The abort produces NO agent event — the stream reader is torn down, so no complete, error or
  // idle status will ever arrive for that turn. The handler has to close the turn out itself or the
  // FSM sits in `working` forever.
  ctx.state =
      ctx.state.endTurn().clearQueue().noPendingApproval().copy(mode = Mode.IDLE, awaiting = null)
}

/**
 * Rotate onto a fresh conversation.
 *
 * Refused while anything is outstanding: a held task's pending doc and a live turn both belong to
 * the session being replaced, and an unanswered approval belongs to the turn that raised it.
 * Rotating under them orphans work nobody is reading.
 *
 * Not a way to stop work — the refusal names what is in the way instead.
 */
suspend fun applyResetSession(ctx: EffectCtx) {
  if (ctx.state.hasOutstandingWork()) {
    ctx.log(
        "reset refused — inFlight=${ctx.state.inFlight.size} queued=${ctx.state.queue.size} " +
            "approval=${ctx.state.pendingApprovalId != null}")
    ctx.voice.say(cannotResetWhileBusy(ctx.state))
    return
  }

  when (ctx.agent.resetSession()) {
    ResetOutcome.OK -> {
      // No FSM state is cleared: the guard already established that inFlight, queue and the approval
      // are all empty, so there is nothing to clear. The activity log follows from the same
      // projection, hence republish rather than reset.
      ctx.publishSessionState()
      ctx.voice.say(ROTATED)
    }
    ResetOutcome.RATE_LIMITED -> ctx.voice.say(RESET_RATE_LIMITED)
    ResetOutcome.FAILED -> ctx.voice.say(RESET_FAILED)
  }
}
