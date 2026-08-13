/*
 * sai-fi — voice concierge (control surface UI).
 */

// The Compose half of VoiceConciergeActivity, lifted out of it: the Activity kept ~460 lines of UI
// alongside ~500 of lifecycle and call plumbing in one class. The state still lives on the Activity —
// this file takes it directly and is pure rendering. Only the `var` fields on the Activity are meant
// to be written from here; the rest are read-only by convention (there is no interface enforcing it).
//
// Layout: Controls (account / machines / glasses / settings, as cards in one LazyColumn) and, in
// DEBUG builds only, a Logs pane behind a tab (the interleaved transcript + log stream, plus the
// backend URL). A release build has no tabs — just the one page of cards.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.Prefs
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.SaiAuth
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.JetBrainsMono
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme
import kotlinx.coroutines.launch

/**
 * The border every outlined button uses.
 *
 * Material 3 defaults an outlined button's border to `outlineVariant`, which in this palette is
 * #1F1F1F against a #1A1A1A card — invisible in dark mode, so the buttons read as floating labels
 * with no edge at all. `outline` is the stronger token and is what OutlinedTextField already draws,
 * so using it here makes the buttons match the machine dropdown sitting right above them.
 */
@Composable
private fun saiEdge() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConciergeScreen(ui: VoiceConciergeActivity) {
  val s by CallController.state.collectAsState()
  val ctx = LocalContext.current
  // Auto-load the machine list once signed in (like `sai machine` after CLI login).
  LaunchedEffect(ui.signedIn) { if (ui.signedIn && ui.machines.isEmpty() && !ui.machinesFetchOk) ui.loadMachines() }

  // Follow the call's active machine (a voice `switchMachine` or mid-call UI switch happens in the
  // service): keep the picker selection in lockstep so the dropdown never shows a stale VM — and so
  // the selection survives correctly after the call ends (when the label falls back to ui.selectedMachine).
  LaunchedEffect(s.machineId, ui.machines.size) {
    val id = s.machineId
    if (s.active && id != null) {
      ui.machines.firstOrNull { it.machineId == id }?.let { ui.selectedMachine = it }
    }
  }

  LocationRationaleDialog(ui)

  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
        // safeDrawing covers the status bar AND the gesture/navigation bar at the bottom. The top
        // inset used to be a hardcoded 40dp, which is neither the real status-bar height on any given
        // device nor anything at all at the bottom — so the last card sat under the nav bar.
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
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

      // The Logs pane is a debug tool, so the tab row only exists in a DEBUG build — a release build
      // is a single page of cards with no chrome above them. The pager stays (with one page) rather
      // than branching the whole layout: a one-page pager can't be swiped and draws nothing of its
      // own, so this costs a wrapper instead of a second copy of the Controls content.
      val debug = BuildConfig.DEBUG
      val scope = rememberCoroutineScope()
      val pagerState = rememberPagerState(pageCount = { if (debug) 2 else 1 })
      if (debug) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
          Tab(
              selected = pagerState.currentPage == 0,
              onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
              text = { Text("Controls") },
          )
          Tab(
              selected = pagerState.currentPage == 1,
              onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
              text = { Text("Logs") },
          )
        }
      }

      HorizontalPager(
          state = pagerState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
      ) { page ->
        if (page == 0) {
        // One scroll for the whole page. Sections are items rather than one big Column so that
        // adding a section later doesn't reintroduce the compose-everything-up-front cost, and so
        // this pane behaves the same way as the Logs pane next door.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
      // ── Account ─────────────────────────────────────────────────────────────────────────────
      item {
      Section(title = "Account") {
        if (ui.signedIn) {
          // Mirrors "Sign in with Google": one button below the title carrying the account it acts
          // on, rather than an email label plus a separate button. Same shape signed in or out, so
          // the card doesn't change height or layout when you sign in.
          OutlinedButton(onClick = { ui.signOut() }, enabled = !s.active, border = saiEdge()) {
            Text(
                "Sign out ${ui.userEmail ?: ""}".trim(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
          }
        } else {
          Button(onClick = { ui.signIn() }) { Text("Sign in with Google") }
          if (ui.authError == null && SaiAuth.isConfigured) {
            Text(
                "Sign in to load machines and start a call.",
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        SectionErrorAffordance(
            title = "Account error",
            message = ui.authError,
            open = ui.authErrorOpen,
            onOpen = { ui.authErrorOpen = true },
            onDismiss = { ui.authErrorOpen = false },
        )
      }
      }

      // ── Machines ────────────────────────────────────────────────────────────────────────────
      item {
      Section(title = "Machines") {
        var machineMenu by remember { mutableStateOf(false) }
        val dropdownEnabled = ui.machinesFetchOk && ui.machines.isNotEmpty()
        // During a call, show the service's active label (voice/UI switch); else the picker selection.
        val displayedLabel =
            if (s.active) s.machineLabel ?: ui.selectedMachine?.label.orEmpty()
            else ui.selectedMachine?.label.orEmpty()
        val machineLabel =
            when {
              !ui.signedIn -> "Load machines"
              ui.machinesFetchOk -> "Machine (${ui.machines.size} found)"
              else -> "Machine"
            }
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
        OutlinedButton(
            border = saiEdge(),
            onClick = { ui.loadMachines() },
            enabled = ui.signedIn && !s.active,
        ) {
          Text("Reload")
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
      }
      }

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
            OutlinedButton(onClick = { ui.registerGlasses() }, border = saiEdge()) { Text("Register glasses") }
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
        if (!s.active) {
          // The call's audio runs over the phone mic + Bluetooth (SCO to the glasses when
          // available), independent of the DAT session. So don't gate Start on the DAT link —
          // only sign-in + a selected machine are required. The glasses temple button and camera
          // capture DO need a DAT-eligible device; we warn when it isn't linked rather than block.
          Button(
              enabled = ui.signedIn && ui.machinesFetchOk && ui.selectedMachine != null,
              onClick = { ui.onStartClicked() },
          ) {
            Text("Start call")
          }
          if (ui.signedIn && ui.machinesFetchOk && ui.selectedMachine != null) {
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
            // contradicting the contract on VoiceConciergeActivity.glassesLinked. Neither hint shows while unknown,
            // which is the honest answer and a brief one.
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
        } else {
          // Two rows of two. Emphasis is carried by fill, not by size: Mute is the one control you
          // reach for constantly (and the one the room sees, since the screen is mirrored to the
          // dashboard) so it's the only filled button up top; Photo and Pause sit on the app
          // background so they read as available-but-quiet against the card they're on. Stop is
          // filled in the error colour — it's the one action you can't undo mid-demo, and it should
          // look different from everything you might tap by accident.
          //
          // Pause is heavier than mute: it drops the mic entirely, so Sai stops hearing you too. The
          // two aren't orthogonal — pause suspends the Live session, so while paused there's nothing
          // to mute or unmute and the mute button would flip a label with no audible effect. Pause
          // dominates and mute greys out; the mute state is preserved, so resuming returns you to
          // whichever you chose.
          // OutlinedButton leaves the container transparent, so Photo and Pause sit on the CARD
          // rather than punching a hole through to the app background. Border: see saiEdge().
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
              // Mic icons rather than transport icons: pause/resume here is about whether Sai can
              // HEAR you, not about playback. A ▶/❚❚ pair reads as "pause the audio Sai is speaking",
              // which is what Mute does.
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
        SectionErrorAffordance(
            title = "Glasses error",
            message = ui.glassesError,
            open = ui.glassesErrorOpen,
            onOpen = { ui.glassesErrorOpen = true },
            onDismiss = { ui.glassesErrorOpen = false },
        )
      }
      }

      // ── Settings ────────────────────────────────────────────────────────────────────────────
      item {
      Section(title = "Settings") {
        if (s.active) {
          Text(
              "Hands-free · Ask-first after ${ui.askFirstThresholdSec}s",
              style = MaterialTheme.typography.bodySmall,
          )
          Text("Settings lock during a call.", style = MaterialTheme.typography.bodySmall)
        } else {
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
                value = ui.askFirstThresholdSec,
                onValueChange = { ui.askFirstThresholdSec = it.filter(Char::isDigit).take(4) },
                label = { Text("Ask-first after (s)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
          }
        }
      }
      }
        }
        } else {
        // Full-height Logs pane: debug composer (top) + a single retained, auto-scrolling stream of
        // the whole session's transcript and log lines interleaved chronologically — readable and
        // copyable during a live call.
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Single ordered stream: transcript turns and log lines each hold their real chronological
          // position (a streaming turn stays anchored where it started while its text updates), so
          // nothing is pinned to the bottom or re-flushed out of order.
          val entries = s.entries
          // Copy the whole unified stream to the clipboard, so testing doesn't need a screenshot.
          val clipboard = LocalClipboardManager.current
          val copyText =
              entries
                  .joinToString("\n") {
                    when (it.kind) {
                      CallController.Kind.YOU -> "you: ${it.text}"
                      CallController.Kind.SAI -> "sai: ${it.text}"
                      CallController.Kind.LOG -> it.text
                    }
                  }
                  .trim()
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            // The backend URL lives here rather than under the app title: it's the first thing you
            // check when the logs look wrong, and it's operator detail that has no business sitting
            // at the top of the screen in a demo.
            Column(modifier = Modifier.weight(1f)) {
              Text("Logs", style = MaterialTheme.typography.titleSmall)
              Text(
                  BuildConfig.CONCIERGE_URL,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
              )
            }
            OutlinedButton(
                border = saiEdge(),
                enabled = copyText.isNotEmpty(),
                onClick = { clipboard.setText(AnnotatedString(copyText)) },
            ) {
              Text("Copy")
            }
          }
          if (BuildConfig.DEBUG && s.active) {
            var typed by remember { mutableStateOf("") }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedTextField(
                  value = typed,
                  onValueChange = { typed = it },
                  label = { Text("Type a message (debug)") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
              )
              Button(
                  enabled = typed.isNotBlank(),
                  onClick = {
                    CallController.sendText(ctx, typed)
                    typed = ""
                  },
              ) {
                Text("Send")
              }
            }
          }
          val listState = rememberLazyListState()
          // Auto-scroll as the stream grows AND as the last (streaming) entry's text lengthens —
          // an in-place transcript update doesn't change the count, so key on both.
          LaunchedEffect(entries.size, entries.lastOrNull()?.text) {
            if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
          }
          if (entries.isEmpty()) {
            Text(
                "Transcript and logs appear here during a call.",
                style = MaterialTheme.typography.bodySmall,
            )
          } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              items(entries, key = { it.id }) { entry ->
                when (entry.kind) {
                  CallController.Kind.YOU ->
                      Text("you: ${entry.text}", style = MaterialTheme.typography.bodyMedium)
                  CallController.Kind.SAI ->
                      Text("sai: ${entry.text}", style = MaterialTheme.typography.bodyMedium)
                  CallController.Kind.LOG ->
                      Text(
                          entry.text,
                          style = MaterialTheme.typography.bodySmall,
                          fontFamily = JetBrainsMono,
                      )
                }
              }
            }
          }
        }
        }
      }
    }
  }
}


/** Reopenable scrollable error dialog — keeps full messages readable in fixed-height sections. */
@Composable
private fun SectionErrorAffordance(
    title: String,
    message: String?,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
  if (message.isNullOrEmpty()) return
  // A link, not a button: this sits inline under a section's own controls, and a filled-out
  // TextButton read as a second action competing with them. Matches `Hint`'s More/Less link.
  Text(
      "View error",
      style = MaterialTheme.typography.labelMedium,
      color = SaiTheme.colors.brand,
      modifier = Modifier.clickable(onClick = onOpen),
  )
  if (open) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
          Column(
              modifier =
                  Modifier.fillMaxWidth()
                      .heightIn(max = 360.dp)
                      .verticalScroll(rememberScrollState()),
          ) {
            Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono)
          }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
  }
}

/**
 * Why Sai-Fi wants location, said in our own words just before the system asks.
 *
 * Android fixes the wording of the platform permission sheet — an app cannot add a sentence to it. So
 * this is the only surface where the reason can appear, and it has to come *first*: once the system
 * sheet is up, an explanation behind it is unreadable and arrives too late to matter.
 *
 * Deliberately not dismissible by tapping outside. The two buttons are the whole decision, and an
 * accidental outside-tap would spend the app's one and only ask (see `maybeAutoRequestLocation`)
 * without the user having answered anything.
 */
@Composable
private fun LocationRationaleDialog(ui: VoiceConciergeActivity) {
  if (!ui.locationRationaleOpen) return
  AlertDialog(
      onDismissRequest = {},
      title = { Text("Let Sai use your location?") },
      text = {
        Text(
            "Sai-Fi can use your phone's location so questions like \"what's the weather\" or " +
                "\"what's near me\" just work, without you having to say where you are out loud.\n\n" +
                "It's read only when a question needs it, never streamed or tracked. Everything else " +
                "in the app works the same if you say no.",
            style = MaterialTheme.typography.bodySmall,
        )
      },
      confirmButton = { TextButton(onClick = { ui.onLocationRationale(true) }) { Text("Continue") } },
      dismissButton = { TextButton(onClick = { ui.onLocationRationale(false) }) { Text("Not now") } },
  )
}

/**
 * One line of explanation, with the rest available on tap.
 *
 * The screen used to carry several three- and four-sentence paragraphs in `bodySmall`, stacked
 * directly under the controls they described. Individually each was accurate; together they were the
 * main reason the app read as unfinished, and the important sentence was never the first one you saw.
 *
 * [summary] is the sentence that changes what you'd do next. [detail] is everything else — collapsed
 * by default and expanded in place, so the information isn't lost, just not shouted.
 */
@Composable
private fun Hint(summary: String, detail: String? = null) {
  var expanded by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (detail != null) {
      if (expanded) {
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          if (expanded) "Less" else "More",
          style = MaterialTheme.typography.labelMedium,
          color = SaiTheme.colors.brand,
          modifier = Modifier.clickable { expanded = !expanded },
      )
    }
  }
}

/** Decode just big enough for [targetPx], so a multi-megapixel still doesn't become a main-thread stall. */
private fun decodeSampled(jpeg: ByteArray, targetPx: Int): android.graphics.Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
  var sample = 1
  while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
/**
 * The most recent glasses photo, with whether it has reached the agent yet.
 *
 * Manual capture is otherwise silent on the phone — the picture only ever appeared on the presenter
 * dashboard and inside the next task, so the wearer couldn't check framing without asking Sai. This
 * is what lets the spoken acknowledgement stay a terse "got it" instead of a description.
 *
 * The wording answers the question the user actually has — *has this gone to the computer?* — and it
 * has three answers, not two. A boolean read "Not sent" through the seconds between the model asking
 * for the photo and the photo existing, which is exactly when Sai is working in silence and the label
 * is all the user has. "Held" is the resting state of a clipboard and must not look like a problem;
 * the old sublabel ("rides your next request") described the auto-attach behaviour that was removed —
 * a photo now goes only with a request that asks for it.
 */
private fun CaptureThumbnail(capture: CallController.Capture) {
  // Decode DOWNSAMPLED for the thumbnail. A glasses still is a couple of megapixels; decoding it at
  // full size runs on the main thread during composition (jank) and then holds ~8 MB of ARGB_8888 to
  // draw a 48dp square. inSampleSize is a power-of-two shortcut the decoder applies while reading.
  val thumb = remember(capture.takenAt) { decodeSampled(capture.jpeg, targetPx = 192) } ?: return
  var full by remember { mutableStateOf(false) }
  // Full resolution only once the dialog is actually opened, and released with it.
  val fullBitmap =
      remember(capture.takenAt, full) {
        if (full) runCatching { BitmapFactory.decodeByteArray(capture.jpeg, 0, capture.jpeg.size) }.getOrNull()
        else null
      }

  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.fillMaxWidth().clickable { full = true },
  ) {
    Image(
        bitmap = thumb.asImageBitmap(),
        contentDescription = "Latest capture",
        contentScale = ContentScale.Crop,
        modifier =
            Modifier.size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text("Latest capture", style = MaterialTheme.typography.bodyMedium)
      Text(
          when (capture.sent) {
            CallController.Sent.SENT -> "Sent to the computer"
            CallController.Sent.SENDING -> "Sending — a request is carrying it"
            CallController.Sent.HELD -> "Held on the phone — say what to do with it"
          },
          style = MaterialTheme.typography.bodySmall,
          color =
              when (capture.sent) {
                CallController.Sent.SENT -> SaiTheme.colors.success
                CallController.Sent.SENDING -> SaiTheme.colors.brand
                CallController.Sent.HELD -> MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
  }

  if (full) {
    AlertDialog(
        onDismissRequest = { full = false },
        confirmButton = { TextButton(onClick = { full = false }) { Text("Close") } },
        text = {
          Image(
              bitmap = (fullBitmap ?: thumb).asImageBitmap(),
              contentDescription = "Latest capture",
              modifier = Modifier.fillMaxWidth(),
          )
        },
    )
  }
}

@Composable
/**
 * Call state as a dot + short label, not a sentence.
 *
 * This is the one thing worth reading from across a room, and the phone screen is now mirrored to the
 * presenter dashboard, so it has to survive being projected. A dot carries the state before anyone
 * reads the word; the colour is the semantic one (green live, amber degraded, muted grey idle) rather
 * than a decorative accent.
 *
 * The service's free-text `status` line ("connecting…", "call ended", error text) stays where it was,
 * in the Glasses section — this chip is the glanceable summary, not a replacement for it.
 */
private fun CallStatusChip(active: Boolean, reconnecting: Boolean, paused: Boolean, muted: Boolean) {
  // One colour per state — paused and muted both being amber made the two indistinguishable at a
  // glance, which defeats the point of a chip you're meant to read without stopping.
  //
  // `reconnecting` outranks paused and muted: if the call can't reach the other end, that's the fact
  // worth showing, and it's the one state where the chip is telling you something is WRONG rather
  // than reporting a choice you made. It comes from CallService (the Live session's reconnect loop
  // and the concierge WS's connection callback) rather than being sniffed out of the status text.
  val (dot, label) =
      when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant to "idle"
        reconnecting -> MaterialTheme.colorScheme.error to "reconnecting"
        paused -> SaiTheme.colors.warning to "paused"
        muted -> SaiTheme.colors.brand to "muted"
        else -> SaiTheme.colors.success to "live"
      }
  Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          Modifier.clip(RoundedCornerShape(999.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              // Border in the state's own colour: it doubles the signal, so the chip still reads at
              // a glance (and at projector size) when the 8dp dot alone is too small to register.
              .border(1.dp, dot, RoundedCornerShape(999.dp))
              .padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
/**
 * One titled card, sized by its content.
 *
 * This used to be a FIXED-HEIGHT box (96/180/280/132 dp) with its own inner `verticalScroll`, all
 * inside the page's outer scroll. Two things were wrong with that. Nested same-axis scrollables
 * make touch handling ambiguous — a drag that starts inside a section scrolls the section, not the
 * page. And anything that didn't fit its hardcoded height simply became invisible in a tiny
 * independently-scrolling box, which is how the Glasses section could hide a button behind a
 * four-line warning. The heights had been tuned by eye against the old type ramp, so they were
 * already wrong the moment the ramp changed, and wrong again at any non-default font scale.
 *
 * Now: no height, no inner scroll. The card is as tall as its content and the page scrolls once.
 * A card (rather than the old divider + title) gives the grouping a visible edge without hairlines.
 */
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Card(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          ),
      border = BorderStroke(1.dp, SaiTheme.colors.border),
      // Flat + bordered, matching the desktop app's cards; M3's default tonal elevation would
      // tint the container away from the `card` token the scheme deliberately points at.
      elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      content()
    }
  }
}
