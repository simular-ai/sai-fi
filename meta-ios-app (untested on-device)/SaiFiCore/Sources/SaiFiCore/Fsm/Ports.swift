/* sai-fi — voice concierge. */

// The two seams the FSM talks through, and the agent's event union.
//
// AgentBridge is the write side (start work, hold it, cancel it, resolve an approval); VoiceChannel
// is how anything reaches the user. Keeping them protocols is what lets the golden scenarios drive
// the whole state machine against fakes, with no network and no MWDAT.
//
// `say` and `instruct` are NOT interchangeable and the difference is audible — see VoiceChannel.
//
// Ported from the Android `fsm/Ports.kt`, which came from cloud-api
// `services/concierge/voice/ports/`. The comments explaining WHY a rule exists came with it — most
// record a failure heard on a real device, and dropping them is how the rule gets "tidied" back
// into the bug.

import Foundation

/// Agent status values. `summarizing` and `aborting` both still count as working.
public enum AgentStatus: String, Sendable, CaseIterable {
  case processing
  case summarizing
  case aborting
  case idle
  case error

  /// Nil for anything unrecognised — the caller drops it rather than guessing.
  public static func fromWire(_ v: String?) -> AgentStatus? {
    guard let v else { return nil }
    return AgentStatus(rawValue: v)
  }
}

/// One question on a `choice` card, with what it offered.
public struct ApprovalQuestion: Sendable, Equatable {
  public let options: [ApprovalOption]
  public let multiple: Bool
  public let allowOther: Bool

  public init(options: [ApprovalOption], multiple: Bool = false, allowOther: Bool = false) {
    self.options = options
    self.multiple = multiple
    self.allowOther = allowOther
  }
}

/// Agent → user events, as the FSM sees them.
public enum AgentEvent: Sendable, Equatable {
  /// Streamed assistant answer text (final-turn).
  case text(String)

  /// Mid-turn narration / tool progress. Deliberately NOT surfaced to the user.
  ///
  /// `failed` marks a STEP that failed while the task carries on — not an `error`, which is
  /// terminal, but the one kind of progress the concierge must hear about: without it Sai has no
  /// idea anything went wrong and fills the silence with a result it never received.
  case progress(text: String, tool: String? = nil, failed: Bool = false)

  case approvalRequest(ApprovalRequestPayload)

  /// A pending request was resolved out-of-band (the app, or another channel).
  case approvalResolved(id: String, status: String)

  case status(AgentStatus)

  case complete(summary: String? = nil)

  case error(String)

  /// The FSM's own projection, echoed back for the client's activity log.
  case sessionState(running: String? = nil, blockedOn: String? = nil, queued: [String] = [])

  /// A system reply from the router about DELIVERY, not about the work — the machine was
  /// hibernated and is waking, the agent is offline, the linked machine is gone.
  ///
  /// Not an `error` (nothing failed) and not `progress` (silent by design), so it is its own kind:
  /// the one thing that must be relayed before the task has produced anything at all. The voice
  /// channel's reply used to be a no-op sink, so a woken VM meant a silent minute with no
  /// explanation.
  ///
  /// `kind == "stalled"` means the agent never picked the task up. Kept apart from an ordinary
  /// delivery notice because the two need different WORDING: that one is about the user's machine,
  /// and a model told only the text describes *itself* as offline instead.
  case notice(text: String, kind: String? = nil)
}

/// The payload of `AgentEvent.approvalRequest`.
///
/// A struct rather than a long associated-value list, because eight of these positionally is how a
/// field gets swapped for its neighbour.
public struct ApprovalRequestPayload: Sendable, Equatable {
  public let id: String
  public let title: String
  public let description: String
  public let approvalType: String
  public let isLinkOnly: Bool

  // No `allowAlways`. The server never sent one — `data-approval-request` stopped carrying it when
  // cloud-api ADR 0014 retired the `approved_always` Grant — so this field was read from an absent
  // key, defaulted to false, and then gated a prompt line offering to stop the asking.
  // See `Effect.approve`.

  /// Present for `select` approvals — every option across every question, flattened.
  public let options: [ApprovalOption]?

  /// The same options still grouped BY QUESTION, when the card asks more than one thing.
  ///
  /// `options` is what the model picks from and what gets read back, and a spoken pick carries no
  /// question index — but the agent resolves a choice positionally, one group per question. This is
  /// the only thing that can put a flat answer back into the right slots. See `groupSelections`.
  public let questions: [ApprovalQuestion]?

  public let multiple: Bool?

  /// Whether the select also accepts a free-form "something else" answer.
  public let allowOther: Bool?

  /// When the request auto-expires (ms epoch), for the pre-timeout ping.
  public let expiresAt: Int64?

  public init(
    id: String,
    title: String,
    description: String,
    approvalType: String,
    isLinkOnly: Bool,
    options: [ApprovalOption]? = nil,
    questions: [ApprovalQuestion]? = nil,
    multiple: Bool? = nil,
    allowOther: Bool? = nil,
    expiresAt: Int64? = nil
  ) {
    self.id = id
    self.title = title
    self.description = description
    self.approvalType = approvalType
    self.isLinkOnly = isLinkOnly
    self.options = options
    self.questions = questions
    self.multiple = multiple
    self.allowOther = allowOther
    self.expiresAt = expiresAt
  }
}

/// How the concierge resolves a pending approval.
///
/// The wire values are the agent API's `response` field, which is a plain yes/no — not the
/// `approved` / `denied` status the approval doc ends up carrying.
///
/// `always` is gone. The endpoint still accepts it and folds it into a one-time approve, which is
/// precisely why sending it was wrong: the call succeeded, nothing persisted, and the user had been
/// told the asking would stop.
public enum ApprovalDecision: String, Sendable {
  case approved = "yes"
  case denied = "no"
}

/// How to resolve a `choice` approval: the picked values, ONE GROUP PER QUESTION, in the card's own
/// order.
///
/// Positional, and the agent requires a non-empty group for every question — a partial answer is
/// refused rather than half-applied. That refusal is the desired outcome: it surfaces as a rejected
/// resolution the model is told to re-present, instead of a card approved with a question silently
/// unanswered.
public struct ApprovalSelection: Sendable, Equatable {
  public let selections: [[String]]
  public init(selections: [[String]]) { self.selections = selections }
}

public enum ResetOutcome: Sendable, Equatable {
  case ok
  case rateLimited
  case failed
}

public protocol AgentBridge: Sendable {
  /// Forward a new task; returns the agent chat session it landed in.
  ///
  /// `attachments` is for a task that was HELD: a queued task carries the photos captured for it,
  /// because by the time it drains the adapter's own stash may hold someone else's. Pass nil on the
  /// immediate path and the adapter drains its stash as before.
  func forwardTask(text: String, attachments: [TaskAttachment]?) async throws -> String

  /// Detach the photos captured for the task about to be queued, so nothing later picks them up.
  ///
  /// The stash is drained by whatever writes next. That is right when the write happens immediately
  /// and wrong the moment a task is held — the photo would drain with the wrong request attached.
  func takePendingAttachments() async -> [TaskAttachment]

  /// Send a mid-turn message to steer / supply input to a running turn.
  func steer(text: String) async throws

  /// Abort the running turn. Has no scope — it stops every request in flight.
  func abort() async throws

  /// Rotate onto a fresh chat session.
  ///
  /// Returns why it didn't happen rather than throwing: the two failures the user needs told apart
  /// are "you've done this a lot lately" and "it broke".
  func resetSession() async -> ResetOutcome

  func resolveApproval(
    id: String,
    decision: ApprovalDecision,
    selection: ApprovalSelection?
  ) async throws
}

extension AgentBridge {
  /// The immediate path — no held attachments to carry.
  public func forwardTask(text: String) async throws -> String {
    try await forwardTask(text: text, attachments: nil)
  }

  public func resolveApproval(id: String, decision: ApprovalDecision) async throws {
    try await resolveApproval(id: id, decision: decision, selection: nil)
  }
}

/// How the concierge reaches the user, and the model driving them.
///
/// `say` and `instruct` are NOT interchangeable, and the difference is audible. The client wraps a
/// `say` in "say this to the user, verbatim", so anything sent that way is heard word for word: it
/// has to BE the sentence, never a description of what to do. Text meant for the model — "this
/// didn't work, here's what to do instead" — goes through `instruct`, which reaches it as context.
/// Sent the wrong way round, the user hears function names and stage directions read aloud.
public protocol VoiceChannel: Sendable {
  /// Speak within the active turn. LITERAL speech — the user hears it verbatim.
  ///
  /// `supersedes` names the SUBJECT this line is about, when a later line on the same subject
  /// should replace it rather than be spoken after it. A held line describes the state at the
  /// moment it was written, and by the time a turn ends that state may have moved on: forward a
  /// task while one is running and the user is told "I'll start that as soon as I'm done with X";
  /// ask for it to be moved up a breath later and they are told "starting on that now". Both are
  /// true when written, and the two of them read out together are a contradiction — which is
  /// exactly what the loop eval caught ("Got it — I'll start that as soon as I'm done with: check
  /// my email. Starting on that now, alongside what I'm already doing: book a table…").
  ///
  /// Only lines sharing a tag replace each other. Untagged speech, and speech about anything else,
  /// still accumulates: two unrelated facts both need saying.
  func say(text: String, supersedes: String?) async

  /// Tell the MODEL something — a correction, or a fact it needs before its next move. Reaches it
  /// as context, so the words themselves are never spoken; what the user hears is the model's own
  /// reply.
  func instruct(text: String) async
}

extension VoiceChannel {
  public func say(_ text: String) async { await say(text: text, supersedes: nil) }
}
