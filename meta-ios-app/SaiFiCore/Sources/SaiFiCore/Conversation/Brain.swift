/* sai-fi — voice concierge. */

// The model, behind one seam.
//
// ScriptedBrain is deterministic and free, and is what gates CI. A live brain calling the real
// model slots in behind the same interface, which is the point of having one: the scenarios do not
// know which is behind it, so the same conversation can be run for its structure (deterministic) and
// for its wording (judged).
//
// A brain sees the FSM's state because the real one effectively does — the model is told what is
// running and what is waiting via `session-state` and `getSaiStatus`, so a scripted brain that had
// to answer "what's going on?" blind would be a worse model than the real one, not a simpler one.
//
// Ported from Android `conversation/Brain.kt`.

import Foundation
import os

/// One model turn: what it says out loud, and what it calls.
///
/// Both are optional and the combinations are meaningful. Speech with no calls is a plain reply;
/// calls with no speech is the silent forward the prompt asks for; neither is a correctly empty turn
/// (an overheard remark, or a muted turn).
public struct BrainTurn: Sendable {
  public var speech: String?
  public var calls: JsonArray
  public init(speech: String? = nil, calls: JsonArray = JsonArray([])) {
    self.speech = speech
    self.calls = calls
  }
}

public protocol Brain: Sendable {
  func turn(input: String, state: ConciergeState) async -> BrainTurn
}

/// `fc("forwardToAgent", ["text": "check my email"])` — one function call, as the model emits it.
func fc(_ name: String, _ args: [String: Any] = [:]) -> JsonObject {
  var raw: [String: Any] = ["name": name]
  if !args.isEmpty { raw["args"] = args }
  return jsonWire(raw)
}

func callsOf(_ calls: JsonObject...) -> JsonArray {
  jsonArrayWire(calls.map(\.raw))
}

/// A brain whose rules are tried in order, first match wins.
///
/// Matching on the input rather than a fixed turn sequence, because the loop decides how many turns
/// there are: a nudge the gate held and released later arrives as an extra input that no fixed script
/// could have predicted the position of.
public final class ScriptedBrain: Brain, @unchecked Sendable {
  public struct Rule: Sendable {
    public let match: @Sendable (String) -> Bool
    public let reply: @Sendable (String, ConciergeState) -> BrainTurn
    public init(
      match: @escaping @Sendable (String) -> Bool,
      reply: @escaping @Sendable (String, ConciergeState) -> BrainTurn
    ) {
      self.match = match
      self.reply = reply
    }
  }

  private let rules: [Rule]
  private let seenLock = OSAllocatedUnfairLock(initialState: [String]())

  public init(_ rules: [Rule] = []) { self.rules = rules }

  /// Inputs the brain was given, in order — including every nudge, which is often the assertion.
  public var seen: [String] { seenLock.withLock { $0 } }

  public func turn(input: String, state: ConciergeState) async -> BrainTurn {
    seenLock.withLock { $0.append(input) }
    if let rule = rules.first(where: { $0.match(input) }) {
      return rule.reply(input, state)
    }
    // The FSM's `say` reaches the model wrapped in "say this verbatim", and a real model says it.
    // Built in rather than left to each scenario because it is mechanical, not a judgment: a test
    // that had to restate it every time would be restating the contract LiveVoiceChannel already has.
    if let said = Self.verbatim(input) {
      return BrainTurn(speech: said)
    }
    return BrainTurn()
  }

  /// Nudges the model was told about, by their `[agent]` / `[system]` prefix.
  public func sawNudgeContaining(_ fragment: String) -> Bool {
    seen.contains { $0.contains(fragment) }
  }

  /// The text inside LiveVoiceChannel's verbatim wrapper, or nil if this is not one.
  public static func verbatim(_ input: String) -> String? {
    let pattern = #"Say to the user, briefly and verbatim: "(.*)""#
    guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
    else { return nil }
    let range = NSRange(input.startIndex..., in: input)
    guard let match = regex.firstMatch(in: input, options: [], range: range),
          match.numberOfRanges >= 2,
          let inner = Range(match.range(at: 1), in: input)
    else { return nil }
    return String(input[inner])
  }

  public static func of(_ rules: Rule...) -> ScriptedBrain { ScriptedBrain(rules) }

  /// An input the client injected rather than something the user said.
  ///
  /// The distinction is load-bearing for test authors, not decoration. A completion nudge quotes the
  /// agent's summary back at the model, so a rule matching "email" written to catch the user asking
  /// about email ALSO catches the nudge reporting "3 new emails" — and replies by forwarding the
  /// task again, forever. That is a bug in the test, not in the product, and it is easy enough to
  /// write that the two kinds of input are kept apart here rather than in every scenario.
  public static func isNudge(_ input: String) -> Bool {
    input.hasPrefix("[agent]") || input.hasPrefix("[system]")
  }

  /// `on({ $0.contains("email") }) { … }` — matches any input, nudges included.
  public static func on(
    _ match: @escaping @Sendable (String) -> Bool,
    reply: @escaping @Sendable (String, ConciergeState) -> BrainTurn
  ) -> Rule {
    Rule(match: match, reply: reply)
  }

  /// Match something the USER said, case-insensitively. Never matches an injected nudge.
  public static func whenSaid(
    _ fragment: String,
    reply: @escaping @Sendable (String, ConciergeState) -> BrainTurn
  ) -> Rule {
    Rule(
      match: { input in
        !isNudge(input) && input.range(of: fragment, options: [.caseInsensitive]) != nil
      },
      reply: reply)
  }

  /// Match a nudge the client injected — a completion, an approval, a mute.
  public static func whenNudged(
    _ fragment: String,
    reply: @escaping @Sendable (String, ConciergeState) -> BrainTurn
  ) -> Rule {
    Rule(
      match: { input in
        isNudge(input) && input.range(of: fragment, options: [.caseInsensitive]) != nil
      },
      reply: reply)
  }

  /// Match any injected nudge.
  public static func whenNudged(
    reply: @escaping @Sendable (String, ConciergeState) -> BrainTurn
  ) -> Rule {
    Rule(match: { isNudge($0) }, reply: reply)
  }
}
