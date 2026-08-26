/* sai-fi — voice concierge. */

// The three conversions between the FSM's typed world and the JSON one around it.
//
// ActivityLog, ConciergeProtocol and AgentEventRouter all read raw JsonObject, and they stay that
// way ON PURPOSE: they are pinned against the server by the parity fixtures, so changing their input
// shape to suit the FSM would break the one check that keeps the two renderings honest. Converting
// at the boundary is the cheaper side of that trade.
//
// Ported from Android `VoiceConverters.kt`.

import Foundation

/// A typed agent event back to the wire shape the log and the nudge router expect.
public func agentEventToJson(_ e: AgentEvent) -> JsonObject {
  switch e {
  case .text(let text):
    return JsonObject(["type": "text", "text": text])
  case .progress(let text, let tool, let failed):
    var raw: [String: Any] = ["type": "progress", "text": text]
    if let tool { raw["tool"] = tool }
    if failed { raw["failed"] = true }
    return asJson(raw)
  case .status(let status):
    return JsonObject(["type": "status", "status": status.rawValue])
  case .complete(let summary):
    var raw: [String: Any] = ["type": "complete"]
    if let summary { raw["summary"] = summary }
    return asJson(raw)
  case .error(let text):
    return JsonObject(["type": "error", "text": text])
  case .notice(let text, let kind):
    var raw: [String: Any] = ["type": "notice", "text": text]
    if let kind { raw["kind"] = kind }
    return asJson(raw)
  case .approvalRequest(let e):
    var raw: [String: Any] = [
      "type": "approval-request",
      "id": e.id,
      "title": e.title,
      "description": e.description,
      "approvalType": e.approvalType,
      "isLinkOnly": e.isLinkOnly,
    ]
    if let opts = e.options {
      raw["options"] = opts.map { ["value": $0.value, "label": $0.label] }
    }
    if let multiple = e.multiple { raw["multiple"] = multiple }
    if let allowOther = e.allowOther { raw["allowOther"] = allowOther }
    return asJson(raw)
  case .approvalResolved(let id, let status):
    return JsonObject(["type": "approval-resolved", "id": id, "status": status])
  case .sessionState(let running, let blockedOn, let queued):
    var raw: [String: Any] = ["type": "session-state", "queued": queued]
    if let running { raw["running"] = running }
    if let blockedOn { raw["blockedOn"] = blockedOn }
    return asJson(raw)
  }
}

/// Round-trip through JSONSerialization so Swift `Bool` becomes a real JSON boolean (CFBoolean),
/// matching what the wire and `optBool` expect.
private func asJson(_ raw: [String: Any]) -> JsonObject {
  guard
    let data = try? JSONSerialization.data(withJSONObject: raw),
    let o = JsonObject(data: data)
  else { return JsonObject(raw) }
  return o
}

/// One fix, in the shape the server's `TaskLocation` expects.
///
/// `capturedAt` is the moment the FIX was taken, not the moment we sent it. `approximate` means a
/// coarse grant, so this is a neighbourhood rather than a spot.
public struct Place: Sendable, Equatable {
  public var lat: Double
  public var lon: Double
  public var accuracyM: Float?
  public var label: String?
  public var approximate: Bool
  public var capturedAt: Int64

  public init(
    lat: Double,
    lon: Double,
    accuracyM: Float? = nil,
    label: String? = nil,
    approximate: Bool = false,
    capturedAt: Int64
  ) {
    self.lat = lat
    self.lon = lon
    self.accuracyM = accuracyM
    self.label = label
    self.approximate = approximate
    self.capturedAt = capturedAt
  }

  /// A phone fix as the FSM's location type.
  ///
  /// Carries the Place's OWN `capturedAt` rather than stamping now: a queued task freezes its
  /// location line at enqueue and may run much later, so the instant has to be when the fix was
  /// actually taken, not when it happened to be forwarded.
  public func toTaskLocation() -> TaskLocation {
    TaskLocation(
      lat: lat,
      lon: lon,
      accuracyM: accuracyM.map(Double.init),
      approximate: approximate,
      label: label,
      capturedAt: capturedAt)
  }
}

/// An uploaded attachment as the FSM's task attachment.
public func taskAttachment(from json: JsonObject) -> TaskAttachment {
  TaskAttachment(
    path: json.optString("path"),
    name: json.optString("name"),
    mime: json.optString("mime"),
    size: json.optInt64OrNil("size") ?? 0,
    downloadUrl: json.str("downloadUrl"),
    fileId: json.str("fileId"),
    width: json.optInt("width", 0).takeIfPositive,
    height: json.optInt("height", 0).takeIfPositive)
}

private extension Int {
  var takeIfPositive: Int? { self > 0 ? self : nil }
}
