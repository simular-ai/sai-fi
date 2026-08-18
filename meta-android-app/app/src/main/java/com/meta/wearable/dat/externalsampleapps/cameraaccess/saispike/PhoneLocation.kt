/*
 * sai-fi — voice-concierge (where the user actually is).
 */

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
// Returns a typed [Result] rather than a bare null, for the same reason GlassesCamera does: the
// causes are different enough that the user needs to hear which one it was. "I don't have
// permission to see where you are" points at a fix they can make; "I couldn't get a fix just now"
// asks them to say where they are and move on. Collapsing both into "no location" strands them.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One fix, in the shape the server's `TaskLocation` expects.
 *
 * [capturedAt] is the moment the FIX was taken, not the moment we sent it — a `lastLocation`
 * fallback can be minutes old, and the server renders this timestamp into the task so the agent can
 * judge for itself how current the position is.
 *
 * [approximate] means the user granted COARSE location only (Android 12+ offers that choice), so
 * this is a neighbourhood rather than a spot. It travels because the server rounds and words the
 * context line differently for it — a ~2 km circle presented as a street corner is how an agent
 * ends up recommending the cafe on a corner the user is nowhere near.
 */
class Place(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val label: String?,
    val approximate: Boolean,
    val capturedAt: Long,
) {
  fun toJson(): JSONObject =
      JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        accuracyM?.let { put("accuracyM", it.toDouble()) }
        label?.let { put("label", it) }
        if (approximate) put("approximate", true)
        put("capturedAt", capturedAt)
      }
}

object PhoneLocation {
  // How long to wait on a fresh fix before falling back to the last known one. The user is mid-
  // sentence waiting for an answer, so this is a conversational budget, not a GPS one: a cold GPS
  // lock outdoors can take half a minute, and holding the forward that long to improve a weather
  // lookup is a worse trade than answering from a fix taken a few minutes ago.
  private const val CURRENT_FIX_TIMEOUT_MS = 5_000L
  // How stale a `lastLocation` fallback may be and still be worth sending. Past this the user may
  // have travelled somewhere else entirely, and a confidently wrong city is worse than admitting we
  // don't know — the timestamp discloses the age either way, but only within a range where the
  // answer is still plausibly about where they are.
  private const val LAST_FIX_MAX_AGE_MS = 60 * 60_000L
  // Reverse geocoding is best-effort garnish on top of coordinates, and it hits the network. Bound
  // it tightly: coordinates alone still answer the request.
  private const val GEOCODE_TIMEOUT_MS = 3_000L

  enum class Reason {
    /** No location permission. The user can grant it in Android settings. */
    DENIED,
    /** Location services are switched off device-wide — nothing to ask. */
    SERVICES_OFF,
    /** Permitted and enabled, but no usable fix arrived in time. */
    NO_FIX,
  }

  sealed interface Result {
    data class Success(val place: Place) : Result
    /** [message] is written to be spoken: it is what the model is told to relay. */
    data class Failure(val reason: Reason, val message: String) : Result
  }

  fun hasPermission(context: Context): Boolean = granularity(context) != null

  /**
   * FINE if granted, else COARSE if granted, else null (nothing granted).
   *
   * Asked as "what did we actually get", not "what did we request": Android 12+ lets the user
   * downgrade a FINE request to approximate from the permission sheet, and a client that assumes it
   * got what it asked for will request PRIORITY_HIGH_ACCURACY it isn't allowed to use and label a
   * 2 km fix as precise.
   */
  private fun granularity(context: Context): Boolean? {
    val fine =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (fine) return true
    val coarse =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    return if (coarse) false else null
  }

  /** Read where the user is now. Safe to call without permission — it reports that as a failure. */
  suspend fun current(context: Context, log: (String) -> Unit = {}): Result {
    val fine =
        granularity(context)
            ?: return Result.Failure(
                Reason.DENIED,
                "location permission hasn't been granted to the sai-fi app")
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (manager?.isLocationEnabled == false) {
      return Result.Failure(Reason.SERVICES_OFF, "location is switched off on the phone")
    }

    val client = LocationServices.getFusedLocationProviderClient(context)
    val request =
        CurrentLocationRequest.Builder()
            .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(if (fine) Granularity.GRANULARITY_FINE else Granularity.GRANULARITY_COARSE)
            .setDurationMillis(CURRENT_FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(0) // a genuinely fresh read; the fallback below is the stale path
            .build()

    // The outer timeout is not redundant with setDurationMillis: a SecurityException-free but wedged
    // provider can leave the Task unresolved, and this call sits in front of a user's forward.
    val fresh =
        runCatching {
              withTimeoutOrNull(CURRENT_FIX_TIMEOUT_MS + 1_000L) {
                client.getCurrentLocation(request, null).await()
              }
            }
            .onFailure { log("📍 fresh fix failed: ${it.message}") }
            .getOrNull()

    val now = System.currentTimeMillis()
    val chosen =
        fresh
            ?: runCatching { client.lastLocation.await() }
                .onFailure { log("📍 last-known fix failed: ${it.message}") }
                .getOrNull()
                ?.takeIf { now - it.time <= LAST_FIX_MAX_AGE_MS }
                ?.also { log("📍 no fresh fix — using one from ${(now - it.time) / 60_000} min ago") }

    if (chosen == null) {
      return Result.Failure(Reason.NO_FIX, "the phone couldn't get a location fix just now")
    }

    return Result.Success(
        Place(
            lat = chosen.latitude,
            lon = chosen.longitude,
            accuracyM = if (chosen.hasAccuracy()) chosen.accuracy else null,
            label = describe(context, chosen),
            approximate = !fine,
            // The provider's own timestamp, so a fallback fix is dated when it was TAKEN. Guard the
            // absurd case (a provider reporting 0 / the future) by falling back to now, which is at
            // worst a small overstatement of freshness rather than a nonsense date in the prompt.
            capturedAt = chosen.time.takeIf { it in 1..now } ?: now,
        ))
  }

  /**
   * Reverse-geocode to something speakable ("Mission District, San Francisco, CA").
   *
   * Best-effort by design: this is the one part that needs the network, and coordinates on their own
   * already answer the request. Any failure returns null and the fix still goes.
   */
  private suspend fun describe(context: Context, loc: Location): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.getDefault())
    val address =
        runCatching {
              withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { results ->
                      if (cont.isActive) cont.resume(results.firstOrNull())
                    }
                  }
                } else {
                  // Blocking below API 33 (minSdk is 31, so this branch is live on real devices) —
                  // it does network I/O, so it must not run on the caller's thread.
                  @Suppress("DEPRECATION")
                  withContext(Dispatchers.IO) {
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                  }
                }
              }
            }
            .getOrNull() ?: return null
    return label(address)
  }

  /**
   * Compose a spoken-friendly place name, coarsest-useful-first.
   *
   * Deliberately NOT the street address: this is read aloud and handed to an agent, and "221B Baker
   * Street" is both more than a weather lookup needs and more than the user agreed to broadcast when
   * they asked what the weather was.
   */
  private fun label(a: Address): String? {
    val parts =
        listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    return parts.take(3).joinToString(", ").ifEmpty { null }
  }
}
