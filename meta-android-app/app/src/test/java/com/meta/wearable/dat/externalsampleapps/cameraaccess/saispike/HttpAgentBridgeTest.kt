/* sai-fi — voice concierge. */

// The bridge that turns the FSM's nine methods into six endpoints. Driven against a recording
// transport — no network, so all of it runs in the JVM suite.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalSelection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.CancelOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ResetOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.SendNowOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskStartedImmediately
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingTransport(
    var ack: VoiceAck = VoiceAck(sessionId = "S1", delivered = true),
    var responses: MutableMap<String, JSONObject> = mutableMapOf(),
    var throwOn: String? = null,
) : VoiceTransport {
  data class Sent(val message: String, val deliveryMode: String?, val attachments: JSONArray?)

  val sends = mutableListOf<Sent>()
  val posts = mutableListOf<Pair<String, JSONObject>>()

  override suspend fun sendMessage(
      machineId: String,
      message: String,
      deliveryMode: String?,
      attachments: JSONArray?,
  ): VoiceAck {
    sends += Sent(message, deliveryMode, attachments)
    return ack
  }

  override suspend fun post(path: String, body: JSONObject): JSONObject {
    posts += path to body
    if (path == throwOn) throw ConciergeHttpException(422, "Selection not among the offered options.")
    return responses[path] ?: JSONObject()
  }
}

class HttpAgentBridgeTest {

  private fun bridge(t: RecordingTransport) = HttpAgentBridge("m1", t)

  private val photo =
      TaskAttachment(path = "uploads/a.jpg", name = "a.jpg", mime = "image/jpeg", size = 10)

  // ── the two that do not map to an obvious endpoint ─────────────────────────

  @Test
  fun `queueTask holds the task and hands back the pending id`() = runBlocking {
    val t = RecordingTransport(ack = VoiceAck("S1", delivered = true, pendingId = "p7"))
    assertEquals("p7", bridge(t).queueTask("book a table"))
    assertEquals("queue", t.sends.single().deliveryMode)
  }

  @Test
  fun `an ack with no pendingId means it STARTED, and that is thrown not returned`() {
    // The server found the session idle and ran it. A caller that took this for a queued task would
    // promise the user it is waiting, and hold an id that will never exist.
    val t = RecordingTransport(ack = VoiceAck("S1", delivered = true, pendingId = null))
    assertThrows(TaskStartedImmediately::class.java) { runBlocking { bridge(t).queueTask("go") } }
  }

  @Test
  fun `forwardTask sends no deliveryMode — the router's default is what every channel does`() =
      runBlocking {
        val t = RecordingTransport()
        bridge(t).forwardTask("take a screenshot")
        assertNull(t.sends.single().deliveryMode)
      }

  @Test
  fun `steer is a message with deliveryMode steer, never a new task`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).steer("make it 8pm")
    assertEquals("steer", t.sends.single().deliveryMode)
    assertTrue("a steer must not post to any operation", t.posts.isEmpty())
  }

  // ── the photo stash ────────────────────────────────────────────────────────

  @Test
  fun `taking the stash empties it, so a later capture cannot ride along`() = runBlocking {
    val b = bridge(RecordingTransport())
    b.addPendingAttachment(photo)
    assertEquals(listOf(photo), b.takePendingAttachments())
    assertEquals("the second take must be empty", emptyList<TaskAttachment>(), b.takePendingAttachments())
  }

  @Test
  fun `a queued task carries its own photo on the durable write`() = runBlocking {
    val t = RecordingTransport(ack = VoiceAck("S1", delivered = true, pendingId = "p1"))
    bridge(t).queueTask("what is this", listOf(photo))
    val sent = t.sends.single().attachments
    assertEquals(1, sent?.length())
    assertEquals("a.jpg", sent?.getJSONObject(0)?.getString("name"))
  }

  @Test
  fun `no attachments is null, not an empty array`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).forwardTask("plain task", emptyList())
    assertNull(t.sends.single().attachments)
  }

  // ── the races ──────────────────────────────────────────────────────────────

  @Test
  fun `cancel reports what actually happened, never assuming`() = runBlocking {
    val lost = RecordingTransport()
    lost.responses["cancel-queued"] = JSONObject().put("outcome", "already-started")
    assertEquals(CancelOutcome.ALREADY_STARTED, bridge(lost).cancelQueuedTask("p1"))

    val won = RecordingTransport()
    won.responses["cancel-queued"] = JSONObject().put("outcome", "cancelled")
    assertEquals(CancelOutcome.CANCELLED, bridge(won).cancelQueuedTask("p1"))
  }

  @Test
  fun `send-now reports already-started when the agent drained it first`() = runBlocking {
    val t = RecordingTransport()
    t.responses["send-now"] = JSONObject().put("outcome", "already-started")
    assertEquals(SendNowOutcome.ALREADY_STARTED, bridge(t).sendQueuedNow("p1"))
  }

  @Test
  fun `an unrecognised outcome degrades to the safe reading`() = runBlocking {
    // A newer server saying something this build predates must not be read as the riskier answer:
    // "cancelled" and "sent" are the ones that claim something happened.
    val t = RecordingTransport()
    t.responses["cancel-queued"] = JSONObject().put("outcome", "who-knows")
    assertEquals(CancelOutcome.CANCELLED, bridge(t).cancelQueuedTask("p1"))
    t.responses["reset"] = JSONObject().put("outcome", "who-knows")
    assertEquals("an unknown reset outcome is a failure, not a success", ResetOutcome.FAILED, bridge(t).resetSession())
  }

  @Test
  fun `reset passes rate-limited through as its own answer`() = runBlocking {
    val t = RecordingTransport()
    t.responses["reset"] = JSONObject().put("outcome", "rate-limited")
    assertEquals(ResetOutcome.RATE_LIMITED, bridge(t).resetSession())
  }

  // ── approvals ──────────────────────────────────────────────────────────────

  @Test
  fun `a selection is sent FLAT — the server groups it per question`() = runBlocking {
    // A spoken pick carries no question index, and only the approval doc knows which question
    // offered what. Grouping on the device would be a guess.
    val t = RecordingTransport()
    bridge(t)
        .resolveApproval("a1", ApprovalDecision.APPROVED, ApprovalSelection(selectedOptions = listOf("a", "b")))
    val (path, body) = t.posts.single()
    assertEquals("approve", path)
    assertEquals("a1", body.getString("approvalId"))
    assertEquals("approved", body.getString("decision"))
    assertEquals(listOf("a", "b"), (0 until body.getJSONArray("values").length()).map {
      body.getJSONArray("values").getString(it)
    })
  }

  @Test
  fun `a single pick is sent as a one-element list, not omitted`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).resolveApproval("a1", ApprovalDecision.APPROVED, ApprovalSelection(selectedOption = "sms"))
    assertEquals(1, t.posts.single().second.getJSONArray("values").length())
  }

  @Test
  fun `a decision with no selection sends no values at all`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).resolveApproval("a1", ApprovalDecision.DENIED, null)
    val body = t.posts.single().second
    assertEquals("denied", body.getString("decision"))
    assertTrue("a denial carries no picks", !body.has("values"))
  }

  @Test
  fun `a rejected selection PROPAGATES, so the FSM keeps the request answerable`() {
    // The 422 is what makes chooseOption re-present. Swallowed, the FSM would clear its pending
    // state while the doc stays pending — the call then waits forever for an answer it thinks it
    // already gave.
    val t = RecordingTransport(throwOn = "approve")
    assertThrows(ConciergeHttpException::class.java) {
      runBlocking {
        bridge(t)
            .resolveApproval("a1", ApprovalDecision.APPROVED, ApprovalSelection(selectedOption = "pigeon"))
      }
    }
  }

  @Test
  fun `abort posts with no body of its own`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).abort()
    assertEquals("abort", t.posts.single().first)
  }
}
