# Directory — what every file is, for someone seeing this repo for the first time

This is a map, not a tutorial. It names every file and what it does in one line, so you can
find the thing you care about without reading the whole tree. For *why* the app is shaped this
way, read [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md); for the *wire contract* it must honour,
read [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md).

**One sentence on the app:** a thin Android app that puts a voice concierge on Meta Ray-Ban
glasses — it opens a Gemini Live audio session (client-side, direct) and a WebSocket to the
server (the agent link), and does almost nothing else itself.

## Where to start reading

1. [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) — the architecture, the two links, the decisions.
2. `…/saispike/CallService.kt` — the call graph; everything a live call does hangs off here.
3. `…/saispike/VoiceConciergeActivity.kt` + `…/ui/ConciergeScreen.kt` — the only screen.
4. The pure policy classes (`GreetingGate`, `ReconnectPolicy`, `HangupPolicy`, …) and their
   tests — small, side-effect-free, and the fastest way to understand a rule in isolation.

---

## Top level

| Path | What it is |
| --- | --- |
| `README.md` | Front door: what the app is, how to build it, how the tests gate CI. |
| `LICENSE` | Licence for this repo (Meta attribution included). |
| `licenses/` | The two bundled font licences (Manrope, JetBrains Mono). |
| `docs/` | This folder — the four Markdown docs described below. |
| `meta-android-app/` | **The app.** A standalone Kotlin/Gradle Android project. |
| `presenter/` | A tiny Node/TypeScript demo dashboard (DEBUG-only spectator feed). |
| `.github/workflows/android.yml` | CI: builds the app and runs the JVM unit tests. |

## docs/

| File | What it is |
| --- | --- |
| `SAI_GLASSES_APP.md` | The architecture overview — read this first. |
| `CONCIERGE_CLIENT_PROTOCOL.md` | The client half of the wire contract (endpoints, WS message tables, close codes, the device tools). Vendored here so the repo is self-contained. |
| `ON_DEVICE_CHECK.md` | A runnable checklist for verifying a build on real glasses. |
| `DIRECTORY.md` | This file. |

---

## The app — `meta-android-app/`

Gradle project. All Kotlin source lives under the package
`com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike` (abbreviated `…/saispike`
below — the path still carries the app's earlier name).

### Build & config

| Path | What it is |
| --- | --- |
| `meta-android-app/README.md` | How to build/run just the app. |
| `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts` | Gradle build scripts. |
| `gradle/libs.versions.toml` | Central dependency/version catalog. |
| `app/src/main/AndroidManifest.xml` | Permissions, the foreground service, the deep-link host. |

### App shell & UI

| File (`…/saispike/`) | What it does |
| --- | --- |
| `SaiFiApp.kt` | `Application`; initialises the Meta DAT SDK once. |
| `MainActivity.kt` | Hosts the DAT registration deep-link callback; no UI of its own. |
| `VoiceConciergeActivity.kt` | The one real screen's controller: sign-in, machine picker, glasses connect, voice settings, Start/Stop. Renders `CallController.state`; holds no call state. |
| `ui/ConciergeScreen.kt` | The Compose UI for that screen. |
| `ui/theme/Theme.kt` | Colours + typography for the app (one file — was three). |

### The call runtime

| File (`…/saispike/`) | What it does |
| --- | --- |
| `CallService.kt` | Foreground service that **owns the call graph** (audio + Gemini Live + the concierge socket + reconnect). The heart of the app. |
| `CallController.kt` | Process singleton; turns UI/gesture actions into service intents and exposes an observable `StateFlow<State>`. |
| `CallNotifications.kt` | The ongoing-call notification and the "why it ended" card (wording is the pure, testable `CallNotificationText`). |
| `AudioIo.kt` | 16 kHz capture / 24 kHz playback; glasses SCO route with phone fallback, full-duplex for barge-in. |
| `GeminiLiveClient.kt` | Raw-WebSocket Gemini Live client: setup, realtime PCM, transcripts, barge-in, function-call routing. |

### The concierge link (client ⇄ server)

| File (`…/saispike/`) | What it does |
| --- | --- |
| `ConciergeClient.kt` | HTTP calls: mint a session, list machines, recall history, upload. |
| `ConciergeSocket.kt` | The `/v1/concierge/ws` WebSocket: effects up, agent-events/speak/instruct down, reconnect, cost-guard closes. |
| `ConciergeProtocol.kt` | Kotlin port of the server's nudge/message logic (kept honest by parity fixtures). |
| `ActivityLog.kt` | Kotlin port of the server's activity-log describer; feeds `getSaiStatus` and the UI. |
| `AgentEventRouter.kt` | Routes incoming agent events to speech/log/UI. |

### Glasses (Meta DAT)

| File (`…/saispike/`) | What it does |
| --- | --- |
| `GlassesGestureSession.kt` | Temple-button gestures (tap = mute, hold/doff/fold = end). |
| `GlassesCamera.kt` | DAT photo capture for the `captureImage` tool. |
| `GlassesLink.kt` | Pure policy for the tri-state "are glasses linked?" (`true`/`false`/`checking`), so a cold-start `StateFlow` never reports a false "disconnected". |

### Pure policies (no Android; each has a unit test)

| File (`…/saispike/`) | The rule it encodes |
| --- | --- |
| `GreetingGate.kt` | Greet once per call, not on every Live reconnect. |
| `ReconnectPolicy.kt` | When to retry a dropped socket and when to give up. |
| `HangupPolicy.kt` | How/when a call ends (spoken goodbye, delays, terminal reasons). |
| `HeldNudgeQueue.kt` | Defer nudges until a turn completes; flush on barge-in. |
| `MachineSwitcher.kt` | The `switchMachine` transition without touching the live audio. |

### Support

| File (`…/saispike/`) | What it does |
| --- | --- |
| `SaiAuth.kt` | Google sign-in and Firebase ID tokens. |
| `Prefs.kt` | Small persisted prefs (e.g. last-picked machine). |
| `PhoneLocation.kt` | Reads a fresh location fix when the model asks for one. |
| `CallObserver.kt` | The seam a spectator watches a call through (`Noop…` is the release default). |

### Debug-only presenter feed

| File (`…/saispike/`) | What it does (DEBUG builds only) |
| --- | --- |
| `PresenterObserver.kt` | The `CallObserver` that owns the presenter socket. |
| `PresenterSocket.kt` | Streams call audio/log/status to the demo dashboard. |
| `WindowCapture.kt` | Mirrors the app's own window as ~3 fps JPEGs to the dashboard. |

### Resources — `app/src/main/res/`

| Path | What it is |
| --- | --- |
| `values/colors.xml`, `values/themes.xml`, `values-night/colors.xml` | Android-XML theme values (comments point back to `ui/theme/Theme.kt`). |
| `font/` | The two bundled variable fonts. |
| `drawable/`, `mipmap-*/` | Launcher icon. |

### Tests — `app/src/test/java/…/saispike/`

Fast JVM unit tests (14 classes, no device/emulator needed). Two kinds:

- **`*Test.kt`** — behaviour tests for one class each (`GlassesLinkTest`, `HangupPolicyTest`,
  `ReconnectPolicyTest`, `GreetingGateTest`, `HeldNudgeQueueTest`, `MachineSwitcherTest`,
  `AgentEventRouterTest`, `ActivityLogTest`, `ConciergeProtocolTest`,
  `CallNotificationTextTest`, `PresenterSocketTest`).
- **`*ParityTest.kt`** — replay a shared fixture to prove the Kotlin port matches the server
  (`ConciergeProtocolParityTest`, `ConciergeSocketParityTest`, `ActivityLogParityTest`).

---

## The presenter — `presenter/`

A small standalone Node/TypeScript app used only for demos; the app talks to it solely from
DEBUG builds.

| Path | What it is |
| --- | --- |
| `server.ts` | The dashboard server (receives the DEBUG presenter feed). |
| `public/index.html` | The dashboard page. |
| `package.json`, `package-lock.json`, `tsconfig.json` | Node project config. |
