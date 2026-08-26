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
//
// iOS 0.8 deltas vs Android (measured with MockDeviceKit on iPhone 17 Simulator — see
// SaiFiTests/Glasses/MockDeviceTests.swift):
//
//   §4(a) Two concurrent DeviceSessions are refused. Capture attaches to the live gesture session
//   (same as Android's CallService — it already passed gesture.deviceSession() in). iOS-only
//   consequence: the privacy LED is tied to this session's stream, not a throwaway session.
//
//   §4(b) No removeStream(). Android's teardown is stream.stop() THEN session.removeStream() because
//   stop() alone leaves the capability attached and wedges the camera. iOS 0.8 has no removal API.
//   Measured: stream.stop(), then addStream again on the SAME session, DOES deliver frames. Teardown
//   is therefore stream.stop() + wait for the state to leave STREAMING — not session.stop() after
//   every capture (that would fire GlassesGestureSession's STOPPED → onStop and end the call).
//
//   capturePhoto(format: .jpeg) so the Android HEIC→JPEG bake collapses; still do orientation
//   normalisation. The still arrives on photoDataPublisher (capturePhoto itself returns Bool).
//   Keep the frame subscription alive across capturePhoto — crux of deliverStill.

import Foundation
import MWDATCamera
import MWDATCore
import UIKit

/// A captured still: JPEG bytes + pixel dimensions.
struct CapturedPhoto: Sendable {
  let jpeg: Data
  let width: Int
  let height: Int
}

enum GlassesCamera {
  // Cold camera start over Bluetooth can be slow; give the stream a generous budget to reach STREAMING
  // before we give up. This was 12s — but a first frame arriving just AFTER the old deadline is exactly
  // the "the user sees the glasses take the picture, yet the app reports failure" gap: the shutter/LED
  // fired but we'd already timed out and torn the stream down. 20s + one retry (below) closes that gap.
  private static let STREAMING_TIMEOUT_MS: Int64 = 20_000
  // The camera can only stream while the parent DAT session is actually STARTED. If the glasses aren't
  // registered/eligible for this app (or are off / folded / out of range) the session never leaves
  // STARTING/STOPPED — a distinct, environmental failure. Wait a short beat for STARTED before we even
  // attach a stream, so we can report THAT honestly instead of a misleading stream timeout.
  private static let SESSION_READY_TIMEOUT_MS: Int64 = 6_000
  // One retry on a transient stream-start failure (re-adds a fresh stream). Small pause first so the
  // failed stream fully releases — the glasses expose a single camera stream per session.
  private static let RETRY_DELAY_MS: Int64 = 750
  // STREAMING only means the state machine advanced; the first frame over Bluetooth lands later.
  // capturePhoto before then fails with a bare "Failed to capture photo". Device 2026-08-20: first
  // frame at 8253 ms — 8 s was just short of a picture that did arrive.
  private static let FIRST_FRAME_TIMEOUT_MS: Int64 = 12_000
  // A cold BT camera's FIRST capturePhoto almost always comes back empty right as frames begin — the
  // reason a capture used to burn 2–3 tries. So the still waits for the stream to be DELIVERING, not
  // merely to have delivered once.
  //
  // This was a fixed settle (700 ms, then 1500 ms) and the clock turned out to be the wrong
  // instrument: on the link where every still failed, the first frame arrived at 2.2 s and
  // capturePhoto STILL needed a second attempt after a full 1.5 s wait. A frame count adapts where a
  // duration cannot — 8 frames land in ~330 ms on a healthy 24 fps link and take seconds on a
  // struggling one, which is precisely when more waiting is the right answer.
  private static let STEADY_FRAMES = 8
  // Cap on waiting for those frames, MEASURED FROM THE FIRST FRAME, not from STREAMING. The previous
  // combined deadline (first-frame timeout + 3 s) stole the settle whenever the first frame was late:
  // 8.2 s to first frame left 2.8 s for the rest, we shot at 6/8, and all three capturePhoto calls
  // failed while the stream recovered to 24 frames underneath them. We still capture anyway when this
  // expires — one frame is a picture — but only after a real post-first-frame wait.
  private static let STEADY_FRAMES_TIMEOUT_MS: Int64 = 8_000
  // In-stream capturePhoto retries — much cheaper than re-adding the whole stream. A safety net now
  // that the settle above makes the first attempt succeed in the common case.
  private static let CAPTURE_ATTEMPTS = 3
  // Between still attempts, wait for a few more video frames (or this cap) rather than a blind 400 ms.
  // On a healthy 24 fps link four frames land in ~160 ms and the wait returns early; on a struggling
  // one the extra frames are the evidence the still pipeline has something to grab.
  private static let CAPTURE_RETRY_DELAY_MS: Int64 = 1_500
  private static let CAPTURE_RETRY_FRAMES = 4
  // Stream config, kept as named constants so the diagnostics can report exactly what we asked for
  // (quality/frame rate are a common cause of "started but sent no frame" on a struggling link).
  // Android VideoQuality.MEDIUM; iOS 0.8 has StreamingResolution, not VideoQuality. .raw is still
  // the right codec — capture is foreground-triggered (plan §1).
  private static let STREAM_RESOLUTION = StreamingResolution.medium
  private static let STREAM_FRAME_RATE: UInt = 24
  private static let STREAM_CFG_DESC = "MEDIUM @ \(STREAM_FRAME_RATE)fps"

  /**
   * Best-effort, concrete rendering of a DAT [DatError] for logs: the enum constant name (the real
   * "error code" — e.g. HINGE_CLOSED / PERMISSIONS_DENIED / TIMEOUT / NO_ELIGIBLE_DEVICE) alongside
   * its human description, plus the underlying [t] (the second arg of DAT's onFailure, previously
   * discarded) when present. Far more actionable than the bare `.description` used before.
   */
  private static func datErr(_ error: Error) -> String {
    // `(e as? Enum<*>)?.name` was the only source of a code, and on this SDK DatError is NOT an enum —
    // so every capture failure logged a bare "Failed to capture photo" with nothing to act on, which
    // is why a whole device session of failures couldn't be diagnosed. Fall back to the concrete class
    // and to toString(), which is where a non-enum error puts its identity.
    let code = kotlinErrorCode(error)
    let description = (error as? any DatError)?.description ?? error.localizedDescription
    let base = "\(code) — \(description)"
    let raw = String(describing: error)
    let extra =
      (raw != description && !base.contains(raw)) ? " {raw: \(raw)}" : ""
    return "\(base)\(extra)"
  }

  /// Outcome of a capture attempt. Failures carry both a user-facing line and a log-only detail.
  enum Result: Sendable {
    case success(CapturedPhoto)
    /**
     * [message] is safe to relay to the user/model verbatim; [detail] is the technical cause for logs.
     *
     * [streamStarted] says whether this attempt's stream reached STREAMING and delivered a frame — i.e.
     * whether the camera itself worked and only the still failed. A second stream is worth adding only
     * when the FIRST never started: re-adding after a stream that did start is what wedged the camera
     * for the rest of a call (the glasses expose one stream per session, and every later attach then
     * sat in STARTING until it timed out). See [capture]'s retry condition.
     */
    struct Failure: Sendable {
      var message: String
      var detail: String
      var streamStarted: Bool = false
    }
    case failure(Failure)
  }

  // How long to wait for a torn-down stream to leave STREAMING before giving up on a clean slot. The
  // glasses expose one stream per session, so re-adding while the old one drains is what wedges the
  // camera for the rest of the call.
  private static let RELEASE_TIMEOUT_MS: Int64 = 3_000

  /**
   * Capture one still off [session]. Requires the DAT camera permission already granted (grant it from
   * the Activity first) AND [session] to reach STARTED. Returns a typed [Result] describing success or
   * the specific failure so the caller can tell the truth.
   */
  static func capture(
    session: DeviceSession,
    wearables: WearablesInterface,
    onLog: @escaping @Sendable (String) -> Void,
    // Task C: invoked when a STREAM-LEVEL retry (attempt 2) begins, so the caller can signal the
    // user (an audio cue) that the extra wait isn't the app hanging. No-op by default.
    onRetry: @escaping @Sendable () -> Void = {}
  ) async -> Result {
    let t0 = nowMs()
    // One round-trip, not two. checkPermissionStatus is eventually-consistent AND can hang on a busy
    // BT link (a barge-in just before capture is the documented case): the 2026-08-20 log printed
    // "permission = unknown" from the first call, then spent 18 s on a second call that came back
    // Granted, all billed as "session reached STARTED after 18570ms". Only an affirmative Denied
    // aborts; an unanswered check is logged and the stream fails loudly if the grant is actually missing.
    var permStatus: PermissionStatus?
    var permErr: String?
    do {
      permStatus = try await wearables.checkPermissionStatus(.camera)
    } catch {
      permErr = datErr(error)
    }
    onLog(
      "camera: DAT camera permission = \(permStatus.map(kotlinPermName) ?? "unknown")"
        + (permErr.map { " (\($0))" } ?? "")
    )
    if permStatus == .denied {
      onLog("camera: FAILED (no permission) — grant glasses camera access in the app first")
      return .failure(
        Result.Failure(
          message:
            "I don't have camera access on the glasses yet — grant it in the app, then try again.",
          detail:
            "DAT camera permission not granted (Wearables.checkPermissionStatus(CAMERA) == Denied)"
        )
      )
    }
    let permMs = nowMs() - t0
    if permMs > 50 { onLog("camera: permission check took \(permMs)ms") }

    // Gate on the session actually being STARTED. A stream attached to a not-STARTED session just sits
    // in STARTING and times out — the real cause (glasses not eligible/ready) would otherwise be hidden
    // behind a generic "stream didn't reach STREAMING". This is the leading environmental failure.
    let sessionBefore = session.state
    let sessionT0 = nowMs()
    let ready = await waitForSessionStarted(session, timeoutMs: SESSION_READY_TIMEOUT_MS)
    let sessionWaitMs = nowMs() - sessionT0
    if !ready {
      let reached = session.state
      onLog(
        "camera: FAILED (session not STARTED within \(SESSION_READY_TIMEOUT_MS)ms; reached "
          + "\(kotlinName(reached)) after \(sessionWaitMs)ms, initial \(kotlinName(sessionBefore))) — glasses not eligible/"
          + "ready; no stream attempted"
      )
      return .failure(
        Result.Failure(
          message:
            "The glasses camera isn't ready — the glasses may not be set up for this app, or they're "
            + "off, folded, or out of range.",
          detail:
            "DeviceSession never reached STARTED (reached \(kotlinName(reached)) after \(sessionWaitMs)ms, initial "
            + "\(kotlinName(sessionBefore)); not DAT-eligible / not connected)"
        )
      )
    }
    onLog("camera: session reached STARTED after \(sessionWaitMs)ms")

    // Session is live. Try the stream; retry ONCE on a transient failure — but ONLY when the stream
    // never started.
    //
    // The stream-level retry exists for a stream that couldn't start. When the stream DID start and
    // delivered frames, and only capturePhoto failed, re-adding is the wrong move twice over: the
    // in-stream retries (CAPTURE_ATTEMPTS) are the cheap retry for exactly that case, and the extra
    // teardown/attach cycle is the thing that appears to wedge the session — in the device log, the one
    // attempt that reached STREAMING was followed by an attempt that never reached it again, for the
    // rest of the call, including a manual capture two minutes later.
    var last: Result.Failure?
    for attempt in 0..<2 {
      if attempt > 0 {
        let prior = last
        if prior?.streamStarted == true {
          onLog(
            "camera: NOT re-adding the stream — it started and delivered frames, only the still "
              + "failed (re-attaching after a working stream is what wedges the session)"
          )
          return .failure(prior!)
        }
        onLog(
          "camera: retrying capture (attempt \(attempt + 1)) — attempt 1 failed: \(prior?.detail ?? "")"
        )
        onRetry()
        try? await Task.sleep(nanoseconds: UInt64(RETRY_DELAY_MS) * 1_000_000)
      }
      switch await attemptCapture(session: session, attemptNo: attempt + 1, t0: t0, onLog: onLog) {
      case .success(let photo):
        return .success(photo)
      case .failure(let f):
        last = f
      }
    }
    return .failure(
      last
        ?? Result.Failure(
          message: "I couldn't get a photo from the glasses.",
          detail: "unknown capture failure"
        )
    )
  }

  /**
   * One full attempt: add stream → wait for STREAMING → snap → decode → JPEG. Always tears the stream
   * down. [attemptNo] is the 1-based stream attempt (1 or 2) and [t0] the capture()-entry timestamp, so
   * the diagnostics can report which attempt failed and the total elapsed time.
   */
  private static func attemptCapture(
    session: DeviceSession,
    attemptNo: Int,
    t0: Int64,
    onLog: @escaping @Sendable (String) -> Void
  ) async -> Result {
    var stream: MWDATCamera.Stream?
    var addErr: String?
    do {
      stream = try session.addStream(
        config: StreamConfiguration(
          videoCodec: .raw,
          resolution: STREAM_RESOLUTION,
          frameRate: STREAM_FRAME_RATE
        )
      )
    } catch {
      addErr = datErr(error)
    }
    guard let s = stream else {
      onLog("camera: FAILED (addStream, attempt \(attemptNo), \(STREAM_CFG_DESC)) — \(addErr ?? "nil stream")")
      return .failure(
        Result.Failure(
          message: "I couldn't open the glasses camera.",
          detail:
            "addStream failed on attempt \(attemptNo) (\(STREAM_CFG_DESC)): \(addErr ?? "nil stream")"
        )
      )
    }
    // start() reports its own failure. Ignoring it turned "the camera refused to start" into a
    // silent 20s wait for a STREAMING state that was never coming — the misleading timeout in the
    // logs. Fail fast with the real reason instead.
    //
    // iOS 0.8: Stream.start() is void; failures arrive on errorPublisher. Wait for STREAMING and
    // the error stream in parallel so a permission/hinge error is not billed as a 20s timeout.
    s.start()
    let streamT0 = nowMs()
    let startOutcome = await waitForStreaming(s, timeoutMs: STREAMING_TIMEOUT_MS)
    let streamingMs = nowMs() - streamT0
    switch startOutcome {
    case .error(let err):
      onLog("camera: FAILED (stream.start, attempt \(attemptNo), \(STREAM_CFG_DESC)) — \(datErr(err))")
      await release(s, attemptNo: attemptNo, onLog: onLog)
      return .failure(
        Result.Failure(
          message: "I couldn't start the glasses camera.",
          detail:
            "stream.start failed on attempt \(attemptNo) (\(STREAM_CFG_DESC)): \(datErr(err))"
        )
      )
    case .timeout:
      let reached = s.state
      onLog(
        "camera: FAILED (no STREAMING within \(STREAMING_TIMEOUT_MS)ms on attempt \(attemptNo); "
          + "reached \(kotlinStreamName(reached)), \(STREAM_CFG_DESC))"
      )
      await release(s, attemptNo: attemptNo, onLog: onLog)
      return .failure(
        Result.Failure(
          message: "The glasses camera didn't start in time.",
          detail:
            "stream stuck before STREAMING on attempt \(attemptNo) (reached StreamState \(kotlinStreamName(reached)), "
            + "timed out after \(streamingMs)ms; \(STREAM_CFG_DESC))"
        )
      )
    case .streaming:
      break
    }
    onLog("camera: STREAMING after \(streamingMs)ms (attempt \(attemptNo))")
    let still = await deliverStill(s, attemptNo: attemptNo, onLog: onLog)
    if still.frames == 0 {
      onLog(
        "camera: FAILED (no frame within \(FIRST_FRAME_TIMEOUT_MS)ms of STREAMING on attempt "
          + "\(attemptNo); StreamState now \(kotlinStreamName(s.state)), \(STREAM_CFG_DESC))"
      )
      await release(s, attemptNo: attemptNo, onLog: onLog)
      return .failure(
        Result.Failure(
          message: "The glasses camera started but sent no picture.",
          detail:
            "no video frame within \(FIRST_FRAME_TIMEOUT_MS)ms of STREAMING on attempt \(attemptNo) "
            + "(STREAMING reached after \(streamingMs)ms; no first frame received; \(STREAM_CFG_DESC))"
        )
      )
    }
    guard let captured = still.photo else {
      onLog(
        "camera: FAILED (capturePhoto ×\(still.tries) on attempt \(attemptNo)) — "
          + "\(still.lastError ?? "") (\(STREAM_CFG_DESC))"
      )
      await release(s, attemptNo: attemptNo, onLog: onLog)
      return .failure(
        Result.Failure(
          message: "The glasses took the shot but I couldn't read it.",
          detail:
            "capturePhoto failed after \(still.tries) attempt(s) on stream attempt "
            + "\(attemptNo) (first frame after \(still.firstFrameMs)ms, then "
            + "\(still.frames)/\(STEADY_FRAMES) frames in \(still.steadyMs)ms; "
            + "\(STREAM_CFG_DESC)): \(still.lastError ?? "")",
          // The camera worked — frames arrived. A fresh stream would add nothing here.
          streamStarted: true
        )
      )
    }
    // Decode + rotate + re-encode on a WORKER thread, never the caller's.
    //
    // capture() is called from CallService's Dispatchers.Main.immediate scope, so this ran on the main
    // thread: a multi-megapixel BitmapFactory.decodeByteArray plus a Bitmap.compress is hundreds of
    // milliseconds of blocking CPU work there, which stalls every other coroutine on that dispatcher —
    // including the DAT gesture collector. That is why a temple-tap mute during a capture wasn't even
    // LOGGED until the capture finished.
    let encoded = await Task.detached(priority: .userInitiated) {
      uprightJPEG(captured, onLog: onLog)
    }.value
    if let encoded {
      await release(s, attemptNo: attemptNo, onLog: onLog)
      return .success(encoded)
    }
    onLog("camera: FAILED (decode on attempt \(attemptNo)) — couldn't decode captured photo")
    await release(s, attemptNo: attemptNo, onLog: onLog)
    return .failure(
      Result.Failure(
        message: "The glasses took the shot but I couldn't read it.",
        detail:
          "photo decode returned null on attempt \(attemptNo) (capturePhoto succeeded but "
          + "Bitmap/HEIC decode produced no bitmap; total \(nowMs() - t0)ms)",
        streamStarted: true // the camera delivered; the bytes were the problem
      )
    )
  }

  /// iOS 0.8 has no removeStream(). §4(b) measured that stream.stop() releases the slot: a later
  /// addStream on the same session delivers frames. Do NOT session.stop() here — that is STOPPED
  /// on the gesture session and would end the call.
  private static func release(
    _ s: MWDATCamera.Stream,
    attemptNo: Int,
    onLog: @escaping @Sendable (String) -> Void
  ) async {
    // NonCancellable, and that is not incidental. A capture is cancellable now — "stop" mid-photo
    // aborts it — and a cancelled coroutine cannot suspend, so an ordinary teardown here would run
    // s.stop() and then die on the release wait, leaving the one camera slot draining with nobody
    // watching. That is precisely the state the comment below describes as wedging every later
    // capture in the call. Teardown is the part that MUST finish, whatever the reason for leaving.
    await Task.detached {
      // stop() ends streaming but LEAVES the capability attached to the session, and the glasses
      // expose exactly one camera stream per session — so the retry's addStream got a stream that
      // could never reach STREAMING (the 20s timeout on attempt 2 in the logs). Remove the
      // capability so the next attempt starts from a clean slot.
      // Both calls used to be bare runCatching, so a FAILING teardown was invisible — and a failing
      // teardown is exactly the state in which every later capture in the call is stuck before
      // STREAMING, which is what the device log showed. Report both, then WAIT for the stream to
      // leave STREAMING before returning: re-adding into a slot that is still draining is a race we
      // were losing 750 ms later. Bounded, and logged whenever it isn't instant.
      //
      // iOS 0.8: there is no removeStream(). stop() is the teardown; §4(b) showed addStream after
      // stop() still delivers frames on MockDeviceKit, so we do not session.stop() here.
      s.stop()
      let releaseT0 = nowMs()
      let released = await waitUntilStreamLeavesStreaming(s, timeoutMs: RELEASE_TIMEOUT_MS)
      let releaseMs = nowMs() - releaseT0
      if released == nil {
        onLog(
          "camera: stream did NOT release within \(RELEASE_TIMEOUT_MS)ms after teardown on "
            + "attempt \(attemptNo) (still \(kotlinStreamName(s.state))) — the next capture may find the slot busy"
        )
      } else if releaseMs > 50 {
        onLog(
          "camera: stream released as \(kotlinStreamName(released!)) after \(releaseMs)ms (attempt \(attemptNo))"
        )
      }
    }.value
  }

  /// What one open stream managed to produce: the still, or why not, plus the frame evidence.
  private struct Still: Sendable {
    var photo: PhotoData?
    var lastError: String?
    var tries: Int
    var frames: Int
    var firstFrameMs: Int64
    var steadyMs: Int64
  }

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
  private static func deliverStill(
    _ s: MWDATCamera.Stream,
    attemptNo: Int,
    onLog: @escaping @Sendable (String) -> Void
  ) async -> Still {
    let frameT0 = nowMs()
    let pump = FramePump()
    let videoToken = s.videoFramePublisher.listen { _ in
      pump.noteFrame()
    }
    let photoBox = PhotoBox()
    let photoToken = s.photoDataPublisher.listen { data in
      photoBox.complete(data)
    }
    let errorBox = ErrorBox()
    let errorToken = s.errorPublisher.listen { error in
      errorBox.complete(error)
    }
    defer {
      Task {
        await videoToken.cancel()
        await photoToken.cancel()
        await errorToken.cancel()
      }
    }
    await poll(timeoutMs: FIRST_FRAME_TIMEOUT_MS) { pump.frames >= 1 }
    if pump.frames == 0 {
      return Still(
        photo: nil,
        lastError: nil,
        tries: 0,
        frames: 0,
        firstFrameMs: -1,
        steadyMs: nowMs() - frameT0
      )
    }
    await poll(timeoutMs: STEADY_FRAMES_TIMEOUT_MS) { pump.frames >= STEADY_FRAMES }
    let seen = pump.frames
    let firstFrameMs = pump.firstFrameAt.map { $0 - frameT0 } ?? 0
    let steadyMs = nowMs() - frameT0
    onLog(
      "camera: first frame after \(firstFrameMs)ms, \(seen)/\(STEADY_FRAMES) frames in "
        + "\(steadyMs)ms (attempt \(attemptNo))"
    )
    // Fewer frames than asked for means the wait timed out with the camera dribbling. Capture
    // anyway — one frame is still a picture, and failing here would throw away a stream that
    // might work — but say so, because "the still failed" reads very differently next to "the
    // link gave us 2 frames in 3 seconds".
    if seen < STEADY_FRAMES {
      onLog(
        "camera: only \(seen) frame(s) after \(STEADY_FRAMES_TIMEOUT_MS)ms from the first "
          + "frame — the link is struggling; capturing anyway"
      )
    }
    // Even with frames flowing a capturePhoto can come back empty; a couple of in-stream
    // retries are far cheaper (and likelier to work) than tearing the whole stream down.
    var photo: PhotoData?
    var capErr: String?
    var tries = 0
    for n in 0..<CAPTURE_ATTEMPTS {
      if photo != nil { break }
      // The user can say "stop" mid-capture, and until this check the retries ran on regardless
      // — the abort reached the agent while the glasses carried on taking a picture nobody
      // wanted. capturePhoto itself is an SDK call with no cancellation of its own, so this is
      // the last point at which stopping is still cheap.
      if Task.isCancelled { break }
      tries = n + 1
      if n > 0 {
        let before = pump.frames
        onLog(
          "camera: capturePhoto retry \(n + 1)/\(CAPTURE_ATTEMPTS) (stream attempt "
            + "\(attemptNo), \(before) frames so far)"
        )
        await poll(timeoutMs: CAPTURE_RETRY_DELAY_MS) {
          pump.frames >= before + CAPTURE_RETRY_FRAMES
        }
      }
      photoBox.reset()
      let accepted = s.capturePhoto(format: .jpeg)
      if !accepted {
        capErr = "PHOTO_CAPTURE_FAILED — capturePhoto(format: jpeg) returned false"
        // Logged PER ATTEMPT, not only once every attempt has failed. The reason was
        // previously kept until the whole capture gave up, so a capture that succeeded on
        // its third try — the common shape on a cold camera — reported that it had retried
        // and never said why, which is exactly the question a reader has.
        onLog("camera: capturePhoto attempt \(n + 1)/\(CAPTURE_ATTEMPTS) failed — \(capErr!)")
        continue
      }
      // iOS delivers the still on photoDataPublisher, not as capturePhoto's return. Wait for
      // it (or a stream error) without dropping the frame pump.
      await poll(timeoutMs: STEADY_FRAMES_TIMEOUT_MS) {
        photoBox.value != nil || errorBox.value != nil
      }
      if let delivered = photoBox.value {
        photo = delivered
      } else if let err = errorBox.value {
        capErr = datErr(err)
        onLog("camera: capturePhoto attempt \(n + 1)/\(CAPTURE_ATTEMPTS) failed — \(capErr!)")
      } else {
        capErr = "PHOTO_CAPTURE_TIMEOUT — photoDataPublisher delivered no still"
        onLog("camera: capturePhoto attempt \(n + 1)/\(CAPTURE_ATTEMPTS) failed — \(capErr!)")
      }
    }
    return Still(
      photo: photo,
      lastError: capErr,
      tries: tries,
      frames: pump.frames,
      firstFrameMs: firstFrameMs,
      steadyMs: nowMs() - frameT0
    )
  }

  private static func uprightJPEG(
    _ photo: PhotoData,
    onLog: @Sendable (String) -> Void
  ) -> CapturedPhoto? {
    // iOS asked for JPEG, so the Android HEIC→JPEG bake (BitmapFactory + q85) collapses to
    // orientation normalisation. UIImage(data:) records EXIF in imageOrientation and leaves the
    // CGImage unrotated — drawing bakes it into pixels, since the JPEG we re-encode carries no
    // EXIF of its own. That's why captured photos reached the agent sideways ("the image is
    // rotated 90°, which is why everything appears sideways").
    guard let image = UIImage(data: photo.data) else { return nil }
    let oriented = bakeOrientation(image, onLog: onLog)
    guard let jpeg = oriented.jpegData(compressionQuality: 0.85) else { return nil }
    let width = oriented.cgImage?.width ?? Int(oriented.size.width * oriented.scale)
    let height = oriented.cgImage?.height ?? Int(oriented.size.height * oriented.scale)
    return CapturedPhoto(jpeg: jpeg, width: width, height: height)
  }

  /**
   * The glasses' camera sensor is mounted rotated, so the HEIC records the correction in its EXIF
   * Orientation tag — and BitmapFactory silently ignores EXIF. That's why captured photos reached the
   * agent sideways ("the image is rotated 90°, which is why everything appears sideways"). Bake the
   * rotation into the pixels here, since the JPEG we re-encode downstream carries no EXIF of its own.
   */
  private static func bakeOrientation(
    _ image: UIImage,
    onLog: @Sendable (String) -> Void
  ) -> UIImage {
    if image.imageOrientation == .up { return image }
    let exif = exifOrientation(image.imageOrientation)
    onLog("camera: applying EXIF orientation \(exif)")
    let format = UIGraphicsImageRendererFormat()
    format.scale = image.scale
    format.opaque = true
    let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
    return renderer.image { _ in
      image.draw(in: CGRect(origin: .zero, size: image.size))
    }
  }
}

// MARK: - Timing / wait helpers

private func nowMs() -> Int64 {
  Int64(Date().timeIntervalSince1970 * 1000)
}

private func poll(timeoutMs: Int64, _ condition: @escaping @Sendable () -> Bool) async {
  let deadline = nowMs() + timeoutMs
  while !condition() && nowMs() < deadline {
    if Task.isCancelled { return }
    try? await Task.sleep(nanoseconds: 20_000_000)
  }
}

private func waitForSessionStarted(_ session: DeviceSession, timeoutMs: Int64) async -> Bool {
  if session.state == .started { return true }
  let states = session.stateStream()
  if session.state == .started { return true }
  return await withTaskGroup(of: Bool.self) { group in
    group.addTask {
      for await st in states {
        if st == .started { return true }
      }
      return session.state == .started
    }
    group.addTask {
      try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
      return session.state == .started
    }
    let result = await group.next() ?? false
    group.cancelAll()
    return result || session.state == .started
  }
}

private enum StreamingWait: Sendable {
  case streaming
  case error(StreamError)
  case timeout
}

private func waitForStreaming(_ stream: MWDATCamera.Stream, timeoutMs: Int64) async -> StreamingWait {
  if stream.state == .streaming { return .streaming }
  let errors = ErrorBox()
  let errorToken = stream.errorPublisher.listen { error in
    errors.complete(error)
  }
  // Re-check after subscribe — publishers do not buffer.
  if stream.state == .streaming {
    Task { await errorToken.cancel() }
    return .streaming
  }
  await poll(timeoutMs: timeoutMs) {
    stream.state == .streaming || errors.value != nil
  }
  Task { await errorToken.cancel() }
  if stream.state == .streaming { return .streaming }
  if let err = errors.value { return .error(err) }
  return .timeout
}

private func waitUntilStreamLeavesStreaming(_ stream: MWDATCamera.Stream, timeoutMs: Int64) async -> StreamState? {
  if stream.state != .streaming { return stream.state }
  await poll(timeoutMs: timeoutMs) { stream.state != .streaming }
  return stream.state != .streaming ? stream.state : nil
}

func kotlinPermName(_ status: PermissionStatus) -> String {
  switch status {
  case .granted: "Granted"
  case .denied: "Denied"
  }
}

func kotlinStreamName(_ state: StreamState) -> String {
  switch state {
  case .stopping: "STOPPING"
  case .stopped: "STOPPED"
  case .waitingForDevice: "WAITING_FOR_DEVICE"
  case .starting: "STARTING"
  case .streaming: "STREAMING"
  case .paused: "PAUSED"
  }
}

func kotlinErrorCode(_ error: Error) -> String {
  if let e = error as? DeviceSessionError {
    switch e {
    case .noEligibleDevice: return "NO_ELIGIBLE_DEVICE"
    case .sessionAlreadyStopped: return "SESSION_ALREADY_STOPPED"
    case .sessionAlreadyExists: return "SESSION_ALREADY_EXISTS"
    case .sessionIdle: return "SESSION_IDLE"
    case .capabilityAlreadyActive: return "CAPABILITY_ALREADY_ACTIVE"
    case .capabilityNotFound: return "CAPABILITY_NOT_FOUND"
    case .unexpectedError: return "UNEXPECTED_ERROR"
    case .thermalCritical: return "THERMAL_CRITICAL"
    case .thermalEmergency: return "THERMAL_EMERGENCY"
    case .peakPowerShutdown: return "PEAK_POWER_SHUTDOWN"
    case .batteryCritical: return "BATTERY_CRITICAL"
    case .datAppOnTheGlassesUpdateRequired: return "DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED"
    case .dwaUnavailable: return "DWA_UNAVAILABLE"
    }
  }
  if let e = error as? StreamError {
    switch e {
    case .internalError: return "INTERNAL_ERROR"
    case .deviceNotFound: return "DEVICE_NOT_FOUND"
    case .deviceNotConnected: return "DEVICE_NOT_CONNECTED"
    case .timeout: return "TIMEOUT"
    case .videoStreamingError: return "VIDEO_STREAMING_ERROR"
    case .permissionDenied: return "PERMISSIONS_DENIED"
    case .hingesClosed: return "HINGE_CLOSED"
    case .thermalCritical: return "THERMAL_CRITICAL"
    case .thermalEmergency: return "THERMAL_EMERGENCY"
    case .peakPowerShutdown: return "PEAK_POWER_SHUTDOWN"
    case .batteryCritical: return "BATTERY_CRITICAL"
    }
  }
  if let e = error as? CaptureError {
    switch e {
    case .photo_capture_timeout: return "PHOTO_CAPTURE_TIMEOUT"
    case .photo_capture_failed: return "PHOTO_CAPTURE_FAILED"
    }
  }
  if let e = error as? PermissionError {
    return String(describing: e).uppercased()
  }
  return (error as? any DatError).map { _ in String(describing: type(of: error)) }
    ?? String(describing: type(of: error))
}

func exifOrientation(_ o: UIImage.Orientation) -> Int32 {
  switch o {
  case .up: 1
  case .down: 3
  case .left: 8
  case .right: 6
  case .upMirrored: 2
  case .downMirrored: 4
  case .leftMirrored: 5
  case .rightMirrored: 7
  @unknown default: 1
  }
}

// MARK: - Tiny concurrency boxes

private final class FramePump: @unchecked Sendable {
  private let lock = NSLock()
  private var count = 0
  private var firstAt: Int64?

  var frames: Int {
    lock.lock()
    defer { lock.unlock() }
    return count
  }

  var firstFrameAt: Int64? {
    lock.lock()
    defer { lock.unlock() }
    return firstAt
  }

  func noteFrame() {
    lock.lock()
    count += 1
    if firstAt == nil { firstAt = nowMs() }
    lock.unlock()
  }
}

private final class PhotoBox: @unchecked Sendable {
  private let lock = NSLock()
  private var photo: PhotoData?

  var value: PhotoData? {
    lock.lock()
    defer { lock.unlock() }
    return photo
  }

  func reset() {
    lock.lock()
    photo = nil
    lock.unlock()
  }

  func complete(_ data: PhotoData) {
    lock.lock()
    photo = data
    lock.unlock()
  }
}

private final class ErrorBox: @unchecked Sendable {
  private let lock = NSLock()
  private var error: StreamError?

  var value: StreamError? {
    lock.lock()
    defer { lock.unlock() }
    return error
  }

  func complete(_ error: StreamError) {
    lock.lock()
    self.error = error
    lock.unlock()
  }
}
