/* sai-fi — voice concierge. */

// The pure state transitions. Each of these encodes a rule that drifted at least once in the
// TypeScript before it was named, so the test is the rule rather than a restatement of the code.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {

  // ── enqueue ────────────────────────────────────────────────────────────────

  @Test
  fun `urgent jumps the queue, normal joins the back`() {
    val s = initialState().enqueue("first").enqueue("second").enqueue("rush", Urgency.URGENT)
    assertEquals(listOf("rush", "first", "second"), s.queue.map { it.text })
  }

  @Test
  fun `an empty attachment list is stored as absent, not as an empty list`() {
    // Downstream asks "does this task carry a photo?". An empty list answering yes-but-none is a
    // third state nothing handles.
    val s = initialState().enqueue("look at this", attachments = emptyList())
    assertNull(s.queue.single().attachments)
  }

  @Test
  fun `a queued task keeps its own photo rather than sharing the bridge stash`() {
    val photo = TaskAttachment(path = "uploads/a.jpg", name = "a.jpg", mime = "image/jpeg", size = 12)
    val s = initialState().enqueue("what is this", attachments = listOf(photo))
    assertEquals(listOf(photo), s.queue.single().attachments)
  }

  // ── removeQueued / clearQueue ──────────────────────────────────────────────

  @Test
  fun `removing an out-of-range index changes nothing`() {
    val s = initialState().enqueue("only one")
    assertSame("out-of-range must be identity, not a silent drop", s, s.removeQueued(4))
    assertSame(s, s.removeQueued(-1))
  }

  @Test
  fun `clearing an already-empty queue is identity`() {
    val s = initialState()
    assertSame(s, s.clearQueue())
  }

  // ── startTurn / endTurn ────────────────────────────────────────────────────

  @Test
  fun `startTurn clears every leftover that used to drift across the six start sites`() {
    val parked =
        initialState()
            .copy(
                mode = Mode.AWAITING_USER,
                awaiting = WaitReason.APPROVAL,
                interruptScopeAsked = true,
                resetConfirmAsked = true,
            )

    val after = parked.startTurn("do the thing")

    assertEquals(Mode.WORKING, after.mode)
    assertNull("awaiting and working cannot both be true", after.awaiting)
    assertNull(
        "the scope question was asked about the PREVIOUS turn — carrying it lets the next " +
            "interrupt abort fresh work without asking",
        after.interruptScopeAsked)
    assertNull(
        "consent to a wipe belongs to the moment it was asked — a task later, a stray " +
            "resetSession would read as the user's answer and there is no undo behind it",
        after.resetConfirmAsked)
    assertEquals(listOf("do the thing"), after.inFlight)
  }

  @Test
  fun `endTurn empties inFlight and the scope flag, but keeps mode and the queue`() {
    val s =
        initialState()
            .enqueue("later")
            .startTurn("now")
            .copy(interruptScopeAsked = true)

    val after = s.endTurn()

    assertEquals(emptyList<String>(), after.inFlight)
    assertNull(after.interruptScopeAsked)
    assertEquals("a turn ending normally still releases the queue elsewhere", 1, after.queue.size)
    assertEquals("endTurn does not decide the mode", Mode.WORKING, after.mode)
  }

  // ── hasOutstandingWork ─────────────────────────────────────────────────────

  @Test
  fun `outstanding work is keyed on the three things that outlive a rotation, not on mode`() {
    // The case that must refuse: mode reads idle, but a task is still queued.
    val queuedOnly = initialState().enqueue("still waiting")
    assertEquals(Mode.IDLE, queuedOnly.mode)
    assertTrue("idle mode with a queued task must still refuse", queuedOnly.hasOutstandingWork())

    assertTrue(initialState().startTurn("running").hasOutstandingWork())
    assertTrue(initialState().copy(pendingApprovalId = "a1").hasOutstandingWork())
    assertFalse(initialState().hasOutstandingWork())
  }

  // ── noPendingApproval ──────────────────────────────────────────────────────

  @Test
  fun `clearing an approval clears all six fields — type is the one that used to be left behind`() {
    val blocked =
        initialState()
            .copy(
                pendingApprovalId = "a1",
                pendingApprovalPrompt = "Run the script?",
                pendingApprovalOptions = listOf(ApprovalOption("yes", "Yes")),
                pendingApprovalAllowOther = true,
                pendingApprovalLinkOnly = true,
                pendingApprovalType = "exec",
            )

    val after = blocked.noPendingApproval()

    assertNull(after.pendingApprovalId)
    assertNull(after.pendingApprovalPrompt)
    assertNull(after.pendingApprovalOptions)
    assertNull(after.pendingApprovalAllowOther)
    assertNull(after.pendingApprovalLinkOnly)
    assertNull("the out-of-band branch used to leak this one", after.pendingApprovalType)
  }

  @Test
  fun `clearing an approval leaves the work alone`() {
    val s = initialState().enqueue("later").startTurn("now").copy(pendingApprovalId = "a1")
    val after = s.noPendingApproval()

    assertEquals(listOf("now"), after.inFlight)
    assertEquals(1, after.queue.size)
  }

  // ── wire mappings ──────────────────────────────────────────────────────────

  @Test
  fun `mode wire strings round-trip, including the hyphenated one`() {
    Mode.entries.forEach { assertEquals(it, Mode.fromWire(it.wire)) }
    assertEquals("awaiting-user", Mode.AWAITING_USER.wire)
    assertNull(Mode.fromWire("awaiting_user"))
    assertNull(Mode.fromWire(null))
  }

  @Test
  fun `an unrecognised urgency is normal, never a rejection`() {
    assertEquals(Urgency.NORMAL, Urgency.fromWire("whenever"))
    assertEquals(Urgency.NORMAL, Urgency.fromWire(null))
    assertEquals(Urgency.URGENT, Urgency.fromWire("urgent"))
  }
}
