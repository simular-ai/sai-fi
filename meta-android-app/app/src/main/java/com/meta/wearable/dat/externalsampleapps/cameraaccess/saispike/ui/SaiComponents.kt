/*
 * sai-fi — the pieces every screen is built from.
 */

// Extracted from ConciergeScreen.kt when that file stopped being one screen. Everything here was
// `private` inside it and is now shared by four: the sign-in gate, Home, Settings and Logs.
//
// The doc comments came with the code. Several of them record a specific thing that went wrong once
// (`Section`'s fixed heights, `saiEdge`'s invisible borders, `CaptureThumbnail`'s three-state label),
// so they are worth more than the lines they describe — don't collapse them into "titled card".

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.JetBrainsMono
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme

/**
 * The border every outlined button uses.
 *
 * Material 3 defaults an outlined button's border to `outlineVariant`, which in this palette is
 * #1F1F1F against a #1A1A1A card — invisible in dark mode, so the buttons read as floating labels
 * with no edge at all. `outline` is the stronger token and is what OutlinedTextField already draws,
 * so using it here makes the buttons match the machine dropdown sitting right above them.
 */
@Composable
internal fun saiEdge() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

/**
 * A settings-group header: the label in the Sai accent, with a rule beneath it.
 *
 * This and the selected bottom-nav destination are the ONLY two places the accent green appears —
 * everything else stays on the neutral palette. That restraint is the point: `Theme.kt` keeps CTAs
 * high-contrast neutral because green means status, and an accent used in six places stops being an
 * accent. `Section` (the card) deliberately keeps its neutral `titleSmall` title; a card title and a
 * group header do different jobs, and colouring both is how one of them stops reading.
 *
 * The rule is the same colour at 35% rather than `outlineVariant`, so the pairing reads as one mark
 * instead of a coloured label that happens to sit above a grey line.
 */
@Composable
internal fun GroupHeader(title: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SaiTheme.colors.accent,
        // Uppercase at 11sp needs the tracking back; without it the letters crowd into a block.
        letterSpacing = 0.08.em,
    )
    HorizontalDivider(thickness = 1.dp, color = SaiTheme.colors.accent.copy(alpha = 0.35f))
  }
}

/**
 * Every screen's header: the title, the call-state chip, and the accent rule under both.
 *
 * One component because all three screens want the identical thing, and because the chip has to be on
 * ALL of them. Call state is the one fact that outranks whatever tab you happen to be looking at — the
 * call keeps running while you are in Settings or reading Logs, and a chip that only existed on Home
 * meant checking whether you were still live cost a tab switch. The notification carries it too, but
 * the notification is not on screen.
 *
 * Self-contained `Column` rather than two loose siblings so a caller can't accidentally put something
 * between the title and its own rule. The rule is full-bleed while the title is inset, so the screens
 * must NOT put horizontal padding on the parent — pad the content below this instead.
 */
@Composable
internal fun ScreenHeader(title: String, s: CallController.State) {
  Column {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      CallStatusChip(
          active = s.active,
          reconnecting = s.reconnecting,
          paused = s.paused,
          muted = s.saiMuted,
      )
    }
    HorizontalDivider(thickness = 1.dp, color = SaiTheme.colors.accent.copy(alpha = 0.35f))
  }
}

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
@Composable
internal fun Section(
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
internal fun Hint(summary: String, detail: String? = null) {
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

/** Reopenable scrollable error dialog — keeps full messages readable in fixed-height sections. */
@Composable
internal fun SectionErrorAffordance(
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
internal fun LocationRationaleDialog(ui: VoiceConciergeActivity) {
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

/** Decode just big enough for [targetPx], so a multi-megapixel still doesn't become a main-thread stall. */
private fun decodeSampled(jpeg: ByteArray, targetPx: Int): android.graphics.Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
  var sample = 1
  while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

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
@Composable
internal fun CaptureThumbnail(capture: CallController.Capture) {
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
@Composable
internal fun CallStatusChip(active: Boolean, reconnecting: Boolean, paused: Boolean, muted: Boolean) {
  // One colour per state — paused and muted both being amber made the two indistinguishable at a
  // glance, which defeats the point of a chip you're meant to read without stopping.
  //
  // `reconnecting` outranks paused and muted: if the call can't reach the other end, that's the fact
  // worth showing, and it's the one state where the chip is telling you something is WRONG rather
  // than reporting a choice you made. It comes from CallService (the Live session's reconnect loop
  // and the concierge WS's connection callback) rather than being sniffed out of the status text.
  //
  // `success` and not `accent`, even though dark mode gives them the same value: this dot means the
  // call is live, and the accent means "this is Sai's". They must not be the same statement.
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
