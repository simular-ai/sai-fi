package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reaction rules, which were 74 lines inside a constructor argument reading six fields of a
 * foreground Service — and so had never been tested.
 *
 * Every case here is a behaviour that was found by hearing it fail on a device.
 */
class AgentEventRouterTest {

  private val THRESHOLD = 15_000L
  private val THROTTLE = 30_000L

  private fun route(
      event: JSONObject,
      muted: Boolean = false,
      userQuietMs: Long = 0,
      sinceLastStepFailureMs: Long = Long.MAX_VALUE,
  ) =
      AgentEventRouter.route(
          event = event,
          muted = muted,
          userQuietMs = userQuietMs,
          askFirstThresholdMs = THRESHOLD,
          sinceLastStepFailureMs = sinceLastStepFailureMs,
          stepFailureIntervalMs = THROTTLE,
      )

  private fun complete(summary: String = "3 unread, all newsletters") =
      JSONObject().put("type", "complete").put("summary", summary)

  private fun progress(failed: Boolean) =
      JSONObject().put("type", "progress").put("text", "opening the site").put("failed", failed)

  // ── ask-first: about the USER's silence, not the task's duration ───────────
  //
  // The gate used to measure how long the TASK took, which is a different question. A 30-second email
  // summary tripped it while the user was mid-sentence with Sai, so Sai was told "the user has been
  // away a while — say NOTHING", obeyed, and the result was never delivered.

  @Test
  fun `a completion reaches a user who is present`() {
    val action = route(complete(), userQuietMs = 2_000)
    assertTrue(action.toString(), action is NudgeAction.Inject)
    assertEquals("complete", (action as NudgeAction.Inject).kind)
  }

  @Test
  fun `a completion is offered rather than announced when the user has gone quiet`() {
    val action = route(complete(), userQuietMs = THRESHOLD + 1)
    assertTrue(action.toString(), action is NudgeAction.Inject)
    // The label must name WHY, or a result the user never heard is indistinguishable from one Sai was
    // correctly told to sit on.
    assertTrue((action as NudgeAction.Inject).kind, action.kind.contains("ask-first"))
  }

  @Test
  fun `a user who never spoke this call counts as quiet`() {
    val action = route(complete(), userQuietMs = Long.MAX_VALUE)
    assertTrue((action as NudgeAction.Inject).kind, action.kind.contains("never spoke"))
  }

  // ── muted ─────────────────────────────────────────────────────────────────

  @Test
  fun `a completion while muted is held, not spoken into a room Sai was told to be quiet in`() {
    val action = route(complete(), muted = true)
    assertTrue(action.toString(), action is NudgeAction.Hold)
    assertEquals("complete", (action as NudgeAction.Hold).kind)
  }

  // Stale by nature: both are true for about a minute. Replayed on unmute they describe a world that
  // has moved on, and the completion supersedes them anyway.
  @Test
  fun `stale-by-nature events are dropped while muted rather than held`() {
    val notice = route(JSONObject().put("type", "notice").put("text", "waking the machine"), muted = true)
    assertTrue(notice.toString(), notice is NudgeAction.Drop)
    assertTrue((notice as NudgeAction.Drop).why, notice.why.contains("stale"))

    val step = route(progress(failed = true), muted = true)
    assertTrue(step.toString(), step is NudgeAction.Drop)
    assertTrue((step as NudgeAction.Drop).why, step.why.contains("stale"))
  }

  // ── a failed step: throttled, never suppressed ─────────────────────────────

  @Test
  fun `ordinary progress is not something to react to`() {
    assertEquals(NudgeAction.Ignore, route(progress(failed = false)))
  }

  @Test
  fun `the first failed step goes out`() {
    val action = route(progress(failed = true))
    assertTrue(action.toString(), action is NudgeAction.InjectStepFailure)
    // It has to carry the fact that there is no result yet, or Sai invents one.
    assertTrue((action as NudgeAction.InjectStepFailure).nudge.isNotEmpty())
  }

  /**
   * A long task can fail several steps while recovering. One nudge per failure floods the session until
   * Sai blurts about it; one every 30s carries the fact without a running commentary. The suppressed
   * ones are still named, so the log shows they happened.
   */
  @Test
  fun `a second failed step within the window is dropped, and says how long ago`() {
    val action = route(progress(failed = true), sinceLastStepFailureMs = 5_000)
    assertTrue(action.toString(), action is NudgeAction.Drop)
    assertTrue((action as NudgeAction.Drop).why, action.why.contains("throttled"))
    assertTrue(action.why, action.why.contains("5s ago"))
  }

  @Test
  fun `a failed step after the window goes out again`() {
    val action = route(progress(failed = true), sinceLastStepFailureMs = THROTTLE + 1)
    assertTrue(action.toString(), action is NudgeAction.InjectStepFailure)
  }

  // A notice is the one thing that must be relayed BEFORE the task has produced anything — the "machine
  // is waking" minute the user would otherwise experience as silence.
  @Test
  fun `a notice reaches an audible user`() {
    val action = route(JSONObject().put("type", "notice").put("text", "waking the machine"))
    assertTrue(action.toString(), action is NudgeAction.Inject)
    assertEquals("notice", (action as NudgeAction.Inject).kind)
  }

  @Test
  fun `an internal event produces nothing`() {
    assertEquals(NudgeAction.Ignore, route(JSONObject().put("type", "status").put("status", "processing")))
  }
}
