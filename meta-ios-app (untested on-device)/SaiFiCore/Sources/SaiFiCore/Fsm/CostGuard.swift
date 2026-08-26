/* sai-fi — voice concierge. */

// The two bounds on what an open microphone can cost: a hard call ceiling and an idle window.
//
// Either firing ends the call, once. They are independent — `touch()` resets the idle countdown and
// deliberately does NOT extend the ceiling, because a call that keeps being active is exactly the one
// a max-duration bound exists to stop.
//
// The idle countdown starts at connection open, not at first activity: a call nobody ever speaks into
// is the case this catches.
//
// Ported from the Android `fsm/CostGuard.kt`, which came from cloud-api
// `services/concierge/voice/core/cost-guard.ts`. Timers are injected rather than taken from a task so
// this is drivable by a virtual clock in the gate.
//
// One thing this client owns that no server does: there is no server-side notion of the call at all,
// so an open microphone costs money whether or not anyone is still wearing the glasses.

import Foundation

public enum CostGuardReason: String, Sendable {
  case maxDuration = "max-duration"
  case idle
}

/// Schedules a one-shot callback. Returns a handle that cancels it.
///
/// A protocol rather than `Task.sleep` so the guard is driven by whatever the caller already has — a
/// run loop in the app, a virtual clock in the gate. Named `DelayTimer` because `Timer` is
/// Foundation's, and this is emphatically not that.
public protocol DelayTimer: Sendable {
  func schedule(delayMs: Int64, action: @escaping @Sendable () -> Void) -> TimerCancellable
}

public protocol TimerCancellable: Sendable {
  func cancel()
}

/// - Parameters:
///   - maxMs: hard max call length; nil or <= 0 disables the ceiling.
///   - idleMs: idle window; nil or <= 0 disables the idle timeout.
///   - onExpire: fired AT MOST ONCE, when either bound trips. The caller ends the call here.
///
/// Not internally synchronised, matching the Kotlin.
///
/// `@unchecked Sendable` because the timer callbacks must be `@Sendable` to be scheduled at all, and
/// the guard has no lock of its own. THE CONFINEMENT CONTRACT IS THE `DelayTimer`'s: it must deliver
/// its callbacks on the same context the guard is driven from. On Android that is a Handler on the
/// main looper; on iOS it is the `@MainActor` timer in the app layer, and a virtual clock in the gate.
/// A `DelayTimer` that fires on an arbitrary thread breaks this type, and would have broken the
/// Kotlin identically.
public final class CostGuard: @unchecked Sendable {
  private let maxMs: Int64?
  private let idleMs: Int64?
  private let timer: any DelayTimer
  private let onExpire: @Sendable (CostGuardReason) -> Void

  private var expired = false
  private var disposed = false
  private var idleTimer: TimerCancellable?
  private var maxTimer: TimerCancellable?

  public init(
    maxMs: Int64?,
    idleMs: Int64?,
    timer: any DelayTimer,
    onExpire: @escaping @Sendable (CostGuardReason) -> Void
  ) {
    self.maxMs = maxMs
    self.idleMs = idleMs
    self.timer = timer
    self.onExpire = onExpire

    if let maxMs, maxMs > 0 {
      maxTimer = timer.schedule(delayMs: maxMs) { [weak self] in self?.fire(.maxDuration) }
    }
    armIdle()  // idle counts from connection open, not from first activity
  }

  /// Register genuine activity — resets the idle countdown. No-op once expired or disposed.
  public func touch() {
    if expired || disposed { return }
    armIdle()
  }

  /// Stop all timers. Idempotent.
  ///
  /// Deliberately does not set `expired`: `fire` checks `disposed` too, so a timer that somehow
  /// survives still cannot call back.
  public func dispose() {
    disposed = true
    clearTimers()
  }

  private func armIdle() {
    idleTimer?.cancel()
    if let idleMs, idleMs > 0 {
      idleTimer = timer.schedule(delayMs: idleMs) { [weak self] in self?.fire(.idle) }
    } else {
      idleTimer = nil
    }
  }

  private func fire(_ reason: CostGuardReason) {
    if expired || disposed { return }
    expired = true
    clearTimers()
    onExpire(reason)
  }

  private func clearTimers() {
    idleTimer?.cancel()
    maxTimer?.cancel()
    idleTimer = nil
    maxTimer = nil
  }
}
