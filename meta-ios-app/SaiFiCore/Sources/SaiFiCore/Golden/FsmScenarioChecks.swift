/* sai-fi — voice concierge. */

// The 63-scenario golden catalog, replayed against the Swift FSM.
//
// `parity/fsm-scenarios.json` is generated from the KOTLIN catalog — steps plus a canonical trace of
// everything observable that running them produced. This replays the steps here and compares the
// trace. `docs/CONCIERGE_CLIENT_PROTOCOL.md` §8 says of a port: "that catalog is the spec worth
// copying; each scenario names the failure it prevents." This is that copy, taken as data rather than
// as a transcription, so there is one catalog and two runners.
//
// The trace is a SUPERSET of what the Kotlin's own assertions check. Each `assert` lambda there checks
// the handful of properties its scenario is about; the trace records every bridge call with its
// arguments, every spoken line and supersede tag, every model-facing instruction, every session
// projection, the whole final state, and what `getSaiStatus` would return. For a port the question is
// not "did the important bits match" but "did anything at all differ".

import Foundation

private let expectedScenarioCount = 63

func fsmScenarioChecks(_ fixtures: ParityFixtures) -> [Check] {
  let scenarios: [JsonObject]
  do {
    scenarios = try fixtures.load("fsm-scenarios.json")
  } catch {
    let reason = "\(error)"
    return [Check(name: "fsm-scenarios.json loads") { reason }]
  }

  var checks: [Check] = [
    // The same guard the Kotlin's PORTED_SCENARIO_COUNT is: a catalog that silently shrinks is a gate
    // that goes green with less in it.
    Check(name: "the catalog still has all 63 scenarios") {
      expectEqual(scenarios.count, expectedScenarioCount, "scenario count")
    }
  ]

  for scenario in scenarios {
    let name = scenario.optString("name", "<unnamed>")
    checks.append(
      Check(name: "fsm: \(name)") {
        await replayScenario(scenario)
      })
  }
  return checks
}

/// Drive one scenario through the real Swift FSM and diff the trace.
private func replayScenario(_ scenario: JsonObject) async -> String? {
  let agent = FakeAgent()
  let voice = FakeChannel()
  let timer = VirtualTimer()
  let published = PublishedBox()
  let activityLog = ActivityLog(now: { timer.now })
  let logBox = ActivityLogBox(log: activityLog)

  // The same wiring as the Kotlin runner and the generator: the FSM's clock IS the virtual timer's, so
  // an absolute `expiresAt` and the delay computed from it agree. Wired to real time, an advanceMs step
  // would never reach the deadline.
  let concierge = Concierge(
    agent: agent,
    voice: voice,
    engine: FakeEngine(goldenBrain),
    timer: timer,
    onSessionState: { event in
      guard case .sessionState(let running, let blockedOn, let queued) = event else { return }
      await published.append(running: running, blockedOn: blockedOn, queued: queued)
      logBox.recordSessionState(running: running, blockedOn: blockedOn, queued: queued)
    },
    now: { timer.now })

  for step in scenario.optArray("steps")?.objects() ?? [] {
    switch step.optString("kind") {

    case "user":
      _ = await concierge.handleUserUtterance(step.optString("utterance"))

    case "agent":
      guard let eventJson = step.optObject("event"), let event = decodeAgentEvent(eventJson) else {
        return "could not decode an agent step"
      }
      // The device feeds its ActivityLog from the same agent events, so status assertions see what the
      // user could actually be told.
      logBox.recordAgentEvent(eventJson)
      _ = await concierge.handleAgentEvent(event)

    case "effects":
      _ = await concierge.applyClientEffects(step.optArray("raw"))

    case "advanceMs":
      let fired = timer.advance(step.optInt64OrNil("ms") ?? 0)
      // See VirtualTimer: the FSM schedules only the approval pre-expiry ping, whose production
      // callback is exactly this.
      for _ in 0..<fired { _ = await concierge.onApprovalTimeoutWarning() }

    case "addPhoto":
      await agent.addPendingAttachment(goldenPhoto(step.optString("name")))

    case "failNextForward":
      await agent.failForwardTask()

    case let unknown:
      return "unknown step kind '\(unknown)' — the Kotlin catalog gained a step this port has not"
    }
  }

  guard let expected = scenario.optObject("trace") else { return "scenario has no trace" }
  return await diffTrace(
    expected: expected,
    agent: agent,
    voice: voice,
    concierge: concierge,
    published: published,
    activityLog: activityLog,
    timer: timer)
}

// ── the diff ─────────────────────────────────────────────────────────────────

private func diffTrace(
  expected: JsonObject,
  agent: FakeAgent,
  voice: FakeChannel,
  concierge: Concierge,
  published: PublishedBox,
  activityLog: ActivityLog,
  timer: VirtualTimer
) async -> String? {
  // Bridge calls, with their arguments — the ordering IS the assertion in most scenarios.
  let calls = await agent.calls
  let expectedCalls = expected.optArray("calls")?.objects() ?? []
  if let mismatch = diffCalls(expected: expectedCalls, actual: calls) { return mismatch }

  let spoken = await voice.spoken
  if let mismatch = diffStrings("spoken", expected.optArray("spoken"), spoken) { return mismatch }

  let tags = await voice.supersedeTags
  let expectedTags = expected.optArray("supersedes").map { arr in
    (0..<arr.count).map { arr.optStringStrict($0) }
  } ?? []
  if expectedTags != tags {
    return "supersede tags differ\n      kotlin: \(expectedTags)\n      swift : \(tags)"
  }

  let instructed = await voice.instructed
  if let mismatch = diffStrings("instructed", expected.optArray("instructed"), instructed) {
    return mismatch
  }

  let states = await published.entries
  let expectedStates = expected.optArray("sessionStates")?.objects() ?? []
  if expectedStates.count != states.count {
    return "session projections: expected \(expectedStates.count), got \(states.count)"
  }
  for (i, projection) in states.enumerated() {
    let e = expectedStates[i]
    if e.str("running") != projection.running {
      return "sessionStates[\(i)].running: expected \(e.str("running") ?? "nil"), got \(projection.running ?? "nil")"
    }
    if e.str("blockedOn") != projection.blockedOn {
      return "sessionStates[\(i)].blockedOn: expected \(e.str("blockedOn") ?? "nil"), got \(projection.blockedOn ?? "nil")"
    }
    let eq = e.optArray("queued")?.strings() ?? []
    if eq != projection.queued {
      return "sessionStates[\(i)].queued: expected \(eq), got \(projection.queued)"
    }
  }

  let state = await concierge.getState()
  if let mismatch = diffState(expected.optObject("state"), state) { return mismatch }

  let status = activityLog.statusText()
  let expectedStatus = expected.optString("status")
  if status != expectedStatus {
    return """
      getSaiStatus differs
            kotlin: \(expectedStatus)
            swift : \(status)
      """
  }

  if let expectedPending = expected.optIntOrNil("pendingTimers"), expectedPending != timer.pending {
    return "pending timers: expected \(expectedPending), got \(timer.pending)"
  }

  return nil
}

private func diffCalls(expected: [JsonObject], actual: [BridgeCall]) -> String? {
  if expected.count != actual.count {
    return """
      bridge call count differs
            kotlin: \(expected.map { $0.optString("method") })
            swift : \(actual.map(\.method))
      """
  }
  for (i, call) in actual.enumerated() {
    let e = expected[i]
    if e.optString("method") != call.method {
      return "calls[\(i)].method: expected \(e.optString("method")), got \(call.method)"
    }
    if e.str("text") != call.text {
      return "calls[\(i)].text: expected \(e.str("text") ?? "nil"), got \(call.text ?? "nil")"
    }
    // `attachments` is present-but-null when the forward carried none, and absent for a method that
    // has no attachments at all. Only compare when the fixture says the key exists.
    if e.has("attachments") || e.raw.keys.contains("attachments") {
      let expectedAtts = e.optArray("attachments")?.strings()
      if expectedAtts ?? [] != call.attachments ?? [] {
        return "calls[\(i)].attachments: expected \(expectedAtts.map(String.init(describing:)) ?? "nil"), "
          + "got \(call.attachments.map(String.init(describing:)) ?? "nil")"
      }
    }
    if e.str("id") != call.id {
      return "calls[\(i)].id: expected \(e.str("id") ?? "nil"), got \(call.id ?? "nil")"
    }
    if e.str("decision") != call.decision {
      return "calls[\(i)].decision: expected \(e.str("decision") ?? "nil"), got \(call.decision ?? "nil")"
    }
    if e.raw.keys.contains("selection") {
      let expectedSel = e.optArray("selection").map { arr in
        arr.objects().isEmpty
          ? (0..<arr.count).compactMap { (arr.opt($0) as? [Any])?.compactMap { $0 as? String } }
          : []
      }
      let actualSel = call.selection
      if (expectedSel ?? []) != (actualSel ?? []) {
        return "calls[\(i)].selection: expected \(expectedSel.map(String.init(describing:)) ?? "nil"), "
          + "got \(actualSel.map(String.init(describing:)) ?? "nil")"
      }
    }
  }
  return nil
}

private func diffStrings(_ label: String, _ expected: JsonArray?, _ actual: [String]) -> String? {
  let want = expected?.strings() ?? []
  if want == actual { return nil }
  return """
    \(label) differs
          kotlin: \(want)
          swift : \(actual)
    """
}

private func diffState(_ expected: JsonObject?, _ state: ConciergeState) -> String? {
  guard let expected else { return "trace has no final state" }

  if expected.optString("mode") != state.mode.rawValue {
    return "state.mode: expected \(expected.optString("mode")), got \(state.mode.rawValue)"
  }
  if expected.str("awaiting") != state.awaiting?.rawValue {
    return "state.awaiting: expected \(expected.str("awaiting") ?? "nil"), got \(state.awaiting?.rawValue ?? "nil")"
  }
  let inFlight = expected.optArray("inFlight")?.strings() ?? []
  if inFlight != state.inFlight {
    return "state.inFlight: expected \(inFlight), got \(state.inFlight)"
  }

  let queue = expected.optArray("queue")?.objects() ?? []
  if queue.count != state.queue.count {
    return "state.queue: expected \(queue.map { $0.optString("text") }), got \(state.queue.map(\.text))"
  }
  for (i, item) in state.queue.enumerated() {
    let e = queue[i]
    if e.optString("text") != item.text {
      return "state.queue[\(i)].text: expected \(e.optString("text")), got \(item.text)"
    }
    if e.optString("urgency") != item.urgency.rawValue {
      return "state.queue[\(i)].urgency: expected \(e.optString("urgency")), got \(item.urgency.rawValue)"
    }
    let atts = e.optArray("attachments")?.strings()
    let actual = item.attachments?.map(\.name)
    if (atts ?? []) != (actual ?? []) {
      return "state.queue[\(i)].attachments: expected \(atts.map(String.init(describing:)) ?? "nil"), "
        + "got \(actual.map(String.init(describing:)) ?? "nil")"
    }
  }

  if expected.str("pendingApprovalId") != state.pendingApprovalId {
    return "state.pendingApprovalId: expected \(expected.str("pendingApprovalId") ?? "nil"), "
      + "got \(state.pendingApprovalId ?? "nil")"
  }
  if expected.str("pendingApprovalPrompt") != state.pendingApprovalPrompt {
    return "state.pendingApprovalPrompt: expected \(expected.str("pendingApprovalPrompt") ?? "nil"), "
      + "got \(state.pendingApprovalPrompt ?? "nil")"
  }
  if expected.str("pendingApprovalType") != state.pendingApprovalType {
    return "state.pendingApprovalType: expected \(expected.str("pendingApprovalType") ?? "nil"), "
      + "got \(state.pendingApprovalType ?? "nil")"
  }
  if expected.optBoolOrNil("pendingApprovalLinkOnly") != state.pendingApprovalLinkOnly {
    return "state.pendingApprovalLinkOnly differs"
  }
  if expected.optBoolOrNil("pendingApprovalAllowOther") != state.pendingApprovalAllowOther {
    return "state.pendingApprovalAllowOther differs"
  }

  let options = expected.optArray("pendingApprovalOptions")?.objects()
  let actualOptions = state.pendingApprovalOptions
  if (options?.count ?? 0) != (actualOptions?.count ?? 0) {
    return "state.pendingApprovalOptions count differs"
  }
  if let options, let actualOptions {
    for (i, option) in actualOptions.enumerated() {
      if options[i].optString("value") != option.value || options[i].optString("label") != option.label {
        return "state.pendingApprovalOptions[\(i)] differs"
      }
    }
  }

  if expected.optBoolOrNil("interruptScopeAsked") != state.interruptScopeAsked {
    return "state.interruptScopeAsked differs"
  }
  if expected.optBoolOrNil("resetConfirmAsked") != state.resetConfirmAsked {
    return "state.resetConfirmAsked differs"
  }
  if expected.optBool("abortedTurn") != state.abortedTurn {
    return "state.abortedTurn: expected \(expected.optBool("abortedTurn")), got \(state.abortedTurn)"
  }
  return nil
}

// ── decoding an agent event from the fixture ─────────────────────────────────

/// The inverse of the generator's `fullAgentEventJson`.
func decodeAgentEvent(_ e: JsonObject) -> AgentEvent? {
  switch e.optString("type") {
  case "text":
    return .text(e.optString("text"))

  case "progress":
    return .progress(text: e.optString("text"), tool: e.str("tool"), failed: e.optBool("failed"))

  case "status":
    guard let status = AgentStatus.fromWire(e.str("status")) else { return nil }
    return .status(status)

  case "complete":
    return .complete(summary: e.str("summary"))

  case "error":
    return .error(e.optString("text"))

  case "notice":
    return .notice(text: e.optString("text"), kind: e.str("kind"))

  case "approval-resolved":
    return .approvalResolved(id: e.optString("id"), status: e.optString("status"))

  case "session-state":
    return .sessionState(
      running: e.str("running"),
      blockedOn: e.str("blockedOn"),
      queued: e.optArray("queued")?.strings() ?? [])

  case "approval-request":
    return .approvalRequest(
      ApprovalRequestPayload(
        id: e.optString("id"),
        title: e.optString("title"),
        description: e.optString("description"),
        approvalType: e.optString("approvalType"),
        isLinkOnly: e.optBool("isLinkOnly"),
        options: e.optArray("options")?.objects().map {
          ApprovalOption(value: $0.optString("value"), label: $0.optString("label"))
        },
        questions: e.optArray("questions")?.objects().map { q in
          ApprovalQuestion(
            options: q.optArray("options")?.objects().map {
              ApprovalOption(value: $0.optString("value"), label: $0.optString("label"))
            } ?? [],
            multiple: q.optBool("multiple"),
            allowOther: q.optBool("allowOther"))
        },
        multiple: e.optBoolOrNil("multiple"),
        allowOther: e.optBoolOrNil("allowOther"),
        expiresAt: e.optInt64OrNil("expiresAt")))

  default:
    return nil
  }
}

// ── small boxes for the replay's collectors ──────────────────────────────────

/// Collects the session projections the FSM published, in order.
private actor PublishedBox {
  struct Entry {
    let running: String?
    let blockedOn: String?
    let queued: [String]
  }
  private(set) var entries: [Entry] = []

  func append(running: String?, blockedOn: String?, queued: [String]) {
    entries.append(Entry(running: running, blockedOn: blockedOn, queued: queued))
  }
}

/// Feeds the real ActivityLog from the replay.
///
/// `ActivityLog` is not Sendable (matching the Kotlin, and for the reason given there), so this box
/// holds it and is only ever touched from the single task driving one scenario.
private final class ActivityLogBox: @unchecked Sendable {
  let log: ActivityLog
  init(log: ActivityLog) { self.log = log }

  func recordAgentEvent(_ e: JsonObject) {
    // The FSM speaks in typed events; ActivityLog was written against the raw frames, and keeping it
    // on those is deliberate — it is fixture-pinned and must not drift to suit the replay. The fixture
    // carries the FULL event, so the lossy subset the Kotlin's `agentEventJson` feeds the log has to be
    // reproduced here rather than passed through whole.
    log.record(JsonObject(activityLogFrame(e)))
  }

  func recordSessionState(running: String?, blockedOn: String?, queued: [String]) {
    var frame: [String: Any] = ["type": "session-state", "queued": queued]
    if let running { frame["running"] = running }
    if let blockedOn { frame["blockedOn"] = blockedOn }
    log.record(JsonObject(frame))
  }
}

/// The subset of an agent event the device's ActivityLog is fed — the Kotlin harness's
/// `agentEventJson`, which is deliberately narrower than the replay fixture's full serialisation.
private func activityLogFrame(_ e: JsonObject) -> [String: Any] {
  switch e.optString("type") {
  case "text":
    return ["type": "text", "text": e.optString("text")]

  case "progress":
    var frame: [String: Any] = ["type": "progress", "text": e.optString("text")]
    if let tool = e.str("tool") { frame["tool"] = tool }
    if e.optBool("failed") { frame["failed"] = true }
    return frame

  case "status":
    return ["type": "status", "status": e.optString("status")]

  case "complete":
    var frame: [String: Any] = ["type": "complete"]
    if let summary = e.str("summary") { frame["summary"] = summary }
    return frame

  case "error":
    return ["type": "error", "text": e.optString("text")]

  case "notice":
    return ["type": "notice", "text": e.optString("text")]

  case "approval-request":
    return [
      "type": "approval-request",
      "id": e.optString("id"),
      "title": e.optString("title"),
      "description": e.optString("description"),
      "approvalType": e.optString("approvalType"),
      "isLinkOnly": e.optBool("isLinkOnly"),
    ]

  case "approval-resolved":
    return ["type": "approval-resolved", "id": e.optString("id"), "status": e.optString("status")]

  default:
    return ["type": e.optString("type")]
  }
}
