/*
 * sai-fi — user authentication (Google Sign-In via Credential Manager → Firebase ID token).
 */

// SaiAuth — the app's user credential for cloud-api. The user signs in with Google through the Jetpack
// **Credential Manager** (the system credential sheet); Firebase Auth turns that into a session, and we
// send a fresh Firebase **ID token** as the `Authorization: Bearer` on every cloud-api call. cloud-api's
// authMiddleware verifies the ID token exactly as for the web/desktop app — no compiled-in key.
//
// Setup (once, in local.properties — values from the simular Firebase project): firebase_app_id,
// firebase_api_key, firebase_project_id, web_client_id (the *Web* OAuth client id, passed as the Google
// ID option's server client id so Firebase accepts the token). The Android package + signing SHA-1 must
// be registered in that Firebase project. Firebase is initialized manually from these (no
// google-services.json / Google-Services Gradle plugin).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import kotlinx.coroutines.tasks.await

object SaiAuth {
  /** True once the Firebase config is present (local.properties filled in). */
  val isConfigured: Boolean
    get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.WEB_CLIENT_ID.isNotBlank()

  /** Initialize Firebase from local.properties config. Idempotent; no-op if unconfigured. */
  fun initialize(context: Context) {
    if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
    runCatching {
      FirebaseApp.initializeApp(
          context,
          FirebaseOptions.Builder()
              .setApplicationId(BuildConfig.FIREBASE_APP_ID)
              .setApiKey(BuildConfig.FIREBASE_API_KEY)
              .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
              .build(),
      )
    }
  }

  private fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

  fun isSignedIn(): Boolean = runCatching { auth().currentUser != null }.getOrDefault(false)

  fun email(): String? = runCatching { auth().currentUser?.email }.getOrNull()

  /**
   * Show the system Google sign-in sheet (Credential Manager), then exchange the returned Google ID
   * token for a Firebase session. Suspends until the user finishes; throws on cancellation/failure or
   * an unexpected credential type (the caller reports it). [context] must be an Activity context — the
   * sheet is UI.
   */
  suspend fun signInWithGoogle(context: Context) {
    val googleIdOption =
        GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false) // let the user pick any account, not just prior ones
            .setAutoSelectEnabled(true) // one-tap re-sign-in when there's a single known account
            .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
    val result = CredentialManager.create(context).getCredential(context, request)
    val credential = result.credential
    if (credential !is CustomCredential ||
        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
      error("Unexpected credential type: ${credential.type}")
    }
    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
    auth().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
  }

  /**
   * A **fresh** Firebase ID token for the Bearer header, or null if signed out / unconfigured. Fetch one
   * per cloud-api call that starts a session (mint + WS + reconnect re-mint) — ID tokens expire ~1h and
   * `getIdToken()` auto-refreshes when needed.
   */
  suspend fun idToken(): String? =
      // `false` = don't force a network round-trip; the SDK still refreshes an expired token by itself,
      // which is what every caller wants. This was a `forceRefresh` parameter that all seven call sites
      // left at its default.
      runCatching { auth().currentUser?.getIdToken(false)?.await()?.token }.getOrNull()

  /** Sign out of Firebase and clear the Credential Manager selection so the next sign-in re-prompts. */
  suspend fun signOut(context: Context) {
    runCatching { auth().signOut() }
    runCatching {
      CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
  }
}
