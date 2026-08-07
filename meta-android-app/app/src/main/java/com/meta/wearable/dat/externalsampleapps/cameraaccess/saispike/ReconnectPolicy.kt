/*
 * sai-fi — voice concierge (whether to retry, and what to say if not).
 */

// Classifying a connection failure and pacing the retries. A pure decision, so it can be tested
// without a device — and the wording is user-facing, so it is worth pinning as well.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/**
 * When to retry a dropped Live session, and what the user is told when retrying is pointless.
 *
 * The distinction this draws is the one that matters on a call: a transient failure (a network blip,
 * a token that expired mid-call) is worth backing off and retrying, and a permanent one is not.
 * Retrying a permanent failure is the worse error of the two — it leaves the user hearing
 * "reconnecting…" forever for a call that is never coming back, instead of the one sentence that
 * explains why.
 */
object ReconnectPolicy {
  /** First retry delay. Short: most drops are a blip and recover immediately. */
  const val INITIAL_BACKOFF_MS = 1_500L

  /** The ceiling. Beyond this the user has already given up on the call anyway. */
  const val MAX_BACKOFF_MS = 15_000L

  /**
   * Permanent HTTP failures that won't recover on retry: bad token, out of credits, not owned, or
   * voice switched off server-side.
   *
   * 503 is here deliberately, against the usual reading. From this endpoint it does not mean "busy,
   * try later" — it is what the server returns when voice is disabled or unkeyed for the service,
   * which no amount of retrying changes.
   */
  fun isPermanent(code: Int): Boolean = code == 401 || code == 402 || code == 403 || code == 503

  /** A short, human reason for a permanent failure — spoken and/or shown in the ended notification. */
  fun reasonFor(code: Int): String =
      when (code) {
        402 -> "You're out of credits for voice."
        503 -> "Voice isn't available right now."
        401,
        403 -> "Voice access was denied."
        else -> "The voice call couldn't continue."
      }

  /** Double the delay, capped. */
  fun nextBackoff(currentMs: Long): Long = (currentMs * 2).coerceAtMost(MAX_BACKOFF_MS)
}
