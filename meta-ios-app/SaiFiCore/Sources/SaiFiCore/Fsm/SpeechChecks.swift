/* sai-fi — voice concierge. */

// The parity gate for `Speech.swift`.
//
// This replays `parity/speech.json` — generated from the KOTLIN by
// `meta-android-app/.../SpeechFixtures.kt` — case by case. It is the only thing holding the Swift and
// Kotlin copies of these lines equal, and it is the exact job the fixtures used to do across
// cloud-api's TypeScript and the Kotlin before that pair was collapsed into one implementation. The
// difference this time is that the second implementation is deliberate, so the gate is permanent
// rather than a migration aid.
//
// Every case carries an `fn` discriminator naming which helper produced it, so nothing here has to
// read the Kotlin source. A fixture whose `fn` this file does not know is a FAILURE, not a skip:
// silently ignoring an unrecognised case is how a helper added on the Android side ends up
// unimplemented here with the gate still green.

import Foundation

func speechChecks(_ fixtures: ParityFixtures) -> [Check] {
  // Loaded once, at registry-build time, so a missing or empty file is a single loud failure rather
  // than fifty confusing ones. A load error becomes ONE failing check rather than an empty list:
  // "the fixtures were unreadable" and "the gate passed" must never look the same.
  let cases: [JsonObject]
  do {
    cases = try fixtures.load("speech.json")
  } catch {
    let reason = "\(error)"
    return [Check(name: "speech.json loads") { reason }]
  }

  var checks: [Check] = [
    // The count is pinned for the same reason `FsmGoldenTest` pins PORTED_SCENARIO_COUNT: a
    // shrinking catalog must not go green quietly. Bump it deliberately when the Kotlin gains a
    // case, in the same commit that implements the case here.
    Check(name: "speech.json still has every case the Kotlin generates") {
      expectEqual(cases.count, expectedSpeechCaseCount, "speech.json case count")
    }
  ]

  for fixture in cases {
    let name = fixture.optString("name", "<unnamed>")
    checks.append(
      Check(name: "speech: \(name)") {
        renderSpeechCase(fixture).flatMap { produced in
          produced == fixture.optString("expected")
            ? nil
            : """
              wording drift
                    kotlin: \(fixture.optString("expected"))
                    swift : \(produced)
              """
        }
      })
  }
  return checks
}

/// How many cases `SpeechFixtures.kt` produces. See the check above for why this is written down.
let expectedSpeechCaseCount = 52

/// Re-produce one fixture's output from the Swift implementation.
///
/// Returns the rendered string, or — via the `Optional` the caller flat-maps — a reason it could not
/// be rendered at all. An unknown `fn` lands in the `default` branch and fails.
private func renderSpeechCase(_ fixture: JsonObject) -> SpeechRender {
  let name = fixture.optString("name", "<unnamed>")

  // A null input means the case is a bare constant, keyed by its own name.
  guard let input = fixture.optObject("input") else {
    guard let value = speechConstants[name] else {
      return .failed("no Swift constant named '\(name)' — implement it in Speech.swift")
    }
    return .rendered(value)
  }

  func strings(_ key: String) -> [String] { input.optArray(key)?.strings() ?? [] }

  switch input.optString("fn") {
  case "readBackList":
    return .rendered(readBackList(strings("tasks")))

  case "queuedBehindTask":
    return .rendered(queuedBehindTask(running: input.optString("running")))

  case "cannotResetWhileBusy":
    var state = ConciergeState()
    state.inFlight = strings("inFlight")
    state.queue = strings("queue").map { QueuedTask(text: $0, urgency: .normal) }
    state.pendingApprovalId = input.str("pendingApprovalId")
    return .rendered(cannotResetWhileBusy(state))

  case "droppedQueuedLine":
    return .rendered(droppedQueuedLine(strings("dropped")))

  case "startingNowLine":
    return .rendered(startingNowLine(strings("tasks")))

  case "stoppedRunningLine":
    return .rendered(stoppedRunningLine(stopped: strings("stopped"), queued: strings("queued")))

  case "interruptScopeQuestion":
    return .rendered(interruptScopeQuestion(running: strings("running"), queued: strings("queued")))

  case "nothingRunningNudge":
    return .rendered(nothingRunningNudge(queued: strings("queued")))

  case "cannotDropOneOfManyNudge":
    return .rendered(cannotDropOneOfManyNudge(inFlight: strings("inFlight")))

  case "whichQueuedToRushNudge":
    return .rendered(whichQueuedToRushNudge(queued: strings("queued")))

  case "noQueuedMatchNudge":
    return .rendered(noQueuedMatchNudge(queued: strings("queued")))

  case "unattributableApprovalNudge":
    return .rendered(
      unattributableApprovalNudge(inFlight: strings("inFlight"), prompt: input.str("prompt")))

  case "relayIntoBlockedTurnNudge":
    var state = ConciergeState()
    state.pendingApprovalOptions = input.optArray("pendingApprovalOptions")?.objects().map {
      ApprovalOption(value: $0.optString("value"), label: $0.optString("label"))
    }
    state.pendingApprovalLinkOnly = input.optBoolOrNil("pendingApprovalLinkOnly")
    state.pendingApprovalPrompt = input.str("pendingApprovalPrompt")
    return .rendered(relayIntoBlockedTurnNudge(state))

  case "matchQueued":
    let queue = strings("queue").map { QueuedTask(text: $0, urgency: .normal) }
    let index = matchQueued(queue: queue, task: input.optString("task"))
    // The fixture stores an index, so compare as a number rendered the same way the generator
    // rendered it.
    return .rendered(String(index))

  case let unknown:
    return .failed(
      "unknown fn '\(unknown)' for case '\(name)' — the Kotlin gained a helper this port has not")
  }
}

/// The result of trying to re-produce a fixture: a rendered string, or why it could not be.
private enum SpeechRender {
  case rendered(String)
  case failed(String)

  /// Hand the rendered string to `body`; a render failure short-circuits to its own reason.
  func flatMap(_ body: (String) -> String?) -> String? {
    switch self {
    case .rendered(let value): return body(value)
    case .failed(let reason): return reason
    }
  }
}

/// Every bare constant in `Speech.swift`, by the name the fixture uses.
///
/// Written out rather than reflected over, because reflection would let a constant be RENAMED
/// without anything noticing — and the fixture is keyed by name.
private let speechConstants: [String: String] = [
  "QUEUED_BEHIND_APPROVAL": QUEUED_BEHIND_APPROVAL,
  "QUEUE_POSITION": QUEUE_POSITION,
  "COULD_NOT_START_TASK": COULD_NOT_START_TASK,
  "MACHINE_WAKING": MACHINE_WAKING,
  "MACHINE_AWAKE": MACHINE_AWAKE,
  "MACHINE_WAKE_FAILED": MACHINE_WAKE_FAILED,
  "ROTATED": ROTATED,
  "RESET_RATE_LIMITED": RESET_RATE_LIMITED,
  "RESET_FAILED": RESET_FAILED,
  "RESELECT_NUDGE": RESELECT_NUDGE,
  "NOTHING_QUEUED_TO_RUSH_NUDGE": NOTHING_QUEUED_TO_RUSH_NUDGE,
  "NOTHING_QUEUED_NUDGE": NOTHING_QUEUED_NUDGE,
  "CONFIRM_RESET_NUDGE": CONFIRM_RESET_NUDGE,
]
