/*
 * sai-fi — voice concierge.
 */

// ConciergeClient — the read-only half of the agent API: machines, history, and image upload.
//
// Named for an endpoint that no longer exists. What it holds now is everything a call needs from
// cloud-api that is NOT a task: `GET /v1/agents/machines` for the picker, `GET /v1/agents/context`
// behind `recallHistory`, and `POST /v1/agents/upload` for a glasses capture. Sending work is
// VoiceChannelClient's job.
//
// Auth = a fresh Firebase ID token (from SaiAuth) sent as a Bearer header — cloud-api's
// authMiddleware verifies it per-user, exactly as for the web/desktop app (never put the token in a
// URL; no compiled-in `sapi_` key). Point CONCIERGE_URL at the cloud-api base (staging by default);
// no `adb reverse` needed — it's a real HTTPS endpoint. HttpURLConnection keeps this client
// dependency-free.
//
// Non-2xx responses throw [ConciergeHttpException] carrying the status so callers can distinguish
// permanent failures — 402 (out of credits), 503 (voice disabled / not configured), 401 (bad token),
// 403 (machine not owned) — from transient ones and stop retrying.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
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
data class Machine(val machineId: String, val name: String?) {
  /** Display label for the picker. */
  val label: String
    get() = name?.takeIf { it.isNotBlank() } ?: machineId
}

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
              setRequestProperty("Authorization", "Bearer $bearerToken")
              // Route to a specific PR's staging revision via the shared staging gateway.
              if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
                setRequestProperty("x-sai-version", BuildConfig.SAI_VERSION_TAG)
              }
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
          Machine(m.getString("machineId"), m.optString("name").ifEmpty { null })
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
            // `channel=api` for the same reason the rotation names it: the route defaults to
            // `cli`, so recall without it answers from the TERMINAL's transcript — a conversation
            // this client has never taken part in.
            (URL("$baseUrl/v1/agents/context?machineId=$machineId&limit=$limit&channel=api")
                    .openConnection()
                    as HttpURLConnection)
                .apply {
                  requestMethod = "GET"
                  connectTimeout = 10_000
                  readTimeout = 15_000
                  setRequestProperty("Authorization", "Bearer $bearerToken")
              // Route to a specific PR's staging revision via the shared staging gateway.
              if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
                setRequestProperty("x-sai-version", BuildConfig.SAI_VERSION_TAG)
              }
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
              setRequestProperty("Authorization", "Bearer $bearerToken")
              // Route to a specific PR's staging revision via the shared staging gateway.
              if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
                setRequestProperty("x-sai-version", BuildConfig.SAI_VERSION_TAG)
              }
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
}
