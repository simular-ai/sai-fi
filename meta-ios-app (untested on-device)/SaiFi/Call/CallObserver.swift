/* sai-fi — voice concierge. */

// A read-only observer of a live call. The presenter feed is the only implementation, and the point
// of this interface is that the call coordinator does not know that.
//
// Ported from Android `CallObserver.kt`. PresenterObserver / PresenterSocket / WindowCapture are
// out of scope — only the protocol and the no-op live here, so the seam exists for a later
// presenter.

import Foundation

/// Everything an outside watcher can be told about a call, as it happens.
///
/// Every method MUST be non-throwing and cheap. These are called from the audio path — `onMic` runs
/// per PCM frame — and an observer is a spectator: it must never be able to fail a call it is
/// watching.
public protocol CallObserver: AnyObject {
  /// A frame of microphone audio (PCM16, 16 kHz mono). Per-frame — keep this cheap.
  func onMic(pcm: Data)
  /// A frame of Sai's speech (PCM16, 24 kHz mono). Per-frame — keep this cheap.
  func onSai(pcm: Data)
  /// The user barged in: Sai's queued playback was flushed, so a mirror must drop it too.
  func onInterrupted()
  /// A glasses photo was captured (JPEG).
  func onPhoto(jpeg: Data)
  /// A frame of the app's own window (JPEG), when screen mirroring is on.
  func onScreen(jpeg: Data)
  /// One line of the activity log. `id` is stable so a mirror can upsert rather than append twice.
  func onLog(id: Int64, text: String)
  /// A transcript line for `role` ("you" / "sai"), carrying the FULL accumulated text.
  func onTurn(id: Int64, role: String, text: String)
  /// The call's state changed — active, a status line, the audio route, machine, mute, pause.
  func onState(
    active: Bool,
    status: String,
    route: String,
    machineLabel: String,
    muted: Bool,
    paused: Bool
  )
  /// The call is over. Release anything held.
  func onCallEnded(machineLabel: String)
}

extension CallObserver {
  public func onMic(pcm: Data) {}
  public func onSai(pcm: Data) {}
  public func onInterrupted() {}
  public func onPhoto(jpeg: Data) {}
  public func onScreen(jpeg: Data) {}
  public func onLog(id: Int64, text: String) {}
  public func onTurn(id: Int64, role: String, text: String) {}
  public func onState(
    active: Bool,
    status: String,
    route: String,
    machineLabel: String,
    muted: Bool,
    paused: Bool
  ) {}
  public func onCallEnded(machineLabel: String) {}
}

/// The default. Every call is a no-op, so a release build carries no observer cost at all.
public final class NoopCallObserver: CallObserver {
  public static let shared = NoopCallObserver()
  public init() {}
}
