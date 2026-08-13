/*
 * sai-fi — Home: the glasses, the machine, and Start/Stop.
 */

// Was the "Controls" tab, and was four cards: Account, Machines, Glasses, Settings. Account moved to
// the sign-in gate and to Settings; the ask-first field moved to Settings. What's left is the two
// things a call needs, in the order the call needs them — the glasses you're speaking through, then
// the machine doing the work, then Start.
//
// Machine sits BELOW Glasses now, which reverses the old order. It is the last decision before Start
// and belongs next to it; it used to be two cards further up, with the glasses controls in between.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.Prefs
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ui: VoiceConciergeActivity,
    s: CallController.State,
    listState: LazyListState,
) {
  val ctx = LocalContext.current

  // The machine autoload is NOT here — it lives in ConciergeScreen, which stays composed across tab
  // switches. See the comment there.
  //
  // Follow the call's active machine (a voice `switchMachine` or mid-call UI switch happens in the
  // service): keep the picker selection in lockstep so the dropdown never shows a stale VM — and so
  // the selection survives correctly after the call ends (when the label falls back to ui.selectedMachine).
  LaunchedEffect(s.machineId, ui.machines.size) {
    val id = s.machineId
    if (s.active && id != null) {
      ui.machines.firstOrNull { it.machineId == id }?.let { ui.selectedMachine = it }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Sai-Fi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      CallStatusChip(
          active = s.active,
          reconnecting = s.reconnecting,
          paused = s.paused,
          muted = s.saiMuted,
      )
    }
    // The header's accent rule — the counterpart to the group rules in Settings, and the reason the
    // app title reads as Sai's rather than as a label on a sample app.
    HorizontalDivider(thickness = 1.dp, color = SaiTheme.colors.accent.copy(alpha = 0.35f))

    // One scroll for the whole page. Sections are items rather than one big Column so that
    // adding a section later doesn't reintroduce the compose-everything-up-front cost.
    LazyColumn(
        state = listState,
        // weight, not fillMaxSize: the list takes what the header above it leaves, stated rather than
        // inferred from the order the Column happens to measure in.
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // ── Glasses / call ──────────────────────────────────────────────────────────────────────
      item {
        Section(title = "Glasses") {
          // Falls back to "phone", not "—": there are only two routes, and glasses require an
          // affirmatively-present SCO device, so "we haven't computed it yet" and "phone" describe the
          // same speaker. A dash read as though audio were going nowhere.
          Text(
              "Audio route: ${s.routeStatus.ifEmpty { "phone" }}",
              style = MaterialTheme.typography.bodySmall,
          )
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                  "DAT: ${ui.glassesReg?.name ?: "—"}",
                  style = MaterialTheme.typography.bodySmall,
              )
              Text(
                  "Link: " +
                      when (ui.glassesLinked) {
                        true -> "connected"
                        false -> "disconnected"
                        null -> "checking…"
                      },
                  style = MaterialTheme.typography.bodySmall,
              )
            }
            if (ui.glassesReg != RegistrationState.REGISTERED) {
              OutlinedButton(onClick = { ui.registerGlasses() }, border = saiEdge()) {
                Text("Register glasses")
              }
            } else if (!ui.glassesCameraGranted) {
              // Disabled only on an AFFIRMATIVE "no device" (`== false`), never on the unknown state.
              // The distinction is the whole point: this button used to be gated on a plain Boolean,
              // and straight after registration the DAT devices flow has often not emitted yet, so the
              // one control that grants the camera was dead while Start was live — no way to grant at
              // all, and nothing on screen to explain why. While the link is still unknown the button
              // stays live and `requestGlassesCamera` re-probes for real, which is feedback a disabled
              // button cannot give. Once DAT has actually said "nothing connected", the grant cannot
              // succeed — Meta AI needs a linked device — so a live button would only lead into a flow
              // that dead-ends; the line below says why it's greyed.
              OutlinedButton(
                  border = saiEdge(),
                  enabled = ui.glassesLinked != false,
                  onClick = { ui.requestGlassesCamera() },
              ) {
                Text("Grant glasses camera")
              }
            }
          }
          if (ui.glassesReg == RegistrationState.REGISTERED &&
              !ui.glassesCameraGranted &&
              ui.glassesLinked == false) {
            Text(
                "Turn glasses on to grant camera (Meta AI needs a linked device).",
                style = MaterialTheme.typography.bodySmall,
            )
          }
          SectionErrorAffordance(
              title = "Glasses error",
              message = ui.glassesError,
              open = ui.glassesErrorOpen,
              onOpen = { ui.glassesErrorOpen = true },
              onDismiss = { ui.glassesErrorOpen = false },
          )
        }
      }

      // ── The call ────────────────────────────────────────────────────────────────────────────
      // Everything about the call in one card, in the order it happens: which machine, then Start —
      // and once it's running, the four controls in Start's place.
      //
      // This is a regrouping, not just a reorder. Machine used to be its own card two above this one,
      // with Start buried at the bottom of Glasses, so the picker and the button it gates were
      // separated by every glasses control. Splitting on "glasses hardware" vs "this call" puts the
      // one decision Start depends on directly above Start.
      item {
        Section(title = "Call") {
          var machineMenu by remember { mutableStateOf(false) }
          val dropdownEnabled = ui.machinesFetchOk && ui.machines.isNotEmpty()
          // During a call, show the service's active label (voice/UI switch); else the picker selection.
          val displayedLabel =
              if (s.active) s.machineLabel ?: ui.selectedMachine?.label.orEmpty()
              else ui.selectedMachine?.label.orEmpty()
          val machineLabel =
              if (ui.machinesFetchOk) "Machine (${ui.machines.size} found)" else "Machine"
          ExposedDropdownMenuBox(
              expanded = machineMenu && dropdownEnabled,
              onExpandedChange = { if (dropdownEnabled) machineMenu = it },
              modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
                value = displayedLabel,
                onValueChange = {},
                readOnly = true,
                enabled = dropdownEnabled,
                singleLine = true,
                label = { Text(machineLabel) },
                placeholder = { Text(if (dropdownEnabled) "Select machine" else "—") },
                trailingIcon = {
                  ExposedDropdownMenuDefaults.TrailingIcon(expanded = machineMenu && dropdownEnabled)
                },
                modifier =
                    Modifier.menuAnchor(
                            type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = dropdownEnabled,
                        )
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = machineMenu && dropdownEnabled,
                onDismissRequest = { machineMenu = false },
            ) {
              ui.machines.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = {
                      ui.selectedMachine = m
                      Prefs.setMachineId(ctx, m.machineId)
                      machineMenu = false
                      if (s.active) {
                        CallController.switchMachine(ctx, m.machineId)
                      }
                    },
                )
              }
            }
          }
          if (ui.machinesInfo.isNotEmpty()) {
            Text(ui.machinesInfo, style = MaterialTheme.typography.bodySmall)
          }
          SectionErrorAffordance(
              title = "Machines error",
              message = ui.machinesError,
              open = ui.machinesErrorOpen,
              onOpen = { ui.machinesErrorOpen = true },
              onDismiss = { ui.machinesErrorOpen = false },
          )
          // The auth error belongs on THIS card, not only on the sign-in screen. Two of the three
          // `showAuthError` call sites are in the Start path (`onStartClicked`'s sign-in re-check and
          // `startServiceNow`'s token mint) and both are reachable only while signed in — so when the
          // gate became the only renderer, a token refresh that failed made Start do nothing at all,
          // with no dialog and no status line. A dead button is the worst possible report of that.
          SectionErrorAffordance(
              title = "Sign-in error",
              message = ui.authError,
              open = ui.authErrorOpen,
              onOpen = { ui.authErrorOpen = true },
              onDismiss = { ui.authErrorOpen = false },
          )
          if (s.active) {
            InCallControls(s = s, ui = ui)
          } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
              // The call's audio runs over the phone mic + Bluetooth (SCO to the glasses when
              // available), independent of the DAT session. So don't gate Start on the DAT link —
              // only a selected machine is required. The glasses temple button and camera capture DO
              // need a DAT-eligible device; we warn when it isn't linked rather than block.
              //
              // The `ui.signedIn` clause this used to carry is gone: behind the gate it is always
              // true, so it only obscured the two conditions that can still fail.
              Button(
                  enabled = ui.machinesFetchOk && ui.selectedMachine != null,
                  onClick = { ui.onStartClicked() },
                  modifier = Modifier.weight(1f).height(48.dp),
              ) {
                Text("Start call")
              }
              OutlinedButton(border = saiEdge(), onClick = { ui.loadMachines() }) { Text("Reload") }
            }
            if (ui.machinesFetchOk && ui.selectedMachine != null) {
              // One line, detail on demand. This was a four-sentence paragraph — the single biggest
              // contributor to the wall-of-grey-text look, and it sat directly under the button you
              // were trying to read.
              //
              // Two different degraded starts, and only the first used to be called out. A linked pair
              // with no camera grant looks completely healthy here, right up until "take a photo"
              // fails mid-call with nothing on this screen having warned you.
              //
              // `== false`, not `!= true`: this asserts something about the glasses, so it needs DAT to
              // have actually said it. On the unknown state it used to claim "aren't linked" on the same
              // screen that was simultaneously reporting "Link: checking…" — contradicting itself, and
              // contradicting the contract on VoiceConciergeActivity.glassesLinked. Neither hint shows
              // while unknown, which is the honest answer and a brief one.
              if (ui.glassesLinked == false) {
                Hint(
                    "Glasses aren't linked — the call runs on phone audio.",
                    detail =
                        "The call will run on phone/Bluetooth audio, but the temple button and photo " +
                            "capture won't work until the glasses are on, unfolded, in range, and " +
                            "registered. A \"no eligible device\" error means the glasses aren't paired " +
                            "for this app yet.",
                )
              } else if (!ui.glassesCameraGranted) {
                Hint(
                    "Camera isn't granted — Sai can't see or take photos on this call.",
                    detail =
                        "Audio and the temple button work. Anything that needs the glasses camera — " +
                            "\"take a photo\", or a question about what you're looking at — will fail " +
                            "until you use \"Grant glasses camera\" above.",
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The four controls a live call has, plus what it is currently doing.
 *
 * Two rows of two. Emphasis is carried by fill, not by size: Mute is the one control you reach for
 * constantly (and the one the room sees, since the screen is mirrored to the dashboard) so it's the
 * only filled button up top; Photo and Pause sit on the app background so they read as
 * available-but-quiet against the card they're on. Stop is filled in the error colour — it's the one
 * action you can't undo mid-demo, and it should look different from everything you might tap by
 * accident.
 *
 * Pause is heavier than mute: it drops the mic entirely, so Sai stops hearing you too. The two aren't
 * orthogonal — pause suspends the Live session, so while paused there's nothing to mute or unmute and
 * the mute button would flip a label with no audible effect. Pause dominates and mute greys out; the
 * mute state is preserved, so resuming returns you to whichever you chose.
 *
 * OutlinedButton leaves the container transparent, so Photo and Pause sit on the CARD rather than
 * punching a hole through to the app background. Border: see [saiEdge].
 */
@Composable
private fun InCallControls(s: CallController.State, ui: VoiceConciergeActivity) {
  val ctx = LocalContext.current
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedButton(
        border = saiEdge(),
        enabled = ui.glassesReg == RegistrationState.REGISTERED && ui.glassesCameraGranted,
        onClick = { CallController.capturePhoto(ctx) },
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
      Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Capture view")
    }
    Button(
        onClick = { CallController.toggleMute(ctx) },
        enabled = !s.paused,
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
      Icon(
          if (s.saiMuted) Icons.AutoMirrored.Filled.VolumeUp
          else Icons.AutoMirrored.Filled.VolumeOff,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(6.dp))
      Text(if (s.saiMuted) "Unmute Sai" else "Mute Sai")
    }
  }
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedButton(
        border = saiEdge(),
        onClick = { CallController.togglePause(ctx) },
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
      // Mic icons rather than transport icons: pause/resume here is about whether Sai can HEAR you,
      // not about playback. A ▶/❚❚ pair reads as "pause the audio Sai is speaking", which is Mute.
      Icon(
          if (s.paused) Icons.Filled.Mic else Icons.Filled.MicOff,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(6.dp))
      Text(if (s.paused) "Resume call" else "Pause call")
    }
    Button(
        onClick = { CallController.stop(ctx) },
        colors =
            ButtonDefaults.buttonColors(
                containerColor = SaiTheme.colors.danger,
                contentColor = SaiTheme.colors.onDanger,
            ),
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
      Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("End call")
    }
  }
  // Both of these say the whole thing, with no More link. They used to keep the sentence that
  // changes what you'd DO ("a long pause ends the call") in the collapsed half, which is the
  // one thing detail-on-demand must never hide — the notification has said it outright all
  // along, and the screen should not be coyer than the notification. `Hint`'s collapse stays
  // for what it was built for: the four-sentence glasses-not-linked paragraph above.
  when {
    s.paused ->
        Hint(
            "Paused — the mic is off, so Sai hears nothing. A long pause ends the call." +
                if (s.saiMuted) " Sai will come back muted when you resume." else "",
        )
    s.saiMuted ->
        Hint(
            "Muted — Sai still hears you and keeps working, it just won't speak. " +
                "Anything that finishes while muted is held and offered after you unmute.",
        )
  }
  // Unavoidable, so make it expected. DAT reports folding, taking the glasses off, walking out
  // of range and holding the temple as ONE indistinguishable "session stopped", and the call
  // goes with it. A wearer who doesn't know that is left talking to nobody.
  Hint("Folding the glasses, taking them off, or losing Bluetooth ends the call.")
  // While a capture is in flight, say so — that wait is seconds long (more with a cold-camera
  // retry) and the button gave no feedback at all until the photo landed.
  if (s.capturing) {
    Text(
        if (s.capture == null) "Taking a photo…" else "Taking a new photo…",
        style = MaterialTheme.typography.bodySmall,
        color = SaiTheme.colors.brand,
    )
  }
  s.capture?.let { CaptureThumbnail(it) }
  if (s.status.isNotEmpty()) {
    Text(
        s.status,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
