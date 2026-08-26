/* sai-fi — voice concierge. */

// What to do with one part of a Live `modelTurn`.
//
// Gemini Live with AUDIO still emits `text` parts and, on thinking models, `thought: true` parts that
// can carry their own audio. Thought audio is a different voice from Sai's, and
// `outputAudioTranscription` does not cover it — the wearer hears words that never appear in the
// transcript. Text parts are not a second TTS path in this app (we only play `inlineData`), but a
// text-only frame with no transcription is the same gap from the other side: speech the log cannot
// show. Classify here, where it can be tested; GeminiLiveClient is the interpreter.
//
// Ported from Android `LiveModelParts.kt`.

public struct LivePartAction: Equatable, Sendable {
  /// Base64 PCM to play. Nil for thought audio and non-audio parts.
  public var playAudioB64: String?
  /// A log line. Names the drop; never carries the part's text (this log is mirrored to a projector).
  public var log: String?
  /// Text to put on Sai's transcript when audio transcription will not cover this frame: a
  /// non-thought text part in a frame that plays no audio. Nil when playing audio (transcription
  /// is the source of truth) or when the part is a thought (thoughts are not speech).
  public var transcriptFallback: String?

  public init(playAudioB64: String? = nil, log: String? = nil, transcriptFallback: String? = nil) {
    self.playAudioB64 = playAudioB64
    self.log = log
    self.transcriptFallback = transcriptFallback
  }
}

public enum LiveModelParts {

  public static func classifyFrame(_ parts: [JsonObject]) -> [LivePartAction] {
    let inspected = parts.map(inspect)
    let willPlayAudio = inspected.contains { !$0.thought && !($0.audioB64?.isEmpty ?? true) }
    return inspected.map { p in
      let play = p.thought ? nil : p.audioB64
      let log: String?
      if p.thought && !(p.audioB64?.isEmpty ?? true) {
        log = "[live] dropped thought audio — not speech"
      } else if p.thought {
        log = "[live] dropped thought text — not speech"
      } else {
        log = nil
      }
      let fallback: String?
      if !p.thought, !willPlayAudio, let text = p.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        fallback = text
      } else {
        fallback = nil
      }
      return LivePartAction(playAudioB64: play, log: log, transcriptFallback: fallback)
    }
  }

  private struct Inspected {
    var thought: Bool
    var audioB64: String?
    var text: String?
  }

  private static func inspect(_ part: JsonObject) -> Inspected {
    let thought = part.optBool("thought", false)
    let audio = part.optObject("inlineData")?.str("data")
    let text = part.str("text")
    return Inspected(thought: thought, audioB64: audio, text: text)
  }
}
