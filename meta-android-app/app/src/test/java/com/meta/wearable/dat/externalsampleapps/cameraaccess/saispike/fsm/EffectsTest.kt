/* sai-fi — voice concierge. */

// The parse boundary. Everything here is about what the model is NOT allowed to make happen: this
// is the one place an invented capability or a malformed payload gets dropped instead of trusted.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {

  private fun effect(vararg pairs: Pair<String, Any?>): JSONObject =
      JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

  // ── the drop rules ─────────────────────────────────────────────────────────

  @Test
  fun `an unknown kind is dropped — a newer model does not get to invent a capability`() {
    assertNull(parseEffect(effect("kind" to "selfDestruct")))
    assertNull(parseEffect(effect("kind" to "")))
    assertNull(parseEffect(null))
  }

  @Test
  fun `an empty string is not a value anywhere it is required`() {
    assertNull(parseEffect(effect("kind" to "say", "text" to "")))
    assertNull(parseEffect(effect("kind" to "forwardToAgent", "text" to "")))
    assertNull(parseEffect(effect("kind" to "relayToAgent", "answer" to "")))
    assertNull(parseEffect(effect("kind" to "enqueue", "task" to "")))
  }

  @Test
  fun `askAndWait needs both a question and a recognised wait reason`() {
    assertNull(parseEffect(effect("kind" to "askAndWait", "question" to "which one?")))
    assertNull(
        parseEffect(
            effect("kind" to "askAndWait", "question" to "which one?", "waitingFor" to "vibes")))

    val ok =
        parseEffect(
            effect("kind" to "askAndWait", "question" to "which one?", "waitingFor" to "urgency"))
    assertEquals(Effect.AskAndWait("which one?", WaitReason.URGENCY), ok)
  }

  @Test
  fun `setState only accepts a real mode`() {
    assertNull(parseEffect(effect("kind" to "setState", "mode" to "vibing")))
    assertEquals(
        Effect.SetState(Mode.AWAITING_USER),
        parseEffect(effect("kind" to "setState", "mode" to "awaiting-user")))
  }

  // ── chooseOption: filter, don't reject ─────────────────────────────────────

  @Test
  fun `chooseOption filters junk values rather than rejecting the whole pick`() {
    val values = JSONArray(listOf("alpha", 7, "", "beta"))
    val parsed = parseEffect(effect("kind" to "chooseOption", "values" to values))
    assertEquals(Effect.ChooseOption(listOf("alpha", "beta")), parsed)
  }

  @Test
  fun `chooseOption with nothing left after filtering is dropped`() {
    assertNull(parseEffect(effect("kind" to "chooseOption", "values" to JSONArray(listOf("", 7)))))
    assertNull(parseEffect(effect("kind" to "chooseOption", "values" to JSONArray())))
    assertNull("a missing array is not an empty one", parseEffect(effect("kind" to "chooseOption")))
  }

  // ── the tolerant cases ─────────────────────────────────────────────────────

  @Test
  fun `an unrecognised urgency degrades to normal instead of dropping the task`() {
    val parsed = parseEffect(effect("kind" to "enqueue", "task" to "later", "urgency" to "sometime"))
    assertEquals(Effect.Enqueue("later", Urgency.NORMAL), parsed)
  }

  @Test
  fun `deny parses with or without a reason, and the reason is carried not dropped`() {
    assertEquals(Effect.Deny(null), parseEffect(effect("kind" to "deny")))
    assertEquals(Effect.Deny("too risky"), parseEffect(effect("kind" to "deny", "reason" to "too risky")))
  }

  @Test
  fun `cancelQueued and sendQueuedNow both parse bare — absent task means all-or-the-only-one`() {
    assertEquals(Effect.CancelQueued(null), parseEffect(effect("kind" to "cancelQueued")))
    assertEquals(Effect.SendQueuedNow(null), parseEffect(effect("kind" to "sendQueuedNow")))
    assertEquals(
        Effect.CancelQueued("the email"),
        parseEffect(effect("kind" to "cancelQueued", "task" to "the email")))
  }

  @Test
  fun `the payload-free effects parse from kind alone`() {
    assertEquals(Effect.Approve, parseEffect(effect("kind" to "approve")))
    assertEquals(Effect.ApproveAlways, parseEffect(effect("kind" to "approveAlways")))
    assertEquals(Effect.Interrupt, parseEffect(effect("kind" to "interrupt")))
    assertEquals(Effect.ResetSession, parseEffect(effect("kind" to "resetSession")))
    assertEquals(Effect.Noop, parseEffect(effect("kind" to "noop")))
  }

  // ── batches ────────────────────────────────────────────────────────────────

  @Test
  fun `a batch drops only the malformed entries and keeps the order of the rest`() {
    val batch =
        JSONArray(
            listOf(
                effect("kind" to "say", "text" to "on it"),
                effect("kind" to "nonsense"),
                effect("kind" to "forwardToAgent", "text" to "book a table"),
                effect("kind" to "say", "text" to ""),
            ))

    val parsed = parseEffects(batch)

    assertEquals(
        "order matters — a say before a forward is a different conversation than after",
        listOf(Effect.Say("on it"), Effect.ForwardToAgent("book a table")),
        parsed)
  }

  @Test
  fun `a non-array batch is empty, not a crash`() {
    assertTrue(parseEffects(null).isEmpty())
    assertTrue(parseEffects(JSONArray()).isEmpty())
    assertTrue("non-object entries are dropped", parseEffects(JSONArray(listOf("say", 3))).isEmpty())
  }
}
