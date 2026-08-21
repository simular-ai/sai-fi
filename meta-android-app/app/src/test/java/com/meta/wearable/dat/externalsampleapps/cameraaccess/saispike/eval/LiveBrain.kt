/* sai-fi — voice concierge. */

// The real model, behind the same seam the scripted brain uses.
//
// Swapping it into ConversationHarness turns the deterministic loop tests into a judged one: the
// same real FSM, the same real bridge and gate, the same scripted agent — but the decisions come
// from the model, running the prompt and tools the app actually ships.
//
// This is what `TranscriptEvalTest` cannot do. There, a `forwardToAgent` resolves to a canned `ok`
// and the queue does not exist, so "is a waiting task described as waiting" is graded against a
// `session-state` the transcript author wrote by hand. Here the task really is waiting, because
// something really is running, and the status the model reads comes from the real ActivityLog.
//
// It answers its own tool calls (the model cannot continue a turn with one outstanding) and ALSO
// hands the effect-bearing ones back to the harness, which routes them through the gate into the
// FSM — the same split GeminiLiveClient makes between locally-answered tools and forwarded effects.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.eval

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.describePhoneClock
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.Brain
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation.BrainTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.shippedProfile
import org.json.JSONArray
import org.json.JSONObject

class LiveBrain(
    apiKey: String,
    model: String,
    /** Answer a locally-handled tool — above all `getSaiStatus`, which reads the real activity log. */
    private val resolveLocalTool: (name: String, args: JSONObject) -> JSONObject,
    private val log: (String) -> Unit = {},
) : Brain {

  private val chat = GeminiText(apiKey, model, log)

  /** Everything the model was given, in order — useful when a run goes strangely. */
  val seen = mutableListOf<String>()

  init {
    val profile = loadProfile()
    chat.systemPrompt = profile.first
    chat.tools = profile.second
  }

  override suspend fun turn(input: String, state: ConciergeState): BrainTurn {
    seen += input
    chat.addUserText(input)

    val speech = StringBuilder()
    val effects = JSONArray()
    var parts = chat.generate()
    var round = 0
    // Exactly one append and one history entry per `generate`. Doing either twice for the final
    // batch is not a cosmetic slip: the doubled text goes to the JUDGE, which reads "On it. On it."
    // as the concierge saying it twice and marks a violation the concierge never committed.
    while (true) {
      parts.mapNotNull { it.text }.forEach { if (it.isNotBlank()) speech.append(it.trim()).append(' ') }
      chat.addModelParts(parts)
      val calls = parts.mapNotNull { it.call }
      if (calls.isEmpty() || ++round > MAX_TOOL_ROUNDS) break

      chat.addToolResponses(
          calls.map { c ->
            val name = c.optString("name")
            val args = c.optJSONObject("args") ?: JSONObject()
            if (name == "getLocalTime") {
              name to JSONObject().put("time", describePhoneClock())
            } else if (name in LOCAL_TOOLS) {
              name to resolveLocalTool(name, args)
            } else {
              // Handed to the harness, which routes it through the gate into the FSM. Answered `ok`
              // here for the same reason GeminiLiveClient answers immediately: the model cannot
              // produce speech while a call in the batch is unanswered, and deferring is what used
              // to buy the user seconds of dead air.
              effects.put(JSONObject().put("name", name).put("args", args))
              name to JSONObject().put("result", "ok")
            }
          })
      parts = chat.generate()
    }

    return BrainTurn(speech = speech.toString().trim().ifEmpty { null }, calls = effects)
  }

  /** The prompt and tools the app ships, from its own asset. */
  private fun loadProfile(): Pair<String, JSONArray> {
    val p = shippedProfile()
    val tools = JSONArray()
    p.tools.forEach { t ->
      tools.put(
          JSONObject()
              .put("name", t.name)
              .put("description", t.description)
              .apply { t.parameters?.let { put("parametersJsonSchema", it) } })
    }
    return p.systemPrompt to tools
  }

  private companion object {
    /** Tools the DEVICE answers itself; no effect ever reaches the FSM for these. */
    val LOCAL_TOOLS = setOf("getSaiStatus", "getLocalTime", "recallHistory", "switchMachine", "endCall")
    const val MAX_TOOL_ROUNDS = 6
  }
}
