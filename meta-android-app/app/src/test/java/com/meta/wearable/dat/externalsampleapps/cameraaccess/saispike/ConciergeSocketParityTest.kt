package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-protocol parity: every server→client message in `ws-messages.json` is dispatched by the REAL
 * [ConciergeSocket.handle], and every client→server message is one this client can produce.
 *
 * This is the contract that had no guard at all. The nudge STRINGS had five fixture files while the
 * message envelope carrying them — the thing an out-of-tree client must implement exactly — had none,
 * so a new `type` on the server reached this client only if somebody remembered to look. Now a server
 * variant with no handler here fails a test.
 *
 * Regenerate the fixtures with: `npm run -w cloud-api concierge:fixtures`.
 */
class ConciergeSocketParityTest {

  private fun fixtures(): JSONArray {
    val text =
        checkNotNull(javaClass.getResourceAsStream("/parity/ws-messages.json")) {
              "missing /parity/ws-messages.json — run `npm run -w cloud-api concierge:fixtures`"
            }
            .bufferedReader()
            .use { it.readText() }
    return JSONArray(text)
  }

  private fun messages(dir: String): List<JSONObject> =
      fixtures().let { arr ->
        (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("dir") == dir }
            .map { it.getJSONObject("message") }
      }

  /** Recorders for the five callbacks the dispatcher can invoke. */
  private class Harness {
    val agentEvents = mutableListOf<JSONObject>()
    val activity = mutableListOf<JSONObject>()
    val spoken = mutableListOf<String>()
    val instructed = mutableListOf<String>()
    var approvalTimeouts = 0

    fun handle(raw: String) =
        dispatchServerMessage(
            raw,
            onAgentEvent = { agentEvents += it },
            onAgentActivity = { activity += it },
            onSpeak = { spoken += it },
            onInstruct = { instructed += it },
            onApprovalTimeout = { approvalTimeouts++ },
        )

    fun handled(): Int =
        agentEvents.size + activity.size + spoken.size + instructed.size + approvalTimeouts
  }

  @Test
  fun `every server message in the contract is dispatched`() {
    val serverMessages = messages("server->client")
    assertTrue("no server->client fixtures found", serverMessages.isNotEmpty())

    for (msg in serverMessages) {
      val h = Harness()
      h.handle(msg.toString())
      assertEquals(
          "unhandled server message type=${msg.getString("type")} — ConciergeSocket.handle has no " +
              "branch for it, so the server can emit a frame this client silently drops",
          1,
          h.handled(),
      )
    }
  }

  @Test
  fun `each server message reaches the right callback`() {
    fun handleOne(type: String): Harness {
      val msg =
          messages("server->client").first { it.getString("type") == type }
      return Harness().also { it.handle(msg.toString()) }
    }

    // agent-event drives the model to react; agent-activity is display-only. Routing them to the same
    // place is the bug this pins: an activity frame that nudges the model makes it narrate progress,
    // which update discipline forbids.
    assertEquals(1, handleOne("agent-event").agentEvents.size)
    assertEquals(0, handleOne("agent-event").activity.size)
    assertEquals(1, handleOne("agent-activity").activity.size)
    assertEquals(0, handleOne("agent-activity").agentEvents.size)

    // `speak` is voiced verbatim; `instruct` is context and must never be spoken as written.
    assertEquals(1, handleOne("speak").spoken.size)
    assertEquals(0, handleOne("speak").instructed.size)
    assertEquals(1, handleOne("instruct").instructed.size)
    assertEquals(0, handleOne("instruct").spoken.size)

    assertEquals(1, handleOne("approval-timeout").approvalTimeouts)
  }

  /** A malformed or unknown frame must be ignored, not crash the socket's reader thread. */
  @Test
  fun `unknown and malformed frames are ignored`() {
    val h = Harness()
    h.handle("not json at all")
    h.handle("""{"type":"some-future-thing","payload":1}""")
    h.handle("{}")
    assertEquals(0, h.handled())
  }

  /**
   * The client→server half. These are the frames this client is expected to be able to SEND, so the
   * test asserts the fixture set covers what ConciergeSocket actually offers a method for — a
   * protocol message the client cannot produce is a contract the server is waiting on forever.
   */
  @Test
  fun `the contract covers every client message this socket can send`() {
    val types = messages("client->server").map { it.getString("type") }.toSet()
    assertEquals(setOf("effects", "usage", "attachment", "location", "keepalive"), types)
  }
}
