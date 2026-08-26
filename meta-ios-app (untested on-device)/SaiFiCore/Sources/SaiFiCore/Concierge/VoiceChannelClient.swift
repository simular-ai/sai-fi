/* sai-fi — voice concierge. */

// The agent's HTTP surface, as this app uses it.
//
// There is no voice-specific endpoint and no voice channel. A voice client authenticates as an
// ordinary API caller and uses `/v1/agents/*` exactly as a script would — which is the point: this
// repo runs against the Sai API as it already exists, and a fork does not need a server change to
// work.
//
// That decision costs one thing and buys another:
//
//   The stream belongs to a TURN, not to the call. `POST /v1/agents/message` streams that message's
//   turn and ends. Between turns nothing is connected, so an approval resolved in the desktop app
//   while nothing is running is not heard here. The FSM's queue is local for the same reason and
//   with the same consequence — see docs/VOICE_FSM.md.
//
//   In exchange there is nothing to keep alive, nothing to reconnect between turns, and no server
//   state that can disagree with this device about what is queued.
//
// The wire vocabulary is the Vercel AI SDK v6 UI message stream (`text-delta`, `data-progress`,
// `data-approval-request`, …), which is not what the FSM speaks. `parseAgentEvent` is the whole
// translation, and it is deliberately the only place that knows both alphabets.
//
// `URLSession.bytes(for:)` returns when headers arrive and yields the body as an AsyncSequence —
// which is natively the two-step shape `openMessageStream` had to hand-build. `timeoutIntervalForRequest`
// is large on that session. `TurnStream.discard()` → `task.cancel()`.
//
// Ported from Android `VoiceChannelClient.kt`. Server side: cloud-api `routes/cli.ts`, mounted at
// `/v1/agents`.

import Foundation
import os

public enum VoiceChannelClient {

  /// The channel this client speaks as, named on every request that has a per-channel answer.
  ///
  /// `cli` and `api` are separate conversations on the server — the dedicated-session key is
  /// `{uid}_{machineId}_{channel}` — and the three routes that resolve one (`POST /new-session`,
  /// `GET /context`, `GET /sessions`) DEFAULT AN ABSENT `channel` TO `cli`. Nothing errors when it
  /// is omitted; the answer is simply about somebody else's conversation.
  public static let apiChannel = "api"

  /// The body for `POST /v1/agents/new-session` — rotate THIS client's conversation.
  public static func newSessionBody(machineId: String) -> JsonObject {
    jsonWire(["machineId": machineId, "channel": apiChannel])
  }

  /// Query items that name this client's conversation. An absent `channel` defaults to `cli`.
  public static func channelQuery(machineId: String, extra: [String: String] = [:]) -> String {
    var parts = ["machineId=\(machineId)", "channel=\(apiChannel)"]
    for (k, v) in extra.sorted(by: { $0.key < $1.key }) {
      parts.append("\(k)=\(v)")
    }
    return parts.joined(separator: "&")
  }

  /// The request `POST /v1/agents/message` is sent as. Extracted so a check can pin headers and the
  /// absent channel without opening a socket.
  public static func messageRequest(
    baseUrl: String,
    bearerToken: String,
    machineId: String,
    message: String,
    attachments: JsonArray? = nil,
    versionTag: String = ""
  ) -> URLRequest {
    var raw: [String: Any] = ["machineId": machineId, "message": message]
    if let attachments, attachments.count > 0 { raw["attachments"] = attachments.raw }
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/message")!)
    request.httpMethod = "POST"
    request.httpBody = jsonData(raw)
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    request.timeoutInterval = 24 * 60 * 60
    return request
  }

  /// The request a non-streaming POST is sent as.
  public static func operationRequest(
    baseUrl: String,
    bearerToken: String,
    path: String,
    body: JsonObject,
    versionTag: String = ""
  ) -> URLRequest {
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/\(path)")!)
    request.httpMethod = "POST"
    request.httpBody = jsonData(body.raw)
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    request.timeoutInterval = 30
    return request
  }

  /// Send a message and hand back its turn's event stream, once the agent has accepted it.
  ///
  /// Deliberately TWO steps rather than one suspending call. `forwardTask` runs inside the FSM's
  /// mutex, and the FSM needs that mutex to handle every event this stream is about to produce — so
  /// a send that stayed suspended for the life of the turn would deadlock the call on its own first
  /// task. `URLSession.bytes(for:)` returns as soon as the response HEADERS arrive.
  ///
  /// Throws `ConciergeHttpException` on a non-2xx, so a refused task is a failed forward rather than
  /// a turn that silently never starts.
  ///
  /// No `channel` is sent. The route pins `api`.
  public static func openMessageStream(
    baseUrl: String,
    bearerToken: String,
    machineId: String,
    message: String,
    attachments: JsonArray? = nil,
    versionTag: String = "",
    session: URLSession = streamingSession,
    http: (any ByteStreaming)? = nil
  ) async throws -> TurnStream {
    let request = messageRequest(
      baseUrl: baseUrl,
      bearerToken: bearerToken,
      machineId: machineId,
      message: message,
      attachments: attachments,
      versionTag: versionTag)
    if let http {
      let (status, bytes) = try await http.bytes(for: request)
      if !(200...299).contains(status) {
        throw ConciergeHttpException(
          status: status, message: "POST /v1/agents/message failed (\(status))")
      }
      return TurnStream(bytes: bytes)
    }
    let (bytes, response) = try await session.bytes(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? -1
    if !(200...299).contains(status) {
      var err = ""
      for try await byte in bytes { err.append(Character(UnicodeScalar(byte))) }
      throw ConciergeHttpException(
        status: status, message: "POST /v1/agents/message failed (\(status)): \(err)")
    }
    return TurnStream(urlBytes: bytes)
  }

  /// POST to one of the agent's non-streaming operations — abort, new-session, approve.
  public static func postOperation(
    baseUrl: String,
    bearerToken: String,
    path: String,
    body: JsonObject,
    versionTag: String = "",
    session: URLSession = operationsSession,
    http: (any DataExchanging)? = nil
  ) async throws -> JsonObject {
    let request = operationRequest(
      baseUrl: baseUrl,
      bearerToken: bearerToken,
      path: path,
      body: body,
      versionTag: versionTag)
    let status: Int
    let text: Data
    if let http {
      (status, text) = try await http.data(for: request)
    } else {
      let (data, response) = try await session.data(for: request)
      status = (response as? HTTPURLResponse)?.statusCode ?? -1
      text = data
    }
    if !(200...299).contains(status) {
      let err = String(data: text, encoding: .utf8) ?? ""
      throw ConciergeHttpException(
        status: status, message: "POST /v1/agents/\(path) failed (\(status)): \(err)")
    }
    if text.isEmpty { return JsonObject([:]) }
    return JsonObject(data: text) ?? JsonObject([:])
  }

  /// No read timeout: a long tool call with nothing to say is the normal case, not a fault.
  public static let streamingSession: URLSession = {
    let c = URLSessionConfiguration.ephemeral
    c.timeoutIntervalForRequest = 24 * 60 * 60
    c.timeoutIntervalForResource = 24 * 60 * 60
    c.httpAdditionalHeaders = [:]
    return URLSession(configuration: c)
  }()

  public static let operationsSession: URLSession = {
    let c = URLSessionConfiguration.ephemeral
    c.timeoutIntervalForRequest = 15
    c.timeoutIntervalForResource = 30
    return URLSession(configuration: c)
  }()

  /// One turn's events, already accepted by the agent and waiting to be read.
  ///
  /// Owns the connection: whoever reads it closes it, including on cancellation.
  /// `discard()` cancels the consume task — the Swift equivalent of disconnecting a socket parked
  /// in `readLine`.
  public final class TurnStream: @unchecked Sendable {
    private struct Bits {
      var cancelled = false
      var consumeTask: Task<Error?, Never>?
    }
    private let bits = OSAllocatedUnfairLock(initialState: Bits())
    private let pull: @Sendable () async throws -> [UInt8]?

    /// From `URLSession.bytes(for:)`.
    public init(urlBytes: URLSession.AsyncBytes) {
      let box = UrlByteBox(urlBytes)
      self.pull = { try await box.nextLine() }
    }

    /// From a scripted byte stream — tests, no server.
    public init(bytes: AsyncThrowingStream<UInt8, Error>) {
      let box = StreamByteBox(bytes)
      self.pull = { try await box.nextLine() }
    }

    /// Give up on the turn without reading it — for a steer, whose events arrive on the original.
    public func discard() {
      bits.withLock { state in
        state.cancelled = true
        state.consumeTask?.cancel()
      }
    }

    /// Read to the end of the turn.
    ///
    /// Returns when the agent finishes, errors, or the connection drops — reconnect policy belongs
    /// to the caller, not here. Frames that do not parse are DROPPED rather than thrown: a newer
    /// server must not be able to end a call by sending something this build predates.
    public func read(
      onEvent: @escaping @Sendable (AgentEvent) async -> Void,
      onLog: @escaping @Sendable (String) -> Void = { _ in }
    ) async throws {
      let turn = TurnEvents()
      let work = Task { () -> Error? in
        do {
          var buffer = Data()
          while !Task.isCancelled {
            guard let chunk = try await pull() else { break }
            buffer.append(contentsOf: chunk)
            while let nl = buffer.firstIndex(of: 10) {
              var lineData = buffer.prefix(upTo: nl)
              buffer.removeSubrange(..<buffer.index(after: nl))
              if lineData.last == 13 { lineData.removeLast() }
              let line = String(data: Data(lineData), encoding: .utf8) ?? ""
              if let payload = ssePayload(line) {
                if let out = turn.onPayload(payload) {
                  await onEvent(out)
                } else {
                  onLog(describeIgnoredFrame(payload))
                }
              }
            }
          }
          return nil
        } catch is CancellationError {
          return nil
        } catch {
          return error
        }
      }
      let already = bits.withLock { state -> Bool in
        state.consumeTask = work
        return state.cancelled
      }
      if already { work.cancel() }
      if let error = await work.value { throw error }
    }
  }
}

/// Owns a `URLSession.AsyncBytes` iterator so the pull closure can be `@Sendable`.
private final class UrlByteBox: @unchecked Sendable {
  private var iterator: URLSession.AsyncBytes.AsyncIterator
  init(_ bytes: URLSession.AsyncBytes) { self.iterator = bytes.makeAsyncIterator() }
  func nextLine() async throws -> [UInt8]? {
    var chunk: [UInt8] = []
    chunk.reserveCapacity(256)
    for _ in 0..<256 {
      guard let b = try await iterator.next() else {
        return chunk.isEmpty ? nil : chunk
      }
      chunk.append(b)
      if b == 10 { break }
    }
    return chunk
  }
}

/// Owns an `AsyncThrowingStream` iterator so the pull closure can be `@Sendable`.
private final class StreamByteBox: @unchecked Sendable {
  private var iterator: AsyncThrowingStream<UInt8, Error>.Iterator
  init(_ bytes: AsyncThrowingStream<UInt8, Error>) { self.iterator = bytes.makeAsyncIterator() }
  func nextLine() async throws -> [UInt8]? {
    var chunk: [UInt8] = []
    chunk.reserveCapacity(256)
    for _ in 0..<256 {
      guard let b = try await iterator.next() else {
        return chunk.isEmpty ? nil : chunk
      }
      chunk.append(b)
      if b == 10 { break }
    }
    return chunk
  }
}

/// A scripted or live exchange that yields body bytes after headers.
public protocol ByteStreaming: Sendable {
  func bytes(for request: URLRequest) async throws -> (status: Int, body: AsyncThrowingStream<UInt8, Error>)
}

/// A scripted or live exchange that returns the whole body.
public protocol DataExchanging: Sendable {
  func data(for request: URLRequest) async throws -> (status: Int, body: Data)
}

func applyCloudApiHeaders(_ request: inout URLRequest, bearerToken: String, versionTag: String) {
  for (k, v) in cloudApiHeaders(bearerToken: bearerToken, versionTag: versionTag) {
    request.setValue(v, forHTTPHeaderField: k)
  }
}

/// Pull a `data:` payload out of one SSE line, or nil for keepalives / blanks / `[DONE]`.
public func ssePayload(_ line: String) -> String? {
  if line.isEmpty { return nil }
  if line.hasPrefix(":") { return nil }
  guard line.hasPrefix("data:") else { return nil }
  let payload = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
  if payload.isEmpty || payload == "[DONE]" { return nil }
  return payload
}

/// One turn's frames, in order — and the one piece of state reading them requires.
///
/// `parseAgentEvent` is stateless and has to stay that way, but a turn is not: the agent's ANSWER
/// arrives as a run of `text-delta` frames and the turn then ends with a bare
/// `{"type":"finish","finishReason":"stop"}` that carries nothing. Mapping that straight through
/// produced `Complete(summary = null)`, whose nudge tells the concierge the task ended WITHOUT
/// reporting any result — on every task that answered perfectly well.
///
/// So the deltas are accumulated here and handed over on `finish`. They are still emitted
/// individually as they arrive.
public final class TurnEvents: @unchecked Sendable {
  private var answer = ""

  /// The event this frame becomes, or nil when the frame is one we ignore.
  public func onPayload(_ raw: String) -> AgentEvent? {
    guard let event = parseAgentEvent(raw) else { return nil }
    switch event {
    case .text(let text):
      answer += text
      return event
    case .complete(let summary):
      // Only fill in a summary the server did not send; never overwrite one it did.
      if (summary == nil || summary?.isEmpty == true) && !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return .complete(summary: answer.trimmingCharacters(in: .whitespacesAndNewlines))
      }
      return event
    default:
      return event
    }
  }
}

/// The log line for a frame that mapped to nothing.
///
/// NAME it. Most unmapped frames are envelope markers this client is right to ignore
/// (`text-start`/`text-end` around the deltas it does read), but a frame the server started sending
/// and this client never learned looks identical from here.
///
/// A `data-*` frame additionally gets its FIELD NAMES. Names only, never values: this log is
/// mirrored to the presenter dashboard, and a data part can carry agent-derived text.
public func describeIgnoredFrame(_ payload: String) -> String {
  guard let json = JsonObject(string: payload) else {
    return "[voice] ignored frame: (untyped)"
  }
  let type = json.optString("type").isEmpty ? "(untyped)" : json.optString("type")
  if !type.hasPrefix("data-") { return "[voice] ignored frame: \(type)" }
  let fields = json.optObject("data")?.raw.keys.sorted() ?? []
  let where_ = json.has("data") ? "data" : "frame"
  if fields.isEmpty {
    return "[voice] ignored frame: \(type) (no \(where_) fields — nothing carried)"
  }
  return "[voice] ignored frame: \(type) — UNHANDLED data part, \(where_) fields: \(fields.joined(separator: ", "))"
}

public func parseAgentEvent(_ raw: String) -> AgentEvent? {
  guard let json = JsonObject(string: raw) else { return nil }
  let data = json.optObject("data")
  switch json.optString("type") {
  case "text-delta":
    return json.str("delta").map { .text($0) }
  case "reasoning-delta":
    return json.str("delta").map { .progress(text: $0) }
  case "data-progress":
    guard let text = data?.str("text") else { return nil }
    return .progress(text: text, tool: data?.str("tool"))
  case "tool-input-available":
    return json.str("toolName").map { .progress(text: $0, tool: $0) }
  // A failed STEP, not a failed turn. `failed` is what the concierge reacts to; without it Sai
  // has no idea anything went wrong and fills the silence with a result it never received.
  case "tool-output-error":
    let text = json.optString("errorText").isEmpty ? "a step failed" : json.optString("errorText")
    return .progress(text: text, failed: true)
  // Delivery news, not work: a hibernated machine waking, an agent that is offline.
  case "data-status":
    guard let text = data?.str("text") else { return nil }
    return .notice(text: text, kind: data?.str("kind"))
  case "start":
    return .status(.processing)
  case "finish":
    return .complete()
  case "error":
    let text = json.optString("errorText").isEmpty ? json.optString("text") : json.optString("errorText")
    return .error(text)
  case "data-approval-request":
    return data.flatMap(parseApprovalRequest)
  default:
    return nil
  }
}

/// A `data-approval-request` payload as an `AgentEvent.approvalRequest`.
///
/// The card can carry its options two ways and both must work: `options` for a single question, and
/// `questions` when it asks several. The flat list is what the model picks from and what gets read
/// back to the user, so a multi-question card is FLATTENED for that purpose while the grouping is
/// kept alongside.
private func parseApprovalRequest(_ data: JsonObject) -> AgentEvent? {
  guard let id = data.str("approvalId") else { return nil }

  let questions: [ApprovalQuestion]? = data.optArray("questions").map { arr in
    (0..<arr.count).compactMap { i in
      guard let q = arr.optObject(i) else { return nil }
      return ApprovalQuestion(
        options: q.optArray("options").toApprovalOptions(),
        multiple: q.optBool("multiple", false),
        allowOther: q.optBool("allowOther", false))
    }
  }

  let flat: [ApprovalOption]? =
    questions?.flatMap(\.options).nilIfEmpty
    ?? data.optArray("options").toApprovalOptions().nilIfEmpty

  let multiple: Bool?
  if data.has("multiple") {
    multiple = data.optBool("multiple")
  } else if let questions {
    multiple = questions.contains(where: \.multiple)
  } else {
    multiple = nil
  }

  let allowOther: Bool?
  if data.has("allowOther") {
    allowOther = data.optBool("allowOther")
  } else if let questions {
    allowOther = questions.contains(where: \.allowOther)
  } else {
    allowOther = nil
  }

  return .approvalRequest(
    ApprovalRequestPayload(
      id: id,
      title: data.optString("title"),
      description: data.optString("description"),
      approvalType: data.optString("approvalType"),
      isLinkOnly: data.optBool("isLinkOnly", false),
      options: flat,
      // Only when it actually asks more than one thing. For a single question the flat list IS the
      // grouping, and carrying a redundant copy is one more thing that can disagree with itself.
      questions: questions?.count ?? 0 > 1 ? questions : nil,
      multiple: multiple,
      allowOther: allowOther,
      expiresAt: data.optInt64OrNil("expiresAt").flatMap { $0 > 0 ? $0 : nil }))
}

/// An options array as `ApprovalOption`s, accepting both shapes the agent writes.
///
/// `askChoice` options are plain strings; the richer cards use `{value,label}`. A plain string is
/// both — the value the agent matches on and the words the user hears.
extension JsonArray? {
  fileprivate func toApprovalOptions() -> [ApprovalOption] {
    guard let self else { return [] }
    return (0..<self.count).compactMap { i in
      if let o = self.optObject(i) {
        return ApprovalOption(value: o.optString("value"), label: o.optString("label"))
      }
      let s = self.optString(i)
      return s.isEmpty ? nil : ApprovalOption(value: s, label: s)
    }
  }
}

extension Array {
  fileprivate var nilIfEmpty: Self? { isEmpty ? nil : self }
}
