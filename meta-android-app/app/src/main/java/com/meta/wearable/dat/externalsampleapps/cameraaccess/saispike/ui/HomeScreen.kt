/*
 * sai-fi — Home: the connection, the machine, and the call.
 */

// Was the "Controls" tab, and was four cards: Account, Machines, Glasses, Settings. Account moved to
// the sign-in gate and to Settings; the ask-first field moved to Settings. What's left is three cards
// in the order a call needs them:
//
//   Connection — what you speak through. Registration, the glasses link, the audio route.
//   Machine    — which computer does the work. A standing choice, changed rarely.
//   Call        — everything you do to the call in front of you.
//
// The Call card's four controls are ALL present at all times; only their enablement changes. And its
// primary slot (bottom-right) is never a dead button — it carries whatever the next available action
// is: "Load machines" → "Start call" → "End call".

// ── The state this reads ─────────────────────────────────────────────────────────────────────────
// Everything comes off the Activity as Compose snapshot state, plus `s` (the service's call state)
// passed in from the shell. Only the `var` fields on the Activity are written from here.

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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
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
      // ── Connection ──────────────────────────────────────────────────────────────────────────
      item {
        Section(title = "Connection") {
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              // "Registration", spelled out, and never a raw enum name. This line used to read
              // "DAT: AVAILABLE" — `RegistrationState.AVAILABLE` means *not registered, and able to
              // be*, so the one state that most needs you to act announced itself with the most
              // reassuring word the SDK has. See [registrationLabel].
              Text(
                  "Meta DAT registration: ${registrationLabel(ui.glassesReg)}",
                  style = MaterialTheme.typography.bodySmall,
              )
              Text(
                  "Glasses: " +
                      when (ui.glassesLinked) {
                        true -> "connected"
                        false -> "not connected"
                        null -> "checking…"
                      },
                  style = MaterialTheme.typography.bodySmall,
              )
              // Falls back to "phone", not "—": there are only two routes, and glasses require an
              // affirmatively-present SCO device, so "we haven't computed it yet" and "phone" describe
              // the same speaker. A dash read as though audio were going nowhere.
              Text(
                  "Audio: ${s.routeStatus.ifEmpty { "phone" }}",
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
          // What "registration" even is. It is the one word on this screen that names a concept the
          // user has no way to infer — it is not Bluetooth pairing, it is not signing in, and the
          // one-app-at-a-time rule is the kind of thing you find out by breaking something else.
          if (ui.glassesReg != RegistrationState.REGISTERED) {
            Hint(
                "Registration is what lets Sai-Fi reach your glasses.",
                detail =
                    "It runs through the Meta AI app, which needs Developer Mode on, and it is " +
                        "separate from Bluetooth pairing and from signing in to Sai. Only ONE " +
                        "third-party app can be registered at a time, so doing this unregisters " +
                        "whatever else you had.",
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

      // ── Machine ─────────────────────────────────────────────────────────────────────────────
      // Its own card again, above Call. It is a different KIND of thing from the call controls —
      // picking which computer does the work is a standing choice you make once and change rarely,
      // while everything in Call is something you do to the call in front of you. Folding the picker
      // and Reload in with Start/Mute/Pause put a dropdown and a network retry in a row of call
      // buttons.
      item {
        Section(title = "Machine") {
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
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
          ) {
            if (ui.machinesInfo.isNotEmpty()) {
              Text(
                  ui.machinesInfo,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.weight(1f),
              )
            } else {
              Spacer(Modifier.weight(1f))
            }
            // Live during a call too. Switching machines mid-call is supported (the picker calls
            // `CallController.switchMachine`), so refreshing the list you would switch from has no
            // reason to be locked.
            OutlinedButton(border = saiEdge(), onClick = { ui.loadMachines() }) { Text("Reload") }
          }
          SectionErrorAffordance(
              title = "Machines error",
              message = ui.machinesError,
              open = ui.machinesErrorOpen,
              onOpen = { ui.machinesErrorOpen = true },
              onDismiss = { ui.machinesErrorOpen = false },
          )
        }
      }

      // ── The call ────────────────────────────────────────────────────────────────────────────
      item {
        Section(title = "Call") {
          CallControls(s = s, ui = ui)
          // The auth error belongs on THIS card, next to Start. Two of the three `showAuthError` call
          // sites are in the Start path (`onStartClicked`'s sign-in re-check and `startServiceNow`'s
          // token mint) and both are reachable only while signed in — so when the sign-in gate became
          // the only renderer, a token refresh that failed made Start do nothing at all, with no
          // dialog and no status line. A dead button is the worst possible report of that.
          SectionErrorAffordance(
              title = "Sign-in error",
              message = ui.authError,
              open = ui.authErrorOpen,
              onOpen = { ui.authErrorOpen = true },
              onDismiss = { ui.authErrorOpen = false },
          )
          if (s.active) {
            // Both of these say the whole thing, with no More link. They used to keep the sentence that
            // changes what you'd DO ("a long pause ends the call") in the collapsed half, which is the
            // one thing detail-on-demand must never hide — the notification has said it outright all
            // along, and the screen should not be coyer than the notification.
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
            // Unavoidable, so make it expected. DAT reports folding, taking the glasses off, walking
            // out of range and holding the temple as ONE indistinguishable "session stopped", and the
            // call goes with it. A wearer who doesn't know that is left talking to nobody.
            Hint("Folding the glasses, taking them off, or losing Bluetooth ends the call.")
            // While a capture is in flight, say so — that wait is seconds long (more with a
            // cold-camera retry) and the button gave no feedback at all until the photo landed.
            if (s.capturing) {
              Text(
                  if (s.capture == null) "Taking a photo…" else "Taking a new photo…",
                  style = MaterialTheme.typography.bodySmall,
                  color = SaiTheme.colors.brand,
              )
            }
            s.capture?.let { CaptureThumbnail(it) }
          } else if (!ui.machinesFetchOk) {
            // Why the slot says "Load machines" rather than "Start call", and — when the last attempt
            // actually failed — what went wrong, quoted from the Machine card so the two agree.
            Hint(
                if (ui.machinesError != null) "Couldn't reach your machines. ${ui.machinesInfo}".trim()
                else "Sai needs your machine list before it can start a call.",
                detail =
                    "The list comes from the Sai API, so this needs a working connection to it — on " +
                        "staging that means the VPN. Nothing about the glasses matters yet.",
            )
          } else if (ui.selectedMachine == null) {
            Hint("Pick a machine in Machine above before starting a call.")
          } else {
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
            // screen that was simultaneously reporting "checking…" — contradicting itself, and
            // contradicting the contract on VoiceConciergeActivity.glassesLinked. Neither hint shows
            // while unknown, which is the honest answer and a brief one.
            if (ui.glassesLinked == false) {
              Hint(
                  "Glasses aren't connected — the call runs on phone audio.",
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
                          "until you use \"Grant glasses camera\" in Connection above.",
              )
            }
          }
          if (s.status.isNotEmpty()) {
            Text(
                s.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/**
 * Plain English for [RegistrationState], which has five values and no obvious ones.
 *
 * This line used to print the enum name straight through — "DAT: AVAILABLE" — and `AVAILABLE` means
 * *not registered, and able to be*. So the single state that requires the user to go and do something
 * announced itself with the most reassuring word in the set, next to a button whose job was to fix it.
 *
 * `null` is "checking…", not "no": the flow behind this is seeded before DAT answers, and the app's
 * standing rule (see `VoiceConciergeActivity.glassesLinked`) is never to let an unknown read as a
 * negative.
 */
private fun registrationLabel(state: RegistrationState?): String =
    when (state) {
      RegistrationState.REGISTERED -> "registered"
      RegistrationState.REGISTERING -> "registering…"
      RegistrationState.UNREGISTERING -> "unregistering…"
      RegistrationState.AVAILABLE -> "not registered"
      // Meta AI can't take a registration at all: app missing/too old, or Developer Mode off.
      RegistrationState.UNAVAILABLE -> "unavailable — check the Meta AI app"
      null -> "checking…"
    }

/**
 * The four call controls, always all four, in a fixed 2×2.
 *
 * Every button is on screen from the start and only Start is enabled, rather than three buttons
 * appearing the instant a call begins. The layout no longer moves under your thumb at the one moment
 * you are most likely to be reaching for it, and the controls a call *has* are legible before you
 * commit to one — which is the honest answer to "what can this thing do".
 *
 * Start and End are the SAME slot, bottom-right, because they are the same decision in two states.
 * That keeps End where Start was: by the time you want to hang up you have already tapped that
 * position once, and it is the one action here you cannot undo.
 *
 * Emphasis is carried by fill, not size. Mute is the control you reach for constantly (and the one the
 * room sees, since the screen is mirrored to the dashboard), so it is the only filled button up top;
 * Photo and Pause are outlined, which leaves their container transparent so they sit on the CARD
 * rather than punching through to the app background. Border: see [saiEdge].
 *
 * Pause is heavier than mute: it drops the mic entirely, so Sai stops hearing you too. The two aren't
 * orthogonal — pause suspends the Live session, so while paused there is nothing to mute or unmute and
 * the mute button would flip a label with no audible effect. Pause dominates and mute greys out; the
 * mute state is preserved, so resuming returns you to whichever you chose.
 */
@Composable
private fun CallControls(s: CallController.State, ui: VoiceConciergeActivity) {
  val ctx = LocalContext.current
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedButton(
        border = saiEdge(),
        enabled =
            s.active && ui.glassesReg == RegistrationState.REGISTERED && ui.glassesCameraGranted,
        onClick = { CallController.capturePhoto(ctx) },
        modifier = Modifier.weight(1f).height(48.dp),
    ) {
      Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Capture view")
    }
    Button(
        onClick = { CallController.toggleMute(ctx) },
        enabled = s.active && !s.paused,
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
        enabled = s.active,
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
    if (s.active) {
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
    } else if (!ui.machinesFetchOk) {
      // Start needs a machine list, and until there is one this slot was a dead button — which is the
      // single most common thing to be looking at on this screen, because the list fails whenever the
      // staging VPN is off or the token won't mint. A greyed "Start call" reports that as "the app is
      // broken".
      //
      // So the primary slot is never dead: it carries the action that is actually available. The
      // sequence is "Load machines" → "Start call" → "End call", and there is always exactly one next
      // thing to tap. The Machine card's own Reload does the same work; this is the copy of it that
      // sits where you were already trying to press.
      Button(
          onClick = { ui.loadMachines() },
          modifier = Modifier.weight(1f).height(48.dp),
      ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Load machines")
      }
    } else {
      // The call's audio runs over the phone mic + Bluetooth (SCO to the glasses when available),
      // independent of the DAT session. So Start is NOT gated on the glasses — only on a selected
      // machine. The temple button and camera capture do need a DAT-eligible device; the hints below
      // warn about that rather than blocking.
      //
      // The `ui.signedIn` clause this used to carry is gone: behind the sign-in gate it is always
      // true, so it only obscured the two conditions that can still fail. `machinesFetchOk` is gone
      // from here too — it is the branch above — so the only way this is disabled now is an empty
      // machine list, which the hint below names and the card above fixes in one tap.
      Button(
          enabled = ui.selectedMachine != null,
          onClick = { ui.onStartClicked() },
          modifier = Modifier.weight(1f).height(48.dp),
      ) {
        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Start call")
      }
    }
  }
}
