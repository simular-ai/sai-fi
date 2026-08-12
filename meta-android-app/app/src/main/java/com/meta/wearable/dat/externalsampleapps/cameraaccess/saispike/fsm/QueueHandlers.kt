/* sai-fi — voice concierge. */

// enqueue / sendQueuedNow / cancelQueued — the waiting list.
//
// The distinction that matters throughout: an entry with a `pendingId` is DURABLE. It exists as a
// pending doc and the AGENT decides when it runs, so the FSM entry is a display copy. An entry
// without one came from the model's `enqueue` and exists nowhere else — nothing but this code will
// ever start it.
//
// For cancelQueued, no agent traffic at all is the CORRECT outcome for a task the agent was never
// told about.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/queue.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/** Hold a task in the FSM only. No durable write, no agent traffic, nothing spoken. */
fun applyEnqueue(ctx: EffectCtx, effect: Effect.Enqueue) {
  ctx.state = ctx.state.enqueue(effect.task, effect.urgency)
}

/**
 * "Do that first" — start a waiting task now, without stopping anything.
 *
 * Every refusal below is model-facing and mutates nothing: the model has to re-ask, and telling the
 * user something moved when it didn't is the failure being avoided.
 */
suspend fun applySendQueuedNow(ctx: EffectCtx, effect: Effect.SendQueuedNow) {
  val queue = ctx.state.queue
  if (queue.isEmpty()) {
    ctx.voice.instruct(NOTHING_QUEUED_TO_RUSH_NUDGE)
    return
  }

  if (effect.task == null && queue.size > 1) {
    // Do NOT guess the head: rushing the wrong task starts the wrong work and reports the right one.
    ctx.voice.instruct(whichQueuedToRushNudge(queue.map { it.text }))
    return
  }

  val index = if (effect.task != null) matchQueued(queue, effect.task) else 0
  if (index < 0) {
    ctx.voice.instruct(noQueuedMatchNudge(queue.map { it.text }))
    return
  }

  val item = queue[index]
  val pendingId = item.pendingId

  if (pendingId == null) {
    // No doc to escalate — but nothing else will ever start it either. Promoting it by forwarding is
    // the same outcome.
    ctx.state = ctx.state.removeQueued(index)
    ctx.agent.forwardTask(item.text, item.attachments)
    ctx.state = ctx.state.startTurn(item.text)
    ctx.voice.say(startingNowLine(listOf(item.text)))
    return
  }

  val outcome = ctx.agent.sendQueuedNow(pendingId)
  ctx.state = ctx.state.removeQueued(index)

  // Both outcomes end in startTurn: the task is in the running turn either way, and startTurn clears
  // interruptScopeAsked deliberately — the escalated task joins the turn, so the next interrupt must
  // find two requests again rather than aborting the newly-added one without asking.
  when (outcome) {
    SendNowOutcome.ALREADY_STARTED -> {
      // Nothing was moved. "I've moved it up" would be a claim about the wrong thing.
      ctx.state = ctx.state.startTurn(item.text)
      ctx.voice.say(alreadyRunningLine(listOf(item.text)))
    }
    SendNowOutcome.SENT -> {
      ctx.state = ctx.state.startTurn(item.text)
      ctx.voice.say(startingNowLine(listOf(item.text)))
    }
  }
}

/**
 * Drop waiting work. With no task named, all of it.
 *
 * A cancel that loses the race is handled by [abortTaskThatBeatTheCancel] — note the consequence:
 * because `abort()` has no scope, losing the race on ONE queued task tears down the whole running
 * turn, and no scope question is asked.
 */
suspend fun applyCancelQueued(ctx: EffectCtx, effect: Effect.CancelQueued) {
  val queue = ctx.state.queue
  if (queue.isEmpty()) {
    ctx.voice.instruct(NOTHING_QUEUED_NUDGE)
    return
  }

  if (effect.task == null) {
    val all = queue.toList()
    // The FSM queue is emptied optimistically, before any I/O.
    ctx.state = ctx.state.clearQueue()
    val result = dropDurably(ctx, all)
    // "Off the list" is spoken BEFORE the abort line; items reported as started are not put back.
    if (result.dropped.isNotEmpty()) ctx.voice.say(droppedQueuedLine(result.dropped))
    if (result.started.isNotEmpty()) abortTaskThatBeatTheCancel(ctx, result.started)
    return
  }

  val index = matchQueued(queue, effect.task)
  if (index < 0) {
    // Claims nothing, and deliberately does not touch the running task.
    ctx.voice.instruct(noQueuedMatchNudge(queue.map { it.text }))
    return
  }

  val item = queue[index]
  ctx.state = ctx.state.removeQueued(index)
  val result = dropDurably(ctx, listOf(item))
  if (result.dropped.isNotEmpty()) ctx.voice.say(droppedQueuedLine(result.dropped))
  if (result.started.isNotEmpty()) abortTaskThatBeatTheCancel(ctx, result.started)
}
