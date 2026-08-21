/*
 * sai-fi — voice-concierge (camera attachments).
 */

// GlassesCamera — capture ONE still off the glasses for the captureImage voice tool. Attaches a
// short-lived camera stream to an existing DAT DeviceSession (the gesture session), waits for STREAMING,
// snaps a photo, then tears the stream down — a headless, service-reusable capture path.
//
// Returns a typed [Result] (never a bare null) so the caller can tell the user/model WHY a capture
// failed instead of a generic "no photo". The failure taxonomy matters because the causes are very
// different: the glasses not being DAT-eligible (the session never reaches STARTED — environmental,
// nothing the app can fix) looks nothing like a transient slow stream start, yet both used to surface
// as the same misleading "stream didn't reach STREAMING in time" line.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A captured still: JPEG bytes + pixel dimensions. */
class CapturedPhoto(val jpeg: ByteArray, val width: Int, val height: Int)

object GlassesCamera {
  // Cold camera start over Bluetooth can be slow; give the stream a generous budget to reach STREAMING
  // before we give up. This was 12s — but a first frame arriving just AFTER the old deadline is exactly
  // the "the user sees the glasses take the picture, yet the app reports failure" gap: the shutter/LED
  // fired but we'd already timed out and torn the stream down. 20s + one retry (below) closes that gap.
  private const val STREAMING_TIMEOUT_MS = 20_000L
  // The camera can only stream while the parent DAT session is actually STARTED. If the glasses aren't
  // registered/eligible for this app (or are off / folded / out of range) the session never leaves
  // STARTING/STOPPED — a distinct, environmental failure. Wait a short beat for STARTED before we even
  // attach a stream, so we can report THAT honestly instead of a misleading stream timeout.
  private const val SESSION_READY_TIMEOUT_MS = 6_000L
  // One retry on a transient stream-start failure (re-adds a fresh stream). Small pause first so the
  // failed stream fully releases — the glasses expose a single camera stream per session.
  private const val RETRY_DELAY_MS = 750L
  // STREAMING only means the state machine advanced; the first frame over Bluetooth lands later.
  // capturePhoto before then fails with a bare "Failed to capture photo". Device 2026-08-20: first
  // frame at 8253 ms — 8 s was just short of a picture that did arrive.
  private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
  // A cold BT camera's FIRST capturePhoto almost always comes back empty right as frames begin — the
  // reason a capture used to burn 2–3 tries. So the still waits for the stream to be DELIVERING, not
  // merely to have delivered once.
  //
  // This was a fixed settle (700 ms, then 1500 ms) and the clock turned out to be the wrong
  // instrument: on the link where every still failed, the first frame arrived at 2.2 s and
  // capturePhoto STILL needed a second attempt after a full 1.5 s wait. A frame count adapts where a
  // duration cannot — 8 frames land in ~330 ms on a healthy 24 fps link and take seconds on a
  // struggling one, which is precisely when more waiting is the right answer.
  private const val STEADY_FRAMES = 8
  // Cap on waiting for those frames, MEASURED FROM THE FIRST FRAME, not from STREAMING. The previous
  // combined deadline (first-frame timeout + 3 s) stole the settle whenever the first frame was late:
  // 8.2 s to first frame left 2.8 s for the rest, we shot at 6/8, and all three capturePhoto calls
  // failed while the stream recovered to 24 frames underneath them. We still capture anyway when this
  // expires — one frame is a picture — but only after a real post-first-frame wait.
  private const val STEADY_FRAMES_TIMEOUT_MS = 8_000L
  // In-stream capturePhoto retries — much cheaper than re-adding the whole stream. A safety net now
  // that the settle above makes the first attempt succeed in the common case.
  private const val CAPTURE_ATTEMPTS = 3
  // Between still attempts, wait for a few more video frames (or this cap) rather than a blind 400 ms.
  // On a healthy 24 fps link four frames land in ~160 ms and the wait returns early; on a struggling
  // one the extra frames are the evidence the still pipeline has something to grab.
  private const val CAPTURE_RETRY_DELAY_MS = 1_500L
  private const val CAPTURE_RETRY_FRAMES = 4
  // Stream config, kept as named constants so the diagnostics can report exactly what we asked for
  // (quality/frame rate are a common cause of "started but sent no frame" on a struggling link).
  private val STREAM_QUALITY = VideoQuality.MEDIUM
  private const val STREAM_FRAME_RATE = 24
  private val STREAM_CFG_DESC = "$STREAM_QUALITY @ ${STREAM_FRAME_RATE}fps"

  /**
   * Best-effort, concrete rendering of a DAT [DatError] for logs: the enum constant name (the real
   * "error code" — e.g. HINGE_CLOSED / PERMISSIONS_DENIED / TIMEOUT / NO_ELIGIBLE_DEVICE) alongside
   * its human description, plus the underlying [t] (the second arg of DAT's onFailure, previously
   * discarded) when present. Far more actionable than the bare `.description` used before.
   */
  private fun datErr(e: DatError, t: Throwable?): String {
    // `(e as? Enum<*>)?.name` was the only source of a code, and on this SDK DatError is NOT an enum —
    // so every capture failure logged a bare "Failed to capture photo" with nothing to act on, which
    // is why a whole device session of failures couldn't be diagnosed. Fall back to the concrete class
    // and to toString(), which is where a non-enum error puts its identity.
    val code = (e as? Enum<*>)?.name ?: e::class.simpleName
    val base = if (code != null) "$code — ${e.description}" else e.description
    val raw = runCatching { e.toString() }.getOrNull()
    val extra = if (raw != null && raw != e.description && !base.contains(raw)) " {raw: $raw}" else ""
    return if (t != null) "$base$extra [${t.javaClass.simpleName}: ${t.message}]" else "$base$extra"
  }

  /** Outcome of a capture attempt. Failures carry both a user-facing line and a log-only detail. */
  sealed interface Result {
    data class Success(val photo: CapturedPhoto) : Result
    /**
     * [message] is safe to relay to the user/model verbatim; [detail] is the technical cause for logs.
     *
     * [streamStarted] says whether this attempt's stream reached STREAMING and delivered a frame — i.e.
     * whether the camera itself worked and only the still failed. A second stream is worth adding only
     * when the FIRST never started: re-adding after a stream that did start is what wedged the camera
     * for the rest of a call (the glasses expose one stream per session, and every later attach then
     * sat in STARTING until it timed out). See [capture]'s retry condition.
     */
    data class Failure(
        val message: String,
        val detail: String,
        val streamStarted: Boolean = false,
    ) : Result
  }

  // How long to wait for a torn-down stream to leave STREAMING before giving up on a clean slot. The
  // glasses expose one stream per session, so re-adding while the old one drains is what wedges the
  // camera for the rest of the call.
  private const val RELEASE_TIMEOUT_MS = 3_000L

  /**
   * Capture one still off [session]. Requires the DAT camera permission already granted (grant it from
   * the Activity first) AND [session] to reach STARTED. Returns a typed [Result] describing success or
   * the specific failure so the caller can tell the truth.
   */
  suspend fun capture(
      session: DeviceSession,
      onLog: (String) -> Unit,
      // Task C: invoked when a STREAM-LEVEL retry (attempt 2) begins, so the caller can signal the
      // user (an audio cue) that the extra wait isn't the app hanging. No-op by default.
      onRetry: () -> Unit = {},
  ): Result {
    val t0 = System.currentTimeMillis()
    // One round-trip, not two. checkPermissionStatus is eventually-consistent AND can hang on a busy
    // BT link (a barge-in just before capture is the documented case): the 2026-08-20 log printed
    // "permission = unknown" from the first call, then spent 18 s on a second call that came back
    // Granted, all billed as "session reached STARTED after 18570ms". Only an affirmative Denied
    // aborts; an unanswered check is logged and the stream fails loudly if the grant is actually missing.
    var permStatus: PermissionStatus? = null
    var permErr: String? = null
    Wearables.checkPermissionStatus(Permission.CAMERA)
        .onSuccess { permStatus = it }
        .onFailure { e, t -> permErr = datErr(e, t) }
    onLog(
        "camera: DAT camera permission = ${permStatus ?: "unknown"}" +
            if (permErr != null) " ($permErr)" else "",
    )
    if (permStatus == PermissionStatus.Denied) {
      onLog("camera: FAILED (no permission) — grant glasses camera access in the app first")
      return Result.Failure(
          "I don't have camera access on the glasses yet — grant it in the app, then try again.",
          "DAT camera permission not granted (Wearables.checkPermissionStatus(CAMERA) == Denied)",
      )
    }
    val permMs = System.currentTimeMillis() - t0
    if (permMs > 50) onLog("camera: permission check took ${permMs}ms")

    // Gate on the session actually being STARTED. A stream attached to a not-STARTED session just sits
    // in STARTING and times out — the real cause (glasses not eligible/ready) would otherwise be hidden
    // behind a generic "stream didn't reach STREAMING". This is the leading environmental failure.
    val sessionBefore = session.state.value
    val sessionT0 = System.currentTimeMillis()
    val ready =
        withTimeoutOrNull(SESSION_READY_TIMEOUT_MS) {
          session.state.first { it == DeviceSessionState.STARTED }
        }
    val sessionWaitMs = System.currentTimeMillis() - sessionT0
    if (ready == null) {
      val reached = session.state.value
      onLog(
          "camera: FAILED (session not STARTED within ${SESSION_READY_TIMEOUT_MS}ms; reached " +
              "$reached after ${sessionWaitMs}ms, initial $sessionBefore) — glasses not eligible/" +
              "ready; no stream attempted",
      )
      return Result.Failure(
          "The glasses camera isn't ready — the glasses may not be set up for this app, or they're " +
              "off, folded, or out of range.",
          "DeviceSession never reached STARTED (reached $reached after ${sessionWaitMs}ms, initial " +
              "$sessionBefore; not DAT-eligible / not connected)",
      )
    }
    onLog("camera: session reached STARTED after ${sessionWaitMs}ms")

    // Session is live. Try the stream; retry ONCE on a transient failure — but ONLY when the stream
    // never started.
    //
    // The stream-level retry exists for a stream that couldn't start. When the stream DID start and
    // delivered frames, and only capturePhoto failed, re-adding is the wrong move twice over: the
    // in-stream retries (CAPTURE_ATTEMPTS) are the cheap retry for exactly that case, and the extra
    // teardown/attach cycle is the thing that appears to wedge the session — in the device log, the one
    // attempt that reached STREAMING was followed by an attempt that never reached it again, for the
    // rest of the call, including a manual capture two minutes later.
    var last: Result.Failure? = null
    repeat(2) { attempt ->
      if (attempt > 0) {
        val prior = last
        if (prior != null && prior.streamStarted) {
          onLog(
              "camera: NOT re-adding the stream — it started and delivered frames, only the still " +
                  "failed (re-attaching after a working stream is what wedges the session)",
          )
          return prior
        }
        onLog(
            "camera: retrying capture (attempt ${attempt + 1}) — attempt 1 failed: ${prior?.detail}",
        )
        onRetry()
        delay(RETRY_DELAY_MS)
      }
      when (val r = attemptCapture(session, attempt + 1, t0, onLog)) {
        is Result.Success -> return r
        is Result.Failure -> last = r
      }
    }
    return last
        ?: Result.Failure("I couldn't get a photo from the glasses.", "unknown capture failure")
  }

  /**
   * One full attempt: add stream → wait for STREAMING → snap → decode → JPEG. Always tears the stream
   * down. [attemptNo] is the 1-based stream attempt (1 or 2) and [t0] the capture()-entry timestamp, so
   * the diagnostics can report which attempt failed and the total elapsed time.
   */
  private suspend fun attemptCapture(
      session: DeviceSession,
      attemptNo: Int,
      t0: Long,
      onLog: (String) -> Unit,
  ): Result {
    var stream: Stream? = null
    var addErr: String? = null
    session
        .addStream(StreamConfiguration(videoQuality = STREAM_QUALITY, frameRate = STREAM_FRAME_RATE))
        .onSuccess { stream = it }
        .onFailure { e, t -> addErr = datErr(e, t) }
    val s =
        stream
            ?: run {
              onLog("camera: FAILED (addStream, attempt $attemptNo, $STREAM_CFG_DESC) — $addErr")
              return Result.Failure(
                  "I couldn't open the glasses camera.",
                  "addStream failed on attempt $attemptNo ($STREAM_CFG_DESC): $addErr",
              )
            }
    try {
      // start() reports its own failure. Ignoring it turned "the camera refused to start" into a
      // silent 20s wait for a STREAMING state that was never coming — the misleading timeout in the
      // logs. Fail fast with the real reason instead.
      var startErr: String? = null
      s.start().onFailure { e, t -> startErr = datErr(e, t) }
      if (startErr != null) {
        onLog("camera: FAILED (stream.start, attempt $attemptNo, $STREAM_CFG_DESC) — $startErr")
        return Result.Failure(
            "I couldn't start the glasses camera.",
            "stream.start failed on attempt $attemptNo ($STREAM_CFG_DESC): $startErr",
        )
      }
      val streamT0 = System.currentTimeMillis()
      val streaming =
          withTimeoutOrNull(STREAMING_TIMEOUT_MS) { s.state.first { it == StreamState.STREAMING } }
      val streamingMs = System.currentTimeMillis() - streamT0
      if (streaming == null) {
        val reached = s.state.value
        onLog(
            "camera: FAILED (no STREAMING within ${STREAMING_TIMEOUT_MS}ms on attempt $attemptNo; " +
                "reached $reached, $STREAM_CFG_DESC)",
        )
        return Result.Failure(
            "The glasses camera didn't start in time.",
            "stream stuck before STREAMING on attempt $attemptNo (reached StreamState $reached, " +
                "timed out after ${streamingMs}ms; $STREAM_CFG_DESC)",
        )
      }
      onLog("camera: STREAMING after ${streamingMs}ms (attempt $attemptNo)")
      val still = deliverStill(s, attemptNo, onLog)
      if (still.frames == 0) {
        onLog(
            "camera: FAILED (no frame within ${FIRST_FRAME_TIMEOUT_MS}ms of STREAMING on attempt " +
                "$attemptNo; StreamState now ${s.state.value}, $STREAM_CFG_DESC)",
        )
        return Result.Failure(
            "The glasses camera started but sent no picture.",
            "no video frame within ${FIRST_FRAME_TIMEOUT_MS}ms of STREAMING on attempt $attemptNo " +
                "(STREAMING reached after ${streamingMs}ms; no first frame received; $STREAM_CFG_DESC)",
        )
      }
      val captured =
          still.photo
              ?: run {
                onLog(
                    "camera: FAILED (capturePhoto ×${still.tries} on attempt $attemptNo) — " +
                        "${still.lastError} ($STREAM_CFG_DESC)",
                )
                return Result.Failure(
                    "The glasses took the shot but I couldn't read it.",
                    "capturePhoto failed after ${still.tries} attempt(s) on stream attempt " +
                        "$attemptNo (first frame after ${still.firstFrameMs}ms, then " +
                        "${still.frames}/$STEADY_FRAMES frames in ${still.steadyMs}ms; " +
                        "$STREAM_CFG_DESC): ${still.lastError}",
                    // The camera worked — frames arrived. A fresh stream would add nothing here.
                    streamStarted = true,
                )
              }
      // Decode + rotate + re-encode on a WORKER thread, never the caller's.
      //
      // capture() is called from CallService's Dispatchers.Main.immediate scope, so this ran on the main
      // thread: a multi-megapixel BitmapFactory.decodeByteArray plus a Bitmap.compress is hundreds of
      // milliseconds of blocking CPU work there, which stalls every other coroutine on that dispatcher —
      // including the DAT gesture collector. That is why a temple-tap mute during a capture wasn't even
      // LOGGED until the capture finished.
      val encoded =
          withContext(Dispatchers.Default) {
            val bmp = toBitmap(captured, onLog) ?: return@withContext null
            val jpeg =
                ByteArrayOutputStream().use {
                  bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
                  it.toByteArray()
                }
            CapturedPhoto(jpeg, bmp.width, bmp.height)
          }
              ?: run {
                onLog("camera: FAILED (decode on attempt $attemptNo) — couldn't decode captured photo")
                return Result.Failure(
                    "The glasses took the shot but I couldn't read it.",
                    "photo decode returned null on attempt $attemptNo (capturePhoto succeeded but " +
                        "Bitmap/HEIC decode produced no bitmap; total ${System.currentTimeMillis() - t0}ms)",
                    streamStarted = true, // the camera delivered; the bytes were the problem
                )
              }
      return Result.Success(encoded)
    } finally {
      // NonCancellable, and that is not incidental. A capture is cancellable now — "stop" mid-photo
      // aborts it — and a cancelled coroutine cannot suspend, so an ordinary teardown here would run
      // s.stop() and then die on the release wait, leaving the one camera slot draining with nobody
      // watching. That is precisely the state the comment below describes as wedging every later
      // capture in the call. Teardown is the part that MUST finish, whatever the reason for leaving.
      withContext(NonCancellable) {
        // stop() ends streaming but LEAVES the capability attached to the session, and the glasses
        // expose exactly one camera stream per session — so the retry's addStream got a stream that
        // could never reach STREAMING (the 20s timeout on attempt 2 in the logs). Remove the
        // capability so the next attempt starts from a clean slot.
        // Both calls used to be bare runCatching, so a FAILING teardown was invisible — and a failing
        // teardown is exactly the state in which every later capture in the call is stuck before
        // STREAMING, which is what the device log showed. Report both, then WAIT for the stream to
        // leave STREAMING before returning: re-adding into a slot that is still draining is a race we
        // were losing 750 ms later. Bounded, and logged whenever it isn't instant.
        runCatching { s.stop() }
            .onFailure { onLog("camera: stop() failed on attempt $attemptNo — $it") }
        runCatching { session.removeStream() }
            .onFailure { onLog("camera: removeStream() failed on attempt $attemptNo — $it") }
        val releaseT0 = System.currentTimeMillis()
        val released =
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { s.state.first { it != StreamState.STREAMING } }
        val releaseMs = System.currentTimeMillis() - releaseT0
        if (released == null) {
          onLog(
              "camera: stream did NOT release within ${RELEASE_TIMEOUT_MS}ms after teardown on " +
                  "attempt $attemptNo (still ${s.state.value}) — the next capture may find the slot busy",
          )
        } else if (releaseMs > 50) {
          onLog("camera: stream released as $released after ${releaseMs}ms (attempt $attemptNo)")
        }
      }
    }
  }

  /** What one open stream managed to produce: the still, or why not, plus the frame evidence. */
  private class Still(
      val photo: PhotoData?,
      val lastError: String?,
      val tries: Int,
      val frames: Int,
      val firstFrameMs: Long,
      val steadyMs: Long,
  )

  /**
   * Wait for the stream to be DELIVERING, then take the still — WITHOUT letting go of the frames.
   *
   * STREAMING is a state-machine transition, not proof a frame exists. Capturing the instant it flips
   * is why capturePhoto returned a bare "Failed to capture photo": over Bluetooth the first frame
   * lands well after the state does. And not just ONE frame — this used to be a frame plus a fixed
   * 1.5 s settle, and on the link where every still failed the settle bought nothing (first frame at
   * 2.2 s, capturePhoto still needed a second attempt). A clock says the same thing on a healthy 24
   * fps link and on a struggling one; a frame COUNT adapts by itself, arriving in ~300 ms when the
   * link is fine and taking seconds when it isn't, which is exactly when more waiting is wanted.
   *
   * The settle clock starts at the FIRST FRAME, not at STREAMING. A combined deadline billed the
   * first-frame wait against the settle: 8.2 s to first frame left 2.8 s for the rest, we shot at
   * 6/8, and capturePhoto ×3 failed while frames recovered underneath (14, then 24).
   *
   * THE COLLECTOR STAYS SUBSCRIBED ACROSS THE CAPTURE, and that is the change this helper exists
   * for. The count used to come from `videoStream.take(STEADY_FRAMES).collect {}`, which CANCELS the
   * subscription the moment the eighth frame arrives — so every capturePhoto was issued against a
   * stream nobody was reading, and on 2026-08-20 a link that had just delivered 8 frames in 672 ms
   * needed three tries to produce one still. Whether an unobserved stream keeps delivering is an SDK
   * detail we would rather not bet a photo on, so the pump runs until the still is in hand.
   */
  private suspend fun deliverStill(s: Stream, attemptNo: Int, onLog: (String) -> Unit): Still =
      coroutineScope {
        val frameT0 = System.currentTimeMillis()
        val frames = AtomicInteger(0)
        val firstFrameAt = AtomicLong(0L)
        val first = CompletableDeferred<Unit>()
        val steady = CompletableDeferred<Unit>()
        val pump =
            launch(start = CoroutineStart.UNDISPATCHED) {
              s.videoStream.collect {
                val n = frames.incrementAndGet()
                if (n == 1) {
                  firstFrameAt.set(System.currentTimeMillis())
                  first.complete(Unit)
                }
                if (n >= STEADY_FRAMES) steady.complete(Unit)
              }
            }
        try {
          withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { first.await() }
          if (frames.get() == 0) {
            return@coroutineScope Still(null, null, 0, 0, -1L, System.currentTimeMillis() - frameT0)
          }
          withTimeoutOrNull(STEADY_FRAMES_TIMEOUT_MS) { steady.await() }
          val seen = frames.get()
          val firstFrameMs = firstFrameAt.get() - frameT0
          val steadyMs = System.currentTimeMillis() - frameT0
          onLog(
              "camera: first frame after ${firstFrameMs}ms, $seen/$STEADY_FRAMES frames in " +
                  "${steadyMs}ms (attempt $attemptNo)",
          )
          // Fewer frames than asked for means the wait timed out with the camera dribbling. Capture
          // anyway — one frame is still a picture, and failing here would throw away a stream that
          // might work — but say so, because "the still failed" reads very differently next to "the
          // link gave us 2 frames in 3 seconds".
          if (seen < STEADY_FRAMES) {
            onLog(
                "camera: only $seen frame(s) after ${STEADY_FRAMES_TIMEOUT_MS}ms from the first " +
                    "frame — the link is struggling; capturing anyway",
            )
          }
          // Even with frames flowing a capturePhoto can come back empty; a couple of in-stream
          // retries are far cheaper (and likelier to work) than tearing the whole stream down.
          var photo: PhotoData? = null
          var capErr: String? = null
          var tries = 0
          repeat(CAPTURE_ATTEMPTS) { n ->
            if (photo != null) return@repeat
            // The user can say "stop" mid-capture, and until this check the retries ran on regardless
            // — the abort reached the agent while the glasses carried on taking a picture nobody
            // wanted. capturePhoto itself is an SDK call with no cancellation of its own, so this is
            // the last point at which stopping is still cheap.
            currentCoroutineContext().ensureActive()
            tries = n + 1
            if (n > 0) {
              val before = frames.get()
              onLog(
                  "camera: capturePhoto retry ${n + 1}/$CAPTURE_ATTEMPTS (stream attempt " +
                      "$attemptNo, $before frames so far)",
              )
              withTimeoutOrNull(CAPTURE_RETRY_DELAY_MS) {
                while (frames.get() < before + CAPTURE_RETRY_FRAMES) delay(50)
              }
            }
            s.capturePhoto()
                .onSuccess { photo = it }
                .onFailure { e, t ->
                  capErr = datErr(e, t)
                  // Logged PER ATTEMPT, not only once every attempt has failed. The reason was
                  // previously kept until the whole capture gave up, so a capture that succeeded on
                  // its third try — the common shape on a cold camera — reported that it had retried
                  // and never said why, which is exactly the question a reader has.
                  onLog(
                      "camera: capturePhoto attempt ${n + 1}/$CAPTURE_ATTEMPTS failed — $capErr",
                  )
                }
          }
          Still(photo, capErr, tries, frames.get(), firstFrameMs, steadyMs)
        } finally {
          pump.cancel()
        }
      }

  private fun toBitmap(photo: PhotoData, onLog: (String) -> Unit): Bitmap? =
      when (photo) {
        // No orientation metadata on this variant — nothing to correct against, so a sideways sensor
        // stays sideways here. Say so in the log: every capture we've seen takes the HEIC path, which
        // means this one is untested, and a rotation report from a build that came through here is a
        // different bug from one that came through uprightByExif.
        is PhotoData.Bitmap -> {
          onLog("camera: PhotoData.Bitmap — no EXIF to correct, orientation NOT verified on this path")
          photo.bitmap
        }
        is PhotoData.HEIC -> {
          val bytes = ByteArray(photo.data.remaining()).also { photo.data.get(it) }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { uprightByExif(it, bytes, onLog) }
        }
      }

  /**
   * The glasses' camera sensor is mounted rotated, so the HEIC records the correction in its EXIF
   * Orientation tag — and BitmapFactory silently ignores EXIF. That's why captured photos reached the
   * agent sideways ("the image is rotated 90°, which is why everything appears sideways"). Bake the
   * rotation into the pixels here, since the JPEG we re-encode downstream carries no EXIF of its own.
   */
  private fun uprightByExif(bmp: Bitmap, heic: ByteArray, onLog: (String) -> Unit): Bitmap {
    val orientation =
        runCatching {
              ExifInterface(ByteArrayInputStream(heic))
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix =
        Matrix().apply {
          when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
              postRotate(90f)
              postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
              postRotate(270f)
              postScale(-1f, 1f)
            }
            else -> Unit // NORMAL / UNDEFINED — already upright
          }
        }
    if (matrix.isIdentity) return bmp
    onLog("camera: applying EXIF orientation $orientation")
    return runCatching {
          Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also {
            if (it !== bmp) bmp.recycle()
          }
        }
        .getOrDefault(bmp) // a rotation OOM must not lose the photo — sideways beats nothing
  }
}
