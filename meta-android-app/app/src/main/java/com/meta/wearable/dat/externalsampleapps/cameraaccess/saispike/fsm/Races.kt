/* sai-fi — voice concierge. */

// The three races between the user's intent and the agent's own schedule.
//
// Held work lives in two uncoordinated places: this FSM's queue, and the durable pending doc the
// agent drains at its own turn boundary. Between the user asking for something and this code acting
// on it, the agent may already have started the very task being cancelled, or ended the turn an
// approval belonged to. Each function here guards one of those.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/races.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** What became of each item we tried to drop. */
data class DropResult(val dropped: List<String>, val started: List<String>)

/**
 * Delete durable docs for held tasks, reporting which ones got away.
 *
 * Guards: *the entry I want to delete may already have been drained.*
 *
 * A throw is counted as `started`, not `dropped`. The outcome is unknown, so claim nothing — an
 * unreported failure here is how "that's off the list" gets said about a task that is still going to
 * run. Mutates no state and speaks nothing; the callers decide what to do with each half.
 */
suspend fun dropDurably(ctx: EffectCtx, items: List<QueuedTask>): DropResult {
  val dropped = mutableListOf<String>()
  val started = mutableListOf<String>()

  for (item in items) {
    val pendingId = item.pendingId
    if (pendingId == null) {
      // No durable doc, so removing it from the FSM queue IS the cancellation.
      dropped += item.text
      continue
    }
    try {
      when (ctx.agent.cancelQueuedTask(pendingId)) {
        CancelOutcome.CANCELLED -> dropped += item.text
        CancelOutcome.ALREADY_STARTED -> started += item.text
      }
    } catch (e: Exception) {
      ctx.log("cancelQueuedTask failed for $pendingId, treating as started: ${e.message}")
      started += item.text
    }
  }
  return DropResult(dropped, started)
}

/**
 * The cancel lost the race — the task the user just cancelled is already running.
 *
 * Aborting without asking is justified narrowly: the user NAMED this task and asked for it to stop,
 * and it is only running because the agent drained it in the moment between. Note that `abort()` has
 * no scope, so this still stops everything else in the turn.
 *
 * Unlike `interrupt`, this does NOT clear the queue — remaining entries survive.
 */
suspend fun abortTaskThatBeatTheCancel(ctx: EffectCtx, started: List<String>) {
  ctx.log("cancel lost the race, aborting: ${readBackList(started)}")
  ctx.agent.abort()
  // Before the state clear — it reads pendingApprovalId.
  denyApprovalKilledByAbort(ctx)
  ctx.clearApprovalTimer()
  ctx.state = ctx.state.endTurn().noPendingApproval().copy(mode = Mode.IDLE, awaiting = null)
  // Spoken after the abort has landed, and says both halves: it had started, and it is stopped now.
  ctx.voice.say(startedThenStoppedLine(started))
}

/**
 * The abort killed the turn an approval belonged to.
 *
 * `denied` is the honest status: the user stopped the task, they did not agree to it. Without this
 * the card can only expire, and the user hears "that request timed out" about work they cancelled
 * minutes ago.
 *
 * Link-only cards are never resolved from here — they are the user's to finish in the app.
 * Best-effort: the abort has already landed and a failure here must not undo it. Mutates no state;
 * the callers do their own clear.
 */
suspend fun denyApprovalKilledByAbort(ctx: EffectCtx) {
  val id = ctx.state.pendingApprovalId ?: return
  if (ctx.state.pendingApprovalLinkOnly == true) return
  try {
    ctx.agent.resolveApproval(id, ApprovalDecision.DENIED)
  } catch (e: Exception) {
    ctx.log("could not deny the approval the abort killed: ${e.message}")
  }
}
