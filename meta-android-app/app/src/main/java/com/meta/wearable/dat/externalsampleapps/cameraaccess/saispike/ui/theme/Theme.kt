/*
 * sai-fi — theme.
 */

// Wrap setContent in this and nothing else.
//
// LIGHT BY DEFAULT, following the system: `isSystemInDarkTheme()` is false unless the device is in
// dark mode, and light matches the desktop Sai app. Both schemes are that app's own token sets, so
// dark mode is Sai's dark theme rather than an invention.
//
// Deliberately NOT Material You / dynamic colour — that would let the phone's wallpaper repaint Sai's
// app, which is the opposite of the point.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun SaiTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()

  // Without this the system bars keep whatever icon polarity the previous theme left behind, which
  // reads as white icons on a light background in one of the two modes.
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      WindowCompat.getInsetsController((view.context as Activity).window, view).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
      }
    }
  }

  MaterialTheme(
      colorScheme = if (dark) SaiDarkColors else SaiLightColors,
      typography = SaiTypography,
      content = content,
  )
}

/**
 * The handful of semantic colours Material 3 has no role for, read as `SaiTheme.colors.success`
 * alongside `MaterialTheme.colorScheme`.
 *
 * Derived from the system setting rather than carried in a CompositionLocal: with one theme and no
 * override, a provider was ceremony around a value that is already available anywhere.
 */
object SaiTheme {
  val colors: SaiExtendedColors
    @Composable get() = if (isSystemInDarkTheme()) SaiDarkExtended else SaiLightExtended
}
