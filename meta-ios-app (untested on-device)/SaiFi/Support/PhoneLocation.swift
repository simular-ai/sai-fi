/* sai-fi — voice-concierge (where the user actually is). */

// PhoneLocation — read ONE fix for a request that turns on where the user physically is.
//
// The agent doing the work runs on a cloud VM in a datacenter, so its own network location is not
// merely unknown but actively wrong — often another country. The phone is the only thing in the
// system that knows, and this is the only thing that asks it.
//
// Read per request, never streamed and never polled: the model sets `includeLocation` on the task
// that needs it, and nothing happens on any other turn. That is the whole privacy posture, so keep
// it — a timer here would quietly turn a question-shaped feature into a tracking one.
//
// Returns a typed Result rather than a bare nil, for the same reason GlassesCamera does: the causes
// are different enough that the user needs to hear which one it was. "I don't have permission to
// see where you are" points at a fix they can make; "I couldn't get a fix just now" asks them to
// say where they are and move on. Collapsing both into "no location" strands them.
//
// Ported from Android `PhoneLocation.kt`.

import CoreLocation
import Foundation
import SaiFiCore

enum PhoneLocation {
  /// How long to wait on a fresh fix before falling back to the last known one. The user is mid-
  /// sentence waiting for an answer, so this is a conversational budget, not a GPS one.
  static let currentFixTimeoutMs: Int64 = 5_000
  /// How stale a last-known fallback may be and still be worth sending.
  static let lastFixMaxAgeMs: Int64 = 60 * 60_000
  /// Reverse geocoding is best-effort garnish on top of coordinates, and it hits the network.
  static let geocodeTimeoutMs: Int64 = 3_000

  enum Reason: Equatable, Sendable {
    /// No location permission. The user can grant it in Settings.
    case denied
    /// Location services are switched off device-wide — nothing to ask.
    case servicesOff
    /// Permitted and enabled, but no usable fix arrived in time.
    case noFix
  }

  enum Result: Equatable, Sendable {
    case success(Place)
    /// `message` is written to be spoken: it is what the model is told to relay.
    case failure(reason: Reason, message: String)
  }

  /// FINE-equivalent if full accuracy, COARSE if reduced, nil if nothing granted.
  ///
  /// Asked as "what did we actually get", not "what did we request": iOS lets the user keep Precise
  /// Location off, and a client that assumes it got a pin will label a neighbourhood as a corner.
  static func granularity() -> Bool? {
    let status = CLLocationManager().authorizationStatus
    switch status {
    case .authorizedAlways, .authorizedWhenInUse:
      return CLLocationManager().accuracyAuthorization == .fullAccuracy
    default:
      return nil
    }
  }

  static func hasPermission() -> Bool { granularity() != nil }

  /// Read where the user is now. Safe to call without permission — it reports that as a failure.
  @MainActor
  static func current(log: @escaping @Sendable (String) -> Void = { _ in }) async -> Result {
    let fine = granularity()
    guard fine != nil else {
      return .failure(
        reason: .denied,
        message: "location permission hasn't been granted to the sai-fi app")
    }
    guard CLLocationManager.locationServicesEnabled() else {
      return .failure(reason: .servicesOff, message: "location is switched off on the phone")
    }

    let reader = LocationReader(precise: fine == true)
    let fresh = await reader.requestFresh(timeoutMs: currentFixTimeoutMs + 1_000)
    if fresh == nil {
      log("📍 fresh fix failed: timed out")
    }

    let now = Int64(Date().timeIntervalSince1970 * 1000)
    var chosen = fresh
    if chosen == nil, let last = reader.lastKnown() {
      let age = now - Int64(last.timestamp.timeIntervalSince1970 * 1000)
      if age <= lastFixMaxAgeMs {
        log("📍 no fresh fix — using one from \(age / 60_000) min ago")
        chosen = last
      }
    }

    guard let chosen else {
      return .failure(reason: .noFix, message: "the phone couldn't get a location fix just now")
    }

    let takenAt = Int64(chosen.timestamp.timeIntervalSince1970 * 1000)
    let capturedAt = (takenAt >= 1 && takenAt <= now) ? takenAt : now
    let accuracy: Float? = chosen.horizontalAccuracy >= 0 ? Float(chosen.horizontalAccuracy) : nil
    let label = await describe(chosen)
    return .success(
      Place(
        lat: chosen.coordinate.latitude,
        lon: chosen.coordinate.longitude,
        accuracyM: accuracy,
        label: label,
        approximate: fine != true,
        capturedAt: capturedAt))
  }

  /// Reverse-geocode to something speakable ("Mission District, San Francisco, CA").
  ///
  /// Best-effort by design: this is the one part that needs the network, and coordinates on their
  /// own already answer the request. Any failure returns nil and the fix still goes.
  static func describe(_ loc: CLLocation) async -> String? {
    await withTaskGroup(of: String?.self) { group in
      group.addTask {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
          CLGeocoder().reverseGeocodeLocation(loc) { marks, _ in
            cont.resume(returning: marks?.first.flatMap(placeLabel(from:)))
          }
        }
      }
      group.addTask {
        try? await Task.sleep(nanoseconds: UInt64(geocodeTimeoutMs) * 1_000_000)
        return nil
      }
      let first = await group.next() ?? nil
      group.cancelAll()
      return first
    }
  }

  /// Compose a spoken-friendly place name, coarsest-useful-first.
  ///
  /// Deliberately NOT the street address: this is read aloud and handed to an agent, and a street
  /// number is both more than a weather lookup needs and more than the user agreed to broadcast
  /// when they asked what the weather was.
  static func placeLabel(
    subLocality: String?,
    locality: String?,
    subAdminArea: String?,
    adminArea: String?,
    country: String?
  ) -> String? {
    let city = nonempty(locality) ?? nonempty(subAdminArea)
    let parts = [nonempty(subLocality), city, nonempty(adminArea), nonempty(country)]
      .compactMap { $0 }
    var seen = Set<String>()
    var unique: [String] = []
    for p in parts where seen.insert(p).inserted {
      unique.append(p)
    }
    let taken = unique.prefix(3)
    if taken.isEmpty { return nil }
    return taken.joined(separator: ", ")
  }

  static func placeLabel(from mark: CLPlacemark) -> String? {
    placeLabel(
      subLocality: mark.subLocality,
      locality: mark.locality,
      subAdminArea: mark.subAdministrativeArea,
      adminArea: mark.administrativeArea,
      country: mark.country)
  }
}

private func nonempty(_ s: String?) -> String? {
  guard let s else { return nil }
  let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
  return t.isEmpty ? nil : t
}

/// One-shot `CLLocationManager` owner. Not reused across calls — `requestLocation` delivers one fix.
private final class LocationReader: NSObject, CLLocationManagerDelegate, @unchecked Sendable {
  private let manager = CLLocationManager()
  private let lock = NSLock()
  private var continuation: CheckedContinuation<CLLocation?, Never>?

  init(precise: Bool) {
    super.init()
    manager.delegate = self
    manager.desiredAccuracy = precise ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
  }

  func lastKnown() -> CLLocation? { manager.location }

  func requestFresh(timeoutMs: Int64) async -> CLLocation? {
    await withTaskGroup(of: CLLocation?.self) { group in
      group.addTask {
        await withCheckedContinuation { (cont: CheckedContinuation<CLLocation?, Never>) in
          self.lock.lock()
          self.continuation = cont
          self.lock.unlock()
          DispatchQueue.main.async { self.manager.requestLocation() }
        }
      }
      group.addTask {
        try? await Task.sleep(nanoseconds: UInt64(max(timeoutMs, 0)) * 1_000_000)
        self.finish(nil)
        return nil
      }
      let first = await group.next() ?? nil
      group.cancelAll()
      return first
    }
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    finish(locations.last)
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    finish(nil)
  }

  fileprivate func finish(_ loc: CLLocation?) {
    lock.lock()
    let cont = continuation
    continuation = nil
    lock.unlock()
    cont?.resume(returning: loc)
  }
}
