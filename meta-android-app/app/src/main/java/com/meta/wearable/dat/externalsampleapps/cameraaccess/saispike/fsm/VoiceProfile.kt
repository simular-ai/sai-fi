/* sai-fi — voice concierge. */

// What the Live session is configured with: the system prompt, the tools, the voice.
//
// This used to arrive from `POST /v1/concierge/session`. That endpoint is gone — the device brings
// its own Gemini key, so there is no token to mint and nothing left for the server to deliver. The
// profile ships with the app, which is what lets this repo run with no Simular server at all for the
// voice half.
//
// It lives in `assets/voice-profile.json` rather than as Kotlin string constants, for three reasons:
// the text is ~36KB and every paragraph is load-bearing, so it was GENERATED from the server's
// source rather than retyped; Kotlin raw strings would need escaping decisions that could silently
// alter it; and the same bytes are what cloud-api's eval vendors back, so a single artefact keeps
// the two honest.
//
// **The wording is not decoration.** Each paragraph encodes a behaviour found by hearing it fail on
// a real device — a photo described as "sent" when it was only taken, the remote VM's screen
// reported as the user's surroundings, "a few seconds" promised for something of unknown duration.
// Change it deliberately or not at all.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** One function declaration, as the Live session takes it. */
data class ToolDeclaration(val name: String, val description: String, val parameters: JSONObject?)

data class VoiceProfile(
    val name: String,
    val voice: String,
    val model: String,
    /** The ordered blocks the prompt is composed from. */
    val promptBlocks: List<String>,
    /** The composed prompt, before any session context is appended. */
    val systemPrompt: String,
    val tools: List<ToolDeclaration>,
    /**
     * Tools the DEVICE answers itself. The model is told they exist, but no effect ever arrives for
     * one — and the device MUST answer every call, or the model stalls mid-turn waiting.
     */
    val deviceToolNames: List<String>,
    /**
     * The persona blocks shared with the TEXT concierge, which still owns them in cloud-api.
     *
     * Carried so [assertStatesBasePersona] can check they survived composition. One wording is meant
     * to serve both concierges, and since the two prompts now live in different repositories, this
     * is the only place that can still notice a block going missing from the voice side.
     */
    val basePersonaBlocks: List<String>,
) {

  /**
   * The prompt with session facts appended.
   *
   * The names are user-controlled and land inside the persona prompt, so a crafted machine name is a
   * prompt-injection vector — sanitize before calling.
   */
  fun systemPromptWithContext(activeMachine: String? = null, machineNames: List<String> = emptyList()): String {
    val parts = mutableListOf<String>()
    if (!activeMachine.isNullOrEmpty()) {
      parts += "the active Sai machine (VM) for this session is \"$activeMachine\""
    }
    if (machineNames.size > 1) {
      parts += "the machines you can switch between are: ${machineNames.joinToString(", ")}"
    }
    return if (parts.isEmpty()) systemPrompt
    else "$systemPrompt\n\nContext: ${parts.joinToString("; ")}."
  }

  /** Tool declarations as the Live `setup` frame wants them. */
  fun toolsJson(): JSONArray =
      JSONArray().apply {
        tools.forEach { t ->
          put(
              JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                t.parameters?.let { put("parameters", it) }
              })
        }
      }

  companion object {
    const val ASSET = "voice-profile.json"

    fun parse(json: String): VoiceProfile {
      val o = JSONObject(json)
      val blocks = o.optJSONArray("promptBlocks") ?: JSONArray()
      val toolsArr = o.optJSONArray("tools") ?: JSONArray()
      val deviceArr = o.optJSONArray("deviceToolNames") ?: JSONArray()
      val baseArr = o.optJSONArray("basePersonaBlocks") ?: JSONArray()
      return VoiceProfile(
          name = o.optString("name", "glasses"),
          voice = o.optString("voice"),
          model = o.optString("model"),
          promptBlocks = (0 until blocks.length()).map { blocks.getString(it) },
          systemPrompt = o.getString("systemPrompt"),
          tools =
              (0 until toolsArr.length()).map { i ->
                val t = toolsArr.getJSONObject(i)
                ToolDeclaration(
                    name = t.getString("name"),
                    description = t.optString("description"),
                    parameters = t.optJSONObject("parameters"),
                )
              },
          deviceToolNames = (0 until deviceArr.length()).map { deviceArr.getString(it) },
          basePersonaBlocks = (0 until baseArr.length()).map { baseArr.getString(it) },
      )
    }

    fun load(stream: InputStream): VoiceProfile =
        parse(stream.bufferedReader().use { it.readText() })
  }
}
