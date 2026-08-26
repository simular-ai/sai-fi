/* sai-fi — voice concierge. */

// Pure policy checks, ported from the Android `*Test.kt` files of the same names. Wording is
// load-bearing — pin the string contents, not the Swift identifier style.

import Foundation
import os

func policyChecks() -> [Check] {
  captureNotesChecks()
    + greetingGateChecks()
    + reconnectPolicyChecks()
    + hangupPolicyChecks()
    + heldNudgeQueueChecks()
    + machineSwitcherChecks()
    + wakePolicyChecks()
    + leavingWorkPolicyChecks()
    + liveModelPartsChecks()
    + captureCueChecks()
}

// ── CaptureNotes ─────────────────────────────────────────────────────────────

private func captureNotesChecks() -> [Check] {
  [
    Check(name: "the capture-started note does not tell the model to keep talking") {
      let note = CaptureNotes.started
      return firstFailure([
        expectTrue(note.contains("SILENT from here"), "SILENT from here"),
        expectTrue(note.contains("do not speak this note"), "do not speak this note"),
        expectTrue(note.contains("do not narrate the wait"), "do not narrate the wait"),
        expectTrue(
          note.contains("do not ask whether they wanted anything else"),
          "do not ask whether they wanted anything else"),
        expectFalse(note.contains("out loud right now"), "the old 'say it out loud right now' instruction must not return"),
      ])
    },
    Check(name: "the held-for-photo note does not invite camera-wait narration") {
      let note = CaptureNotes.heldForPhoto
      return firstFailure([
        expectTrue(note.contains("do not speak this note"), "do not speak this note"),
        expectTrue(note.contains("do not tell the user you are waiting"), "do not tell the user you are waiting"),
        expectTrue(note.contains("NOT started yet"), "NOT started yet"),
        expectTrue(note.contains("Do not claim it is running"), "Do not claim it is running"),
        expectFalse(
          note.contains("waiting for the glasses photo"),
          "the old 'waiting for the glasses photo' phrasing was spoken as camera-wait speech"),
      ])
    },
  ]
}

// ── GreetingGate ─────────────────────────────────────────────────────────────

private func greetingGateChecks() -> [Check] {
  [
    Check(name: "greets on first ready only") {
      let gate = GreetingGate()
      return firstFailure([
        expectTrue(gate.shouldGreet(), "greets on the first ready"),
        expectFalse(gate.shouldGreet(), "does not re-greet on a reconnect"),
        expectFalse(gate.shouldGreet(), "does not re-greet on resume"),
      ])
    },
    Check(name: "does not greet before reset on a resumed gate") {
      let gate = GreetingGate()
      _ = gate.shouldGreet()
      return expectFalse(gate.shouldGreet(), "already greeted")
    },
    Check(name: "reset re-arms for a new call") {
      let gate = GreetingGate()
      _ = gate.shouldGreet()
      _ = gate.shouldGreet()
      gate.reset()
      return firstFailure([
        expectTrue(gate.shouldGreet(), "re-arms after reset for the next call"),
        expectFalse(gate.shouldGreet(), "once per call after re-arm"),
      ])
    },
    Check(name: "concurrent readies greet exactly once") {
      let gate = GreetingGate()
      let greetCount = OSAllocatedUnfairLock(initialState: 0)
      DispatchQueue.concurrentPerform(iterations: 32) { _ in
        if gate.shouldGreet() {
          greetCount.withLock { $0 += 1 }
        }
      }
      return expectEqual(greetCount.withLock { $0 }, 1, "exactly one thread wins the greeting")
    },
  ]
}

// ── ReconnectPolicy ──────────────────────────────────────────────────────────

private func reconnectPolicyChecks() -> [Check] {
  [
    Check(name: "the four permanent codes") {
      firstFailure([
        expectTrue(ReconnectPolicy.isPermanent(401), "401"),
        expectTrue(ReconnectPolicy.isPermanent(402), "402"),
        expectTrue(ReconnectPolicy.isPermanent(403), "403"),
        expectTrue(ReconnectPolicy.isPermanent(503), "503"),
      ])
    },
    Check(name: "503 counts as permanent here, against the usual reading") {
      expectTrue(ReconnectPolicy.isPermanent(503), "503")
    },
    Check(name: "transient failures are retried") {
      firstFailure([
        expectFalse(ReconnectPolicy.isPermanent(500), "500"),
        expectFalse(ReconnectPolicy.isPermanent(502), "502"),
        expectFalse(ReconnectPolicy.isPermanent(504), "504"),
        expectFalse(ReconnectPolicy.isPermanent(429), "429"),
        expectFalse(ReconnectPolicy.isPermanent(0), "0"),
      ])
    },
    Check(name: "each permanent code gets its own reason, because they are different problems") {
      firstFailure([
        expectEqual(ReconnectPolicy.reasonFor(402), "You're out of credits for voice.", "402"),
        expectEqual(ReconnectPolicy.reasonFor(503), "Voice isn't available right now.", "503"),
        expectEqual(ReconnectPolicy.reasonFor(401), "Voice access was denied.", "401"),
        expectEqual(ReconnectPolicy.reasonFor(403), "Voice access was denied.", "403"),
      ])
    },
    Check(name: "an unrecognised code still gets a sentence, not a status number") {
      expectEqual(ReconnectPolicy.reasonFor(418), "The voice call couldn't continue.", "418")
    },
    Check(name: "every reason is one short spoken sentence") {
      for code in [401, 402, 403, 503, 999] {
        let reason = ReconnectPolicy.reasonFor(code)
        if let fail = firstFailure([
          expectTrue(reason.hasSuffix("."), "ends with a period"),
          expectTrue(reason.count < 60, "short"),
          expectFalse(reason.contains("\(code)"), "no status number"),
        ]) { return fail }
      }
      return nil
    },
    Check(name: "backoff doubles") {
      firstFailure([
        expectEqual(ReconnectPolicy.nextBackoff(1_500), Int64(3_000), "1500→3000"),
        expectEqual(ReconnectPolicy.nextBackoff(3_000), Int64(6_000), "3000→6000"),
      ])
    },
    Check(name: "backoff is capped, so a long outage does not become an infinite wait") {
      firstFailure([
        expectEqual(ReconnectPolicy.nextBackoff(12_000), Int64(15_000), "12000"),
        expectEqual(ReconnectPolicy.nextBackoff(15_000), Int64(15_000), "15000"),
        expectEqual(ReconnectPolicy.nextBackoff(60_000), Int64(15_000), "60000"),
      ])
    },
    Check(name: "the first retry is quick, because most drops are a blip") {
      expectEqual(ReconnectPolicy.initialBackoffMs, Int64(1_500), "initial backoff")
    },
  ]
}

// ── HangupPolicy ─────────────────────────────────────────────────────────────

private func hangupDecide(
  spokeThisTurn: Bool = false,
  lastUserSpeechAt: Int64 = 0,
  lastSaiSpeechAt: Int64 = 0,
  lastSaiText: String = "",
  muted: Bool = false,
  guardUsed: Bool = false
) -> HangupAction {
  HangupPolicy.decide(
    spokeThisTurn: spokeThisTurn,
    lastUserSpeechAt: lastUserSpeechAt,
    lastSaiSpeechAt: lastSaiSpeechAt,
    lastSaiText: lastSaiText,
    muted: muted,
    guardUsed: guardUsed)
}

private func hangupPolicyChecks() -> [Check] {
  [
    Check(name: "Sai spoke this turn and the user has talked — end after the goodbye lands") {
      expectEqual(
        hangupDecide(spokeThisTurn: true, lastUserSpeechAt: 5_000),
        .endAfterGoodbye,
        "end after goodbye")
    },
    Check(name: "a goodbye from an earlier turn still counts, if it came after the user last spoke") {
      expectEqual(
        hangupDecide(lastUserSpeechAt: 5_000, lastSaiSpeechAt: 6_000, lastSaiText: "bye then"),
        .endAfterGoodbye,
        "earlier goodbye")
    },
    Check(name: "muted, there is no goodbye to hear — end immediately, no window") {
      expectEqual(
        hangupDecide(spokeThisTurn: true, lastUserSpeechAt: 5_000, muted: true),
        .endNow,
        "end now")
    },
    Check(name: "the user has never spoken — a farewell it heard was not aimed at it") {
      let action = hangupDecide(spokeThisTurn: true, lastUserSpeechAt: 0)
      guard case .holdAndAsk(let why, _) = action else { return "expected holdAndAsk, got \(action)" }
      return expectEqual(why, "the user hasn't said anything this call", "why")
    },
    Check(name: "Sai has not spoken since the user's last turn — no goodbye was answered") {
      let action = hangupDecide(lastUserSpeechAt: 9_000, lastSaiSpeechAt: 4_000, lastSaiText: "sure")
      guard case .holdAndAsk(let why, _) = action else { return "expected holdAndAsk, got \(action)" }
      return expectEqual(why, "Sai hasn't spoken since the user's last turn — no goodbye", "why")
    },
    Check(name: "a later timestamp with empty text is a turn that produced nothing, not a sign-off") {
      let action = hangupDecide(lastUserSpeechAt: 5_000, lastSaiSpeechAt: 6_000, lastSaiText: "  ")
      if case .holdAndAsk = action { return nil }
      return "expected holdAndAsk, got \(action)"
    },
    Check(name: "muted and unconfirmed — hold, but do not ask, because asking cannot be heard") {
      let action = hangupDecide(lastUserSpeechAt: 0, muted: true)
      guard case .holdSilently(let why) = action else { return "expected holdSilently, got \(action)" }
      return expectEqual(why, "the user hasn't said anything this call", "why")
    },
    Check(name: "the second endCall goes through — saying hang up twice means it") {
      expectEqual(hangupDecide(lastUserSpeechAt: 0, guardUsed: true), .endAfterGoodbye, "second endCall")
    },
    Check(name: "the second endCall while muted ends now rather than opening a window") {
      expectEqual(
        hangupDecide(lastUserSpeechAt: 0, muted: true, guardUsed: true), .endNow, "muted second")
    },
    Check(name: "the held nudge says the call is still open and asks rather than assumes") {
      guard case .holdAndAsk(_, let nudge) = hangupDecide(lastUserSpeechAt: 0) else {
        return "expected holdAndAsk"
      }
      return firstFailure([
        expectTrue(nudge.hasPrefix("[system]"), "system"),
        expectTrue(nudge.contains("STILL OPEN"), "STILL OPEN"),
        expectTrue(nudge.contains("did you want me to hang up?"), "ask"),
        expectTrue(nudge.contains("Do not say goodbye"), "must not say goodbye again"),
      ])
    },
    Check(name: "the cancelled nudge tells Sai not to sign off twice") {
      let nudge = HangupPolicy.cancelledNudge
      return firstFailure([
        expectTrue(nudge.hasPrefix("[system]"), "system"),
        expectTrue(nudge.contains("STILL OPEN"), "STILL OPEN"),
        expectTrue(nudge.contains("Do not say goodbye again"), "do not sign off twice"),
        expectTrue(nudge.contains("aimed at someone else"), "overheard-farewell case"),
      ])
    },
    Check(name: "no hangup pending — nothing to cancel") {
      expectFalse(
        HangupPolicy.shouldCancel(openedAt: 0, now: 10_000, stragglerGuardMs: 600),
        "nothing pending")
    },
    Check(name: "speech inside the straggler guard is the goodbye's own transcription, not a barge-in") {
      expectFalse(
        HangupPolicy.shouldCancel(openedAt: 10_000, now: 10_300, stragglerGuardMs: 600),
        "inside the guard")
    },
    Check(name: "speech after the guard means the user was not done") {
      expectTrue(
        HangupPolicy.shouldCancel(openedAt: 10_000, now: 10_700, stragglerGuardMs: 600),
        "after the guard")
    },
    Check(name: "the guard boundary itself cancels") {
      expectTrue(
        HangupPolicy.shouldCancel(openedAt: 10_000, now: 10_600, stragglerGuardMs: 600),
        "at the boundary")
    },
  ]
}

// ── HeldNudgeQueue ───────────────────────────────────────────────────────────

private func heldNudgeQueueChecks() -> [Check] {
  [
    Check(name: "only the newest completion survives") {
      let q = HeldNudgeQueue()
      _ = q.add(kind: "complete", nudge: "first result")
      _ = q.add(kind: "complete", nudge: "second result")
      _ = q.add(kind: "complete", nudge: "third result")
      let out = q.drain()
      return firstFailure([
        expectEqual(out.count, 1, "an older result is superseded by definition"),
        expectEqual(out.first?.nudge ?? "", "third result", "newest"),
      ])
    },
    Check(name: "progress chatter is discarded not queued") {
      let q = HeldNudgeQueue()
      return firstFailure([
        expectFalse(q.add(kind: "progress", nudge: "opening the site"), "progress"),
        expectFalse(q.add(kind: "status", nudge: "processing"), "status"),
        expectTrue(q.drain().isEmpty, "nothing comes back out"),
      ])
    },
    Check(name: "urgent events come out first") {
      let q = HeldNudgeQueue()
      _ = q.add(kind: "complete", nudge: "the result")
      _ = q.add(kind: "approval-request", nudge: "okay to send it?")
      _ = q.add(kind: "error", nudge: "it broke")
      return expectEqual(
        q.drain().map(\.kind),
        ["error", "approval-request", "complete"],
        "both urgent kinds precede the completion")
    },
    Check(name: "cap is enforced from the back") {
      let q = HeldNudgeQueue(max: 2)
      _ = q.add(kind: "approval-request", nudge: "a")
      _ = q.add(kind: "approval-request", nudge: "b")
      _ = q.add(kind: "approval-request", nudge: "c")
      let out = q.drain()
      return firstFailure([
        expectEqual(out.count, 2, "capped"),
        expectEqual(out.map(\.nudge), ["c", "b"], "newest urgent goes to the front"),
      ])
    },
    Check(name: "drain empties so unmuting twice cannot repeat") {
      let q = HeldNudgeQueue()
      _ = q.add(kind: "complete", nudge: "the result")
      return firstFailure([
        expectEqual(q.drain().count, 1, "first drain"),
        expectTrue(q.drain().isEmpty, "second drain"),
      ])
    },
    Check(name: "clear drops everything") {
      let q = HeldNudgeQueue()
      _ = q.add(kind: "complete", nudge: "x")
      _ = q.add(kind: "error", nudge: "y")
      q.clear()
      return expectTrue(q.drain().isEmpty, "cleared")
    },
  ]
}

// ── MachineSwitcher ──────────────────────────────────────────────────────────

private func machineSwitcherChecks() -> [Check] {
  let laptop = Machine(machineId: "m-1", name: "Work Laptop")
  let studio = Machine(machineId: "m-2", name: "Mac Studio")
  let all = [laptop, studio]
  return [
    Check(name: "no machines at all") {
      let d = MachineSwitcher.resolve(query: "laptop", machines: [], currentMachineId: "m-1")
      guard case .noMachines(let reply) = d else { return "expected noMachines, got \(d)" }
      return expectEqual(reply, "I don't have another machine to switch to.", "reply")
    },
    Check(name: "a blank name is not a request") {
      let d = MachineSwitcher.resolve(query: "   ", machines: all, currentMachineId: "m-1")
      if case .noMachines = d { return nil }
      return "expected noMachines, got \(d)"
    },
    Check(name: "an exact name, whatever the casing") {
      let d = MachineSwitcher.resolve(query: "work laptop", machines: all, currentMachineId: "m-2")
      guard case .switchTo(let machine, _) = d else { return "expected switchTo, got \(d)" }
      return expectEqual(machine, laptop, "matched")
    },
    Check(name: "the user says LESS than the label — studio finds Mac Studio") {
      let d = MachineSwitcher.resolve(query: "studio", machines: all, currentMachineId: "m-1")
      guard case .switchTo(let machine, _) = d else { return "expected switchTo, got \(d)" }
      return expectEqual(machine, studio, "matched")
    },
    Check(name: "the user says MORE than the label — my mac studio at home finds Mac Studio") {
      let d = MachineSwitcher.resolve(
        query: "my mac studio at home", machines: all, currentMachineId: "m-1")
      guard case .switchTo(let machine, _) = d else { return "expected switchTo, got \(d)" }
      return expectEqual(machine, studio, "matched")
    },
    Check(name: "an exact match wins over a containment match") {
      let exact = Machine(machineId: "m-3", name: "Studio")
      let d = MachineSwitcher.resolve(
        query: "studio", machines: [studio, exact], currentMachineId: "m-1")
      guard case .switchTo(let machine, _) = d else { return "expected switchTo, got \(d)" }
      return expectEqual(machine, exact, "exact wins")
    },
    Check(name: "no match names what the user actually has, rather than just failing") {
      let d = MachineSwitcher.resolve(
        query: "the server in the cupboard", machines: all, currentMachineId: "m-1")
      guard case .notFound(let reply) = d else { return "expected notFound, got \(d)" }
      return firstFailure([
        expectTrue(reply.contains("the server in the cupboard"), "the query"),
        expectTrue(reply.contains("Work Laptop"), "Work Laptop"),
        expectTrue(reply.contains("Mac Studio"), "Mac Studio"),
      ])
    },
    Check(name: "already on it is a no-op that still says so") {
      let d = MachineSwitcher.resolve(query: "Mac Studio", machines: all, currentMachineId: "m-2")
      guard case .alreadyOn(let reply) = d else { return "expected alreadyOn, got \(d)" }
      return expectEqual(reply, "You're already on Mac Studio.", "reply")
    },
    Check(name: "the switch reply carries a context update the model must not speak") {
      guard case .switchTo(_, let reply) = MachineSwitcher.resolve(
        query: "laptop", machines: all, currentMachineId: "m-2")
      else { return "expected switchTo" }
      return firstFailure([
        expectTrue(reply.hasPrefix("Switched to Work Laptop."), "prefix"),
        expectTrue(reply.contains("not to be spoken aloud"), "not spoken"),
        expectTrue(reply.contains("ignore any earlier context"), "stale context"),
      ])
    },
    Check(name: "the UI picker's nudge says the same thing, as a system line") {
      let nudge = MachineSwitcher.contextNudge("Work Laptop")
      return firstFailure([
        expectTrue(nudge.hasPrefix("[system]"), "system"),
        expectTrue(nudge.contains("not to be spoken aloud"), "not spoken"),
        expectTrue(nudge.contains("Work Laptop"), "label"),
        expectTrue(nudge.contains("ignore any earlier context"), "stale context"),
      ])
    },
  ]
}

// ── WakePolicy ───────────────────────────────────────────────────────────────

private func wakePolicyChecks() -> [Check] {
  [
    Check(name: "announces a wake it dispatched") {
      let d = WakePolicy.onWakeRequested(
        startingUp: true, muted: false, audible: true,
        status: "hibernated", canWake: true, dispatched: true)
      guard case .speak(let line, let watch) = d else { return "expected speak, got \(d)" }
      return firstFailure([
        expectEqual(line, MACHINE_WAKING, "line"),
        expectTrue(watch, "a wake it announced is a wake it must watch"),
      ])
    },
    Check(name: "announces a machine already mid-wake, which dispatched nothing") {
      let d = WakePolicy.onWakeRequested(
        startingUp: true, muted: false, audible: true,
        status: "wakingup", canWake: true, dispatched: false)
      guard case .speak(let line, _) = d else { return "expected speak, got \(d)" }
      return expectEqual(line, MACHINE_WAKING, "still announced")
    },
    Check(name: "says nothing about a machine that cannot be woken") {
      let d = WakePolicy.onWakeRequested(
        startingUp: false, muted: false, audible: true,
        status: "hibernated", canWake: false, dispatched: false)
      guard case .silent(let why) = d else { return "expected silent, got \(d)" }
      return expectTrue(why.contains("canWake=false"), "honesty case")
    },
    Check(name: "says nothing about a machine that was already awake") {
      let d = WakePolicy.onWakeRequested(
        startingUp: false, muted: false, audible: true,
        status: "active", canWake: true, dispatched: false)
      if case .silent = d { return nil }
      return "expected silent, got \(d)"
    },
    Check(name: "drops the opening line while muted") {
      let d = WakePolicy.onWakeRequested(
        startingUp: true, muted: true, audible: true,
        status: "hibernated", canWake: true, dispatched: true)
      guard case .silent(let why) = d else { return "expected silent, got \(d)" }
      return expectTrue(why.contains("muted"), "muted")
    },
    Check(name: "says nothing with no live session to say it through") {
      let d = WakePolicy.onWakeRequested(
        startingUp: true, muted: false, audible: false,
        status: "hibernated", canWake: true, dispatched: true)
      if case .silent = d { return nil }
      return "expected silent, got \(d)"
    },
    Check(name: "reports the machine coming up") {
      let d = WakePolicy.onWatchEnded(active: true, muted: false, audible: true)
      guard case .speak(let line, let watch) = d else { return "expected speak, got \(d)" }
      return firstFailure([
        expectEqual(line, MACHINE_AWAKE, "line"),
        expectFalse(watch, "the watch is over either way"),
      ])
    },
    Check(name: "reports a wake that never landed") {
      let d = WakePolicy.onWatchEnded(active: false, muted: false, audible: true)
      guard case .speak(let line, _) = d else { return "expected speak, got \(d)" }
      return expectEqual(line, MACHINE_WAKE_FAILED, "line")
    },
    Check(name: "drops the outcome while muted, even the failure") {
      let fail = WakePolicy.onWatchEnded(active: false, muted: true, audible: true)
      let ok = WakePolicy.onWatchEnded(active: true, muted: true, audible: true)
      guard case .silent(let why) = fail else { return "expected silent failure, got \(fail)" }
      if case .silent = ok {
        return expectTrue(why.contains("muted"), "muted")
      }
      return "expected silent success, got \(ok)"
    },
  ]
}

// ── LeavingWorkPolicy ────────────────────────────────────────────────────────

private func leavingState(
  inFlight: [String] = [],
  queued: [String] = [],
  approval: String? = nil
) -> ConciergeState {
  var s = ConciergeState()
  s.inFlight = inFlight
  s.queue = queued.map { QueuedTask(text: $0, urgency: .normal) }
  s.pendingApprovalId = approval
  return s
}

private func leavingWorkPolicyChecks() -> [Check] {
  [
    Check(name: "nothing outstanding goes straight through") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(), leaving: .call, alreadyAsked: false, muted: false)
      if case .proceed = a { return nil }
      return "expected proceed, got \(a)"
    },
    Check(name: "a running task is asked about before the call ends") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(inFlight: ["check my email"]),
        leaving: .call, alreadyAsked: false, muted: false)
      guard case .ask(let n) = a else { return "expected ask, got \(a)" }
      return firstFailure([
        expectTrue(n.contains("check my email"), "names the work"),
        expectTrue(n.contains("you have NOT hung up"), "reads as English"),
        expectTrue(n.contains("NOTHING has happened yet"), "nothing has happened yet"),
        expectTrue(n.contains("Sai app"), "points at the app"),
        expectTrue(n.contains("won't hear the result"), "says the call won't carry the result"),
      ])
    },
    Check(name: "a running task is asked about before a machine switch, with the switch's own wording") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(inFlight: ["check my email"]),
        leaving: .machine, alreadyAsked: false, muted: false)
      guard case .ask(let n) = a else { return "expected ask, got \(a)" }
      return firstFailure([
        expectTrue(n.contains("you have NOT moved to another machine"), "past participle"),
        expectTrue(n.contains("the machine they're leaving"), "the old machine"),
      ])
    },
    Check(name: "running and queued are named separately, never as one list") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(inFlight: ["check my email"], queued: ["book a table"]),
        leaving: .call, alreadyAsked: false, muted: false)
      guard case .ask(let n) = a else { return "expected ask, got \(a)" }
      return firstFailure([
        expectTrue(n.contains("you're still working on check my email"), "model is the subject"),
        expectTrue(n.contains("book a table hasn't started yet"), "queued named separately"),
      ])
    },
    Check(name: "an unanswered approval counts as outstanding work") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(approval: "appr-1"),
        leaving: .call, alreadyAsked: false, muted: false)
      guard case .ask(let n) = a else { return "expected ask, got \(a)" }
      return expectTrue(n.contains("a request is waiting on their answer"), "approval")
    },
    Check(name: "the second attempt goes through") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(inFlight: ["check my email"]),
        leaving: .call, alreadyAsked: true, muted: false)
      if case .proceed = a { return nil }
      return "expected proceed, got \(a)"
    },
    Check(name: "muted, there is no question to put") {
      let a = LeavingWorkPolicy.decide(
        state: leavingState(inFlight: ["check my email"]),
        leaving: .call, alreadyAsked: false, muted: true)
      if case .proceed = a { return nil }
      return "expected proceed, got \(a)"
    },
  ]
}

// ── LiveModelParts ───────────────────────────────────────────────────────────

private func part(_ json: String) -> JsonObject {
  guard let o = JsonObject(string: json) else { fatalError("fixture is not an object: \(json)") }
  return o
}

private func liveModelPartsChecks() -> [Check] {
  [
    Check(name: "ordinary audio is played and is not a transcript fallback") {
      let actions = LiveModelParts.classifyFrame([
        part(#"{"inlineData":{"mimeType":"audio/pcm","data":"AAAA"}}"#)
      ])
      guard actions.count == 1 else { return "expected 1 action, got \(actions.count)" }
      return firstFailure([
        expectEqual(actions[0].playAudioB64 ?? "", "AAAA", "play"),
        expectTrue(actions[0].transcriptFallback == nil, "played audio is transcribed separately"),
        expectTrue(actions[0].log == nil, "no log"),
      ])
    },
    Check(name: "thought audio is not played — it is a different untranscribed voice") {
      let actions = LiveModelParts.classifyFrame([
        part(#"{"thought":true,"text":"waiting for the camera to start","inlineData":{"data":"THOUGHT"}}"#)
      ])
      guard actions.count == 1 else { return "expected 1 action, got \(actions.count)" }
      return firstFailure([
        expectTrue(actions[0].playAudioB64 == nil, "thought audio must not reach the speaker"),
        expectTrue(actions[0].transcriptFallback == nil, "thoughts are not speech"),
        expectEqual(actions[0].log ?? "", "[live] dropped thought audio — not speech", "log"),
      ])
    },
    Check(name: "thought text without audio is dropped, not spoken or transcribed") {
      let actions = LiveModelParts.classifyFrame([
        part(#"{"thought":true,"text":"paragraph writing"}"#)
      ])
      guard actions.count == 1 else { return "expected 1 action, got \(actions.count)" }
      return firstFailure([
        expectTrue(actions[0].playAudioB64 == nil, "no audio"),
        expectTrue(actions[0].transcriptFallback == nil, "not speech"),
        expectEqual(actions[0].log ?? "", "[live] dropped thought text — not speech", "log"),
      ])
    },
    Check(name: "a text-only frame surfaces on the transcript so untranscribed speech is still visible") {
      let actions = LiveModelParts.classifyFrame([part(#"{"text":"let me take a look"}"#)])
      guard actions.count == 1 else { return "expected 1 action, got \(actions.count)" }
      return firstFailure([
        expectTrue(actions[0].playAudioB64 == nil, "no audio"),
        expectEqual(actions[0].transcriptFallback ?? "", "let me take a look", "fallback"),
        expectTrue(actions[0].log == nil, "contents stay off the projector log"),
      ])
    },
    Check(name: "text beside playable audio is not also transcribed — that would double the line") {
      let actions = LiveModelParts.classifyFrame([
        part(#"{"text":"let me take a look"}"#),
        part(#"{"inlineData":{"data":"AAAA"}}"#),
      ])
      guard actions.count == 2 else { return "expected 2 actions, got \(actions.count)" }
      return firstFailure([
        expectTrue(actions[0].playAudioB64 == nil, "text part has no audio"),
        expectTrue(actions[0].transcriptFallback == nil, "the audio in this frame owns the transcript"),
        expectEqual(actions[1].playAudioB64 ?? "", "AAAA", "speech audio"),
        expectTrue(actions[1].transcriptFallback == nil, "no fallback on the audio part"),
      ])
    },
    Check(name: "speech audio still plays when a thought part is sitting next to it") {
      let actions = LiveModelParts.classifyFrame([
        part(#"{"thought":true,"inlineData":{"data":"THOUGHT"}}"#),
        part(#"{"inlineData":{"data":"SPEECH"}}"#),
      ])
      guard actions.count == 2 else { return "expected 2 actions, got \(actions.count)" }
      return firstFailure([
        expectTrue(actions[0].playAudioB64 == nil, "thought dropped"),
        expectEqual(actions[1].playAudioB64 ?? "", "SPEECH", "speech plays"),
        expectTrue(actions[1].transcriptFallback == nil, "no fallback"),
      ])
    },
  ]
}

// ── CaptureCue ───────────────────────────────────────────────────────────────

private func captureCueChecks() -> [Check] {
  [
    Check(name: "the capture cue is a two-note tone, not speech") {
      let expectedBytes = CaptureCue.sampleRate * CaptureCue.durationMs / 1000 * 2
      return firstFailure([
        expectEqual(CaptureCue.durationMs, 185, "70+45+70"),
        expectTrue(CaptureCue.durationMs < 300, "a spoken line is longer than this"),
        expectEqual(CaptureCue.pcm.count, expectedBytes, "pcm byte count"),
        expectTrue(CaptureCue.pcm.contains(where: { $0 != 0 }), "the cue is not silence"),
      ])
    },
    Check(name: "the cue is built once") {
      // `static let` is the Swift equivalent of Kotlin `by lazy`. Two reads must be the same bytes.
      expectEqual(CaptureCue.pcm, CaptureCue.pcm, "stable")
    },
  ]
}
