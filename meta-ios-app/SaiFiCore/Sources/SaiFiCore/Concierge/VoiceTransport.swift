/* sai-fi — voice concierge. */

// The HTTP calls the FSM's AgentBridge makes, as one seam — so the FSM can be driven without a
// network. ScriptedAgent implements this; VoiceSession's live transport does too.
//
// Ported from Android `HttpAgentBridge.kt` (`VoiceTransport`).

import Foundation

/// The HTTP calls this bridge makes, as one seam — so the FSM can be driven without a network.
public protocol VoiceTransport: Sendable {
  /// Send a message.
  ///
  /// Returns once the agent has ACCEPTED it, not once the turn is done — this runs inside the FSM's
  /// mutex, and the FSM needs that mutex to handle the events this very message is about to produce.
  /// Throws when the agent refuses it.
  ///
  /// `follow` whether this message's response stream is the one to read. True for a new task, whose
  /// stream carries the turn. False for a steer: it lands in a turn that is already being read, so
  /// its own stream would deliver every event a second time.
  func sendMessage(
    machineId: String,
    message: String,
    attachments: JsonArray?,
    follow: Bool
  ) async throws

  /// Stop following the turn in flight, if there is one. Idempotent, and a no-op when none is.
  ///
  /// Not suspending, deliberately: it is called from inside the FSM's mutex, and closing a
  /// connection must not be able to block the lock that every agent event needs to be handled.
  ///
  /// Abstract rather than defaulted, because a double that quietly does nothing here is how this
  /// went unnoticed — the whole failure was a layer that believed a teardown was happening
  /// somewhere else.
  func abandonTurn()

  /// POST to one of the `/v1/agents` operations. Returns the parsed body; throws on a non-2xx.
  func post(path: String, body: JsonObject) async throws -> JsonObject
}

/// A non-2xx from a concierge / agents endpoint, carrying the HTTP `status` so callers can react to
/// permanent failures (402 out-of-credits, 503 voice-disabled, 401 bad-token, 403 machine-not-owned)
/// instead of retrying forever.
public struct ConciergeHttpException: Error, Sendable {
  public let status: Int
  public let message: String

  public init(status: Int, message: String) {
    self.status = status
    self.message = message
  }
}

extension ConciergeHttpException: LocalizedError {
  public var errorDescription: String? { message }
}
