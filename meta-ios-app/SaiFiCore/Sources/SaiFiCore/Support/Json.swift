/* sai-fi — voice concierge. */

// A thin `org.json`-shaped reader over Foundation's `[String: Any]`.
//
// WHY NOT Codable. Three reasons, all of which bit the Android port first:
//
//  1. The goldens are byte-pinned. `ConciergeProtocol` and `ActivityLog` render strings that JSON
//     fixtures assert byte for byte, and absent-vs-null-vs-empty are three different things in that
//     contract. Codable flattens the distinction at exactly the wrong moments.
//  2. `parseEffect` is a trust boundary over model output, not a decoder. It has to be tolerant in
//     some fields and strict in others (see Effects.swift) and return nil rather than throw. A
//     Codable conformance expresses "this shape or an error"; the boundary needs "this shape, or
//     drop it and carry on".
//  3. It keeps the Swift readable against the Kotlin side by side. Two implementations of the same
//     grammar will drift; a line-for-line diff is the cheapest thing that makes the drift visible.
//
// DIVERGENCE FROM org.json, deliberate. `JSONObject.optString(key, "")` calls `toString()` on ANY
// non-null value, so a `text` field holding an object stringifies into its own JSON and gets spoken
// aloud. Scalars are coerced here the same way Kotlin coerces them, because that is load-bearing for
// enum fields arriving as numbers; containers are rejected instead, because reading one out to a user
// is a bug the Kotlin happens to have rather than behaviour worth matching.

import Foundation

/// A read-only view over a decoded JSON object.
///
/// `@unchecked Sendable` because `JSONSerialization` hands back `Any` and there is no way to cast to
/// `any Sendable`. Everything it produces is an immutable `NSString`/`NSNumber`/`NSNull`/`NSArray`/
/// `NSDictionary`, this type never mutates `raw`, and nothing here hands the boxes out for writing —
/// so the guarantee holds by construction even though the compiler cannot see it.
public struct JsonObject: @unchecked Sendable {
  public let raw: [String: Any]

  public init(_ raw: [String: Any]) { self.raw = raw }

  /// Decode from bytes. Nil on anything that isn't a JSON object.
  public init?(data: Data) {
    guard
      let any = try? JSONSerialization.jsonObject(with: data),
      let dict = any as? [String: Any]
    else { return nil }
    self.raw = dict
  }

  public init?(string: String) {
    guard let data = string.data(using: .utf8) else { return nil }
    self.init(data: data)
  }

  public func has(_ key: String) -> Bool {
    guard let value = raw[key] else { return false }
    return !(value is NSNull)
  }

  /// The raw value, with JSON null flattened to nil.
  public func opt(_ key: String) -> Any? {
    guard let value = raw[key], !(value is NSNull) else { return nil }
    return value
  }

  /// `org.json`'s `optString(key, fallback)`, restricted to scalars — see the file header.
  public func optString(_ key: String, _ fallback: String = "") -> String {
    guard let value = opt(key) else { return fallback }
    return Self.coerceScalar(value) ?? fallback
  }

  /// Non-empty string, or nil. The TS `str()` guard — empty strings are rejected everywhere.
  public func str(_ key: String) -> String? {
    let value = optString(key, "")
    return value.isEmpty ? nil : value
  }

  public func optBool(_ key: String, _ fallback: Bool = false) -> Bool {
    guard let value = opt(key) else { return fallback }
    if let n = value as? NSNumber { return n.boolValue }
    if let s = value as? String { return s == "true" }
    return fallback
  }

  /// Nil when absent, so "not stated" and "stated false" stay distinguishable — several of the
  /// approval fields depend on that difference.
  public func optBoolOrNil(_ key: String) -> Bool? {
    guard let value = opt(key) else { return nil }
    if let n = value as? NSNumber { return n.boolValue }
    if let s = value as? String { return s == "true" ? true : (s == "false" ? false : nil) }
    return nil
  }

  public func optInt(_ key: String, _ fallback: Int = 0) -> Int {
    guard let value = opt(key) else { return fallback }
    if let n = value as? NSNumber { return n.intValue }
    if let s = value as? String, let parsed = Int(s) { return parsed }
    return fallback
  }

  public func optIntOrNil(_ key: String) -> Int? {
    guard let value = opt(key) else { return nil }
    if let n = value as? NSNumber { return n.intValue }
    if let s = value as? String { return Int(s) }
    return nil
  }

  public func optInt64OrNil(_ key: String) -> Int64? {
    guard let value = opt(key) else { return nil }
    if let n = value as? NSNumber { return n.int64Value }
    if let s = value as? String { return Int64(s) }
    return nil
  }

  public func optObject(_ key: String) -> JsonObject? {
    guard let dict = opt(key) as? [String: Any] else { return nil }
    return JsonObject(dict)
  }

  public func optArray(_ key: String) -> JsonArray? {
    guard let array = opt(key) as? [Any] else { return nil }
    return JsonArray(array)
  }

  /// True only for a real JSON `true`/`false`.
  ///
  /// `NSNumber as? Bool` succeeds for the integers 1 and 0 as well, because Foundation bridges them
  /// — so an `as? Bool` test ahead of `as? NSNumber` turns the number 1 into the string "true". That
  /// is not hypothetical: it is what `matchQueued`'s index fixtures caught the first time this gate
  /// ran, with the index 1 rendering as "true". `CFBooleanGetTypeID` is the only reliable
  /// discriminator.
  static func isRealBool(_ value: Any) -> Bool {
    CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
  }

  /// Scalars only. Containers return nil — see the DIVERGENCE note in the file header.
  static func coerceScalar(_ value: Any) -> String? {
    if let s = value as? String { return s }
    if isRealBool(value) { return (value as? Bool) == true ? "true" : "false" }
    if let n = value as? NSNumber {
      // Match Kotlin's Integer/Double `toString()`: an integral value has no ".0" tail, a
      // fractional one keeps it. This only matters for enum fields arriving as numbers, which then
      // fail their `fromWire` and take the documented default.
      if CFNumberIsFloatType(n) {
        return n.doubleValue == n.doubleValue.rounded() && abs(n.doubleValue) < 1e15
          ? String(format: "%.1f", n.doubleValue)
          : "\(n.doubleValue)"
      }
      return "\(n.int64Value)"
    }
    return nil
  }
}

/// A read-only view over a decoded JSON array. `@unchecked Sendable` for the same reason as
/// `JsonObject` — see the note there.
public struct JsonArray: @unchecked Sendable {
  public let raw: [Any]

  public init(_ raw: [Any]) { self.raw = raw }

  public init?(data: Data) {
    guard
      let any = try? JSONSerialization.jsonObject(with: data),
      let array = any as? [Any]
    else { return nil }
    self.raw = array
  }

  public init?(string: String) {
    guard let data = string.data(using: .utf8) else { return nil }
    self.init(data: data)
  }

  public var count: Int { raw.count }

  public func opt(_ index: Int) -> Any? {
    guard index >= 0, index < raw.count else { return nil }
    let value = raw[index]
    return value is NSNull ? nil : value
  }

  /// Strictly a string — no coercion. `chooseOption` casts this way on the Kotlin side too, and it
  /// is the strict half of the parse boundary on purpose.
  public func optStringStrict(_ index: Int) -> String? {
    opt(index) as? String
  }

  /// `org.json`'s coercing `optString(index)`, for the places the Kotlin uses it — the `queued` list
  /// in a `session-state` event, read by both `renderAgentActivity` and `ActivityLog`. Kept separate
  /// from `optStringStrict` so the strict half of the parse boundary cannot pick this up by accident.
  public func optString(_ index: Int, _ fallback: String = "") -> String {
    guard let value = opt(index) else { return fallback }
    return JsonObject.coerceScalar(value) ?? fallback
  }

  public func optObject(_ index: Int) -> JsonObject? {
    guard let dict = opt(index) as? [String: Any] else { return nil }
    return JsonObject(dict)
  }

  public func objects() -> [JsonObject] {
    (0..<count).compactMap { optObject($0) }
  }

  public func strings() -> [String] {
    (0..<count).compactMap { optStringStrict($0) }
  }
}
