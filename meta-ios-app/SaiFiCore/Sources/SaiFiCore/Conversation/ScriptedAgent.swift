/* sai-fi — voice concierge. */

// An agent on the other end of the real bridge.
//
// It implements VoiceTransport, the seam UNDER HttpAgentBridge, so the bridge itself, its six
// methods mapping onto four `/v1/agents/*` operations, and the event plumbing are all the real ones.
// Faking AgentBridge instead would bypass exactly the layer a wire bug lives in.
//
// **What it replies is a function of what was forwarded**, not a fixed script the test wrote out in
// advance. That is the whole point: the golden catalog already pins "given these events, the FSM does
// this", and it cannot catch a drain that never fires, because the events it replays were written by
// hand on the assumption that it did. Here a queued task produces events only if something actually
// forwards it, so "the drain fired and the result reached the user" is an emergent property.
//
// It runs ONE task at a time, like the real thing: a machine has one agent. A forward arriving while
// one is running is recorded as such, which is how the queue's correctness becomes observable.
//
// Ported from Android `conversation/ScriptedAgent.kt`.

import Foundation
import os

/// One thing the agent does, some milliseconds into a turn.
public struct AgentBeat: Sendable {
  public let afterMs: Int64
  public let event: AgentEvent
  public init(afterMs: Int64, event: AgentEvent) {
    self.afterMs = afterMs
    self.event = event
  }
}

/// How the agent behaves for a task whose text matches `match`.
///
/// `beats` are relative to the moment the task starts. The last one is normally a `complete`, and a
/// programme with no terminal event models a task that never finishes — which is a case worth having.
public struct AgentProgram: Sendable {
  public let match: @Sendable (String) -> Bool
  public let beats: [AgentBeat]
  public init(match: @escaping @Sendable (String) -> Bool, beats: [AgentBeat]) {
    self.match = match
    self.beats = beats
  }
}

/// Everything a scenario can assert about what reached the agent.
public struct AgentCall: Sendable {
  public let method: String
  public let text: String?
  public let body: JsonObject?
  public init(method: String, text: String? = nil, body: JsonObject? = nil) {
    self.method = method
    self.text = text
    self.body = body
  }
}

public final class ScriptedAgent: VoiceTransport, @unchecked Sendable {
  private let clock: HarnessClock
  private let deliver: @Sendable (AgentEvent) async -> Void
  private let log: @Sendable (String) -> Void

  private struct Bits {
    var programs: [AgentProgram] = []
    var fallback: [AgentBeat] = [
      AgentBeat(afterMs: 50, event: .status(.processing)),
      AgentBeat(afterMs: 500, event: .complete(summary: "done")),
    ]
    var calls: [AgentCall] = []
    var started: [String] = []
    var failNextSend = false
    var abortStopsTheRun = true
    var producedAfterAbandon = 0
    var deliveriesAfterAbandon = 0
    var following = false
    var abandoned = false
    var overlapped: [String] = []
    var running: String?
    var runningBeats: [TimerCancellable] = []
  }

  private let bits = OSAllocatedUnfairLock(initialState: Bits())

  public init(
    clock: HarnessClock,
    deliver: @escaping @Sendable (AgentEvent) async -> Void,
    log: @escaping @Sendable (String) -> Void = { _ in }
  ) {
    self.clock = clock
    self.deliver = deliver
    self.log = log
  }

  /// Programmes are tried in order; the first match wins.
  public var programs: [AgentProgram] {
    get { bits.withLock { $0.programs } }
    set { bits.withLock { $0.programs = newValue } }
  }

  /// A default for anything unmatched: acknowledge, then finish.
  public var fallback: [AgentBeat] {
    get { bits.withLock { $0.fallback } }
    set { bits.withLock { $0.fallback = newValue } }
  }

  public var calls: [AgentCall] { bits.withLock { $0.calls } }
  public var started: [String] { bits.withLock { $0.started } }
  public var overlapped: [String] { bits.withLock { $0.overlapped } }

  public var failNextSend: Bool {
    get { bits.withLock { $0.failNextSend } }
    set { bits.withLock { $0.failNextSend = newValue } }
  }

  /// Whether `POST abort` actually stops the run — the SERVER's half of a stop, and not a given.
  ///
  /// True is the contract as documented. False is what a device log showed on 2026-08-20: a 2xx came
  /// back and the agent carried on through two more tool calls and a full answer. A double that can
  /// only model the happy version cannot be used to prove the client survives the other one.
  public var abortStopsTheRun: Bool {
    get { bits.withLock { $0.abortStopsTheRun } }
    set { bits.withLock { $0.abortStopsTheRun = newValue } }
  }

  /// Events the agent PRODUCED after this device stopped following — whether they got through or not.
  public var producedAfterAbandon: Int { bits.withLock { $0.producedAfterAbandon } }

  /// Of those, the ones that actually reached the FSM. Must stay at zero.
  public var deliveriesAfterAbandon: Int { bits.withLock { $0.deliveriesAfterAbandon } }

  /// True while a task is in flight, so a scenario can assert the agent really is busy.
  public var isBusy: Bool { bits.withLock { $0.running != nil } }

  public func sendMessage(
    machineId: String,
    message: String,
    attachments: JsonArray?,
    follow: Bool
  ) async throws {
    bits.withLock {
      $0.calls.append(AgentCall(method: follow ? "forward" : "steer", text: message))
    }
    if bits.withLock({ $0.failNextSend }) {
      bits.withLock { $0.failNextSend = false }
      log("[agent] refusing \(message)")
      throw ConciergeHttpException(status: 503, message: "the machine is unreachable")
    }
    // A steer lands in a turn already being read: it produces no stream of its own, which is exactly
    // why HttpAgentBridge passes follow=false for it.
    if !follow { return }
    let task = requestOf(message)
    if bits.withLock({ $0.running != nil }) {
      bits.withLock { $0.overlapped.append(task) }
    }
    startTask(task)
    // Returns once ACCEPTED, not once done — the FSM holds its mutex across this call and needs to be
    // out of it before the events this task is about to produce arrive. Scheduling them on the clock
    // rather than delivering them here is what reproduces that.
  }

  /// The user's words, with the context the bridge appends stripped back off.
  ///
  /// `taskText` fences everything that is not the request into `[Context, …]` blocks after a blank
  /// line — the clock on every task, the location fix on the ones that asked for it. Recording the
  /// whole envelope as the task would make `started` and `overlapped` answer a question no scenario
  /// is asking, and would break every one of them the day a new block is added. So the double records
  /// what the user said, which is what "the agent started this task" is supposed to mean.
  ///
  /// Program matching still runs against the FULL message on purpose: a program that wants to key on
  /// something in the envelope can, and a `contains` on the request works either way.
  private func requestOf(_ message: String) -> String {
    let cut = message.components(separatedBy: "\n\n[Context,").first ?? message
    return cut.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  /// The CLIENT's half of a stop: this device is no longer listening to the turn.
  ///
  /// Deliberately does nothing to the run — the agent's beats go on being produced, exactly as a
  /// server that ignored the abort would. They simply stop reaching the FSM, and each one that would
  /// have is counted so a scenario can insist the silence is real rather than incidental.
  ///
  /// Keeping this separate from `post("abort")` is the point. Conflating the two is how the original
  /// bug hid: this double stopped the run and the reader in one atomic free action, so "an aborted
  /// task is not reported as done" passed while production did neither.
  public func abandonTurn() {
    let wasFollowing = bits.withLock { $0.following }
    if !wasFollowing { return }
    log("[agent] client stopped following the turn")
    bits.withLock {
      $0.abandoned = true
      $0.following = false
    }
  }

  public func post(path: String, body: JsonObject) async throws -> JsonObject {
    let abortStops = bits.withLock { state -> Bool in
      state.calls.append(AgentCall(method: path, body: body))
      return state.abortStopsTheRun
    }
    if path == "abort" && abortStops { abortRunning() }
    switch path {
    case "new-session": return JsonObject(["sessionId": "S-harness"])
    case "abort": return jsonWire(["aborted": abortStops])
    default: return jsonWire(["ok": true])
    }
  }

  private func startTask(_ text: String) {
    bits.withLock {
      $0.running = text
      $0.started.append(text)
      $0.following = true
      $0.abandoned = false
    }
    log("[agent] started: \(text)")
    let beats = bits.withLock { state in
      state.programs.first(where: { $0.match(text) })?.beats ?? state.fallback
    }
    let handles: [TimerCancellable] = beats.map { beat in
      clock.scheduleSuspending(delayMs: beat.afterMs) { [weak self] in
        guard let self else { return }
        if Self.isTerminal(beat.event) {
          self.bits.withLock { $0.running = nil }
          self.log("[agent] finished: \(text)")
        }
        let (abandoned, following) = self.bits.withLock { ($0.abandoned, $0.following) }
        if abandoned {
          self.bits.withLock { $0.producedAfterAbandon += 1 }
        }
        if !following {
          self.log("[agent] produced \(eventKindForLog(beat.event)) — nobody is listening")
          return
        }
        if abandoned {
          self.bits.withLock { $0.deliveriesAfterAbandon += 1 }
        }
        await self.deliver(beat.event)
      }
    }
    bits.withLock { $0.runningBeats = handles }
  }

  /// Stop the running task without finishing it — what `abort` does.
  ///
  /// Cancels its remaining beats rather than letting them land, because an aborted task does not go
  /// on emitting progress and then report itself complete. Reporting a stopped task as finished is a
  /// failure the golden catalog already names (`abort ≠ done`), and an agent double that kept
  /// emitting would manufacture it here.
  public func abortRunning() {
    let (handles, running) = bits.withLock { state -> ([TimerCancellable], String?) in
      let handles = state.runningBeats
      state.runningBeats = []
      let running = state.running
      state.running = nil
      return (handles, running)
    }
    handles.forEach { $0.cancel() }
    if let running { log("[agent] aborted: \(running)") }
  }

  public func callsTo(_ method: String) -> [AgentCall] {
    calls.filter { $0.method == method }
  }

  private static func isTerminal(_ event: AgentEvent) -> Bool {
    switch event {
    case .complete, .error: return true
    default: return false
    }
  }
}

func eventKindForLog(_ event: AgentEvent) -> String {
  switch event {
  case .text: return "Text"
  case .progress: return "Progress"
  case .approvalRequest: return "ApprovalRequest"
  case .approvalResolved: return "ApprovalResolved"
  case .status: return "Status"
  case .complete: return "Complete"
  case .error: return "Error"
  case .sessionState: return "SessionState"
  case .notice: return "Notice"
  }
}
