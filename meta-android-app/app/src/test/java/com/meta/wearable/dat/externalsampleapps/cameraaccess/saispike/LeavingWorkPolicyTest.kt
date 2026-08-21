package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.QueuedTask
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Urgency
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving work behind — by hanging up, or by changing machines.
 *
 * The switch path had no question at all before this: `applyMachineSwitch` builds a fresh
 * VoiceSession, so the queue and the in-flight turn went with the old one and a task Sai had promised
 * out loud vanished with nothing said.
 */
class LeavingWorkPolicyTest {

  private fun ask(a: LeavingWorkAction): String {
    assertTrue("expected a question, got $a", a is LeavingWorkAction.Ask)
    return (a as LeavingWorkAction.Ask).nudge
  }

  private fun state(
      inFlight: List<String> = emptyList(),
      queued: List<String> = emptyList(),
      approval: String? = null,
  ) =
      ConciergeState(
          inFlight = inFlight,
          queue = queued.map { QueuedTask(it, Urgency.NORMAL) },
          pendingApprovalId = approval,
      )

  @Test
  fun `nothing outstanding goes straight through`() {
    assertTrue(
        LeavingWorkPolicy.decide(state(), Leaving.CALL, alreadyAsked = false, muted = false)
            is LeavingWorkAction.Proceed)
  }

  @Test
  fun `a running task is asked about before the call ends`() {
    val n = ask(
        LeavingWorkPolicy.decide(
            state(inFlight = listOf("check my email")),
            Leaving.CALL,
            alreadyAsked = false,
            muted = false))
    assertTrue("names the work", n.contains("check my email"))
    assertTrue("reads as English", n.contains("you have NOT hung up"))
    assertTrue("nothing has happened yet", n.contains("NOTHING has happened yet"))
    // The part that makes the choice actionable: left running is not lost.
    assertTrue("points at the app", n.contains("Sai app"))
    assertTrue("says the call won't carry the result", n.contains("won't hear the result"))
  }

  @Test
  fun `a running task is asked about before a machine switch, with the switch's own wording`() {
    val n = ask(
        LeavingWorkPolicy.decide(
            state(inFlight = listOf("check my email")),
            Leaving.MACHINE,
            alreadyAsked = false,
            muted = false))
    assertTrue("past participle — it slots into \"you have NOT …\"", n.contains("you have NOT moved to another machine"))
    // Where it keeps running matters here in a way it does not for a hang-up: the OLD machine.
    assertTrue(n.contains("the machine they're leaving"))
  }

  @Test
  fun `running and queued are named separately, never as one list`() {
    // The interruptScopeQuestion rule: one is work in progress they may not want to lose, the other
    // has not happened at all, and reading them together describes a queued task as underway.
    val n = ask(
        LeavingWorkPolicy.decide(
            state(inFlight = listOf("check my email"), queued = listOf("book a table")),
            Leaving.CALL,
            alreadyAsked = false,
            muted = false))
    // The subject of this clause is the MODEL, not the user — it used to read "They are still
    // working on check my email", which tells the user they are doing Sai's work.
    assertTrue(n.contains("you're still working on check my email"))
    assertTrue(n.contains("book a table hasn't started yet"))
  }

  @Test
  fun `an unanswered approval counts as outstanding work`() {
    val n = ask(
        LeavingWorkPolicy.decide(
            state(approval = "appr-1"), Leaving.CALL, alreadyAsked = false, muted = false))
    // Stands on its own: hung off a "they are …" lead-in this rendered as
    // "They are there's a request waiting on their answer".
    assertTrue(n.contains("a request is waiting on their answer"))
  }

  @Test
  fun `the second attempt goes through`() {
    // One-shot, like applyInterrupt's scope question: someone who says "hang up" twice means it, and a
    // question you cannot get past is a trap.
    assertTrue(
        LeavingWorkPolicy.decide(
            state(inFlight = listOf("check my email")),
            Leaving.CALL,
            alreadyAsked = true,
            muted = false) is LeavingWorkAction.Proceed)
  }

  @Test
  fun `muted, there is no question to put`() {
    // Asking reaches nobody. The caller decides what to do instead — a hang-up proceeds, a switch
    // proceeds and is reported on unmute — but neither can wait on an answer.
    assertTrue(
        LeavingWorkPolicy.decide(
            state(inFlight = listOf("check my email")),
            Leaving.CALL,
            alreadyAsked = false,
            muted = true) is LeavingWorkAction.Proceed)
  }
}
