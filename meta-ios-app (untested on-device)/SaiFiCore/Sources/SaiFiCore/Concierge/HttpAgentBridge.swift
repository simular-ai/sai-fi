/* sai-fi — voice concierge. */

// The FSM's AgentBridge, over the ordinary agent API.
//
// Six methods against four endpoints on `/v1/agents/*`, plus a photo stash that never leaves the
// device. This is the whole write side of a call: start work, steer it, stop it, rotate the
// conversation, resolve an approval.
//
// There is no endpoint for HOLDING a task, because holding one is not something the server is told
// about — the queue lives in the FSM and nothing else can start what is in it.
//
// `steer` is the one method whose endpoint is not obvious: it is the same POST /message as
// forwardTask. The router folds a message into a running turn on its own, which is what steering
// means, so there is nothing extra to say.
//
// Ported from Android `HttpAgentBridge.kt`. Clocks, location lines, and API headers live in
// TaskContext.swift — this file calls them rather than reimplementing them.

import Foundation
import os

public final class HttpAgentBridge: AgentBridge, @unchecked Sendable {
  private let machineId: String
  private let transport: any VoiceTransport
  private let log: @Sendable (String) -> Void
  private let abortLocalWork: @Sendable () -> Void
  private let nowMs: @Sendable () -> Int64
  private let timeZone: TimeZone

  /// Photos captured for whatever writes next.
  ///
  /// Stays on the device: a held task takes its own copy at enqueue (the FSM calls
  /// `takePendingAttachments` when it holds one), so a later capture cannot ride along with it.
  private let stash = OSAllocatedUnfairLock(initialState: [TaskAttachment]())

  /// Where the user physically is, for the request about to follow.
  ///
  /// Consumed by the NEXT task written and then cleared — the model sets `includeLocation` on the
  /// request it belongs to, so a fix left behind would ride an unrelated one.
  private let pendingLocation = OSAllocatedUnfairLock(initialState: TaskLocation?.none)

  public init(
    machineId: String,
    transport: any VoiceTransport,
    log: @escaping @Sendable (String) -> Void = { _ in },
    abortLocalWork: @escaping @Sendable () -> Void = {},
    nowMs: @escaping @Sendable () -> Int64 = {
      Int64(Date().timeIntervalSince1970 * 1000)
    },
    timeZone: TimeZone = .current
  ) {
    self.machineId = machineId
    self.transport = transport
    self.log = log
    self.abortLocalWork = abortLocalWork
    self.nowMs = nowMs
    self.timeZone = timeZone
  }

  public func addPendingAttachment(_ attachment: TaskAttachment) {
    stash.withLock { $0.append(attachment) }
  }

  public func setPendingLocation(_ location: TaskLocation) {
    pendingLocation.withLock { $0 = location }
  }

  private func takeLocation() -> TaskLocation? {
    pendingLocation.withLock { loc in
      let taken = loc
      loc = nil
      return taken
    }
  }

  public func takePendingAttachments() async -> [TaskAttachment] {
    stash.withLock { items in
      let taken = items
      items.removeAll()
      return taken
    }
  }

  /// Forward a task and follow its turn.
  ///
  /// The location fix is folded in HERE and frozen with the text — which is why the stamp is an
  /// absolute UTC instant rather than "just now". A held task takes its fix when it is HELD, not
  /// when it drains, and it may drain much later.
  ///
  /// Returns the empty string, because nothing here reads a session id — the FSM keeps no session
  /// identity on purpose (see State.swift).
  public func forwardTask(text: String, attachments: [TaskAttachment]?) async throws -> String {
    try await transport.sendMessage(
      machineId: machineId,
      message: taskText(text, location: takeLocation(), nowMs: nowMs(), timeZone: timeZone),
      attachments: attachments.toJsonOrNull(),
      follow: true)
    return ""
  }

  /// Steer the running turn.
  ///
  /// The same endpoint as a new task, deliberately: the router folds a message into a turn already
  /// running, which is exactly what steering is. No location — a correction mid-turn is about the
  /// task, not about where the user is standing.
  public func steer(text: String) async throws {
    try await transport.sendMessage(
      machineId: machineId, message: text, attachments: nil, follow: false)
  }

  /// Stop the running turn — ALL THREE halves of it, and the local two go first.
  ///
  /// Stop LISTENING, stop the device's own work, then ask the server. Both local steps run before
  /// the POST and regardless of what it does, because the POST is a round trip that can be slow or
  /// fail outright and the two device-side failures do not need the server's permission to be fixed.
  ///
  /// The server's half is best-effort and is now at least OBSERVABLE: `{aborted: false}` means there
  /// was nothing there to stop, which was previously indistinguishable from success.
  public func abort() async throws {
    transport.abandonTurn()
    abortLocalWork()
    // Best-effort, and it has to be CODED that way and not merely described that way. A throw here
    // returned before `applyInterrupt` could close the turn out, leaving the FSM in `working` with
    // its reader already torn down — so no event could ever arrive to end it, and admission held
    // every later task behind a turn that could not finish.
    do {
      let body = jsonWire(["machineId": machineId])
      let reply = try await transport.post(path: "abort", body: body)
      if !reply.optBool("aborted", true) {
        log("[bridge] abort: nothing to stop, the server says")
      }
    } catch {
      log("[bridge] the server was not told to abort — \(error.localizedDescription)")
    }
  }

  /// Rotate onto a fresh conversation.
  ///
  /// A 429 is the rate limit, and it is worth telling apart from a failure: "you've done this a lot
  /// lately" and "it broke" need different things said to the user.
  ///
  /// The body comes from `VoiceChannelClient.newSessionBody` rather than being built here, because
  /// built here it forgot the `channel` — and the route defaults an absent one to `cli`, so a user
  /// saying "start fresh" rotated the TERMINAL's conversation and left this one exactly where it
  /// was, poison and all.
  public func resetSession() async -> ResetOutcome {
    do {
      _ = try await transport.post(
        path: "new-session", body: VoiceChannelClient.newSessionBody(machineId: machineId))
      return .ok
    } catch let e as ConciergeHttpException {
      return e.status == 429 ? .rateLimited : .failed
    } catch {
      log("new-session failed: \(error.localizedDescription)")
      return .failed
    }
  }

  /// Resolve an approval.
  ///
  /// A rejected selection comes back 400 and the transport throws — which is exactly what the FSM
  /// wants: it keeps the request pending, keeps its timer, and nudges the model to re-present.
  /// Swallowing it would clear the FSM's pending state while the request stays open, and the call
  /// would deadlock waiting for an answer it believes it already gave.
  ///
  /// `selections` is positional, one non-empty group per question; the FSM grouped them on the way
  /// here. An empty group is sent as-is rather than dropped — the agent refuses the whole
  /// resolution, which is the honest outcome for a question the user never answered, and silently
  /// omitting it would approve the card with an answer missing.
  public func resolveApproval(
    id: String,
    decision: ApprovalDecision,
    selection: ApprovalSelection?
  ) async throws {
    var raw: [String: Any] = [
      "approvalId": id,
      "response": decision.rawValue,
    ]
    if let groups = selection?.selections, !groups.isEmpty {
      raw["selections"] = groups
    }
    _ = try await transport.post(path: "approve", body: jsonWire(raw))
  }
}

extension [TaskAttachment] {
  func toJsonOrNull() -> JsonArray? {
    if isEmpty { return nil }
    let arr: [Any] = map { a in
      var o: [String: Any] = [
        "path": a.path,
        "name": a.name,
        "mime": a.mime,
        "size": a.size,
      ]
      if let u = a.downloadUrl { o["downloadUrl"] = u }
      if let id = a.fileId { o["fileId"] = id }
      if let w = a.width { o["width"] = w }
      if let h = a.height { o["height"] = h }
      return o
    }
    return jsonArrayWire(arr)
  }
}

extension Optional where Wrapped == [TaskAttachment] {
  func toJsonOrNull() -> JsonArray? {
    self?.toJsonOrNull()
  }
}
