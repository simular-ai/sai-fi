/*
 * sai-fi — voice concierge.
 */

// A read-only observer of a live call. The presenter feed is the only implementation, and the point of
// this interface is that CallService does not know that.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/**
 * Everything an outside watcher can be told about a call, as it happens.
 *
 * This exists because the presenter feed — a demo dashboard that mirrors the call to a laptop — was
 * threaded through the call graph as eleven `presenter?.…` calls sitting inside `onAudio`,
 * `onInterrupted`, the capture success path, `log()`, `status()`, `transcript()` and the teardown. The
 * core of a phone call knew, at eleven points, that a demo tool might be listening.
 *
 * Now it knows only that an observer might be. [NoopCallObserver] is the release-build default and
 * every method is empty, so the mirroring costs nothing when nobody is watching; [PresenterObserver]
 * is constructed only inside the existing `BuildConfig.DEBUG` branch.
 *
 * Every method MUST be non-throwing and cheap. These are called from the audio path — `onMic` runs per
 * PCM frame — and an observer is a spectator: it must never be able to fail a call it is watching.
 */
interface CallObserver {
  /** A frame of microphone audio (PCM16, 16 kHz mono). Per-frame — keep this cheap. */
  fun onMic(pcm: ByteArray) {}

  /** A frame of Sai's speech (PCM16, 24 kHz mono). Per-frame — keep this cheap. */
  fun onSai(pcm: ByteArray) {}

  /** The user barged in: Sai's queued playback was flushed, so a mirror must drop it too. */
  fun onInterrupted() {}

  /** A glasses photo was captured (JPEG). */
  fun onPhoto(jpeg: ByteArray) {}

  /** A frame of the app's own window (JPEG), when screen mirroring is on. */
  fun onScreen(jpeg: ByteArray) {}

  /** One line of the activity log. [id] is stable so a mirror can upsert rather than append twice. */
  fun onLog(id: Long, text: String) {}

  /**
   * A transcript line for [role] ("you" / "sai"), carrying the FULL accumulated text.
   *
   * Full text rather than a delta, keyed by a stable [id], so a mirror shows exactly what the phone
   * shows without tracking deltas of its own.
   */
  fun onTurn(id: Long, role: String, text: String) {}

  /** The call's state changed — active, a status line, the audio route, machine, mute, pause. */
  fun onState(
      active: Boolean,
      status: String,
      route: String,
      machineLabel: String,
      muted: Boolean,
      paused: Boolean,
  ) {}

  /** The call is over. Release anything held. */
  fun onCallEnded(machineLabel: String) {}
}

/** The default. Every call is a no-op, so a release build carries no observer cost at all. */
object NoopCallObserver : CallObserver
