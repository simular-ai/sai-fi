/*
 * sai-fi — DAT registration deep-link callback host.
 */

// MainActivity — NOT the launcher (VoiceConciergeActivity is). It exists only as the target for the
// `saiwearables://` deep link the Meta AI app uses to return after registration / permission flows. On
// Android the DAT SDK consumes that callback automatically once an activity with the matching
// intent-filter receives it — the result surfaces via Wearables.registrationState, which is observed in
// VoiceConciergeActivity — so this activity just needs to exist, initialize the SDK defensively, and
// bounce the user back to the voice screen. The DAT camera-stream sample UI this used to host has been
// removed; registration is driven from VoiceConciergeActivity.

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    runCatching { Wearables.initialize(this) } // idempotent — SaiFiApp already initialized at startup
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
              modifier = Modifier.fillMaxSize().padding(24.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
          ) {
            Text("Sai-Fi", style = MaterialTheme.typography.titleLarge)
            Text(
                "Glasses connected. Return to Sai to start a voice call.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { openVoiceScreen() }) { Text("Back to Sai") }
          }
        }
      }
    }
  }

  private fun openVoiceScreen() {
    startActivity(
        Intent(this, VoiceConciergeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    finish()
  }
}
