/* sai-fi — voice concierge. */

// The FSM's AgentBridge, over the ordinary agent API.
//
// Six methods against four endpoints on `/v1/agents/*`, plus a photo stash that never leaves the
// device. This is the whole write side of a call: start work, steer it, stop it, rotate the
// conversation, resolve an approval.
//
// There is no endpoint for HOLDING a task, because holding one is not something the server is told
// about — the queue lives in the FSM and nothing else can start what is in it. `queueTask`,
// `cancelQueuedTask` and `sendQueuedNow` used to be here, against a durable pending doc; what they
// bought was a held task surviving a dropped call, and what they cost was three races against the
// agent draining that doc behind the FSM's back. See docs/VOICE_FSM.md.
//
// `steer` is the one method whose endpoint is not obvious: it is the same POST /message as
// forwardTask. The router folds a message into a running turn on its own, which is what steering
// means, so there is nothing extra to say.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalDecision
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalSelection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ResetOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** The HTTP calls this bridge makes, as one seam — so the FSM can be driven without a network. */
interface VoiceTransport {
  /**
   * Send a message.
   *
   * Returns once the agent has ACCEPTED it, not once the turn is done — this runs inside the FSM's
   * mutex, and the FSM needs that mutex to handle the events this very message is about to produce.
   * Throws when the agent refuses it.
   *
   * @param follow whether this message's response stream is the one to read. True for a new task,
   *   whose stream carries the turn. False for a steer: it lands in a turn that is already being
   *   read, so its own stream would deliver every event a second time.
   */
  suspend fun sendMessage(
      machineId: String,
      message: String,
      attachments: JSONArray?,
      follow: Boolean,
  )

  /** POST to one of the `/v1/agents` operations. Returns the parsed body; throws on a non-2xx. */
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
   * [takePendingAttachments] when it holds one), so a later capture cannot ride along with it.
   * Synchronized because captures arrive on the DAT callback thread.
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

  /**
   * Forward a task and follow its turn.
   *
   * The location fix is folded in HERE and frozen with the text — which is why the stamp is an
   * absolute UTC instant rather than "just now". A held task takes its fix when it is HELD, not when
   * it drains, and it may drain much later.
   *
   * Returns the empty string: on this API the response is the turn's event stream, so no session id
   * comes back. Nothing reads it — the FSM keeps no session identity on purpose (see State.kt).
   */
  override suspend fun forwardTask(text: String, attachments: List<TaskAttachment>?): String {
    transport.sendMessage(
        machineId, taskText(text, takeLocation()), attachments.toJsonOrNull(), follow = true)
    return ""
  }

  /**
   * Steer the running turn.
   *
   * The same endpoint as a new task, deliberately: the router folds a message into a turn already
   * running, which is exactly what steering is. No location — a correction mid-turn is about the
   * task, not about where the user is standing.
   */
  override suspend fun steer(text: String) {
    transport.sendMessage(machineId, text, null, follow = false)
  }

  override suspend fun abort() {
    transport.post("abort", JSONObject().put("machineId", machineId))
  }

  /**
   * Rotate onto a fresh conversation.
   *
   * A 429 is the rate limit, and it is worth telling apart from a failure: "you've done this a lot
   * lately" and "it broke" need different things said to the user.
   */
  override suspend fun resetSession(): ResetOutcome =
      try {
        transport.post("new-session", JSONObject().put("machineId", machineId))
        ResetOutcome.OK
      } catch (e: ConciergeHttpException) {
        if (e.status == 429) ResetOutcome.RATE_LIMITED else ResetOutcome.FAILED
      } catch (e: Exception) {
        log("new-session failed: ${e.message}")
        ResetOutcome.FAILED
      }

  /**
   * Resolve an approval.
   *
   * A rejected selection comes back 400 and the transport throws — which is exactly what the FSM
   * wants: it keeps the request pending, keeps its timer, and nudges the model to re-present.
   * Swallowing it would clear the FSM's pending state while the request stays open, and the call
   * would deadlock waiting for an answer it believes it already gave.
   *
   * `selections` is positional, one non-empty group per question; the FSM grouped them on the way
   * here. An empty group is sent as-is rather than dropped — the agent refuses the whole resolution,
   * which is the honest outcome for a question the user never answered, and silently omitting it
   * would approve the card with an answer missing.
   */
  override suspend fun resolveApproval(
      id: String,
      decision: ApprovalDecision,
      selection: ApprovalSelection?,
  ) {
    val body =
        JSONObject().apply {
          put("approvalId", id)
          put("response", decision.wire)
          selection?.selections?.takeIf { it.isNotEmpty() }?.let { groups ->
            put(
                "selections",
                JSONArray().apply {
                  groups.forEach { g -> put(JSONArray().apply { g.forEach { put(it) } }) }
                })
          }
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
