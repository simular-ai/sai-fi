/*
 * sai-fi — the app shell: the sign-in gate, and the bottom bar behind it.
 */

// This file was the entire UI (952 lines: a header Row, a debug-only tab row, and four cards in one
// LazyColumn). It is now the shell only — which screen is showing, and nothing about what's on it.
// The screens are HomeScreen / SettingsScreen / LogsScreen; the shared pieces are SaiComponents.kt.
//
// The state still lives on the Activity and this takes it directly. Only the `var` fields there are
// meant to be written from here; the rest are read-only by convention (there is no interface enforcing
// it, and the one that existed was ceremony with a single implementer).
//
// Two structural changes worth naming, because both replace something that worked differently:
//
//  - Signed out, the gate is the whole app. There used to be an "Account" card at the top of the
//    control page, which left a signed-out user looking at a machine dropdown, glasses controls and a
//    Start button with every one of them inert.
//  - The tabs are at the BOTTOM, they carry icons, and they exist in every build. They used to be a
//    PrimaryTabRow at the top that only appeared in DEBUG — so a release build had no chrome at all,
//    and the Logs pane was reachable exactly when the compiler said so rather than when the user asked.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.CallController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme

@Composable
fun ConciergeScreen(ui: VoiceConciergeActivity) {
  val s by CallController.state.collectAsState()

  // The rationale dialog is hoisted above the gate so it can appear the moment sign-in completes —
  // `refreshAuthState` opens it, and that is the same recomposition that swaps the gate for the shell.
  LocationRationaleDialog(ui)

  // `&& !s.active` is not defensive padding — it is the case the onResume auth refresh exists to
  // catch. That refresh can flip `signedIn` false while the foreground service is still running a
  // call (revoked refresh token, signed out on another device), and a bare `!signedIn` gate would
  // then replace End call, Mute, Pause and the status line with a sign-in button. The call would keep
  // running with the notification as the only way to reach it. Settings already refuses to sign out
  // mid-call for the same reason; this stops the app doing involuntarily what that forbids.
  if (!ui.signedIn && !s.active) {
    SignInScreen(ui)
    return
  }

  // Hoisted rather than remembered inside each screen: a screen that leaves composition takes its
  // `remember` with it, so Home would jump back to the top every time you visited Settings.
  val homeListState = rememberLazyListState()
  val settingsListState = rememberLazyListState()

  // Lives HERE and not in HomeScreen, because the shell stays composed and a screen does not. Keyed
  // on the sign-in transition, matching what it was keyed on when this was one page: from inside
  // HomeScreen it re-ran on every Home → Settings → Home trip, and since the guard only passes when
  // the last load FAILED, that refetched on every return and wiped the error text the user was
  // reading (loadMachines opens by clearing it and writing "Loading…").
  LaunchedEffect(ui.signedIn) {
    if (ui.signedIn && ui.machines.isEmpty() && !ui.machinesFetchOk) ui.loadMachines()
  }

  var selected by rememberSaveable { mutableStateOf(SaiTab.HOME) }
  val tabs = tabsFor(ui.devMode)
  // Turning developer mode off while standing on Logs would otherwise leave a selected tab with no
  // item in the bar — a pane on screen and no way to navigate off it. See SaiTabTest.
  //
  // Rendered from the coerced value so the fallback is right on the FIRST frame, and written back so
  // `selected` never holds a tab the bar isn't showing. Without the write-back, turning developer mode
  // off and then on again teleports you to Logs from wherever you were, because the stale LOGS was
  // still sitting in the saved state. Assigning an equal value is a no-op, so this doesn't loop.
  val tab = coerceTab(selected, ui.devMode)
  LaunchedEffect(tab) { selected = tab }

  Scaffold(
      // safeDrawing, not Scaffold's default `systemBarsForVisualComponents` (status + navigation bars
      // only). The single Column this replaced used safeDrawing, which also covers a display cutout —
      // this is what keeps that. It does NOT restore the keyboard half: Scaffold uses the bottom bar's
      // measured height for the content's bottom padding whenever a bottom bar exists and drops the
      // inset's bottom entirely, so the IME is handled below instead.
      contentWindowInsets = WindowInsets.safeDrawing,
      bottomBar = {
        NavigationBar {
          tabs.forEach { t ->
            val isSelected = t == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { selected = t },
                icon = {
                  Icon(
                      if (isSelected) t.filled else t.outlined,
                      contentDescription = t.label,
                  )
                },
                label = { Text(t.label) },
                // The accent on the icon and label, but NOT on the indicator pill: a green pill is a
                // green button by another name, and Theme.kt keeps CTAs high-contrast neutral because
                // green means status. A green glyph is an accent; a green slab is a call to action.
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = SaiTheme.colors.accent,
                        selectedTextColor = SaiTheme.colors.accent,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
          }
        }
      },
  ) { innerPadding ->
    // Scaffold supplies the status bar at the top and the NavigationBar's own height (which already
    // includes the gesture-bar inset) at the bottom. The keyboard is the one thing it does not supply
    // here, for the reason in `contentWindowInsets` above — so:
    //
    //   consumeWindowInsets(innerPadding)  declares what `padding` has already dealt with,
    //   imePadding()                       then adds only the part of the IME that isn't covered.
    //
    // Without the consume, imePadding would add the full keyboard height on top of a bottom already
    // inset by the bar and push the content up by ~80dp too much. `enableEdgeToEdge()` is on and the
    // manifest sets no `windowSoftInputMode`, so nothing resizes the window for the keyboard and this
    // is the only thing keeping the two text fields (Settings' ask-first, the Logs composer) reachable.
    Box(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
    ) {
      when (tab) {
        SaiTab.HOME -> HomeScreen(ui = ui, s = s, listState = homeListState)
        SaiTab.SETTINGS -> SettingsScreen(ui = ui, s = s, listState = settingsListState)
        SaiTab.LOGS -> LogsScreen(s = s)
      }
    }
  }
}
