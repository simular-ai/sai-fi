/* sai-fi — voice concierge. */

// The agent HTTP layer, gated without a network: a recording VoiceTransport, the SSE translator,
// and ConciergeClient's parsers. Ported from Android HttpAgentBridgeTest / VoiceChannelClientTest /
// ConciergeClientWakeTest.

import Foundation
import os

func agentLinkChecks() -> [Check] {
  httpAgentBridgeChecks() + voiceChannelParseChecks() + conciergeClientParseChecks()
}

// ── recording transport ──────────────────────────────────────────────────────────────────────────

private final class RecordingTransport: VoiceTransport, @unchecked Sendable {
  struct Sent {
    var message: String
    var attachments: JsonArray?
    var follow: Bool
  }

  private struct State {
    var responses: [String: JsonObject] = [:]
    var throwOn: String?
    var throwStatus: Int = 400
    var sends: [Sent] = []
    var posts: [(String, JsonObject)] = []
    var abandoned: [Int] = []
  }

  private let lock = OSAllocatedUnfairLock(initialState: State())

  var throwOn: String? {
    get { lock.withLock { $0.throwOn } }
    set { lock.withLock { $0.throwOn = newValue } }
  }
  var throwStatus: Int {
    get { lock.withLock { $0.throwStatus } }
    set { lock.withLock { $0.throwStatus = newValue } }
  }
  var sends: [Sent] { lock.withLock { $0.sends } }
  var posts: [(String, JsonObject)] { lock.withLock { $0.posts } }
  var abandoned: [Int] { lock.withLock { $0.abandoned } }

  func sendMessage(
    machineId: String, message: String, attachments: JsonArray?, follow: Bool
  ) async throws {
    lock.withLock { $0.sends.append(Sent(message: message, attachments: attachments, follow: follow)) }
  }

  func abandonTurn() {
    lock.withLock { $0.abandoned.append($0.posts.count) }
  }

  func post(path: String, body: JsonObject) async throws -> JsonObject {
    let (throwOn, throwStatus, reply) = lock.withLock { state -> (String?, Int, JsonObject?) in
      state.posts.append((path, body))
      return (state.throwOn, state.throwStatus, state.responses[path])
    }
    if path == throwOn {
      throw ConciergeHttpException(status: throwStatus, message: "rejected")
    }
    return reply ?? JsonObject([:])
  }
}

private let photo = TaskAttachment(
  path: "uploads/a.jpg", name: "a.jpg", mime: "image/jpeg", size: 10)

private func bridge(_ t: RecordingTransport, abortLocalWork: @escaping @Sendable () -> Void = {})
  -> HttpAgentBridge
{
  HttpAgentBridge(machineId: "m1", transport: t, abortLocalWork: abortLocalWork)
}

private func httpAgentBridgeChecks() -> [Check] {
  [
    Check(name: "a new task is FOLLOWED — its response is the turn") {
      let t = RecordingTransport()
      _ = try? await bridge(t).forwardTask(text: "take a screenshot", attachments: nil)
      return expectEqual(t.sends.first?.follow, true, "follow")
    },
    Check(name: "a steer is NOT followed — the turn it lands in is already being read") {
      let t = RecordingTransport()
      try? await bridge(t).steer(text: "make it 8pm")
      return firstFailure([
        expectEqual(t.sends.first?.follow, false, "follow"),
        expectTrue(t.posts.isEmpty, "a steer must not post to any operation"),
      ])
    },
    Check(name: "steer carries no location — a correction is about the task") {
      let t = RecordingTransport()
      let b = bridge(t)
      b.setPendingLocation(TaskLocation(lat: 1, lon: 2, capturedAt: 0))
      try? await b.steer(text: "make it 8pm")
      return expectEqual(t.sends.single?.message, "make it 8pm", "message")
    },
    Check(name: "every forwarded task carries the clock, location or no location") {
      let t = RecordingTransport()
      _ = try? await bridge(t).forwardTask(text: "book a table for Friday", attachments: nil)
      let sent = t.sends.single?.message ?? ""
      return firstFailure([
        expectTrue(sent.hasPrefix("book a table for Friday"), "the user's words must still lead"),
        expectTrue(sent.contains("where the user is (time zone "), "no clock reached the agent: \(sent)"),
      ])
    },
    Check(name: "taking the stash empties it, so a later capture cannot ride along") {
      let b = bridge(RecordingTransport())
      b.addPendingAttachment(photo)
      let first = await b.takePendingAttachments()
      let second = await b.takePendingAttachments()
      return firstFailure([
        expectEqual(first, [photo], "first take"),
        expectTrue(second.isEmpty, "the second take must be empty"),
      ])
    },
    Check(name: "a held task carries its OWN photo when it finally drains") {
      let t = RecordingTransport()
      _ = try? await bridge(t).forwardTask(text: "what is this", attachments: [photo])
      let sent = t.sends.single?.attachments
      return firstFailure([
        expectEqual(sent?.count, 1, "count"),
        expectEqual(sent?.optObject(0)?.optString("name"), "a.jpg", "name"),
      ])
    },
    Check(name: "no attachments is null, not an empty array") {
      let t = RecordingTransport()
      _ = try? await bridge(t).forwardTask(text: "plain task", attachments: [])
      return expectTrue(t.sends.single?.attachments == nil, "null")
    },
    Check(name: "reset tells the rate limit apart from a failure") {
      let limited = RecordingTransport()
      limited.throwOn = "new-session"
      limited.throwStatus = 429
      let broken = RecordingTransport()
      broken.throwOn = "new-session"
      broken.throwStatus = 500
      let a = await bridge(limited).resetSession()
      let b = await bridge(broken).resetSession()
      let c = await bridge(RecordingTransport()).resetSession()
      return firstFailure([
        expectEqual(a, .rateLimited, "429"),
        expectEqual(b, .failed, "500"),
        expectEqual(c, .ok, "ok"),
      ])
    },
    Check(name: "reset names the api channel, so it rotates OUR conversation") {
      let t = RecordingTransport()
      _ = await bridge(t).resetSession()
      return expectEqual(t.posts.single?.1.optString("channel"), "api", "channel")
    },
    Check(name: "abort stops reading the turn, and does it before the POST") {
      let t = RecordingTransport()
      try? await bridge(t).abort()
      return firstFailure([
        expectEqual(t.abandoned, [0], "abandoned once, before any post"),
        expectEqual(t.posts.map(\.0), ["abort"], "posts"),
      ])
    },
    Check(name: "a failing abort POST still leaves the turn abandoned, and does not throw") {
      let t = RecordingTransport()
      t.throwOn = "abort"
      let localAborts = OSAllocatedUnfairLock(initialState: 0)
      try? await HttpAgentBridge(
        machineId: "m1",
        transport: t,
        abortLocalWork: { localAborts.withLock { $0 += 1 } }
      ).abort()
      return firstFailure([
        expectEqual(localAborts.withLock { $0 }, 1, "the local halves still ran"),
        expectEqual(t.abandoned, [0], "and ran before the doomed post"),
        expectEqual(t.posts.map(\.0), ["abort"], "posts"),
      ])
    },
    Check(name: "abort needs no channel — it is about the machine, not a conversation") {
      let t = RecordingTransport()
      try? await bridge(t).abort()
      return expectFalse(t.posts.single?.1.has("channel") == true, "abort must not name a channel")
    },
    Check(name: "reset and abort both name the machine") {
      let t = RecordingTransport()
      try? await bridge(t).abort()
      _ = await bridge(t).resetSession()
      return firstFailure([
        expectEqual(t.posts.map(\.0), ["abort", "new-session"], "paths"),
        expectTrue(t.posts.allSatisfy { $0.1.optString("machineId") == "m1" }, "machineId"),
      ])
    },
    Check(name: "selections go out GROUPED, one array per question") {
      let t = RecordingTransport()
      try? await bridge(t).resolveApproval(
        id: "a1",
        decision: .approved,
        selection: ApprovalSelection(selections: [["a"], ["b", "c"]]))
      let body = t.posts.single?.1
      let groups = body?.optArray("selections")
      return firstFailure([
        expectEqual(t.posts.first?.0, "approve", "path"),
        expectEqual(body?.optString("approvalId"), "a1", "id"),
        expectEqual(body?.optString("response"), "yes", "response"),
        expectEqual(groups?.count, 2, "groups"),
        expectEqual((groups?.raw[0] as? [Any])?.count, 1, "group 0"),
        expectEqual((groups?.raw[1] as? [Any])?.count, 2, "group 1"),
      ])
    },
    Check(name: "the decision is the API's yes-no, not the doc's approved-denied") {
      let t = RecordingTransport()
      let b = bridge(t)
      try? await b.resolveApproval(id: "a1", decision: .approved, selection: nil)
      try? await b.resolveApproval(id: "a2", decision: .denied, selection: nil)
      return expectEqual(t.posts.map { $0.1.optString("response") }, ["yes", "no"], "response")
    },
    Check(name: "a decision with no selection sends no selections at all") {
      let t = RecordingTransport()
      try? await bridge(t).resolveApproval(id: "a1", decision: .denied, selection: nil)
      return expectFalse(t.posts.single?.1.has("selections") == true, "a denial carries no picks")
    },
    Check(name: "an unanswered question is sent as an EMPTY group, not omitted") {
      let t = RecordingTransport()
      try? await bridge(t).resolveApproval(
        id: "a1",
        decision: .approved,
        selection: ApprovalSelection(selections: [[], ["b"]]))
      let groups = t.posts.single?.1.optArray("selections")
      return firstFailure([
        expectEqual(groups?.count, 2, "groups"),
        expectEqual((groups?.raw[0] as? [Any])?.count, 0, "empty first group"),
      ])
    },
    Check(name: "a rejected selection PROPAGATES, so the FSM keeps the request answerable") {
      let t = RecordingTransport()
      t.throwOn = "approve"
      do {
        try await bridge(t).resolveApproval(
          id: "a1",
          decision: .approved,
          selection: ApprovalSelection(selections: [["pigeon"]]))
        return "expected ConciergeHttpException"
      } catch is ConciergeHttpException {
        return nil
      } catch {
        return "wrong error: \(error)"
      }
    },
    Check(name: "TURN_STREAM_LOST is the Android wording, byte for byte") {
      expectEqual(
        TURN_STREAM_LOST,
        "lost the connection to the agent partway through, so the outcome of that task is unknown — "
          + "it may still be running",
        "TURN_STREAM_LOST")
    },
  ]
}

private func voiceChannelParseChecks() -> [Check] {
  [
    Check(name: "an unrecognised frame is dropped, not thrown") {
      firstFailure([
        expectTrue(parseAgentEvent("{\"type\":\"some-future-thing\",\"x\":1}") == nil, "future"),
        expectTrue(parseAgentEvent("not json at all") == nil, "garbage"),
        expectTrue(parseAgentEvent("{\"no\":\"type\"}") == nil, "no type"),
        expectTrue(parseAgentEvent("{\"type\":\"text-start\",\"id\":\"m1\"}") == nil, "text-start"),
      ])
    },
    Check(name: "a text delta is passed straight through, not buffered until the end") {
      let e = parseAgentEvent("{\"type\":\"text-delta\",\"id\":\"m1\",\"delta\":\"the inbox is\"}")
      let empty = parseAgentEvent("{\"type\":\"text-delta\",\"delta\":\"\"}")
      return firstFailure([
        expectEqual(e, .text("the inbox is"), "delta"),
        expectTrue(empty == nil, "an empty delta says nothing"),
      ])
    },
    Check(name: "finish is a COMPLETION — it is the only end-of-turn signal on this stream") {
      let e = parseAgentEvent("{\"type\":\"finish\",\"finishReason\":\"stop\"}")
      guard case .complete(let summary) = e else { return "expected complete, got \(String(describing: e))" }
      return expectTrue(summary == nil, "no summary on the wire")
    },
    Check(name: "a failed tool is PROGRESS, not an error — the task carries on") {
      let e = parseAgentEvent(
        "{\"type\":\"tool-output-error\",\"toolCallId\":\"t1\",\"errorText\":\"429 from the API\"}")
      guard case .progress(let text, _, let failed) = e else {
        return "expected progress, got \(String(describing: e))"
      }
      return firstFailure([
        expectTrue(failed, "failed"),
        expectEqual(text, "429 from the API", "text"),
      ])
    },
    Check(name: "data-status is a NOTICE — it is delivery news, not work") {
      let e = parseAgentEvent(
        "{\"type\":\"data-status\",\"data\":{\"text\":\"Waking your machine\"}}")
      guard case .notice(let text, _) = e else { return "expected notice, got \(String(describing: e))" }
      return expectEqual(text, "Waking your machine", "text")
    },
    Check(name: "a turn that answers in text deltas completes WITH that answer as its summary") {
      let turn = TurnEvents()
      var seen: [AgentEvent] = []
      for payload in [
        "{\"type\":\"start\"}",
        "{\"type\":\"text-start\"}",
        "{\"type\":\"text-delta\",\"delta\":\"It is \"}",
        "{\"type\":\"text-delta\",\"delta\":\"half past four.\"}",
        "{\"type\":\"text-end\"}",
        "{\"type\":\"finish\",\"finishReason\":\"stop\"}",
      ] {
        if let e = turn.onPayload(payload) { seen.append(e) }
      }
      let texts = seen.compactMap { if case .text(let t) = $0 { return t } else { return nil } }
      let complete = seen.compactMap { if case .complete(let s) = $0 { return s } else { return nil } }
      return firstFailure([
        expectEqual(texts, ["It is ", "half past four."], "deltas still arrive"),
        expectEqual(complete, ["It is half past four."], "summary"),
      ])
    },
    Check(name: "a turn that really said nothing still completes with nothing") {
      let turn = TurnEvents()
      var summary: String??
      for payload in ["{\"type\":\"start\"}", "{\"type\":\"finish\",\"finishReason\":\"stop\"}"] {
        if case .complete(let s) = turn.onPayload(payload) { summary = s }
      }
      return expectTrue(summary == .some(nil) || summary == .some(.some("")), "empty stays empty")
    },
    Check(name: "new-session body names channel=api; message body does not") {
      let session = VoiceChannelClient.newSessionBody(machineId: "m1")
      let req = VoiceChannelClient.messageRequest(
        baseUrl: "https://api.example", bearerToken: "tok", machineId: "m1", message: "hi")
      let body = JsonObject(data: req.httpBody ?? Data())
      return firstFailure([
        expectEqual(session.optString("channel"), "api", "new-session"),
        expectFalse(body?.has("channel") == true, "message pins api on the route, not the body"),
        expectEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer tok", "auth"),
        expectEqual(req.value(forHTTPHeaderField: "Accept"), "text/event-stream", "sse"),
      ])
    },
    Check(name: "context and sessions query strings name the api channel") {
      let ctx = ConciergeClient.contextRequest(
        baseUrl: "https://api.example", bearerToken: "t", machineId: "m1")
      let ses = ConciergeClient.sessionsRequest(
        baseUrl: "https://api.example", bearerToken: "t", machineId: "m1")
      let ctxUrl = ctx.url?.absoluteString ?? ""
      let sesUrl = ses.url?.absoluteString ?? ""
      return firstFailure([
        expectTrue(ctxUrl.contains("channel=api"), "context \(ctxUrl)"),
        expectTrue(sesUrl.contains("channel=api"), "sessions \(sesUrl)"),
      ])
    },
    Check(name: "an unmapped data part is named with its fields, never its values") {
      let session = describeIgnoredFrame(
        "{\"type\":\"data-session\",\"data\":{\"title\":\"x\",\"id\":\"s1\"}}")
      let secret = describeIgnoredFrame(
        "{\"type\":\"data-session\",\"data\":{\"secret\":\"hunter2\"}}")
      return firstFailure([
        expectEqual(
          describeIgnoredFrame("{\"type\":\"text-start\",\"id\":\"1\"}"),
          "[voice] ignored frame: text-start",
          "envelope"),
        expectEqual(
          session,
          "[voice] ignored frame: data-session — UNHANDLED data part, data fields: id, title",
          "data-session"),
        expectFalse(secret.contains("hunter2"), "values are never logged"),
        expectEqual(describeIgnoredFrame("not json"), "[voice] ignored frame: (untyped)", "garbage"),
      ])
    },
  ]
}

private func conciergeClientParseChecks() -> [Check] {
  [
    Check(name: "a dispatched wake parses every field") {
      let out = ConciergeClient.parseWake(
        "{\"ok\":true,\"waking\":true,\"startingUp\":true,\"status\":\"hibernated\",\"canWake\":true}")
      return firstFailure([
        expectTrue(out.waking, "waking"),
        expectTrue(out.startingUp, "startingUp"),
        expectEqual(out.status, "hibernated", "status"),
        expectTrue(out.canWake, "canWake"),
      ])
    },
    Check(name: "a machine already mid-wake is startingUp without being waking") {
      let out = ConciergeClient.parseWake(
        "{\"ok\":true,\"waking\":false,\"startingUp\":true,\"status\":\"wakingup\",\"canWake\":true}")
      return firstFailure([
        expectFalse(out.waking, "waking"),
        expectTrue(out.startingUp, "startingUp"),
      ])
    },
    Check(name: "a JSON null status parses as null, not as the string null") {
      let out = ConciergeClient.parseWake(
        "{\"ok\":true,\"waking\":false,\"startingUp\":false,\"status\":null,\"canWake\":false}")
      return firstFailure([
        expectTrue(out.status == nil, "status"),
        expectFalse(out.startingUp, "startingUp"),
        expectFalse(out.canWake, "canWake"),
      ])
    },
    Check(name: "an older server that sends none of the fields degrades to doing nothing") {
      let out = ConciergeClient.parseWake("{\"ok\":true}")
      return firstFailure([
        expectFalse(out.startingUp, "silence is the safe default"),
        expectFalse(out.waking, "waking"),
        expectFalse(out.canWake, "canWake"),
        expectTrue(out.status == nil, "status"),
      ])
    },
    Check(name: "machines carry status and wakeability, and isActive is exact") {
      let ms = ConciergeClient.parseMachines(
        """
        {"machines":[
          {"machineId":"m1","name":"Alpha","status":"active","canWake":true},
          {"machineId":"m2","name":"Beta","status":"hibernated","canWake":true},
          {"machineId":"m3","name":"Gamma","status":"WAKINGUP","canWake":false}
        ]}
        """
      )
      let byId = Dictionary(uniqueKeysWithValues: ms.map { ($0.machineId, $0) })
      return firstFailure([
        expectTrue(byId["m1"]?.isActive == true, "m1"),
        expectFalse(byId["m2"]?.isActive == true, "m2 active"),
        expectTrue(byId["m2"]?.canWake == true, "m2 canWake"),
        expectFalse(byId["m3"]?.isActive == true, "an unknown spelling is not active"),
      ])
    },
    Check(name: "a machine from an older server has no status and cannot be woken") {
      let m = ConciergeClient.parseMachines("{\"machines\":[{\"machineId\":\"m1\",\"name\":\"Alpha\"}]}")
        .first
      return firstFailure([
        expectTrue(m?.status == nil, "status"),
        expectFalse(m?.canWake == true, "canWake"),
        expectFalse(m?.isActive == true, "never active"),
      ])
    },
  ]
}

private extension Array {
  var single: Element? { count == 1 ? first : nil }
}
