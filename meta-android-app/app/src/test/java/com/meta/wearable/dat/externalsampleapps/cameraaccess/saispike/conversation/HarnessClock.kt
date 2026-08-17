/* sai-fi — voice concierge. */

// One clock for the whole closed loop.
//
// The FSM needs a [Timer] for its approval ping, and the scripted agent needs to deliver an event
// programme some milliseconds after a task was forwarded. Both must move together or the ordering
// between them is fiction — an approval that "times out" before the completion that would have
// resolved it is a real failure mode, and a test with two independent clocks cannot express it.
//
// So this is the only clock. `advance` fires everything now due, in due order, and re-checks after
// each one: an action may schedule another (a drain forwarding the next task, which then schedules
// that task's events) and anything falling inside the same window has to run in this same advance,
// not the next.
//
// Agent-event actions are `suspend` because delivering one calls into the FSM, which is suspending.
// The FSM's own Timer actions are not, which is why there are two kinds.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Cancellable
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Timer

/**
 * Starts at a non-zero time deliberately.
 *
 * CallService uses `0L` as the sentinel for "the user has not spoken this call", so a clock starting
 * at zero makes the first utterance indistinguishable from silence and every completion comes back
 * wearing the ask-first wording. That is a harness artifact rather than a finding, and starting the
 * clock somewhere real is cheaper than teaching every scenario to step past it.
 */
class HarnessClock(var now: Long = 10_000L) : Timer {

  private class Entry(
      val dueAt: Long,
      val seq: Long,
      val plain: (() -> Unit)? = null,
      val suspending: (suspend () -> Unit)? = null,
  )

  private val entries = mutableListOf<Entry>()
  private var seq = 0L

  /** The FSM's timer seam. */
  override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
    val entry = Entry(now + delayMs, seq++, plain = action)
    entries += entry
    return Cancellable { entries.remove(entry) }
  }

  /** Schedule an agent-side action, which may suspend into the FSM. */
  fun scheduleSuspending(delayMs: Long, action: suspend () -> Unit): Cancellable {
    val entry = Entry(now + delayMs, seq++, suspending = action)
    entries += entry
    return Cancellable { entries.remove(entry) }
  }

  /**
   * Move the clock, firing everything that comes due.
   *
   * Re-checks after every action rather than snapshotting the due list up front: an action can
   * schedule another inside the same window (a queue drain forwards the next task, whose first event
   * lands 10 ms later), and those have to run here rather than waiting for the next advance.
   */
  suspend fun advance(ms: Long) {
    val target = now + ms
    var fired = 0
    while (true) {
      val next = entries.filter { it.dueAt <= target }.minWithOrNull(compareBy({ it.dueAt }, { it.seq })) ?: break
      entries.remove(next)
      now = maxOf(now, next.dueAt)
      // A runaway loop here means the conversation is feeding itself — a nudge that provokes a reply
      // that provokes the same nudge. Fail saying so, rather than exhausting the heap and leaving a
      // bare OutOfMemoryError to be interpreted.
      if (++fired > RUNAWAY) {
        throw IllegalStateException(
            "the conversation did not settle after $RUNAWAY actions in one advance — " +
                "something is answering itself in a loop")
      }
      next.plain?.invoke()
      next.suspending?.invoke()
    }
    now = target
  }

  /** Run until nothing is left to fire. The usual way to end a scenario. */
  suspend fun drain() {
    var rounds = 0
    while (entries.isNotEmpty()) {
      if (++rounds > RUNAWAY) {
        throw IllegalStateException(
            "the conversation never went quiet after $RUNAWAY rounds — something keeps rescheduling")
      }
      val furthest = entries.maxOf { it.dueAt }
      advance((furthest - now).coerceAtLeast(1))
    }
  }

  private companion object {
    const val RUNAWAY = 500
  }

  val pending: Int
    get() = entries.size
}
