/*
 * sai-fi — voice concierge (glasses temple-button control).
 */

// GlassesGestureSession — opens a DAT DeviceSession purely to react to the glasses temple gestures, so
// the temple can start/stop the call. A DAT session is capability-agnostic (display is just one
// attachable capability, which we deliberately don't attach — we have no display glasses).
//
// IMPORTANT — DAT 0.8 gives a third-party app NO gesture/touch/wear-state API. The gestures are NOT
// delivered as events we can bind; they are hardwired to session lifecycle, and all we observe is the
// resulting DeviceSessionState transition. Confirmed against the official DAT docs (Wearables MCP):
// "Users can pause, resume, or stop your session by closing the hinges, taking the glasses off, or
// tapping the glasses." The complete set of temple gestures is only three, and none is remappable:
//
//   • tap                → PAUSED ⇄ STARTED   (pause/resume the call)
//   • tap-and-hold       → STOPPED            (stops the session → we end the call)
//   • doff / fold / drop → STOPPED            (indistinguishable from tap-and-hold: same signal)
//   • two-finger "back"  → ends a DISPLAY session only (display capability + display hardware; N/A here)
//
// There is NO double-tap, swipe, or drag exposed, and tap-and-hold cannot be told apart from a fold/
// doff (both are just STOPPED with no distinguishing reason). So we CANNOT bind a third distinct action
// (e.g. photo capture) to a glasses gesture — manual capture lives on the phone UI / voice instead.
// See docs/SAI_GLASSES_APP.md §"Glasses gestures".
//
// This runs ALONGSIDE the call (not the camera stream), so it outlives a paused call and a tap can
// resume it. NOTE (on-device to-do): the docs describe gesture→state handling "during an active
// stream/session"; confirm a session with NO capability attached still delivers these transitions —
// if it doesn't, attach a throwaway camera stream purely to keep it live.
//
// iOS 0.8 deltas vs Android (measured with MockDeviceKit on iPhone 17 Simulator — see
// SaiFiTests/Glasses/MockDeviceTests.swift):
//
//   §4(a) Two concurrent DeviceSessions are refused (MockDeviceKit, iPhone 17 Simulator). A
//   capability-less gesture session and a separate camera session cannot coexist. iOS-only: one
//   session for the call, addStream on demand. That lights the privacy LED for as long as the
//   stream is attached; we still stop() the stream between captures (see GlassesCamera / §4(b))
//   rather than leaving it on for the whole call.
//
//   stateStream() does not buffer. Subscribe BEFORE start() and also read session.state
//   synchronously — the sample's DeviceSessionManager.getSession() exists entirely for this.

import Foundation
import MWDATCore

/// Sample CameraAccess affordances, ported byte-for-byte so a firmware/app-on-glasses floor is a
/// button, not a mystery error. `Compatibility.deviceUpdateRequired` → firmware;
/// `DeviceSessionError.datAppOnTheGlassesUpdateRequired` → app on glasses.
enum GlassesUpdateAffordance {
  static let firmware = "Update firmware"
  static let appOnGlasses = "Update app on glasses"
}

/// Answers to plan §4, measured on MockDeviceKit (iPhone 17 Simulator). Re-asserted every run by
/// `MockDeviceTests.testSection4a_concurrentSessions` and `testSection4b_streamReleaseWithoutRemoveStream`.
enum GlassesDatPolicy {
  /// §4(a): a second DeviceSession is refused. One session for the call; `addStream` on demand.
  /// iOS-only: the privacy LED is that session's stream.
  static let concurrentSessionsAllowed = false
  /// §4(b): `stream.stop()` then `addStream` on the same session delivers frames again. Teardown is
  /// `stop()`, not `session.stop()` (that would end the call via the gesture collector).
  static let streamStopReleasesSlot = true
}

@MainActor
final class GlassesGestureSession {
  private let wearables: WearablesInterface
  private let deviceSelector: AutoDeviceSelector
  private let onTap: () -> Void
  private let onStop: () -> Void
  private let onLog: (String) -> Void

  private var session: DeviceSession?
  private var stateTask: Task<Void, Never>?
  private var errorTask: Task<Void, Never>?
  private var deviceMonitorTask: Task<Void, Never>?
  private var prev: DeviceSessionState?

  /// Matches DeviceSessionManager.hasActiveDevice — AutoDeviceSelector only reports an eligible
  /// device once its stream is observed.
  private(set) var hasActiveDevice = false

  /// `Compatibility.deviceUpdateRequired` on the session's device — show
  /// `GlassesUpdateAffordance.firmware`.
  private(set) var requiresFirmwareUpdate = false

  /// `DeviceSessionError.datAppOnTheGlassesUpdateRequired` on create/start — show
  /// `GlassesUpdateAffordance.appOnGlasses`.
  private(set) var requiresDATAppUpdate = false

  init(
    wearables: WearablesInterface,
    onTap: @escaping () -> Void,
    onStop: @escaping () -> Void,
    onLog: @escaping (String) -> Void
  ) {
    self.wearables = wearables
    self.onTap = onTap
    self.onStop = onStop
    self.onLog = onLog
    self.deviceSelector = AutoDeviceSelector(wearables: wearables)
    deviceMonitorTask = Task { [weak self] in
      guard let self else { return }
      for await device in self.deviceSelector.activeDeviceStream() {
        self.hasActiveDevice = device != nil
      }
    }
  }

  /// The live DAT session (for attaching a one-shot camera stream), or null if none is open.
  func deviceSession() -> DeviceSession? { session }

  /// Best-effort: open a session for gestures. No-ops (with a log) if no glasses are registered.
  ///
  /// iOS: subscribe to AutoDeviceSelector *before* createSession — a fresh selector with no
  /// observer is how createSession returns `noEligibleDevice` even though the mock is paired
  /// (DeviceSessionManager.getSession() exists for this).
  func start() async {
    if session != nil { return }
    let deadline = Date().addingTimeInterval(6)
    while deviceSelector.activeDevice == nil && Date() < deadline {
      try? await Task.sleep(for: .milliseconds(50))
    }
    do {
      let created = try wearables.createSession(deviceSelector: deviceSelector)
      session = created
      refreshCompatibility(for: created)
      // Subscribe BEFORE start() — stateStream() does not buffer, and start() can complete
      // synchronously on MockDeviceKit. Also handle the current session.state after subscribe
      // and again after start(), or a transition that lands between the two is lost.
      let states = created.stateStream()
      let errors = created.errorStream()
      errorTask = Task { [onLog] in
        for await error in errors {
          // The call's audio is independent of this DAT session, so a session error only
          // disables the temple button + camera, not the call. "No eligible device" means
          // the glasses aren't paired/eligible for this app (on, unfolded, in range, registered).
          onLog(
            "glasses session error: \(error.description) — temple button/camera unavailable; the call continues on phone/Bluetooth audio"
          )
        }
      }
      stateTask = Task { [weak self] in
        for await st in states {
          self?.handle(st)
        }
      }
      handle(created.state)
      do {
        try created.start()
      } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
        requiresDATAppUpdate = true
        onLog(
          "glasses: no session (register glasses to use the temple button) — \(DeviceSessionError.datAppOnTheGlassesUpdateRequired.description)"
        )
        stop()
        return
      } catch {
        onLog(
          "glasses: no session (register glasses to use the temple button) — \(datErrorDescription(error))"
        )
        stop()
        return
      }
      handle(created.state)
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      requiresDATAppUpdate = true
      onLog(
        "glasses: no session (register glasses to use the temple button) — \(DeviceSessionError.datAppOnTheGlassesUpdateRequired.description)"
      )
    } catch {
      onLog(
        "glasses: no session (register glasses to use the temple button) — \(datErrorDescription(error))"
      )
    }
  }

  func stop() {
    stateTask?.cancel()
    stateTask = nil
    errorTask?.cancel()
    errorTask = nil
    prev = nil
    session?.stop()
    session = nil
  }

  /// Wait until DAT has actually released the device. `stop()` returns while the session is still
  /// `.stopping`, and the next `createSession` then fails with `sessionAlreadyExists`.
  func stopAndWait(timeout: TimeInterval = 5) async {
    guard let live = session else { return }
    let states = live.stateStream()
    stop()
    if live.state != .stopped {
      await withTaskGroup(of: Void.self) { group in
        group.addTask {
          for await state in states where state == .stopped { return }
        }
        group.addTask {
          try? await Task.sleep(for: .seconds(timeout))
        }
        await group.next()
        group.cancelAll()
      }
    }
  }

  deinit {
    deviceMonitorTask?.cancel()
    stateTask?.cancel()
    errorTask?.cancel()
    session?.stop()
  }

  private func handle(_ st: DeviceSessionState) {
    let p = prev
    if p == st { return }
    prev = st
    // Log the transition the moment it ARRIVES, before acting on it. A temple-tap mute
    // during a capture appeared minutes late in the log, and without an arrival line
    // there was no way to tell a late DELIVERY from a late reaction. Our own half of that
    // is fixed (the photo decode no longer blocks this dispatcher — see GlassesCamera);
    // if a tap is still late with this line in place, the queueing is DAT's.
    if p != nil { onLog("glasses: session \(kotlinName(p!)) → \(kotlinName(st))") }
    switch st {
    case .started:
      if p == nil {
        onLog("glasses: session started — temple button live")
      } else if p == .paused {
        onTap() // tap → resume
      }
    case .paused:
      onTap() // tap → pause
    case .stopped:
      onStop() // tap-and-hold / doff / fold
    default:
      break // STARTING / STOPPING / IDLE — transient
    }
  }

  private func refreshCompatibility(for session: DeviceSession) {
    guard let device = wearables.deviceForIdentifier(session.deviceId) else { return }
    if device.compatibility() == .deviceUpdateRequired {
      requiresFirmwareUpdate = true
    }
  }
}

func kotlinName(_ state: DeviceSessionState) -> String {
  switch state {
  case .idle: "IDLE"
  case .starting: "STARTING"
  case .started: "STARTED"
  case .paused: "PAUSED"
  case .stopping: "STOPPING"
  case .stopped: "STOPPED"
  }
}

func datErrorDescription(_ error: Error) -> String {
  (error as? any DatError)?.description ?? error.localizedDescription
}
