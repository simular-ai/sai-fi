/*
 * sai-fi — voice concierge.
 */

// ConciergeClient — the read-only half of the agent API: machines, history, and image upload.
//
// Named for an endpoint that no longer exists. What it holds now is everything a call needs from
// the Sai API that is NOT a task: `GET /v1/agents/machines` for the picker, `GET /v1/agents/context`
// behind `recallHistory`, and `POST /v1/agents/upload` for a glasses capture. Sending work is
// VoiceChannelClient's job.
//
// Auth = a fresh Firebase ID token (from SaiAuth) sent as a Bearer header — the Sai API verifies
// it per-user, exactly as for the web/desktop app (never put the token in a URL; no compiled-in
// `sapi_` key). SAI_API_URL defaults to production; no `adb reverse` needed. HttpURLConnection
// keeps this client dependency-free.
//
// Non-2xx responses throw [ConciergeHttpException] carrying the status so callers can distinguish
// permanent failures — 402 (out of credits), 503 (voice disabled / not configured), 401 (bad token),
// 403 (machine not owned) — from transient ones and stop retrying.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A non-2xx from a concierge / agents endpoint, carrying the HTTP [status] so callers can react to
 * permanent failures (402 out-of-credits, 503 voice-disabled, 401 bad-token, 403 machine-not-owned)
 * instead of retrying forever.
 */
class ConciergeHttpException(val status: Int, message: String) : IOException(message)

/**
 * What one Live session is configured with.
 *
 * This used to be the parsed `POST /v1/concierge/session` response. That endpoint is gone — the
 * device brings its own API key and ships its own profile — so this is now built locally from
 * [VoiceProfile] plus the session's machine context.
 */
data class SessionBootstrap(
    /** e.g. gemini-3.1-flash-live-preview. Ships with the app in `assets/voice-profile.json`. */
    val model: String,
    val systemPrompt: String,
    /** Raw JSON array of function declarations, forwarded to the Live session as-is. */
    val toolsJson: String,
    val toolCount: Int,
    val voice: String,
)

/** A Sai machine (VM) the user can target, from GET /v1/agents/machines. */
data class Machine(
    val machineId: String,
    val name: String?,
    /**
     * VM state as the server stores it: `active` · `hibernated` · `hibernating` · `wakingup`, or null
     * when the machine has never reported one — read that as offline.
     *
     * Only [isActive] is read at present, by the wake watcher. Deliberately kept as the server's own
     * string rather than an enum: a second spelling of these states is how the two drift apart, and
     * an unknown value must degrade to "not active" rather than fail to parse.
     */
    val status: String? = null,
    /**
     * Whether a hibernated machine can be woken remotely at all — a property of where it is hosted,
     * not of what it is doing now.
     *
     * The reason this is not inferable from [status]: a `hibernated` machine with `canWake = false` is
     * asleep and staying that way, so announcing a wake for it promises a minute that never ends.
     */
    val canWake: Boolean = false,
) {
  /** Display label for the picker. */
  val label: String
    get() = name?.takeIf { it.isNotBlank() } ?: machineId

  /** Up and usable. Anything else — including an unrecognised state — is not. */
  val isActive: Boolean
    get() = status == "active"
}

/**
 * What `POST /v1/agents/wake` answered.
 *
 * Four fields because the caller has four different sentences to choose between, and only one of them
 * is true at a time. See [ConciergeClient.wakeMachine].
 */
data class WakeOutcome(
    /**
     * THIS call dispatched a wake. False for a machine that was already awake, already on its way, or
     * cannot be woken — and false is not the same as "nothing to say" in the second case.
     */
    val waking: Boolean,
    /**
     * The machine is not usable yet but is coming up, whether or not we are the reason.
     *
     * **Branch on this, not [waking].** A machine already mid-wake answers `waking = false` — correctly,
     * since nothing was dispatched — and the user is still owed the "about a minute" line. It is also
     * false for a hibernated machine that cannot be woken, which is exactly when nothing should be said.
     */
    val startingUp: Boolean,
    /** As stored, read server-side BEFORE the dispatch. Null when the field is absent. */
    val status: String?,
    val canWake: Boolean,
)

object ConciergeClient {
  /**
   * List the user's Sai machines (like `sai machine` in the CLI) so the app can offer a picker.
   * GET /v1/agents/machines with the Firebase ID token as Bearer. Throws on a non-2xx response.
   */
  suspend fun listMachines(baseUrl: String, bearerToken: String): List<Machine> =
      withContext(Dispatchers.IO) {
        val conn =
            (URL("$baseUrl/v1/agents/machines").openConnection() as HttpURLConnection).apply {
              requestMethod = "GET"
              connectTimeout = 10_000
              readTimeout = 15_000
              applyCloudApiHeaders(bearerToken)
            }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
          throw ConciergeHttpException(code, "GET /v1/agents/machines failed: HTTP $code — ${body.take(300)}")
        }
        val arr = JSONObject(body).optJSONArray("machines") ?: return@withContext emptyList()
        (0 until arr.length()).map {
          val m = arr.getJSONObject(it)
          Machine(
              machineId = m.getString("machineId"),
              name = m.optString("name").ifEmpty { null },
              // Absent on a server older than 2026-08-20, which reads as "offline / cannot wake" —
              // and the wake path degrades to doing nothing rather than to guessing.
              status = m.optString("status").ifEmpty { null },
              canWake = m.optBoolean("canWake", false),
          )
        }
      }

  /**
   * Fetch the machine's recent conversation history (`GET /v1/agents/context`) for the model's
   * `recallHistory` tool — recall questions ("did you finish…?") get answered from this instead of
   * waking the agent. Returns a compact transcript string, oldest first, sized for a tool response.
   * Throws [ConciergeHttpException] on a non-2xx response.
   */
  suspend fun fetchContext(
      baseUrl: String,
      bearerToken: String,
      machineId: String,
      limit: Int = 30,
  ): String =
      withContext(Dispatchers.IO) {
        val conn =
            // `channel` for the same reason the rotation names it: the route defaults to `cli`, so
            // recall without it answers from the TERMINAL's transcript — a conversation this client
            // has never taken part in. Same constant as the POST bodies; see
            // [VoiceChannelClient.API_CHANNEL].
            (URL(
                        "$baseUrl/v1/agents/context?machineId=$machineId&limit=$limit" +
                            "&channel=${VoiceChannelClient.API_CHANNEL}")
                    .openConnection()
                    as HttpURLConnection)
                .apply {
                  requestMethod = "GET"
                  connectTimeout = 10_000
                  readTimeout = 15_000
                  applyCloudApiHeaders(bearerToken)
                }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
          throw ConciergeHttpException(code, "GET /v1/agents/context failed: HTTP $code — ${body.take(300)}")
        }
        val arr = JSONObject(body).optJSONArray("messages") ?: JSONArray()
        if (arr.length() == 0) return@withContext "No recent history on this machine."
        val lines = StringBuilder()
        for (i in 0 until arr.length()) {
          val m = arr.getJSONObject(i)
          val role = if (m.optString("role") == "user") "user" else "sai"
          val content = m.optString("content").replace(Regex("\\s+"), " ").trim()
          if (content.isEmpty()) continue
          lines.append(role).append(": ").append(content.take(400)).append('\n')
        }
        // Keep the tool response bounded — the tail (most recent) matters most.
        lines.toString().takeLast(6_000).ifEmpty { "No recent history on this machine." }
      }

  /**
   * Upload raw image bytes to Firebase Storage via `POST /v1/agents/upload` (same authMiddleware +
   * Bearer Firebase ID token as /session). MIME is derived server-side from [filename]'s extension. Returns the
   * server's attachment JSON `{path,name,mime,size,downloadUrl}` — hand it to the concierge WS as an
   * `attachment`. Throws [ConciergeHttpException] on a non-2xx response.
   */
  suspend fun uploadAttachment(
      baseUrl: String,
      bearerToken: String,
      bytes: ByteArray,
      filename: String,
  ): JSONObject =
      withContext(Dispatchers.IO) {
        val conn =
            (URL("$baseUrl/v1/agents/upload").openConnection() as HttpURLConnection).apply {
              requestMethod = "POST"
              doOutput = true
              connectTimeout = 10_000
              readTimeout = 30_000
              applyCloudApiHeaders(bearerToken)
              setRequestProperty("Content-Type", "application/octet-stream")
              setRequestProperty("x-filename", filename)
            }
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
          throw ConciergeHttpException(code, "POST /v1/agents/upload failed: HTTP $code — ${body.take(300)}")
        }
        JSONObject(body)
      }

  /**
   * Wake a hibernated machine, without sending it any work — `POST /v1/agents/wake`.
   *
   * The point of it having no payload: every other wake in the system rides a delivery, and a message
   * arriving during a running turn is folded INTO that turn. So waking by sending a throwaway "hello"
   * means the dummy and the user's next real request share one turn, and the dummy's completion ends
   * it out from under the real work. There is nothing to deliver at call bind anyway.
   *
   * Safe to call redundantly — the server no-ops on a machine that is already awake. Throws
   * [ConciergeHttpException] on a non-2xx; a 404 means the machine is not this account's.
   */
  suspend fun wakeMachine(
      baseUrl: String,
      bearerToken: String,
      machineId: String,
  ): WakeOutcome =
      withContext(Dispatchers.IO) {
        val conn =
            (URL("$baseUrl/v1/agents/wake").openConnection() as HttpURLConnection).apply {
              requestMethod = "POST"
              doOutput = true
              connectTimeout = 10_000
              readTimeout = 15_000
              applyCloudApiHeaders(bearerToken)
              setRequestProperty("Content-Type", "application/json")
            }
        conn.outputStream.use {
          it.write(JSONObject().put("machineId", machineId).toString().toByteArray())
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
          throw ConciergeHttpException(code, "POST /v1/agents/wake failed: HTTP $code — ${body.take(300)}")
        }
        val o = JSONObject(body)
        WakeOutcome(
            waking = o.optBoolean("waking", false),
            // Absent on an older server: nothing is starting up as far as this client can tell, so the
            // wake path stays silent rather than announcing a minute it cannot vouch for.
            startingUp = o.optBoolean("startingUp", false),
            status = o.optString("status").ifEmpty { null },
            canWake = o.optBoolean("canWake", false),
        )
      }
}
