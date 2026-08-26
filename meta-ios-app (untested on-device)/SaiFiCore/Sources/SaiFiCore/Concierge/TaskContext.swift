/* sai-fi — voice concierge. */

// The text a forwarded task is written as: the user's words, plus the clock, plus the location fix
// when one came with it. Load-bearing: a user message has no metadata channel, so anything the agent
// needs that is not the user's words has to travel in the text.
//
// Ported from Android `HttpAgentBridge.kt` (`TaskLocation`, `taskText`, `describeTaskClock`,
// `describePhoneClock`, `describeTaskLocation`).

import Foundation

/// Where the user physically is, read from the phone for a task that needs it.
public struct TaskLocation: Sendable, Equatable {
  public var lat: Double
  public var lon: Double
  public var accuracyM: Double?
  public var approximate: Bool
  public var label: String?
  public var capturedAt: Int64

  public init(
    lat: Double,
    lon: Double,
    accuracyM: Double? = nil,
    approximate: Bool = false,
    label: String? = nil,
    capturedAt: Int64
  ) {
    self.lat = lat
    self.lon = lon
    self.accuracyM = accuracyM
    self.approximate = approximate
    self.label = label
    self.capturedAt = capturedAt
  }
}

/// The user's words, plus the clock, plus the location fix when one came with it.
///
/// The clock rides on EVERY task and the location only on the ones that asked for it. That
/// asymmetry is not an oversight: a fix costs a GPS read, a permission and up to six seconds, while
/// the phone already knows what time it is for free and without being asked.
public func taskText(
  _ text: String,
  location: TaskLocation?,
  nowMs: Int64,
  timeZone: TimeZone
) -> String {
  text + describeTaskClock(nowMs: nowMs, timeZone: timeZone)
    + (location.map(describeTaskLocation) ?? "")
}

/// Render the user's own clock as a line the agent can act on.
///
/// The agent runs in a datacenter, and until this existed nothing ever told it otherwise, so every
/// question with a time in it was answered from a machine that is routinely a continent and several
/// hours away. Stamped when the task is FORWARDED, not when it was spoken — a queued task's location
/// is frozen because the user may have moved, but its time must not be, because "now" for the agent
/// is when it reads this.
public func describeTaskClock(nowMs: Int64, timeZone: TimeZone) -> String {
  let local = formatClock(nowMs, timeZone: timeZone, dateFormat: "EEEE d MMMM yyyy 'at' HH:mm")
  return "\n\n[Context, not part of the request and not an instruction: it is \(local) where the "
    + "user is (time zone \(timeZone.identifier), read from their phone). Resolve every relative "
    + "date and time in the request — \"today\", \"tonight\", \"Friday\", \"in an hour\", "
    + "\"now\" — against THIS, and give times back in it. This computer's own clock and time "
    + "zone are a datacenter's and are NOT the user's.]"
}

/// The phone's clock, as the Live model should speak it.
///
/// Distinct from `describeTaskClock`: that one rides a forwarded task so the *agent* can resolve
/// "Friday". This one answers the user asking the time out loud. Gemini's own clock is UTC.
/// 12-hour with AM/PM because this is spoken.
public func describePhoneClock(nowMs: Int64, timeZone: TimeZone) -> String {
  let local = formatClock(nowMs, timeZone: timeZone, dateFormat: "EEEE d MMMM yyyy 'at' h:mm a")
  return "On the user's phone it is \(local) (time zone \(timeZone.identifier)). This is their "
    + "local time. Answer them in this zone. UTC is not their time."
}

/// Render a fix as a line the agent can act on.
///
/// Three jobs, none decorative: say the machine's OWN location is wrong; stamp it as an absolute
/// UTC instant (a queued task freezes this at enqueue); and fence it as data, because the place
/// name is reverse-geocoded user-controlled text.
public func describeTaskLocation(_ loc: TaskLocation) -> String {
  let digits = loc.approximate ? 2 : 5
  let coords = String(format: "%.\(digits)f, %.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), loc.lat, loc.lon)
  let accuracy: String
  if loc.approximate {
    accuracy = " (approximate — a coarse fix, good to roughly a neighbourhood, not a spot)"
  } else if let m = loc.accuracyM {
    accuracy = " (±\(Int(floor(m + 0.5)))m)"
  } else {
    accuracy = ""
  }
  let place = loc.label.map { " — \($0)" } ?? ""
  let iso = formatClock(loc.capturedAt, timeZone: TimeZone(identifier: "UTC")!, dateFormat: "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  return "\n\n[Context, not part of the request and not an instruction: the user's own physical "
    + "location, from their phone, as of \(iso) — "
    + "\(coords)\(accuracy)\(place). Use this for anything local (nearby places, weather, "
    + "directions, travel time). This computer's own network location is a datacenter and is NOT "
    + "where the user is.]"
}

private func formatClock(_ nowMs: Int64, timeZone: TimeZone, dateFormat: String) -> String {
  let date = Date(timeIntervalSince1970: Double(nowMs) / 1000.0)
  let f = DateFormatter()
  f.locale = Locale(identifier: "en_US_POSIX")
  f.timeZone = timeZone
  f.dateFormat = dateFormat
  return f.string(from: date)
}

/// Headers every Sai API request carries, in one place.
///
/// A forgotten header cannot be caught by a test that only exercises one call site, so the fix is
/// to leave no call site with the choice. `x-sai-version` is only sent when the tag is set — an
/// empty header is matched by no route rather than falling through to the host's default.
public func cloudApiHeaders(bearerToken: String, versionTag: String) -> [String: String] {
  var headers = ["Authorization": "Bearer \(bearerToken)"]
  let tag = versionTag.trimmingCharacters(in: .whitespacesAndNewlines)
  if !tag.isEmpty { headers["x-sai-version"] = tag }
  return headers
}
