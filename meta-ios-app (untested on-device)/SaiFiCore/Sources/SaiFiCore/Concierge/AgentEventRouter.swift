/* sai-fi — voice concierge. */

// What to do with an agent event that warrants a reaction: say it now, hold it until Sai is audible,
// or drop it. A pure decision, so it can be tested without a device.
//
// Ported from Android `AgentEventRouter.kt`.

import Foundation

/// What `AgentEventRouter.route` decided.
public enum NudgeAction: Equatable, Sendable {
  /// Nothing to react to (ordinary progress, an internal event).
  case ignore
  /// Inject now. `kind` is the log label, which names WHY this wording was chosen.
  case inject(kind: String, nudge: String)
  /// A failed step, injected now, and the throttle window restarts from this moment.
  case injectStepFailure(nudge: String)
  /// Hold until Sai is audible again; `HeldNudgeQueue` collapses and replays these.
  case hold(kind: String, nudge: String)
  /// Deliberately not delivered. `why` is for the log — these are decisions, not losses.
  case drop(why: String)
}

/// How long the user has actually been QUIET, for the ask-first gate.
///
/// Not simply `now − lastUserSpeechAt`: a user who asked for something and is sitting there waiting
/// for it is silent for exactly as long as the work takes, and reading that as absence is what
/// silences the answer they were waiting to hear. So the clock stops at `workStartedAt` — the moment
/// we began doing something for them, since they last spoke — and everything after it is the wait,
/// not the absence.
///
/// `workStartedAt` is the FIRST such moment, not the latest, and that distinction is the whole bug
/// it was written for. It used to be the moment the task was forwarded, which is the same instant
/// on the ordinary path — but a vision task is held on the device until the glasses photo lands, so
/// a 40-second capture sat between the user's request and the forward and was counted as 40 seconds
/// of absence. The user had spoken half a second before the camera started.
///
/// A stale stamp (work that began before they last spoke) is ignored, which is what the `>=` is for:
/// their speech is the newer fact.
///
/// THE TRADE-OFF, chosen deliberately. A user who asks for something and then genuinely walks away
/// is indistinguishable from one who asks and waits — the silence is identical and nothing else is
/// observable — so this rule fails towards DELIVERING.
public func userQuietMs(now: Int64, lastUserSpeechAt: Int64, workStartedAt: Int64) -> Int64 {
  if lastUserSpeechAt == 0 { return Int64.max }
  if workStartedAt >= lastUserSpeechAt { return workStartedAt - lastUserSpeechAt }
  return now - lastUserSpeechAt
}

public enum AgentEventRouter {

  public static func route(
    event: JsonObject,
    muted: Bool,
    /// Since the user last spoke. `Int64.max` when they never have this call.
    userQuietMs: Int64,
    askFirstThresholdMs: Int64,
    /// Since the last step-failure nudge went out. `Int64.max` if none has.
    sinceLastStepFailureMs: Int64,
    stepFailureIntervalMs: Int64
  ) -> NudgeAction {
    switch event.optString("type") {
    case "progress":
      if !event.optBool("failed", false) { return .ignore }
      if sinceLastStepFailureMs < stepFailureIntervalMs {
        return .drop(
          why: "step-failed — throttled (told Sai \(sinceLastStepFailureMs / 1000)s ago)")
      }
      if muted { return .drop(why: "not holding step-failed while muted — it will be stale") }
      return .injectStepFailure(nudge: describeAgentEvent(event))
    case "notice":
      if muted { return .drop(why: "not holding notice while muted — it will be stale") }
      return .inject(kind: "notice", nudge: describeAgentEvent(event))
    default:
      break
    }

    let type = event.optString("type")
    let askFirst = type == "complete" && (muted || userQuietMs > askFirstThresholdMs)
    let nudge = askFirst ? describeCompleteAskFirst(event) : describeAgentEvent(event)
    if nudge.isEmpty { return .ignore }

    let kind: String
    if !askFirst {
      kind = type
    } else if muted {
      kind = "complete (ask-first: muted)"
    } else if userQuietMs == Int64.max {
      kind = "complete (ask-first: user never spoke)"
    } else {
      kind = "complete (ask-first: user quiet \(userQuietMs / 1000)s)"
    }
    return muted ? .hold(kind: type, nudge: nudge) : .inject(kind: kind, nudge: nudge)
  }
}
