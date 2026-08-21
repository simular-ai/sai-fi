/* sai-fi — voice concierge. */

// The voice profile the app actually ships, for the tests that grade it.
//
// One function, because it used to be two resource paths and they disagreed. `assets/
// voice-profile.json` is what the app loads; a byte copy of it lived at `test/resources/parity/
// prompt-and-tools.json` so cloud-api's offline eval could read the same bytes out of a sibling
// checkout. Nothing kept the two equal. By 2026-08-18 the copy still declared an `approveAlways`
// tool and still told the model to offer "want me to just allow these from now on?" — months after
// ADR 0014 retired that and sai-fi#9 removed it — so `VoiceProfileTest` and `LiveBrain` were both
// grading a prompt for a feature the product does not have.
//
// The copy is gone and the asset is on the unit-test classpath (`sourceSets` in build.gradle.kts).
// Everything that wants the profile comes through here, so there is nowhere for a second one to
// appear.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.VoiceProfile

/** The profile from `app/src/main/assets/`, as the app parses it. */
fun shippedProfile(): VoiceProfile {
  val stream =
      checkNotNull(VoiceProfile::class.java.getResourceAsStream("/" + VoiceProfile.ASSET)) {
        "missing /${VoiceProfile.ASSET} on the test classpath — it is app/src/main/assets/" +
            "${VoiceProfile.ASSET}, put there by the `sourceSets` block in build.gradle.kts"
      }
  return VoiceProfile.load(stream)
}
