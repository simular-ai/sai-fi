/* sai-fi — voice concierge. */

// The closed loop, with everything real except the brain and the agent.
//
//     brain ──tool calls──▶ LiveTurnGate ──effects──▶ Concierge ──▶ HttpAgentBridge
//       ▲                        │                       │              │
//       │                   defer/inject                 │       ScriptedAgent (VoiceTransport)
//       │                        ▲                       ▼              │
//       └───nudges / [system]────┴── AgentEventRouter ◀── agent events ──┘
//
// Layer 1 (the golden catalog) fakes the brain; layer 2 (the eval) fakes the FSM and the agent. The
// failures that live in the JOIN are invisible to both, and they are the ones the on-device check
// calls its highest risk — "if Sai says 'I'll do that next' and then it never runs, the drain never
// fired". Nothing in either layer can catch that, because in one the drain is scripted and in the
// other there is no queue at all.
//
// What is REAL here: Concierge (the FSM), HttpAgentBridge and its transport seam, LiveTurnGate,
// LiveVoiceChannel, AgentEventRouter, HeldNudgeQueue, ActivityLog, and the effect-parse boundary
// (`applyClientEffects`) the model's tool calls actually go through.
//
// What is not: the model (a Brain) and the agent (a ScriptedAgent).
//
// The client-policy block below — activity log, nudge routing, mute holding — is deliberately a
// mirror of CallService's `onAgentEvent`. That code is welded to a Service and cannot be reached
// from a JVM test; keeping the mirror small and pointing at the original is the cheaper half of the
// trade. If the two drift, the on-device check is what notices, so any change there belongs here too.
//
// Ported from Android `conversation/ConversationHarness.kt`. The presenter feed is out of scope.

import Foundation
import os

/// A line in the conversation, as a person listening to the call would experience it.
public struct Line: Sendable, Equatable {
  public let speaker: String
  public let text: String
}

/// Defaults mirroring CallService's.
private let defaultAskFirstMs: Int64 = 45_000
private let stepFailureNudgeIntervalMs: Int64 = 60_000

private final class TransportBox: VoiceTransport, @unchecked Sendable {
  var inner: (any VoiceTransport)?
  var fallback: (any VoiceTransport)!
  private var dest: any VoiceTransport { inner ?? fallback }
  func sendMessage(
    machineId: String, message: String, attachments: JsonArray?, follow: Bool
  ) async throws {
    try await dest.sendMessage(
      machineId: machineId, message: message, attachments: attachments, follow: follow)
  }
  func abandonTurn() { dest.abandonTurn() }
  func post(path: String, body: JsonObject) async throws -> JsonObject {
    try await dest.post(path: path, body: body)
  }
}

private final class DeliverBox: @unchecked Sendable {
  var deliver: @Sendable (AgentEvent) async -> Void = { _ in }
}

private final class SpeakBox: @unchecked Sendable {
  var push: @Sendable (String, String) -> Void = { _, _ in }
}

private final class SessionBox: @unchecked Sendable {
  var onState: @Sendable (AgentEvent) async -> Void = { _ in }
}

private final class HarnessRef: @unchecked Sendable {
  weak var harness: ConversationHarness?
}

public final class ConversationHarness: @unchecked Sendable {
  private let brain: any Brain
  public let clock: HarnessClock
  /// How long the model's speech occupies the channel. The window in which a nudge gets deferred.
  public var speakingMs: Int64

  private let logBox = OSAllocatedUnfairLock(initialState: [String]())
  public var log: [String] { logBox.withLock { $0 } }

  public let gate: LiveTurnGate
  public let activityLog: ActivityLog
  private let heldNudges = HeldNudgeQueue()
  public let agent: ScriptedAgent
  public let concierge: Concierge
  private let bridge: HttpAgentBridge
  private let transportBox: TransportBox
  private let me = HarnessRef()

  public private(set) var transcript: [Line] = []
  public private(set) var sessionStates: [AgentEvent] = []
  public private(set) var muted = false

  private var lastUserSpeechAt: Int64 = 0
  /// Mirrors CallService's field of the same name — the quiet clock's stop point. See `userQuietMs`.
  private var workStartedAt: Int64 = 0
  private var lastStepFailureNudgeAt: Int64 = 0

  public init(brain: any Brain, clock: HarnessClock = HarnessClock(), speakingMs: Int64 = 800) {
    self.brain = brain
    self.clock = clock
    self.speakingMs = speakingMs

    let logFn: @Sendable (String) -> Void = { [logBox] line in
      logBox.withLock { $0.append(line) }
    }
    self.gate = LiveTurnGate { clock.now }
    self.activityLog = ActivityLog { clock.now }

    let deliverBox = DeliverBox()
    let agent = ScriptedAgent(
      clock: clock,
      deliver: { await deliverBox.deliver($0) },
      log: logFn)
    self.agent = agent

    let box = TransportBox()
    box.fallback = agent
    self.transportBox = box

    let bridge = HttpAgentBridge(
      machineId: "M-harness",
      transport: box,
      log: logFn,
      nowMs: { clock.now })
    self.bridge = bridge

    let speakBox = SpeakBox()
    let voice = LiveVoiceChannel { kind, text in
      speakBox.push(kind, text)
    }

    let sessionBox = SessionBox()
    self.concierge = Concierge(
      agent: bridge,
      voice: voice,
      engine: ClientBrain(),
      timer: clock,
      onSessionState: { await sessionBox.onState($0) },
      now: { clock.now },
      log: logFn)

    sessionBox.onState = { [weak self] s in
      self?.sessionStates.append(s)
      self?.activityLog.record(agentEventToJson(s))
    }

    deliverBox.deliver = { [me] event in
      await me.harness?.onAgentEvent(event)
    }
    speakBox.push = { [me] kind, text in
      guard let harness = me.harness else { return }
      harness.clock.scheduleSuspending(delayMs: 0) {
        await harness.runGate(harness.gate.injectNudge(kind, text))
      }
    }
    me.harness = self
  }

  /// Send this run's traffic to `replacement` instead of the scripted agent. Call before `start`.
  public func useTransport(_ replacement: any VoiceTransport) {
    transportBox.inner = replacement
  }

  /// Hand an agent event to the client, as the turn-stream reader does.
  public func deliverAgentEvent(_ event: AgentEvent) async { await onAgentEvent(event) }

  /// Leave a photo on the bridge for whatever writes next — what a real capture does when it lands.
  public func stashAttachment(_ attachment: TaskAttachment) {
    bridge.addPendingAttachment(attachment)
  }

  public func state() async -> ConciergeState { await concierge.getState() }

  /// What `getSaiStatus` would answer right now — the same renderer the device uses.
  public func status() -> String { activityLog.statusText() }

  private func logLine(_ line: String) {
    logBox.withLock { $0.append(line) }
  }

  /// Note that work the user is waiting on has begun, keeping the FIRST such moment since they spoke.
  private func markWorkStarted() {
    if workStartedAt < lastUserSpeechAt || workStartedAt == 0 { workStartedAt = clock.now }
  }

  // ── Driving the conversation ───────────────────────────────────────────────

  /// Bring the Live session up.
  public func start() async {
    await runGate(gate.onConnect())
    await runGate(gate.onSetupComplete())
  }

  /// The user says something.
  public func user(_ utterance: String) async {
    lastUserSpeechAt = clock.now
    let s = await concierge.getState()
    if !s.inFlight.isEmpty || !s.queue.isEmpty { markWorkStarted() }
    transcript.append(Line(speaker: "you", text: utterance))
    _ = gate.onUserTranscript(utterance)
    await modelTurn(utterance)
  }

  /// The user talks over the model.
  ///
  /// Two things happen on a real barge-in and both matter: the server VAD raises `interrupted`, which
  /// ends the model's turn and opens the discard window, and then the new utterance is a fresh turn.
  public func bargeIn(_ utterance: String) async {
    await runGate(gate.onInterrupted())
    logLine("— barge-in —")
    await user(utterance)
  }

  /// Mute / unmute, which on the device is the temple button or the on-screen control.
  public func setMuted(_ value: Bool) async {
    muted = value
    if value {
      await runGate(gate.injectSessionState("muted", "[system] you are muted", sticky: true))
    } else {
      await runGate(gate.injectSessionState("unmuted", "[system] you are unmuted", sticky: false))
      for held in heldNudges.drain() {
        await runGate(gate.injectNudge(held.kind, held.nudge))
      }
    }
  }

  /// A token-expiry reconnect mid-call.
  public func reconnect() async {
    await runGate(gate.onConnect())
    await runGate(gate.onSetupComplete())
  }

  /// Let time pass, delivering whatever the agent had scheduled.
  public func advance(_ ms: Int64) async throws { try await clock.advance(ms) }

  /// Run until the agent has nothing left to say.
  public func settle() async throws { try await clock.drain() }

  // ── The model's half ───────────────────────────────────────────────────────

  /// Give the model a turn, and put whatever it decided through the real boundaries.
  ///
  /// The order matters and mirrors the device: the model's speech opens the turn (so a nudge arriving
  /// now is deferred by the gate), its tool calls go through the same routing GeminiLiveClient
  /// applies, and the turn closes `speakingMs` later — which is when anything held gets released.
  private func modelTurn(_ input: String) async {
    let turn = await brain.turn(input: input, state: await concierge.getState())
    if let speech = turn.speech, !speech.isEmpty {
      _ = gate.onSaiTranscript(speech)
      transcript.append(Line(speaker: "sai", text: speech))
    }
    await routeCalls(turn.calls)
    // The turn ends after the model has finished speaking, not instantly: the gap is the window in
    // which a completion landing mid-sentence is held, which is the race worth testing.
    //
    // WHICH clock matters, and getting it wrong is silent. A live transport delivers on real time and
    // nothing advances the virtual one, so scheduling the turn end there meant it never fired:
    // `modelSpeaking` stayed true for the rest of the call and every nudge was deferred behind a turn
    // that would never end. The model looked mute when it had simply never been told anything — the
    // same shape as the bug LiveTurnGate was extracted for, reproduced in the harness, and it spoiled
    // a live demo before the cause was spotted.
    let speakFor = (turn.speech?.isEmpty == false) ? speakingMs : Int64(0)
    if transportBox.inner != nil {
      try? await Task.sleep(nanoseconds: UInt64(max(speakFor, 0)) * 1_000_000)
      await runGate(gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true))
    } else {
      clock.scheduleSuspending(delayMs: speakFor) { [me] in
        guard let harness = me.harness else { return }
        await harness.runGate(harness.gate.onGenerationOrTurnEnd(generationEnded: false, turnEnded: true))
      }
    }
  }

  /// The tool-call half of GeminiLiveClient.handleToolCall, minus the socket.
  private func routeCalls(_ calls: JsonArray) async {
    // Mirrors the real client: the gate is told a turn is in flight BEFORE any effect reaches the
    // FSM, because the FSM answers some of them by speaking and a spoken line is a client turn.
    gate.onToolCall()
    var effects: [JsonObject] = []
    let hasCapture = (0..<calls.count).contains {
      calls.optObject($0)?.optString("name") == "captureImage"
    }
    for i in 0..<calls.count {
      guard let c = calls.optObject(i) else { continue }
      let name = c.optString("name")
      let effect = fcToEffect(c)
      switch name {
      case "getSaiStatus":
        logLine("→ tool: getSaiStatus → \(status())")
      case "getLocalTime":
        logLine("→ tool: getLocalTime → \(describePhoneClock(nowMs: clock.now, timeZone: .current))")
      case "forwardToAgent", "enqueue", "relayToAgent":
        let wantsPhoto = c.optObject("args")?.optBool("attachLatestImage", false) == true
        switch gate.routeTaskCall(
          name: name, effect: effect, wantsPhoto: wantsPhoto, hasCapture: hasCapture
        ) {
        case .heldForPhoto(_, let log):
          logLine(log)
        case .emit(let log):
          effects.append(effect)
          logLine(log)
        }
      default:
        effects.append(effect)
        logLine("→ effect: \(name)")
      }
    }
    if !effects.isEmpty {
      markWorkStarted()
      _ = await concierge.applyClientEffects(JsonArray(effects.map(\.raw)))
    }
  }

  /// `{ kind: name, ...args }` — the concierge effect shape, as GeminiLiveClient builds it.
  private func fcToEffect(_ fc: JsonObject) -> JsonObject {
    var raw: [String: Any] = ["kind": fc.optString("name")]
    if let args = fc.optObject("args") {
      for (k, v) in args.raw { raw[k] = v }
    }
    return jsonWire(raw)
  }

  // ── The client's own policy on agent events (mirrors CallService.onAgentEvent)

  private func onAgentEvent(_ e: AgentEvent) async {
    // Mirrors the gate in VoiceSession's reader, and covers all three sinks for the same reason it
    // does: the tail of an aborted turn is not spoken, not written to the log `getSaiStatus` answers
    // from, and not given to the FSM. Two of those three are nothing to do with the FSM, which is why
    // suppressing its reaction alone left the result still being read out.
    if await concierge.disownsAgentEvents() {
      logLine("dropped \(eventKindForLog(e)) from an aborted turn")
      return
    }
    _ = await concierge.handleAgentEvent(e)

    let json = agentEventToJson(e)
    activityLog.record(json)
    if case .complete = e {
      transcript.append(Line(speaker: "agent", text: json.optString("summary").isEmpty
        ? json.optString("text") : json.optString("summary")))
    } else if case .error = e {
      transcript.append(Line(speaker: "agent", text: json.optString("summary").isEmpty
        ? json.optString("text") : json.optString("summary")))
    }

    let action = AgentEventRouter.route(
      event: json,
      muted: muted,
      userQuietMs: userQuietMs(now: clock.now, lastUserSpeechAt: lastUserSpeechAt, workStartedAt: workStartedAt),
      askFirstThresholdMs: defaultAskFirstMs,
      sinceLastStepFailureMs: lastStepFailureNudgeAt == 0
        ? Int64.max : clock.now - lastStepFailureNudgeAt,
      stepFailureIntervalMs: stepFailureNudgeIntervalMs)

    switch action {
    case .ignore:
      break
    case .drop(let why):
      logLine("→ nudge: \(why)")
    case .injectStepFailure(let nudge):
      lastStepFailureNudgeAt = clock.now
      await runGate(gate.injectNudge("step-failed", nudge))
    case .inject(let kind, let nudge):
      await runGate(gate.injectNudge(kind, nudge))
    case .hold(let kind, let nudge):
      if !heldNudges.add(kind: kind, nudge: nudge) {
        logLine(
          "→ nudge: \(kind) — discarded while muted (stale by the time it could be heard)")
      }
    }
  }

  // ── The gate's action interpreter ──────────────────────────────────────────

  /// Perform what the gate decided — the same job GeminiLiveClient.run does, except that a turn sent
  /// to the model comes back here as another model turn, which is what closes the loop.
  @discardableResult
  private func runGate(_ actions: [GateAction]) async -> [GateAction] {
    for action in actions {
      switch action {
      case .log(let text):
        logLine(text)
      case .sendTurn(let text):
        await modelTurn(text)
      case .saiTranscript, .userTranscript, .turnComplete, .flushPlayback:
        break
      }
    }
    return actions
  }

  // ── Assertion helpers ──────────────────────────────────────────────────────

  /// Everything the user heard, lowercased and joined — for "was this ever said" checks.
  public func heard() -> String {
    transcript.filter { $0.speaker == "sai" }.map(\.text).joined(separator: " | ").lowercased()
  }

  public func saidSomethingLike(_ fragments: String...) -> Bool {
    let h = heard()
    return fragments.contains { h.contains($0.lowercased()) }
  }

  public func logHas(_ fragment: String) -> Bool {
    log.contains { $0.contains(fragment) }
  }
}
