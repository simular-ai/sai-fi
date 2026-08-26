# sai-fi — voice concierge on Meta Ray-Ban glasses

The phone app that puts the Sai voice concierge on **Meta Ray-Ban glasses**. The phone
opens a Gemini Live audio session with the user's own key, runs the conversation state machine
itself, and talks to the Sai API only to reach the agent. The glasses are the microphone, speaker
and camera. Android is the shipping client; iOS is the in-progress port.

To verify a build on hardware, start with [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md).

The Sai API is documented at [sai.work/api](https://sai.work/api). This repo does not contain the
server. What the client is obliged to do on the wire is
[`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md). The conversation state machine —
modes, the effect grammar, the admission rule, the races, and why each rule exists — is
[`VOICE_FSM.md`](VOICE_FSM.md). Read that before changing anything under `fsm/`.

**Code:** `meta-android-app/` (Kotlin, display name "sai-fi", `applicationId ai.simular.saiglasses`,
package `…cameraaccess.saispike`) and `meta-ios-app/` (Swift, bundle id `ai.simular.saifi`, in
progress).

The applicationId and the Java package still carry the app's earlier name. They change together, to
`ai.simular.saifi`, in a package rename — deliberately not with the display rename, because the
applicationId is what the Meta AI registration binds to. Changing it forces a re-registration in the
Wearables Developer Center and a fresh install, and only an on-device call can confirm that worked.

---

## 1. Why it's shaped this way

These are the decisions that still constrain the code. The ones that were tried and reversed are
named only where they explain a current trade-off.

- **Meta DAT is a native phone-app SDK**, mediated by the Meta AI companion app; the glasses connect
  over Bluetooth. There is no Meta-AI intent routing for third-party voice, so this app builds the
  whole voice layer itself. Distribution is gated (developer-preview release channels). **iOS
  development and internal TestFlight are open**; App Store and external TestFlight are blocked by
  MFi (`com.meta.ar.wearable`) until Meta puts third-party apps on their Product Plan. The iOS port
  lives in `meta-ios-app/`. The Android app is still the shipping client.
- **A standalone Android app, not inside the Capacitor Sai app.** DAT is Kotlin/Swift. Fast native
  iteration beat embedding this in the existing desktop/mobile shell.
- **Gemini Live owns audio; this app owns the conversation; the Sai API owns the agent.** There is
  no on-device STT/TTS/VAD stack, and there is no voice-specific server. Audio goes phone ⇄ Google.
  Work goes phone ⇄ `POST /v1/agents/*`. The FSM sits between them, on this device.
- **The queue is local.** A voice-specific WebSocket and then a `/v1/voice/*` HTTP surface both
  existed and were removed. Reaching the agent through the ordinary Sai API is what lets a fork of
  this repo run against the API as it already exists. The cost is that a held task does not survive
  a dropped call; see [`VOICE_FSM.md`](VOICE_FSM.md).
- **A prompt or model change is an app release.** `assets/voice-profile.json` ships in the APK.
  There is no server call that delivers the system prompt, tools, or voice.
- **Persistent HFP/SCO, not A2DP hi-fi.** HFP and A2DP are mutually exclusive. Design A (one
  full-duplex SCO session for the whole call) keeps the mic live while Sai speaks, so voice barge-in
  works on the glasses. Design B (switch to A2DP for playback) would drop the mic mid-utterance.
  Playback is mono and SCO-quality on purpose.

---

## 2. Architecture

```
  Meta Ray-Ban glasses        Android app                              Sai API                    Sai agent
 ┌──────────────────┐        ┌───────────────────────────────────┐  ┌──────────────────────┐  ┌──────────────┐
 │ mic (HFP/SCO) ───┼──BT───▶│ AudioIo ─PCM16 16k─┐              │  │                      │  │ computer-use │
 │ speaker ◀────────┼──BT────│ ◀─PCM 24k─ GeminiLiveClient ──────┼─▶│  (not involved)      │  │ agent on the │
 │ temple button ···┼······▶ │ VoiceSession                      │  │ POST /v1/agents/     │  │ user's VM    │
 └──────────────────┘        │ CallService (fg) · CallController ├─▶│      message   ──────┼─▶│              │
                             │ **the concierge FSM lives here**  │◀─┤   (its response IS   │  │              │
                             │ VoiceConciergeActivity (UI)       │  │    the turn's events)│  │              │
                             └───────────────────────────────────┘  └──────────────────────┘  └──────────────┘
   audio link: glasses ⇄ Gemini Live (client-side, direct)      agent link: app ⇄ the ordinary Sai API
```

Two independent links per call: the **audio link** (client ⇄ Gemini Live directly, with the user's
own key — the Sai API is not involved at all) and the **agent link** (client ⇄ Sai API over HTTP —
the execution/trust boundary). The FSM sits between them, on this device: the model's tool calls go
straight into it, and agent events arrive on the turn's own stream.

The agent link is not a persistent connection. A turn's events arrive on the response to the
message that started it, so the app is connected to the API **only while the agent is working**.
Nothing that happens between turns is heard — an approval resolved in the desktop app while nothing
is running, for instance.

### Android modules

| Module                               | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VoiceConciergeActivity`             | Thin UI/launcher: Google sign-in, machine picker (remembers the last pick via `Prefs`), glasses connect (auto-requests DAT camera permission once registered), the two persisted settings, Start/Stop. Audio route is automatic (glasses SCO when available, otherwise phone) — status is read-only. Renders `CallController.state`; holds no call state. Refreshes auth state on every `onResume`, because a signed-out session is now a gate rather than a card.                                                                                                                                                                                                                                                                                                                     |
| `ui/` (7 files)                      | **The screens.** `ConciergeScreen` is the shell only — the sign-in gate, then a `Scaffold` with a bottom `NavigationBar` over `Home` / `Settings` / `Logs`. Signed out, `SignInScreen` is the whole app and signing in is the only action. The Logs tab is gated on the **persisted developer-mode switch**, not on `BuildConfig.DEBUG` — a build type answers "was this compiled for development" and the question is "does the person holding the phone want operator detail". `SaiTab.kt` holds that visibility rule as pure functions so `SaiTabTest` can pin the case where turning the switch off strands you on a hidden tab. |
| `CallController`                     | Process singleton; observable `StateFlow<State>`; turns UI/gesture actions into `CallService` intents; carries `StartParams` (incl. `askFirstThresholdMs`); exposes `toggleMute`/`togglePause`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `CallService`                        | Foreground `microphone` service that **owns the call graph** (AudioIo + GeminiLiveClient + VoiceSession + reconnect). Notification via `CallNotifications`; a spectator (the presenter feed) watches through `CallObserver`. Handles `switchMachine`/`endCall`; auto-follows glasses SCO connect/reconnect mid-call; permanent failures (401/403/503) end the call with a spoken/notified reason instead of retrying.                                                                                                                                                                                                                                                                                                                                                           |
| `CallObserver` / `PresenterObserver` | The seam a spectator watches a call through. `NoopCallObserver` is the release default with every method empty; `PresenterObserver` owns the `PresenterSocket` and is built only in the DEBUG branch of `CallService.startPresenter`. Every method must be non-throwing and cheap: `onMic` runs per PCM frame, and a spectator must never be able to fail the call it is watching.                                                                                                                                                                                                                                                                                                                                                                                                    |
| `CallNotifications`                  | The channel, the ongoing call card and the dismissible "why it ended" card. The wording is `CallNotificationText`, a pure function of (muted, paused, machineLabel) — and therefore JVM-testable. Pause dominates mute in every line, because a paused call sends no mic frames and so no keepalives, and the idle guard ends it exactly like a walked-away call.                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `WindowCapture`                      | **DEBUG only.** Mirrors **this app's own window** to the presenter dashboard as ~3 fps JPEGs, so the room sees the app UI itself. `PixelCopy` on the Activity's `Window`. **No MediaProjection**, so no consent dialog and nothing outside this app's window is captured.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GeminiLiveClient`                   | Raw-WebSocket Gemini Live client: `setup` (model/prompt/tools/voice from the bootstrap) + realtime PCM; audio, transcripts, barge-in; routes function calls — effects → the FSM, client-local tools handled on-device; nudge gating. Connects with the user's own key (`BidiGenerateContent?key=`). The Constrained/`access_token` form is for a server-minted ephemeral token, which this app no longer uses — passing a key to that form fails 1007.                                                                                                                                                                                                                                                                                                                                  |
| `AudioIo`                            | 16 kHz capture / 24 kHz playback. **Both routes** run on `VOICE_COMMUNICATION`/`MODE_IN_COMMUNICATION` for the whole call (platform AEC cancels the speaker from the mic; full-duplex voice barge-in). **Glasses route:** one persistent HFP/SCO session — the glasses mic streams up while the model's TTS plays back over the _same_ SCO link, mic live throughout. Playback is mono, SCO-fidelity (we deliberately do **not** switch to A2DP for hi-fi). Route-loss falls back to phone without dropping the call.                                                                                                                                                                                                                                                                    |
| `VoiceSession`                       | One call's concierge: owns the FSM, its two ports, and the reader for each turn's stream. Mints a **fresh Firebase ID token per attempt** (a long call outlives the ~1h one it started with). Effects go straight into the local FSM. A turn's stream is read on its own coroutine, never awaited by the send that opened it: `forwardTask` runs inside the FSM's mutex and the FSM needs that mutex for every event about to arrive. A dropped stream is reported to the FSM as an **error, never a completion**. **Owns the cost guard**: there is no server-side notion of this call at all, and an open microphone costs money whether or not anyone is still wearing the glasses. |
| `HttpAgentBridge`                    | The FSM's `AgentBridge` over `/v1/agents/*` — forward, steer, abort, reset, approve. There is no endpoint for holding a task: the queue is local, so the agent is told about a task only when it starts. Also holds the photo stash (taken when a task is held, so a later capture cannot ride along) and folds a location fix into the task's text, because a user message has no metadata channel.                                                                                                                                                                                                                                                                                                                                     |
| `ConciergeClient`                    | HTTP: `GET /v1/agents/machines` (with `status` + `canWake`), `GET /v1/agents/context` (recallHistory), `POST /v1/agents/upload`, `POST /v1/agents/wake`. Dependency-free; non-2xx throws typed exceptions so permanent failures are distinguishable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `ActivityLog` / `ConciergeProtocol`  | The canonical nudge and activity-log wording (`describeAgentEvent` with prompt-injection fencing). The only copy, pinned by the string goldens. Feed `getSaiStatus` + the UI activity view.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GlassesGestureSession`              | DAT `DeviceSession` (no display/camera capability) reacting to the only temple gestures DAT surfaces: tap = mute/unmute Sai, tap-and-hold/doff/fold = end (all just `DeviceSessionState`; no gesture is remappable — see §5).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `SaiFiApp` / `MainActivity`          | App init (`Wearables.initialize` once); `MainActivity` is the DAT-registration deep-link callback host (`saiwearables`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `fsm/`                               | **The conversation state machine**: modes and transitions, the bounded effect grammar and its parse boundary, the admission rule that holds a mid-turn task instead of folding it in, the local held-task queue, the cost guard, and every line the FSM speaks. Everything but `Concierge.kt` is pure, which is what makes the 63-scenario golden catalog runnable as JVM tests; `Concierge.kt` serialises all four input kinds through one `Mutex`, because two forwards interleaving at a suspension point books the restaurant twice. Design: [`VOICE_FSM.md`](VOICE_FSM.md). `CallService.buildConcierge` builds one `VoiceSession` per call and feeds the model's tool calls straight into it.                        |

### iOS modules

The same architecture, in `meta-ios-app/`. `SaiFiCore/` is Foundation-only (FSM, protocol, policies, goldens). The Xcode target holds DAT and anything that needs AVFoundation / SwiftUI.

| Module | Role |
| --- | --- |
| `SaiFiCore/` | Pure half. Gate: `swift run saifi-check` (471 checks, including the conversation harness). |
| `GlassesGestureSession` / `GlassesCamera` | DAT session + still capture. iOS 0.8: one session only; `stream.stop()` then `addStream` reuses the slot. |
| `Prefs` / `PhoneLocation` / `Theme` / `CallNotifications` | Same keys, one-shot location, Sai tokens, ended-reason banner only (no ongoing-call notification). |
| Audio / Gemini Live | In the Xcode target. HFP duplex unverified on hardware. |
| Agent HTTP | In SaiFiCore (`HttpAgentBridge`, `VoiceChannelClient`, `VoiceSession`). Scripted harness green; live POST unverified. |
| UI / sign-in / CallCoordinator | Not written yet. |

---

## 3. How a call works

1. **Bootstrap is local.** There is no session-mint endpoint. The prompt, tools, model and voice
   ship in `assets/voice-profile.json`, and the Gemini key comes from `local.properties` at build
   time. What that buys is a client whose voice half works without the Sai API reachable at all.
2. **Live reconnect is baseline behavior.** A dropped Gemini socket mid-call is expected (network
   blip, process pause). `GreetingGate` re-arms only at `startCall`, so a reconnect or
   resume-after-pause — both of which re-run `setupComplete` — does not re-greet.
3. **Nudge discipline:** never inject a nudge mid-utterance (self-interruption); defer real nudges
   until `turnComplete`; on `serverContent.interrupted` flush queued playback (barge-in).
4. **Tool responses:** answer **every** function call or the model stalls — `getSaiStatus` → local
   `ActivityLog` read (never forwarded); everything else `{result:'ok'}`.
5. **Prompt-injection fencing:** agent-derived text is data, not instructions — keep the
   `describeAgentEvent` fencing (`"""…"""`) intact.
6. **Proactive opening greeting:** on the **first** Live `setupComplete` of a call, `CallService`
   injects `GREETING_NUDGE` so Sai greets first. Gemini Live stays silent until it gets some input,
   so the nudge is what kicks off the opening turn. It's model *output*, not mic input, so it does
   not wait on the mic being open, and barge-in is unaffected. The wording is fixture-pinned.

The rendered strings (`ConciergeProtocolGoldenTest`, `ActivityLogGoldenTest`) and the orchestration
(`FsmGoldenTest`, 63 scenarios) are the spec. There is no server→client frame fixture, because there
are no server→client frames — the SSE stream carries agent events and nothing else.

### Machines: waking one, and leaving one

**Waking.** A hibernated machine is woken at **call bind and at every switch** — announced when it is
asleep, before the user has asked for anything. `WakePolicy` decides whether to speak;
`CallService.wakeMachine` calls `POST /v1/agents/wake` and then polls `GET /v1/agents/machines` for
`status == "active"`. Three rules, each of which is a way of saying something untrue about a computer
the wearer cannot see:

- **Branch on `startingUp`, not `waking`.** A machine already mid-wake answers `waking: false` —
  correctly, nothing was dispatched — and is still owed the "about a minute" line.
- **Say nothing when `canWake` is false.** That machine is asleep and staying that way, and
  `MACHINE_WAKING` promises about a minute.
- **The wake happens while muted; only the announcement is dropped.** The *watch* continues, so
  unmuting mid-wake still hears `MACHINE_AWAKE`.

`waking: true` only ever meant dispatched — the VM service is fire-and-forget — so the three-minute
timeout is this client's own and `MACHINE_WAKE_FAILED` is the honest end of it. All three lines share
one nudge kind so a later one replaces an earlier one still held for the end of a turn.

**Leaving.** `applyMachineSwitch` builds a fresh `VoiceSession`, so the FSM — queue, in-flight turn,
pending approval — goes with the old one, and `close()` discards the stream without aborting: the work
keeps running on the machine being left and its result reaches nobody. So a switch with work
outstanding **asks first**, through the `LeavingWorkPolicy` it shares with `endCall`. From the
**picker** there is no question to ask — a tap is not a conversation — so it switches and then says
what was left behind.

Moving machines **retires the conversation behind you** (one live session per programmatic channel),
so coming back to a machine starts fresh and `recallHistory` will not reach what you did there
before. Within a call that costs nothing: the Live model holds the whole call in its own context.
Ending a call and starting another stays in one conversation; the only thing that rotates the
session is the user saying "start fresh". See [`VOICE_FSM.md`](VOICE_FSM.md) §7b.

**Client-local voice tools:** `getSaiStatus`, `recallHistory` (`GET /v1/agents/context` — recall
without waking the agent), `switchMachine` (rebuilds the concierge against another owned VM; Live
audio keeps running; **asks first when work is outstanding**), `endCall` (same `LeavingWorkPolicy`;
spoken goodbye then teardown), `captureImage` (DAT photo → upload → attached to the next forward;
the client's half of the obligation is
[`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) §4).

**Where the user is** rides the same rail but is not a tool: the model sets `includeLocation` on the
`forwardToAgent`/`relayToAgent` that needs it, and a fresh fix (`PhoneLocation`) is read just before
the effects, exactly as `attachLatestImage` does for a photo. Neither flag reaches the server —
`parseEffect` ignores unknown fields. When no fix is available the request **still goes**, and a
`location-unavailable` nudge tells the model to say so rather than name a city.

**Manual capture:** the in-call attach-photo button runs the same capture/upload/stash plumbing.
DAT surfaces no gesture to bind it to (see §5).

---

## 4. Auth, keys, privacy

- **On-device login** (`SaiAuth.kt`): Google → Firebase Auth. Every Sai API call sends a fresh
  **Firebase ID token** as `Authorization: Bearer` — never in a URL. There is no compiled-in
  `sapi_` key. Firebase is initialized from `local.properties` (`firebase_app_id`,
  `firebase_api_key`, `firebase_project_id`, `web_client_id`). The app's package + signing SHA-1
  must be registered in that Firebase project. There is no `google-services.json` and no
  `google-services` Gradle plugin.
- **Signed out, sign-in is the only thing on screen.** A build with no Firebase configuration says
  so and names the four missing keys, rather than showing a button that cannot work.
- **Re-login is rare:** Firebase Auth persists the session; `getIdToken()` auto-refreshes. A
  session revoked out of band is noticed on the next `onResume`, not the instant it happens.
- The server authorizes `machineId` ownership per request (403 otherwise).
- The Gemini key is the user's own, from `local.properties`, and never reaches a Simular endpoint.
  It is a plaintext constant in the built APK, so it travels with any build you share. See
  [`SECURITY.md`](../SECURITY.md).
- Voice-resolution limits (link-only credentials → finish in the app) are **server-enforced
  persona rules**; don't weaken them client-side.
- **Privacy.** The glasses mic streams straight to Google Gemini Live, so everything it picks up —
  including people who never consented — leaves the device. Tuned VAD only stops bystanders from
  *triggering* Sai; it does not stop their voices being captured. **Mute is not a replacement:** it
  silences Sai's output and leaves the mic open. The only control that actually stops capture today
  is **Pause**, which drops the mic and the Live session. There is no recording indicator for
  continuous audio (Meta requires a visible one for the camera; the audio equivalent on face-worn
  hardware has no answer), and `recallHistory` puts prior-session transcripts into the same model
  context. Treat this as unresolved before any non-lab use.

---

## 5. DAT platform facts

- **minSdk 31** — required by the audio path (`AudioManager.setCommunicationDevice()` for HFP
  routing and runtime `BLUETOOTH_CONNECT` are API 31+).
- **Manifest:** `com.meta.wearable.mwdat.APPLICATION_ID` + `com.meta.wearable.mwdat.CLIENT_TOKEN`
  meta-data (from `manifestPlaceholders`, empty by default — filled from the Wearables Developer
  Center), and a registration callback `<intent-filter>` (`saiwearables`) on `MainActivity`.
- **Permissions:** `BLUETOOTH`, `BLUETOOTH_CONNECT`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
  `INTERNET`, `POST_NOTIFICATIONS` (API 33+), `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`.
  Mic is a standard OS permission; **camera** is a DAT `Permission` grant made through the Meta AI
  app.
- **Location is asked for once, at sign-in** — never per call and never mid-conversation, because a
  system dialog is unanswerable by someone whose eyes and hands are busy. `CallService` declares
  `foregroundServiceType="microphone|location"`: it reads the fix while foregrounded, and from
  Android 14 that combination without the declared type is a `SecurityException`.
- **Dev Mode / registration:** Meta AI app (`com.facebook.stella`) v272+, glasses firmware v125+
  (v127 for DAT 0.8); tap App Version 5× to enable Developer Mode; **only one third-party app can
  be registered at a time**.
- **Audio:** the glasses mic is **HFP** (8 kHz mono narrowband unless wideband mSBC/LC3 negotiates)
  with wearer-isolating beamforming. See §1 for why this app stays on SCO for the whole call.
- **Glasses gestures (hard SDK ceiling).** DAT 0.8 exposes **no** gesture API to a third-party
  app — gestures are hardwired to session lifecycle, and all the app observes is the resulting
  `DeviceSessionState`. The complete set of temple gestures is three, none remappable:
  - **tap** → `PAUSED ⇄ STARTED` (this app maps that to mute/unmute Sai)
  - **tap-and-hold** → `STOPPED` (ends the call)
  - **doff / fold / drop** → `STOPPED` — **indistinguishable** from tap-and-hold. **Losing
    Bluetooth is the same signal**, so a call cannot survive the glasses being folded, taken off,
    or walking out of range. An in-call line says so up front.
  - There is **no double-tap, swipe, or drag**, and no physical capture-button event. A third
    gesture-bound action (e.g. photo capture) is not possible on DAT 0.8 — capture stays on the
    phone button / voice.

A capability-less session (this app attaches none) is what delivers taps. Attaching a throwaway
camera stream to "keep the session live" would need the DAT camera permission, light the privacy
LED for the whole call, and reintroduce a camera capability the design omits.

**iOS 0.8 (measured with MockDeviceKit on iPhone 17, 2026-08-25):** two `DeviceSession`s cannot
coexist — a second `createSession` is refused — so capture attaches to the live gesture session
and `addStream` is on demand. That is iOS-only: the privacy LED belongs to that session's stream.
We still `stop()` the stream between captures rather than leaving it on for the whole call.
`stream.stop()` then `addStream` on the same session still delivers frames (there is no
`removeStream()`). Do not `session.stop()` after a capture: that is `STOPPED`, which ends the call.

---

## 6. Running it

See the [repository README](../README.md) for the full key table and build commands. Short form:

1. Copy `meta-android-app/local.properties.example` to `local.properties` and fill it in.
   `sai_api_url` defaults to `https://api.sai.simular.ai`. An empty value fails at runtime with
   `no sai_api_url` rather than a mysterious network error. Optional `sai_version_tag` sends
   `x-sai-version` to pin a specific server revision; leave it blank against production.
2. Register the app with Meta AI (Wearables Developer Center + Developer Mode). Only one
   third-party DAT app can hold a registration at a time.
3. Open the `meta-android-app` folder in Android Studio, sync, plug in a phone, Run. Sign in with
   Google, pick a machine, start the call.

Logs: `adb logcat | grep SaiFi`.

A laptop on the same LAN is a valid `sai_api_url` for unmerged server work
(`http://localhost:8080` + `adb reverse tcp:8080 tcp:8080`). Production is the default and the
only path that survives unplugging.

---

## 7. Testing

Deterministic first, by-ear last. `--rerun` is required: Gradle does not treat environment
variables as task inputs, so a second run is UP-TO-DATE and reports success without running.

1. **String goldens.** Every string the concierge speaks or shows is pinned byte for byte in
   `app/src/test/resources/parity/`. A reworded spoken line breaks a test, not a demo — the
   wording was nearly all found by hearing it fail on a call. Rewriting them is
   `SAI_REGEN_GOLDENS=1` (see the README). Generation is a switched-off test on purpose: a golden
   that regenerates itself cannot detect drift.
2. **JVM unit tests.** Pure policies one class each, protocol and bridge tests, `LiveTurnGateTest`.
   What these cannot reach is `GeminiLiveClient`'s socket and `AudioIo`'s PCM path.
3. **The conversation harness** (`app/src/test/…/conversation/`). A fake brain's tool calls go
   through the real `LiveTurnGate` → `Concierge` → `HttpAgentBridge`, and stop at `VoiceTransport`
   — the seam *under* the bridge. `TimingMatrixTest` replays a conversation at seven speeds and
   asserts invariants, because every barge-in ⇄ queue bug on record is a race.
4. **Paid tiers, off by default.** `SAI_LIVE_AGENT=1` for contract drift against a real API;
   `SAI_CONVERSATION_EVAL=1` / `SAI_TRANSCRIPT_EVAL=1` for a judged model; `SAI_DEMO=1` for a real
   model and a real agent end to end. CI never sets any of them. In the judged tiers only the
   deterministic half fails the build; the judge's verdicts print as a score.
5. **On-device.** [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) — the audio path, the camera, and
   how it all feels. [`ON_DEVICE_DEMO_FLOW.md`](ON_DEVICE_DEMO_FLOW.md) is the same ten checks as
   a spoken script.

Anything reproducible belongs off-device, where it is free and runs on every push.

---

## 8. Known limitations

- **A held task lost to a dropped call is currently silent.** A machine switch used to lose it the
  same way and now asks first (`LeavingWorkPolicy`). A dropped call is the case still without an
  answer; see [`VOICE_FSM.md`](VOICE_FSM.md) §7.
- **Nothing is heard between turns.** An approval resolved elsewhere while the agent is idle will
  not reach the FSM. `GET /v1/agents/context` at turn boundaries is the cheapest fix that needs no
  server change.
- **Display HUD status** waits on display-capable hardware (Ray-Ban Display).
- **Cold start by voice** needs a wake word; the mic is off when the service isn't running.
- The fuzzy `contains` name match on `switchMachine` can still mis-target similar machine names.
- `AgentEventRouter` and `HeldNudgeQueue` still run alongside the FSM rather than inside it.
  Folding them in would put every "when do we speak" decision in one place.
