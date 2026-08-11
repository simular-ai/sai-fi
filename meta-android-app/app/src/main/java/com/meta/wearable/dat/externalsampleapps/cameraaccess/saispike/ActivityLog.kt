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
    if (startedAt == null || endedAt != null) {
      startedAt = now()
      endedAt = null
      steps = 0
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
        if (s == "idle" || s == "error") end() else begin()
      }
      "progress" -> {
        begin()
        steps++
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
