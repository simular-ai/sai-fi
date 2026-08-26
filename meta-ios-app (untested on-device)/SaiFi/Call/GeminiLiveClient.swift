/* sai-fi — voice concierge. */

// GeminiLiveClient — raw-WebSocket client for the Gemini Live API (BidiGenerateContent).
//
// The device holds the user's own Gemini key and opens the v1alpha Live endpoint with it
// (`BidiGenerateContent?key=`). We implement the wire protocol directly with URLSession. This is
// the client-side, low-latency Live session: mic PCM16 up, model audio down, native VAD /
// turn-taking / barge-in. The model's function-calls are effects into the on-device FSM.
//
// Ported from Android `GeminiLiveClient.kt`. Decisions live in SaiFiCore's `LiveTurnGate` /
// `LiveModelParts` / `CaptureNotes`; this file is the I/O interpreter.

import Foundation
import SaiFiCore
import os

/// What one Live session is configured with.
///
/// Built locally from `VoiceProfile` plus the session's machine context. Lives here (not in
/// ConciergeClient) until Phase 4 exists.
public struct SessionBootstrap: Sendable {
  /// e.g. gemini-3.1-flash-live-preview. Ships with the app in `voice-profile.json`.
  public var model: String
  public var systemPrompt: String
  /// Raw JSON array of function declarations, forwarded to the Live session as-is.
  public var toolsJson: String
  public var toolCount: Int
  public var voice: String

  public init(model: String, systemPrompt: String, toolsJson: String, toolCount: Int, voice: String) {
    self.model = model
    self.systemPrompt = systemPrompt
    self.toolsJson = toolsJson
    self.toolCount = toolCount
    self.voice = voice
  }

  public static func from(
    profile: VoiceProfile,
    activeMachine: String? = nil,
    machineNames: [String] = []
  ) -> SessionBootstrap {
    let tools: [[String: Any]] = profile.tools.map { t in
      var d: [String: Any] = [
        "name": t.name,
        "description": t.description,
      ]
      if let p = t.parameters { d["parameters"] = p.raw }
      return d
    }
    let toolsJson =
      (try? String(data: JSONSerialization.data(withJSONObject: tools), encoding: .utf8)) ?? "[]"
    return SessionBootstrap(
      model: profile.model,
      systemPrompt: profile.systemPromptWithContext(
        activeMachine: activeMachine, machineNames: machineNames),
      toolsJson: toolsJson,
      toolCount: profile.tools.count,
      voice: profile.voice)
  }
}

/// Pure wire helpers — URL and setup JSON — so the non-negotiables can be unit-tested.
public enum GeminiLiveWire {
  public static let hostPath =
    "wss://generativelanguage.googleapis.com/ws/"
    + "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

  public static func endpoint(apiKey: String) -> URL? {
    var allowed = CharacterSet.urlQueryAllowed
    allowed.remove(charactersIn: "+&=")
    let encoded = apiKey.addingPercentEncoding(withAllowedCharacters: allowed) ?? apiKey
    return URL(string: "\(hostPath)?key=\(encoded)")
  }

  /// The setup frame. Tools go in as `parametersJsonSchema`, not `parameters`.
  /// `inputAudioTranscription` stays an empty object — do not add `languageCodes`.
  public static func setupObject(_ boot: SessionBootstrap) -> [String: Any] {
    let model = boot.model.hasPrefix("models/") ? boot.model : "models/\(boot.model)"

    var declarations: [[String: Any]] = []
    if let tools = JsonArray(string: boot.toolsJson) {
      for t in tools.objects() {
        var d: [String: Any] = [
          "name": t.optString("name"),
          "description": t.optString("description"),
        ]
        if let params = t.opt("parameters") {
          d["parametersJsonSchema"] = params
        }
        declarations.append(d)
      }
    }

    let generationConfig: [String: Any] = [
      "responseModalities": ["AUDIO"],
      "speechConfig": [
        "voiceConfig": [
          "prebuiltVoiceConfig": [
            "voiceName": boot.voice
          ]
        ]
      ],
    ]

    var setup: [String: Any] = [
      "model": model,
      "generationConfig": generationConfig,
      "systemInstruction": [
        "parts": [["text": boot.systemPrompt]]
      ],
      // MUST stay an empty object. A `languageCodes` hint is Vertex-only; the Developer API
      // rejects the whole setup frame and closes with 1007.
      "inputAudioTranscription": [String: Any](),
      "outputAudioTranscription": [String: Any](),
      "realtimeInputConfig": [
        "automaticActivityDetection": [
          "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
          "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
          "prefixPaddingMs": 400,
          "silenceDurationMs": 1200,
        ]
      ],
    ]
    if !declarations.isEmpty {
      setup["tools"] = [["functionDeclarations": declarations]]
    }
    return ["setup": setup]
  }

  public static func jsonString(_ obj: [String: Any]) -> String? {
    guard JSONSerialization.isValidJSONObject(obj),
      let data = try? JSONSerialization.data(withJSONObject: obj, options: [])
    else { return nil }
    return String(data: data, encoding: .utf8)
  }
}

public final class GeminiLiveClient: @unchecked Sendable {

  public typealias AudioHandler = @Sendable (Data) -> Void
  public typealias InterruptedHandler = @Sendable () -> Void
  public typealias TranscriptHandler = @Sendable (_ role: String, _ delta: String) -> Void
  public typealias TurnCompleteHandler = @Sendable () -> Void
  public typealias EffectsHandler = @Sendable ([JsonObject]) -> Void
  public typealias StatusHandler = @Sendable () -> String
  public typealias RecallHandler = @Sendable (_ respond: @escaping (String) -> Void) -> Void
  public typealias SwitchMachineHandler = @Sendable (String) -> String
  public typealias EndCallHandler = @Sendable (_ spokeThisTurn: Bool) -> Void
  public typealias CaptureHandler = @Sendable (_ respond: @escaping (Bool, String) -> Void) -> Void
  public typealias UsageHandler = @Sendable (_ prompt: Int, _ response: Int, _ total: Int) -> Void
  public typealias PhotoDestinedHandler = @Sendable () -> Void
  public typealias LogHandler = @Sendable (String) -> Void
  public typealias ReadyHandler = @Sendable () -> Void
  public typealias ClosedHandler = @Sendable () -> Void

  private let onAudio: AudioHandler
  private let onInterrupted: InterruptedHandler
  private let onTranscript: TranscriptHandler
  private let onTurnComplete: TurnCompleteHandler
  private let onEffects: EffectsHandler
  private let onGetSaiStatus: StatusHandler
  private let onRecallHistory: RecallHandler
  private let onSwitchMachine: SwitchMachineHandler
  private let onEndCall: EndCallHandler
  private let onCaptureImage: CaptureHandler
  private let onUsage: UsageHandler
  private let onPhotoDestined: PhotoDestinedHandler
  private let onLog: LogHandler
  private let onReady: ReadyHandler
  private let onClosed: ClosedHandler

  /// The turn/nudge state machine. It lives in `LiveTurnGate` rather than here because it is the
  /// part worth testing and this class is untestable: a WebSocket, a Base64 and a Log.
  private let gate = LiveTurnGate()

  private let session: URLSession
  private let hook: SocketHook
  private let lock = NSLock()
  private var ws: URLSessionWebSocketTask?
  private var generation = 0
  private var closedNotified = false
  private var receiveTask: Task<Void, Never>?
  private var pingTask: Task<Void, Never>?
  private var pendingSetup: String?

  private let log = Logger(subsystem: "ai.simular.saiglasses", category: "Live")

  private final class SocketHook: NSObject, URLSessionWebSocketDelegate {
    weak var owner: GeminiLiveClient?
    func urlSession(
      _ session: URLSession,
      webSocketTask: URLSessionWebSocketTask,
      didOpenWithProtocol _: String?
    ) {
      owner?.socketDidOpen(webSocketTask)
    }
    func urlSession(
      _ session: URLSession,
      webSocketTask: URLSessionWebSocketTask,
      didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
      reason: Data?
    ) {
      owner?.socketDidClose(webSocketTask, code: closeCode, reason: reason)
    }
  }

  public init(
    onAudio: @escaping AudioHandler,
    onInterrupted: @escaping InterruptedHandler,
    onTranscript: @escaping TranscriptHandler,
    onTurnComplete: @escaping TurnCompleteHandler,
    onEffects: @escaping EffectsHandler,
    onGetSaiStatus: @escaping StatusHandler,
    onRecallHistory: @escaping RecallHandler,
    onSwitchMachine: @escaping SwitchMachineHandler,
    onEndCall: @escaping EndCallHandler,
    onCaptureImage: @escaping CaptureHandler,
    onUsage: @escaping UsageHandler,
    onPhotoDestined: @escaping PhotoDestinedHandler,
    onLog: @escaping LogHandler,
    onReady: @escaping ReadyHandler,
    onClosed: @escaping ClosedHandler
  ) {
    self.onAudio = onAudio
    self.onInterrupted = onInterrupted
    self.onTranscript = onTranscript
    self.onTurnComplete = onTurnComplete
    self.onEffects = onEffects
    self.onGetSaiStatus = onGetSaiStatus
    self.onRecallHistory = onRecallHistory
    self.onSwitchMachine = onSwitchMachine
    self.onEndCall = onEndCall
    self.onCaptureImage = onCaptureImage
    self.onUsage = onUsage
    self.onPhotoDestined = onPhotoDestined
    self.onLog = onLog
    self.onReady = onReady
    self.onClosed = onClosed
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 60
    config.timeoutIntervalForResource = 60 * 60
    let hook = SocketHook()
    self.hook = hook
    self.session = URLSession(configuration: config, delegate: hook, delegateQueue: nil)
    hook.owner = self
  }

  deinit {
    session.invalidateAndCancel()
  }

  /// Perform what the gate decided, in order.
  private func run(_ actions: [GateAction]) {
    for action in actions {
      switch action {
      case .sendTurn(let text): sendClientTurn(text)
      case .log(let text): onLog(text)
      case .saiTranscript(let text): onTranscript("sai", text)
      case .userTranscript(let text): onTranscript("you", text)
      case .turnComplete: onTurnComplete()
      case .flushPlayback: onInterrupted()
      }
    }
  }

  public func connect(boot: SessionBootstrap, apiKey: String) {
    let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    if key.isEmpty {
      onLog("start failed: no gemini_api_key")
      notifyClosed()
      return
    }
    guard let url = GeminiLiveWire.endpoint(apiKey: key) else {
      onLog("start failed: no gemini_api_key")
      notifyClosed()
      return
    }

    run(gate.onConnect())
    lock.lock()
    generation += 1
    let gen = generation
    closedNotified = false
    ws?.cancel(with: .goingAway, reason: nil)
    receiveTask?.cancel()
    pingTask?.cancel()
    let task = session.webSocketTask(with: url)
    ws = task
    lock.unlock()

    pendingSetup = GeminiLiveWire.jsonString(GeminiLiveWire.setupObject(boot))
    task.resume()

    receiveTask = Task { [weak self] in
      await self?.receiveLoop(task, generation: gen)
    }
    pingTask = Task { [weak self] in
      await self?.pingLoop(task, generation: gen)
    }
  }

  /// Send a frame of mic PCM16 (16 kHz mono, little-endian) as realtime input.
  public func sendAudio(_ pcm: Data) {
    let b64 = pcm.base64EncodedString()
    let msg: [String: Any] = [
      "realtimeInput": [
        "audio": [
          "data": b64,
          "mimeType": "audio/pcm;rate=16000",
        ]
      ]
    ]
    if let s = GeminiLiveWire.jsonString(msg) { send(s) }
  }

  /// Send a typed user turn as text (testing without a mic). Same path as speech.
  public func sendText(_ text: String) { sendClientTurn(text) }

  private func sendClientTurn(_ text: String) {
    let msg: [String: Any] = [
      "clientContent": [
        "turns": [
          [
            "role": "user",
            "parts": [["text": text]],
          ]
        ],
        "turnComplete": true,
      ]
    ]
    if let s = GeminiLiveWire.jsonString(msg) { send(s) }
  }

  public func close() {
    gate.onClose()
    lock.lock()
    generation += 1
    ws?.cancel(with: .normalClosure, reason: nil)
    ws = nil
    receiveTask?.cancel()
    pingTask?.cancel()
    receiveTask = nil
    pingTask = nil
    lock.unlock()
  }

  // ── Socket I/O ─────────────────────────────────────────────────────────────

  private func send(_ text: String) {
    lock.lock()
    let task = ws
    lock.unlock()
    task?.send(.string(text)) { _ in }
  }

  private func receiveLoop(_ task: URLSessionWebSocketTask, generation gen: Int) async {
    while !Task.isCancelled {
      lock.lock()
      let current = generation
      lock.unlock()
      guard current == gen else { return }
      do {
        let message = try await task.receive()
        let raw: String
        switch message {
        case .string(let text): raw = text
        case .data(let data): raw = String(data: data, encoding: .utf8) ?? ""
        @unknown default: continue
        }
        if !raw.isEmpty { handle(task, raw) }
      } catch {
        lock.lock()
        let still = generation == gen
        lock.unlock()
        if still && !Task.isCancelled {
          log.error("live socket failure \(error.localizedDescription, privacy: .public)")
          onLog("live: FAILED  \(error.localizedDescription)")
          notifyClosed()
        }
        return
      }
    }
  }

  private func pingLoop(_ task: URLSessionWebSocketTask, generation gen: Int) async {
    while !Task.isCancelled {
      do {
        try await Task.sleep(for: .seconds(20))
      } catch {
        return
      }
      lock.lock()
      let still = generation == gen && ws === task
      lock.unlock()
      guard still else { return }
      task.sendPing { _ in }
    }
  }

  fileprivate func socketDidOpen(_ task: URLSessionWebSocketTask) {
    lock.lock()
    let live = ws === task
    let setup = pendingSetup
    pendingSetup = nil
    lock.unlock()
    guard live else { return }
    onLog("live: socket open — sending setup")
    if let setup { send(setup) }
  }

  fileprivate func socketDidClose(
    _ task: URLSessionWebSocketTask,
    code: URLSessionWebSocketTask.CloseCode,
    reason: Data?
  ) {
    lock.lock()
    let live = ws === task
    lock.unlock()
    guard live else { return }
    let why = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
    onLog("live: closed \(code.rawValue) \(why)")
    notifyClosed()
  }

  private func notifyClosed() {
    lock.lock()
    if closedNotified {
      lock.unlock()
      return
    }
    closedNotified = true
    lock.unlock()
    onClosed()
  }

  // ── Incoming ───────────────────────────────────────────────────────────────

  private func handle(_ sock: URLSessionWebSocketTask, _ raw: String) {
    guard let json = JsonObject(string: raw) else { return }
    if let u = json.optObject("usageMetadata") {
      onUsage(
        u.optInt("promptTokenCount"),
        u.optInt("responseTokenCount"),
        u.optInt("totalTokenCount"))
    }
    if json.has("setupComplete") {
      run(gate.onSetupComplete())
      onReady()
    } else if let sc = json.optObject("serverContent") {
      handleServerContent(sc)
    } else if let toolCall = json.optObject("toolCall") {
      handleToolCall(sock, toolCall)
    }
  }

  private func handleServerContent(_ sc: JsonObject) {
    if sc.optBool("interrupted", false) { run(gate.onInterrupted()) }

    if let text = sc.optObject("inputTranscription")?.str("text") {
      run(gate.onUserTranscript(text))
    }
    if let text = sc.optObject("outputTranscription")?.str("text") {
      run(gate.onSaiTranscript(text))
    }

    if let parts = sc.optObject("modelTurn")?.optArray("parts") {
      // Asked once per frame, not per part: re-reading the clock inside the loop could split a
      // single frame across the discard window's edge.
      let discarding = gate.shouldDiscardAudio()
      let hadTranscription = sc.optObject("outputTranscription")?.str("text") != nil
      for action in LiveModelParts.classifyFrame(parts.objects()) {
        if let line = action.log { onLog(line) }
        if let data = action.playAudioB64, !discarding, let pcm = Data(base64Encoded: data) {
          gate.onAudioAccepted()
          onAudio(pcm)
        }
        if let fallback = action.transcriptFallback, !discarding, !hadTranscription {
          run(gate.onSaiTranscript(fallback))
        }
      }
    }

    run(
      gate.onGenerationOrTurnEnd(
        generationEnded: sc.optBool("generationComplete", false),
        turnEnded: sc.optBool("turnComplete", false)))
  }

  /// Relay the model's function-calls to the concierge as effects, then tool-respond to EVERY call
  /// so the model continues its turn. `getSaiStatus` is answered locally (never forwarded).
  private func handleToolCall(_ sock: URLSessionWebSocketTask, _ toolCall: JsonObject) {
    guard let calls = toolCall.optArray("functionCalls") else { return }
    // FIRST, before a single call is dispatched.
    gate.onToolCall()
    var effects: [JsonObject] = []
    var responses: [[String: Any]] = []
    let hasCapture = calls.objects().contains { $0.optString("name") == "captureImage" }

    for i in 0..<calls.count {
      guard let c = calls.optObject(i) else { continue }
      let name = c.optString("name")
      if name == "recallHistory" {
        onLog("🕘 recallHistory")
        let id = c.opt("id")
        onRecallHistory { [weak self] history in
          self?.sendToolResponseTo(
            sock, id: id, name: name, response: ["history": history])
        }
        continue
      }
      if name == "captureImage" {
        onLog("📷 captureImage")
        let id = c.opt("id")
        gate.onCaptureStarted()
        // Answer IMMEDIATELY. The model cannot produce speech while a tool call in the batch is
        // unanswered. The outcome arrives later as a nudge.
        sendToolResponseTo(
          sock,
          id: id,
          name: name,
          response: [
            "result": "capture-started",
            "note": CaptureNotes.started,
          ])
        onCaptureImage { [weak self] ok, message in
          guard let self else { return }
          let released = self.gate.onCaptureSettled()
          if !self.gate.claimOutcomeNudge() {
            self.onLog("📷 outcome already relayed — not telling Sai twice")
            return
          }
          if ok {
            if !released.effects.isEmpty {
              self.onLog(
                "→ effect: released \(released.names.joined(separator: ", ")) (photo held, attaching if asked)"
              )
              self.onEffects(released.effects)
            }
            self.injectNudge(
              released.effects.isEmpty ? "capture-landed" : "capture-landed+task",
              released.effects.isEmpty
                ? "[agent] The glasses photo landed. It is SAVED on the device and has NOT been sent "
                  + "anywhere — it goes only when a request carries it. Acknowledge in a few words "
                  + "that you have it; do not say you sent it or that anything is underway."
                : "[agent] The glasses photo landed and the task you queued has now started. Don't "
                  + "re-describe the photo — you haven't seen it, the task has."
            )
          } else {
            for n in released.names {
              self.onLog("✗ dropped \(n) — it needed the photo, and the capture failed")
            }
            self.injectNudge(
              "capture-failed",
              "[agent] The glasses capture FAILED: \(message). Nothing was forwarded and no photo "
                + "exists. Tell the user plainly that the capture failed and why, and offer to try "
                + "again. Do NOT claim any task is running or done, and do NOT describe what they "
                + "are looking at — you cannot see it."
            )
          }
        }
        continue
      }

      let response: [String: Any]
      switch name {
      case "getSaiStatus":
        response = ["status": onGetSaiStatus()]
      case "getLocalTime":
        onLog("→ tool: getLocalTime")
        response = ["time": Self.phoneClock()]
      case "switchMachine":
        let target = c.optObject("args")?.optString("machine") ?? ""
        onLog("↺ switchMachine: \(target)")
        response = ["result": onSwitchMachine(target)]
      case "endCall":
        onLog("⏻ endCall")
        onEndCall(gate.didSpeakThisTurn)
        response = ["result": "ok"]
      case "forwardToAgent", "enqueue", "relayToAgent":
        let wantsPhoto = c.optObject("args")?.optBool("attachLatestImage") == true
        switch gate.routeTaskCall(
          name: name, effect: fcToEffect(c), wantsPhoto: wantsPhoto, hasCapture: hasCapture)
        {
        case .heldForPhoto(let held, let line):
          onPhotoDestined()
          onLog(line)
          response = held.raw
        case .emit(let line):
          effects.append(fcToEffect(c))
          onLog(line)
          response = ["result": "ok"]
        }
      default:
        effects.append(fcToEffect(c))
        onLog("→ effect: \(name)")
        response = ["result": "ok"]
      }
      var entry: [String: Any] = ["name": name, "response": response]
      if let id = c.opt("id") { entry["id"] = id }
      responses.append(entry)
    }
    if !effects.isEmpty { onEffects(effects) }
    if !responses.isEmpty {
      send(
        GeminiLiveWire.jsonString([
          "toolResponse": ["functionResponses": responses]
        ]) ?? "")
    }
  }

  private func sendToolResponseTo(
    _ sock: URLSessionWebSocketTask,
    id: Any?,
    name: String,
    response: [String: Any]
  ) {
    lock.lock()
    let live = ws
    lock.unlock()
    if live !== sock {
      log.warning("dropping deferred \(name, privacy: .public) tool response — Live session was replaced")
      return
    }
    var entry: [String: Any] = ["name": name, "response": response]
    if let id { entry["id"] = id }
    send(
      GeminiLiveWire.jsonString([
        "toolResponse": [
          "functionResponses": [entry]
        ]
      ]) ?? "")
  }

  /// `{ kind: fc.name, ...fc.args }` — the concierge effect shape.
  private func fcToEffect(_ fc: JsonObject) -> JsonObject {
    var raw: [String: Any] = ["kind": fc.optString("name")]
    if let args = fc.optObject("args") {
      for (k, v) in args.raw { raw[k] = v }
    }
    return JsonObject(raw)
  }

  // ── Nudges ─────────────────────────────────────────────────────────────────

  public func injectNudge(_ kind: String, _ turns: String, dropIfBusy: Bool = false) {
    run(gate.injectNudge(kind, turns, dropIfBusy: dropIfBusy))
  }

  public func injectSessionState(_ kind: String, _ turns: String, sticky: Bool) {
    run(gate.injectSessionState(kind, turns, sticky: sticky))
  }

  public var didSpeakThisTurn: Bool { gate.didSpeakThisTurn }
  public var isModelSpeaking: Bool { gate.isModelSpeaking }
  public func claimOutcomeNudge() -> Bool { gate.claimOutcomeNudge() }

  private static func phoneClock() -> String {
    describePhoneClock(
      nowMs: Int64((Date().timeIntervalSince1970 * 1000.0).rounded()),
      timeZone: .current)
  }
}
