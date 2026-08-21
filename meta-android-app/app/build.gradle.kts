/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

// local.properties (gitignored) — holds the SDK dir, GitHub token, the voice-concierge config
// (sai_api_url, optional sai_version_tag), and the Firebase sign-in config (firebase_app_id,
// firebase_api_key, firebase_project_id, web_client_id). Never commit these values.
val localProperties =
    Properties().apply {
      val localPropertiesFile = rootProject.file("local.properties")
      if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  /**
   * The shipped voice profile, on the unit-test classpath.
   *
   * `VoiceProfileTest` used to grade a COPY of it vendored under `test/resources/parity/`, which is
   * exactly how it went stale: the copy still declared `approveAlways` and told the model to offer
   * "always allow" months after the product retired both, and the test that exists to catch a
   * dropped prompt block was reading a file nothing ships. A test about the shipped artefact reads
   * the shipped artefact.
   */
  sourceSets { getByName("test") { resources.srcDir("src/main/assets") } }

  defaultConfig {
    applicationId = "ai.simular.saiglasses"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Meta Wearables Device Access Toolkit registration, from local.properties. The client token is a
    // CREDENTIAL and this app is open-source, so it is never committed — it used to be hardcoded in
    // AndroidManifest.xml while these placeholders sat here unused and empty. Get both from the app
    // registered in the Wearables Developer Center. Empty builds fine; Meta AI just won't grant a
    // device session.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] = localProperties.getProperty("mwdat_client_token", "")

    // Your own Gemini API key. The app opens the Live session DIRECTLY with it — there is no
    // server-minted ephemeral token any more, and audio never touches cloud-api. Compiled in exactly
    // like presenter_key: fine for a build you run yourself, not for one you hand out or publish,
    // because the key travels with the binary. Empty = voice cannot start, and the app says so.
    buildConfigField(
        "String", "GEMINI_API_KEY", "\"${localProperties.getProperty("gemini_api_key", "")}\"")
    // Sai API base, from local.properties. Production is the default. An empty value (sai_api_url=
    // with nothing after the equals) compiles, then fails at runtime with `no sai_api_url` rather
    // than a mysterious network error. This reaches the AGENT; the voice conversation itself needs
    // nothing from it.
    buildConfigField(
        "String",
        "SAI_API_URL",
        "\"${localProperties.getProperty("sai_api_url", "https://api.sai.simular.ai")}\"",
    )
    // Optional. Sent as `x-sai-version` on every Sai API call so a gateway can route to one server
    // revision. Empty = whatever that host serves by default. Inert against a host that does not
    // honour the header.
    buildConfigField("String", "SAI_VERSION_TAG", "\"${localProperties.getProperty("sai_version_tag", "")}\"")
    // Presenter feed (DEBUG builds only — see CallService): the phone publishes the live call (audio,
    // conversation text, logs, glasses photos) to a laptop dashboard so an audience can hear and read
    // it, since Sai's replies otherwise only reach the wearer's glasses speaker. Run the server with
    // `cd presenter && npm run presenter`; it prints the exact values to paste here. Leave
    // presenter_url empty and it is derived from sai_api_url's host when that host is a LAN/dev
    // address, so pointing the app at your laptop is enough. Empty + non-local sai_api_url = off.
    buildConfigField("String", "PRESENTER_URL", "\"${localProperties.getProperty("presenter_url", "")}\"")
    buildConfigField("String", "PRESENTER_KEY", "\"${localProperties.getProperty("presenter_key", "")}\"")
    // Firebase config for in-app Google Sign-In (from local.properties). Sign-in yields a Firebase
    // ID token the app sends as the Bearer to the Sai API — no compiled-in credential. Empty
    // defaults compile fine; sign-in just won't work until they're set.
    buildConfigField("String", "FIREBASE_APP_ID", "\"${localProperties.getProperty("firebase_app_id", "")}\"")
    buildConfigField("String", "FIREBASE_API_KEY", "\"${localProperties.getProperty("firebase_api_key", "")}\"")
    buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${localProperties.getProperty("firebase_project_id", "")}\"")
    buildConfigField("String", "WEB_CLIENT_ID", "\"${localProperties.getProperty("web_client_id", "")}\"")
  }

  // Declared BEFORE buildTypes: these blocks run in source order, so a release buildType that reads
  // signingConfigs must come after them.
  signingConfigs {
    // Debug: the keystore from Meta's public sample, committed on purpose. Meta AI's app registration
    // binds to a signature, so a STABLE debug signature is a requirement — a per-machine
    // auto-generated one would break registration on every fresh checkout.
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }

    // Release: from local.properties (gitignored), and only when configured. Absent keys → no
    // "release" config at all → AGP emits `app-release-unsigned.apk`, which no device will install.
    localProperties.getProperty("release_store_file")?.let { storePath ->
      create("release") {
        storeFile = file(storePath)
        storePassword = localProperties.getProperty("release_store_password")
        keyAlias = localProperties.getProperty("release_key_alias")
        keyPassword = localProperties.getProperty("release_key_password")
      }
    }
  }

  buildTypes {
    release {
      // R8 is OFF deliberately. `proguard-rules.pro` was comments-only, so minification ran with no
      // keep rules against three reflection-heavy dependencies (Firebase, the DAT SDK, org.json) —
      // a latent runtime crash dressed up as an optimisation. Writing real rules is out of scope;
      // running R8 empty is worse than not running it.
      isMinifyEnabled = false

      // NEVER the debug config. This used to be `signingConfigs.getByName("debug")` — i.e. the
      // committed `sample.keystore` with password "sample" — so every release APK was signed with a
      // key published in Meta's sample repo. `findByName` yields null when release_* keys are unset,
      // and an unsigned APK is an obvious, uninstallable failure rather than a silent one.
      signingConfig = signingConfigs.findByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  // Icons for the call controls. The version catalog already carried this entry but the module
  // never depended on it, so the UI used emoji instead — which don't take a tint, don't scale
  // with the type ramp, and render differently on every OEM skin. R8 strips the unused ones
  // from release builds.
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.okhttp)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.play.services)
  // The FSM's Mutex. Already on the classpath transitively, declared explicitly because the voice
  // concierge state machine depends on it directly: it serialises every input through one lock, and
  // an implicit dependency is one bump of an unrelated library away from disappearing.
  implementation(libs.kotlinx.coroutines.core)
  // FusedLocationProviderClient, for "what's the weather / what's near me". The framework
  // LocationManager would avoid this dependency but is markedly worse at producing a fix indoors or
  // in a pocket, which is where a glasses user asking a question usually is.
  implementation(libs.play.services.location)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  // JVM unit tests for the pure ports (ConciergeProtocol/ActivityLog). org.json ships a real impl so
  // JSONObject works off-device (the android.jar one is a throwing stub in unit tests).
  testImplementation(libs.junit)
  testImplementation(libs.json)
  // Virtual-time control for the GlassesLink settle window — the bug it guards is a StateFlow that
  // emits synchronously on subscribe, which only a coroutine test can reproduce.
  testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Let the behavioural eval see its environment.
 *
 * The judged tiers are opt-in and cost model quota, so each reads its own switch and a key from the
 * environment and skips itself without them: `LoopEvalTest` on `SAI_CONVERSATION_EVAL`,
 * `TranscriptEvalTest` on `SAI_TRANSCRIPT_EVAL`. Forwarded EXPLICITLY rather than left to the
 * Gradle daemon's inherited environment: a daemon started before you exported the variables carries
 * the old ones, so the eval reports "skipped" with nothing on screen to say why — which reads as a
 * broken test rather than a switch that is off.
 *
 *   SAI_CONVERSATION_EVAL=1 GEMINI_API_KEY=… \
 *     ./gradlew :app:testDebugUnitTest --tests "*LoopEvalTest*"
 */
tasks.withType<Test>().configureEach {
  listOf(
          "SAI_CONVERSATION_EVAL",
          "SAI_TRANSCRIPT_EVAL",
          // Rewriting the golden fixtures. Off in CI, and that is the point: a golden set that
          // regenerates itself on every run cannot detect drift. See RegenerateGoldensTest.
          "SAI_REGEN_GOLDENS",
          "GEMINI_API_KEY",
          "GOOGLE_API_KEY",
          "EVAL_MODEL",
          "JUDGE_MODEL",
          "EVAL_ONLY",
          "EVAL_PRINT",
          // The live-agent contract tier, which talks to a real cloud-api and bills a real agent.
          "SAI_LIVE_AGENT",
          "SAI_API_URL",
          "SAI_MACHINE_ID",
          "SAI_ID_TOKEN",
          // Mirroring a harness conversation to the presenter, so a test can be watched.
          "SAI_PRESENTER",
          "SAI_PRESENTER_PACE_MS",
          // The demo flow, which drives a real agent from a real model.
          "SAI_DEMO",
      )
      .forEach { name -> System.getenv(name)?.let { environment(name, it) } }
  // The eval's output IS its result — a scorecard, not an assertion count — so it has to reach the
  // terminal. Only while it is running: on the ordinary suite this would bury the JVM tests in noise.
  // (A full run makes dozens of model calls and waits out per-minute rate limits between them, so it
  // takes minutes. Gradle sets no default test timeout, so there is nothing to raise.)
  if (System.getenv("SAI_CONVERSATION_EVAL") == "1" || System.getenv("SAI_TRANSCRIPT_EVAL") == "1" ||
      System.getenv("SAI_PRESENTER") == "1" || System.getenv("SAI_DEMO") == "1") {
    testLogging { showStandardStreams = true }
  }
}
