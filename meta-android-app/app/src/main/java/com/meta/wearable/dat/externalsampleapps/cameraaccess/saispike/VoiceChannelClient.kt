/* sai-fi — voice concierge. */

// The voice channel's HTTP surface: POST /v1/voice/message to start work, GET /v1/voice/stream to
// hear about it. Together these replace the WebSocket.
//
// The write ACKS rather than streams. On the CLI the POST is the stream, and that cannot work here:
// this device holds one long-lived connection and has to hear things no write of its own provoked —
// an approval raised mid-turn, a completion after a reconnect, a resolution the user made in the
// desktop app, a turn the agent began by draining its own queue.
//
// The stream is deliberately NOT a second source of truth. It carries agent events; what the
// concierge does about them is the FSM's business, and this file has no opinion on any of it.
//
// Server side: cloud-api `routes/voice.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalOption
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What `POST /v1/voice/message` answered. */
data class VoiceAck(
    /** The session the task landed in, or null when the router handled it without delivering. */
    val sessionId: String?,
    /**
     * False when nothing will stream.
     *
     * Without this the device waits for events that are never coming — the router handled the
     * message itself, so there is no turn to follow.
     */
    val delivered: Boolean,
    /** The handle for cancelling a held task before it runs. */
    val pendingId: String? = null,
    /**
     * A system reply about DELIVERY, not about the work — a hibernated machine waking, an agent
     * that is offline. Speak it: dropping it is how a task sent to a sleeping machine bought a
     * silent minute with no explanation.
     */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

object VoiceChannelClient {

  /**
   * Send a task on the voice channel.
   *
   * The channel is pinned by the ROUTE, not by anything sent here — `voice` buys the concierge
   * bypass, and a caller able to name its own channel would take that bypass for free.
   */
  suspend fun sendMessage(
      baseUrl: String,
      bearerToken: String,
      machineId: String,
      message: String,
      deliveryMode: String? = null,
      attachments: JSONArray? = null,
  ): VoiceAck =
      withContext(Dispatchers.IO) {
        val body =
            JSONObject().apply {
              put("machineId", machineId)
              put("message", message)
              deliveryMode?.let { put("deliveryMode", it) }
              attachments?.let { put("attachments", it) }
            }

        val conn = (URL("$baseUrl/v1/voice/message").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        try {
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val status = conn.responseCode
          if (status !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw ConciergeHttpException(status, "POST /v1/voice/message failed ($status): $err")
          }
          val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
          VoiceAck(
              sessionId = json.optString("sessionId", "").takeIf { it.isNotEmpty() },
              delivered = json.optBoolean("delivered", false),
              pendingId = json.optString("pendingId", "").takeIf { it.isNotEmpty() },
              notice = json.optString("notice", "").takeIf { it.isNotEmpty() },
              noticeIsError = json.optBoolean("noticeIsError", false),
          )
        } finally {
          conn.disconnect()
        }
      }

  /**
   * Read the agent-event stream until the caller stops collecting or the connection drops.
   *
   * Suspends for the life of the stream. Cancellation closes the connection; a drop returns
   * normally, because reconnect policy belongs to the caller, not here — see ReconnectPolicy.
   *
   * Frames that do not parse are DROPPED rather than thrown: a newer server must not be able to end
   * a call by sending something this build predates. Same rule as `dispatchServerMessage`.
   */
  suspend fun streamEvents(
      baseUrl: String,
      bearerToken: String,
      machineId: String,
      onEvent: suspend (AgentEvent) -> Unit,
      onLog: (String) -> Unit = {},
  ) =
      withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(machineId, "UTF-8")
        val conn =
            (URL("$baseUrl/v1/voice/stream?machineId=$encoded").openConnection()
                as HttpURLConnection)
        conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.connectTimeout = 15_000
        // No read timeout: an idle stream between turns is the normal case, not a fault.
        conn.readTimeout = 0

        try {
          val status = conn.responseCode
          if (status !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw ConciergeHttpException(status, "GET /v1/voice/stream failed ($status): $err")
          }
          conn.inputStream.bufferedReader().use { reader ->
            readSseLines(reader) { payload ->
              val event = parseAgentEvent(payload)
              if (event == null) onLog("[voice] dropped an unrecognised frame") else onEvent(event)
            }
          }
        } finally {
          conn.disconnect()
        }
      }

  /**
   * Pull `data:` payloads out of an SSE stream.
   *
   * Comment lines (`:` — the keepalive) and blanks are skipped. Nothing here interprets the payload.
   */
  private suspend fun readSseLines(reader: BufferedReader, onPayload: suspend (String) -> Unit) {
    while (true) {
      val line = reader.readLine() ?: return // stream closed
      when {
        line.isEmpty() -> continue
        line.startsWith(":") -> continue // keepalive
        line.startsWith("data:") -> {
          val payload = line.removePrefix("data:").trim()
          if (payload.isNotEmpty() && payload != "[DONE]") onPayload(payload)
        }
      }
    }
  }
}

/**
 * One SSE payload as a typed [AgentEvent], or null if this build does not recognise it.
 *
 * Tolerant reads throughout (`opt*`), matching the rest of this app: a field added server-side must
 * not break a client that predates it.
 */
fun parseAgentEvent(raw: String): AgentEvent? {
  val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
  return when (json.optString("type")) {
    "text" -> AgentEvent.Text(json.optString("text"))
    "progress" ->
        AgentEvent.Progress(
            text = json.optString("text"),
            tool = json.optString("tool", "").takeIf { it.isNotEmpty() },
            failed = json.optBoolean("failed", false),
        )
    "status" -> AgentStatus.fromWire(json.optString("status"))?.let { AgentEvent.Status(it) }
    "complete" ->
        AgentEvent.Complete(json.optString("summary", "").takeIf { it.isNotEmpty() })
    "error" -> AgentEvent.Error(json.optString("text"))
    "notice" -> AgentEvent.Notice(json.optString("text"))
    "queued-task-started" ->
        json.optString("pendingId", "").takeIf { it.isNotEmpty() }?.let {
          AgentEvent.QueuedTaskStarted(it)
        }
    "approval-request" ->
        json.optString("id", "").takeIf { it.isNotEmpty() }?.let { id ->
          AgentEvent.ApprovalRequest(
              id = id,
              title = json.optString("title"),
              description = json.optString("description"),
              approvalType = json.optString("approvalType"),
              isLinkOnly = json.optBoolean("isLinkOnly", false),
              allowAlways = json.optBoolean("allowAlways", false),
              options = json.optJSONArray("options")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                  arr.optJSONObject(i)?.let {
                    ApprovalOption(it.optString("value"), it.optString("label"))
                  }
                }
              },
              multiple = if (json.has("multiple")) json.optBoolean("multiple") else null,
              allowOther = if (json.has("allowOther")) json.optBoolean("allowOther") else null,
              expiresAt = json.optLong("expiresAt", 0L).takeIf { it > 0 },
          )
        }
    "approval-resolved" ->
        json.optString("id", "").takeIf { it.isNotEmpty() }?.let {
          AgentEvent.ApprovalResolved(it, json.optString("status"))
        }
    "session-state" ->
        AgentEvent.SessionState(
            running = json.optString("running", "").takeIf { it.isNotEmpty() },
            blockedOn = json.optString("blockedOn", "").takeIf { it.isNotEmpty() },
            queued =
                json.optJSONArray("queued")?.let { arr ->
                  (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
        )
    else -> null
  }
}
