/*
 * sai-fi — the bottom-bar destinations, and the rule for which of them exist.
 */

// Two destinations always, three when developer mode is on. The visibility rule lives here as two pure
// functions rather than inline in the shell because it is the one piece of genuinely new logic in the
// navigation, and the failure it prevents is silent: turn developer mode off while standing on Logs
// and the selected tab no longer has a bar item, which leaves a pane on screen with no way back.
//
// Icons: filled when selected, outlined when not — the Material 3 convention, and the thing that makes
// the selected destination readable without relying on colour alone.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class SaiTab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
  /** Status, the machine picker, the glasses, and Start/Stop. Was called "Controls". */
  HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
  /** Account, the ask-first threshold, developer mode, and what build this is. */
  SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
  /** The interleaved transcript + log stream. Only exists while developer mode is on. */
  LOGS("Logs", Icons.Filled.Terminal, Icons.Outlined.Terminal),
}

/**
 * The destinations the bottom bar shows.
 *
 * Logs is hidden rather than disabled. A greyed third item in a two-item bar reads as something
 * broken; two items reads as finished.
 */
fun tabsFor(devMode: Boolean): List<SaiTab> =
    if (devMode) listOf(SaiTab.HOME, SaiTab.SETTINGS, SaiTab.LOGS)
    else listOf(SaiTab.HOME, SaiTab.SETTINGS)

/**
 * The tab to actually show, given what the user last selected and whether developer mode is still on.
 *
 * Falls back to [SaiTab.HOME] rather than to the nearest visible tab: the only way to reach a hidden
 * tab is to have turned developer mode off from Settings, and after that the useful place to be is the
 * screen you actually operate, not the one you just came from.
 */
fun coerceTab(selected: SaiTab, devMode: Boolean): SaiTab =
    if (selected in tabsFor(devMode)) selected else SaiTab.HOME
