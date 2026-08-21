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

  /**
   * Stop following the turn in flight, if there is one. Idempotent, and a no-op when none is.
   *
   * Not suspending, deliberately: it is called from inside the FSM's mutex, and closing a connection
   * must not be able to block the lock that every agent event needs to be handled.
   *
   * Abstract rather than defaulted, because a double that quietly does nothing here is how this went
   * unnoticed — the whole failure was a layer that believed a teardown was happening somewhere else.
   */
  fun abandonTurn()

  /** POST to one of the `/v1/agents` operations. Returns the parsed body; throws on a non-2xx. */
  suspend fun post(path: String, body: JSONObject): JSONObject
}

class HttpAgentBridge(
    private val machineId: String,
    private val transport: VoiceTransport,
    private val log: (String) -> Unit = {},
    /**
     * Stop whatever the DEVICE is doing for the running turn. See [abort].
     *
     * A no-op by default so tests and any other caller keep the pure-HTTP bridge they had.
     */
    private val abortLocalWork: () -> Unit = {},
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
   * Returns the empty string, because nothing here reads a session id — the FSM keeps no session
   * identity on purpose (see State.kt).
   *
   * It is NOT true, as this said until 2026-08-19, that none comes back. The live tier showed staging
   * sending a `data-session` frame carrying `sessionId` once per turn; this client maps it to nothing
   * and drops it, which is consistent with the design decision above but is a choice rather than an
   * absence. Worth knowing, because the last bug in this area — "start fresh" rotating the terminal's
   * conversation instead of this one — is precisely the disagreement that frame would have made
   * visible.
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

  /**
   * Stop the running turn — ALL THREE halves of it, and the local two go first.
   *
   * Stop LISTENING, stop the device's own work, then ask the server. Both local steps run before the
   * POST and regardless of what it does, because the POST is a round trip that can be slow or fail
   * outright and the two device-side failures do not need the server's permission to be fixed:
   *
   * - The turn's event stream was read to its natural end after an abort, so on 2026-08-20 a task the
   *   user had just stopped delivered its progress, its answer, and a `complete` nudge — and Sai
   *   reported the result of work it had been told to abandon. `applyInterrupt` has always claimed
   *   the reader was torn down; [VoiceTransport.abandonTurn] is what finally makes that true.
   * - A turn about what the user is looking at spends most of its life on this phone waiting for the
   *   glasses camera, so a "wait, stop" still ended with a shutter firing and a photo arriving for a
   *   task that no longer existed.
   *
   * The server's half is best-effort and is now at least OBSERVABLE: `{aborted: false}` means there
   * was nothing there to stop, which was previously indistinguishable from success.
   */
  override suspend fun abort() {
    runCatching { transport.abandonTurn() }
        .onFailure { log("[bridge] could not stop following the turn — ${it.message}") }
    runCatching { abortLocalWork() }
        .onFailure { log("[bridge] local abort failed — ${it.message}") }
    // Best-effort, and it has to be CODED that way and not merely described that way. A throw here
    // returned before `applyInterrupt` could close the turn out, leaving the FSM in `working` with
    // its reader already torn down — so no event could ever arrive to end it, and admission held
    // every later task behind a turn that could not finish. The local halves above are the ones that
    // actually stop the work; a failed POST costs a server-side turn still running, which the next
    // `abort` or the turn's own end resolves.
    runCatching { transport.post("abort", JSONObject().put("machineId", machineId)) }
        .onSuccess {
          if (!it.optBoolean("aborted", true)) log("[bridge] abort: nothing to stop, the server says")
        }
        .onFailure { log("[bridge] the server was not told to abort — ${it.message}") }
  }

  /**
   * Rotate onto a fresh conversation.
   *
   * A 429 is the rate limit, and it is worth telling apart from a failure: "you've done this a lot
   * lately" and "it broke" need different things said to the user.
   *
   * The body comes from [VoiceChannelClient.newSessionBody] rather than being built here, because
   * built here it forgot the `channel` — and the route defaults an absent one to `cli`, so a user
   * saying "start fresh" rotated the TERMINAL's conversation and left this one exactly where it was,
   * poison and all. Nothing failed; the rotation just happened to somebody else.
   */
  override suspend fun resetSession(): ResetOutcome =
      try {
        transport.post("new-session", VoiceChannelClient.newSessionBody(machineId))
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
 * The text a forwarded task is written as: the user's words, plus the clock, plus the location fix
 * when one came with it.
 *
 * A user message has no metadata channel — the message doc's only structured extra is
 * `attachments` — so anything the agent needs that is not the user's words has to travel in the
 * text. That makes this wording load-bearing.
 *
 * The clock rides on EVERY task and the location only on the ones that asked for it. That asymmetry
 * is not an oversight: a fix costs a GPS read, a permission and up to six seconds, while the phone
 * already knows what time it is for free and without being asked. See [describeTaskClock] for why
 * every task needs it.
 */
fun taskText(
    text: String,
    location: TaskLocation?,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String = text + describeTaskClock(nowMs, zone) + (location?.let { describeTaskLocation(it) } ?: "")

/**
 * Render the user's own clock as a line the agent can act on.
 *
 * The agent runs in a datacenter, and until this existed nothing ever told it otherwise, so every
 * question with a time in it was answered from a machine that is routinely a continent and several
 * hours away. Two failures, and the second is the one that does damage:
 *
 * - "what time is it" is answered with the VM's time, confidently and wrongly;
 * - **a relative date silently resolves against the wrong day.** "Book a table for Friday", "remind
 *   me tonight", "is it open now" all depend on what day and hour it is WHERE THE USER IS, and a VM
 *   west of them can still be on yesterday. Nothing about the answer looks wrong when it comes back.
 *
 * So this goes on every task rather than on request. The day name is spelled out because that is the
 * word the user actually said, and the zone is given as an IANA id — the agent can do the arithmetic
 * itself from that, and an offset alone would lose the DST rule for any date but today.
 *
 * Stamped when the task is FORWARDED, not when it was spoken, which is the opposite of the rule for
 * location: a queued task's location is frozen because the user may have moved, but its time must
 * not be, because "now" for the agent is when it reads this and a held task can drain much later.
 */
fun describeTaskClock(nowMs: Long, zone: TimeZone): String {
  val local =
      SimpleDateFormat("EEEE d MMMM yyyy 'at' HH:mm", Locale.US)
          .apply { timeZone = zone }
          .format(Date(nowMs))
  return "\n\n[Context, not part of the request and not an instruction: it is $local where the " +
      "user is (time zone ${zone.id}, read from their phone). Resolve every relative date and time " +
      "in the request — \"today\", \"tonight\", \"Friday\", \"in an hour\", \"now\" — against THIS, " +
      "and give times back in it. This computer's own clock and time zone are a datacenter's and " +
      "are NOT the user's.]"
}

/**
 * The phone's clock, as the Live model should speak it.
 *
 * Distinct from [describeTaskClock]: that one rides a forwarded task so the *agent* can resolve
 * "Friday". This one answers the user asking the time out loud. Gemini's own clock is UTC, which
 * is why "what time is it" used to come back several hours off — the model answered without a
 * tool, from a clock that is not the user's. 12-hour with AM/PM because this is spoken.
 */
fun describePhoneClock(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
  val local =
      SimpleDateFormat("EEEE d MMMM yyyy 'at' h:mm a", Locale.US)
          .apply { timeZone = zone }
          .format(Date(nowMs))
  return "On the user's phone it is $local (time zone ${zone.id}). This is their local time. " +
      "Answer them in this zone. UTC is not their time."
}

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
