/* sai-fi — voice concierge. */

// HeldNudgeQueue — what Sai would have said while muted, kept until it can be heard again.
//
// While muted the client drops its audio, so injecting a nudge that makes it speak would burn the
// result: Sai would say it to nobody and the agent event is not repeated. So we hold those nudges and
// replay them on unmute (CallService injects them, using the ask-first wording for completions so it
// waits for a natural gap rather than blurting).
//
// The collapsing rules exist so unmuting produces ONE short offer, not a monologue:
//   · only the newest `complete` survives — an older result is superseded by definition;
//   · `approval-request` / `error` are what actually block the user, so they come out first;
//   · anything else (progress chatter) is not worth replaying at all once it's stale.
//
// Pure so it unit-tests directly — the sequencing is the part worth pinning.
//
// Ported from Android `HeldNudgeQueue.kt`.

import Foundation
import os

public final class HeldNudgeQueue: @unchecked Sendable {
  public struct Held: Equatable, Sendable {
    public let kind: String
    public let nudge: String
    public init(kind: String, nudge: String) {
      self.kind = kind
      self.nudge = nudge
    }
  }

  private let max: Int
  private let lock = OSAllocatedUnfairLock(initialState: [Held]())

  public init(max: Int = 5) { self.max = max }

  /// True for the event kinds worth waking the user for the moment Sai is audible again.
  private static func urgent(_ kind: String) -> Bool {
    kind == "approval-request" || kind == "error"
  }

  /// Hold `nudge`. Returns false when it was deliberately discarded rather than queued, so the caller
  /// can log honestly instead of claiming everything was kept.
  @discardableResult
  public func add(kind: String, nudge: String) -> Bool {
    lock.withLock { items in
      // Progress/status chatter is worthless by the time the user can hear it — the completion or
      // the current state supersedes it. Dropping it here is what keeps the replay to one line.
      if !Self.urgent(kind) && kind != "complete" { return false }
      if kind == "complete" { items.removeAll { $0.kind == "complete" } }
      let held = Held(kind: kind, nudge: nudge)
      if Self.urgent(kind) { items.insert(held, at: 0) } else { items.append(held) }
      // Trim from the back: the front is urgent, and the newest complete is already deduped.
      while items.count > max { items.removeLast() }
      return true
    }
  }

  /// Take everything held, in delivery order (urgent first), and clear.
  public func drain() -> [Held] {
    lock.withLock { items in
      let out = items
      items.removeAll()
      return out
    }
  }

  public func clear() {
    lock.withLock { $0.removeAll() }
  }
}
