/*
 * sai-fi — voice concierge (has DAT actually said whether the glasses are there?).
 */

// Turning DAT's device flows into three states instead of two — and, the part that has now bitten
// twice, deciding when *silence* from DAT has gone on long enough to count as an answer. A pure
// decision, so the rule is testable without glasses, a companion app, or an SDK.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Whether DAT reports a connected device — `true`/`false` once it has told us, `null` while it hasn't.
 *
 * The three states exist because gating UI on a plain Boolean disabled the camera grant on exactly the
 * run that needed it (a fresh install, right after registration). But a tri-state alone does not fix
 * that, which is the trap this class exists to close: **`Wearables.devices` is a `StateFlow` seeded
 * with an empty set**, filled in asynchronously by ACDC device callbacks. A collector is therefore
 * handed "nothing is connected" *synchronously on subscribe*, milliseconds after `onCreate`, whether or
 * not anything is connected. Map that first value straight to `false` and `null` never survives long
 * enough to protect anything — the tri-state is decoration and the dead button is back.
 *
 * DAT has no "discovery finished" signal to wait for, so absence can only be resolved by time: nothing
 * reported reads `null` until [SETTLE_MS] has passed still hearing nothing, and `false` after that.
 * Once DAT has named a device whose link state we could actually read, it is talking to us, and a
 * later empty set is a real answer — reported as `false` immediately, with no second settle wait.
 *
 * Drive it through [observe], not by calling the step methods from an Activity collector. The settle
 * delay and the `collectLatest` cancel-on-new-reading are part of the contract — leave them in the
 * Activity and the StateFlow trap can ship again while the step-method tests stay green.
 *
 * Not thread-safe, and not meant to be: one instance per collector, touched only from that collector.
 */
class GlassesLink {
  /**
   * True once DAT has said something we could actually read — either a device's link state, or
   * nothing at all for a whole settle window.
   */
  var hasAnswered = false
    private set

  /** DAT reported a device it can read: [connected] is the answer, whatever it is. */
  fun onLinkState(connected: Boolean): Boolean {
    hasAnswered = true
    return connected
  }

  /**
   * DAT is reporting nothing readable — an empty device set, or ids whose metadata hasn't landed yet.
   *
   * `false` if DAT has answered before (so this is a device genuinely going away), otherwise `null`:
   * we have not heard from it, and saying "disconnected" would be inventing an answer.
   */
  fun onNothingReported(): Boolean? = if (hasAnswered) false else null

  /** [SETTLE_MS] passed with nothing reported. Absence is now the answer. */
  fun onSettleElapsed(): Boolean {
    hasAnswered = true
    return false
  }

  /**
   * Drive this [GlassesLink] from a stream of readings and publish every decision.
   *
   * Upstream convention: `null` means nothing readable (empty device set / metadata not yet landed);
   * `true`/`false` means DAT reported a device whose link state we could read. Uses [collectLatest]
   * so a device appearing mid-settle cancels the wait — without that, a late settle would overwrite a
   * real "connected" with "disconnected".
   *
   * This is the function the Activity must call. The step methods above exist so the rule itself is
   * unit-testable without a dispatcher; [observe] is what makes the StateFlow-seeded-empty trap
   * reproducible under virtual time.
   */
  suspend fun observe(readings: Flow<Boolean?>, onPublish: (Boolean?) -> Unit) {
    readings.collectLatest { reading ->
      if (reading == null) {
        val reported = onNothingReported()
        onPublish(reported)
        if (reported == null) {
          delay(SETTLE_MS)
          onPublish(onSettleElapsed())
        }
      } else {
        onPublish(onLinkState(reading))
      }
    }
  }

  companion object {
    /**
     * How long nothing-reported stays `null` before it becomes an affirmative "no device".
     *
     * Deliberately generous, because the two ways of being wrong here are not equally bad. Concluding
     * "no glasses" too early greys out the camera grant while the glasses are sitting there powered
     * on — the original bug. Staying unknown too long only shows "checking…" a beat longer and leaves
     * the grant button live, and the button re-probes DAT for real when pressed, so a wrong guess in
     * that direction still gives the user a true answer.
     */
    const val SETTLE_MS = 5_000L
  }
}
