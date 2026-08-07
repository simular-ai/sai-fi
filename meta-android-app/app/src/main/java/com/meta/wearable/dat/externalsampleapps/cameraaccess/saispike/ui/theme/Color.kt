/*
 * sai-fi — colours.
 */

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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

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
