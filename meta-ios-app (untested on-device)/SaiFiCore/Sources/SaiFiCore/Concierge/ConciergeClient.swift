/* sai-fi — voice concierge. */

// ConciergeClient — the read-only half of the agent API: machines, history, and image upload.
//
// Named for an endpoint that no longer exists. What it holds now is everything a call needs from
// the Sai API that is NOT a task: `GET /v1/agents/machines` for the picker, `GET /v1/agents/context`
// behind `recallHistory`, and `POST /v1/agents/upload` for a glasses capture. Sending work is
// VoiceChannelClient's job.
//
// Auth = a fresh Firebase ID token sent as a Bearer header. Non-2xx responses throw
// `ConciergeHttpException` carrying the status so callers can distinguish permanent failures from
// transient ones and stop retrying.
//
// Ported from Android `ConciergeClient.kt`.

import Foundation

/// What one Live session is configured with.
///
/// This used to be the parsed `POST /v1/concierge/session` response. That endpoint is gone — the
/// device brings its own API key and ships its own profile — so this is now built locally from
/// `VoiceProfile` plus the session's machine context.
public struct SessionBootstrap: Sendable, Equatable {
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
}

/// What `POST /v1/agents/wake` answered.
///
/// Four fields because the caller has four different sentences to choose between, and only one of
/// them is true at a time.
public struct WakeOutcome: Sendable, Equatable {
  /// THIS call dispatched a wake. False for a machine that was already awake, already on its way,
  /// or cannot be woken — and false is not the same as "nothing to say" in the second case.
  public var waking: Bool
  /// The machine is not usable yet but is coming up, whether or not we are the reason.
  ///
  /// **Branch on this, not `waking`.** A machine already mid-wake answers `waking = false` —
  /// correctly, since nothing was dispatched — and the user is still owed the "about a minute"
  /// line.
  public var startingUp: Bool
  /// As stored, read server-side BEFORE the dispatch. Nil when the field is absent.
  public var status: String?
  public var canWake: Bool

  public init(waking: Bool, startingUp: Bool, status: String?, canWake: Bool) {
    self.waking = waking
    self.startingUp = startingUp
    self.status = status
    self.canWake = canWake
  }
}

public enum ConciergeClient {

  public static func machinesRequest(
    baseUrl: String, bearerToken: String, versionTag: String = ""
  ) -> URLRequest {
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/machines")!)
    request.httpMethod = "GET"
    request.timeoutInterval = 15
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    return request
  }

  public static func contextRequest(
    baseUrl: String,
    bearerToken: String,
    machineId: String,
    limit: Int = 30,
    versionTag: String = ""
  ) -> URLRequest {
    // `channel` for the same reason the rotation names it: the route defaults to `cli`, so recall
    // without it answers from the TERMINAL's transcript — a conversation this client has never
    // taken part in.
    let q = VoiceChannelClient.channelQuery(machineId: machineId, extra: ["limit": "\(limit)"])
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/context?\(q)")!)
    request.httpMethod = "GET"
    request.timeoutInterval = 15
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    return request
  }

  /// The sessions list names the same channel — an absent one defaults to `cli`.
  public static func sessionsRequest(
    baseUrl: String, bearerToken: String, machineId: String, versionTag: String = ""
  ) -> URLRequest {
    let q = VoiceChannelClient.channelQuery(machineId: machineId)
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/sessions?\(q)")!)
    request.httpMethod = "GET"
    request.timeoutInterval = 15
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    return request
  }

  public static func wakeRequest(
    baseUrl: String, bearerToken: String, machineId: String, versionTag: String = ""
  ) -> URLRequest {
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/wake")!)
    request.httpMethod = "POST"
    request.httpBody = jsonData(["machineId": machineId])
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.timeoutInterval = 15
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    return request
  }

  public static func uploadRequest(
    baseUrl: String, bearerToken: String, bytes: Data, filename: String, versionTag: String = ""
  ) -> URLRequest {
    var request = URLRequest(url: URL(string: "\(baseUrl)/v1/agents/upload")!)
    request.httpMethod = "POST"
    request.httpBody = bytes
    request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
    request.setValue(filename, forHTTPHeaderField: "x-filename")
    request.timeoutInterval = 30
    applyCloudApiHeaders(&request, bearerToken: bearerToken, versionTag: versionTag)
    return request
  }

  public static func parseWake(_ body: String) -> WakeOutcome {
    let o = JsonObject(string: body) ?? JsonObject([:])
    return WakeOutcome(
      waking: o.optBool("waking", false),
      // Absent on an older server: nothing is starting up as far as this client can tell, so the
      // wake path stays silent rather than announcing a minute it cannot vouch for.
      startingUp: o.optBool("startingUp", false),
      status: o.str("status"),
      canWake: o.optBool("canWake", false))
  }

  public static func parseMachines(_ body: String) -> [Machine] {
    let arr = JsonObject(string: body)?.optArray("machines") ?? JsonArray([])
    return (0..<arr.count).compactMap { i in
      guard let m = arr.optObject(i) else { return nil }
      let machineId = m.optString("machineId")
      guard !machineId.isEmpty else { return nil }
      return Machine(
        machineId: machineId,
        name: m.str("name"),
        // Absent on a server older than 2026-08-20, which reads as "offline / cannot wake" —
        // and the wake path degrades to doing nothing rather than to guessing.
        status: m.str("status"),
        canWake: m.optBool("canWake", false))
    }
  }

  public static func parseContext(_ body: String) -> String {
    let arr = JsonObject(string: body)?.optArray("messages") ?? JsonArray([])
    if arr.count == 0 { return "No recent history on this machine." }
    var lines = ""
    for i in 0..<arr.count {
      guard let m = arr.optObject(i) else { continue }
      let role = m.optString("role") == "user" ? "user" : "sai"
      let collapsed = m.optString("content")
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
      if collapsed.isEmpty { continue }
      let clipped = String(collapsed.prefix(400))
      lines += "\(role): \(clipped)\n"
    }
    if lines.isEmpty { return "No recent history on this machine." }
    if lines.count <= 6_000 { return lines }
    return String(lines.suffix(6_000))
  }

  /// List the user's Sai machines so the app can offer a picker.
  public static func listMachines(
    baseUrl: String,
    bearerToken: String,
    versionTag: String = "",
    http: (any DataExchanging)? = nil
  ) async throws -> [Machine] {
    let request = machinesRequest(baseUrl: baseUrl, bearerToken: bearerToken, versionTag: versionTag)
    let (status, data) = try await exchange(request, http: http, session: VoiceChannelClient.operationsSession)
    let body = String(data: data, encoding: .utf8) ?? ""
    if !(200...299).contains(status) {
      throw ConciergeHttpException(
        status: status,
        message: "GET /v1/agents/machines failed: HTTP \(status) — \(body.prefix(300))")
    }
    return parseMachines(body)
  }

  /// Fetch the machine's recent conversation history for the model's `recallHistory` tool.
  public static func fetchContext(
    baseUrl: String,
    bearerToken: String,
    machineId: String,
    limit: Int = 30,
    versionTag: String = "",
    http: (any DataExchanging)? = nil
  ) async throws -> String {
    let request = contextRequest(
      baseUrl: baseUrl, bearerToken: bearerToken, machineId: machineId, limit: limit,
      versionTag: versionTag)
    let (status, data) = try await exchange(request, http: http, session: VoiceChannelClient.operationsSession)
    let body = String(data: data, encoding: .utf8) ?? ""
    if !(200...299).contains(status) {
      throw ConciergeHttpException(
        status: status,
        message: "GET /v1/agents/context failed: HTTP \(status) — \(body.prefix(300))")
    }
    return parseContext(body)
  }

  /// Upload raw image bytes. Returns the server's attachment JSON.
  public static func uploadAttachment(
    baseUrl: String,
    bearerToken: String,
    bytes: Data,
    filename: String,
    versionTag: String = "",
    http: (any DataExchanging)? = nil
  ) async throws -> JsonObject {
    let request = uploadRequest(
      baseUrl: baseUrl, bearerToken: bearerToken, bytes: bytes, filename: filename,
      versionTag: versionTag)
    let (status, data) = try await exchange(request, http: http, session: VoiceChannelClient.operationsSession)
    let body = String(data: data, encoding: .utf8) ?? ""
    if !(200...299).contains(status) {
      throw ConciergeHttpException(
        status: status,
        message: "POST /v1/agents/upload failed: HTTP \(status) — \(body.prefix(300))")
    }
    return JsonObject(string: body) ?? JsonObject([:])
  }

  /// Wake a hibernated machine, without sending it any work — `POST /v1/agents/wake`.
  ///
  /// The point of it having no payload: every other wake in the system rides a delivery, and a
  /// message arriving during a running turn is folded INTO that turn. So waking by sending a
  /// throwaway "hello" means the dummy and the user's next real request share one turn, and the
  /// dummy's completion ends it out from under the real work.
  public static func wakeMachine(
    baseUrl: String,
    bearerToken: String,
    machineId: String,
    versionTag: String = "",
    http: (any DataExchanging)? = nil
  ) async throws -> WakeOutcome {
    let request = wakeRequest(
      baseUrl: baseUrl, bearerToken: bearerToken, machineId: machineId, versionTag: versionTag)
    let (status, data) = try await exchange(request, http: http, session: VoiceChannelClient.operationsSession)
    let body = String(data: data, encoding: .utf8) ?? ""
    if !(200...299).contains(status) {
      throw ConciergeHttpException(
        status: status,
        message: "POST /v1/agents/wake failed: HTTP \(status) — \(body.prefix(300))")
    }
    return parseWake(body)
  }
}

private func exchange(
  _ request: URLRequest,
  http: (any DataExchanging)?,
  session: URLSession
) async throws -> (Int, Data) {
  if let http { return try await http.data(for: request) }
  let (data, response) = try await session.data(for: request)
  let status = (response as? HTTPURLResponse)?.statusCode ?? -1
  return (status, data)
}
