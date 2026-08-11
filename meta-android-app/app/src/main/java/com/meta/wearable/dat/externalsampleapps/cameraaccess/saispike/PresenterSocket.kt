/*
 * sai-fi — voice concierge (presenter feed).
 */

// PresenterSocket — publishes the live call to a laptop dashboard: conversation text, log lines, call
// state, glasses photos, and raw PCM for BOTH voices. Demo tool: Sai's replies come out of the glasses
// speaker, so without this only the wearer hears them.
//
// Outbound-only, so no port is opened on the phone and no cable is needed — the laptop is already
// reachable (it hosts cloud-api for the demo), so we dial it. Modelled on ConciergeSocket: same OkHttp
// setup, same backoff, same generation-stamped listener (a superseded socket's callbacks must not
// touch live state — that bug cost us a phantom second connection once already). Simpler than
// ConciergeSocket: no token minting, no permanent close codes.
//
// TWO RULES, both about never harming the call this exists to showcase:
//  1. Every publish is fire-and-forget. If the socket is down the frame is DROPPED, never queued —
//     a dead laptop must not grow memory or stall anything on the phone.
//  2. Never block. Audio publishes run on the mic thread and the Live socket's reader thread; both
//     must stay free. OkHttp's send() only enqueues, and we drop frames when the queue backs up
//     rather than letting it grow — a late audio frame is worthless anyway.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class PresenterSocket(
    /** Laptop base, e.g. `ws://192.168.1.50:8899` (no trailing slash, no path). */
    private val baseUrl: String,
    /** Shared key the server checks on upgrade; blank when the server runs without one. */
    private val key: String,
    private val scope: CoroutineScope,
    private val machineLabel: String?,
    /** Status lines for the phone's own log view ("presenter: connected"). Never called per-frame. */
    private val onLog: (String) -> Unit,
) {
  private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  @Volatile private var ws: WebSocket? = null
  @Volatile private var active = false
  @Volatile private var connected = false
  @Volatile private var backoffMs = BASE_BACKOFF_MS
  @Volatile private var generation = 0
  /** So "disconnected — retrying" is logged once per outage, not once per attempt. */
  @Volatile private var loggedDown = false

  private val handler = Handler(Looper.getMainLooper())
  private val reconnectRunnable = Runnable { if (active && !connected) open() }

  private val micSeq = AtomicInteger(0)
  private val saiSeq = AtomicInteger(0)
  private val photoSeq = AtomicInteger(0)
  private val screenSeq = AtomicInteger(0)

  fun connect() {
    active = true
    backoffMs = BASE_BACKOFF_MS
    open()
  }

  fun close() {
    active = false
    connected = false
    generation++ // orphan the current socket's callbacks
    handler.removeCallbacks(reconnectRunnable)
    runCatching { ws?.close(1000, null) }
    ws = null
  }

  private fun open() {
    handler.removeCallbacks(reconnectRunnable)
    val gen = ++generation
    runCatching { ws?.cancel() }
    val url = "$baseUrl/publish" + if (key.isNotBlank()) "?k=$key" else ""
    ws = runCatching { client.newWebSocket(Request.Builder().url(url).build(), Listener(gen)) }
        .getOrElse {
          Log.w(TAG, "presenter open failed", it)
          reconnect()
          null
        }
  }

  private fun reconnect() {
    if (!active) return
    handler.postDelayed(reconnectRunnable, backoffMs)
    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
  }

  // ── Publishing ────────────────────────────────────────────────────────────────────────────────────

  /** Mic PCM16 @16 kHz. Dropped silently when the feed is down or the socket is backing up. */
  fun publishMic(pcm: ByteArray) = sendAudio(TAG_YOU, micSeq, pcm)

  /** Model PCM16 @24 kHz — what the glasses speaker is playing, so the room can hear it too. */
  fun publishSai(pcm: ByteArray) = sendAudio(TAG_SAI, saiSeq, pcm)

  /**
   * A glasses photo (JPEG) so the dashboard shows what Sai was asked to look at. Photos get a far
   * larger queue budget than audio: they are rare, and dropping one loses the whole point, whereas a
   * dropped 100 ms audio frame is inaudible.
   */
  fun publishPhoto(jpeg: ByteArray) {
    val sock = ws ?: return
    if (!connected) return
    runCatching {
      if (sock.queueSize() > PHOTO_QUEUE_MAX) return
      sock.send(framed(TAG_PHOTO, photoSeq.getAndIncrement(), jpeg))
    }
  }

  /**
   * A phone-screen frame (JPEG) so the room sees the app UI itself — the machine picker, the mute
   * button, a permission dialog, a crash — not just the call's contents.
   *
   * Budgeted like audio, not like a photo: a photo is only dropped at 8 MB because losing one loses
   * the point, whereas a screen frame is worthless the instant the next one exists. Dropping keeps
   * the dashboard CURRENT instead of letting it fall further behind the phone the longer wifi is bad.
   */
  fun publishScreen(jpeg: ByteArray) {
    val sock = ws ?: return
    if (!connected) return
    runCatching {
      if (sock.queueSize() > SCREEN_QUEUE_MAX) return
      sock.send(framed(TAG_SCREEN, screenSeq.getAndIncrement(), jpeg))
    }
  }

  private fun sendAudio(tag: Int, seq: AtomicInteger, pcm: ByteArray) {
    val sock = ws ?: return
    if (!connected) return
    runCatching {
      // Backpressure: on congested wifi TCP buffers rather than dropping, which would make the
      // dashboard drift further behind the wearer the longer it lasts. Drop instead of accumulating.
      if (sock.queueSize() > AUDIO_QUEUE_MAX) return
      sock.send(framed(tag, seq.getAndIncrement(), pcm))
    }
  }

  /** `[tag u8][seq u32 LE][payload]` — the sequence lets the dashboard spot gaps and resync to live. */
  private fun framed(tag: Int, seq: Int, payload: ByteArray): ByteString {
    val out = ByteArray(5 + payload.size)
    out[0] = tag.toByte()
    out[1] = (seq and 0xFF).toByte()
    out[2] = ((seq shr 8) and 0xFF).toByte()
    out[3] = ((seq shr 16) and 0xFF).toByte()
    out[4] = ((seq shr 24) and 0xFF).toByte()
    payload.copyInto(out, 5)
    return out.toByteString()
  }

  /** A conversation turn, keyed by the SAME id the phone's own log view uses (upsert on the dashboard). */
  fun turn(id: Long, role: String, text: String) =
      sendJson(JSONObject().put("t", "turn").put("id", id).put("role", role).put("text", text))

  fun log(id: Long, text: String) =
      sendJson(JSONObject().put("t", "log").put("id", id).put("text", text))

  /**
   * Header state. [muted] and [paused] are separate booleans rather than prose in [status] because the
   * room reads them from across the room, off a projector — and because mute is the control the
   * operator most needs to see at a glance. Pause outranks mute on the dashboard, as it does on the
   * phone's own chip.
   */
  fun state(
      active: Boolean,
      status: String,
      route: String,
      machine: String?,
      muted: Boolean,
      paused: Boolean,
  ) =
      sendJson(
          JSONObject()
              .put("t", "state")
              .put("active", active)
              .put("status", status)
              .put("route", route)
              .put("machine", machine ?: JSONObject.NULL)
              .put("muted", muted)
              .put("paused", paused),
      )

  /**
   * The user barged in — tell the dashboard to drop what it has queued, the way the phone flushes its
   * own playback. Sent even though audio publishing is fire-and-forget: without it the room hears the
   * tail of a sentence Sai was already cut off in, followed by its next answer, which sounds like
   * Sai repeating itself.
   */
  fun interrupted() = sendJson(JSONObject().put("t", "interrupted"))

  private fun sendJson(o: JSONObject) {
    val sock = ws ?: return
    if (!connected) return
    runCatching { sock.send(o.toString()) }
  }

  private inner class Listener(private val gen: Int) : WebSocketListener() {
    private val stale: Boolean
      get() = gen != generation

    override fun onOpen(webSocket: WebSocket, response: Response) {
      if (stale) {
        runCatching { webSocket.cancel() }
        return
      }
      connected = true
      backoffMs = BASE_BACKOFF_MS
      loggedDown = false
      // Declares the sample rates and resets the dashboard for this call.
      runCatching {
        webSocket.send(
            JSONObject()
                .put("t", "hello")
                .put("app", "sai-fi")
                .put("machine", machineLabel ?: JSONObject.NULL)
                .put("sampleRates", JSONObject().put("mic", MIC_RATE).put("sai", SAI_RATE))
                .toString(),
        )
      }
      onLog("presenter: connected")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (stale) return
      connected = false
      val code = response?.code
      if (!loggedDown) {
        loggedDown = true
        // 401 is the one worth calling out: it means the key doesn't match, and retrying won't help
        // until it's fixed — but we keep retrying anyway so fixing the server is enough.
        onLog(
            if (code == 401) "presenter: rejected (401 — presenter_key mismatch), retrying"
            else "presenter: disconnected — retrying",
        )
      }
      reconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (stale) return
      connected = false
      if (!loggedDown) {
        loggedDown = true
        onLog("presenter: disconnected — retrying")
      }
      reconnect()
    }
  }

  companion object {
    private const val TAG = "SaiFi:Presenter"
    private const val TAG_YOU = 1
    private const val TAG_SAI = 2
    private const val TAG_PHOTO = 3
    private const val TAG_SCREEN = 4
    const val MIC_RATE = 16_000
    const val SAI_RATE = 24_000
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 15_000L
    // ~1 MB of unsent audio is already ~12 s behind; past that, dropping is strictly better.
    private const val AUDIO_QUEUE_MAX = 1_000_000L
    private const val PHOTO_QUEUE_MAX = 8_000_000L
    // Between the two: a few screen frames may buffer through a blip, but a backlog is never worth
    // sending — by the time it drains every frame in it is stale.
    private const val SCREEN_QUEUE_MAX = 2_000_000L

    /**
     * The feed's URL. An explicit `presenter_url` wins; otherwise derive it from the cloud-api host,
     * since the demo laptop runs both — pointing the app at the laptop then lights up the dashboard
     * with nothing else to configure. Blank means the feature is off.
     */
    fun resolveUrl(presenterUrl: String, conciergeUrl: String, port: Int = 8899): String {
      if (presenterUrl.isNotBlank()) return presenterUrl.trimEnd('/')
      val host =
          runCatching { java.net.URI(conciergeUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
              ?: return ""
      // Only auto-derive for a LAN/dev host: deriving against staging would point at a server that
      // isn't there and retry forever for no reason.
      val local =
          host == "localhost" ||
              host == "127.0.0.1" ||
              host == "10.0.2.2" ||
              host.startsWith("192.168.") ||
              host.startsWith("10.") ||
              host.startsWith("172.")
      return if (local) "ws://$host:$port" else ""
    }
  }
}
