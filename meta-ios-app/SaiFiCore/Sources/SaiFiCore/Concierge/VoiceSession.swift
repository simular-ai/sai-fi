/* sai-fi — voice concierge. */

// One call's concierge: the FSM, its two ports, and the turn streams that drive it.
//
// This replaces ConciergeSocket. The socket carried effects UP to a server-side FSM and brought
// speak/instruct back DOWN; now the FSM is here, so effects go straight into it and the only thing
// arriving from the server is agent events.
//
// There is no persistent connection. A turn's events arrive on the response to the message that
// started it, so this session is CONNECTED ONLY WHILE THE AGENT IS WORKING.
//
// Ported from Android `VoiceSession.kt`.

import Foundation
import os

/// Told to the FSM when a turn's stream ends without the agent saying how it went.
///
/// Phrased as a fact about THIS DEVICE's knowledge, not about the task: the agent may well still be
/// working, and the one thing that must not be said is that it finished. The model turns this into
/// whatever it wants to tell the user; what matters here is that it never hears "done".
///
/// Copied byte for byte from Android `VoiceSession.kt`.
public let TURN_STREAM_LOST =
  "lost the connection to the agent partway through, so the outcome of that task is unknown — "
  + "it may still be running"

/// The FSM's voice out, wired to the Live model.
///
/// `say` is wrapped in "say this verbatim" and `instruct` is not — that difference is the whole
/// point of the two methods, and collapsing them is how a user ends up hearing a function name
/// read aloud.
public final class LiveVoiceChannel: VoiceChannel, @unchecked Sendable {
  private let speak: @Sendable (String, String) -> Void

  public init(speak: @escaping @Sendable (String, String) -> Void) {
    self.speak = speak
  }

  public func say(text: String, supersedes: String?) async {
    // The subject rides in the KIND, which is already the gate's key for a nudge — so superseding
    // needs no new channel between here and there.
    let kind = supersedes != nil ? "speak:\(supersedes!)" : "speak"
    speak(kind, "[system] Say to the user, briefly and verbatim: \"\(text)\"")
  }

  public func instruct(text: String) async {
    // Injected as sent, with no wrapper: the words themselves are never spoken, and what the user
    // hears is the model's own reply to them.
    speak("instruct", text)
  }
}

/// The brain, as the FSM sees it.
///
/// There is nothing to decide here: the Live model on this device already decided, and its tool
/// calls ARE the effects. The FSM asks for a decision at points where a server-side brain would
/// have been consulted; on this path those return nothing and the model acts on its own.
public struct ClientBrain: DecisionEngine {
  public init() {}
  public func decide(input: DecisionInput, state: ConciergeState) async -> [Effect] { [] }
}

/// Everything one call needs on the concierge side.
///
/// Owns the FSM, the bridge, and the reader that feeds agent events into it. A drop mid-turn is
/// an error (`TURN_STREAM_LOST`), never a completion. A steer (`follow = false`) discards its own
/// stream or every event is delivered twice.
///
/// A call does NOT own a conversation. The session is the SERVER's, resolved through its
/// `{uid}_{machineId}_{channel}` pointer, and the only thing that rotates it is the user saying so.
public final class VoiceSession: @unchecked Sendable {
  /// Mirrors the server guard's old defaults: an hour of call, five minutes of silence.
  public static let defaultMaxCallMs: Int64 = 60 * 60_000
  public static let defaultIdleMs: Int64 = 5 * 60_000

  public let machineId: String
  public let bridge: HttpAgentBridge

  private let baseUrl: String
  private let tokenProvider: @Sendable () async -> String?
  private let versionTag: String
  private let speak: @Sendable (String, String) -> Void
  private let onAgentEvent: @Sendable (AgentEvent) async -> Void
  private let onConnectionChange: @Sendable (Bool) -> Void
  private let onPermanentFailure: @Sendable (Int) -> Void
  private let onCostGuard: @Sendable (CostGuardReason) -> Void
  private let onLog: @Sendable (String) -> Void
  private let http: (any ByteStreaming & DataExchanging)?

  private let lock = OSAllocatedUnfairLock(initialState: SessionBits())
  private let concierge: Concierge
  private let guard_: CostGuard

  private struct SessionBits {
    var turnTask: Task<Void, Never>?
    var turnStream: VoiceChannelClient.TurnStream?
    var turnGeneration = 0
    var active = false
    var lastResponseTokens = 0
  }

  public init(
    baseUrl: String,
    tokenProvider: @escaping @Sendable () async -> String?,
    machineId: String,
    speak: @escaping @Sendable (String, String) -> Void,
    onAgentEvent: @escaping @Sendable (AgentEvent) async -> Void,
    onConnectionChange: @escaping @Sendable (Bool) -> Void,
    onPermanentFailure: @escaping @Sendable (Int) -> Void,
    onCostGuard: @escaping @Sendable (CostGuardReason) -> Void = { _ in },
    abortLocalWork: @escaping @Sendable () -> Void = {},
    maxCallMs: Int64? = VoiceSession.defaultMaxCallMs,
    idleMs: Int64? = VoiceSession.defaultIdleMs,
    onLog: @escaping @Sendable (String) -> Void = { _ in },
    timer: any DelayTimer,
    versionTag: String = "",
    http: (any ByteStreaming & DataExchanging)? = nil
  ) {
    self.baseUrl = baseUrl
    self.tokenProvider = tokenProvider
    self.machineId = machineId
    self.speak = speak
    self.onAgentEvent = onAgentEvent
    self.onConnectionChange = onConnectionChange
    self.onPermanentFailure = onPermanentFailure
    self.onCostGuard = onCostGuard
    self.onLog = onLog
    self.versionTag = versionTag
    self.http = http

    let transport = LiveVoiceTransport(
      baseUrl: baseUrl,
      machineId: machineId,
      tokenProvider: tokenProvider,
      versionTag: versionTag,
      http: http,
      onLog: onLog)
    self.bridge = HttpAgentBridge(
      machineId: machineId, transport: transport, log: onLog, abortLocalWork: abortLocalWork)

    let concierge = Concierge(
      agent: bridge,
      voice: LiveVoiceChannel(speak: speak),
      engine: ClientBrain(),
      timer: timer,
      log: onLog)
    self.concierge = concierge

    self.guard_ = CostGuard(
      maxMs: maxCallMs,
      idleMs: idleMs,
      timer: timer,
      onExpire: { reason in
        onLog("[voice] cost guard tripped: \(reason.rawValue)")
        onCostGuard(reason)
      })

    transport.owner = self
  }

  /// Register genuine interaction.
  ///
  /// Deliberately NOT called for every agent event: a long task emits progress for minutes with
  /// nobody in the room, and counting that as activity is exactly the walked-away call the idle
  /// bound exists to end. Effects and the user's own speech are the signals that someone is still
  /// here.
  public func touch() { guard_.touch() }

  /// Live usage totals, as the model reports them (cumulative).
  ///
  /// Only a rise in RESPONSE tokens counts as activity. Input tokens grow continuously while a
  /// microphone is merely open, so treating them as a live conversation is exactly the walked-away
  /// call the idle bound exists to end. A decrease means the Live session restarted, so the
  /// baseline follows it down rather than going negative.
  public func onUsage(promptTokens: Int, responseTokens: Int) {
    lock.withLock { bits in
      if responseTokens < bits.lastResponseTokens {
        bits.lastResponseTokens = responseTokens
        return
      }
      if responseTokens > bits.lastResponseTokens {
        bits.lastResponseTokens = responseTokens
        guard_.touch()
      }
    }
  }

  public func start() {
    lock.withLock { $0.active = true }
  }

  /// The model's tool calls, straight into the FSM. No round trip.
  public func applyEffects(_ raw: JsonArray) {
    guard_.touch()
    Task { _ = await concierge.applyClientEffects(raw) }
  }

  public func close() {
    lock.withLock { $0.active = false }
    stopFollowingTurn("the call ended")
    guard_.dispose()
    Task { await concierge.stop() }
  }

  public func state() async -> ConciergeState { await concierge.getState() }

  public func handleAgentEvent(_ event: AgentEvent) async {
    _ = await concierge.handleAgentEvent(event)
  }

  public func disownsAgentEvents() async -> Bool { await concierge.disownsAgentEvents() }

  fileprivate func followTurn(_ stream: VoiceChannelClient.TurnStream) {
    stopFollowingTurn("a newer turn superseded it")
    let gen = lock.withLock { bits -> Int in
      bits.turnStream = stream
      return bits.turnGeneration
    }
    let task = Task { [weak self] in
      guard let self else { return }
      do {
        self.onConnectionChange(true)
        try await stream.read(
          onEvent: { event in
            if Task.isCancelled { return }
            let current = self.lock.withLock { $0.turnGeneration }
            if gen != current {
              self.onLog("[voice] dropped \(eventKindName(event)) from an abandoned turn")
              return
            }
            if await self.concierge.disownsAgentEvents() {
              self.onLog("[voice] dropped \(eventKindName(event)) from an aborted turn")
              return
            }
            await self.onAgentEvent(event)
            _ = await self.concierge.handleAgentEvent(event)
          },
          onLog: self.onLog)
      } catch is CancellationError {
        return
      } catch {
        let current = self.lock.withLock { $0.turnGeneration }
        if gen != current { return }
        self.onLog("[voice] turn stream dropped: \(error.localizedDescription)")
        self.onConnectionChange(false)
      }
      // Whatever ended the stream, the turn is over as far as this device can tell. Told to the
      // FSM as an error rather than a completion: a dropped stream is not a finished task, and
      // reporting it as one is how "all done" gets said about work that may still be running.
      let stillOurs = self.lock.withLock { bits -> Bool in
        bits.active && gen == bits.turnGeneration
      }
      if stillOurs, await self.concierge.getState().isWorking() {
        _ = await self.concierge.handleAgentEvent(.error(TURN_STREAM_LOST))
      }
      self.lock.withLock { bits in
        if bits.turnStream === stream { bits.turnStream = nil }
      }
    }
    lock.withLock { $0.turnTask = task }
  }

  fileprivate func stopFollowingTurn(_ why: String) {
    let had: Bool = lock.withLock { bits in
      bits.turnTask != nil || bits.turnStream != nil
    }
    if !had { return }
    onLog("[voice] stopped following the turn — \(why)")
    lock.withLock { bits in
      bits.turnGeneration += 1
      bits.turnTask?.cancel()
      bits.turnTask = nil
      bits.turnStream?.discard()
      bits.turnStream = nil
    }
  }

  fileprivate func endCallIfRejected<T>(_ block: () async throws -> T) async throws -> T {
    do {
      return try await block()
    } catch let e as ConciergeHttpException {
      if ReconnectPolicy.isPermanent(e.status) {
        onLog("[voice] rejected permanently (\(e.status)) — ending the call")
        onPermanentFailure(e.status)
      }
      throw e
    }
  }

  fileprivate func token() async throws -> String {
    guard let t = await tokenProvider() else { throw ConciergeHttpException(status: 401, message: "no auth token") }
    return t
  }
}

/// The live `VoiceTransport`: open a message stream, follow or discard it, POST operations.
///
/// A steer (`follow = false`) discards its own stream — reading it too would deliver every event
/// of that turn a second time.
private final class LiveVoiceTransport: VoiceTransport, @unchecked Sendable {
  let baseUrl: String
  let machineId: String
  let tokenProvider: @Sendable () async -> String?
  let versionTag: String
  let http: (any ByteStreaming & DataExchanging)?
  let onLog: @Sendable (String) -> Void
  weak var owner: VoiceSession?

  init(
    baseUrl: String,
    machineId: String,
    tokenProvider: @escaping @Sendable () async -> String?,
    versionTag: String,
    http: (any ByteStreaming & DataExchanging)?,
    onLog: @escaping @Sendable (String) -> Void
  ) {
    self.baseUrl = baseUrl
    self.machineId = machineId
    self.tokenProvider = tokenProvider
    self.versionTag = versionTag
    self.http = http
    self.onLog = onLog
  }

  func sendMessage(
    machineId: String,
    message: String,
    attachments: JsonArray?,
    follow: Bool
  ) async throws {
    guard let owner else { return }
    let stream = try await owner.endCallIfRejected {
      let token = try await owner.token()
      return try await VoiceChannelClient.openMessageStream(
        baseUrl: baseUrl,
        bearerToken: token,
        machineId: machineId,
        message: message,
        attachments: attachments,
        versionTag: versionTag,
        http: http)
    }
    // A steer lands in a turn already being read; reading its stream too would deliver every
    // event of that turn a second time.
    if !follow {
      stream.discard()
      return
    }
    owner.followTurn(stream)
  }

  func abandonTurn() {
    owner?.stopFollowingTurn("it was aborted")
  }

  func post(path: String, body: JsonObject) async throws -> JsonObject {
    guard let owner else { return JsonObject([:]) }
    return try await owner.endCallIfRejected {
      var raw = body.raw
      raw["machineId"] = machineId
      let token = try await owner.token()
      return try await VoiceChannelClient.postOperation(
        baseUrl: baseUrl,
        bearerToken: token,
        path: path,
        body: jsonWire(raw),
        versionTag: versionTag,
        http: http)
    }
  }
}

private func eventKindName(_ event: AgentEvent) -> String {
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
