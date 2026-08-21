/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.json.JSONObject

/**
 * What to do with one part of a Live `modelTurn`.
 *
 * Gemini Live with AUDIO still emits `text` parts and, on thinking models, `thought: true` parts that
 * can carry their own audio. Thought audio is a different voice from Sai's, and
 * `outputAudioTranscription` does not cover it — the wearer hears words that never appear in the
 * transcript. Text parts are not a second TTS path in this app (we only play `inlineData`), but a
 * text-only frame with no transcription is the same gap from the other side: speech the log cannot
 * show. Classify here, where it can be tested; [GeminiLiveClient] is the interpreter.
 */
data class LivePartAction(
    /** Base64 PCM to play. Null for thought audio and non-audio parts. */
    val playAudioB64: String? = null,
    /** A log line. Names the drop; never carries the part's text (this log is mirrored to a projector). */
    val log: String? = null,
    /**
     * Text to put on Sai's transcript when audio transcription will not cover this frame: a
     * non-thought text part in a frame that plays no audio. Null when playing audio (transcription
     * is the source of truth) or when the part is a thought (thoughts are not speech).
     */
    val transcriptFallback: String? = null,
)

object LiveModelParts {

  fun classifyFrame(parts: JSONArray): List<LivePartAction> {
    val inspected = (0 until parts.length()).map { inspect(parts.getJSONObject(it)) }
    val willPlayAudio = inspected.any { !it.thought && !it.audioB64.isNullOrEmpty() }
    return inspected.map { p ->
      val play = if (!p.thought) p.audioB64 else null
      val log =
          when {
            p.thought && !p.audioB64.isNullOrEmpty() ->
                "[live] dropped thought audio — not speech"
            p.thought -> "[live] dropped thought text — not speech"
            else -> null
          }
      val fallback =
          if (!p.thought && !willPlayAudio && !p.text.isNullOrBlank()) p.text else null
      LivePartAction(playAudioB64 = play, log = log, transcriptFallback = fallback)
    }
  }

  private data class Inspected(val thought: Boolean, val audioB64: String?, val text: String?)

  private fun inspect(part: JSONObject): Inspected {
    val thought = part.optBoolean("thought", false)
    val audio = part.optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }
    val text = part.optString("text").takeIf { it.isNotEmpty() }
    return Inspected(thought, audio, text)
  }
}
