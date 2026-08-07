/*
 * sai-fi — voice-concierge app.
 */

// SaiFiApp — process-wide initialization in Application.onCreate:
//  • the DAT SDK, so `Wearables.*` is ready wherever it's first touched (registration in
//    VoiceConciergeActivity, the gesture DeviceSession in CallService, the deep-link callback in
//    MainActivity), and
//  • Firebase, so Google Sign-In (SaiAuth) can mint the ID token the app sends to cloud-api.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class SaiFiApp : Application() {
  override fun onCreate() {
    super.onCreate()
    runCatching { Wearables.initialize(this) }
    SaiAuth.initialize(this)
  }
}
