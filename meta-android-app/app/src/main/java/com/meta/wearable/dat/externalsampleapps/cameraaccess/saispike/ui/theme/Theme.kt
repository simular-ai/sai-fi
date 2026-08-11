/*
 * sai-fi — theme: colours, type, and the SaiTheme wrapper.
 */

// One file for the whole theme. It was three (Color/Type/Theme); they share a package and are only
// ever used together through `SaiTheme`, so the split bought nothing but three headers.
//
// Wrap setContent in `SaiTheme` and nothing else. LIGHT BY DEFAULT, following the system:
// `isSystemInDarkTheme()` is false unless the device is in dark mode, and light matches the desktop
// Sai app. Both schemes are that app's own token sets, so dark mode is Sai's dark theme rather than an
// invention. Deliberately NOT Material You / dynamic colour — that would let the phone's wallpaper
// repaint Sai's app, which is the opposite of the point.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

// ── Colours ──────────────────────────────────────────────────────────────────────────────────────
//
// Ported from the desktop Sai app's tokens, `app/src/renderer/src/assets/base.css` (`:root` = light,
// `.dark` = dark). Each value names the CSS variable it came from, so drift is greppable.
//
// The app previously rendered under a bare `darkColorScheme()` — the STOCK Material baseline, i.e.
// Google's purple, with nothing of Sai's brand in it.
//
// Two rules for anything added here:
//
//  1. `primary` is near-black / near-white, NOT the Sai green. The desktop app's CTAs are
//     high-contrast neutral and green means status. Green buttons would be louder than the desktop
//     app, not more consistent with it.
//  2. EVERY ColorScheme role must be set. Anything omitted falls back to the Material baseline and
//     only shows up when something rare draws with it — a snackbar action, a tertiary container —
//     which in practice means on stage. Only the roles below are Sai's; the rest of the palette is
//     the same handful of tokens reused, which is why the two schemes look repetitive.

private object L {
  val background = Color(0xFFF9FAF5) // --background
  val foreground = Color(0xFF1A1A1E) // --foreground
  val surface = Color(0xFFF5F6F1) // --surface
  val card = Color(0xFFFFFFFF) // --card
  val cardHover = Color(0xFFE8E8E5) // --card-hover
  val primary = Color(0xFF0C0C0C) // --primary
  val primaryFg = Color(0xFFFAFAFA) // --primary-foreground
  val secondary = Color(0xFFEFEFED) // --secondary / --muted / --accent
  val mutedFg = Color(0xFF6B6B73) // --muted-foreground
  val border = Color(0xFFE2E2DF) // --border
  val borderStrong = Color(0xFFD0D0CC) // --border-strong
  val destructive = Color(0xFFDC2626) // --destructive
}

private object D {
  val background = Color(0xFF0C0C0C)
  val foreground = Color(0xFFFAFAFA)
  val surface = Color(0xFF111111)
  val card = Color(0xFF1A1A1A)
  val cardHover = Color(0xFF202020)
  val primary = Color(0xFFFAFAFA)
  val primaryFg = Color(0xFF0C0C0C)
  val secondary = Color(0xFF1A1A1A)
  val mutedFg = Color(0xFFA3A3A3)
  val border = Color(0xFF1F1F1F)
  val borderStrong = Color(0xFF2A2A2A)
  // base.css states these in OKLCH; converted to sRGB rather than approximated by eye.
  val destructive = Color(0xFF82181A) // oklch(0.396 0.141 25.723)
  val destructiveFg = Color(0xFFFB2C36) // oklch(0.637 0.237 25.331)
}

/** `--success` / `--logo-bg` — the same green as the launcher icon. Mode-independent. */
private val Green = Color(0xFF16D342)
/** `--warning`. Mode-independent. */
private val Amber = Color(0xFFFFAB3C)
/**
 * `--brand` — links and highlights.
 *
 * The only token that has to DIVERGE from base.css. The desktop app uses #0022FF in both modes, and
 * on this app's dark background that measures 2.49:1 contrast — below WCAG AA, and in practice
 * legible only as "something blue is there". [BlueDark] is the same hue (OKLCH 264°) lifted to
 * L=0.70, which lands at 7.16:1 on #0C0C0C — parity with the 7.49:1 the light value gets on #F9FAF5.
 */
private val Blue = Color(0xFF0022FF)
private val BlueDark = Color(0xFF6B9AFF)

// Material 3's `Card` draws from surfaceContainerLow, so pointing the low end at `--card` is what
// lands a Card on the desktop app's card colour instead of an M3-derived tint.
val SaiLightColors: ColorScheme =
    lightColorScheme(
        primary = L.primary,
        onPrimary = L.primaryFg,
        primaryContainer = L.secondary,
        onPrimaryContainer = L.foreground,
        inversePrimary = D.primary,
        secondary = L.secondary,
        onSecondary = L.foreground,
        secondaryContainer = L.secondary,
        onSecondaryContainer = L.foreground,
        tertiary = Blue,
        onTertiary = Color.White,
        tertiaryContainer = L.secondary,
        onTertiaryContainer = Blue,
        background = L.background,
        onBackground = L.foreground,
        surface = L.background,
        onSurface = L.foreground,
        surfaceVariant = L.secondary,
        onSurfaceVariant = L.mutedFg,
        surfaceTint = L.primary,
        surfaceBright = L.card,
        surfaceDim = L.cardHover,
        surfaceContainerLowest = L.card,
        surfaceContainerLow = L.card,
        surfaceContainer = L.surface,
        surfaceContainerHigh = L.cardHover,
        surfaceContainerHighest = L.cardHover,
        inverseSurface = D.background,
        inverseOnSurface = D.foreground,
        outline = L.borderStrong,
        outlineVariant = L.border,
        error = L.destructive,
        onError = Color.White,
        errorContainer = L.secondary,
        onErrorContainer = L.destructive,
        scrim = Color.Black,
    )

val SaiDarkColors: ColorScheme =
    darkColorScheme(
        primary = D.primary,
        onPrimary = D.primaryFg,
        primaryContainer = D.secondary,
        onPrimaryContainer = D.foreground,
        inversePrimary = L.primary,
        secondary = D.secondary,
        onSecondary = D.foreground,
        secondaryContainer = D.secondary,
        onSecondaryContainer = D.foreground,
        tertiary = BlueDark,
        onTertiary = D.background,
        tertiaryContainer = D.secondary,
        onTertiaryContainer = D.foreground, // Blue on #1a1a1a fails contrast; use the fg tone
        background = D.background,
        onBackground = D.foreground,
        surface = D.background,
        onSurface = D.foreground,
        surfaceVariant = D.secondary,
        onSurfaceVariant = D.mutedFg,
        surfaceTint = D.primary,
        surfaceBright = D.cardHover,
        surfaceDim = D.background,
        surfaceContainerLowest = D.surface,
        surfaceContainerLow = D.card,
        surfaceContainer = D.card,
        surfaceContainerHigh = D.cardHover,
        surfaceContainerHighest = D.cardHover,
        inverseSurface = L.background,
        inverseOnSurface = L.foreground,
        outline = D.borderStrong,
        outlineVariant = D.border,
        error = D.destructiveFg, // the -foreground tone is the legible one on a dark surface
        onError = Color.White,
        errorContainer = D.destructive,
        onErrorContainer = D.destructiveFg,
        scrim = Color.Black,
    )

/**
 * Filled-destructive pair for the Stop button.
 *
 * Separate from `colorScheme.error` because one token can't serve both jobs: `error` also colours the
 * chip's reconnecting dot, which sits on a dark surface and needs to be BRIGHT (#FB2C36), whereas the
 * same red as a large filled button is glaring. [DangerDark] is that red pulled down to #A82824 —
 * 6.72:1 against its own label and still unmistakably a red button.
 */
private val Danger = Color(0xFFDC2626) // --destructive (light)
private val DangerDark = Color(0xFFA82824)

/**
 * The few semantic colours Material 3 has no role for. Reach for these rather than bending
 * `tertiary` or `error` into meaning something else.
 *
 * Deliberately only what the UI actually uses — this started as eight fields and half were never
 * read. Add one when a screen needs it, not in anticipation.
 */
@Immutable
data class SaiExtendedColors(
    val success: Color,
    val warning: Color,
    val brand: Color,
    val border: Color,
    /** Filled destructive button background. */
    val danger: Color,
    /** Label on [danger]. */
    val onDanger: Color,
)

val SaiLightExtended =
    SaiExtendedColors(
        success = Green,
        warning = Amber,
        brand = Blue,
        border = L.border,
        danger = Danger,
        onDanger = L.background,
    )

val SaiDarkExtended =
    SaiExtendedColors(
        success = Green,
        warning = Amber,
        brand = BlueDark,
        border = D.border,
        danger = DangerDark,
        onDanger = D.foreground,
    )

// ── Type ─────────────────────────────────────────────────────────────────────────────────────────
//
// Manrope (UI) and JetBrains Mono (logs), the same two faces the desktop Sai app uses. The TTFs in
// res/font are converted from the very `@fontsource-variable` woff2 files that app ships, so both
// render the same outlines — not a same-named font from a different source. Bundled rather than
// fetched via downloadable-fonts: a demo must not depend on a font download.
//
// Both are VARIABLE fonts (Manrope wght 200–800, JetBrains Mono 100–800), so each weight is one file
// instanced at a different point on the axis rather than a separate file.
//
// EVERY Material 3 slot is set. An unset slot silently keeps Roboto — and M3 pulls from slots you
// never name directly (AlertDialog titles come from headlineSmall, Button labels from labelLarge,
// unstyled Text from bodyLarge), so a partial ramp shows up as one stray Roboto heading that nobody
// notices until it's on a projector. Sizes come from the desktop app's `--text-v2-*` tokens; the
// slots below the line have no Sai equivalent and only need the right family.

// FontVariation is still @ExperimentalTextApi. Opted in knowingly: the alternative is shipping a
// static file per weight, which is several times the bytes for the same result.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) =
    Font(resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val Manrope =
    FontFamily(
        variable(R.font.manrope_variable, FontWeight.Normal),
        variable(R.font.manrope_variable, FontWeight.Medium),
        variable(R.font.manrope_variable, FontWeight.SemiBold),
        variable(R.font.manrope_variable, FontWeight.Bold),
    )

/** The log/transcript stream, where columns must line up. */
val JetBrainsMono =
    FontFamily(
        variable(R.font.jetbrains_mono_variable, FontWeight.Normal),
        variable(R.font.jetbrains_mono_variable, FontWeight.Medium),
    )

private fun ramp(size: Double, lineHeight: Double, weight: FontWeight) =
    TextStyle(fontFamily = Manrope, fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight)

private val M3 = Typography()

private fun TextStyle.manrope() = copy(fontFamily = Manrope)

/**
 * Sizes are in `sp`, so they scale with the user's font-size setting. That's why the section cards
 * carry no fixed heights — at max font scale a pinned height clips.
 */
val SaiTypography =
    Typography(
        // Sai's ramp. `--text-v2-body` is weight 450, which has no M3 constant; Normal is the closest
        // and the variable axis makes the difference negligible at that size.
        displaySmall = ramp(32.0, 38.4, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em), // display
        titleLarge = ramp(20.0, 26.0, FontWeight.SemiBold), // h1
        titleMedium = ramp(18.0, 25.2, FontWeight.SemiBold), // h2
        titleSmall = ramp(16.0, 24.0, FontWeight.SemiBold), // h3
        bodyLarge = ramp(15.5, 25.6, FontWeight.Normal), // body
        bodyMedium = ramp(13.0, 19.5, FontWeight.Normal), // body-sm
        bodySmall = ramp(12.0, 18.0, FontWeight.Normal), // meta
        labelLarge = ramp(13.0, 19.5, FontWeight.Medium), // ui — Buttons, Tabs, field labels
        labelMedium = ramp(12.0, 18.0, FontWeight.Medium),
        labelSmall = ramp(11.0, 16.0, FontWeight.Medium),
        // No Sai equivalent — keep M3's metrics, just not M3's font.
        displayLarge = M3.displayLarge.manrope(),
        displayMedium = M3.displayMedium.manrope(),
        headlineLarge = M3.headlineLarge.manrope(),
        headlineMedium = M3.headlineMedium.manrope(),
        headlineSmall = M3.headlineSmall.manrope(),
    )

// ── Theme ────────────────────────────────────────────────────────────────────────────────────────

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
