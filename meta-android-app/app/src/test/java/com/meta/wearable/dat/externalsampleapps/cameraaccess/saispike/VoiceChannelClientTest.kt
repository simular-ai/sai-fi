/* sai-fi — voice concierge. */

// The SSE frame parser. Network is not exercised here — HttpURLConnection needs a server — but the
// parse boundary is where the interesting failures live, and it is pure.

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
    // A newer server must not be able to end a call by sending something this build predates.
    assertNull(parseAgentEvent("""{"type":"some-future-thing","x":1}"""))
    assertNull(parseAgentEvent("not json at all"))
    assertNull(parseAgentEvent("""{"no":"type"}"""))
  }

  @Test
  fun `a status frame with an unknown value is dropped rather than guessed`() {
    assertNull(parseAgentEvent("""{"type":"status","status":"contemplating"}"""))
    assertEquals(
        AgentEvent.Status(AgentStatus.PROCESSING),
        parseAgentEvent("""{"type":"status","status":"processing"}"""))
  }

  @Test
  fun `an approval-request carries its options, and absent flags stay absent`() {
    val e =
        parseAgentEvent(
            """{"type":"approval-request","id":"a1","title":"Which?","description":"d",
               "approvalType":"choice","isLinkOnly":false,"allowAlways":false,
               "options":[{"value":"sms","label":"Text"},{"value":"app","label":"App"}]}""")
        as AgentEvent.ApprovalRequest

    assertEquals("a1", e.id)
    assertEquals(listOf("sms", "app"), e.options?.map { it.value })
    assertEquals(listOf("Text", "App"), e.options?.map { it.label })
    // Absent is not false: the FSM's allowOther guard turns on the difference.
    assertNull(e.allowOther)
    assertNull(e.multiple)
    assertNull("no expiry means no pre-timeout ping", e.expiresAt)
  }

  @Test
  fun `an approval-request without an id is dropped — there would be nothing to resolve`() {
    assertNull(parseAgentEvent("""{"type":"approval-request","title":"Which?"}"""))
  }

  @Test
  fun `allowOther true survives, because free-text answers depend on it`() {
    val e =
        parseAgentEvent(
            """{"type":"approval-request","id":"a1","approvalType":"choice","allowOther":true}""")
            as AgentEvent.ApprovalRequest
    assertEquals(true, e.allowOther)
  }

  @Test
  fun `a session-state frame parses its three parts`() {
    val e =
        parseAgentEvent(
            """{"type":"session-state","running":"book a table","queued":["check email"]}""")
            as AgentEvent.SessionState
    assertEquals("book a table", e.running)
    assertEquals(listOf("check email"), e.queued)
    assertNull(e.blockedOn)
  }

  @Test
  fun `a complete with no summary is still a completion`() {
    val e = parseAgentEvent("""{"type":"complete"}""") as AgentEvent.Complete
    assertNull(e.summary)
  }

  @Test
  fun `progress carries the failed flag, which is the only progress the brain hears about`() {
    val e =
        parseAgentEvent("""{"type":"progress","text":"retrying","failed":true}""")
            as AgentEvent.Progress
    assertTrue(e.failed)
    assertNull(e.tool)
  }

  @Test
  fun `a notice parses — dropping it is how a waking machine bought a silent minute`() {
    val e = parseAgentEvent("""{"type":"notice","text":"Waking your machine"}""") as AgentEvent.Notice
    assertEquals("Waking your machine", e.text)
  }
}
