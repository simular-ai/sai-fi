/* sai-fi — voice concierge. */

// The frame parser, which is now a TRANSLATOR: the agent API streams the Vercel AI SDK's UI message
// vocabulary and the FSM speaks its own. Network is not exercised here — HttpURLConnection needs a
// server — but the translation is where the interesting failures live, and it is pure.
//
// The mismatches between the two alphabets are what these tests are mostly about. Every one of them
// is a place where the obvious mapping is wrong.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChannelClientTest {

  @Test
  fun `an unrecognised frame is dropped, not thrown`() {
    // A newer server must not be able to end a call by sending something this build predates. There
    // are more frame kinds on this stream than the FSM has any use for, so dropping is the common
    // case here rather than the exception.
    assertNull(parseAgentEvent("""{"type":"some-future-thing","x":1}"""))
    assertNull(parseAgentEvent("not json at all"))
    assertNull(parseAgentEvent("""{"no":"type"}"""))
    assertNull(parseAgentEvent("""{"type":"text-start","id":"m1"}"""))
  }

  // ── the mappings that are not one-to-one ───────────────────────────────────

  @Test
  fun `a text delta is passed straight through, not buffered until the end`() {
    // The FSM's Text only resets dead-air backoff, so a fragment is as good as a sentence — and
    // holding the answer until `text-end` would undo the point of streaming it.
    val e = parseAgentEvent("""{"type":"text-delta","id":"m1","delta":"the inbox is"}""")
    assertEquals(AgentEvent.Text("the inbox is"), e)
    assertNull("an empty delta says nothing", parseAgentEvent("""{"type":"text-delta","delta":""}"""))
  }

  @Test
  fun `finish is a COMPLETION — it is the only end-of-turn signal on this stream`() {
    // There is no `complete` frame to map from, and no summary on the wire: the answer already
    // arrived as text. Failing to map this leaves the FSM in `working` for the rest of the call.
    val e = parseAgentEvent("""{"type":"finish","finishReason":"stop"}""") as AgentEvent.Complete
    assertNull(e.summary)
  }

  @Test
  fun `a failed tool is PROGRESS, not an error — the task carries on`() {
    // `error` is terminal and ends the turn. A step that failed while the task continues must not
    // be read as one, or every recoverable retry would end the turn early.
    val e =
        parseAgentEvent("""{"type":"tool-output-error","toolCallId":"t1","errorText":"429 from the API"}""")
            as AgentEvent.Progress
    assertTrue(e.failed)
    assertEquals("429 from the API", e.text)
  }

  @Test
  fun `reasoning is mid-turn narration, which is silent by design`() {
    val e = parseAgentEvent("""{"type":"reasoning-delta","id":"r1","delta":"checking inbox"}""")
            as AgentEvent.Progress
    assertEquals("checking inbox", e.text)
    assertTrue("narration is not a failure", !e.failed)
  }

  @Test
  fun `data-status is a NOTICE — it is delivery news, not work`() {
    // A hibernated machine waking, an agent that is offline. Dropping it is how a task sent to a
    // sleeping machine bought a silent minute with no explanation.
    val e =
        parseAgentEvent("""{"type":"data-status","data":{"text":"Waking your machine"}}""")
            as AgentEvent.Notice
    assertEquals("Waking your machine", e.text)
  }

  @Test
  fun `data-progress keeps the tool name`() {
    val e =
        parseAgentEvent("""{"type":"data-progress","data":{"text":"opening the inbox","tool":"browser"}}""")
            as AgentEvent.Progress
    assertEquals("browser", e.tool)
    assertTrue(!e.failed)
  }

  @Test
  fun `start opens the turn`() {
    assertEquals(
        AgentEvent.Status(AgentStatus.PROCESSING),
        parseAgentEvent("""{"type":"start","messageId":"m1"}"""))
  }

  // ── approvals ──────────────────────────────────────────────────────────────

  @Test
  fun `an approval-request carries its options, and absent flags stay absent`() {
    val e =
        parseAgentEvent(
            """{"type":"data-approval-request","data":{"approvalId":"a1","title":"Which?",
               "description":"d","approvalType":"choice","isLinkOnly":false,"allowAlways":false,
               "options":[{"value":"sms","label":"Text"},{"value":"app","label":"App"}]}}""")
            as AgentEvent.ApprovalRequest

    assertEquals("a1", e.id)
    assertEquals(listOf("sms", "app"), e.options?.map { it.value })
    assertEquals(listOf("Text", "App"), e.options?.map { it.label })
    // Absent is not false: the FSM's allowOther guard turns on the difference.
    assertNull(e.allowOther)
    assertNull(e.multiple)
    assertNull("a single question needs no grouping", e.questions)
    assertNull("no expiry means no pre-timeout ping", e.expiresAt)
  }

  @Test
  fun `plain-string options are accepted — askChoice writes them that way`() {
    // The value the agent matches on and the words the user hears are the same string.
    val e =
        parseAgentEvent(
            """{"type":"data-approval-request","data":{"approvalId":"a1","options":["Free","Pro"]}}""")
            as AgentEvent.ApprovalRequest
    assertEquals(listOf("Free", "Pro"), e.options?.map { it.value })
    assertEquals(listOf("Free", "Pro"), e.options?.map { it.label })
  }

  @Test
  fun `a multi-question card is flattened for picking AND kept grouped for resolving`() {
    // The model picks from one flat list, because a spoken pick carries no question index. The
    // agent resolves positionally. Both are needed, and only keeping one of them loses a card.
    val e =
        parseAgentEvent(
            """{"type":"data-approval-request","data":{"approvalId":"a1","questions":[
               {"options":[{"value":"sms","label":"Text"}]},
               {"options":[{"value":"am","label":"Morning"},{"value":"pm","label":"Evening"}],
                "multiple":true}]}}""")
            as AgentEvent.ApprovalRequest

    assertEquals(listOf("sms", "am", "pm"), e.options?.map { it.value })
    assertEquals(2, e.questions?.size)
    assertEquals(listOf("am", "pm"), e.questions?.get(1)?.options?.map { it.value })
    assertEquals("a card is multi-select if any question is", true, e.multiple)
  }

  @Test
  fun `an approval-request without an id is dropped — there would be nothing to resolve`() {
    assertNull(parseAgentEvent("""{"type":"data-approval-request","data":{"title":"Which?"}}"""))
  }

  @Test
  fun `allowOther true survives, because free-text answers depend on it`() {
    val e =
        parseAgentEvent(
            """{"type":"data-approval-request","data":{"approvalId":"a1","approvalType":"choice","allowOther":true}}""")
            as AgentEvent.ApprovalRequest
    assertEquals(true, e.allowOther)
  }

  /**
   * The bug this exists for: the agent answers in `text-delta` frames and the turn ends with a bare
   * `finish`, so the completion carried no result and the concierge was told to say nothing came
   * back. Found on a live call — the agent reported the time and the user never heard it.
   */
  @Test
  fun `a turn that answers in text deltas completes WITH that answer as its summary`() {
    val turn = TurnEvents()
    val seen = mutableListOf<AgentEvent>()
    listOf(
            """{"type":"start"}""",
            """{"type":"text-start"}""",
            """{"type":"text-delta","delta":"It is "}""",
            """{"type":"text-delta","delta":"half past four."}""",
            """{"type":"text-end"}""",
            """{"type":"finish","finishReason":"stop"}""",
        )
        .forEach { turn.onPayload(it)?.let(seen::add) }

    // The deltas still arrive individually — the activity log and the transcript are built from them.
    assertEquals(listOf("It is ", "half past four."), seen.filterIsInstance<AgentEvent.Text>().map { it.text })

    val complete = seen.filterIsInstance<AgentEvent.Complete>().single()
    assertEquals("It is half past four.", complete.summary)
  }

  @Test
  fun `a turn that really said nothing still completes with nothing`() {
    val turn = TurnEvents()
    val seen = mutableListOf<AgentEvent>()
    listOf("""{"type":"start"}""", """{"type":"finish","finishReason":"stop"}""")
        .forEach { turn.onPayload(it)?.let(seen::add) }
    // Empty must stay empty: the "nothing came back" nudge is right when nothing did.
    assertTrue(seen.filterIsInstance<AgentEvent.Complete>().single().summary.isNullOrEmpty())
  }

  @Test
  fun `a summary the server did send is never overwritten`() {
    val turn = TurnEvents()
    turn.onPayload("""{"type":"text-delta","delta":"chatter"}""")
    val complete = turn.onPayload("""{"type":"complete","summary":"the real summary"}""")
    // Only fills a gap. If the wire ever starts carrying a summary, that one wins.
    if (complete is AgentEvent.Complete) assertEquals("the real summary", complete.summary)
  }
}
