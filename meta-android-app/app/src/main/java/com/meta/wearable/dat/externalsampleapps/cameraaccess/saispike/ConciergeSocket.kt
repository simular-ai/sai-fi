/*
 * sai-fi — voice concierge.
 */

// ConciergeSocket — the WebSocket to cloud-api's /v1/concierge/ws. Sends the Live model's effects up;
// receives agent-events / speak / approval-timeout / agent-activity down. Auth is a fresh Firebase ID
// token (from [tokenProvider]) as a Bearer header on the upgrade (OkHttp supports WS headers), plus
// ?machineId=. The token is re-fetched on EVERY (re)connect — Firebase ID tokens expire ~1h and a
// long call's WS can outlive the token it started with, so reusing a start-time token would fail the
// upgrade with a 401 that looks permanent. Auto-reconnects on a transient drop; a permanent upgrade
// rejection (401 bad token / 403 machine not owned / 503 voice disabled) stops retrying and fires
// [onPermanentFailure] so the call can end with a clear reason.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class ConciergeSocket(
    /** cloud-api base, e.g. http://localhost:8080 or https://…run.app (no trailing slash). */
    private val baseUrl: String,
    /** Fetches a FRESH Bearer token (Firebase ID token) — called before every upgrade attempt. */
    private val tokenProvider: suspend () -> String?,
    /** Scope the token fetch + socket open run on (the owning CallService's scope). */
    private val scope: CoroutineScope,
    private val machineId: String,
    private val onAgentEvent: (JSONObject) -> Unit,
    private val onAgentActivity: (JSONObject) -> Unit,
    private val onSpeak: (String) -> Unit,
    /**
     * Context for the model, injected as-is — NOT spoken. The counterpart to [onSpeak], which the
     * client wraps in "say this verbatim": a correction like "that option wasn't on the list, present
     * them again" sent down that path is read out to the user, function names and all.
     */
    private val onInstruct: (String) -> Unit,
    private val onApprovalTimeout: () -> Unit,
    /** A permanent upgrade rejection (401/403/503) — stop retrying; the caller ends the call. */
    private val onPermanentFailure: (code: Int) -> Unit,
    /** The server ended the call with a terminal close code (cost guard) — tear down, don't reconnect. */
    private val onEndByServer: (code: Int, reason: String) -> Unit,
    /**
     * Connection state, so the UI can say so. Reported here rather than left for the caller to
     * infer from [onLog] text — a dropped socket is a fact this class knows and everything else
     * would be string-matching log lines.
     */
    private val onConnectionChange: (connected: Boolean) -> Unit = {},
    private val onLog: (String) -> Unit,
) {
  private val client =
      OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null
  @Volatile private var active = false
  @Volatile private var connected = false
  // Exponential backoff for reconnects (reset to base on a clean open); capped so we keep retrying.
  @Volatile private var backoffMs = BASE_BACKOFF_MS
  // A single reusable reconnect timer — scheduled/cancelled as one, so we never open two sockets.
  private val handler = Handler(Looper.getMainLooper())
  private val reconnectRunnable = Runnable { if (active && !connected) open() }
  // The in-flight open (token fetch + newWebSocket); superseded so two opens never race to a socket.
  @Volatile private var openJob: Job? = null
  // Bumped on every open(); a socket's Listener carries the value it was created with, so callbacks
  // from a superseded connection can be told apart from the live one.
  @Volatile private var generation = 0

  fun connect() {
    active = true
    backoffMs = BASE_BACKOFF_MS
    open()
  }

  private fun open() {
    handler.removeCallbacks(reconnectRunnable) // cancel any pending retry — this open supersedes it
    openJob?.cancel() // supersede an in-flight open (its token fetch) so two opens never race
    // Stamp this attempt. Everything below belongs to THIS generation; a socket from an older one is
    // dead to us even if OkHttp is still delivering its callbacks (see Listener).
    val gen = ++generation
    runCatching { ws?.cancel() } // drop a half-open socket before replacing it (no leak/dup)
    // Mint a fresh Bearer before each upgrade (token fetch is suspend), then open on the main looper.
    openJob = scope.launch {
      val bearer = tokenProvider()
      if (!active || !isActive) return@launch // torn down / superseded while fetching the token
      if (bearer.isNullOrBlank()) {
        // Signed out / token unavailable — treat as transient and back off (a re-login re-arms it).
        onLog("concierge: no auth token — retrying")
        reconnect()
        return@launch
      }
      // http→ws, https→wss.
      val wsBase = baseUrl.replaceFirst("http", "ws")
      val url = "$wsBase/v1/concierge/ws?machineId=$machineId"
      val reqBuilder = Request.Builder().url(url).addHeader("Authorization", "Bearer $bearer")
      // Route to a specific PR's staging revision via the shared staging gateway.
      if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
        reqBuilder.addHeader("x-sai-version", BuildConfig.SAI_VERSION_TAG)
      }
      if (gen != generation) return@launch // superseded while fetching the token
      ws = client.newWebSocket(reqBuilder.build(), Listener(gen))
    }
  }

  /** Forward the Live model's function-calls (effects) to the concierge core. */
  fun sendEffects(effects: JSONArray) {
    ws?.send(JSONObject().put("type", "effects").put("effects", effects).toString())
  }

  /**
   * Tell the server a human is still present, so the idle cost guard doesn't hang up.
   *
   * The guard treats model OUTPUT tokens as proof of life precisely so a walked-away open mic still
   * times out — but a MUTED Sai emits none, making a real conversation look identical to an abandoned
   * one. The caller sends this only while muted, only when the mic actually heard speech, and at most
   * once a minute (CallService.maybeKeepalive), so an abandoned muted call still expires.
   */
  fun sendKeepalive() {
    runCatching { ws?.send(JSONObject().put("type", "keepalive").toString()) }
  }

  /** Send a captured photo's uploaded reference for the server to stash + attach to the next forward. */
  fun sendAttachment(attachment: JSONObject) {
    ws?.send(JSONObject().put("type", "attachment").put("attachment", attachment).toString())
  }

  /**
   * Send where the user is, for the server to fold into the request arriving right behind it.
   *
   * Same one-socket ordering contract as [sendAttachment]: this must be sent BEFORE the effects it
   * belongs to, because the server's stash is drained by the next write.
   */
  fun sendLocation(location: JSONObject) {
    ws?.send(JSONObject().put("type", "location").put("location", location).toString())
  }

  /** Report cumulative Live token usage so the server can bill the delta. */
  fun sendUsage(promptTokens: Int, responseTokens: Int, totalTokens: Int) {
    ws?.send(
        JSONObject()
            .put("type", "usage")
            .put(
                "usage",
                JSONObject()
                    .put("promptTokens", promptTokens)
                    .put("responseTokens", responseTokens)
                    .put("totalTokens", totalTokens),
            )
            .toString(),
    )
  }

  fun close() {
    active = false
    connected = false
    openJob?.cancel()
    handler.removeCallbacks(reconnectRunnable)
    ws?.close(1000, null)
    ws = null
  }

  /**
   * A network change came back — reconnect NOW instead of waiting out the backoff, but ONLY if we're
   * actually down. A no-op on a healthy socket (so a spurious network event never drops a live call).
   */
  fun kick() {
    if (active && !connected) {
      backoffMs = BASE_BACKOFF_MS
      open()
    }
  }

  /**
   * Callbacks from a SUPERSEDED socket must be ignored. `open()` cancels the previous socket, and
   * OkHttp reports that cancellation as an onFailure on the dead socket — which used to clear
   * `connected` and call reconnect(), opening yet another socket on top of the healthy one (the
   * duplicate "concierge: connected" in the logs). A stale onMessage was worse: it would re-deliver
   * agent events, double-nudging the model. Identity is by generation, not by comparing to `ws`,
   * because onOpen can fire before the `ws = newWebSocket(...)` assignment lands.
   */
  private inner class Listener(private val gen: Int) : WebSocketListener() {
    private val stale: Boolean
      get() = gen != generation

    override fun onOpen(webSocket: WebSocket, response: Response) {
      if (stale) {
        runCatching { webSocket.cancel() } // a superseded connection that made it through — drop it
        return
      }
      connected = true
      onConnectionChange(true)
      backoffMs = BASE_BACKOFF_MS // clean connection — reset backoff
      onLog("concierge: connected")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      if (!stale) handle(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      if (!stale) handle(bytes.utf8())
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (stale) return // our own cancel() of a replaced socket — not a live-call failure
      connected = false
      onConnectionChange(false)
      val code = response?.code
      Log.e(TAG, "concierge socket failure ($code)", t)
      onLog("concierge: failed ${code ?: ""} ${t.message}")
      // A permanent upgrade rejection (bad token / machine not owned / voice disabled) won't fix itself
      // — stop retrying and let the caller end the call with a spoken reason.
      if (code != null && code in PERMANENT_CODES) {
        active = false
        handler.removeCallbacks(reconnectRunnable)
        onPermanentFailure(code)
      } else {
        reconnect()
      }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      if (stale) {
        runCatching { webSocket.close(1000, null) } // finish the handshake, but change no state
        return
      }
      // The server may END the call with a terminal close code (cost guard: max duration / idle).
      // Treat it like a permanent failure — stop reconnecting and let the caller tear the call down.
      connected = false
      onConnectionChange(false)
      if (code in TERMINAL_CLOSE_CODES) {
        active = false
        openJob?.cancel()
        handler.removeCallbacks(reconnectRunnable)
        onLog("concierge: server ended call ($code $reason)")
        onEndByServer(code, reason)
      }
      runCatching { webSocket.close(1000, null) } // complete the close handshake
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (stale) return // a replaced socket finishing its close — the live one is unaffected
      connected = false
      onConnectionChange(false)
      onLog("concierge: closed $code $reason")
      reconnect() // no-op once active is cleared (terminal close / close()) — else a transient drop
    }
  }

  private fun handle(raw: String) {
    dispatchServerMessage(
        raw,
        onAgentEvent = onAgentEvent,
        onAgentActivity = onAgentActivity,
        onSpeak = onSpeak,
        onInstruct = onInstruct,
        onApprovalTimeout = onApprovalTimeout,
    )
  }

  private fun reconnect() {
    if (!active) return
    val delay = backoffMs
    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    onLog("concierge: reconnecting in ${delay}ms")
    handler.removeCallbacks(reconnectRunnable) // at most one pending retry
    handler.postDelayed(reconnectRunnable, delay)
  }

  companion object {
    private const val TAG = "SaiFi:Concierge"
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 15_000L
    /** WS-upgrade rejections that won't fix themselves: bad token / machine not owned / voice disabled. */
    private val PERMANENT_CODES = setOf(401, 403, 503)
    // Server cost-guard close codes — the call is over, don't reconnect. Mirrors CONCIERGE_CLOSE
    // in cloud-api's transport/protocol.ts (4001 = max duration, 4002 = idle timeout).
    const val CLOSE_MAX_DURATION = 4001
    const val CLOSE_IDLE = 4002
    private val TERMINAL_CLOSE_CODES = setOf(CLOSE_MAX_DURATION, CLOSE_IDLE)
  }
}
