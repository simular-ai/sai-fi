/* sai-fi — voice concierge. */

// A non-reentrant async mutex.
//
// WHY THIS EXISTS, AND WHY AN `actor` IS NOT ENOUGH. This is the single most important thing to get
// right in the port.
//
// The Kotlin FSM wraps every entry point in `Mutex.withLock { … }`, and it holds that lock ACROSS
// suspension points. That is the whole guarantee: every handler is read-state → suspend on I/O →
// write-state, so without it two handlers interleave at the suspension point and the second writes
// over a state the first already changed. Concretely, from the Kotlin's own comment: two forwards
// both see an empty `inFlight` before either records a turn, and the user's restaurant gets booked
// twice.
//
// A Swift `actor` does NOT provide that. Actors guarantee mutually-exclusive access to their state
// between suspension points, but they are REENTRANT: while one actor method is parked on an `await`,
// another call into the same actor runs. So `actor Concierge` on its own gives exactly the
// interleaving the Mutex was added to prevent — and it would do it silently, because every
// individual state access still looks correctly isolated.
//
// So the port keeps both, for two different jobs:
//
//   actor      — the compiler enforces that nothing touches FSM state from outside.
//   AsyncLock  — mutual exclusion that survives an `await`, matching Mutex.withLock.
//
// Deadlock behaviour matches the Kotlin too: this lock is non-reentrant, so a lock-holder that
// awaits something needing the lock again deadlocks — exactly as `kotlinx` `Mutex` would. That is a
// bug in the caller in both languages, not something to paper over with reentrancy.

import Foundation
import os

/// A FIFO async mutex. Fair by construction: waiters are resumed in arrival order, so a busy FSM
/// cannot starve one entry point in favour of another.
///
/// A `final class` over an `OSAllocatedUnfairLock` rather than an `actor`, for one practical reason:
/// `release()` has to be callable from a `defer`, and `defer` cannot `await`. An actor would make it
/// isolated and therefore async, and the whole point is that release is unconditional even on an early
/// return or a thrown error. (`NSLock` will not do either — its `lock()` is unavailable from an async
/// context, which is precisely the situation here.) The unfair lock is held only around a couple of
/// field updates and never across a suspension.
public final class AsyncLock: Sendable {
  private struct Waiting {
    var held = false
    var waiters: [CheckedContinuation<Void, Never>] = []
  }

  private let waiting = OSAllocatedUnfairLock(initialState: Waiting())

  public init() {}

  public func acquire() async {
    // Fast path: uncontended.
    let takenImmediately = waiting.withLock { state -> Bool in
      if state.held { return false }
      state.held = true
      return true
    }
    if takenImmediately { return }

    await withCheckedContinuation { continuation in
      // Re-checked INSIDE the lock, because between the fast path above and here a release may have
      // happened. Without the recheck that release is lost and the caller waits forever on a free lock.
      let freedInTheMeantime = waiting.withLock { state -> Bool in
        if !state.held {
          state.held = true
          return true
        }
        state.waiters.append(continuation)
        return false
      }
      if freedInTheMeantime { continuation.resume() }
    }
  }

  public func release() {
    // Hand the lock straight to the next waiter rather than clearing `held` — clearing it would let a
    // fresh `acquire()` barge in ahead of a queued waiter. Resumed OUTSIDE the lock: a continuation
    // resume can run arbitrary code, and none of it should be able to re-enter this lock.
    let next = waiting.withLock { state -> CheckedContinuation<Void, Never>? in
      guard state.held else { return nil }
      if state.waiters.isEmpty {
        state.held = false
        return nil
      }
      return state.waiters.removeFirst()
    }
    next?.resume()
  }
}
