/* sai-fi — voice concierge. */

// forwardToAgent and relayToAgent — starting work, and steering work already underway.
//
// The admission rule lives here: a task arriving while the agent is busy or blocked is HELD, not
// folded into the running turn. That is the difference between "I'll do that next" being true and
// being a guess.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/tasks.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/**
 * Start a task, or hold it.
 *
 * Admission: held when an approval is pending OR anything is already in flight. Both mean the
 * agent's turn is not free, and folding a second request into it is how one task's context leaks
 * into another's.
 */
suspend fun applyForwardToAgent(ctx: EffectCtx, effect: Effect.ForwardToAgent) {
  val heldBehindApproval = ctx.state.pendingApprovalId != null

  if (heldBehindApproval || ctx.state.inFlight.isNotEmpty()) {
    // Taken BEFORE the durable write: photos left on the bridge belong to whoever writes next, so a
    // held task must carry its own or drain with the wrong picture attached.
    val attachments = ctx.agent.takePendingAttachments()
    var queued = true

    try {
      val pendingId = ctx.agent.queueTask(effect.text, attachments)
      ctx.state = ctx.state.enqueue(effect.text, Urgency.NORMAL, attachments, pendingId)
    } catch (e: TaskStartedImmediately) {
      // The session turned out idle and it started. It is running, not waiting — say nothing about
      // holding it.
      ctx.state = ctx.state.startTurn(effect.text)
      queued = false
    } catch (e: Exception) {
      // The task now exists NOWHERE — not durably, not in the FSM — and the attachments went with
      // it. Silence here is a user waiting for work nothing ever took.
      ctx.log("queueTask failed: ${e.message}")
      ctx.state = ctx.state.copy(interruptScopeAsked = null)
      ctx.voice.say(COULD_NOT_HOLD_TASK)
      queued = false
    }

    if (!queued) return

    ctx.voice.say(
        if (heldBehindApproval) QUEUED_BEHIND_APPROVAL
        else queuedBehindTask(ctx.state.inFlight.last()))
    return
  }

  // Idle: forward it now. Deliberately no attachments argument — the adapter drains its own stash on
  // the immediate path.
  try {
    ctx.agent.forwardTask(effect.text)
  } catch (e: Exception) {
    // State is otherwise untouched so the request can simply be repeated; in particular startTurn
    // never runs, so the FSM stays idle rather than believing in a task that never started.
    ctx.log("forwardTask failed: ${e.message}")
    ctx.state = ctx.state.copy(interruptScopeAsked = null)
    ctx.voice.say(COULD_NOT_START_TASK)
    return
  }
  ctx.state = ctx.state.startTurn(effect.text)
}

/**
 * Steer the running turn — an answer, a correction, or a scoped cancellation.
 *
 * Never queued: admission holds NEW work only. A follow-up about the task already running is not new
 * work, and holding it until the turn ends would answer a question after the thing that asked it.
 */
suspend fun applyRelayToAgent(ctx: EffectCtx, effect: Effect.RelayToAgent) {
  ctx.agent.steer(effect.answer)

  if (ctx.relayResolvesApproval()) {
    val id = ctx.state.pendingApprovalId!!
    ctx.clearApprovalTimer()
    // Best-effort: a failure here must not undo the steer, which has already landed.
    try {
      ctx.agent.resolveApproval(id, ApprovalDecision.APPROVED)
    } catch (e: Exception) {
      ctx.log("could not resolve the approval the relay answered: ${e.message}")
    }
    // No confirmation is spoken. Asking "shall I go ahead?" about something the user just answered
    // is the double-confirm this rule exists to prevent.
    ctx.state = ctx.state.noPendingApproval()
  }

  // A relay is how a SCOPED cancellation reaches the agent, so it retires any open scope question.
  ctx.state = ctx.state.copy(interruptScopeAsked = null)

  if (ctx.state.pendingApprovalId != null) {
    // The agent is parked inside the approval and will not read the steer until it resolves. Say so
    // to the MODEL — the frame stays awaiting-user, deliberately, so the next utterance still counts
    // as the answer.
    ctx.log("relay went into a turn blocked on an approval")
    ctx.voice.instruct(relayIntoBlockedTurnNudge(ctx.state))
    return
  }

  ctx.state = ctx.state.copy(mode = Mode.WORKING, awaiting = null)
}
