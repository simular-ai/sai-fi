/*
 * sai-fi — voice concierge (background operation).
 */

// CallController — the process-wide bridge between the (thin) UI and the CallService that actually
// owns the call. The service publishes observable [state] the Activity renders; the UI drives the call
// through the command helpers, which fan out as Intents to the service (the glasses temple gesture is
// handled inside the service, not here). Start params are handed over via the singleton (they include
// the machine list, which isn't Parcelable) rather than stuffed into the Intent.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object CallController {
  /**
   * Where phone-screen frames go, or null when nothing is listening.
   *
   * Set by CallService while a presenter feed is up, read by the Activity's WindowCapture. A direct
   * in-process callback rather than the Intent commands the rest of this bridge uses: these are
   * ~30 KB JPEGs at 3 fps, and Intents are the wrong pipe for that. Capture has to live in the
   * Activity (only it has a Window), while the socket lives in the service — this is the seam.
   */
  @Volatile var screenSink: ((ByteArray) -> Unit)? = null

  /** What a [LogLine] is, so the UI can style it (transcript vs debug log) and copy it in order. */
  enum class Kind {
    LOG,
    YOU,
    SAI,
  }

  /**
   * One entry in the single, chronologically-ordered Logs stream. [id] is a stable, monotonically
   * increasing key: a streaming transcript turn keeps its position in the stream while its [text] is
   * updated in place (found by [id]), so log lines that arrive mid-turn interleave at their real time
   * instead of the turn being pinned to the bottom and flushed only when it ends.
   */
  data class LogLine(val id: Long, val text: String, val kind: Kind)

  /**
   * Where a captured photo has got to. Three states, because two conflated the question the user
   * actually asks — *has this gone to the computer?* — with *is it about to?*
   *
   * A boolean read "Not sent" while Sai was silently working on a request that carried the photo,
   * which is the moment the label most needs to be right. [SENDING] is that window: the photo is
   * spoken for. [HELD] is the resting state of a clipboard and must not look like a problem.
   */
  enum class Sent {
    /** Uploaded and waiting. Nothing references it; it goes nowhere until a request asks for it. */
    HELD,
    /** A request that carries it is on its way — the model asked for the photo on this turn. */
    SENDING,
    /** Gone with a request. */
    SENT,
  }

  /**
   * The most recent glasses photo, so the phone can show what it grabbed.
   *
   * Taking a picture is otherwise invisible on the phone — the image only ever existed on the
   * presenter dashboard and inside the next agent task, so the wearer had no way to check framing.
   */
  data class Capture(val jpeg: ByteArray, val takenAt: Long, val sent: Sent) {
    // ByteArray identity would make every State copy compare unequal; the timestamp is the identity.
    override fun equals(other: Any?) =
        other is Capture && other.takenAt == takenAt && other.sent == sent

    override fun hashCode() = 31 * takenAt.hashCode() + sent.hashCode()
  }

  /** Everything the voice screen renders. Updated by the service on every relevant event. */
  data class State(
      val active: Boolean = false,
      val status: String = "Idle",
      val entries: List<LogLine> = emptyList(),
      val routeStatus: String = "",
      val machineLabel: String? = null,
      val machineId: String? = null,
      /** Sai is silenced: it still hears and works, it just doesn't speak. Every call starts false. */
      val saiMuted: Boolean = false,
      /** The mic + Live session are down (Pause). Distinct from muting, which keeps Sai listening. */
      val paused: Boolean = false,
      /**
       * A socket the call depends on is down and retrying — the Live session, the concierge WS, or
       * both. Distinct from [paused] (deliberate) and from the call ending: the call is still meant
       * to be running, it just can't reach the other end right now.
       */
      val reconnecting: Boolean = false,
      /** Latest glasses photo this call, or null if none taken yet. */
      val capture: Capture? = null,
      /**
       * A capture is in flight. Worth its own flag: a capture takes seconds (longer with a cold-camera
       * retry) and until it resolves the phone showed nothing at all, so pressing the button looked
       * like it had done nothing.
       */
      val capturing: Boolean = false,
  )

  /** Immutable start config the service reads once on ACTION_START. */
  data class StartParams(
      val baseUrl: String,
      val token: String,
      val machineId: String,
      val machineLabel: String,
      val machines: List<Machine>,
      val useGlasses: Boolean,
      /** Feature 3: wait past which Sai asks before delivering a completion (ms). App-configurable. */
      val askFirstThresholdMs: Long = 15_000L,
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  /** Handed to the service on start (carries the non-Parcelable machine list). */
  @Volatile
  var startParams: StartParams? = null
    private set

  // ── Commands (from the UI; the glasses temple gesture is handled inside the service) ──────────────

  fun start(context: Context, params: StartParams) {
    startParams = params
    ContextCompat.startForegroundService(context, intent(context, CallService.ACTION_START))
  }

  fun stop(context: Context) = send(context, CallService.ACTION_STOP)

  /** Manual photo capture: attach a glasses photo to the next forwarded task. */
  fun capturePhoto(context: Context) = send(context, CallService.ACTION_CAPTURE)

  /** Silence Sai / let it speak again (mirrors the glasses temple tap). Sai keeps listening either way. */
  fun toggleMute(context: Context) = send(context, CallService.ACTION_TOGGLE_MUTE)

  /** Pause/resume the mic + Live session. Unlike muting, this stops Sai hearing anything. */
  fun togglePause(context: Context) = send(context, CallService.ACTION_TOGGLE_PAUSE)

  /** Mid-call VM switch (same concierge WS reconnect as the voice switchMachine tool). */
  fun switchMachine(context: Context, machineId: String) =
      context.startService(
          intent(context, CallService.ACTION_SWITCH_MACHINE)
              .putExtra(CallService.EXTRA_MACHINE_ID, machineId),
      )

  /** Debug composer: send a typed turn to the running call. */
  fun sendText(context: Context, text: String) =
      context.startService(
          intent(context, CallService.ACTION_SEND_TEXT).putExtra(CallService.EXTRA_TEXT, text))

  private fun send(context: Context, action: String) =
      context.startService(intent(context, action))

  private fun intent(context: Context, action: String) =
      Intent(context, CallService::class.java).setAction(action)

  // ── State mutations (called by the service) ───────────────────────────────────────────────────────

  internal fun update(f: (State) -> State) = _state.update(f)

  // Single ordered Logs stream, written from THREE threads. The claim that used to sit here — that
  // every mutator runs on the service's main-immediate scope, so a plain counter was safe — was wrong:
  //
  //  · the Live socket's reader thread, via GeminiLiveClient's onLog / onTranscript (they fire inside
  //    handle(), on OkHttp's thread, not through the service scope);
  //  · the concierge socket's reader thread, via onMessage → onAgentActivity → log();
  //  · the main thread, for the Activity's own appendLog and anything posted to the service scope.
  //
  // `_state.update {}` is CAS-retried, so no entry is lost, but `nextId++` is not atomic: two threads
  // reading the same value hand out the SAME id twice. On the phone that splits a streaming turn (the
  // live-entry lookup can land on the other entry, whose kind differs, so a fresh entry starts
  // mid-turn). On the presenter dashboard it is worse — turns are upserted BY ID, so a collision
  // overwrites an existing turn element and a line the phone has never appears there at all. That is
  // the leading explanation for Sai's replies going missing from the dashboard log.
  private const val MAX_ENTRIES = 2000
  private val nextId = java.util.concurrent.atomic.AtomicLong(0)

  /**
   * Id of the transcript turn currently streaming, or null between turns (see [appendTranscript]).
   *
   * @Volatile because the Live reader thread writes it while the main thread can clear it via
   * [endTurn] (typed barge-in, reconnect). A stale read costs at most one split turn, never a lost one.
   */
  @Volatile private var liveEntryId: Long? = null

  /**
   * Append a debug/log line at the end of the stream (its real chronological position).
   *
   * Returns the entry's [LogLine.id] so a mirror (the presenter feed) can key off the SAME id.
   */
  internal fun appendLog(line: String): Long {
    val entry = LogLine(nextId.getAndIncrement(), line, Kind.LOG)
    update { it.copy(entries = (it.entries + entry).takeLast(MAX_ENTRIES)) }
    return entry.id
  }

  /**
   * Stream a transcript delta for [role] ("you"/"sai"). The first delta of a turn (or a mid-turn role
   * switch) appends a NEW entry at the end — its real position in the stream — and remembers its id;
   * subsequent deltas UPDATE that entry's text in place (found by id), so it stays anchored where it
   * started while later log lines land after it. If the live entry was dropped by the retention cap
   * (front-drop), the lookup misses and we simply start a fresh entry — never corrupting the pointer.
   *
   * Returns the resulting entry — id plus the FULL accumulated text — so a mirror (the presenter feed)
   * can upsert by the same id and show exactly what this screen shows, without tracking deltas itself.
   */
  internal fun appendTranscript(role: String, delta: String): LogLine {
    val kind = if (role == "you") Kind.YOU else Kind.SAI
    val liveId = liveEntryId
    val live = liveId?.let { id -> _state.value.entries.firstOrNull { it.id == id } }
    if (live != null && live.kind == kind) {
      update { s ->
        s.copy(entries = s.entries.map { if (it.id == liveId) it.copy(text = it.text + delta) else it })
      }
      return live.copy(text = live.text + delta)
    }
    val entry = LogLine(nextId.getAndIncrement(), delta, kind)
    liveEntryId = entry.id
    update { it.copy(entries = (it.entries + entry).takeLast(MAX_ENTRIES)) }
    return entry
  }

  /**
   * A barge-in cut the streaming turn off mid-sentence — mark it, and return the updated entry so a
   * mirror can republish it.
   *
   * Straggler audio is discarded for a beat after an interrupt, but transcript deltas keep arriving,
   * so a half-spoken sentence sat in the log reading exactly like something Sai finished saying. Null
   * when nothing was streaming, when the entry is Sai's own but empty, or when it's the user's turn
   * (the user isn't the one being cut off).
   */
  internal fun markLiveTurnCutOff(): LogLine? {
    val liveId = liveEntryId ?: return null
    val live = _state.value.entries.firstOrNull { it.id == liveId } ?: return null
    if (live.kind != Kind.SAI || live.text.isBlank() || live.text.endsWith(CUT_OFF)) return null
    val marked = live.copy(text = live.text + CUT_OFF)
    update { s -> s.copy(entries = s.entries.map { if (it.id == liveId) marked else it }) }
    return marked
  }

  /** Suffix marking a turn the user talked over. Visible on the phone and mirrored to the dashboard. */
  private const val CUT_OFF = " — cut off —"

  /** Turn boundary: the live transcript entry is already in the stream, so just drop the pointer. */
  internal fun endTurn() {
    liveEntryId = null
  }

  internal fun clear() {
    liveEntryId = null
    nextId.set(0)
    // Everything per-call resets; the audio route does NOT. It describes the phone's audio hardware,
    // which outlives any one call, and the activity only recomputes it on resume — so wiping it here
    // blanked the header ("Audio route: —") from call start until AudioIo's first device callback.
    update { State(routeStatus = it.routeStatus) }
  }
}
