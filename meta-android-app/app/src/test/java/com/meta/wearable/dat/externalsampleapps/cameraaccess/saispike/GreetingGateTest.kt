package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the once-per-call greeting latch. The Live session fires setup-complete on every
 * connect (start, mid-call reconnect, resume-after-pause), so the greeting must fire on the FIRST
 * ready only — these lock that in without needing the Android CallService.
 */
class GreetingGateTest {
  @Test
  fun greetsOnFirstReadyOnly() {
    val gate = GreetingGate()
    assertTrue("greets on the first ready", gate.shouldGreet())
    // A mid-call reconnect / a second setup-complete must NOT re-greet.
    assertFalse("does not re-greet on a reconnect", gate.shouldGreet())
    assertFalse("does not re-greet on resume", gate.shouldGreet())
  }

  @Test
  fun doesNotGreetBeforeReset_onAResumedGate() {
    val gate = GreetingGate()
    gate.shouldGreet() // first call already greeted
    assertFalse(gate.shouldGreet())
  }

  @Test
  fun resetReArmsForANewCall() {
    val gate = GreetingGate()
    assertTrue(gate.shouldGreet())
    assertFalse(gate.shouldGreet())
    // A brand-new call re-arms the latch, so the next call greets again.
    gate.reset()
    assertTrue("re-arms after reset for the next call", gate.shouldGreet())
    assertFalse(gate.shouldGreet())
  }

  @Test
  fun concurrentReadiesGreetExactlyOnce() {
    val gate = GreetingGate()
    val greetCount = java.util.concurrent.atomic.AtomicInteger(0)
    val threads =
        (1..32).map {
          Thread {
            if (gate.shouldGreet()) greetCount.incrementAndGet()
          }
        }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertTrue("exactly one thread wins the greeting", greetCount.get() == 1)
  }
}
