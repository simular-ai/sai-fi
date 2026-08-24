/* sai-fi — voice concierge. */

// enqueue / sendQueuedNow / cancelQueued — the waiting list.
//
// The waiting list is local and nothing else knows it exists. The agent is told about a task only when
// it starts, so every operation here is a list edit: no agent traffic at all is the CORRECT outcome for
// work the agent was never told about, and a cancel cannot lose a race against a drain that no longer
// happens.
//
// Ported from the Android `fsm/QueueHandlers.kt`, which came from cloud-api
// `services/concierge/voice/core/effect-handlers/queue.ts`, minus the durable half — see
// docs/VOICE_FSM.md for what that cost.

import Foundation

extension Concierge {

  /// Hold a task in the FSM only. No durable write, no agent traffic, nothing spoken.
  func applyEnqueue(task: String, urgency: Urgency) {
    state = state.enqueue(text: task, urgency: urgency)
  }

  /// "Do that first" — start a waiting task now, without stopping anything.
  ///
  /// Every refusal below is model-facing and mutates nothing: the model has to re-ask, and telling the
  /// user something moved when it didn't is the failure being avoided.
  func applySendQueuedNow(task: String?) async {
    let queue = state.queue
    if queue.isEmpty {
      await voice.instruct(text: NOTHING_QUEUED_TO_RUSH_NUDGE)
      return
    }

    if task == nil, queue.count > 1 {
      // Do NOT guess the head: rushing the wrong task starts the wrong work and reports the right one.
      await voice.instruct(text: whichQueuedToRushNudge(queued: queue.map(\.text)))
      return
    }

    let index = task != nil ? matchQueued(queue: queue, task: task!) : 0
    if index < 0 {
      await voice.instruct(text: noQueuedMatchNudge(queued: queue.map(\.text)))
      return
    }

    let item = queue[index]
    // Nothing else could have started it, so forwarding it IS the escalation. startTurn clears
    // interruptScopeAsked deliberately — the escalated task joins the running turn, so the next
    // interrupt must find two requests again rather than aborting the newly-added one without asking.
    state = state.removeQueued(index)
    _ = try? await agent.forwardTask(text: item.text, attachments: item.attachments)
    state = state.startTurn(item.text)
    await voice.say(text: startingNowLine([item.text]), supersedes: QUEUE_POSITION)
  }

  /// Drop waiting work. With no task named, all of it.
  ///
  /// Removing the entry IS the cancellation — the task was never sent anywhere, so nothing can have
  /// started it in the meantime and "that's off the list" is unconditionally true. This used to race
  /// the agent's own drain and had to report which tasks got away.
  ///
  /// The RUNNING task is deliberately untouched: "cancel that" about a queued item must not tear down
  /// work the user did not name. Stopping the running turn is `interrupt`.
  func applyCancelQueued(task: String?) async {
    let queue = state.queue
    if queue.isEmpty {
      await voice.instruct(text: NOTHING_QUEUED_NUDGE)
      return
    }

    guard let task else {
      let all = queue.map(\.text)
      state = state.clearQueue()
      await voice.say(text: droppedQueuedLine(all), supersedes: nil)
      return
    }

    let index = matchQueued(queue: queue, task: task)
    if index < 0 {
      // Claims nothing, and deliberately does not touch the running task.
      await voice.instruct(text: noQueuedMatchNudge(queued: queue.map(\.text)))
      return
    }

    let item = queue[index]
    state = state.removeQueued(index)
    await voice.say(text: droppedQueuedLine([item.text]), supersedes: nil)
  }
}
