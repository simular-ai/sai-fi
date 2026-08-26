/* sai-fi — voice concierge. */

// The parity gate for `ActivityLog.swift`.
//
// Replays `parity/activity-log-status.json`: sixteen scripted timelines, each a list of
// (clock reading, event) steps followed by a read at a fixed instant. The clock is injected, so the
// output is reproducible — which is the only reason `msSinceTaskStart()` can be pinned at all.
//
// Ported from `ActivityLogGoldenTest.kt` / `ActivityLogTest.kt`.

import Foundation

private let expectedActivityLogCaseCount = 16

func activityLogChecks(_ fixtures: ParityFixtures) -> [Check] {
  let cases: [JsonObject]
  do {
    cases = try fixtures.load("activity-log-status.json")
  } catch {
    let reason = "\(error)"
    return [Check(name: "activity-log-status.json loads") { reason }]
  }

  var checks: [Check] = [
    Check(name: "activity-log-status.json still has every case the Kotlin generates") {
      expectEqual(cases.count, expectedActivityLogCaseCount, "activity-log-status.json case count")
    }
  ]

  for fixture in cases {
    let name = fixture.optString("name", "<unnamed>")
    checks.append(
      Check(name: "activity-log: \(name)") {
        guard
          let input = fixture.optObject("input"),
          let expected = fixture.optObject("expected")
        else { return "fixture '\(name)' is malformed" }
        return replayActivityLog(input, expecting: expected)
      })
  }

  checks += activityLogMeaningChecks()
  return checks
}

/// Drive one scripted timeline and compare both readings.
private func replayActivityLog(_ input: JsonObject, expecting expected: JsonObject) -> String? {
  // The clock is stepped explicitly: `begin()` and `end()` read it at the moment the event is
  // recorded, and `msSinceTaskStart()` reads it at `readAt`. A wall clock here would make
  // `msSinceTaskStart` unpinnable.
  let clock = MutableClock()
  let log = ActivityLog(
    maxLines: input.optIntOrNil("maxLines") ?? 12,
    now: { clock.value })

  for step in input.optArray("timeline")?.objects() ?? [] {
    guard let event = step.optObject("event") else { continue }
    clock.value = step.optInt64OrNil("at") ?? 0
    log.record(event)
  }

  clock.value = input.optInt64OrNil("readAt") ?? 0

  let producedStatus = log.statusText()
  let expectedStatus = expected.optString("statusText")
  if producedStatus != expectedStatus {
    return """
      statusText drift
            kotlin: \(expectedStatus)
            swift : \(producedStatus)
      """
  }

  let producedMs = log.msSinceTaskStart()
  let expectedMs = expected.optInt64OrNil("msSinceTaskStart")
  if producedMs != expectedMs {
    return "msSinceTaskStart: expected \(expectedMs.map(String.init) ?? "nil"), "
      + "got \(producedMs.map(String.init) ?? "nil")"
  }
  return nil
}

/// A settable clock. `ActivityLog`'s `now` closure is `@Sendable`, so the box has to be too; it is
/// only ever touched from the one task driving the replay.
private final class MutableClock: @unchecked Sendable {
  var value: Int64 = 0
}

// ── what the log SAYS ────────────────────────────────────────────────────────

private func activityLogMeaningChecks() -> [Check] {
  [
    Check(name: "a block on the user is never described as still working") {
      // The 2026-07-31 failure: parked on a question it had asked itself, Sai reported waiting to
      // hear back from a third party. "Still working" and "blocked" are different states and
      // collapsing them is how the honesty bugs happen.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "progress", "text": "searching"]))
      log.record(JsonObject(["type": "session-state", "blockedOn": "which flight?", "queued": []]))
      let status = log.statusText()
      return firstFailure([
        expectTrue(status.hasPrefix("BLOCKED ON THE USER"), "leads with the block"),
        expectFalse(status.contains("Still working"), "never says still working"),
        expectTrue(status.contains("The question is YOURS"), "the question is Sai's own"),
        expectTrue(
          status.contains("never say you're waiting to hear back from anyone else"),
          "no third party"),
      ])
    },

    Check(name: "a queued task is described as next, never as underway") {
      // The same lie one step earlier: a task the server has accepted but not started.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "session-state", "queued": ["book a table"]]))
      let status = log.statusText()
      return firstFailure([
        expectTrue(status.contains("NOT STARTED YET"), "says not started"),
        expectTrue(status.contains("Describe them as next, never as underway"), "how to say it"),
      ])
    },

    Check(name: "a finished result is attributed once another task is running") {
      // An unattributed result is how a finished email summary gets read back as the outcome of the
      // booking that started after it.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "complete", "summary": "Sent the email."]))
      // A new task starts.
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "progress", "text": "opening the booking site"]))
      let status = log.statusText()
      return firstFailure([
        expectTrue(status.contains("AN EARLIER TASK, now finished"), "attributed"),
        expectTrue(status.contains("NOT to the one above"), "not the running one"),
        expectTrue(status.contains("Sent the email."), "carries the outcome"),
      ])
    },

    Check(name: "the outcome survives a buffer that has rolled past it") {
      // The reason lastOutcome is state and not a line: getSaiStatus is where the ask-first nudge
      // sends the model to find a held result, and any task running afterwards pushes it out of a
      // twelve-line scrollback. A model with nothing to find says the task stopped.
      let log = ActivityLog(maxLines: 3, now: { 0 })
      log.record(JsonObject(["type": "complete", "summary": "three newsletters"]))
      for i in 1...5 {
        log.record(JsonObject(["type": "progress", "text": "step \(i)"]))
      }
      let status = log.statusText()
      return firstFailure([
        expectTrue(status.contains("three newsletters"), "the outcome survived"),
        expectFalse(status.contains("finished: three newsletters"), "its LINE did roll away"),
      ])
    },

    Check(name: "a resolution clears the block without waiting for a session-state") {
      // Nothing guarantees a session-state follows a resolution. Until it does, the log kept blaming
      // the user for a wait that was over — the 2026-07-31 failure inverted.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "session-state", "blockedOn": "which flight?", "queued": []]))
      log.record(JsonObject(["type": "approval-resolved", "id": "a1", "status": "approved"]))
      let status = log.statusText()
      return firstFailure([
        expectFalse(status.contains("BLOCKED ON THE USER"), "block cleared"),
        expectTrue(status.contains("Still working"), "back to working"),
      ])
    },

    Check(name: "a block does not survive into the next task") {
      // A stale block makes statusText lead with a question about work nobody is doing any more.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "session-state", "blockedOn": "which flight?", "queued": []]))
      log.record(JsonObject(["type": "complete", "summary": "done"]))
      log.record(JsonObject(["type": "progress", "text": "a new task's first step"]))
      return expectFalse(log.statusText().contains("BLOCKED ON THE USER"), "cleared by begin()")
    },

    Check(name: "aborting after a finished task is not a new task") {
      // Treated as work starting, an abort arriving after the task finished cleared the end time and
      // zeroed the step count — so the log answered "Still working — 0 step(s) done so far" about a
      // cancellation.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "progress", "text": "searching"]))
      log.record(JsonObject(["type": "complete", "summary": "Found three."]))
      log.record(JsonObject(["type": "status", "status": "aborting"]))
      let status = log.statusText()
      return firstFailure([
        expectFalse(status.contains("Still working"), "not running"),
        expectTrue(status.contains("Finished after 1 step(s)."), "keeps the real step count"),
      ])
    },

    Check(name: "a complete with no summary records that, rather than nothing") {
      // "It finished and said nothing" is a real answer to "how did that go?", and the absence of one
      // is what gets filled in with a guess.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "complete"]))
      return expectTrue(
        log.statusText().contains("finished without saying what it found"),
        "records the absence")
    },

    Check(name: "session-state never enters the rolling buffer") {
      // It is the present; the buffer is the past. In the buffer it would scroll away.
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "session-state", "queued": ["a"]]))
      let status = log.statusText()
      return firstFailure([
        expectTrue(status.contains("No activity reported yet."), "buffer stayed empty"),
        expectTrue(status.contains("NOT STARTED YET"), "but the projection is reported"),
      ])
    },

    Check(name: "consecutive duplicate lines collapse") {
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "progress", "text": "same"]))
      log.record(JsonObject(["type": "progress", "text": "same"]))
      log.record(JsonObject(["type": "progress", "text": "different"]))
      let status = log.statusText()
      let occurrences = status.components(separatedBy: "same").count - 1
      return firstFailure([
        expectEqual(occurrences, 1, "the duplicate line collapsed"),
        // ...but the STEP count still counts every event: three progress events arrived, and only
        // the LINE was deduplicated. Collapsing the count too would under-report the work.
        expectTrue(status.contains("3 step(s)"), "all three steps still counted"),
      ])
    },

    Check(name: "msSinceTaskStart is nil until a task begins, then freezes at the end") {
      let clock = MutableClock()
      let log = ActivityLog(now: { clock.value })
      guard log.msSinceTaskStart() == nil else { return "should be nil before any task" }
      clock.value = 1_000
      log.record(JsonObject(["type": "status", "status": "processing"]))
      clock.value = 3_000
      guard log.msSinceTaskStart() == 2_000 else {
        return "running: expected 2000, got \(log.msSinceTaskStart().map(String.init) ?? "nil")"
      }
      clock.value = 4_000
      log.record(JsonObject(["type": "complete", "summary": "done"]))
      clock.value = 99_000
      return expectEqual(log.msSinceTaskStart(), 3_000, "frozen at endedAt − startedAt")
    },

    Check(name: "reset clears every piece of held state") {
      let log = ActivityLog(now: { 0 })
      log.record(JsonObject(["type": "status", "status": "processing"]))
      log.record(JsonObject(["type": "progress", "text": "step"]))
      log.record(JsonObject(["type": "session-state", "blockedOn": "q", "queued": ["a"]]))
      log.record(JsonObject(["type": "complete", "summary": "s"]))
      log.reset()
      return firstFailure([
        expectEqual(log.statusText(), "No activity reported yet.", "back to empty"),
        expectTrue(log.msSinceTaskStart() == nil, "timer cleared"),
      ])
    },
  ]
}
