/* sai-fi — voice concierge. */

// enqueue / sendQueuedNow / cancelQueued — the waiting list.
//
// The waiting list is local and nothing else knows it exists. The agent is told about a task only
// when it starts, so every operation here is a list edit: no agent traffic at all is the CORRECT
// outcome for work the agent was never told about, and a cancel cannot lose a race against a drain
// that no longer happens.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/queue.ts`, minus the durable
// half — see docs/VOICE_FSM.md for what that cost.

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
  // Nothing else could have started it, so forwarding it IS the escalation. startTurn clears
  // interruptScopeAsked deliberately — the escalated task joins the running turn, so the next
  // interrupt must find two requests again rather than aborting the newly-added one without asking.
  ctx.state = ctx.state.removeQueued(index)
  ctx.agent.forwardTask(item.text, item.attachments)
  ctx.state = ctx.state.startTurn(item.text)
  ctx.voice.say(startingNowLine(listOf(item.text)), supersedes = QUEUE_POSITION)
}

/**
 * Drop waiting work. With no task named, all of it.
 *
 * Removing the entry IS the cancellation — the task was never sent anywhere, so nothing can have
 * started it in the meantime and "that's off the list" is unconditionally true. This used to race
 * the agent's own drain and had to report which tasks got away.
 *
 * The RUNNING task is deliberately untouched: "cancel that" about a queued item must not tear down
 * work the user did not name. Stopping the running turn is `interrupt`.
 */
suspend fun applyCancelQueued(ctx: EffectCtx, effect: Effect.CancelQueued) {
  val queue = ctx.state.queue
  if (queue.isEmpty()) {
    ctx.voice.instruct(NOTHING_QUEUED_NUDGE)
    return
  }

  if (effect.task == null) {
    val all = queue.map { it.text }
    ctx.state = ctx.state.clearQueue()
    ctx.voice.say(droppedQueuedLine(all))
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
  ctx.voice.say(droppedQueuedLine(listOf(item.text)))
}
