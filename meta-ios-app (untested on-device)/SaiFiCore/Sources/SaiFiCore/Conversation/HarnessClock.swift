/* sai-fi — voice concierge. */

// One clock for the whole closed loop.
//
// The FSM needs a DelayTimer for its approval ping, and the scripted agent needs to deliver an event
// programme some milliseconds after a task was forwarded. Both must move together or the ordering
// between them is fiction — an approval that "times out" before the completion that would have
// resolved it is a real failure mode, and a test with two independent clocks cannot express it.
//
// So this is the only clock. `advance` fires everything now due, in due order, and re-checks after
// each one: an action may schedule another (a drain forwarding the next task, which then schedules
// that task's events) and anything falling inside the same window has to run in this same advance,
// not the next.
//
// Agent-event actions are async because delivering one calls into the FSM. The FSM's own timer
// actions are not, which is why there are two kinds.
//
// Ported from Android `conversation/HarnessClock.kt`.

import Foundation
import os

/// Starts at a non-zero time deliberately.
///
/// CallService uses `0` as the sentinel for "the user has not spoken this call", so a clock starting
/// at zero makes the first utterance indistinguishable from silence and every completion comes back
/// wearing the ask-first wording. That is a harness artifact rather than a finding, and starting the
/// clock somewhere real is cheaper than teaching every scenario to step past it.
public final class HarnessClock: DelayTimer, @unchecked Sendable {
  private struct Entry {
    let dueAt: Int64
    let seq: Int64
    let plain: (@Sendable () -> Void)?
    let suspending: (@Sendable () async -> Void)?
  }

  private struct Bits {
    var now: Int64
    var entries: [Entry] = []
    var seq: Int64 = 0
  }

  private let bits: OSAllocatedUnfairLock<Bits>

  public init(now: Int64 = 10_000) {
    self.bits = OSAllocatedUnfairLock(initialState: Bits(now: now))
  }

  public var now: Int64 { bits.withLock { $0.now } }

  public var pending: Int { bits.withLock { $0.entries.count } }

  /// The FSM's timer seam.
  public func schedule(delayMs: Int64, action: @escaping @Sendable () -> Void) -> TimerCancellable {
    let seq = bits.withLock { s -> Int64 in
      let seq = s.seq
      s.seq += 1
      s.entries.append(Entry(dueAt: s.now + delayMs, seq: seq, plain: action, suspending: nil))
      return seq
    }
    return HarnessCancellable { [bits] in
      bits.withLock { $0.entries.removeAll { $0.seq == seq } }
    }
  }

  /// Schedule an agent-side action, which may suspend into the FSM.
  @discardableResult
  public func scheduleSuspending(
    delayMs: Int64,
    action: @escaping @Sendable () async -> Void
  ) -> TimerCancellable {
    let seq = bits.withLock { s -> Int64 in
      let seq = s.seq
      s.seq += 1
      s.entries.append(Entry(dueAt: s.now + delayMs, seq: seq, plain: nil, suspending: action))
      return seq
    }
    return HarnessCancellable { [bits] in
      bits.withLock { $0.entries.removeAll { $0.seq == seq } }
    }
  }

  /// Move the clock, firing everything that comes due.
  ///
  /// Re-checks after every action rather than snapshotting the due list up front: an action can
  /// schedule another inside the same window (a queue drain forwards the next task, whose first event
  /// lands 10 ms later), and those have to run here rather than waiting for the next advance.
  public func advance(_ ms: Int64) async throws {
    let target = bits.withLock { $0.now + ms }
    var fired = 0
    while true {
      let next = bits.withLock { s -> Entry? in
        let due = s.entries.filter { $0.dueAt <= target }
        guard let pick = due.min(by: { a, b in
          a.dueAt != b.dueAt ? a.dueAt < b.dueAt : a.seq < b.seq
        }) else { return nil }
        s.entries.removeAll { $0.seq == pick.seq }
        s.now = max(s.now, pick.dueAt)
        return pick
      }
      guard let next else { break }
      fired += 1
      if fired > Self.runaway {
        throw HarnessClockError.runawayAdvance
      }
      next.plain?()
      await next.suspending?()
    }
    bits.withLock { $0.now = target }
  }

  /// Run until nothing is left to fire. The usual way to end a scenario.
  public func drain() async throws {
    var rounds = 0
    while bits.withLock({ !$0.entries.isEmpty }) {
      rounds += 1
      if rounds > Self.runaway {
        throw HarnessClockError.runawayDrain
      }
      let furthest = bits.withLock { s in s.entries.map(\.dueAt).max() ?? s.now }
      let now = bits.withLock { $0.now }
      try await advance(max(furthest - now, 1))
    }
  }

  private static let runaway = 500
}

public enum HarnessClockError: Error, CustomStringConvertible {
  case runawayAdvance
  case runawayDrain

  public var description: String {
    switch self {
    case .runawayAdvance:
      return "the conversation did not settle after 500 actions in one advance — "
        + "something is answering itself in a loop"
    case .runawayDrain:
      return "the conversation never went quiet after 500 rounds — something keeps rescheduling"
    }
  }
}

private struct HarnessCancellable: TimerCancellable {
  let onCancel: @Sendable () -> Void
  func cancel() { onCancel() }
}
