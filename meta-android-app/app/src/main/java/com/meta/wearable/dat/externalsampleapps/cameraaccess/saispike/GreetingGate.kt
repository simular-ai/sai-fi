/*
 * sai-fi — voice concierge.
 */

// GreetingGate — one-shot latch for the proactive opening greeting.
//
// The Live session fires setup-complete on EVERY connect: the initial start of a call, a mid-call
// reconnect (token expiry / network blip), and resume-after-pause. The greeting must open the call
// exactly ONCE, so the decision is gated on "first ready of this call", not on the event itself.
// This is pulled out of CallService as a tiny pure latch so that once-per-call rule is unit-testable
// without the Android service (mic, notifications, sockets).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

class GreetingGate {
  @Volatile private var greeted = false

  /** Re-arm at the start of a new call (call from startCall). */
  fun reset() {
    greeted = false
  }

  /**
   * True only on the FIRST ready of a call; false for every subsequent ready (reconnect / resume).
   * Latches on the first true so the greeting can never fire twice within one call. Synchronized so a
   * reconnect racing the first ready can't slip through the check-then-set.
   */
  @Synchronized
  fun shouldGreet(): Boolean {
    if (greeted) return false
    greeted = true
    return true
  }
}
