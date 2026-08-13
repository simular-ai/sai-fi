/*
 * sai-fi — Logs: the transcript and the log stream, interleaved.
 */

// Reachable only while developer mode is on (see SaiTab / Prefs.devMode), which is what this pane used
// to get from BuildConfig.DEBUG. Two consequences of that swap:
//
//  - the composer is gated on `s.active` alone now. Being on this screen already means developer mode,
//    so re-checking the build type would just make a release build's Logs tab half-functional.
//  - the backend URL moved to Settings. It lived in this header because it's the first thing you check
//    when the logs look wrong, but that put the only copy of it behind a debug-only tab.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.JetBrainsMono

@Composable
fun LogsScreen(s: CallController.State) {
  val ctx = LocalContext.current
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

  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      OutlinedButton(
          border = saiEdge(),
          enabled = copyText.isNotEmpty(),
          onClick = { clipboard.setText(AnnotatedString(copyText)) },
      ) {
        Text("Copy")
      }
    }
    if (s.active) {
      var typed by remember { mutableStateOf("") }
      Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Type a message") },
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
