/* sai-fi — voice concierge. */

// Classifying a connection failure and pacing the retries. A pure decision, so it can be tested
// without a device — and the wording is user-facing, so it is worth pinning as well.
//
// Ported from Android `ReconnectPolicy.kt`.

/// When to retry a dropped Live session, and what the user is told when retrying is pointless.
///
/// The distinction this draws is the one that matters on a call: a transient failure (a network blip,
/// a token that expired mid-call) is worth backing off and retrying, and a permanent one is not.
/// Retrying a permanent failure is the worse error of the two — it leaves the user hearing
/// "reconnecting…" forever for a call that is never coming back, instead of the one sentence that
/// explains why.
public enum ReconnectPolicy {
  /// First retry delay. Short: most drops are a blip and recover immediately.
  public static let initialBackoffMs: Int64 = 1_500

  /// The ceiling. Beyond this the user has already given up on the call anyway.
  public static let maxBackoffMs: Int64 = 15_000

  /// Permanent HTTP failures that won't recover on retry: bad token, out of credits, not owned, or
  /// voice switched off server-side.
  ///
  /// 503 is here deliberately, against the usual reading. From this endpoint it does not mean "busy,
  /// try later" — it is what the server returns when voice is disabled or unkeyed for the service,
  /// which no amount of retrying changes.
  public static func isPermanent(_ code: Int) -> Bool {
    code == 401 || code == 402 || code == 403 || code == 503
  }

  /// A short, human reason for a permanent failure — spoken and/or shown in the ended notification.
  public static func reasonFor(_ code: Int) -> String {
    switch code {
    case 402: return "You're out of credits for voice."
    case 503: return "Voice isn't available right now."
    case 401, 403: return "Voice access was denied."
    default: return "The voice call couldn't continue."
    }
  }

  /// Double the delay, capped.
  public static func nextBackoff(_ currentMs: Int64) -> Int64 {
    min(currentMs * 2, maxBackoffMs)
  }
}
