/* sai-fi — voice concierge. */

// The orchestrator: the one place inputs are serialised and effects are dispatched.
//
// SERIALISATION. Every handler is read-state → suspend on I/O → write-state. Without serialisation
// two of them interleave at the suspension point and the second writes over a state the first already
// changed. Concretely: two forwards both see an empty `inFlight` before either records a turn, and the
// user's restaurant gets booked twice. The server does this with a promise-tail chain; the Kotlin does
// it with one `Mutex` held across suspensions.
//
// This port needs BOTH an actor and an explicit lock, and the reason matters: Swift actors are
// REENTRANT at `await` points, so `actor` alone gives exactly the interleaving the Mutex prevents. The
// actor is what stops anything outside touching FSM state; `AsyncLock` is what survives an `await`.
// See Support/AsyncLock.swift.
//
// WHERE `EffectCtx` WENT. The Kotlin has an `EffectCtx` whose `state` is a getter/setter pair aliasing
// the orchestrator's field, "not a copy: a handler assigns to it and the next handler in the same batch
// sees the assignment". Actor isolation gives that for free — the handlers are `extension Concierge`
// methods in the files named after their Kotlin counterparts, so `self.state` IS the live alias and a
// captured snapshot is not expressible. Everything else `EffectCtx` carried (`agent`, `voice`,
// `clearApprovalTimer`, `relayResolvesApproval`, `publishSessionState`, `log`) is a member here.
//
// Ported from the Android `fsm/Concierge.kt`, which came from cloud-api
// `services/concierge/voice/core/concierge.ts`.

import Foundation

/// How long before an approval expires the user gets a heads-up.
public let APPROVAL_TIMEOUT_LEAD_MS: Int64 = 20_000

/// What prompted a decision.
public enum DecisionInput: Sendable {
  case user(utterance: String)
  case agent(event: AgentEvent)
  case approvalTimeout
}

/// The brain. In production this is the client's own Live model, reached by handing it the input and
/// reading back the tool calls it makes; in the gate it is a script.
public protocol DecisionEngine: Sendable {
  func decide(input: DecisionInput, state: ConciergeState) async -> [Effect]
}

/// The only mapping from a wait reason to a mode.
private let modeForWait: [WaitReason: Mode] = [
  .clarification: .clarifying,
  .urgency: .negotiating,
  .approval: .awaitingUser,
  .input: .awaitingUser,
]

public actor Concierge {
  let agent: any AgentBridge
  let voice: any VoiceChannel
  private let engine: any DecisionEngine
  private let timer: any DelayTimer

  /// Optional sink for the session projection the client's activity log reads.
  private let onSessionState: (@Sendable (AgentEvent) async -> Void)?

  /// Wall clock, injected so the approval lead time is testable — the same idiom ActivityLog uses. An
  /// `expiresAt` is an absolute epoch ms, so the lead calculation needs the same clock the timer runs
  /// on, or a virtual-time test schedules against real now and never fires.
  private let now: @Sendable () -> Int64

  let log: @Sendable (String) -> Void

  /// The live state. Actor-isolated, so this IS the Kotlin's aliased `EffectCtx.state`.
  var state: ConciergeState = initialState()

  private let lock = AsyncLock()
  private var approvalTimer: TimerCancellable?
  private var lastSessionState: String?

  /// Set by the caller so the timer can re-enter the serialised path.
  ///
  /// A plain callback rather than a task launch, because the FSM owns no scope — the caller decides
  /// where the resulting async work runs.
  private var onApprovalTimeoutFired: @Sendable () -> Void = {}

  public init(
    agent: any AgentBridge,
    voice: any VoiceChannel,
    engine: any DecisionEngine,
    timer: any DelayTimer,
    onSessionState: (@Sendable (AgentEvent) async -> Void)? = nil,
    now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
    log: @escaping @Sendable (String) -> Void = { _ in }
  ) {
    self.agent = agent
    self.voice = voice
    self.engine = engine
    self.timer = timer
    self.onSessionState = onSessionState
    self.now = now
    self.log = log
  }

  public func getState() -> ConciergeState { state }

  public func setOnApprovalTimeoutFired(_ body: @escaping @Sendable () -> Void) {
    onApprovalTimeoutFired = body
  }

  /// True while anything arriving from the agent is the tail of a turn the user aborted.
  ///
  /// Read by the client's own event handling, which is a SEPARATE path from this FSM: the nudge that
  /// makes Sai speak a result, and the ActivityLog entry that lets `getSaiStatus` report one, are both
  /// produced there and neither asks the FSM's permission. So suppressing the FSM's reaction alone does
  /// not stop a cancelled task's answer reaching the user — it stops the least of the three. See
  /// `ConciergeState.abortedTurn`.
  public func disownsAgentEvents() -> Bool { state.abortedTurn }

  // ── entry points (all serialised) ──────────────────────────────────────────

  public func handleUserUtterance(_ utterance: String) async -> [Effect] {
    await lock.acquire()
    defer { lock.release() }
    let effects = await engine.decide(input: .user(utterance: utterance), state: state)
    await applyEffects(effects)
    return effects
  }

  /// The shipped path: the client's model already decided, and these are its tool calls.
  public func applyClientEffects(_ raw: JsonArray?) async -> [Effect] {
    await lock.acquire()
    defer { lock.release() }
    let effects = parseEffects(raw)
    await applyEffects(effects)
    return effects
  }

  public func handleAgentEvent(_ event: AgentEvent) async -> [Effect] {
    await lock.acquire()
    defer { lock.release() }

    // Out-of-band resolution, handled BEFORE ingest. A mismatched id is fully inert — no ingest, no
    // drain, no projection — because it is not our approval to react to.
    if case .approvalResolved(let id, _) = event {
      if state.pendingApprovalId != id { return [] }
      clearApprovalTimer()
      state = state.noPendingApproval()
      state.mode = .working
      state.awaiting = nil
      let acked = await engine.decide(input: .agent(event: event), state: state)
      await applyEffects(acked)
      return acked
    }

    // The tail of an ABORTED turn, and fully inert — no ingest, no reaction — for the same reason
    // the mismatched approval above is: it is not ours to react to. The user said stop; the device
    // stopped following the stream and the agent was ASKED to stop, over a round trip, and it does
    // not stop mid-thought. Whatever still arrives is about work they cancelled.
    //
    // Inert rather than "ingested but not voiced", which was the first shape and leaks two ways. A
    // stale `status: processing` would put the FSM back into WORKING with nothing in flight — and
    // draining the queue needs IDLE, so the next task would never start. A stale ApprovalRequest
    // would be recorded as pending and never voiced, parking the call on an approval nobody can
    // answer. Both are the wedge that `endTurn`'s unconditional clear exists to prevent, arrived at
    // from the other direction.
    //
    // The abort has already left the FSM idle and the turn closed, so there is nothing here that
    // ingest still needs to do. See ConciergeState.abortedTurn for why the window ends at the next
    // task and not sooner.
    if state.abortedTurn {
      log("ignored \(eventKind(event)) from the aborted turn")
      return []
    }

    state = ingestAgentEvent(state: state, event: event, timers: IngestTimerBridge(owner: self), log: log)

    // After ingest, so the prompt is populated; before the model reacts, because it is about to
    // voice this approval and the one thing it must not do is pin it on the wrong task.
    if case .approvalRequest = event, state.inFlight.count > 1 {
      await voice.instruct(text: unattributableApprovalNudge(
        inFlight: state.inFlight, prompt: state.pendingApprovalPrompt))
    }

    var effects: [Effect] = []
    if wantsReaction(event) {
      effects = await engine.decide(input: .agent(event: event), state: state)
      await applyEffects(effects)
    }

    await maybeDrainQueue()
    await publishSessionState()
    return effects
  }

  /// The pre-expiry ping fired.
  ///
  /// The re-check inside the lock is the whole point: between the timer firing and the lock being
  /// acquired, an approve or a completion may have cleared the approval.
  public func onApprovalTimeoutWarning() async -> [Effect] {
    await lock.acquire()
    defer { lock.release() }
    if state.pendingApprovalId == nil { return [] }
    let effects = await engine.decide(input: .approvalTimeout, state: state)
    await applyEffects(effects)
    return effects
  }

  public func stop() {
    clearApprovalTimer()
  }

  // ── dispatch ───────────────────────────────────────────────────────────────

  /// Strictly sequential. A batch like [say, forwardToAgent, askAndWait] must apply in order, or the
  /// wait mode lands before startTurn sets `working` and is immediately clobbered.
  func applyEffects(_ effects: [Effect]) async {
    for effect in effects { await applyEffect(effect) }

    // The reset confirmation belongs to the exchange that raised it, and nothing else was expiring it.
    // `startTurn` clears it, but a held reset happens with nothing running — so on a call where the
    // user answered "no, just drop that" the yes-flag survived, and the next stray "forget it",
    // minutes and subjects later, wiped the conversation with no question asked. Any batch that is not
    // another `resetSession` is the user having moved on; asking again is the safe direction.
    if state.resetConfirmAsked == true, !effects.contains(where: { $0 == .resetSession }) {
      state.resetConfirmAsked = nil
    }

    // Every entry point reaches the queue through here, which is why the drain is here and not at the
    // three call sites. It used to hang off `handleAgentEvent` alone, so an `enqueue` decided by the
    // model while nothing was running — no turn in flight, therefore no agent event ever coming —
    // appended to `state.queue` and stopped there, permanently, after the user had been told it was
    // next. That is the invariant below stated as a bug: a path that leaves `mode` at IDLE and does not
    // drain.
    await maybeDrainQueue()
    await publishSessionState()
  }

  private func applyEffect(_ effect: Effect) async {
    switch effect {
    case .say(let text):
      await voice.say(text: text, supersedes: nil)

    // A pure state signal — it does NOT speak. The client's model already voiced the question;
    // speaking it here would double it up and interrupt the model mid-sentence.
    case .askAndWait(_, let waitingFor):
      state.mode = modeForWait[waitingFor] ?? state.mode
      state.awaiting = waitingFor

    case .forwardToAgent(let text):
      await applyForwardToAgent(text: text)

    case .relayToAgent(let answer):
      await applyRelayToAgent(answer: answer)

    case .approve, .deny:
      await applyApprovalDecision(effect)

    case .chooseOption(let values):
      await applyChooseOption(values: values)

    case .enqueue(let task, let urgency):
      applyEnqueue(task: task, urgency: urgency)

    case .interrupt(let scope):
      await applyInterrupt(scope: scope)

    case .cancelQueued(let task):
      await applyCancelQueued(task: task)

    case .sendQueuedNow(let task):
      await applySendQueuedNow(task: task)

    case .setState(let mode):
      state = state.withMode(mode)

    case .resetSession:
      await applyResetSession()

    case .noop:
      break
    }
  }

  /// Which events the brain is told about.
  ///
  /// `progress` only when a step actually failed — otherwise Sai has no idea anything went wrong and
  /// fills the silence with a result it never received. Everything else here is either terminal or
  /// something the user must hear about.
  private func wantsReaction(_ event: AgentEvent) -> Bool {
    switch event {
    case .progress(_, _, let failed): return failed
    case .approvalRequest, .complete, .error, .notice: return true
    default: return false
    }
  }

  /// Start the next held task, if the agent is free. At most one per call.
  ///
  /// This is the ONLY thing that ever starts queued work. The server holds no copy, so a task that
  /// never reaches here never runs at all — which makes every path that can leave `mode` at IDLE a
  /// path that must call this. Missing one strands a task the user was told was coming.
  func maybeDrainQueue() async {
    if state.mode != .idle { return }
    if state.queue.isEmpty { return }
    let index = 0
    let next = state.queue[index]
    state = state.removeQueued(index)
    // Its OWN attachments, never the bridge's current stash.
    _ = try? await agent.forwardTask(text: next.text, attachments: next.attachments)
    state = state.startTurn(next.text)
  }

  /// A relay also resolves the pending approval only for a free-text question with no options.
  ///
  /// An allowlist, deliberately. It was a denylist once, and an `exec` "Command Approval Required" got
  /// silently approved by a relay about a photo.
  func relayResolvesApproval() -> Bool {
    if state.pendingApprovalId == nil { return false }
    if let options = state.pendingApprovalOptions, !options.isEmpty { return false }
    return state.pendingApprovalType == "user_input"
  }

  // ── the approval timer ─────────────────────────────────────────────────────

  func scheduleApprovalTimeout(expiresAt: Int64?) {
    clearApprovalTimer()
    guard let expiresAt else { return }  // no expiry means no ping
    let delay = max(0, expiresAt - now() - APPROVAL_TIMEOUT_LEAD_MS)
    let fire = onApprovalTimeoutFired
    approvalTimer = timer.schedule(delayMs: delay) { fire() }
  }

  func clearApprovalTimer() {
    approvalTimer?.cancel()
    approvalTimer = nil
  }

  // ── the session projection ─────────────────────────────────────────────────

  /// Publish what the client's activity log reads, suppressing an unchanged repeat.
  ///
  /// Deliberately not per-mutation: a queue that drains within one batch is never announced.
  func publishSessionState() async {
    guard let sink = onSessionState else { return }
    let running = state.inFlight.first
    let blockedOn = state.mode == .awaitingUser ? state.pendingApprovalPrompt : nil
    let queued = state.queue.map(\.text)

    // Canonical, fixed key order — a JSON object with unstable ordering would defeat the dedupe. Built
    // by hand rather than through JSONSerialization for exactly that reason: `.sortedKeys` would order
    // them alphabetically, which is a different order from the Kotlin's insertion order, and the only
    // thing that matters is that it is STABLE and matches.
    var parts = ["\"type\":\"session-state\""]
    if let running { parts.append("\"running\":\(jsonString(running))") }
    if let blockedOn { parts.append("\"blockedOn\":\(jsonString(blockedOn))") }
    parts.append("\"queued\":[\(queued.map(jsonString).joined(separator: ","))]")
    let canonical = "{\(parts.joined(separator: ","))}"

    if canonical == lastSessionState { return }
    lastSessionState = canonical
    await sink(.sessionState(running: running, blockedOn: blockedOn, queued: queued))
  }
}

/// A short name for the log line about an ignored event.
private func eventKind(_ event: AgentEvent) -> String {
  switch event {
  case .text: return "Text"
  case .progress: return "Progress"
  case .approvalRequest: return "ApprovalRequest"
  case .approvalResolved: return "ApprovalResolved"
  case .status: return "Status"
  case .complete: return "Complete"
  case .error: return "Error"
  case .sessionState: return "SessionState"
  case .notice: return "Notice"
  }
}

/// Minimal JSON string escaping, for the dedupe key only.
private func jsonString(_ s: String) -> String {
  var out = "\""
  for ch in s.unicodeScalars {
    switch ch {
    case "\"": out += "\\\""
    case "\\": out += "\\\\"
    case "\n": out += "\\n"
    case "\r": out += "\\r"
    case "\t": out += "\\t"
    default:
      if ch.value < 0x20 {
        out += String(format: "\\u%04x", ch.value)
      } else {
        out.unicodeScalars.append(ch)
      }
    }
  }
  return out + "\""
}

/// Bridges the orchestrator's timer into the ingest's view of it.
///
/// A `nonisolated` shim holding the actor: `ingestAgentEvent` is a plain function called from inside
/// the actor, so the hops here are already on the right context.
private struct IngestTimerBridge: IngestTimers {
  let owner: Concierge

  func scheduleApprovalTimeout(expiresAt: Int64?) {
    owner.assumeIsolatedScheduleApprovalTimeout(expiresAt: expiresAt)
  }

  func clearApprovalTimer() {
    owner.assumeIsolatedClearApprovalTimer()
  }
}

extension Concierge {
  /// `ingestAgentEvent` is synchronous and is only ever called from inside the actor, so the timer
  /// calls it makes are already actor-isolated — `assumeIsolated` states that rather than forcing the
  /// ingest to become async, which would let another entry point interleave mid-ingest.
  nonisolated func assumeIsolatedScheduleApprovalTimeout(expiresAt: Int64?) {
    assumeIsolated { $0.scheduleApprovalTimeout(expiresAt: expiresAt) }
  }

  nonisolated func assumeIsolatedClearApprovalTimer() {
    assumeIsolated { $0.clearApprovalTimer() }
  }
}
