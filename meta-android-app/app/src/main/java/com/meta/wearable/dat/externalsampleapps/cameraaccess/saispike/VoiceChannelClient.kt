/* sai-fi — voice concierge. */

// The agent's HTTP surface, as this app uses it.
//
// There is no voice-specific endpoint and no voice channel. A voice client authenticates as an
// ordinary API caller and uses `/v1/agents/*` exactly as a script would — which is the point: this
// repo runs against the Sai API as it already exists, and a fork does not need a server change to
// work.
//
// That decision costs one thing and buys another:
//
//   The stream belongs to a TURN, not to the call. `POST /v1/agents/message` streams that message's
//   turn and ends. Between turns nothing is connected, so an approval resolved in the desktop app
//   while nothing is running is not heard here. The FSM's queue is local for the same reason and
//   with the same consequence — see docs/VOICE_FSM.md.
//
//   In exchange there is nothing to keep alive, nothing to reconnect between turns, and no server
//   state that can disagree with this device about what is queued.
//
// The wire vocabulary is the Vercel AI SDK v6 UI message stream (`text-delta`, `data-progress`,
// `data-approval-request`, …), which is not what the FSM speaks. [parseAgentEvent] is the whole
// translation, and it is deliberately the only place that knows both alphabets.
//
// Server side: cloud-api `routes/cli.ts`, mounted at `/v1/agents`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalOption
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalQuestion
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object VoiceChannelClient {

  /**
   * The channel this client speaks as, named on every request that has a per-channel answer.
   *
   * `cli` and `api` are separate conversations on the server — the dedicated-session key is
   * `{uid}_{machineId}_{channel}` — and the three routes that resolve one (`POST /new-session`,
   * `GET /context`, `GET /sessions`) DEFAULT AN ABSENT `channel` TO `cli`, because `cli` was the only
   * programmatic channel when they were written and an old CLI must keep working. Nothing errors when
   * it is omitted; the answer is simply about somebody else's conversation.
   *
   * A constant rather than a literal per call site because the omission has already happened twice
   * over: `resetSession` here rotated the TERMINAL's session on a spoken "start fresh", while the
   * per-call mint in [VoiceSession] named the channel correctly — two paths to the same endpoint,
   * disagreeing. `POST /message` needs none of this: that route is per-channel by URL — the
   * `/v1/agents` mount is `api`, `/v1/cli` is `cli` — so a send always lands in this client's own
   * conversation.
   */
  const val API_CHANNEL = "api"

  /**
   * The body for `POST /v1/agents/new-session` — rotate THIS client's conversation.
   *
   * Shared by both callers so neither can forget the channel: the FSM's `resetSession` (the user
   * saying "start fresh") and the per-call session mint.
   */
  fun newSessionBody(machineId: String): JSONObject =
      JSONObject().put("machineId", machineId).put("channel", API_CHANNEL)

  /**
   * Send a message and hand back its turn's event stream, once the agent has accepted it.
   *
   * Deliberately TWO steps rather than one suspending call. `forwardTask` runs inside the FSM's
   * mutex, and the FSM needs that mutex to handle every event this stream is about to produce — so a
   * send that stayed suspended for the life of the turn would deadlock the call on its own first
   * task. This suspends only until the response HEADERS arrive, which is what makes the difference
   * between "the agent refused this" and "the agent is working on it" available to the caller, and
   * then the reading happens on somebody else's coroutine.
   *
   * Throws [ConciergeHttpException] on a non-2xx, so a refused task is a failed forward rather than
   * a turn that silently never starts.
   *
   * No `channel` is sent. The route pins `api`, and `api` is a programmatic channel, so the text
   * concierge and the legacy free-text matchers are already skipped: a spoken "restart agent"
   * reaches the machine as a task instead of being answered by the server. That bypass is the only
   * thing a voice client ever needed from a channel of its own.
   */
  suspend fun openMessageStream(
      baseUrl: String,
      bearerToken: String,
      machineId: String,
      message: String,
      attachments: JSONArray? = null,
  ): TurnStream =
      withContext(Dispatchers.IO) {
        val body =
            JSONObject().apply {
              put("machineId", machineId)
              put("message", message)
              attachments?.let { put("attachments", it) }
            }

        val conn = (URL("$baseUrl/v1/agents/message").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.applyCloudApiHeaders(bearerToken)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        // No read timeout: a long tool call with nothing to say is the normal case, not a fault.
        conn.readTimeout = 0

        try {
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val status = conn.responseCode
          if (status !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            throw ConciergeHttpException(status, "POST /v1/agents/message failed ($status): $err")
          }
        } catch (e: Throwable) {
          conn.disconnect()
          throw e
        }
        TurnStream(conn)
      }

  /**
   * One turn's events, already accepted by the agent and waiting to be read.
   *
   * Owns the connection: whoever reads it closes it, including on cancellation.
   */
  class TurnStream(private val conn: HttpURLConnection) {

    /**
     * Read to the end of the turn.
     *
     * Returns when the agent finishes, errors, or the connection drops — reconnect policy belongs to
     * the caller, not here. Frames that do not parse are DROPPED rather than thrown: a newer server
     * must not be able to end a call by sending something this build predates.
     */
    suspend fun read(onEvent: suspend (AgentEvent) -> Unit, onLog: (String) -> Unit = {}) =
        withContext(Dispatchers.IO) {
          val turn = TurnEvents()
          try {
            conn.inputStream.bufferedReader().use { reader ->
              readSseLines(reader) { payload ->
                when (val out = turn.onPayload(payload)) {
                  null -> onLog(describeIgnoredFrame(payload))
                  else -> onEvent(out)
                }
              }
            }
          } finally {
            conn.disconnect()
          }
        }

    /** Give up on the turn without reading it — for a steer, whose events arrive on the original. */
    fun discard() {
      runCatching { conn.disconnect() }
    }
  }

  /**
   * POST to one of the agent's non-streaming operations — abort, new-session, approve.
   *
   * A non-2xx throws [ConciergeHttpException] carrying the status, which the callers depend on: a
   * 400 from `approve` is a rejected selection that must reach the FSM, and a 401/403 anywhere is
   * permanent and ends the call rather than retrying.
   */
  suspend fun postOperation(
      baseUrl: String,
      bearerToken: String,
      path: String,
      body: JSONObject,
  ): JSONObject =
      withContext(Dispatchers.IO) {
        val conn = (URL("$baseUrl/v1/agents/$path").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.applyCloudApiHeaders(bearerToken)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
          conn.outputStream.use { it.write(body.toString().toByteArray()) }
          val status = conn.responseCode
          if (status !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw ConciergeHttpException(status, "POST /v1/agents/$path failed ($status): $err")
          }
          val text = conn.inputStream.bufferedReader().use { it.readText() }
          if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
          conn.disconnect()
        }
      }

}

/**
 * Pull `data:` payloads out of an SSE stream.
 *
 * Comment lines (`:` — the keepalive) and blanks are skipped, as is the `[DONE]` sentinel. Nothing
 * here interprets the payload.
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

/**
 * One UI-message-stream frame as a typed [AgentEvent], or null if it is not one the FSM cares about.
 *
 * The two alphabets do not line up one-for-one, and the mismatches are the interesting part:
 *
 * - `text-delta` carries a FRAGMENT. The FSM's `Text` is fragment-tolerant (it only resets dead-air
 *   backoff), so each delta is passed straight through rather than buffered — buffering here would
 *   mean holding the answer until `text-end`, and the point of streaming is that she can start
 *   speaking before the agent has finished.
 * - `reasoning-*` is mid-turn thinking. It maps to `Progress`, which is silent by design.
 * - `finish` is the turn ending, so it becomes `Complete` — the FSM's own end-of-turn signal. There
 *   is no summary on the wire; the answer already arrived as text.
 * - `tool-output-error` is a STEP that failed while the task carries on: `Progress(failed = true)`,
 *   not `Error`, which is terminal. Getting this backwards ends turns that are still running.
 * - `data-status` is delivery news (waking a machine, agent offline), which is `Notice` — the one
 *   thing that must be relayed before the task has produced anything at all.
 *
 * Tolerant reads throughout (`opt*`): a field added server-side must not break a client that
 * predates it.
 */
/**
 * One turn's frames, in order — and the one piece of state reading them requires.
 *
 * `parseAgentEvent` is stateless and has to stay that way, but a turn is not: the agent's ANSWER
 * arrives as a run of `text-delta` frames and the turn then ends with a bare
 * `{"type":"finish","finishReason":"stop"}` that carries nothing. Mapping that straight through
 * produced `Complete(summary = null)`, whose nudge tells the concierge:
 *
 *   "The task ended WITHOUT reporting any result. You have no answer and nothing to relay. …
 *    Tell the user plainly that nothing came back."
 *
 * — on every task that answered perfectly well. Caught on a live call: the agent replied with the
 * time and the user was never told it. Nothing anywhere built a `Complete` WITH a summary, so this
 * was every completion, not an edge case.
 *
 * So the deltas are accumulated here and handed over on `finish`. They are still emitted
 * individually as they arrive — the activity log and the presenter transcript are built from them,
 * and `getSaiStatus` reads that log, which is the only reason the answer was reachable at all
 * before. An empty accumulation still means an empty summary, because a turn that really said
 * nothing should still report that it said nothing.
 */
class TurnEvents {
  private val answer = StringBuilder()

  /** The event this frame becomes, or null when the frame is one we ignore. */
  fun onPayload(raw: String): AgentEvent? {
    val event = parseAgentEvent(raw) ?: return null
    return when (event) {
      is AgentEvent.Text -> {
        answer.append(event.text)
        event
      }
      // Only fill in a summary the server did not send; never overwrite one it did.
      is AgentEvent.Complete ->
          if (event.summary.isNullOrEmpty() && answer.isNotBlank())
              AgentEvent.Complete(answer.toString().trim())
          else event
      else -> event
    }
  }
}

/**
 * The log line for a frame that mapped to nothing.
 *
 * NAME it. Most unmapped frames are envelope markers this client is right to ignore
 * (`text-start`/`text-end` around the deltas it does read), but a frame the server started sending and
 * this client never learned looks identical from here — and an anonymous "dropped something" line
 * cannot tell the two apart. The live-agent contract test reads these lines, so the name is the
 * evidence it works from.
 *
 * A `data-*` frame additionally gets its FIELD NAMES, because the two cases stop being equivalent
 * there: a bookend carries nothing worth having, whereas a custom data part is the server handing over
 * state, and every other one of those (`data-status`, `data-progress`, `data-approval-request`) has a
 * handler. On 2026-08-19 the live tier showed `data-session` arriving once per turn with no handler at
 * all, and the log could not say what was in it — which is the gap this closes.
 *
 * Names only, never values: this log is mirrored to the presenter dashboard, and a data part can carry
 * agent-derived text. Knowing a frame has an `id` and a `title` is what tells you whether to write a
 * branch for it; the contents are not needed for that and are not ours to project on a wall.
 */
fun describeIgnoredFrame(payload: String): String {
  val json = runCatching { JSONObject(payload) }.getOrNull()
  val type = json?.optString("type").orEmpty().ifEmpty { "(untyped)" }
  if (json == null || !type.startsWith("data-")) return "[voice] ignored frame: $type"
  val fields = json.optJSONObject("data")?.keys()?.asSequence()?.sorted()?.toList().orEmpty()
  val where = if (json.has("data")) "data" else "frame"
  return if (fields.isEmpty()) "[voice] ignored frame: $type (no $where fields — nothing carried)"
  else "[voice] ignored frame: $type — UNHANDLED data part, $where fields: ${fields.joinToString(", ")}"
}

fun parseAgentEvent(raw: String): AgentEvent? {
  val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
  val data = json.optJSONObject("data")
  return when (json.optString("type")) {
    "text-delta" -> json.optString("delta").takeIf { it.isNotEmpty() }?.let { AgentEvent.Text(it) }
    "reasoning-delta" ->
        json.optString("delta").takeIf { it.isNotEmpty() }?.let { AgentEvent.Progress(it) }
    "data-progress" ->
        data?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
          AgentEvent.Progress(it, tool = data.optString("tool", "").takeIf { t -> t.isNotEmpty() })
        }
    "tool-input-available" ->
        json.optString("toolName").takeIf { it.isNotEmpty() }?.let {
          AgentEvent.Progress(it, tool = it)
        }
    // A failed STEP, not a failed turn. `failed` is what the concierge reacts to; without it she
    // has no idea anything went wrong and fills the silence with a result she never received.
    "tool-output-error" ->
        AgentEvent.Progress(
            json.optString("errorText").ifEmpty { "a step failed" },
            failed = true,
        )
    // Delivery news, not work: a hibernated machine waking, an agent that is offline.
    "data-status" ->
        data?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
          AgentEvent.Notice(it, kind = data.optString("kind", "").takeIf { k -> k.isNotEmpty() })
        }
    "start" -> AgentEvent.Status(AgentStatus.PROCESSING)
    "finish" -> AgentEvent.Complete()
    "error" ->
        AgentEvent.Error(json.optString("errorText").ifEmpty { json.optString("text") })
    "data-approval-request" -> data?.let { parseApprovalRequest(it) }
    else -> null
  }
}

/**
 * A `data-approval-request` payload as an [AgentEvent.ApprovalRequest].
 *
 * The card can carry its options two ways and both must work: `options` for a single question, and
 * `questions` when it asks several. The flat list is what the model picks from and what gets read
 * back to the user, so a multi-question card is FLATTENED for that purpose while the grouping is
 * kept alongside — see `groupSelections`, which is what puts a spoken answer back in the right
 * slots.
 */
private fun parseApprovalRequest(data: JSONObject): AgentEvent.ApprovalRequest? {
  val id = data.optString("approvalId").takeIf { it.isNotEmpty() } ?: return null

  val questions =
      data.optJSONArray("questions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
          arr.optJSONObject(i)?.let { q ->
            ApprovalQuestion(
                options = q.optJSONArray("options").toApprovalOptions(),
                multiple = q.optBoolean("multiple", false),
                allowOther = q.optBoolean("allowOther", false),
            )
          }
        }
      }

  val flat =
      questions?.flatMap { it.options }?.takeIf { it.isNotEmpty() }
          ?: data.optJSONArray("options").toApprovalOptions().takeIf { it.isNotEmpty() }

  return AgentEvent.ApprovalRequest(
      id = id,
      title = data.optString("title"),
      description = data.optString("description"),
      approvalType = data.optString("approvalType"),
      isLinkOnly = data.optBoolean("isLinkOnly", false),
      // No `allowAlways` read — the frame has never carried one. See `Effect.Approve`.
      options = flat,
      // Only when it actually asks more than one thing. For a single question the flat list IS the
      // grouping, and carrying a redundant copy is one more thing that can disagree with itself.
      questions = questions?.takeIf { it.size > 1 },
      multiple =
          when {
            data.has("multiple") -> data.optBoolean("multiple")
            questions != null -> questions.any { it.multiple }
            else -> null
          },
      allowOther =
          when {
            data.has("allowOther") -> data.optBoolean("allowOther")
            questions != null -> questions.any { it.allowOther }
            else -> null
          },
      expiresAt = data.optLong("expiresAt", 0L).takeIf { it > 0 },
  )
}

/**
 * An options array as [ApprovalOption]s, accepting both shapes the agent writes.
 *
 * `askChoice` options are plain strings; the richer cards use `{value,label}`. A plain string is
 * both — the value the agent matches on and the words the user hears.
 */
private fun JSONArray?.toApprovalOptions(): List<ApprovalOption> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { i ->
    optJSONObject(i)?.let { ApprovalOption(it.optString("value"), it.optString("label")) }
        ?: optString(i, "").takeIf { it.isNotEmpty() }?.let { ApprovalOption(it, it) }
  }
}
