/* sai-fi — voice concierge. */

// The effect grammar: the bounded set of things the model is allowed to make happen.
//
// The conversation is open-ended; the capabilities are not. Every user utterance and every agent
// event cashes out as effects from this list, which is what makes the model's output validatable
// instead of trusted. `parseEffect` is that boundary — an effect the model invents, or gets the
// shape of wrong, is DROPPED rather than guessed at.
//
// Ported from the Android `fsm/Effects.kt`, which came from cloud-api
// `services/concierge/voice/core/effects.ts`. Parsing is deliberately tolerant in the same places
// and strict in the same places; the asymmetries are noted per-case below, because "cleaning them
// up" changes what the model can do.

import Foundation

public enum Effect: Sendable, Equatable {
  /// Speak to the user, verbatim.
  case say(text: String)

  /// Park on a user reply.
  ///
  /// A pure state signal — it does NOT speak. The client's Live model has already voiced the
  /// question; speaking it here would double it up and interrupt the model mid-sentence. The
  /// `question` payload is carried for the record and is not used by the FSM.
  case askAndWait(question: String, waitingFor: WaitReason)

  /// Start work. The only effect that ever begins a task.
  case forwardToAgent(text: String)

  /// Steer the running turn — an answer or a correction, not new work.
  case relayToAgent(answer: String)

  /// Approve the pending request, once.
  ///
  /// There is no `approveAlways` beside this any more. It resolved with `response: "always"`, which
  /// the server folds into exactly this — the `approved_always` Grant it used to write is retired
  /// (cloud-api ADR 0014). So the effect existed to make a promise nothing kept: the model offered
  /// to stop asking, the card was approved once, and the next identical request interrupted the user
  /// again. `parseEffect` folds a stray `approveAlways` here rather than dropping it.
  case approve

  /// `reason` is parsed and then never used — the agent is told `denied` and nothing else. Kept
  /// because the model supplies it and dropping it at parse time would silently change the wire
  /// contract.
  case deny(reason: String? = nil)

  /// Resolve a `choice` approval. Values are checked against what was offered.
  case chooseOption(values: [String])

  /// Hold a task in the FSM only — no durable doc, no agent traffic.
  case enqueue(task: String, urgency: Urgency)

  /// Stop the running turn, and — unless `scope` narrows it — the queue with it.
  ///
  /// The scope is the one place this grammar goes beyond cloud-api's; see `InterruptScope` for the
  /// cancellation nobody could express without it.
  case interrupt(scope: InterruptScope = .everything)

  /// Drop a waiting task. `task` nil means all of them.
  case cancelQueued(task: String? = nil)

  /// Start a waiting task now. `task` nil is only unambiguous when exactly one waits.
  case sendQueuedNow(task: String? = nil)

  case setState(mode: Mode)

  /// Rotate onto a fresh conversation. Refused while anything is outstanding.
  case resetSession

  case noop
}

/// Validate an untrusted `{ kind, ... }` object from the model into a typed `Effect`.
///
/// Returns nil on any shape or enum violation so the caller drops it rather than trusting it. An
/// unknown `kind` is nil too — a newer model inventing a capability does not get to exercise it.
public func parseEffect(_ raw: JsonObject?) -> Effect? {
  guard let raw else { return nil }
  switch raw.optString("kind") {
  case "say":
    guard let text = raw.str("text") else { return nil }
    return .say(text: text)

  case "askAndWait":
    // Strict on both halves: an unrecognised `waitingFor` rejects the whole call, because parking
    // on a reason the FSM cannot read means parking forever.
    guard
      let question = raw.str("question"),
      let waitingFor = WaitReason.fromWire(raw.str("waitingFor"))
    else { return nil }
    return .askAndWait(question: question, waitingFor: waitingFor)

  case "forwardToAgent":
    guard let text = raw.str("text") else { return nil }
    return .forwardToAgent(text: text)

  case "relayToAgent":
    guard let answer = raw.str("answer") else { return nil }
    return .relayToAgent(answer: answer)

  case "approve":
    return .approve

  // Folded, not rejected. The tool is no longer declared and the prompt no longer names it, but a
  // Live model improvises, and the two outcomes do not cost the same: folding resolves the card
  // once — exactly what the server does with `response: "always"` — while returning nil leaves the
  // approval pending and the model waiting on a decision it believes it made. The user gets their
  // approval either way; only the promise to stop asking is gone.
  case "approveAlways":
    return .approve

  case "chooseOption":
    // Non-strings and empty strings are filtered out rather than rejecting the call; nil only when
    // nothing survives. A partly-malformed pick list still resolves what it can.
    guard let arr = raw.optArray("values") else { return nil }
    let values = (0..<arr.count).compactMap { index -> String? in
      guard let value = arr.optStringStrict(index), !value.isEmpty else { return nil }
      return value
    }
    return values.isEmpty ? nil : .chooseOption(values: values)

  case "deny":
    return .deny(reason: raw.str("reason"))

  // An absent or unrecognised urgency defaults to normal — NOT a rejection.
  case "enqueue":
    guard let task = raw.str("task") else { return nil }
    return .enqueue(task: task, urgency: Urgency.fromWire(raw.str("urgency")))

  // An absent or unrecognised scope is `everything`, matching `enqueue`'s urgency: the wider reading
  // is what a bare "stop" means, so a scope this build does not know must not silently narrow the
  // cancellation and leave work running the user thinks they stopped.
  case "interrupt":
    return .interrupt(scope: InterruptScope.fromWire(raw.str("scope")))

  case "cancelQueued":
    return .cancelQueued(task: raw.str("task"))

  case "sendQueuedNow":
    return .sendQueuedNow(task: raw.str("task"))

  case "setState":
    // Strict, like askAndWait: a mode the FSM cannot read is not a mode it should enter.
    guard let mode = Mode.fromWire(raw.str("mode")) else { return nil }
    return .setState(mode: mode)

  case "resetSession":
    return .resetSession

  case "noop":
    return .noop

  default:
    return nil
  }
}

/// Validate a batch, dropping any malformed effects. A nil input yields an empty list.
public func parseEffects(_ raw: JsonArray?) -> [Effect] {
  guard let raw else { return [] }
  return (0..<raw.count).compactMap { parseEffect(raw.optObject($0)) }
}
