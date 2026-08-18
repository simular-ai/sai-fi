/*
 * sai-fi — voice concierge.
 */

// ActivityLog — Kotlin port of cloud-api's core/activity-log.ts. A rolling record of what the agent has
// been doing + a step count (past facts only), surfaced to the Live model on demand via the
// getSaiStatus tool. Fed every `agent-activity` event from the concierge WS. It deliberately does NOT
// report elapsed time: whether that's measured from the session or the task is ambiguous to the user,
// so we drop it (msSinceTaskStart stays for the internal ask-first gate, not for the model to speak).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONObject

class ActivityLog(
    private val maxLines: Int = 12,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
  private val lines = ArrayDeque<String>()
  private var startedAt: Long? = null
  private var endedAt: Long? = null
  private var steps = 0
  // The server's projection of the session (the `session-state` event). Held as STATE, not folded
  // into `lines`: the buffer is a rolling history that drops its oldest entry after `maxLines`, so a
  // queued task would silently stop being mentioned while it was still waiting. What is waiting now
  // is a fact about the present, and has to be answered from the latest word rather than from
  // whatever is still in the scrollback.
  private var queued: List<String> = emptyList()
  private var blockedOn: String? = null

  fun record(event: JSONObject) {
    track(event)
    append(event)
  }

  fun reset() {
    lines.clear()
    startedAt = null
    endedAt = null
    steps = 0
    queued = emptyList()
    blockedOn = null
  }

  /**
   * The getSaiStatus tool result — what is happening now, then the past as facts.
   *
   * Three distinguishable states, because collapsing them is how the honesty bugs happen: work is
   * RUNNING, or it is blocked on the USER, or it is accepted and NOT STARTED. "Still working" said
   * about a turn parked on an unanswered question is the 2026-07-31 failure (Sai reported waiting on
   * a third party for a question it had asked itself), and "still working" said about a queued
   * task is the same lie one step earlier.
   */
  fun statusText(): String {
    val out = mutableListOf<String>()
    val blocked = blockedOn
    if (blocked != null) {
      // Deliberately ahead of the step count: the step count invites "still working", and nothing is
      // working. Whatever progress was made, the state now is a question the user has not answered.
      out.add(
          "BLOCKED ON THE USER — $steps step(s) done, then it stopped to ask them: " +
              "\"$blocked\". Nothing is progressing until they answer. The question is YOURS, " +
              "not a third party's, so never say you're waiting to hear back from anyone else.",
      )
    } else if (startedAt != null) {
      out.add(
          if (isRunning())
              "Still working — $steps step(s) done so far. You have no estimate of how much longer; " +
                  "don't invent one."
          else "Finished after $steps step(s).",
      )
    }
    if (queued.isNotEmpty()) {
      out.add(
          "NOT STARTED YET, waiting their turn (${queued.size}, in order): " +
              "${queued.joinToString(", ") { "\"$it\"" }}. " +
              "The computer runs one task at a time, so these begin only when the current one ends. " +
              "Describe them as next, never as underway or being worked on.",
      )
    }
    out.add(
        if (lines.isEmpty()) "No activity reported yet."
        else "Recent activity (oldest first):\n${lines.joinToString("\n")}",
    )
    return out.joinToString("\n")
  }

  /**
   * Milliseconds the user has waited on the current/last task — `endedAt−startedAt` once finished, else
   * `now−startedAt`; null if no task has begun. Used to gate "ask before delivering" after a long wait.
   */
  fun msSinceTaskStart(): Long? = startedAt?.let { (endedAt ?: now()) - it }

  private fun isRunning(): Boolean = startedAt != null && endedAt == null

  private fun begin() {
    // A fresh task begins when work starts and none is currently running.
    if (startedAt == null || endedAt != null) {
      startedAt = now()
      endedAt = null
      steps = 0
      // The block belonged to the task that just ended. Carrying it into a new one makes statusText()
      // lead with a question about work nobody is doing any more — see the 'approval-resolved' case
      // for why a stale block is worse than no block at all.
      blockedOn = null
    }
  }

  private fun end() {
    if (startedAt != null && endedAt == null) endedAt = now()
  }

  private fun track(e: JSONObject) {
    when (e.optString("type")) {
      // Replaces wholesale — it is the server's current picture, not a delta. Deliberately does NOT
      // touch the task timer or the step count: nothing has happened to the agent, we have just been
      // told what is outstanding.
      "session-state" -> {
        val arr = e.optJSONArray("queued")
        queued = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
        blockedOn = e.optString("blockedOn").takeIf { it.isNotEmpty() }
      }
      "status" -> {
        val s = e.optString("status")
        // `aborting` is a task ENDING, not one starting, and it is not on the begin() side even
        // though it is not terminal either. Treated as work starting, an abort that arrives after the
        // task already finished cleared the end time and zeroed the step count, so statusText()
        // answered "Still working — 0 step(s) done so far" about a task being cancelled: running when
        // nothing is, and no history to show for it. Left as-is until it lands — the abort may not
        // take, and idle/error/complete all follow it and do the ending properly.
        if (s == "idle" || s == "error") end() else if (s != "aborting") begin()
      }
      "progress" -> {
        begin()
        steps++
      }
      // The question has an answer, however it arrived — the user may have resolved it in the desktop
      // app, or it may have timed out. Either way the agent is no longer parked on it.
      //
      // Cleared here rather than waiting for the next `session-state`, because that event is the
      // server volunteering its picture and nothing guarantees one follows a resolution. Until it
      // does, statusText() keeps leading with "BLOCKED ON THE USER — nothing is progressing until they
      // answer" about a question they have already answered, and suppresses the "Still working" line
      // entirely. That is the 2026-07-31 honesty failure inverted: blaming the user for a wait that is
      // over.
      //
      // Not matched on the id: `session-state.blockedOn` carries the question TEXT, not the approval
      // id, so there is nothing to correlate against. Any resolution clears the block, and the next
      // `session-state` re-asserts one if the server still sees it.
      "approval-resolved" -> {
        blockedOn = null
      }
      "complete",
      "error" -> end()
    }
  }

  private fun append(e: JSONObject) {
    val line = lineFor(e)
    if (line.isEmpty()) return
    if (lines.lastOrNull() == line) return // skip consecutive dups
    lines.addLast(line)
    if (lines.size > maxLines) lines.removeFirst()
  }

  private fun lineFor(e: JSONObject): String =
      when (e.optString("type")) {
        "status" -> "status: ${e.optString("status")}"
        "progress" ->
            if (e.optString("tool").isNotEmpty()) "${e.optString("text")} (${e.optString("tool")})"
            else e.optString("text")
        "text" -> e.optString("text")
        "approval-request" -> "needs you: ${e.optString("title")}"
        // The counterpart to 'needs you:' above. Without it the buffer keeps an unanswered-looking
        // question in the scrollback forever, which reads as still-pending even after statusText() has
        // correctly stopped calling the task blocked. Carries no title — the event has only an id —
        // but it always follows the 'needs you:' line that names it.
        "approval-resolved" ->
            e.optString("status").let { s ->
              if (s == "timeout" || s == "expired") "stopped waiting for that request"
              else "that request was answered ($s)"
            }
        "complete" -> "finished" + summarySuffix(e)
        "error" -> "error: ${e.optString("text")}"
        "notice" -> "note: ${e.optString("text")}"
        // "session-state" is deliberately absent: it is the present, and this buffer is the past. It
        // is rendered by statusText() from the stored projection instead, so it cannot scroll away.
        else -> ""
      }
}

private fun summarySuffix(e: JSONObject): String {
  val s = e.optString("summary")
  return if (s.isNotEmpty()) ": $s" else ""
}
