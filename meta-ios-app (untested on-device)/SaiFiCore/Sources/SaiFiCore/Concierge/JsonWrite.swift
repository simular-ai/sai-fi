/* sai-fi — voice concierge. */

// JSONSerialization writers for the agent HTTP layer.
//
// Codable is forbidden here: absent-vs-null is load-bearing, and Kotlin only writes `"failed": true`
// when the value is true — omitting the key otherwise. Round-tripping through JSONSerialization is
// what makes a Swift `Bool` a real JSON boolean (CFBoolean), matching `JsonObject.optBool`.

import Foundation

func jsonWire(_ raw: [String: Any]) -> JsonObject {
  let data = try! JSONSerialization.data(withJSONObject: raw)
  return JsonObject(data: data)!
}

func jsonArrayWire(_ raw: [Any]) -> JsonArray {
  let data = try! JSONSerialization.data(withJSONObject: raw)
  return JsonArray(data: data)!
}

func jsonData(_ raw: [String: Any]) -> Data {
  try! JSONSerialization.data(withJSONObject: raw)
}

func jsonStringData(_ raw: [String: Any]) -> String {
  String(data: jsonData(raw), encoding: .utf8) ?? "{}"
}
