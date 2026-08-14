/*
 * sai-fi — the sign-in gate.
 */

// The whole screen when nobody is signed in, and the only thing reachable from it. No tabs, no bottom
// bar, no scroll.
//
// It replaces an "Account" card that was the first of four on the control page. That arrangement left
// a signed-out user looking at the entire control surface — machine dropdown, glasses controls, Start
// — with every one of them inert, which reads as a broken app rather than as one you haven't signed
// into. Nothing here is gated on anything, because there is nothing here but the one action.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.SaiAuth
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceConciergeActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ui.theme.SaiTheme

/**
 * Google's own button colours, from their Identity brand guidelines.
 *
 * Hardcoded rather than routed through `colorScheme`, which is the one place in this app that should
 * happen: this button is Google's chrome sitting inside ours, and the guidelines fix its container,
 * label and border in both modes. Bending it to the Sai palette would make it a Sai button that says
 * "Google", which is exactly what the guidelines exist to prevent.
 */
private object GoogleButton {
  val lightContainer = Color(0xFFFFFFFF)
  val lightLabel = Color(0xFF1F1F1F)
  val lightBorder = Color(0xFF747775)
  val darkContainer = Color(0xFF131314)
  val darkLabel = Color(0xFFE3E3E3)
  val darkBorder = Color(0xFF8E918F)
}

@Composable
fun SignInScreen(ui: VoiceConciergeActivity) {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // The launcher artwork, which already carries its own green tile and white mark — so it is drawn
      // as-is, with no tint. Clipped rather than padded: the PNG is a full-bleed adaptive-icon
      // foreground, and the 24dp radius is what makes it read as the app icon the user just tapped
      // instead of a photo of one.
      Image(
          painter = painterResource(R.drawable.ic_launcher_foreground),
          contentDescription = null,
          modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
      )
      Spacer(Modifier.height(20.dp))
      Text("Sai-Fi", style = MaterialTheme.typography.displaySmall)
      Spacer(Modifier.height(12.dp))
      // The one accent flourish on this screen. A rule rather than coloured text: at display size the
      // title carries itself, and green type here would be the loudest thing in the app.
      Box(Modifier.width(56.dp).height(2.dp).background(SaiTheme.colors.accent))
      Spacer(Modifier.height(12.dp))
      Text(
          "Sai on your glasses.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(36.dp))

      // A build with no Firebase config cannot sign in at all, and behind a full-screen gate that is
      // a dead end rather than one dud card. The old Account section hid its only explanation in
      // exactly this case (the helper line was `&& SaiAuth.isConfigured`), which was survivable when
      // the rest of the page was still there. Say it outright instead, and name the four keys — the
      // person hitting this is whoever just cloned the repo.
      if (SaiAuth.isConfigured) {
        GoogleSignInButton(onClick = { ui.signIn() })
      } else {
        GoogleSignInButton(onClick = {}, enabled = false)
        Spacer(Modifier.height(12.dp))
        Text(
            "This build has no Firebase configuration. Set firebase_app_id, firebase_api_key, " +
                "firebase_project_id and web_client_id in local.properties, then rebuild.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
      }

      // Sign-in failures are worth the full text: the most common one on a fresh clone is Google
      // rejecting the debug keystore's SHA-1 because it isn't registered in the Firebase project, and
      // that is unguessable from "sign-in failed".
      Spacer(Modifier.height(16.dp))
      SectionErrorAffordance(
          title = "Sign-in error",
          message = ui.authError,
          open = ui.authErrorOpen,
          onOpen = { ui.authErrorOpen = true },
          onDismiss = { ui.authErrorOpen = false },
      )
    }
  }
}

/**
 * "Sign in with Google", to Google's spec: their container/label/border per mode, their mark at 18dp,
 * a pill, and a 40dp minimum height.
 *
 * One deliberate deviation: the label is Manrope (`labelLarge`), not the Roboto Medium the guidelines
 * name. This app ships two variable fonts and no Roboto, and adding a third face for one button's
 * label is not worth the bytes — the substitution is what almost every app using this button does.
 */
@Composable
private fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean = true) {
  val dark = isSystemInDarkTheme()
  val container = if (dark) GoogleButton.darkContainer else GoogleButton.lightContainer
  val label = if (dark) GoogleButton.darkLabel else GoogleButton.lightLabel
  val border = if (dark) GoogleButton.darkBorder else GoogleButton.lightBorder
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = RoundedCornerShape(999.dp),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = container,
              contentColor = label,
              // Google's guidelines have no disabled state for this button, so hold its own colours
              // at reduced alpha rather than falling through to M3's grey — the reason it's disabled
              // is spelled out directly underneath, and a grey slab reads as a loading state.
              disabledContainerColor = container.copy(alpha = 0.45f),
              disabledContentColor = label.copy(alpha = 0.45f),
          ),
      border = BorderStroke(1.dp, border.copy(alpha = if (enabled) 1f else 0.45f)),
      elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
      // widthIn BEFORE fillMaxWidth: cap the available width, then fill it. The other order fixes the
      // width to the parent's first and then hands the cap a min it already exceeds.
      modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 40.dp),
  ) {
    Icon(
        painter = painterResource(R.drawable.ic_google_g),
        contentDescription = null,
        // Tint explicitly unset: the mark is four fixed brand colours and Icon would otherwise
        // repaint the whole thing in `contentColor`, which is a guideline violation.
        tint = Color.Unspecified,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(12.dp))
    Text("Sign in with Google", style = MaterialTheme.typography.labelLarge)
  }
}
