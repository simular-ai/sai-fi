/* sai-fi — voice concierge (has DAT actually said whether the glasses are there?). */

// Turning DAT's device stream into three states instead of two — and, the part that has now bitten
// twice, deciding when *silence* from DAT has gone on long enough to count as an answer. A pure
// decision, so the rule is testable without glasses, a companion app, or an SDK.
//
// Drive it through `observe`, not by calling the step methods from a view collector. The settle
// delay and cancelling the wait on a new reading are part of the contract — leave them in the view
// and the StateFlow-seeded-empty trap (here, `devicesStream()` seeding empty) can ship again while
// the step-method tests stay green.
//
// Not thread-safe, and not meant to be: one instance per collector, touched only from that
// collector.
//
// Ported from Android `GlassesLink.kt`.

import Foundation

public final class GlassesLink: @unchecked Sendable {

  /// How long nothing-reported stays `nil` before it becomes an affirmative "no device".
  ///
  /// Deliberately generous, because the two ways of being wrong here are not equally bad.
  /// Concluding "no glasses" too early greys out the camera grant while the glasses are sitting
  /// there powered on — the original bug. Staying unknown too long only shows "checking…" a beat
  /// longer and leaves the grant button live.
  public static let settleMs: Int64 = 5_000

  /// True once DAT has said something we could actually read — either a device's link state, or
  /// nothing at all for a whole settle window.
  public private(set) var hasAnswered = false

  public init() {}

  /// DAT reported a device it can read: the return value is the answer, whatever it is.
  public func onLinkState(_ connected: Bool) -> Bool {
    hasAnswered = true
    return connected
  }

  /// DAT is reporting nothing readable — an empty device set, or ids whose metadata hasn't landed
  /// yet.
  ///
  /// `false` if DAT has answered before (so this is a device genuinely going away), otherwise
  /// `nil`: we have not heard from it, and saying "disconnected" would be inventing an answer.
  public func onNothingReported() -> Bool? {
    hasAnswered ? false : nil
  }

  /// `settleMs` passed with nothing reported. Absence is now the answer.
  public func onSettleElapsed() -> Bool {
    hasAnswered = true
    return false
  }

  /// Drive this `GlassesLink` from a stream of readings and publish every decision.
  ///
  /// Upstream convention: `nil` means nothing readable; `true`/`false` means DAT reported a device
  /// whose link state we could read. A new reading cancels an in-flight settle — without that, a
  /// late settle would overwrite a real "connected" with "disconnected".
  ///
  /// `sleep` is injectable so the settle window can be driven under a virtual clock. The default
  /// is a real delay of `settleMs`.
  public func observe(
    _ readings: AsyncStream<Bool?>,
    sleep: @escaping @Sendable (Int64) async -> Void = { ms in
      try? await Task.sleep(nanoseconds: UInt64(max(ms, 0)) * 1_000_000)
    },
    onPublish: @escaping @Sendable (Bool?) -> Void
  ) async {
    var pendingSettle: Task<Void, Never>?
    for await reading in readings {
      pendingSettle?.cancel()
      pendingSettle = nil
      if let reading {
        onPublish(onLinkState(reading))
      } else {
        let reported = onNothingReported()
        onPublish(reported)
        if reported == nil {
          pendingSettle = Task { [weak self] in
            await sleep(Self.settleMs)
            guard !Task.isCancelled, let self else { return }
            onPublish(self.onSettleElapsed())
          }
        }
      }
    }
    _ = await pendingSettle?.value
  }
}
