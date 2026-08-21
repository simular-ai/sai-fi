/* sai-fi — voice concierge. */

// The bridge that turns the FSM's six methods into four `/v1/agents/*` endpoints. Driven against a
// recording transport — no network, so all of it runs in the JVM suite.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalSelection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ResetOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  /** Every call, in order, so an abort can be checked for stopping the read BEFORE it posts. */
  val abandoned = mutableListOf<Int>()

  override suspend fun sendMessage(
      machineId: String,
      message: String,
      attachments: JSONArray?,
      follow: Boolean,
  ) {
    sends += Sent(message, attachments, follow)
  }

  override fun abandonTurn() {
    abandoned += posts.size
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

  // ── the user's clock ───────────────────────────────────────────────────────

  @Test
  fun `the clock is the user's, so a relative date cannot resolve against the datacenter's day`() {
    // One instant, two places, and NOT THE SAME DAY. That is the failure worth a test: "book a table
    // for Friday" is not a question about the agent's calendar, and a VM in California reading it for
    // a user in Singapore books Thursday without anything about the answer looking wrong.
    val instant = Instant.parse("2026-08-20T18:00:00Z").toEpochMilli()

    val singapore = describeTaskClock(instant, TimeZone.getTimeZone("Asia/Singapore"))
    assertTrue(singapore, singapore.contains("Friday 21 August 2026 at 02:00"))
    assertTrue("the zone has to travel too, or DST is unrecoverable", singapore.contains("Asia/Singapore"))

    val california = describeTaskClock(instant, TimeZone.getTimeZone("America/Los_Angeles"))
    assertTrue(california, california.contains("Thursday 20 August 2026 at 11:00"))
  }

  @Test
  fun `the spoken clock is the phone's, never UTC`() {
    val instant = Instant.parse("2026-08-20T18:00:00Z").toEpochMilli()
    val spoken = describePhoneClock(instant, TimeZone.getTimeZone("Asia/Singapore"))
    assertTrue(spoken, spoken.contains("Friday 21 August 2026 at 2:00 AM"))
    assertTrue(spoken, spoken.contains("Asia/Singapore"))
    assertTrue("UTC must be named as the wrong clock", spoken.contains("UTC is not their time"))
    assertFalse("24-hour UTC must not be what it speaks", spoken.contains("18:00"))
  }

  @Test
  fun `every forwarded task carries the clock, location or no location`() = runBlocking {
    // Unlike the location, which costs a GPS read and only rides on the requests that asked for it.
    // The phone knows the time for free, and the request that needs it is not identifiable in advance
    // — "is it open now" does not look like a question about time until it is answered wrongly.
    val t = RecordingTransport()
    bridge(t).forwardTask("book a table for Friday")

    val sent = t.sends.single().message
    assertTrue("the user's words must still lead", sent.startsWith("book a table for Friday"))
    assertTrue("no clock reached the agent: $sent", sent.contains("where the user is (time zone "))
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

  @Test
  fun `reset names the api channel, so it rotates OUR conversation and not the terminal's`() =
      runBlocking {
        // The bug this pins: the route defaults an absent `channel` to `cli`, and this body was built
        // without one. So a user saying "start fresh" rotated the TERMINAL's session while this
        // client's kept growing — nothing failed, the rotation just landed on somebody else's
        // conversation, which is why it survived every attempt to reset away from a poisoned one.
        val t = RecordingTransport()
        bridge(t).resetSession()
        assertEquals("api", t.posts.single().second.getString("channel"))
      }

  @Test
  fun `abort stops reading the turn, and does it before the POST`() = runBlocking {
    // Ordering, not just occurrence. The POST is a network round-trip that can be slow or fail, and
    // every event that arrives while it is in flight is an event from a turn the user has already
    // stopped. On 2026-08-20 an aborted task delivered its progress, its answer and a `complete`
    // through a reader nobody had closed, and Sai reported the result of work it had been told to
    // abandon — so the local teardown goes first and unconditionally.
    val t = RecordingTransport()
    bridge(t).abort()
    assertEquals("abandoned once, before any post", listOf(0), t.abandoned)
    assertEquals(listOf("abort"), t.posts.map { it.first })
  }

  @Test
  fun `a failing abort POST still leaves the turn abandoned, and does not throw`() = runBlocking {
    // The half that matters most is the local one: a machine that never got the abort is a task
    // running somewhere, but a reader still attached is a task still TALKING to the user.
    //
    // And it must not propagate. This used to throw, which returned before `applyInterrupt` could
    // close the turn out — so the FSM stayed in `working` with its reader ALREADY torn down, meaning
    // no event could ever arrive to end it, and admission then held every later task behind a turn
    // that could not finish. One failed POST killed the rest of the call. The server's half is
    // best-effort by nature; the fix was to code it that way rather than only say so.
    val t = RecordingTransport(throwOn = "abort")
    var localAborts = 0

    HttpAgentBridge("m1", t, abortLocalWork = { localAborts++ }).abort()

    assertEquals("the local halves still ran", 1, localAborts)
    assertEquals("and ran before the doomed post", listOf(0), t.abandoned)
    assertEquals(listOf("abort"), t.posts.map { it.first })
  }

  @Test
  fun `abort needs no channel — it is about the machine, not a conversation`() = runBlocking {
    // Not an oversight to be "fixed" later: /abort stops whatever that machine is running. There is
    // no per-channel answer for it, and sending one would imply there is.
    val t = RecordingTransport()
    bridge(t).abort()
    assertTrue("abort must not name a channel", !t.posts.single().second.has("channel"))
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
