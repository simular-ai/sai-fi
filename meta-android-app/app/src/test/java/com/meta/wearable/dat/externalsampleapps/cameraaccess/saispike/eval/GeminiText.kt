/* sai-fi — voice concierge. */

// The model in TEXT mode, for the eval.
//
// This APPROXIMATES the live audio path: a Live audio session cannot be driven headlessly, so the
// eval tests the prompt-driven behaviour in text mode with the same prompt and the same tools the
// device ships. What it cannot see is anything about speaking *while* calling — text mode answers a
// tool-triggering turn with a functionCall part and no text part — which is why `voice-before-capture`
// is in the rubric but marked `text-mode` and graded on the device instead.
//
// Raw REST rather than a client library: the app has OkHttp already, and adding a Google GenAI
// dependency to a test source set to send three JSON shapes is not worth the dependency.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A quota refusal that will not clear today — retrying is pointless and costs the next run's budget. */
class DailyQuotaExhausted(model: String) :
    RuntimeException(
        "$model is out of free-tier quota for today. Switch models (EVAL_MODEL / JUDGE_MODEL) " +
            "or use a billed key.")

/**
 * One thing the model produced: some words, or a call.
 *
 * [raw] is the part exactly as it arrived, and it is what gets echoed back into the history. Newer
 * models attach a `thoughtSignature` NEXT TO the `functionCall` (a sibling field, not a member of
 * it), and the API rejects the next request with a 400 if it is missing — "Function call is missing
 * a thought_signature in functionCall parts". Rebuilding the part from its pieces silently drops it,
 * so the part is carried whole rather than reconstructed.
 */
data class ModelPart(
    val text: String? = null,
    val call: JSONObject? = null,
    val raw: JSONObject = JSONObject(),
)

class GeminiText(
    private val apiKey: String,
    private val model: String,
    private val log: (String) -> Unit = {},
) {
  private val http =
      OkHttpClient.Builder()
          .callTimeout(120, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .build()

  /** The running conversation, in the shape the REST API takes. */
  private val contents = JSONArray()

  var systemPrompt: String = ""
  var tools: JSONArray = JSONArray()

  fun addUserText(text: String) {
    contents.put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
  }

  /** Record what the model said, verbatim, so the next turn sees its own previous move intact. */
  fun addModelParts(parts: List<ModelPart>) {
    val arr = JSONArray()
    parts.forEach { arr.put(it.raw) }
    if (arr.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", arr))
  }

  /** Answer the model's calls so it can continue the turn. */
  fun addToolResponses(responses: List<Pair<String, JSONObject>>) {
    val arr = JSONArray()
    responses.forEach { (name, response) ->
      arr.put(
          JSONObject()
              .put("functionResponse", JSONObject().put("name", name).put("response", response)))
    }
    if (arr.length() > 0) contents.put(JSONObject().put("role", "user").put("parts", arr))
  }

  /** Generate the next turn from the conversation so far. */
  fun generate(): List<ModelPart> {
    val body =
        JSONObject()
            .put("contents", contents)
            .apply {
              if (systemPrompt.isNotEmpty()) {
                put(
                    "systemInstruction",
                    JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
              }
              if (tools.length() > 0) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
              }
            }
    val json = post(body)
    val parts =
        json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()
    return (0 until parts.length()).mapNotNull { i ->
      val p = parts.getJSONObject(i)
      when {
        p.has("functionCall") -> ModelPart(call = p.getJSONObject("functionCall"), raw = p)
        p.has("text") -> ModelPart(text = p.getString("text"), raw = p)
        else -> null
      }
    }
  }

  /**
   * POST with the retry policy the TS runner used.
   *
   * A 429 carrying a `PerMinute` quota is pacing: wait out the server's own `retryDelay` and go
   * again, which is why a full run takes minutes. A `PerDay` one is terminal for that model, and
   * retrying it only burns the budget the next run needs — so it fails fast, saying which knob to
   * turn. Neither is a behaviour regression, and reading them as one sends people hunting a bug in
   * the prompt.
   */
  private fun post(body: JSONObject): JSONObject {
    var attempt = 0
    while (true) {
      val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
      val req =
          Request.Builder()
              .url(url)
              .post(body.toString().toRequestBody("application/json".toMediaType()))
              .build()
      val (code, text) = http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
      if (code in 200..299) return JSONObject(text)

      if (code == 429) {
        if (text.contains("PerDay") || text.contains("per day", ignoreCase = true)) {
          throw DailyQuotaExhausted(model)
        }
        if (++attempt > MAX_ATTEMPTS) throw RuntimeException("429 after $MAX_ATTEMPTS attempts: ${text.take(200)}")
        val wait = retryDelayMs(text) ?: (attempt * 20_000L)
        log("  … rate limited, waiting ${wait / 1000}s (attempt $attempt/$MAX_ATTEMPTS)")
        Thread.sleep(wait)
        continue
      }
      // 404 usually means the id is retired for this key rather than anything being wrong here.
      throw RuntimeException("HTTP $code from $model: ${text.take(300)}")
    }
  }

  /** The server tells us how long to wait; honour it rather than guessing. */
  private fun retryDelayMs(body: String): Long? =
      Regex("""["']?retryDelay["']?\s*:\s*["'](\d+(?:\.\d+)?)s["']""")
          .find(body)
          ?.groupValues
          ?.get(1)
          ?.toDoubleOrNull()
          ?.let { (it * 1000).toLong() + 1_000 }

  private companion object {
    const val MAX_ATTEMPTS = 5
  }
}
