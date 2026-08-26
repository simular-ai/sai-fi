/* sai-fi — voice concierge. */

// The agent-orchestration FSM's state, and the pure transitions over it.
//
// This tracks what the concierge is doing *with respect to the agent* — idle, clarifying a task
// before forwarding it, waiting on the agent, negotiating priority. Conversational memory is NOT
// here: it lives in the Live model's own context.
//
// Everything in this file is pure. No tasks, no clock, no ports — a state and an input go in, a new
// state comes out. That is what makes the 63 golden scenarios runnable with no I/O at all, and it is
// the same split GlassesLink uses (pure steps + a thin driver on top).
//
// Ported from the Android `fsm/State.kt`, which came from cloud-api
// `services/concierge/voice/core/state.ts`. The comments explaining WHY a rule exists came with it —
// most of them record a failure seen on a real device, and dropping them is how the rule gets
// "tidied" back into the bug.

import Foundation

/// What the concierge is doing with respect to the agent.
public enum Mode: String, Sendable, CaseIterable {
  case idle
  case clarifying
  case working
  case awaitingUser = "awaiting-user"
  case negotiating

  public static func fromWire(_ v: String?) -> Mode? {
    guard let v else { return nil }
    return Mode(rawValue: v)
  }
}

/// Why we're awaiting a user reply — shapes how the next utterance is read.
public enum WaitReason: String, Sendable, CaseIterable {
  case clarification
  case approval
  case input
  case urgency

  public static func fromWire(_ v: String?) -> WaitReason? {
    guard let v else { return nil }
    return WaitReason(rawValue: v)
  }
}

public enum Urgency: String, Sendable, CaseIterable {
  case normal
  case urgent

  /// An absent or unrecognised urgency is `normal` — never a rejection. Mirrors `parseEffect`.
  public static func fromWire(_ v: String?) -> Urgency {
    guard let v, let parsed = Urgency(rawValue: v) else { return .normal }
    return parsed
  }
}

/// How much an `interrupt` stops.
///
/// A divergence from cloud-api, which has only the unscoped abort. It exists because there was no
/// way to say the commonest cancellation of all: DROP THIS ONE AND CARRY ON. `interrupt` took the
/// queue with it, `cancelQueued` cannot touch a running task, and `relayToAgent` only narrows a task
/// from the inside — so a user who said "forget it" over a running task had nothing that meant it,
/// and the model reached for `resetSession`, which wipes the conversation and stops no work at all.
///
/// `.everything` stays the default, because "stop" with no qualifier means stop, and an absent or
/// unrecognised scope must not quietly become the narrower reading.
public enum InterruptScope: String, Sendable, CaseIterable {
  /// Abort the running turn AND drop everything waiting behind it.
  case everything
  /// Abort the running turn only. The waiting list survives, and the next task starts by itself.
  case running

  public static func fromWire(_ v: String?) -> InterruptScope {
    let normalised = v?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard let normalised, let parsed = InterruptScope(rawValue: normalised) else { return .everything }
    return parsed
  }
}

/// A photo captured for a task, bound to it rather than left on the bridge.
public struct TaskAttachment: Sendable, Equatable {
  public let path: String
  public let name: String
  public let mime: String
  public let size: Int64
  public let downloadUrl: String?
  public let fileId: String?
  public let width: Int?
  public let height: Int?

  public init(
    path: String,
    name: String,
    mime: String,
    size: Int64,
    downloadUrl: String? = nil,
    fileId: String? = nil,
    width: Int? = nil,
    height: Int? = nil
  ) {
    self.path = path
    self.name = name
    self.mime = mime
    self.size = size
    self.downloadUrl = downloadUrl
    self.fileId = fileId
    self.width = width
    self.height = height
  }
}

public struct QueuedTask: Sendable, Equatable {
  public let text: String
  public let urgency: Urgency

  /// The photos captured for THIS task, taken off the bridge when it was queued.
  ///
  /// A held task cannot leave its photo in the bridge's stash: the stash is drained by whatever
  /// writes next, so the picture would either ride someone else's request or come back with a second
  /// one attached. Binding it here is what makes "queued" safe for a vision task.
  public let attachments: [TaskAttachment]?

  public init(text: String, urgency: Urgency, attachments: [TaskAttachment]? = nil) {
    self.text = text
    self.urgency = urgency
    self.attachments = attachments
  }
}

/// An option offered by a pending `choice` request.
public struct ApprovalOption: Sendable, Equatable {
  public let value: String
  public let label: String
  public init(value: String, label: String) {
    self.value = value
    self.label = label
  }
}

public struct ConciergeState: Sendable, Equatable {
  public var mode: Mode = .idle

  /// Agent approval awaiting a spoken decision.
  public var pendingApprovalId: String?

  /// What the pending request actually asks, so it can be put back to the user.
  ///
  /// The question is voiced once, when the request arrives. If the user then says something that is
  /// not an answer to it, the only way to re-ask is to still have it — otherwise the model
  /// re-invents the question, or drops it.
  public var pendingApprovalPrompt: String?

  /// The options a pending `choice` request offered — values guard `chooseOption`, and the LABELS
  /// are what gets read back when the user asks what the choices are.
  ///
  /// Values alone were kept here until 2026-07-31, which is half of why "what are the options?"
  /// became a round-trip to an agent that was blocked on this very request: there was nothing
  /// human-readable to answer from.
  public var pendingApprovalOptions: [ApprovalOption]?

  /// The pending `choice` request's questions, when it asks more than one thing.
  ///
  /// Held only so a flat spoken answer can be put back into per-question groups on the way out;
  /// nothing else reads it. Nil for the ordinary single-question card, where the flat list IS the
  /// grouping.
  public var pendingApprovalQuestions: [ApprovalQuestion]?

  /// Whether the pending `choice` accepts a free-form "something else" answer.
  public var pendingApprovalAllowOther: Bool?

  /// The pending approval is link-only (browser-completed) — not voice-resolvable.
  public var pendingApprovalLinkOnly: Bool?

  /// The pending approval's type, so we can tell the two kinds of "link-only" apart.
  /// `service_connect` / `service_auth` genuinely need a browser; `user_input` only needs TEXT,
  /// which a voice session supplies fine — see `relayResolvesApproval`.
  public var pendingApprovalType: String?

  /// What the next user utterance answers, when mode is awaiting-user-ish.
  public var awaiting: WaitReason?

  /// Tasks held while the agent is busy.
  public var queue: [QueuedTask] = []

  /// The user's requests forwarded into the CURRENT agent turn, oldest first — i.e. exactly what an
  /// `interrupt` would stop. Every forward lands in the same chat session (a machine has one), so
  /// one turn routinely carries several separate requests, while `abort()` has no scope and stops
  /// all of them. Tracking them is what lets the FSM notice that a "cancel" is ambiguous before it
  /// destroys work the user never mentioned.
  public var inFlight: [String] = []

  /// We've already asked the user whether a cancellation means everything or just one thing, so the
  /// next `interrupt` is them having answered "everything" — it goes through.
  public var interruptScopeAsked: Bool?

  /// We've already put "do you want to wipe the whole conversation?" to the user, so the next
  /// `resetSession` is them having said yes.
  ///
  /// The same one-shot shape as `interruptScopeAsked`, and for a sharper reason: a rotation cannot be
  /// undone. "Forget it", "never mind" and "drop that" are nearly always about the last thing said,
  /// yet they share their vocabulary with "forget everything we talked about" — and on device a bare
  /// "forget it" was heard as the second one and cleared the conversation. A misrouted dismissal now
  /// costs one question instead of the session.
  public var resetConfirmAsked: Bool?

  /// The last turn was ABORTED, so anything still arriving from it is about work the user cancelled.
  ///
  /// The abort is three-part and only two parts are ours (see `HttpAgentBridge.abort`): the device
  /// stops following the stream and stops its own work immediately, but the agent is merely ASKED to
  /// stop, over a round trip, and it does not stop mid-thought. A result already on its way arrives
  /// after the FSM has cleared the turn — and until this flag existed it was indistinguishable from a
  /// result the user was waiting for, so Sai read out the answer to the very question they had just
  /// told it to drop.
  ///
  /// Keyed on the abort rather than on "nothing is in flight", which was the first attempt and is
  /// wrong in a way that matters: `applyAgentStatus` ends a turn on a stray `idle` status too, so an
  /// empty `inFlight` is also what a perfectly live turn looks like for a moment. Suppressing on that
  /// would lose real results, and losing a result is the worse failure of the two.
  ///
  /// Cleared by `startTurn` and NOT by `endTurn`, which is the ordering the guard depends on: the
  /// terminal event of the aborted turn runs through ingest — and therefore `endTurn` — on its way to
  /// being suppressed, so an `endTurn` that cleared this would clear it with the event still in hand.
  /// A new task starting is the honest end of the window: from there the FSM is waiting on something
  /// again, and anything arriving is presumed to be that.
  public var abortedTurn: Bool = false

  public init() {}
}

// NOTE: there is deliberately NO sessionId here. Session identity belongs to the bridge — the id
// returned by forwardTask is discarded, and resetSession rotates it inside the bridge. A copy in the
// FSM would be a second answer to "which conversation is this?", free to disagree with the first.

public func initialState() -> ConciergeState { ConciergeState() }

extension ConciergeState {
  public func withMode(_ mode: Mode) -> ConciergeState {
    var next = self
    next.mode = mode
    return next
  }

  /// Enqueue a held task. Urgent tasks jump to the front of the queue.
  ///
  /// This list is the WHOLE truth about held work: nothing was written anywhere else, and nothing
  /// but this FSM will ever start these tasks. So the spoken order and the real order cannot
  /// disagree, and moving one up is just `sendQueuedNow` forwarding it.
  ///
  /// The flip side is that held work does not survive the call. See `hasOutstandingWork`.
  public func enqueue(
    text: String,
    urgency: Urgency = .normal,
    attachments: [TaskAttachment]? = nil
  ) -> ConciergeState {
    let item = QueuedTask(
      text: text,
      urgency: urgency,
      // Empty is stored as absent, matching the TS spread — an empty list and no list must not be
      // two different things downstream.
      attachments: (attachments?.isEmpty ?? true) ? nil : attachments
    )
    var next = self
    next.queue = urgency == .urgent ? [item] + queue : queue + [item]
    return next
  }

  /// Drop everything waiting, without running any of it.
  ///
  /// For "stop all of it": an abort ends the running turn, and the queue has to go with it, or the
  /// cancellation starts the next task instead of stopping anything. Separate from `endTurn` on
  /// purpose — a turn ending NORMALLY should still release the queue.
  public func clearQueue() -> ConciergeState {
    guard !queue.isEmpty else { return self }
    var next = self
    next.queue = []
    return next
  }

  /// Drop one waiting task by its position, for a scoped cancel of something not yet started.
  public func removeQueued(_ index: Int) -> ConciergeState {
    guard index >= 0, index < queue.count else { return self }
    var next = self
    next.queue.remove(at: index)
    return next
  }

  /// A task has actually reached the agent and is now running.
  ///
  /// One named transition instead of the copy that used to sit at each of the six sites where a task
  /// starts — those had DRIFTED: three cleared `awaiting` and `interruptScopeAsked`, three cleared
  /// neither or only one. Both leftovers are wrong once a new task is underway:
  /// - `awaiting` says the FSM is waiting on the user for something, while `mode` says it is
  ///   working. Nothing can be true of both.
  /// - `interruptScopeAsked` means "we already asked which task to stop". It was asked about the
  ///   PREVIOUS turn's requests, so carrying it into a new one lets the next `interrupt` abort fresh
  ///   work without asking — the exact failure `endTurn` clears it to prevent.
  /// - `resetConfirmAsked` is the same argument about a bigger button: it means "the user has been
  ///   asked whether to wipe the conversation". New work is a new context, and a stale yes would let
  ///   the next stray "forget it" rotate the session without a question.
  /// - `abortedTurn` closes the window in which a result is presumed to belong to cancelled work.
  ///   Once the FSM is waiting on something again, it has to believe what arrives — see
  ///   `ConciergeState`.
  ///
  /// Callers needing a different mode (e.g. a task that starts while parked) set it after; this is
  /// the common shape, not a straitjacket.
  public func startTurn(_ text: String) -> ConciergeState {
    var next = beginTask(text)
    next.mode = .working
    next.awaiting = nil
    next.interruptScopeAsked = nil
    next.resetConfirmAsked = nil
    next.abortedTurn = false
    return next
  }

  /// Record a request forwarded into the current agent turn.
  public func beginTask(_ text: String) -> ConciergeState {
    var next = self
    next.inFlight = inFlight + [text]
    return next
  }

  /// The turn is over (completed, errored, or aborted) — nothing is in flight any more.
  ///
  /// Clears the scope question with it: it was asked about THIS turn's requests, and a flag
  /// outliving them would let the next `interrupt` abort a fresh turn's work without asking.
  ///
  /// Deliberately does NOT touch `mode` or the queue.
  public func endTurn() -> ConciergeState {
    var next = self
    next.inFlight = []
    next.interruptScopeAsked = nil
    return next
  }

  /// True while the agent is actively working.
  public func isWorking() -> Bool { mode == .working }

  /// True when there is outstanding work, so the conversation must not be rotated away.
  ///
  /// Keyed on the three things that OUTLIVE a rotation rather than on `mode`: a running turn's
  /// stream belongs to the session being replaced, an unanswered approval belongs to the turn that
  /// raised it, and a queued task was promised to the user out loud. `mode` would be the wrong test
  /// — it can read `idle` with a task still queued, which is precisely the case that must refuse.
  ///
  /// The queue is in memory only, so it does not survive the CALL either. That is a real loss and it
  /// is deliberate: nothing the agent holds can be started behind the FSM's back, which is what the
  /// three races used to guard against. Dropping a call drops the promise with it.
  public func hasOutstandingWork() -> Bool {
    !inFlight.isEmpty || !queue.isEmpty || pendingApprovalId != nil
  }

  /// Everything describing the request we were waiting on, cleared in one go.
  ///
  /// One function rather than written out at each of the six sites that finish an approval: they had
  /// already drifted (the out-of-band branch never cleared `pendingApprovalType`), and a field left
  /// behind is a stale answer to "what is the user being asked?" — which is exactly what the re-ask
  /// nudge reads.
  public func noPendingApproval() -> ConciergeState {
    var next = self
    next.pendingApprovalId = nil
    next.pendingApprovalPrompt = nil
    next.pendingApprovalLinkOnly = nil
    next.pendingApprovalOptions = nil
    next.pendingApprovalQuestions = nil
    next.pendingApprovalAllowOther = nil
    next.pendingApprovalType = nil
    return next
  }
}

/// Put a flat list of picked values back into one group per question.
///
/// A value lands in the FIRST question that offered it, and each value is used once — so two
/// questions offering the same label do not both claim the same answer. Values nothing offered (a
/// free-text answer to an `allowOther` question) go to the first question that accepts free text,
/// and failing that to the first question, which is where a single-question card puts everything.
///
/// A question left with no pick yields an EMPTY group, deliberately. The agent refuses the whole
/// resolution rather than applying half of it, and that refusal is what gets the model to ask again.
/// Inventing a pick to make the shape valid would answer for the user.
public func groupSelections(values: [String], questions: [ApprovalQuestion]?) -> [[String]] {
  guard let questions, !questions.isEmpty else { return [values] }
  var groups = [[String]](repeating: [], count: questions.count)
  for value in values {
    let offered = questions.firstIndex { q in q.options.contains { $0.value == value } }
    let index: Int
    if let offered {
      index = offered
    } else {
      index = questions.firstIndex { $0.allowOther } ?? 0
    }
    groups[index].append(value)
  }
  return groups
}
