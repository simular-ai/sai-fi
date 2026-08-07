/*
 * sai-fi — voice concierge.
 */

// The presenter feed as a CallObserver. DEBUG-only demo tooling; see CallObserver for why the
// indirection exists.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import kotlinx.coroutines.CoroutineScope

/**
 * Mirrors a live call to the presenter dashboard.
 *
 * The only [CallObserver] implementation. It owns the [PresenterSocket] outright — nothing else holds a
 * reference — so deleting the demo feed is deleting this file, `PresenterSocket`, `WindowCapture` and
 * the one construction site in `CallService.startPresenter`.
 *
 * Failures are the socket's problem, not the call's: `PresenterSocket`'s publish methods are
 * fire-and-forget and drop frames while the socket is down, which is why no method here has error
 * handling of its own.
 */
class PresenterObserver(
    baseUrl: String,
    key: String,
    scope: CoroutineScope,
    machineLabel: String,
    /**
     * Log sink. Goes STRAIGHT to CallController, never through CallService.log() — that publishes to
     * the presenter, and a socket reporting its own failures down a path that publishes to that socket
     * is how you get a loop.
     */
    onLog: (String) -> Unit,
) : CallObserver {
  private val socket =
      PresenterSocket(
          baseUrl = baseUrl,
          key = key,
          scope = scope,
          machineLabel = machineLabel,
          onLog = onLog,
      )

  fun connect() = socket.connect()

  /** The seam `WindowCapture` publishes app-window frames through. */
  fun screenSink(): (ByteArray) -> Unit = { socket.publishScreen(it) }

  override fun onMic(pcm: ByteArray) {
    socket.publishMic(pcm)
  }

  override fun onSai(pcm: ByteArray) {
    socket.publishSai(pcm)
  }

  override fun onInterrupted() {
    socket.interrupted()
  }

  override fun onPhoto(jpeg: ByteArray) {
    socket.publishPhoto(jpeg)
  }

  override fun onScreen(jpeg: ByteArray) {
    socket.publishScreen(jpeg)
  }

  override fun onLog(id: Long, text: String) {
    socket.log(id, text)
  }

  override fun onTurn(id: Long, role: String, text: String) {
    socket.turn(id, role, text)
  }

  override fun onState(
      active: Boolean,
      status: String,
      route: String,
      machineLabel: String,
      muted: Boolean,
      paused: Boolean,
  ) {
    socket.state(active, status, route, machineLabel, muted, paused)
  }

  override fun onCallEnded(machineLabel: String) {
    socket.state(false, "call ended", "", machineLabel, muted = false, paused = false)
    socket.close()
  }
}
