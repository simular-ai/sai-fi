/* sai-fi — voice concierge. */

// The two bounds on what an open microphone can cost: a hard call ceiling and an idle window.
//
// Either firing ends the call, once. They are independent — `touch()` resets the idle countdown and
// deliberately does NOT extend the ceiling, because a call that keeps being active is exactly the
// one a max-duration bound exists to stop.
//
// The idle countdown starts at connection open, not at first activity: a call nobody ever speaks
// into is the case this catches.
//
// Ported from cloud-api `services/concierge/voice/core/cost-guard.ts`. Timers are injected rather
// than taken from a coroutine scope so this is testable under runTest's virtual clock.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

enum class CostGuardReason(val wire: String) {
  MAX_DURATION("max-duration"),
  IDLE("idle"),
}

/**
 * Schedules a one-shot callback. Returns a handle that cancels it.
 *
 * An interface rather than a coroutine `delay` so the guard is driven by whatever the caller already
 * has — a Handler on Android, virtual time in a test.
 */
fun interface Timer {
  fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

fun interface Cancellable {
  fun cancel()
}

/**
 * @param maxMs hard max call length; null or <= 0 disables the ceiling.
 * @param idleMs idle window; null or <= 0 disables the idle timeout.
 * @param onExpire fired AT MOST ONCE, when either bound trips. The caller ends the call here.
 */
class CostGuard(
    private val maxMs: Long?,
    private val idleMs: Long?,
    private val timer: Timer,
    private val onExpire: (CostGuardReason) -> Unit,
) {
  private var expired = false
  private var disposed = false
  private var idleTimer: Cancellable? = null
  private var maxTimer: Cancellable? = null

  init {
    if (maxMs != null && maxMs > 0) {
      maxTimer = timer.schedule(maxMs) { fire(CostGuardReason.MAX_DURATION) }
    }
    armIdle() // idle counts from connection open, not from first activity
  }

  /** Register genuine activity — resets the idle countdown. No-op once expired or disposed. */
  fun touch() {
    if (expired || disposed) return
    armIdle()
  }

  /**
   * Stop all timers. Idempotent.
   *
   * Deliberately does not set `expired`: [fire] checks `disposed` too, so a timer that somehow
   * survives still cannot call back.
   */
  fun dispose() {
    disposed = true
    clearTimers()
  }

  private fun armIdle() {
    idleTimer?.cancel()
    idleTimer =
        if (idleMs != null && idleMs > 0) timer.schedule(idleMs) { fire(CostGuardReason.IDLE) }
        else null
  }

  private fun fire(reason: CostGuardReason) {
    if (expired || disposed) return
    expired = true
    clearTimers()
    onExpire(reason)
  }

  private fun clearTimers() {
    idleTimer?.cancel()
    maxTimer?.cancel()
    idleTimer = null
    maxTimer = null
  }
}
