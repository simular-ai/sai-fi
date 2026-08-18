/* sai-fi — voice concierge. */

// The bridge that turns the FSM's six methods into four `/v1/agents/*` endpoints. Driven against a
// recording transport — no network, so all of it runs in the JVM suite.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalSelection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ResetOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingTransport(
    var responses: MutableMap<String, JSONObject> = mutableMapOf(),
    var throwOn: String? = null,
    var throwStatus: Int = 400,
) : VoiceTransport {
  data class Sent(val message: String, val attachments: JSONArray?, val follow: Boolean)

  val sends = mutableListOf<Sent>()
  val posts = mutableListOf<Pair<String, JSONObject>>()

  override suspend fun sendMessage(
      machineId: String,
      message: String,
      attachments: JSONArray?,
      follow: Boolean,
  ) {
    sends += Sent(message, attachments, follow)
  }

  override suspend fun post(path: String, body: JSONObject): JSONObject {
    posts += path to body
    if (path == throwOn) throw ConciergeHttpException(throwStatus, "rejected")
    return responses[path] ?: JSONObject()
  }
}

class HttpAgentBridgeTest {

  private fun bridge(t: RecordingTransport) = HttpAgentBridge("m1", t)

  private val photo =
      TaskAttachment(path = "uploads/a.jpg", name = "a.jpg", mime = "image/jpeg", size = 10)

  // ── which stream gets followed ─────────────────────────────────────────────

  @Test
  fun `a new task is FOLLOWED — its response is the turn`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).forwardTask("take a screenshot")
    assertTrue(t.sends.single().follow)
  }

  @Test
  fun `a steer is NOT followed — the turn it lands in is already being read`() = runBlocking {
    // Reading a steer's own stream too would deliver every event of that turn a second time: the
    // completion twice, the approval twice, the whole answer twice.
    val t = RecordingTransport()
    bridge(t).steer("make it 8pm")
    assertEquals(false, t.sends.single().follow)
    assertTrue("a steer must not post to any operation", t.posts.isEmpty())
  }

  @Test
  fun `steer carries no location — a correction is about the task, not the user's whereabouts`() =
      runBlocking {
        val t = RecordingTransport()
        val b = bridge(t)
        b.setPendingLocation(TaskLocation(lat = 1.0, lon = 2.0, capturedAt = 0L))
        b.steer("make it 8pm")
        assertEquals("make it 8pm", t.sends.single().message)
      }

  // ── the photo stash ────────────────────────────────────────────────────────

  @Test
  fun `taking the stash empties it, so a later capture cannot ride along`() = runBlocking {
    val b = bridge(RecordingTransport())
    b.addPendingAttachment(photo)
    assertEquals(listOf(photo), b.takePendingAttachments())
    assertEquals(
        "the second take must be empty", emptyList<TaskAttachment>(), b.takePendingAttachments())
  }

  @Test
  fun `a held task carries its OWN photo when it finally drains`() = runBlocking {
    // The FSM takes the stash at enqueue and hands it back here at drain time, so the picture
    // travels with the task that was captured for it rather than with whatever writes next.
    val t = RecordingTransport()
    bridge(t).forwardTask("what is this", listOf(photo))
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

  // ── resetting the conversation ─────────────────────────────────────────────

  @Test
  fun `reset tells the rate limit apart from a failure`() = runBlocking {
    // The two need different things said: "you've done this a lot lately" and "it broke".
    val limited = RecordingTransport(throwOn = "new-session", throwStatus = 429)
    assertEquals(ResetOutcome.RATE_LIMITED, bridge(limited).resetSession())

    val broken = RecordingTransport(throwOn = "new-session", throwStatus = 500)
    assertEquals(ResetOutcome.FAILED, bridge(broken).resetSession())

    assertEquals(ResetOutcome.OK, bridge(RecordingTransport()).resetSession())
  }

  @Test
  fun `reset and abort both name the machine`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).abort()
    bridge(t).resetSession()
    assertEquals(listOf("abort", "new-session"), t.posts.map { it.first })
    assertTrue(t.posts.all { it.second.getString("machineId") == "m1" })
  }

  // ── approvals ──────────────────────────────────────────────────────────────

  @Test
  fun `selections go out GROUPED, one array per question`() = runBlocking {
    // The agent resolves a choice positionally. The FSM did the grouping — it is the only thing
    // that knows which question offered what — and the bridge must not flatten it back.
    val t = RecordingTransport()
    bridge(t)
        .resolveApproval(
            "a1",
            ApprovalDecision.APPROVED,
            ApprovalSelection(listOf(listOf("a"), listOf("b", "c"))))
    val (path, body) = t.posts.single()
    assertEquals("approve", path)
    assertEquals("a1", body.getString("approvalId"))
    assertEquals("yes", body.getString("response"))
    val groups = body.getJSONArray("selections")
    assertEquals(2, groups.length())
    assertEquals(1, groups.getJSONArray(0).length())
    assertEquals(2, groups.getJSONArray(1).length())
  }

  @Test
  fun `the decision is the API's yes-no, not the doc's approved-denied`() = runBlocking {
    // `always` used to be the third value here. The endpoint still accepts it and folds it into a
    // plain approve, which is why sending it was a bug rather than a no-op: the POST succeeded,
    // nothing persisted, and the user had been promised the asking would stop (cloud-api ADR 0014).
    val t = RecordingTransport()
    val b = bridge(t)
    b.resolveApproval("a1", ApprovalDecision.APPROVED, null)
    b.resolveApproval("a2", ApprovalDecision.DENIED, null)
    assertEquals(listOf("yes", "no"), t.posts.map { it.second.getString("response") })
  }

  @Test
  fun `a decision with no selection sends no selections at all`() = runBlocking {
    val t = RecordingTransport()
    bridge(t).resolveApproval("a1", ApprovalDecision.DENIED, null)
    val body = t.posts.single().second
    assertTrue("a denial carries no picks", !body.has("selections"))
  }

  @Test
  fun `an unanswered question is sent as an EMPTY group, not omitted`() = runBlocking {
    // The agent refuses a resolution that does not answer every question, which is the outcome we
    // want — it becomes a re-present nudge. Dropping the empty group instead would shift every
    // later answer one question to the left and approve the card with the wrong picks.
    val t = RecordingTransport()
    bridge(t)
        .resolveApproval(
            "a1", ApprovalDecision.APPROVED, ApprovalSelection(listOf(emptyList(), listOf("b"))))
    val groups = t.posts.single().second.getJSONArray("selections")
    assertEquals(2, groups.length())
    assertEquals(0, groups.getJSONArray(0).length())
  }

  @Test
  fun `a rejected selection PROPAGATES, so the FSM keeps the request answerable`() {
    // The 400 is what makes chooseOption re-present. Swallowed, the FSM would clear its pending
    // state while the request stays open — the call then waits forever for an answer it thinks it
    // already gave.
    val t = RecordingTransport(throwOn = "approve")
    assertThrows(ConciergeHttpException::class.java) {
      runBlocking {
        bridge(t)
            .resolveApproval(
                "a1", ApprovalDecision.APPROVED, ApprovalSelection(listOf(listOf("pigeon"))))
      }
    }
    Unit
  }
}
