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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ASK_FIRST_MAX_SEC
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ASK_FIRST_MIN_SEC
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    ui: VoiceConciergeActivity,
    s: CallController.State,
    listState: LazyListState,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    ScreenHeader("Settings", s)

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
          // you@example.com", one ellipsized line) so the card kept the same height signed in and
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
            AskFirstField(ui)
            Hint(
                "How long Sai works before checking back with you.",
                detail =
                    "Below this, Sai finishes the job and tells you when it's done. Above it, Sai " +
                        "comes back to confirm before carrying on — so a short value means more " +
                        "interruptions and more control, and a long one means fewer of both. Zero " +
                        "means check with you about everything.",
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

      // ── Troubleshooting ─────────────────────────────────────────────────────────────────────
      // In the app rather than in a doc because every one of these is discovered mid-call, wearing
      // glasses, with the phone in a pocket — which is exactly when a README is unreachable. Each
      // entry names the SYMPTOM first, because that is the only thing the user has.
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          GroupHeader("Troubleshooting")

          Faq(
              "Sai can't take photos, or says the camera was denied",
              "Meta AI can grant the camera just once, and a pending glasses firmware update can " +
                  "take it away until the update finishes — so a grant that worked yesterday may " +
                  "not hold today. Open the Meta AI app, let any glasses update complete, then come " +
                  "back to Home: \"Grant glasses camera\" reappears under Connection once this app " +
                  "notices the permission is gone. If it is greyed out, the glasses aren't linked — " +
                  "turn them on first, because Meta AI can't grant a permission for a device it " +
                  "can't see.",
          )
          Faq(
              "Sai never hears me, or I never hear Sai",
              "Check the Audio line on Home for the route it picked. Glasses audio needs the " +
                  "glasses connected before you start the call; otherwise it uses the phone. If " +
                  "only one direction is broken it is almost always the route, not the call — end " +
                  "it, reconnect the glasses, and start again.",
          )
          Faq(
              "Sai talks over itself, or stops for no reason",
              "Its own voice is reaching the microphone. Turn the volume down, and if a laptop or " +
                  "speaker is playing the call aloud, point it away from you. On the phone route, " +
                  "wired headphones rule it out entirely.",
          )
          Faq(
              "Sai says my computer hasn't picked something up",
              "The machine is asleep or offline. Sai wakes it when it can, and a cloud machine " +
                  "takes about a minute — but a machine of your own that is switched off can't be " +
                  "woken remotely at all. Check it in the Sai app.",
          )
          Faq(
              "I asked for something and never heard back",
              "If you were quiet for a while, Sai holds the result rather than interrupting — say " +
                  "anything and it will offer it. \"Ask first after\" above is that delay; raise it " +
                  "and Sai reports straight away instead.",
          )
          Faq(
              "The call ended on its own",
              "Five minutes of silence ends a call to save battery, and so does an hour of talking. " +
                  "Sai says which before it hangs up. Tap to start again.",
          )
          Faq(
              "Nothing works, and I want to report it",
              "The two lines at the bottom of this screen are what a bug report needs: the build, " +
                  "and the server it is talking to. Turn on Developer mode above and the Logs tab " +
                  "keeps a transcript you can read back.",
          )
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
              BuildConfig.SAI_API_URL,
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

/**
 * The ask-first threshold: a stepper either side of a typable number, and an explicit "Saved".
 *
 * This was a bare `OutlinedTextField`, and the complaint about it was right — there was no way to
 * confirm an edit. Three separate things were wrong, and a confirmation alone would only have fixed
 * the first:
 *
 *  1. **Nothing acknowledged the change.** The value was written on every keystroke, so it *was*
 *     saved, but the screen said so nowhere. Silence is indistinguishable from a field that does
 *     nothing.
 *  2. **No way to finish.** The default keyboard had no Done key and the field never lost focus, so
 *     the keyboard sat over the rest of Settings until you pressed system Back. It was not even a
 *     numeric keypad — a digits-only field asking with a full QWERTY.
 *  3. **Blanking it was silently a no-op.** Clearing the field to retype persisted nothing (an empty
 *     string doesn't parse), so the stored value quietly stayed put while the field showed nothing.
 *     Walking away there left the screen disagreeing with what Sai would actually do.
 *
 * So the stepper is the primary control: it never opens a keyboard, and the number moving under your
 * finger is its own acknowledgement. The field stays for typing an exact value, now with a numeric
 * keypad and a Done key that commits and dismisses it. Commit also happens on focus loss, so tapping
 * anywhere else settles the edit rather than abandoning it, and `commitAskFirstSec` clamps the value
 * and re-fills a blank field from what is actually in force.
 *
 * `edited` is tracked here rather than inferred by comparing the field against what is stored: the
 * per-keystroke write means storage already agrees with the field, so a comparison can never tell
 * "typed something" from "just tapped in and out".
 */
/**
 * One symptom, and what to do about it. Collapsed by default.
 *
 * Collapsed because the list is read by someone hunting one symptom, and seven answers open at once is
 * a wall to scroll past rather than a list to scan. The question stays a full-width click target so it
 * is usable while walking.
 */
@Composable
private fun Faq(question: String, answer: String) {
  var open by remember { mutableStateOf(false) }
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable { open = !open }
              .padding(vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          question,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
      )
      Text(
          if (open) "−" else "+",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (open) {
      Text(
          answer,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun AskFirstField(ui: VoiceConciergeActivity) {
  val focus = LocalFocusManager.current
  var commits by remember { mutableIntStateOf(0) }
  var edited by remember { mutableStateOf(false) }
  var showSaved by remember { mutableStateOf(false) }
  // Keyed on the commit COUNT, not the value: committing the same number is still an answer to "did
  // that take?", and re-firing the flash on an unchanged value is the point.
  LaunchedEffect(commits) {
    if (commits == 0) return@LaunchedEffect
    showSaved = true
    delay(1_600)
    showSaved = false
  }
  val current = ui.askFirstThresholdSec.toIntOrNull()

  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedIconButton(
        onClick = {
          ui.nudgeAskFirstSec(up = false)
          edited = false
          commits++
        },
        // A blank or unparseable field leaves the stepper live: tapping it is then a way OUT of that
        // state (nudge reads through to the stored value), not another dead control.
        enabled = current == null || current > ASK_FIRST_MIN_SEC,
        border = saiEdge(),
        modifier = Modifier.size(48.dp),
    ) {
      Icon(Icons.Filled.Remove, contentDescription = "Less time")
    }
    OutlinedTextField(
        value = ui.askFirstThresholdSec,
        onValueChange = {
          edited = true
          ui.onAskFirstSecChanged(it)
        },
        label = { Text("Ask first after (s)") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(
                onDone = {
                  ui.commitAskFirstSec()
                  edited = false
                  commits++
                  focus.clearFocus()
                },
            ),
        modifier =
            Modifier.weight(1f).onFocusChanged { state ->
              // On the way OUT only, and only if something was typed — otherwise tapping in and
              // straight back out would claim to have saved an edit nobody made.
              if (!state.isFocused && edited) {
                ui.commitAskFirstSec()
                edited = false
                commits++
              }
            },
    )
    OutlinedIconButton(
        onClick = {
          ui.nudgeAskFirstSec(up = true)
          edited = false
          commits++
        },
        enabled = current == null || current < ASK_FIRST_MAX_SEC,
        border = saiEdge(),
        modifier = Modifier.size(48.dp),
    ) {
      Icon(Icons.Filled.Add, contentDescription = "More time")
    }
  }
  if (showSaved) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          Icons.Filled.Check,
          contentDescription = null,
          tint = SaiTheme.colors.accent,
          modifier = Modifier.size(14.dp),
      )
      Text("Saved", style = MaterialTheme.typography.labelMedium, color = SaiTheme.colors.accent)
    }
  }
}
