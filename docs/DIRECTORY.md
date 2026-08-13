# Directory — what every file is, for someone seeing this repo for the first time

This is a map, not a tutorial. It names every file and what it does in one line, so you can
find the thing you care about without reading the whole tree. For *why* the app is shaped this
way, read [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md); for the *wire contract* it must honour,
read [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md).

**One sentence on the app:** an Android app that puts a voice concierge on Meta Ray-Ban glasses — it
opens a Gemini Live audio session directly with the user's own key, runs the conversation's state
machine itself, and talks to Sai's cloud-api only to reach the agent.

## Where to start reading

1. [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) — the architecture, the two links, the decisions.
2. `…/saispike/CallService.kt` — the call graph; everything a live call does hangs off here.
3. `…/saispike/VoiceConciergeActivity.kt` + `…/ui/` — the one Activity and the four screens it hosts.
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
| `ON_DEVICE_CHECK.md` | A runnable checklist for verifying a build on real glasses — eight checks, each naming what it exercises and how it fails. |
| `VOICE_FSM.md` | The design of the conversation state machine this app owns — modes, effects, the admission rule, the races, and why each rule exists. Read before changing anything under `fsm/`. |
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
| `VoiceConciergeActivity.kt` | The UI's controller **and** its state: sign-in, the machine list, glasses registration, the two persisted settings, Start/Stop. Renders `CallController.state`; holds no call state. |
| `ui/ConciergeScreen.kt` | The shell, and nothing else: the sign-in gate, then a `Scaffold` with the bottom `NavigationBar`. ~110 lines — it used to be the whole UI at 952. |
| `ui/SignInScreen.kt` | The gate. Signed out, this is the entire app: logo, title, one Google button. |
| `ui/HomeScreen.kt` | Status chip and three cards: **Connection** (registration, glasses link, audio route), **Machine** (picker + reload), **Call** (all four controls, always visible; Start and End share the same slot). Was the "Controls" tab. |
| `ui/SettingsScreen.kt` | Account + sign-out, the ask-first threshold (stepper + typable field, committed on Done/blur), the developer-mode switch, and which build this is. |
| `ui/LogsScreen.kt` | The interleaved transcript + log stream and the text composer. Only reachable with developer mode on. |
| `ui/SaiTab.kt` | The three bottom-bar destinations, plus the pure rule for which exist (`tabsFor` / `coerceTab`). |
| `ui/SaiComponents.kt` | The pieces every screen shares: `Section`, `GroupHeader`, `Hint`, `CallStatusChip`, `CaptureThumbnail`, the error dialog, the location rationale. |
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
| `ConciergeClient.kt` | HTTP calls: list machines, recall history, upload. |
| `VoiceChannelClient.kt` | The `/v1/agents/*` surface: `POST /message` (whose response IS the turn's stream), `abort` / `new-session` / `approve`, and the translation from the AI-SDK stream vocabulary into `AgentEvent`. |
| `VoiceSession.kt` | One call's concierge: the FSM, its two ports, the SSE reader, reconnect, and the cost guard. Replaces the old WebSocket. |
| `HttpAgentBridge.kt` | The FSM's `AgentBridge` over HTTP, plus the photo stash and the location line folded into a task's text. |
| `VoiceConverters.kt` | Typed agent events back to the JSON that `ActivityLog` and `AgentEventRouter` read. |
| `ConciergeProtocol.kt` | Kotlin port of the server's nudge logic (kept honest by parity fixtures). |
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

### The conversation state machine — `…/saispike/fsm/`

What happens between the user speaking and the agent working, and back. Ported from the server; see
[`VOICE_FSM.md`](VOICE_FSM.md) for why each rule exists. Everything except `Concierge.kt` is pure —
no coroutines, no clock, no I/O — which is what makes the golden catalog runnable as JVM tests.

**This drives every call.** `CallService.buildConcierge` constructs a `VoiceSession` per call, the
model's tool calls go into `applyEffects`, and the WebSocket path it replaced is deleted.

| File (`…/saispike/fsm/`) | What it does |
| --- | --- |
| `State.kt` | The state, the modes, and the pure transitions over them. |
| `Effects.kt` | The 15 things the model is allowed to make happen, and the parse boundary that drops anything else. |
| `Ports.kt` | `AgentBridge` and `VoiceChannel` — the two seams — plus the agent event union. |
| `Concierge.kt` | The orchestrator: one `Mutex`, the dispatch loop, the queue drain, the session projection. |
| `AgentIngest.kt` | What an agent event means regardless of what the model says about it. |
| `EffectCtx.kt` | The whole surface a handler gets; `state` is a live alias, not a copy. |
| `TaskHandlers.kt` | `forwardToAgent` / `relayToAgent` — the admission rule. |
| `ApprovalHandlers.kt` | `approve` / `deny` / `chooseOption`, including the offered-value guard. |
| `QueueHandlers.kt` | `enqueue` / `sendQueuedNow` / `cancelQueued` — all list edits, because the queue never leaves the device. |
| `InterruptHandler.kt` | `interrupt` (the one-shot scope question) and `resetSession`. |
| `CostGuard.kt` | The two bounds on what an open microphone can cost. |
| `Speech.kt` | Every line the FSM produces — `say` (verbatim) and `instruct` (model-only). |
| `VoiceProfile.kt` | Loads `assets/voice-profile.json`: the system prompt, the tool declarations, the model and the voice. Ships with the app, because no server delivers them any more. |

### Support

| File (`…/saispike/`) | What it does |
| --- | --- |
| `SaiAuth.kt` | Google sign-in and Firebase ID tokens. |
| `Prefs.kt` | Small persisted prefs: the last-picked machine, the two Settings values (developer mode, ask-first seconds), and the one-shot permission-prompt flags. |
| `PhoneLocation.kt` | Reads a fresh location fix when the model asks for one. |
| `CallObserver.kt` | The seam a spectator watches a call through (`Noop…` is the release default). |

### Debug-only presenter feed

| File (`…/saispike/`) | What it does (DEBUG builds only) |
| --- | --- |
| `PresenterObserver.kt` | The `CallObserver` that owns the presenter socket. |
| `PresenterSocket.kt` | Streams call audio/log/status to the demo dashboard. |
| `WindowCapture.kt` | Mirrors the app's own window as ~3 fps JPEGs to the dashboard. |

### Assets — `app/src/main/assets/`

| Path | What it is |
| --- | --- |
| `voice-profile.json` | The system prompt (41 blocks), the 18 tool declarations, the model and the voice. **Generated** from the server's source before it was deleted — the wording is load-bearing, so it was never retyped. The same bytes are vendored to `app/src/test/resources/parity/prompt-and-tools.json` and read by cloud-api's eval. |

### Resources — `app/src/main/res/`

| Path | What it is |
| --- | --- |
| `values/colors.xml`, `values/themes.xml`, `values-night/colors.xml` | Android-XML theme values (comments point back to `ui/theme/Theme.kt`). |
| `font/` | The two bundled variable fonts. |
| `drawable/` | Launcher icon foreground (reused as the sign-in screen's logo) and `ic_google_g.xml` — Google's mark for the sign-in button, whose four colours are theirs and must not be retinted. |
| `mipmap-*/` | Launcher icon. |

### Tests — `app/src/test/java/…/saispike/`

Fast JVM unit tests (23 classes, 245 tests, no device/emulator needed). Three kinds:

- **`*Test.kt`** — behaviour tests for one class each (`GlassesLinkTest`, `HangupPolicyTest`,
  `ReconnectPolicyTest`, `GreetingGateTest`, `HeldNudgeQueueTest`, `MachineSwitcherTest`,
  `AgentEventRouterTest`, `ActivityLogTest`, `ConciergeProtocolTest`,
  `CallNotificationTextTest`, `PresenterSocketTest`, `AskFirstStepperTest`, `ui/SaiTabTest`).
- **`*ParityTest.kt`** — replay a shared fixture to prove the Kotlin port matches the server
  (`ConciergeProtocolParityTest`, `ActivityLogParityTest`).
- **`fsm/`** — the state machine's own tests, including `FsmGoldenTest`: 59 scenarios ported from the
  server's catalog, each naming the failure it prevents.

---

## The presenter — `presenter/`

A small standalone Node/TypeScript app used only for demos; the app talks to it solely from
DEBUG builds.

| Path | What it is |
| --- | --- |
| `server.ts` | The dashboard server (receives the DEBUG presenter feed). |
| `public/index.html` | The dashboard page. |
| `package.json`, `package-lock.json`, `tsconfig.json` | Node project config. |
