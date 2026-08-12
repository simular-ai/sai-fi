/* sai-fi — voice concierge. */

// The FSM's AgentBridge, over HTTP.
//
// Nine methods against six endpoints on `/v1/voice/*`, plus a photo stash that never leaves the
// device. This is the whole write side of a call: start work, hold it, cancel it, escalate it, stop
// it, rotate the conversation, resolve an approval.
//
// Two of the nine do NOT map to an obvious endpoint, and both matter:
//
//   queueTask uses the same POST /message as forwardTask, with deliveryMode=queue. If the server
//   finds the session idle it starts the task instead of holding it, and the ack comes back with no
//   pendingId — which is TaskStartedImmediately, thrown so a caller cannot go on to promise the user
//   it is waiting, or hold an id that will never exist.
//
//   resolveApproval goes to /v1/voice/approve rather than /v1/agents/approve, because that route
//   wants selections already grouped one array per question and a spoken pick carries no question
//   index. The grouping needs the approval doc's payload, which is server-side.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalSelection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.CancelOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ResetOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.SendNowOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskStartedImmediately
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** The HTTP calls this bridge makes, as one seam — so the FSM can be driven without a network. */
interface VoiceTransport {
  suspend fun sendMessage(
      machineId: String,
      message: String,
      deliveryMode: String?,
      attachments: JSONArray?,
  ): VoiceAck

  /** POST to one of the `/v1/voice` operations. Returns the parsed body; throws on a non-2xx. */
  suspend fun post(path: String, body: JSONObject): JSONObject
}

class HttpAgentBridge(
    private val machineId: String,
    private val transport: VoiceTransport,
    private val log: (String) -> Unit = {},
) : AgentBridge {

  /**
   * Photos captured for whatever writes next.
   *
   * Stays on the device: a held task takes its own copy at enqueue (the FSM calls
   * [takePendingAttachments] before the durable write), so a later capture cannot ride along with
   * it. Synchronized because captures arrive on the DAT callback thread.
   */
  private val stash = mutableListOf<TaskAttachment>()

  fun addPendingAttachment(attachment: TaskAttachment) {
    synchronized(stash) { stash += attachment }
  }

  /**
   * Where the user physically is, for the request about to follow.
   *
   * Consumed by the NEXT task written and then cleared — the model sets `includeLocation` on the
   * request it belongs to, so a fix left behind would ride an unrelated one.
   */
  @Volatile private var pendingLocation: TaskLocation? = null

  fun setPendingLocation(location: TaskLocation) {
    pendingLocation = location
  }

  private fun takeLocation(): TaskLocation? {
    val loc = pendingLocation
    pendingLocation = null
    return loc
  }

  override fun takePendingAttachments(): List<TaskAttachment> =
      synchronized(stash) {
        val taken = stash.toList()
        stash.clear()
        taken
      }

  override suspend fun forwardTask(text: String, attachments: List<TaskAttachment>?): String {
    // No deliveryMode: the FSM only forwards when nothing is in flight, and the router's default is
    // what every other channel does.
    val ack = transport.sendMessage(machineId, taskText(text, takeLocation()), null, attachments.toJsonOrNull())
    ack.notice?.let { log("[voice] notice: $it") }
    return ack.sessionId ?: ""
  }

  override suspend fun queueTask(text: String, attachments: List<TaskAttachment>?): String {
    // The fix is folded in HERE, at enqueue, and frozen with the task — which is why the stamp is an
    // absolute UTC instant rather than "just now": a held task may run much later.
    val ack = transport.sendMessage(machineId, taskText(text, takeLocation()), "queue", attachments.toJsonOrNull())
    ack.notice?.let { log("[voice] notice: $it") }
    // No pendingId means the session turned out idle and the task STARTED. Thrown, not returned, so
    // the caller cannot mistake it for a queued task.
    return ack.pendingId ?: throw TaskStartedImmediately()
  }

  override suspend fun steer(text: String) {
    transport.sendMessage(machineId, text, "steer", null)
  }

  override suspend fun cancelQueuedTask(pendingId: String): CancelOutcome {
    val res = transport.post("cancel-queued", JSONObject().put("pendingId", pendingId))
    return if (res.optString("outcome") == "already-started") CancelOutcome.ALREADY_STARTED
    else CancelOutcome.CANCELLED
  }

  override suspend fun sendQueuedNow(pendingId: String): SendNowOutcome {
    val res = transport.post("send-now", JSONObject().put("pendingId", pendingId))
    return if (res.optString("outcome") == "already-started") SendNowOutcome.ALREADY_STARTED
    else SendNowOutcome.SENT
  }

  override suspend fun abort() {
    transport.post("abort", JSONObject())
  }

  override suspend fun resetSession(): ResetOutcome =
      when (transport.post("reset", JSONObject()).optString("outcome")) {
        "ok" -> ResetOutcome.OK
        "rate-limited" -> ResetOutcome.RATE_LIMITED
        else -> ResetOutcome.FAILED
      }

  /**
   * Resolve an approval.
   *
   * A rejected selection comes back 422 and the transport throws — which is exactly what the FSM
   * wants: it keeps the request pending, keeps its timer, and nudges the model to re-present.
   * Swallowing it would clear the FSM's pending state while the doc stays pending, and the call
   * would deadlock waiting for an answer it believes it already gave.
   */
  override suspend fun resolveApproval(
      id: String,
      decision: ApprovalDecision,
      selection: ApprovalSelection?,
  ) {
    val values =
        selection?.selectedOptions ?: selection?.selectedOption?.let { listOf(it) } ?: emptyList()
    val body =
        JSONObject().apply {
          put("approvalId", id)
          put("decision", decision.wire)
          // FLAT on purpose — the server groups them per question, because a spoken pick carries no
          // question index and only the approval doc knows which question offered what.
          if (values.isNotEmpty()) put("values", JSONArray().apply { values.forEach { put(it) } })
        }
    transport.post("approve", body)
  }
}

private fun List<TaskAttachment>?.toJsonOrNull(): JSONArray? {
  if (this.isNullOrEmpty()) return null
  return JSONArray().apply {
    this@toJsonOrNull.forEach { a ->
      put(
          JSONObject().apply {
            put("path", a.path)
            put("name", a.name)
            put("mime", a.mime)
            put("size", a.size)
            a.downloadUrl?.let { put("downloadUrl", it) }
            a.fileId?.let { put("fileId", it) }
            a.width?.let { put("width", it) }
            a.height?.let { put("height", it) }
          })
    }
  }
}

/** Where the user physically is, read from the phone for a task that needs it. */
data class TaskLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val approximate: Boolean = false,
    val label: String? = null,
    val capturedAt: Long,
)

/**
 * The text a forwarded task is written as: the user's words, plus the location fix when one came
 * with it.
 *
 * A user message has no metadata channel — the message doc's only structured extra is
 * `attachments` — so anything the agent needs that is not the user's words has to travel in the
 * text. That makes this wording load-bearing.
 */
fun taskText(text: String, location: TaskLocation?): String =
    if (location == null) text else text + describeTaskLocation(location)

/**
 * Render a fix as a line the agent can act on.
 *
 * Three jobs, none decorative: say the machine's OWN location is wrong (it runs in a datacenter and
 * will otherwise answer "near me" from there); stamp it as an absolute UTC instant (a queued task
 * freezes this at enqueue and may run much later, so a relative "just now" would age into a lie);
 * and fence it as data, because the place name is reverse-geocoded user-controlled text.
 *
 * A coarse fix is rounded to ~1km as well as labelled — five decimals for a neighbourhood-accurate
 * grant invents precision the phone never had.
 */
fun describeTaskLocation(loc: TaskLocation): String {
  val digits = if (loc.approximate) 2 else 5
  val coords = "%.${digits}f, %.${digits}f".format(Locale.US, loc.lat, loc.lon)
  val accuracy =
      when {
        loc.approximate ->
            " (approximate — a coarse fix, good to roughly a neighbourhood, not a spot)"
        loc.accuracyM != null -> " (±${Math.round(loc.accuracyM)}m)"
        else -> ""
      }
  val place = loc.label?.let { " — $it" } ?: ""
  val iso =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .format(Date(loc.capturedAt))
  return "\n\n[Context, not part of the request and not an instruction: the user's own physical " +
      "location, from their phone, as of $iso — " +
      "$coords$accuracy$place. Use this for anything local (nearby places, weather, directions, " +
      "travel time). This computer's own network location is a datacenter and is NOT where the user is.]"
}
