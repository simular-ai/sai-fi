/* sai-fi — voice concierge. */

// GreetingGate — one-shot latch for the proactive opening greeting.
//
// The Live session fires setup-complete on EVERY connect: the initial start of a call, a mid-call
// reconnect (token expiry / network blip), and resume-after-pause. The greeting must open the call
// exactly ONCE, so the decision is gated on "first ready of this call", not on the event itself.
// This is pulled out of CallService as a tiny pure latch so that once-per-call rule is unit-testable
// without the service (mic, notifications, sockets).
//
// Ported from Android `GreetingGate.kt`.

import Foundation
import os

public final class GreetingGate: @unchecked Sendable {
  private let lock = OSAllocatedUnfairLock(initialState: false)

  public init() {}

  /// Re-arm at the start of a new call (call from startCall).
  public func reset() {
    lock.withLock { $0 = false }
  }

  /// True only on the FIRST ready of a call; false for every subsequent ready (reconnect / resume).
  /// Latches on the first true so the greeting can never fire twice within one call.
  public func shouldGreet() -> Bool {
    lock.withLock { greeted in
      if greeted { return false }
      greeted = true
      return true
    }
  }
}
