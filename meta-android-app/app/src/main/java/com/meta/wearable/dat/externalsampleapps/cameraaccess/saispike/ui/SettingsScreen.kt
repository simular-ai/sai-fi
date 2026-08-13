/*
 * sai-fi — Settings: the account, the one conversation setting, and developer mode.
 */

// A settings LIST, not another dashboard: `GroupHeader` + rows, no cards. Home earns cards because its
// contents are live state you glance at; these are three things you set once, and wrapping each in a
// bordered box made them look like status readouts.
//
// Everything here used to be somewhere else on the control page — sign-out in the first of four cards,
// the ask-first field in the last, and the backend URL buried in the Logs tab header where a release
// build could never see it. Developer mode is new.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity

@Composable
fun SettingsScreen(
    ui: VoiceConciergeActivity,
    s: CallController.State,
    listState: LazyListState,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Text(
        "Settings",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      // ── Account ─────────────────────────────────────────────────────────────────────────────
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          GroupHeader("Account")
          // The email is its own line now. It used to live inside the button's label ("Sign out
          // jamie@simular.ai", one ellipsized line) so the card kept the same height signed in and
          // signed out — a constraint the sign-in gate removed, since this screen only ever renders
          // signed in.
          Text(
              ui.userEmail ?: "Signed in",
              style = MaterialTheme.typography.bodyLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              "Signed in with Google",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(
              onClick = { ui.signOut() },
              enabled = !s.active,
              border = saiEdge(),
          ) {
            Text("Sign out")
          }
          // Say why, rather than leaving an inert button to be interpreted. Signing out mid-call would
          // pull the token the call is authenticating with out from under it.
          if (s.active) Hint("End the call to sign out.")
          SectionErrorAffordance(
              title = "Account error",
              message = ui.authError,
              open = ui.authErrorOpen,
              onOpen = { ui.authErrorOpen = true },
              onDismiss = { ui.authErrorOpen = false },
          )
        }
      }

      // ── Conversation ────────────────────────────────────────────────────────────────────────
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          GroupHeader("Conversation")
          if (s.active) {
            // Locked during a call because it is read once, into StartParams, when the call starts —
            // editing it mid-call would show a number that isn't the one in force.
            Text(
                "Ask-first after ${ui.askFirstThresholdSec}s",
                style = MaterialTheme.typography.bodyLarge,
            )
            Hint("Settings lock during a call.")
          } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedTextField(
                  value = ui.askFirstThresholdSec,
                  onValueChange = { ui.onAskFirstSecChanged(it) },
                  label = { Text("Ask first after") },
                  singleLine = true,
                  modifier = Modifier.width(160.dp),
              )
              Text(
                  "seconds",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Hint(
                "How long Sai works before checking back with you.",
                detail =
                    "Below this, Sai finishes the job and tells you when it's done. Above it, Sai " +
                        "comes back to confirm before carrying on — so a short value means more " +
                        "interruptions and more control, and a long one means fewer of both.",
            )
          }
        }
      }

      // ── Advanced ────────────────────────────────────────────────────────────────────────────
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          GroupHeader("Advanced")
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Developer mode", style = MaterialTheme.typography.bodyLarge)
              Text(
                  "Shows the Logs tab: the live transcript, the raw event stream, and a text " +
                      "composer for talking to Sai without speaking.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // Unlocked during a call on purpose, unlike the ask-first field. Nothing about it is read
            // into the call — it only decides whether a tab is drawn — and mid-call is exactly when
            // you want to turn it on, because that is when the logs have anything in them.
            Switch(checked = ui.devMode, onCheckedChange = { ui.onDevModeChanged(it) })
          }
        }
      }

      // ── What build is this ──────────────────────────────────────────────────────────────────
      // The two facts you need before reporting that something is broken. The backend URL was only
      // ever visible in the Logs tab header, which meant a release build never showed it at all.
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          HorizontalDivider(
              thickness = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              modifier = Modifier.padding(bottom = 8.dp),
          )
          Text(
              "sai-fi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                  BuildConfig.BUILD_TYPE,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
              BuildConfig.CONCIERGE_URL,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          if (BuildConfig.SAI_VERSION_TAG.isNotBlank()) {
            Text(
                "pinned to ${BuildConfig.SAI_VERSION_TAG}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
