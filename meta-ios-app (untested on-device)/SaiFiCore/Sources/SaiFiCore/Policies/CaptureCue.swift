/* sai-fi — voice concierge. */

// The glasses-capture cue: two short rising sine blips, never speech.
//
// Played the instant a capture starts so the user hears something while the camera spins up. It is a
// tone on purpose — a second, different-sounding voice saying "one sec" (or "waiting for the camera")
// before Sai's own voice is more jarring than a neutral blip. Built as 24 kHz PCM16 to ride the same
// comm track as the model's audio.
//
// Duration is well under a spoken syllable on purpose: if this were ever swapped for TTS, the tests
// on `pcm` and `durationMs` go red.
//
// Ported from Android `CaptureCue.kt`.

import Foundation

public enum CaptureCue {
  public static let sampleRate = 24_000
  public static let toneMs = 70
  public static let gapMs = 45
  public static let durationMs = toneMs + gapMs + toneMs

  /// Built once. A `static let` is the Swift equivalent of the Kotlin `by lazy`.
  public static let pcm: Data = build()

  private static func build() -> Data {
    let samples = sampleRate * toneMs / 1000
    let gap = sampleRate * gapMs / 1000
    var out = Data()
    out.reserveCapacity((samples * 2 + gap) * 2)

    func tone(_ freq: Double) {
      for i in 0..<samples {
        let fade = min(1.0, min(Double(i), Double(samples - i)) / (Double(sampleRate) * 0.008))
        let v = sin(2.0 * Double.pi * freq * Double(i) / Double(sampleRate)) * 0.22 * fade
        let s = Int(v * Double(Int16.max)).clamped(to: -32768...32767)
        out.append(UInt8(s & 0xFF))
        out.append(UInt8((s >> 8) & 0xFF))
      }
    }

    tone(880.0)
    out.append(Data(repeating: 0, count: gap * 2))
    tone(1174.7)
    return out
  }
}

private extension Int {
  func clamped(to range: ClosedRange<Int>) -> Int {
    Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
  }
}
