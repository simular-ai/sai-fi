/*
 * sai-fi — type.
 */

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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

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
