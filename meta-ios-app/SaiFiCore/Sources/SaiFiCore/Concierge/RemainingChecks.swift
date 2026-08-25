/* sai-fi — voice concierge. */

// Checks for the remaining I/O-free pieces: AgentEventRouter, VoiceConverters, the task-context
// lines, CallNotificationText, SaiTab, GlassesLink, CloudApiHeaders.
//
// Ported from the Android `*Test.kt` files of the same names.

import Foundation
import os

func remainingPureChecks() -> [Check] {
  agentEventRouterChecks()
    + voiceConverterChecks()
    + taskContextChecks()
    + callNotificationTextChecks()
    + saiTabChecks()
    + glassesLinkChecks()
    + cloudApiHeaderChecks()
}

/// Round-trip through JSONSerialization so Swift `true` becomes a real JSON boolean.
private func wire(_ raw: [String: Any]) -> JsonObject {
  let data = try! JSONSerialization.data(withJSONObject: raw)
  return JsonObject(data: data)!
}

// ── AgentEventRouter ─────────────────────────────────────────────────────────

private let routerThreshold: Int64 = 15_000
private let routerThrottle: Int64 = 30_000

private func routerComplete(_ summary: String = "3 unread, all newsletters") -> JsonObject {
  wire(["type": "complete", "summary": summary])
}

private func routerProgress(failed: Bool) -> JsonObject {
  wire(["type": "progress", "text": "opening the site", "failed": failed])
}

private func routerRoute(
  _ event: JsonObject,
  muted: Bool = false,
  userQuietMs: Int64 = 0,
  sinceLastStepFailureMs: Int64 = Int64.max
) -> NudgeAction {
  AgentEventRouter.route(
    event: event,
    muted: muted,
    userQuietMs: userQuietMs,
    askFirstThresholdMs: routerThreshold,
    sinceLastStepFailureMs: sinceLastStepFailureMs,
    stepFailureIntervalMs: routerThrottle)
}

private func agentEventRouterChecks() -> [Check] {
  [
    Check(name: "a completion reaches a user who is present") {
      let action = routerRoute(routerComplete(), userQuietMs: 2_000)
      guard case .inject(let kind, _) = action else { return "expected inject, got \(action)" }
      return expectEqual(kind, "complete", "kind")
    },
    Check(name: "a completion is offered rather than announced when the user has gone quiet") {
      let action = routerRoute(routerComplete(), userQuietMs: routerThreshold + 1)
      guard case .inject(let kind, _) = action else { return "expected inject, got \(action)" }
      return expectTrue(kind.contains("ask-first"), kind)
    },
    Check(name: "a user who never spoke this call counts as quiet") {
      let action = routerRoute(routerComplete(), userQuietMs: Int64.max)
      guard case .inject(let kind, _) = action else { return "expected inject, got \(action)" }
      return expectTrue(kind.contains("never spoke"), kind)
    },
    Check(name: "a completion while muted is held, not spoken into a room Sai was told to be quiet in") {
      let action = routerRoute(routerComplete(), muted: true)
      guard case .hold(let kind, _) = action else { return "expected hold, got \(action)" }
      return expectEqual(kind, "complete", "kind is the event type")
    },
    Check(name: "stale-by-nature events are dropped while muted rather than held") {
      let notice = routerRoute(wire(["type": "notice", "text": "waking the machine"]), muted: true)
      let step = routerRoute(routerProgress(failed: true), muted: true)
      guard case .drop(let noticeWhy) = notice else { return "expected drop notice, got \(notice)" }
      guard case .drop(let stepWhy) = step else { return "expected drop step, got \(step)" }
      return firstFailure([
        expectTrue(noticeWhy.contains("stale"), "notice"),
        expectTrue(stepWhy.contains("stale"), "step-failed"),
      ])
    },
    Check(name: "ordinary progress is not something to react to") {
      expectEqual(routerRoute(routerProgress(failed: false)), .ignore, "ignore")
    },
    Check(name: "the first failed step goes out") {
      let action = routerRoute(routerProgress(failed: true))
      guard case .injectStepFailure(let nudge) = action else {
        return "expected injectStepFailure, got \(action)"
      }
      return expectFalse(nudge.isEmpty, "it has to carry the fact that there is no result yet")
    },
    Check(name: "a second failed step within the window is dropped, and says how long ago") {
      let action = routerRoute(routerProgress(failed: true), sinceLastStepFailureMs: 5_000)
      guard case .drop(let why) = action else { return "expected drop, got \(action)" }
      return firstFailure([
        expectTrue(why.contains("throttled"), "throttled"),
        expectTrue(why.contains("5s ago"), "how long ago"),
      ])
    },
    Check(name: "a failed step after the window goes out again") {
      let action = routerRoute(routerProgress(failed: true), sinceLastStepFailureMs: routerThrottle + 1)
      if case .injectStepFailure = action { return nil }
      return "expected injectStepFailure, got \(action)"
    },
    Check(name: "a notice reaches an audible user") {
      let action = routerRoute(wire(["type": "notice", "text": "waking the machine"]))
      guard case .inject(let kind, _) = action else { return "expected inject, got \(action)" }
      return expectEqual(kind, "notice", "kind")
    },
    Check(name: "an internal event produces nothing") {
      expectEqual(
        routerRoute(wire(["type": "status", "status": "processing"])), .ignore, "status is internal")
    },
    Check(name: "a user waiting on a task is not a user who has gone away") {
      let spoke: Int64 = 100_000
      return expectEqual(userQuietMs(now: spoke + 40_000, lastUserSpeechAt: spoke, workStartedAt: spoke + 500), Int64(500), "half a second")
    },
    Check(name: "a user who spoke and then went silent with nothing running is measured to now") {
      let spoke: Int64 = 100_000
      return expectEqual(userQuietMs(now: spoke + 40_000, lastUserSpeechAt: spoke, workStartedAt: 0), Int64(40_000), "to now")
    },
    Check(name: "work that began before they last spoke is stale and does not stop the clock") {
      let spoke: Int64 = 100_000
      return expectEqual(
        userQuietMs(now: spoke + 40_000, lastUserSpeechAt: spoke, workStartedAt: spoke - 5_000),
        Int64(40_000),
        "speech is the newer fact")
    },
    Check(name: "speaking while work is already running restarts the wait at zero") {
      let spoke: Int64 = 100_000
      return expectEqual(userQuietMs(now: spoke + 40_000, lastUserSpeechAt: spoke, workStartedAt: spoke), Int64(0), "present")
    },
    Check(name: "a user who never spoke this call is maximally quiet, whatever is running") {
      let spoke: Int64 = 100_000
      return firstFailure([
        expectEqual(userQuietMs(now: spoke, lastUserSpeechAt: 0, workStartedAt: 0), Int64.max, "idle"),
        expectEqual(
          userQuietMs(now: spoke, lastUserSpeechAt: 0, workStartedAt: spoke - 1_000), Int64.max,
          "with work"),
      ])
    },
    Check(name: "an unbroken run of work since they last spoke stays one wait, however long") {
      let spoke: Int64 = 100_000
      return expectEqual(
        userQuietMs(now: spoke + 600_000, lastUserSpeechAt: spoke, workStartedAt: spoke + 500),
        Int64(500),
        "the stamp does not advance to a later task")
    },
  ]
}

// ── VoiceConverters ──────────────────────────────────────────────────────────

private func voiceConverterChecks() -> [Check] {
  [
    Check(name: "agentEventToJson round-trips a failed progress into the router") {
      let json = agentEventToJson(.progress(text: "opening the site", failed: true))
      return firstFailure([
        expectEqual(json.optString("type"), "progress", "type"),
        expectTrue(json.optBool("failed", false), "failed"),
      ])
    },
    Check(name: "agentEventToJson omits failed when the step did not fail") {
      let json = agentEventToJson(.progress(text: "opening the site"))
      return expectFalse(json.has("failed"), "failed is only present when true")
    },
    Check(name: "agentEventToJson writes agent status on the wire") {
      expectEqual(agentEventToJson(.status(.processing)).optString("status"), "processing", "wire")
    },
    Check(name: "a phone fix keeps the capturedAt it was taken at") {
      let place = Place(lat: 1.3, lon: 103.8, accuracyM: 12, label: "Orchard", capturedAt: 99)
      let loc = place.toTaskLocation()
      return firstFailure([
        expectEqual(loc.capturedAt, Int64(99), "not stamped now"),
        expectEqual(loc.accuracyM ?? -1, 12.0, "accuracy"),
        expectEqual(loc.label ?? "", "Orchard", "label"),
      ])
    },
    Check(name: "toTaskAttachment drops empty optional urls and zero dimensions") {
      let a = taskAttachment(
        from: wire([
          "path": "uploads/a.jpg", "name": "a.jpg", "mime": "image/jpeg", "size": 10,
          "downloadUrl": "", "width": 0,
        ]))
      return firstFailure([
        expectEqual(a.name, "a.jpg", "name"),
        expectTrue(a.downloadUrl == nil, "empty url is absent"),
        expectTrue(a.width == nil, "zero width is absent"),
        expectEqual(a.size, Int64(10), "size"),
      ])
    },
  ]
}

// ── task context (clock + location) ──────────────────────────────────────────

private func taskContextChecks() -> [Check] {
  let instant: Int64 = 1_787_248_800_000  // 2026-08-20T18:00:00Z
  return [
    Check(name: "the clock is the user's, so a relative date cannot resolve against the datacenter's day") {
      let singapore = describeTaskClock(
        nowMs: instant, timeZone: TimeZone(identifier: "Asia/Singapore")!)
      let california = describeTaskClock(
        nowMs: instant, timeZone: TimeZone(identifier: "America/Los_Angeles")!)
      return firstFailure([
        expectTrue(singapore.contains("Friday 21 August 2026 at 02:00"), "Singapore next day"),
        expectTrue(singapore.contains("Asia/Singapore"), "the zone has to travel too"),
        expectTrue(california.contains("Thursday 20 August 2026 at 11:00"), "California same day"),
      ])
    },
    Check(name: "the spoken clock is the phone's, never UTC") {
      let spoken = describePhoneClock(
        nowMs: instant, timeZone: TimeZone(identifier: "Asia/Singapore")!)
      return firstFailure([
        expectTrue(spoken.contains("Friday 21 August 2026 at 2:00 AM"), "12-hour"),
        expectTrue(spoken.contains("Asia/Singapore"), "zone"),
        expectTrue(spoken.contains("UTC is not their time"), "UTC must be named as the wrong clock"),
        expectFalse(spoken.contains("18:00"), "24-hour UTC must not be what it speaks"),
      ])
    },
    Check(name: "every forwarded task carries the clock, location or no location") {
      let sent = taskText(
        "book a table for Friday",
        location: nil,
        nowMs: instant,
        timeZone: TimeZone(identifier: "Asia/Singapore")!)
      return firstFailure([
        expectTrue(sent.hasPrefix("book a table for Friday"), "the user's words must still lead"),
        expectTrue(sent.contains("where the user is (time zone "), "clock reached the agent"),
      ])
    },
    Check(name: "a coarse fix is rounded to a neighbourhood and fenced as data") {
      let loc = TaskLocation(
        lat: 1.304567, lon: 103.831234, approximate: true, label: "Orchard", capturedAt: instant)
      let line = describeTaskLocation(loc)
      return firstFailure([
        expectTrue(line.contains("1.30, 103.83"), "two decimals"),
        expectTrue(line.contains("approximate"), "labelled coarse"),
        expectTrue(line.contains("Orchard"), "place name"),
        expectTrue(line.contains("[Context, not part of the request"), "fenced"),
        expectTrue(line.contains("2026-08-20T18:00:00.000Z"), "absolute UTC instant"),
      ])
    },
  ]
}

// ── CallNotificationText ─────────────────────────────────────────────────────

private func callNotificationTextChecks() -> [Check] {
  [
    Check(name: "a muted call never claims to be listening in the title") {
      let title = CallNotificationText.title(muted: true, paused: false)
      return firstFailure([
        expectTrue(title.contains("muted"), "muted title should say muted, was: \(title)"),
        expectEqual(
          CallNotificationText.title(muted: false, paused: false), "Sai is listening", "live"),
      ])
    },
    Check(name: "a muted call says it still listens but will not speak") {
      let body = CallNotificationText.body(muted: true, paused: false, machineLabel: "Main VM")
      return firstFailure([
        expectTrue(body.contains("still listening"), "still listening"),
        expectTrue(body.contains("won't speak"), "won't speak"),
      ])
    },
    Check(name: "pause dominates mute, and warns that a long pause ends the call") {
      for muted in [true, false] {
        let title = CallNotificationText.title(muted: muted, paused: true)
        let body = CallNotificationText.body(muted: muted, paused: true, machineLabel: "Main VM")
        if let fail = firstFailure([
          expectEqual(title, "Sai is paused", "title"),
          expectTrue(body.contains("can't hear you"), "body"),
          expectTrue(body.contains("ends the call"), "idle guard"),
          expectEqual(
            CallNotificationText.secondaryAction(muted: muted, paused: true), "Resume", "action"),
        ]) { return fail }
      }
      return nil
    },
    Check(name: "the secondary action names what it will do") {
      firstFailure([
        expectEqual(
          CallNotificationText.secondaryAction(muted: false, paused: false), "Mute", "mute"),
        expectEqual(
          CallNotificationText.secondaryAction(muted: true, paused: false), "Unmute", "unmute"),
      ])
    },
    Check(name: "a live call names the machine it is working on") {
      let body = CallNotificationText.body(muted: false, paused: false, machineLabel: "Main VM")
      return expectTrue(body.contains("Main VM"), "machine")
    },
  ]
}

// ── SaiTab ───────────────────────────────────────────────────────────────────

private func saiTabChecks() -> [Check] {
  [
    Check(name: "logs is hidden unless developer mode is on") {
      firstFailure([
        expectEqual(tabsFor(devMode: false), [SaiTab.home, .settings], "off"),
        expectEqual(tabsFor(devMode: true), [SaiTab.home, .settings, .logs], "on"),
      ])
    },
    Check(name: "home is always first, so the bar never opens on a secondary destination") {
      firstFailure([
        expectEqual(tabsFor(devMode: false).first ?? .logs, SaiTab.home, "off"),
        expectEqual(tabsFor(devMode: true).first ?? .logs, SaiTab.home, "on"),
      ])
    },
    Check(name: "turning developer mode off while on logs falls back to home") {
      expectEqual(coerceTab(.logs, devMode: false), SaiTab.home, "fallback")
    },
    Check(name: "a visible tab is left alone") {
      firstFailure([
        expectEqual(coerceTab(.logs, devMode: true), SaiTab.logs, "logs on"),
        expectEqual(coerceTab(.settings, devMode: false), SaiTab.settings, "settings off"),
        expectEqual(coerceTab(.settings, devMode: true), SaiTab.settings, "settings on"),
        expectEqual(coerceTab(.home, devMode: false), SaiTab.home, "home"),
      ])
    },
    Check(name: "every tab has a distinct label, so the bar has no two identical items") {
      let labels = SaiTab.allCases.map(\.label)
      return expectEqual(labels.count, Set(labels).count, "distinct")
    },
  ]
}

// ── GlassesLink ──────────────────────────────────────────────────────────────

private func glassesLinkChecks() -> [Check] {
  [
    Check(name: "nothing reported yet is unknown, not disconnected") {
      let link = GlassesLink()
      return firstFailure([
        expectTrue(link.onNothingReported() == nil, "unknown"),
        expectFalse(link.hasAnswered, "nothing has been established yet"),
      ])
    },
    Check(name: "silence becomes an affirmative no once the settle window passes") {
      let link = GlassesLink()
      _ = link.onNothingReported()
      return firstFailure([
        expectFalse(link.onSettleElapsed(), "absence"),
        expectTrue(link.hasAnswered, "answered"),
      ])
    },
    Check(name: "a readable device is an answer immediately, in either state") {
      firstFailure([
        expectTrue(GlassesLink().onLinkState(true), "connected"),
        expectFalse(GlassesLink().onLinkState(false), "disconnected"),
        expectTrue(
          {
            let l = GlassesLink()
            _ = l.onLinkState(false)
            return l.hasAnswered
          }(),
          "has answered"),
      ])
    },
    Check(name: "once DAT has spoken, a device going away is reported at once") {
      let link = GlassesLink()
      _ = link.onLinkState(true)
      return expectTrue(link.onNothingReported() == false, "absence is now meaningful")
    },
    Check(name: "a concluded absence stays concluded") {
      let link = GlassesLink()
      _ = link.onNothingReported()
      _ = link.onSettleElapsed()
      return expectTrue(link.onNothingReported() == false, "no flicker back to checking")
    },
    Check(name: "the link can recover after being concluded absent") {
      let link = GlassesLink()
      _ = link.onNothingReported()
      _ = link.onSettleElapsed()
      return firstFailure([
        expectTrue(link.onLinkState(true), "glasses powered on later"),
        expectTrue(link.onNothingReported() == false, "and folded again"),
      ])
    },
    Check(name: "the settle window is generous, because the two errors are not symmetric") {
      firstFailure([
        expectEqual(GlassesLink.settleMs, Int64(5_000), "5s"),
        expectTrue(GlassesLink.settleMs >= 3_000, "not tight"),
      ])
    },
    Check(name: "a seeded-empty stream stays null until the settle window, not false on subscribe") {
      await glassesObserveSeededEmpty()
    },
    Check(name: "a device mid-settle cancels the wait and does not get overwritten by a late false") {
      await glassesObserveMidSettle()
    },
    Check(name: "once answered, an empty reading publishes false with no second settle wait") {
      await glassesObserveAlreadyAnswered()
    },
  ]
}

private final class VirtualClock: @unchecked Sendable {
  private struct State: @unchecked Sendable {
    var now: Int64 = 0
    var waiters: [UUID: (deadline: Int64, resume: @Sendable () -> Void)] = [:]
  }
  private let lock = OSAllocatedUnfairLock(initialState: State())

  func sleep(_ ms: Int64) async {
    let id = UUID()
    await withTaskCancellationHandler {
      await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
        let immediate = lock.withLock { state -> Bool in
          let deadline = state.now + ms
          if deadline <= state.now { return true }
          state.waiters[id] = (deadline, { @Sendable in cont.resume() })
          return false
        }
        if immediate { cont.resume() }
      }
    } onCancel: {
      let resume: (@Sendable () -> Void)? = lock.withLock { state in
        state.waiters.removeValue(forKey: id)?.resume
      }
      resume?()
    }
  }

  func advance(_ ms: Int64) {
    let ready: [@Sendable () -> Void] = lock.withLock { state in
      state.now += ms
      let now = state.now
      var out: [@Sendable () -> Void] = []
      for (id, waiter) in state.waiters where waiter.deadline <= now {
        out.append(waiter.resume)
        state.waiters.removeValue(forKey: id)
      }
      return out
    }
    ready.forEach { $0() }
  }
}

private func waitUntil(_ pred: @escaping () -> Bool, tries: Int = 400) async -> Bool {
  for _ in 0..<tries {
    if pred() { return true }
    try? await Task.sleep(nanoseconds: 1_000_000)
  }
  return pred()
}

private func glassesObserveSeededEmpty() async -> String? {
  let clock = VirtualClock()
  let (stream, cont) = AsyncStream<Bool?>.makeStream()
  let published = OSAllocatedUnfairLock(initialState: [Bool?]())
  let link = GlassesLink()
  let task = Task {
    await link.observe(stream, sleep: { await clock.sleep($0) }) { v in
      published.withLock { $0.append(v) }
    }
  }
  cont.yield(nil)
  guard await waitUntil({ published.withLock { $0 } == [nil] }) else {
    return "seeded empty must publish null, not false — got \(published.withLock { $0 })"
  }
  clock.advance(GlassesLink.settleMs - 1)
  try? await Task.sleep(nanoseconds: 2_000_000)
  if published.withLock({ $0 }) != [nil] {
    return "still unknown one tick before settle, got \(published.withLock { $0 })"
  }
  clock.advance(1)
  guard await waitUntil({ published.withLock { $0 } == [nil, false] }) else {
    return "only now is absence an answer, got \(published.withLock { $0 })"
  }
  cont.finish()
  await task.value
  return nil
}

private func glassesObserveMidSettle() async -> String? {
  let clock = VirtualClock()
  let (stream, cont) = AsyncStream<Bool?>.makeStream()
  let published = OSAllocatedUnfairLock(initialState: [Bool?]())
  let link = GlassesLink()
  let task = Task {
    await link.observe(stream, sleep: { await clock.sleep($0) }) { v in
      published.withLock { $0.append(v) }
    }
  }
  cont.yield(nil)
  guard await waitUntil({ published.withLock { $0.count } == 1 }) else {
    return "expected the seed, got \(published.withLock { $0 })"
  }
  cont.yield(true)
  guard await waitUntil({ published.withLock { $0 } == [nil, true] }) else {
    return "device mid-settle, got \(published.withLock { $0 })"
  }
  clock.advance(GlassesLink.settleMs * 2)
  try? await Task.sleep(nanoseconds: 5_000_000)
  let got = published.withLock { $0 }
  cont.finish()
  await task.value
  return expectEqual(got, [nil, true], "settle was cancelled — a late false must not overwrite connected")
}

private func glassesObserveAlreadyAnswered() async -> String? {
  let clock = VirtualClock()
  let (stream, cont) = AsyncStream<Bool?>.makeStream()
  let published = OSAllocatedUnfairLock(initialState: [Bool?]())
  let link = GlassesLink()
  let task = Task {
    await link.observe(stream, sleep: { await clock.sleep($0) }) { v in
      published.withLock { $0.append(v) }
    }
  }
  cont.yield(true)
  guard await waitUntil({ published.withLock { $0 } == [true] }) else {
    return "expected connected, got \(published.withLock { $0 })"
  }
  cont.yield(nil)
  guard await waitUntil({ published.withLock { $0 } == [true, false] }) else {
    return "empty after answer must be false immediately, got \(published.withLock { $0 })"
  }
  clock.advance(GlassesLink.settleMs * 2)
  try? await Task.sleep(nanoseconds: 5_000_000)
  let got = published.withLock { $0 }
  cont.finish()
  await task.value
  return expectEqual(got, [true, false], "already answered — no settle delay, no extra publish")
}

// ── CloudApiHeaders ──────────────────────────────────────────────────────────

private func cloudApiHeaderChecks() -> [Check] {
  [
    Check(name: "every request carries the bearer token") {
      expectEqual(
        cloudApiHeaders(bearerToken: "tok", versionTag: "")["Authorization"],
        "Bearer tok",
        "Authorization")
    },
    Check(name: "x-sai-version is omitted when the tag is empty, not sent blank") {
      let headers = cloudApiHeaders(bearerToken: "tok", versionTag: "  ")
      return expectFalse(headers.keys.contains("x-sai-version"), "empty header matches no route")
    },
    Check(name: "x-sai-version is sent when the tag is set") {
      expectEqual(
        cloudApiHeaders(bearerToken: "tok", versionTag: "abc123")["x-sai-version"],
        "abc123",
        "pin")
    },
  ]
}
