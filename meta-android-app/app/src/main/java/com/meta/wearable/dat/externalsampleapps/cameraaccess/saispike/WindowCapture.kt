/*
 * sai-fi — voice concierge (presenter feed).
 */

// WindowCapture — mirrors THIS APP'S WINDOW to the presenter dashboard as low-rate JPEGs.
//
// Replaces the MediaProjection implementation, which captured the whole display. Android offers no
// way to pre-select "just my app" for MediaProjection: `createConfigForDefaultDisplay()` removes the
// entire-screen-vs-single-app picker but then always captures the entire screen, and partial capture
// always routes through the user picking an app. So MediaProjection cannot do what's wanted here.
//
// An app can always read its own window, though, and PixelCopy does exactly that. Consequences, all
// of them improvements for a demo:
//
//   • No consent dialog. MediaProjection's is mandatory and single-use per session, so it appeared
//     once per call and had to be dismissed before going on stage.
//   • No cast indicator in the status bar for the whole call.
//   • Notification banners, other apps and system dialogs are NOT captured — only this app's window.
//     That was previously a real privacy footgun, since the feed rides unencrypted ws://.
//   • No mediaProjection foreground-service type or permission.
//
// The trade: capture only works while the Activity is resumed and its window has a surface. Background
// the app and frames stop until it returns. For mirroring the app's own UI that's the correct
// behaviour — there is nothing to show when the app isn't on screen — but it does mean this cannot
// mirror the Meta AI permission sheets, which live in another app's window.
//
// Threading: the tick runs on the main thread (it reads View dimensions and PixelCopy wants a live
// Window), while the copy itself happens on the render thread and the JPEG encode runs on this
// class's own HandlerThread. The next tick is only scheduled once the previous encode finishes, so
// frames can't overlap on the shared bitmap and a slow encode throttles itself instead of queueing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import java.io.ByteArrayOutputStream

class WindowCapture(
    private val window: Window,
    /** Called on the encode thread with a JPEG. Must not block. */
    private val onFrame: (ByteArray) -> Unit,
    /** Status lines only. Never called per-frame. */
    private val onLog: (String) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private var thread: HandlerThread? = null
  private var encode: Handler? = null
  @Volatile private var running = false
  /**
   * Which run a scheduled hop belongs to. Bumped by [stop], read on the encode thread.
   *
   * `running` alone cannot carry this: the hop back to the main thread is posted from the encode
   * thread, so it can land after a [stop] *and* a later [start], by which point the flag is true
   * again and the hop looks live.
   */
  @Volatile private var generation = 0
  private var bitmap: Bitmap? = null
  private var logged = false

  private val tick = Runnable { capture() }

  fun start() {
    if (running) return
    running = true
    val t = HandlerThread("sai-window-capture").also { it.start() }
    thread = t
    encode = Handler(t.looper)
    main.post(tick)
  }

  fun stop() {
    running = false
    // Retires any hop already posted from the encode thread. `removeCallbacks` cannot reach those —
    // it matches by Runnable identity, and that one is posted from another thread at an arbitrary
    // moment — so the generation is what makes them inert.
    generation++
    main.removeCallbacks(tick)
    thread?.quitSafely()
    thread = null
    encode = null
    bitmap?.recycle()
    bitmap = null
    logged = false
  }

  private fun scheduleNext() {
    if (running) main.postDelayed(tick, FRAME_INTERVAL_MS)
  }

  private fun capture() {
    if (!running) return
    // Nothing is listening (no call, or the presenter feed is off) — don't spend a copy on it.
    if (CallController.screenSink == null) {
      scheduleNext()
      return
    }
    val decor = window.peekDecorView()
    if (decor == null || !decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) {
      scheduleNext()
      return
    }

    // PixelCopy scales the window into whatever size the destination bitmap is, so the downscale is
    // free here rather than a second CPU pass.
    val scale = minOf(1f, MAX_WIDTH.toFloat() / decor.width)
    val w = ((decor.width * scale).toInt() / 2) * 2
    val h = ((decor.height * scale).toInt() / 2) * 2
    if (w <= 0 || h <= 0) {
      scheduleNext()
      return
    }
    val dest =
        bitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
              bitmap?.recycle()
              bitmap = it
            }
    if (!logged) {
      logged = true
      onLog("screen: mirroring app window ${w}x$h @${1000 / FRAME_INTERVAL_MS}fps")
    }

    val handler = encode ?: return
    val ok =
        runCatching {
              PixelCopy.request(window, dest, { result -> onCopied(result, dest) }, handler)
            }
            .isSuccess
    // An IllegalArgumentException here means the window has no surface yet (mid-transition). Not
    // fatal and not worth logging per frame — just try again on the next tick.
    if (!ok) scheduleNext()
  }

  private fun onCopied(result: Int, dest: Bitmap) {
    try {
      if (!running) return
      if (result != PixelCopy.SUCCESS) return
      val out = ByteArrayOutputStream(JPEG_HINT_BYTES)
      dest.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      onFrame(out.toByteArray())
    } catch (e: Throwable) {
      // A capture failure must never reach the call.
      Log.w(TAG, "window frame failed", e)
    } finally {
      // Tagged with the run it belongs to. Untagged, a pause landing between this encode finishing
      // and the hop running left the hop queued; `start()` then set `running` back to true and
      // posted its own tick, and this one posted a SECOND alongside it. Two chains at ~3 fps then
      // raced on the single `bitmap` — a PixelCopy write overlapping a compress read — which is
      // precisely what the header of this class says cannot happen.
      val gen = generation
      main.post { if (gen == generation) scheduleNext() }
    }
  }

  private companion object {
    const val TAG = "SaiFi:WindowCapture"
    /** Wide enough for the app's UI text to stay legible in a dashboard panel. */
    const val MAX_WIDTH = 400
    /** ~3 fps. A dashboard gains nothing from more, and the link may be a high-RTT VPN hop. */
    const val FRAME_INTERVAL_MS = 333L
    const val JPEG_QUALITY = 50
    const val JPEG_HINT_BYTES = 48 * 1024
  }
}
