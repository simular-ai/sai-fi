/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/**
 * The glasses-capture cue: two short rising sine blips, never speech.
 *
 * Played the instant a capture starts so the user hears something while the camera spins up. It is a
 * tone on purpose — a second, different-sounding voice saying "one sec" (or "waiting for the camera")
 * before Sai's own voice is more jarring than a neutral blip. Built as 24 kHz PCM16 to ride the same
 * comm track as the model's audio.
 *
 * Duration is well under a spoken syllable on purpose: if this were ever swapped for TTS, the tests
 * on [pcm] and [durationMs] go red.
 */
object CaptureCue {
  const val SAMPLE_RATE = 24_000
  const val TONE_MS = 70
  const val GAP_MS = 45
  const val DURATION_MS = TONE_MS + GAP_MS + TONE_MS

  val pcm: ByteArray by lazy { build() }

  private fun build(): ByteArray {
    val samples = SAMPLE_RATE * TONE_MS / 1000
    val gap = SAMPLE_RATE * GAP_MS / 1000
    val out = java.io.ByteArrayOutputStream()
    fun tone(freq: Double) {
      for (i in 0 until samples) {
        val fade = minOf(1.0, minOf(i, samples - i) / (SAMPLE_RATE * 0.008))
        val v = Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE) * 0.22 * fade
        val s = (v * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
        out.write(s and 0xFF)
        out.write((s shr 8) and 0xFF)
      }
    }
    tone(880.0)
    repeat(gap * 2) { out.write(0) }
    tone(1174.7)
    return out.toByteArray()
  }
}
