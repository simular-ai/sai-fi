/* sai-fi — voice concierge. */

// ActivityLog — a rolling record of what the agent has been doing, plus a step count (past facts
// only), surfaced to the Live model on demand via the `getSaiStatus` tool. Fed every agent event.
//
// It deliberately does NOT report elapsed time: whether that is measured from the session or the task
// is ambiguous to the user, so it is dropped. `msSinceTaskStart` stays for the internal ask-first
// gate, not for the model to speak.
//
// Ported from the Android `ActivityLog.kt`, which came from cloud-api `core/activity-log.ts`.
// `parity/activity-log-status.json` pins `statusText()` and `msSinceTaskStart()` over sixteen
// scripted timelines on a fixed clock.
//
// NOT thread-safe, exactly as the Kotlin is not: it is driven from the call's own confined context
// (`@MainActor` in the app layer, the harness's single task in tests). Making it an actor would put
// an `await` in front of `getSaiStatus`, which is answered synchronously inside a tool response.

import Foundation

public final class ActivityLog {
  private let maxLines: Int
  private let now: @Sendable () -> Int64

  private var lines: [String] = []
  private var startedAt: Int64?
  private var endedAt: Int64?
  private var steps = 0

  /// The server's projection of the session (the `session-state` event). Held as STATE, not folded
  /// into `lines`: the buffer is a rolling history that drops its oldest entry after `maxLines`, so a
  /// queued task would silently stop being mentioned while it was still waiting. What is waiting now
  /// is a fact about the present, and has to be answered from the latest word rather than from
  /// whatever is still in the scrollback.
  private var queued: [String] = []
  private var blockedOn: String?

  /// The last finished task's outcome, held as STATE for exactly the reason `queued` is: the buffer
  /// rolls, and this is the one line in it that has to survive.
  ///
  /// `getSaiStatus` is what the ask-first nudge sends the model back to when it is holding a result
  /// and the user finally asks — and the result reached that nudge as a `complete` line in the
  /// scrollback, twelve entries from being dropped. Any task that runs afterwards pushes it out, so
  /// the answer to "how did that go?" was a buffer full of the NEXT task's progress and no trace of
  /// the one being asked about. A model with nothing to find says the task stopped.
  private var lastOutcome: String?

  public init(maxLines: Int = 12, now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
    self.maxLines = maxLines
    self.now = now
  }

  public func record(_ event: JsonObject) {
    track(event)
    append(event)
  }

  public func reset() {
    lines.removeAll()
    startedAt = nil
    endedAt = nil
    steps = 0
    queued = []
    blockedOn = nil
    lastOutcome = nil
  }

  /// The getSaiStatus tool result — what is happening now, then the past as facts.
  ///
  /// Three distinguishable states, because collapsing them is how the honesty bugs happen: work is
  /// RUNNING, or it is blocked on the USER, or it is accepted and NOT STARTED. "Still working" said
  /// about a turn parked on an unanswered question is the 2026-07-31 failure (Sai reported waiting on
  /// a third party for a question it had asked itself), and "still working" said about a queued task
  /// is the same lie one step earlier.
  public func statusText() -> String {
    var out: [String] = []

    if let blocked = blockedOn {
      // Deliberately ahead of the step count: the step count invites "still working", and nothing is
      // working. Whatever progress was made, the state now is a question the user has not answered.
      out.append(
        "BLOCKED ON THE USER — \(steps) step(s) done, then it stopped to ask them: "
          + "\(q)\(blocked)\(q). Nothing is progressing until they answer. The question is YOURS, "
          + "not a third party's, so never say you're waiting to hear back from anyone else.")
    } else if startedAt != nil {
      out.append(
        isRunning()
          ? "Still working — \(steps) step(s) done so far. You have no estimate of how much longer; "
            + "don't invent one."
          : "Finished after \(steps) step(s).")
    }

    // Attributed, not just repeated. Once another task is running, the same words describe something
    // the user is no longer asking about, and an unattributed result is how a finished email summary
    // gets read back as the outcome of the booking that started after it.
    if let outcome = lastOutcome {
      out.append(
        (isRunning() || blockedOn != nil)
          ? "AN EARLIER TASK, now finished, reported this: \(q)\(outcome)\(q). It belongs to that "
            + "earlier task, NOT to the one above. Never report it as the outcome of what is "
            + "running now."
          : "What it reported: \(q)\(outcome)\(q).")
    }

    if !queued.isEmpty {
      out.append(
        "NOT STARTED YET, waiting their turn (\(queued.count), in order): "
          + "\(queued.map { "\(q)\($0)\(q)" }.joined(separator: ", ")). "
          + "The computer runs one task at a time, so these begin only when the current one ends. "
          + "Describe them as next, never as underway or being worked on.")
    }

    out.append(
      lines.isEmpty
        ? "No activity reported yet."
        : "Recent activity (oldest first):\n\(lines.joined(separator: "\n"))")

    return out.joined(separator: "\n")
  }

  /// Milliseconds the user has waited on the current/last task — `endedAt − startedAt` once finished,
  /// else `now − startedAt`; nil if no task has begun. Used to gate "ask before delivering" after a
  /// long wait.
  public func msSinceTaskStart() -> Int64? {
    guard let startedAt else { return nil }
    return (endedAt ?? now()) - startedAt
  }

  private func isRunning() -> Bool { startedAt != nil && endedAt == nil }

  private func begin() {
    // A fresh task begins when work starts and none is currently running.
    if startedAt == nil || endedAt != nil {
      startedAt = now()
      endedAt = nil
      steps = 0
      // The block belonged to the task that just ended. Carrying it into a new one makes statusText()
      // lead with a question about work nobody is doing any more — see the `approval-resolved` case
      // for why a stale block is worse than no block at all.
      blockedOn = nil
    }
  }

  private func end() {
    if startedAt != nil && endedAt == nil { endedAt = now() }
  }

  private func track(_ e: JsonObject) {
    switch e.optString("type") {

    // Replaces wholesale — it is the server's current picture, not a delta. Deliberately does NOT
    // touch the task timer or the step count: nothing has happened to the agent, we have just been
    // told what is outstanding.
    case "session-state":
      queued = e.optArray("queued").map { arr in (0..<arr.count).map { arr.optString($0) } } ?? []
      let blocked = e.optString("blockedOn")
      blockedOn = blocked.isEmpty ? nil : blocked

    case "status":
      let s = e.optString("status")
      // `aborting` is a task ENDING, not one starting, and it is not on the begin() side even though
      // it is not terminal either. Treated as work starting, an abort that arrives after the task
      // already finished cleared the end time and zeroed the step count, so statusText() answered
      // "Still working — 0 step(s) done so far" about a task being cancelled: running when nothing
      // is, and no history to show for it. Left as-is until it lands — the abort may not take, and
      // idle/error/complete all follow it and do the ending properly.
      if s == "idle" || s == "error" {
        end()
      } else if s != "aborting" {
        begin()
      }

    case "progress":
      begin()
      steps += 1

    // The question has an answer, however it arrived — the user may have resolved it in the desktop
    // app, or it may have timed out. Either way the agent is no longer parked on it.
    //
    // Cleared here rather than waiting for the next `session-state`, because that event is the server
    // volunteering its picture and nothing guarantees one follows a resolution. Until it does,
    // statusText() keeps leading with "BLOCKED ON THE USER — nothing is progressing until they
    // answer" about a question they have already answered, and suppresses the "Still working" line
    // entirely. That is the 2026-07-31 honesty failure inverted: blaming the user for a wait that is
    // over.
    //
    // Not matched on the id: `session-state.blockedOn` carries the question TEXT, not the approval
    // id, so there is nothing to correlate against. Any resolution clears the block, and the next
    // `session-state` re-asserts one if the server still sees it.
    case "approval-resolved":
      blockedOn = nil

    // The outcome is kept whether the task succeeded or failed, and a `complete` with no summary is
    // recorded as exactly that rather than left nil: "it finished and said nothing" is a real answer
    // to "how did that go?", and the absence of one is what gets filled in with a guess.
    case "complete":
      end()
      let summary = e.optString("summary")
      lastOutcome = summary.isEmpty ? "finished without saying what it found" : summary

    case "error":
      end()
      lastOutcome = "it failed: \(e.optString("text"))"

    default:
      break
    }
  }

  private func append(_ e: JsonObject) {
    let line = lineFor(e)
    if line.isEmpty { return }
    if lines.last == line { return }  // skip consecutive dups
    lines.append(line)
    if lines.count > maxLines { lines.removeFirst() }
  }

  private func lineFor(_ e: JsonObject) -> String {
    switch e.optString("type") {
    case "status":
      return "status: \(e.optString("status"))"

    case "progress":
      let tool = e.optString("tool")
      return tool.isEmpty ? e.optString("text") : "\(e.optString("text")) (\(tool))"

    case "text":
      return e.optString("text")

    case "approval-request":
      return "needs you: \(e.optString("title"))"

    // The counterpart to 'needs you:' above. Without it the buffer keeps an unanswered-looking
    // question in the scrollback forever, which reads as still-pending even after statusText() has
    // correctly stopped calling the task blocked. Carries no title — the event has only an id — but
    // it always follows the 'needs you:' line that names it.
    case "approval-resolved":
      let s = e.optString("status")
      return (s == "timeout" || s == "expired")
        ? "stopped waiting for that request"
        : "that request was answered (\(s))"

    case "complete":
      return "finished" + summarySuffix(e)

    case "error":
      return "error: \(e.optString("text"))"

    case "notice":
      return "note: \(e.optString("text"))"

    // "session-state" is deliberately absent: it is the present, and this buffer is the past. It is
    // rendered by statusText() from the stored projection instead, so it cannot scroll away.
    default:
      return ""
    }
  }
}

private func summarySuffix(_ e: JsonObject) -> String {
  let s = e.optString("summary")
  return s.isEmpty ? "" : ": \(s)"
}
