/*
 * sai-fi — voice concierge (audio capture/playback + glasses routing).
 */

// AudioIo — capture (16 kHz PCM16) + playback (24 kHz PCM16) for the Gemini Live loop, with a
// selectable route (phone vs glasses).
//
// Both routes ride the VOICE_COMMUNICATION path (capture source + playback usage) with the audio mode
// held at MODE_IN_COMMUNICATION for the whole call, so the platform AEC cancels our own playback out
// of the mic. Without this, speaker output leaks into the mic and the model constantly self-barges-in
// (the browser gets AEC free from getUserMedia; we don't).
//
// PHONE route: capture + playback ride the built-in earpiece/speaker + mic.
//
// GLASSES route: one persistent HFP/SCO session for the whole call — the glasses mic streams to the
// model while the model's TTS plays back over the same SCO link. Mono, lower-fidelity (SCO wideband,
// not A2DP hi-fi), but full-duplex: the mic stays live during playback, so the automatic-VAD voice
// barge-in works on the glasses route exactly like it does on the phone route. We deliberately do NOT
// switch to A2DP for hi-fi playback — that would drop the mic mid-utterance and make barge-in
// impossible on the glasses. Always-on full-duplex (and the barge-in it buys) is the desired tradeoff.
//
// If AEC misbehaves on the phone route, test with wired headphones to isolate it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

class AudioIo(
    context: Context,
    /**
     * Notified whenever the active mic/speaker route changes — including an unsolicited change like the
     * glasses powering off mid-call (the platform falls back to the built-in device). Delivered on the
     * main thread. [onGlasses] is true when the active device is the BT SCO route.
     */
    private val onRouteChanged: ((name: String?, onGlasses: Boolean) -> Unit)? = null,
) {
  enum class Route {
    PHONE,
    GLASSES,
  }

  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val mainExecutor = context.mainExecutor
  private var record: AudioRecord? = null
  @Volatile private var track: AudioTrack? = null
  @Volatile private var capturing = false
  private var worker: Thread? = null
  // Model audio waiting to be written to the track. Decoupling the socket thread from AudioTrack's
  // blocking write is what lets a barge-in flush land immediately (see play/flushPlayback).
  private val playQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var player: Thread? = null
  private var savedMode = AudioManager.MODE_NORMAL
  @Volatile private var desiredRoute = Route.PHONE

  // Fires on any comm-device change (our own selectRoute + unsolicited losses like glasses off).
  private val routeListener =
      AudioManager.OnCommunicationDeviceChangedListener { device ->
        val onGlasses = device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        Log.d(TAG, "comm device → ${device?.productName} (glasses=$onGlasses)")
        onRouteChanged?.invoke(device?.productName?.toString(), onGlasses)
      }

  // SCO can appear/disappear without the *active* communication device changing (e.g. glasses
  // reconnect while we're already on the built-in mic). Notify so CallService can auto-follow.
  private val deviceCallback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
          if (!capturing) return
          if (addedDevices.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return
          Log.d(TAG, "SCO device added")
          if (desiredRoute == Route.GLASSES) {
            applyRoute() // reconnect onto glasses; routeListener reports the result
          } else {
            notifyCurrentRoute() // still on phone — let CallService flip preference + select
          }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
          if (!capturing) return
          if (removedDevices.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return
          Log.d(TAG, "SCO device removed")
          notifyCurrentRoute()
        }
      }

  private fun notifyCurrentRoute() {
    val device = audioManager.communicationDevice
    onRouteChanged?.invoke(
        device?.productName?.toString(),
        device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )
  }

  /** The glasses on the HFP/SCO (voice) route, if connected as a Bluetooth audio device. */
  fun glassesDevice(): AudioDeviceInfo? =
      audioManager.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
      }

  /** True if the glasses (or any BT SCO headset) are reachable as a comm device right now. */
  fun glassesAvailable(): Boolean = glassesDevice() != null

  /**
   * Choose the route to use. Safe to call before [start] (stored and applied when capture opens) or
   * during a call (applied immediately). Returns the active device name, or null if GLASSES was asked
   * for but no SCO device is present (the caller stays on / falls back to PHONE).
   */
  fun selectRoute(route: Route) {
    desiredRoute = route
    if (capturing) applyRoute()
  }

  /**
   * Apply [desiredRoute] to the platform. Requires MODE_IN_COMMUNICATION (held for the whole call).
   *
   * Returns nothing on purpose: the route that actually took effect reaches the UI through
   * [onRouteChanged], which fires from the platform callback and is therefore the truth. An earlier
   * version returned a device name that all three call sites discarded.
   */
  private fun applyRoute() {
    when (desiredRoute) {
      Route.GLASSES -> {
        val dev = glassesDevice()
        if (dev == null) {
          Log.w(TAG, "GLASSES requested but no SCO device — staying on phone")
        } else {
          val ok = audioManager.setCommunicationDevice(dev)
          Log.d(TAG, "setCommunicationDevice(glasses)=$ok")
        }
      }
      Route.PHONE ->
          audioManager.clearCommunicationDevice() // back to earpiece/speaker; mode unchanged
    }
  }

  /** Open playback + start streaming mic frames (16 kHz PCM16 mono) to [onPcm] until [stop]. */
  @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the screen before this runs.
  fun start(onPcm: (ByteArray) -> Unit) {
    savedMode = audioManager.mode
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION // engage the platform AEC path
    audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, routeListener)
    audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    applyRoute() // honor a route selected before the call started (best-effort; falls back to phone)

    track = buildTrack()

    playQueue.clear()
    val inMin = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
    val rec =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            maxOf(inMin, FRAME_BYTES * 4))
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      throw IllegalStateException("AudioRecord failed to init (mic permission? in-call?)")
    }
    record = rec
    capturing = true
    // Started only now: the loop below runs while `capturing`, which is false until this point.
    player =
        thread(name = "live-playback") {
          while (capturing) {
            // Poll (not take) so the thread notices `capturing = false` promptly at teardown.
            val chunk = playQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            runCatching { track?.write(chunk, 0, chunk.size) }
          }
        }
    rec.startRecording()
    worker =
        thread(name = "live-mic") {
          val buf = ByteArray(FRAME_BYTES)
          // Client-side noise gate (see NOISE_GATE_* for the why): sub-threshold frames are REPLACED
          // WITH DIGITAL SILENCE, never dropped. Dropping them broke barge-in. Gemini's VAD runs
          // server-side and detects start-of-speech from the contrast between silence and speech in a
          // CONTINUOUS stream — with the frames dropped, the client sent nothing at all while Sai spoke
          // and the user was quiet, so the stream simply stopped and resumed already mid-utterance. The
          // very transition the VAD keys on had been deleted, and the interrupt came late or not at all.
          // Sending silence keeps the stream continuous (so onset detection and silenceDurationMs both
          // work normally) while still denying the VAD any ambient content to hallucinate words from.
          var hangoverUntil = 0L // keep the gate open until this wall-clock ms after the last loud frame
          while (capturing) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val frame = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
            val now = System.currentTimeMillis()
            if (rms(frame, n) >= NOISE_GATE_RMS) hangoverUntil = now + NOISE_GATE_HANGOVER_MS
            // Same cadence either way — one frame in, one frame out. A dropped frame would also drift
            // the server's sense of elapsed audio; silence keeps the timeline honest.
            onPcm(if (now < hangoverUntil) frame else ByteArray(frame.size))
          }
        }
  }

  /** Build the playback track on the comm (voice / SCO) path — full-duplex with the live mic. */
  private fun buildTrack(): AudioTrack {
    val outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
        .setBufferSizeInBytes(maxOf(outMin, OUT_RATE)) // ~0.5 s of 16-bit mono
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .apply { play() }
  }

  /**
   * Queue a chunk of 24 kHz PCM16 from the model.
   *
   * Never writes to the AudioTrack directly. `AudioTrack.write` BLOCKS once the track buffer is full
   * (~0.5 s here), and this used to be called straight from the Live WebSocket reader thread — so
   * that thread sat inside write() and could not read the `interrupted` message behind it. Barge-in
   * was therefore processed up to half a second late, after more of the reply had already played,
   * which is a large part of why interrupting "didn't stop it talking". A dedicated playback thread
   * drains this queue instead, so the reader thread is never stalled and a flush takes effect at once.
   */
  fun play(pcm: ByteArray) {
    playQueue.offer(pcm)
  }

  /**
   * A short two-note cue, played the instant a glasses capture starts.
   *
   * The model emits captureImage with no speech attached and only talks once the photo resolves, so
   * the user asks "what am I looking at?" and hears nothing at all for several seconds while the
   * camera spins up. This fills that gap immediately. Deliberately a tone rather than synthesized
   * speech: a second, different-sounding voice saying "one sec" before Sai's own voice replies is
   * more jarring than a neutral cue. Rides the same comm track as the model's audio, so it follows
   * the call route (glasses SCO or phone) with no extra audio plumbing.
   */
  fun playCaptureCue() {
    if (track == null) return
    playQueue.offer(CAPTURE_CUE)
  }

  /** Two short rising sine blips with fades, as 24 kHz PCM16 — built once. */
  private val CAPTURE_CUE: ByteArray by lazy {
    val toneMs = 70
    val gapMs = 45
    val samples = OUT_RATE * toneMs / 1000
    val gap = OUT_RATE * gapMs / 1000
    val out = java.io.ByteArrayOutputStream()
    fun tone(freq: Double) {
      for (i in 0 until samples) {
        // Fade in/out so the blip doesn't click at the edges.
        val fade = minOf(1.0, minOf(i, samples - i) / (OUT_RATE * 0.008))
        val v = Math.sin(2.0 * Math.PI * freq * i / OUT_RATE) * 0.22 * fade
        val s = (v * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
        out.write(s and 0xFF)
        out.write((s shr 8) and 0xFF)
      }
    }
    tone(880.0)
    repeat(gap * 2) { out.write(0) }
    tone(1174.7)
    out.toByteArray()
  }

  /** Barge-in: drop everything queued for playback so the model goes quiet immediately. */
  fun flushPlayback() {
    playQueue.clear() // drop everything not yet written, not just what the track already holds
    track?.let {
      runCatching {
        it.pause()
        it.flush()
        it.play()
      }
    }
  }

  fun stop() {
    capturing = false
    val rec = record
    record = null
    runCatching { rec?.stop() }
    worker?.join(500)
    worker = null
    playQueue.clear()
    // Unblock the playback thread BEFORE waiting on it. It can be parked inside a blocking write for
    // the length of the track buffer (~0.5s) — a stalled SCO link at teardown is the realistic case —
    // and pausing plus flushing is what makes that write return, so the join below almost always
    // finds a thread that has already left the native call.
    track?.let { runCatching { it.pause(); it.flush() } }
    val playbackFinished = player?.let { it.join(500); !it.isAlive } ?: true
    player = null
    rec?.release()
    // …and if it somehow did not, do NOT release underneath it: releasing frees the native track the
    // thread is still writing to. The runCatching around that write swallows the IllegalStateException,
    // so the use-after-free was silent — a leaked track on a teardown path is the cheaper of the two.
    if (playbackFinished) {
      track?.let { runCatching { it.release() } }
    } else {
      Log.w(TAG, "playback thread still in write() at stop — not releasing the track under it")
    }
    track = null
    runCatching { audioManager.removeOnCommunicationDeviceChangedListener(routeListener) }
    runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    runCatching { audioManager.clearCommunicationDevice() }
    runCatching { audioManager.mode = savedMode }
    Log.d(TAG, "audio stopped")
  }

  companion object {
    /** RMS amplitude (0..32767) of a little-endian PCM16 [frame] over [len] bytes — the gate's metric. */
    private fun rms(frame: ByteArray, len: Int): Double {
      var sumSq = 0.0
      var count = 0
      var i = 0
      while (i + 1 < len) {
        val lo = frame[i].toInt() and 0xFF
        val hi = frame[i + 1].toInt() // sign-extends the high byte → a signed 16-bit sample
        val sample = (hi shl 8) or lo
        sumSq += (sample * sample).toDouble()
        count++
        i += 2
      }
      return if (count == 0) 0.0 else kotlin.math.sqrt(sumSq / count)
    }

    /**
     * True if [frame] carries speech, by the same RMS test the noise gate uses. Lets a caller tell "a
     * human just spoke" from "silence" without duplicating the threshold — the muted-call keepalive
     * needs exactly that distinction (see CallService.maybeKeepalive).
     */
    fun carriesSpeech(frame: ByteArray): Boolean = rms(frame, frame.size) >= NOISE_GATE_RMS

    private const val TAG = "SaiFi:Audio"
    private const val IN_RATE = 16_000
    private const val OUT_RATE = 24_000
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit

    // ── Client-side noise gate (primary fix for low-energy VAD hallucination) ──────────────────────
    // Gemini's server VAD — especially at START_SENSITIVITY_HIGH, which we keep for snappy barge-in —
    // latches onto near-silence / ambient room noise on the glasses SCO route and transcribes it as
    // phantom words (often a random language, frequently Spanish), then answers speech that never
    // happened. We drop sub-threshold mic frames HERE so that noise never reaches the VAD, while real
    // speech (above threshold) passes straight through — hands-free barge-in stays instant and we never
    // mute the mic during playback.
    //
    //
    // RMS is measured on the 16 kHz mono PCM16 mic frame (amplitude 0..32767). ~500 (≈ -36 dBFS) sits
    // above typical room noise but well below normal speech (RMS ~1500-6000). THIS IS THE PRIMARY
    // TUNING KNOB: raise it if phantom words persist, lower it if quiet speech gets clipped.
    private const val NOISE_GATE_RMS = 500.0
    // Hangover: keep passing real audio this long after the last loud frame, so brief mid-sentence
    // dips (inter-word pauses, soft syllables) aren't flattened to silence mid-word. Only sustained
    // idle beyond this window is silenced — exactly the phantom-words scenario.
    //
    // It no longer has to out-last the VAD's silenceDurationMs (1200 ms). That requirement existed
    // only while gated frames were DROPPED: cutting the stream dead the moment the user stopped left
    // the server without the trailing audio it needs to detect end-of-speech, stranding the turn open.
    // Now the stream never stops — it carries silence — so the server sees end-of-speech on its own
    // and this value is free to be tuned purely for mid-word dips.
    private const val NOISE_GATE_HANGOVER_MS = 1500L

    /** True when a BT SCO/HFP headset (the glasses) is available as a communication device. */
    fun glassesScoAvailable(context: Context): Boolean {
      val am = context.getSystemService(AudioManager::class.java) ?: return false
      return am.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }
  }
}
