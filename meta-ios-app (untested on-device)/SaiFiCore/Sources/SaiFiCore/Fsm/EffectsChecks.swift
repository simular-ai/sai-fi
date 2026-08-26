/* sai-fi — voice concierge. */

// The parse boundary. Everything here is about what the model is NOT allowed to make happen: this is
// the one place an invented capability or a malformed payload gets dropped instead of trusted.
//
// Ported from `fsm/EffectsTest.kt`.

import Foundation

private func effect(_ pairs: [String: Any]) -> JsonObject { JsonObject(pairs) }

func effectsChecks() -> [Check] {
  [
    // ── the drop rules ───────────────────────────────────────────────────────

    Check(name: "an unknown kind is dropped — a newer model does not get to invent a capability") {
      firstFailure([
        expectTrue(parseEffect(effect(["kind": "selfDestruct"])) == nil, "invented kind"),
        expectTrue(parseEffect(effect(["kind": ""])) == nil, "empty kind"),
        expectTrue(parseEffect(nil) == nil, "nil input"),
        expectTrue(parseEffect(effect([:])) == nil, "no kind at all"),
      ])
    },

    Check(name: "an empty string is not a value anywhere it is required") {
      firstFailure([
        expectTrue(parseEffect(effect(["kind": "say", "text": ""])) == nil, "say"),
        expectTrue(parseEffect(effect(["kind": "forwardToAgent", "text": ""])) == nil, "forward"),
        expectTrue(parseEffect(effect(["kind": "relayToAgent", "answer": ""])) == nil, "relay"),
        expectTrue(parseEffect(effect(["kind": "enqueue", "task": ""])) == nil, "enqueue"),
      ])
    },

    Check(name: "askAndWait needs both a question and a recognised wait reason") {
      firstFailure([
        expectTrue(
          parseEffect(effect(["kind": "askAndWait", "question": "which one?"])) == nil,
          "missing waitingFor"),
        expectTrue(
          parseEffect(effect(["kind": "askAndWait", "question": "which one?", "waitingFor": "vibes"])) == nil,
          "unrecognised waitingFor"),
        expectEqual(
          parseEffect(effect(["kind": "askAndWait", "question": "which one?", "waitingFor": "urgency"])),
          .askAndWait(question: "which one?", waitingFor: .urgency),
          "the valid case"),
      ])
    },

    Check(name: "setState only accepts a real mode") {
      firstFailure([
        expectTrue(parseEffect(effect(["kind": "setState", "mode": "vibing"])) == nil, "invented mode"),
        expectEqual(
          parseEffect(effect(["kind": "setState", "mode": "awaiting-user"])),
          .setState(mode: .awaitingUser),
          "the hyphenated wire value"),
      ])
    },

    // ── chooseOption: filter, don't reject ───────────────────────────────────

    Check(name: "chooseOption filters junk values rather than rejecting the whole pick") {
      // A partly-malformed pick list still resolves what it can — the alternative leaves the
      // approval pending with the user believing they answered it.
      expectEqual(
        parseEffect(effect(["kind": "chooseOption", "values": ["alpha", 7, "", "beta"]])),
        .chooseOption(values: ["alpha", "beta"]),
        "surviving values")
    },

    Check(name: "chooseOption with nothing left after filtering is dropped") {
      firstFailure([
        expectTrue(
          parseEffect(effect(["kind": "chooseOption", "values": ["", 7]])) == nil,
          "all values filtered out"),
        expectTrue(
          parseEffect(effect(["kind": "chooseOption", "values": []])) == nil,
          "empty array"),
        expectTrue(
          parseEffect(effect(["kind": "chooseOption"])) == nil,
          "a missing array is not an empty one"),
      ])
    },

    // ── the tolerant cases ───────────────────────────────────────────────────

    Check(name: "an unrecognised urgency degrades to normal instead of dropping the task") {
      expectEqual(
        parseEffect(effect(["kind": "enqueue", "task": "later", "urgency": "sometime"])),
        .enqueue(task: "later", urgency: .normal),
        "degraded urgency")
    },

    Check(name: "deny parses with or without a reason, and the reason is carried not dropped") {
      firstFailure([
        expectEqual(parseEffect(effect(["kind": "deny"])), .deny(reason: nil), "bare deny"),
        expectEqual(
          parseEffect(effect(["kind": "deny", "reason": "too risky"])),
          .deny(reason: "too risky"),
          "deny with a reason"),
      ])
    },

    Check(name: "cancelQueued and sendQueuedNow both parse bare — absent task means all-or-the-only-one") {
      firstFailure([
        expectEqual(parseEffect(effect(["kind": "cancelQueued"])), .cancelQueued(task: nil), "bare cancel"),
        expectEqual(parseEffect(effect(["kind": "sendQueuedNow"])), .sendQueuedNow(task: nil), "bare send"),
        expectEqual(
          parseEffect(effect(["kind": "cancelQueued", "task": "the email"])),
          .cancelQueued(task: "the email"),
          "named cancel"),
      ])
    },

    Check(name: "the payload-free effects parse from kind alone") {
      firstFailure([
        expectEqual(parseEffect(effect(["kind": "approve"])), .approve, "approve"),
        expectEqual(parseEffect(effect(["kind": "resetSession"])), .resetSession, "resetSession"),
        expectEqual(parseEffect(effect(["kind": "noop"])), .noop, "noop"),
      ])
    },

    // `interrupt` carries the one piece of payload this grammar has that cloud-api's does not, and
    // the DEFAULT is the part worth pinning: a bare `interrupt` has always meant "stop the lot", and
    // every model that has ever called it sent it bare. A scope that defaulted the other way would
    // silently narrow every existing "stop" to the running task and leave the queue running behind
    // it.
    Check(name: "a bare interrupt still means everything, and an unknown scope falls back to it") {
      firstFailure([
        expectEqual(parseEffect(effect(["kind": "interrupt"])), .interrupt(scope: .everything), "bare"),
        expectEqual(
          parseEffect(effect(["kind": "interrupt", "scope": "the whole lot"])),
          .interrupt(scope: .everything),
          "unrecognised scope"),
        expectEqual(
          parseEffect(effect(["kind": "interrupt", "scope": "everything"])),
          .interrupt(scope: .everything),
          "explicit everything"),
      ])
    },

    Check(name: "scope running parses, case and whitespace insensitively") {
      firstFailure([
        expectEqual(
          parseEffect(effect(["kind": "interrupt", "scope": "running"])),
          .interrupt(scope: .running),
          "plain"),
        expectEqual(
          parseEffect(effect(["kind": "interrupt", "scope": " Running "])),
          .interrupt(scope: .running),
          "padded and capitalised"),
      ])
    },

    Check(name: "a stray approveAlways folds to a one-time approve rather than being dropped") {
      // The tool is gone and the prompt no longer names it, but a Live model improvises names, and
      // returning nil here would leave the card pending and the model waiting on a decision it
      // thinks it made. Folding matches what the server does with `response: "always"` — approve
      // once.
      expectEqual(parseEffect(effect(["kind": "approveAlways"])), .approve, "folded")
    },

    // ── batches ──────────────────────────────────────────────────────────────

    Check(name: "a batch drops only the malformed entries and keeps the order of the rest") {
      let batch = JsonArray([
        ["kind": "say", "text": "on it"],
        ["kind": "nonsense"],
        ["kind": "forwardToAgent", "text": "book a table"],
        ["kind": "say", "text": ""],
      ])
      // Order matters — a say before a forward is a different conversation than after.
      return expectEqual(
        parseEffects(batch),
        [.say(text: "on it"), .forwardToAgent(text: "book a table")],
        "surviving batch")
    },

    Check(name: "a non-array batch is empty, not a crash") {
      firstFailure([
        expectTrue(parseEffects(nil).isEmpty, "nil"),
        expectTrue(parseEffects(JsonArray([])).isEmpty, "empty"),
        expectTrue(parseEffects(JsonArray(["say", 3])).isEmpty, "non-object entries are dropped"),
      ])
    },
  ]
}
