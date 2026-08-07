package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While Sai is muted, a nudge that would make her speak has to be held — injected then, she'd say the
 * result to nobody and the agent event never repeats, so it would be lost outright.
 *
 * What's worth pinning is the collapsing: unmuting must produce ONE short offer, never a backlog
 * monologue. That was the risk called out when this was designed.
 */
class HeldNudgeQueueTest {
  @Test
  fun onlyTheNewestCompletionSurvives() {
    val q = HeldNudgeQueue()
    q.add("complete", "first result")
    q.add("complete", "second result")
    q.add("complete", "third result")
    val out = q.drain()
    assertEquals(1, out.size) // an older result is superseded by definition
    assertEquals("third result", out[0].nudge)
  }

  @Test
  fun progressChatterIsDiscardedNotQueued() {
    val q = HeldNudgeQueue()
    // Returning false lets the caller log honestly rather than claiming it kept everything.
    assertFalse(q.add("progress", "opening the site"))
    assertFalse(q.add("status", "processing"))
    // Observed through drain() rather than a size accessor: what matters is that nothing comes BACK
    // out, which is the only thing a caller can see.
    assertEquals(emptyList<HeldNudgeQueue.Held>(), q.drain())
  }

  @Test
  fun urgentEventsComeOutFirst() {
    val q = HeldNudgeQueue()
    q.add("complete", "the result")
    q.add("approval-request", "okay to send it?")
    q.add("error", "it broke")
    val kinds = q.drain().map { it.kind }
    // Both urgent kinds precede the completion — they're what's actually blocking the user.
    assertEquals(listOf("error", "approval-request", "complete"), kinds)
  }

  @Test
  fun capIsEnforcedFromTheBack() {
    val q = HeldNudgeQueue(max = 2)
    q.add("approval-request", "a")
    q.add("approval-request", "b")
    q.add("approval-request", "c")
    val out = q.drain()
    assertEquals(2, out.size)
    // Newest urgent goes to the front and the oldest falls off the back.
    assertEquals(listOf("c", "b"), out.map { it.nudge })
  }

  @Test
  fun drainEmptiesSoUnmutingTwiceCannotRepeat() {
    val q = HeldNudgeQueue()
    q.add("complete", "the result")
    assertEquals(1, q.drain().size)
    assertTrue(q.drain().isEmpty())
  }

  @Test
  fun clearDropsEverything() {
    val q = HeldNudgeQueue()
    q.add("complete", "x")
    q.add("error", "y")
    q.clear()
    assertEquals(emptyList<HeldNudgeQueue.Held>(), q.drain())
  }
}
