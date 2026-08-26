/* sai-fi — voice concierge. */

// The three seams, faked. These are what let the whole state machine be driven with no network and no
// MWDAT.
//
// FakeChannel records `say` and `instruct` SEPARATELY on purpose. The two are not interchangeable (one
// is spoken verbatim, one never reaches the user), and every recorded regression in this area was a
// line going out on the wrong one. A fake that merged them could not catch that.
//
// FakeAgent has no queue of its own. It used to on the server, because the server held one and the two
// could disagree — the agent starting a task the FSM still believed was waiting. The queue is local
// now, so the FSM's own queue IS the whole truth and a second copy here could only lie about it.
//
// Ported from the Android `fsm/Fakes.kt`. Actors rather than plain classes because `Concierge` is an
// actor and its collaborators must be Sendable to cross into it.

import Foundation
import os

/// One recorded call to the bridge.
public struct BridgeCall: Sendable, Equatable {
  public let method: String
  public let text: String?
  public let attachments: [String]?
  public let id: String?
  public let decision: String?
  public let selection: [[String]]?

  init(
    method: String,
    text: String? = nil,
    attachments: [String]? = nil,
    id: String? = nil,
    decision: String? = nil,
    selection: [[String]]? = nil
  ) {
    self.method = method
    self.text = text
    self.attachments = attachments
    self.id = id
    self.decision = decision
    self.selection = selection
  }
}

public actor FakeAgent: AgentBridge {
  public private(set) var calls: [BridgeCall] = []
  private var stash: [TaskAttachment] = []
  private var forwardFails = false
  private var resetOutcome: ResetOutcome = .ok
  private var resolveFails = false

  public init() {}

  public func setResetOutcome(_ outcome: ResetOutcome) { resetOutcome = outcome }
  public func setResolveFails(_ fails: Bool) { resolveFails = fails }

  /// The next immediate forward fails — the machine is unreachable, or the write is rejected.
  public func failForwardTask() { forwardFails = true }

  /// A glasses capture landing on the bridge, waiting for whatever writes next.
  public func addPendingAttachment(_ attachment: TaskAttachment) { stash.append(attachment) }

  public func forwardTask(text: String, attachments: [TaskAttachment]?) async throws -> String {
    calls.append(
      BridgeCall(method: "forwardTask", text: text, attachments: attachments?.map(\.name) ?? nil))
    if forwardFails { throw FakeBridgeError.forwardFailed }
    return "S-test"
  }

  public func takePendingAttachments() async -> [TaskAttachment] {
    calls.append(BridgeCall(method: "takePendingAttachments"))
    let taken = stash
    stash = []
    return taken
  }

  public func steer(text: String) async throws {
    calls.append(BridgeCall(method: "steer", text: text))
  }

  public func abort() async throws {
    calls.append(BridgeCall(method: "abort"))
  }

  public func resetSession() async -> ResetOutcome {
    calls.append(BridgeCall(method: "resetSession"))
    return resetOutcome
  }

  public func resolveApproval(
    id: String,
    decision: ApprovalDecision,
    selection: ApprovalSelection?
  ) async throws {
    calls.append(
      BridgeCall(
        method: "resolveApproval",
        id: id,
        decision: decision.rawValue,
        selection: selection?.selections))
    if resolveFails { throw FakeBridgeError.resolveRejected }
  }
}

public enum FakeBridgeError: Error {
  case forwardFailed
  case resolveRejected
}

public actor FakeChannel: VoiceChannel {
  /// Heard by the user, verbatim.
  public private(set) var spoken: [String] = []
  /// The subject tag on each spoken line, in step with `spoken`.
  public private(set) var supersedeTags: [String?] = []
  /// Reaches the model as context; never voiced.
  public private(set) var instructed: [String] = []

  public init() {}

  /// `supersedes` is recorded, not applied: the gate is what replaces, and this fake is below it.
  public func say(text: String, supersedes: String?) async {
    spoken.append(text)
    supersedeTags.append(supersedes)
  }

  public func instruct(text: String) async {
    instructed.append(text)
  }
}

/// A scripted brain: each input yields whatever the script says.
public struct FakeEngine: DecisionEngine {
  let script: @Sendable (DecisionInput, ConciergeState) -> [Effect]

  public init(_ script: @escaping @Sendable (DecisionInput, ConciergeState) -> [Effect]) {
    self.script = script
  }

  public func decide(input: DecisionInput, state: ConciergeState) async -> [Effect] {
    script(input, state)
  }
}

/// A timer with a virtual clock, so `advanceMs` steps are deterministic.
///
/// DIFFERENCE FROM THE KOTLIN, and it is forced. There, `advance()` invokes the due action
/// synchronously and the action does `runBlocking { concierge.onApprovalTimeoutWarning() }`. Swift
/// cannot block a thread waiting on an actor, so `advance()` returns HOW MANY timers came due and the
/// replay runner awaits the FSM once per firing. That is faithful because the FSM schedules exactly one
/// kind of timer — the approval pre-expiry ping, whose production callback is
/// `onApprovalTimeoutWarning` and nothing else. `assertOnlyApprovalTimersScheduled` pins that
/// assumption so it cannot quietly stop being true.
public final class VirtualTimer: DelayTimer, @unchecked Sendable {
  private struct Entry {
    let id: Int
    let dueAt: Int64
    let action: @Sendable () -> Void
  }

  private struct State {
    var now: Int64 = 0
    var entries: [Entry] = []
    var nextId = 0
    var everScheduled = 0
  }

  private let state = OSAllocatedUnfairLock(initialState: State())

  public init(now: Int64 = 0) {
    state.withLock { $0.now = now }
  }

  public var now: Int64 { state.withLock { $0.now } }
  public var pending: Int { state.withLock { $0.entries.count } }
  public var everScheduled: Int { state.withLock { $0.everScheduled } }

  public func schedule(delayMs: Int64, action: @escaping @Sendable () -> Void) -> TimerCancellable {
    let id = state.withLock { s -> Int in
      let id = s.nextId
      s.nextId += 1
      s.everScheduled += 1
      s.entries.append(Entry(id: id, dueAt: s.now + delayMs, action: action))
      return id
    }
    return VirtualCancellable { [state] in
      state.withLock { s in s.entries.removeAll { $0.id == id } }
    }
  }

  /// Move the clock. Returns how many timers came due — the caller drives the FSM that many times.
  @discardableResult
  public func advance(_ ms: Int64) -> Int {
    let due = state.withLock { s -> [Entry] in
      s.now += ms
      let due = s.entries.filter { $0.dueAt <= s.now }
      s.entries.removeAll { $0.dueAt <= s.now }
      return due
    }
    // The actions themselves are no-ops in the replay (see the type comment); firing them keeps any
    // future non-approval timer honest rather than silently skipped.
    for entry in due { entry.action() }
    return due.count
  }
}

private struct VirtualCancellable: TimerCancellable {
  let onCancel: @Sendable () -> Void
  func cancel() { onCancel() }
}
