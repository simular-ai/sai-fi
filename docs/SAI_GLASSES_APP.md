# sai-fi — Android Client for the Voice Concierge

The standalone Android app that puts the voice concierge on **Meta Ray-Ban glasses**: a thin
native shell around a client-side Gemini Live audio session and a WS relay to cloud-api.

To verify a build on hardware, start with [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md).

**The server side lives in another repository, and this repo does not link into it.** What the client
is obliged to do is written down here instead, in
[`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) — the endpoints, the message tables, the
close codes and the five device tools. Read that first; everything below describes how these modules
meet it.

**The conversation state machine is now this app's**, and its design is
[`VOICE_FSM.md`](VOICE_FSM.md) — modes, the effect grammar, the admission rule, the races, and why
each rule exists. That is a change of ownership, not a copy: the FSM used to run server-side and was
deliberately not described here, on the grounds that a mirror of someone else's design goes stale
without anything catching it. What genuinely stays server-side — token minting, billing, the system
prompt — is listed in §1 of that doc, because a fork needs to know what it still needs a server for.

The DAT platform research this design rests on — the glasses platform comparison, the mic-access and
Meta-AI-coexistence go/no-go findings, and the distribution constraints — is not linked either. Its
conclusions are what §1 and §6 below record, which is the part that constrains this app.

**Code:** `meta-android-app/` (Kotlin, standalone app "sai-fi",
`applicationId ai.simular.saiglasses`, package `…cameraaccess.saispike`).

The applicationId and the package still carry the app's earlier name. Both change together, to
`ai.simular.saifi`, in the package rename — deliberately not with the display rename, because the
applicationId is what the Meta AI registration binds to: changing it forces a re-registration in the
Wearables Developer Center and a fresh install, and only an on-device call can confirm that worked.

---

## 1. How we got here (platform research → direction changes)

- **Meta DAT** (Device Access Toolkit) is a _native phone-app SDK_ (Kotlin/Swift) mediated by the
  Meta AI companion app; the glasses connect over Bluetooth. Capabilities: camera
  (photo/stream), display HUD (Ray-Ban Display only), media/audio over standard BT profiles
  (A2DP out, HFP mic), and session lifecycle.
- **Research verdicts (2026-07-02):** Q1 — third-party continuous mic capture **works** (the
  go/no-go cleared). Q2 — no Meta-AI intent routing; we build the whole voice layer ourselves.
  Real friction is **distribution gating** (developer-preview release channels), not audio.
- **Direction change 1:** prototype as a **standalone Android app** (fast native iteration), not
  inside the Capacitor Sai app. Integration later is a copy + auth swap.
- **Direction change 2:** connect through the **voice concierge**, not directly to
  `/v1/agents/*`. This deleted the entire old plan's surface: no Groq Whisper STT, no on-device
  TTS, no energy VAD, no SSE chat-protocol parsing in Kotlin. **Gemini Live owns audio; the
  concierge server owns the agent; the app is genuinely thin.**
- **Direction change 3 (implemented):** the standalone voice-concierge dev server was **folded
  into cloud-api** — `POST /v1/concierge/session` + `WS /v1/concierge/ws`, real per-user auth,
  in-process agent bridge.
- **Direction change 4 (implemented, 2026-08-12):** and then most of it came back here. The
  orchestration FSM, the spoken lines, the prompt and the cost guard now run **on this device**, and
  the user brings their own Gemini key. The session endpoint and the WebSocket are gone.
- **Direction change 5 (implemented, 2026-08-13):** the last of it. There is no voice surface on the
  server either — this app reaches the agent through `/v1/agents/*`, the ordinary Sai API, and the
  held-task queue lives only in the FSM. cloud-api gained nothing for voice at all, which is what
  lets a fork of this repo run against the API as it already exists. The cost is that a held task no
  longer survives a dropped call; see [`VOICE_FSM.md`](VOICE_FSM.md).

## 2. Architecture

```
  Meta Ray-Ban glasses        Android app (thin client)                cloud-api                  Sai agent
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
own key — cloud-api is not involved at all) and the **agent link** (client ⇄ cloud-api over HTTP —
the execution/trust boundary). The FSM sits between them, on this device: the model's tool calls go
straight into it, and agent events arrive on the turn's own stream.

Note what the agent link is NOT: a persistent connection. A turn's events arrive on the response to
the message that started it, so the app is connected to cloud-api **only while the agent is
working**. Nothing that happens between turns is heard — an approval resolved in the desktop app
while nothing is running, for instance.

### Android modules

| Module                               | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VoiceConciergeActivity`             | Thin UI/launcher: Google sign-in, machine picker (remembers the last pick via `Prefs`), glasses connect (auto-requests DAT camera permission once registered), voice-UX settings, Start/Stop. Audio route is automatic (glasses SCO when available, otherwise phone) — status is read-only. Renders `CallController.state`; holds no call state. Debug-only composer/log are `BuildConfig.DEBUG`-gated.                                                                                                                                                                                                                                                                                                                                                                                |
| `CallController`                     | Process singleton; observable `StateFlow<State>`; turns UI/gesture actions into `CallService` intents; carries `StartParams` (incl. `askFirstThresholdMs`); exposes `toggleMute`/`togglePause`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `CallService`                        | Foreground `microphone` service that **owns the call graph** (AudioIo + GeminiLiveClient + VoiceSession + reconnect). Notification via `CallNotifications`; a spectator (the presenter feed) watches through `CallObserver`. Handles `switchMachine`/`endCall`; auto-follows glasses SCO connect/reconnect mid-call; permanent failures (401/403/503) end the call with a spoken/notified reason instead of retrying.                                                                                                                                                                                                                                                                                                                                                           |
| `CallObserver` / `PresenterObserver` | The seam a spectator watches a call through. `NoopCallObserver` is the release default with every method empty; `PresenterObserver` owns the `PresenterSocket` and is built only in the DEBUG branch of `CallService.startPresenter`. The demo feed used to be eleven `presenter?.…` calls threaded through `onAudio`, the capture path, `log()`, `status()` and the teardown — the core of a call knew at eleven points that a demo tool might be listening. Every method must be non-throwing and cheap: `onMic` runs per PCM frame, and a spectator must never be able to fail the call it is watching.                                                                                                                                                                             |
| `CallNotifications`                  | The channel, the ongoing call card and the dismissible "why it ended" card. The WORDING is `CallNotificationText`, a pure function of (muted, paused, machineLabel) — and therefore JVM-testable, which the private `when` blocks on the Service were not. Pause dominates mute in every line, because a paused call sends no mic frames and so no keepalives, and the server's idle guard ends it exactly like a walked-away call.                                                                                                                                                                                                                                                                                                                                                    |
| `WindowCapture`                      | **DEBUG only.** Mirrors **this app's own window** to the presenter dashboard as ~3 fps JPEGs (tag 4 on `PresenterSocket`), so the room sees the app UI itself — machine picker, mute button, a crash — not just the call's contents. `PixelCopy` on the Activity's `Window`, encoded on its own `HandlerThread`; never touches the mic thread or the Live reader thread, and frames are dropped rather than queued. **No MediaProjection**, so no consent dialog, no cast indicator, no `mediaProjection` FGS type, and nothing outside this app's window is captured. Runs only while the Activity is resumed, and publishes through `CallController.screenSink`, which `CallService` opens alongside the presenter socket.                                                           |
| `GeminiLiveClient`                   | Raw-WebSocket Gemini Live client: `setup` (model/prompt/tools/voice from the bootstrap) + realtime PCM; audio, transcripts, barge-in; routes function calls — effects → concierge, client-local tools handled on-device; nudge gating.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `AudioIo`                            | 16 kHz capture / 24 kHz playback. **Both routes** run on `VOICE_COMMUNICATION`/`MODE_IN_COMMUNICATION` for the whole call (platform AEC cancels the speaker from the mic; full-duplex voice barge-in). **Glasses route:** one persistent HFP/SCO session — the glasses mic streams up while the model's TTS plays back over the _same_ SCO link, mic live throughout, so voice barge-in works on the glasses exactly like on the phone. Playback is mono, SCO-fidelity (we deliberately do **not** switch to A2DP for hi-fi, which would drop the mic mid-utterance and kill barge-in — always-on full-duplex is the chosen tradeoff). Route-loss falls back to phone without dropping the call; SCO device add/remove notifies so the service can auto-reselect glasses on reconnect. |
| `VoiceSession`                       | One call's concierge: owns the FSM, its two ports, and the reader for each turn's stream. Mints a **fresh Firebase ID token per attempt** (a long call outlives the ~1h one it started with). Effects go straight into the local FSM — there is no round trip. A turn's stream is read on its own coroutine, never awaited by the send that opened it: `forwardTask` runs inside the FSM's mutex and the FSM needs that mutex for every event about to arrive. A dropped stream is reported to the FSM as an **error, never a completion** — the agent may still be working, and the one thing that must not be said is "done". **Owns the cost guard**, which used to be the server's: there is no server-side notion of this call at all, and an open microphone costs money whether or not anyone is still wearing the glasses. |
| `HttpAgentBridge`                    | The FSM's `AgentBridge` over `/v1/agents/*` — forward, steer, abort, reset, approve. There is no endpoint for HOLDING a task: the queue is local, so the agent is told about a task only when it starts. Also holds the photo stash (taken when a task is held, so a later capture cannot ride along) and folds a location fix into the task's text, because a user message has no metadata channel.                                                                                                                                                                                                                                                                                                                                     |
| `ConciergeClient`                    | HTTP: `POST /session`, `GET /v1/agents/machines`, `GET /v1/agents/context` (recallHistory), `POST /v1/agents/upload`. Dependency-free; non-2xx throws typed exceptions so permanent failures are distinguishable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `ActivityLog` / `ConciergeProtocol`  | Kotlin ports of the server's `core/activity-log.ts` and `core/nudges.ts` (`describeAgentEvent` with prompt-injection fencing — port verbatim; drift is caught by the cross-port parity fixtures, §8.1). Feed `getSaiStatus` + the UI activity view.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GlassesGestureSession`              | DAT `DeviceSession` (no display/camera capability) reacting to the only temple gestures DAT surfaces: tap = mute/unmute Sai, tap-and-hold/doff/fold = end (all just `DeviceSessionState`; no gesture is remappable — see §6 "Glasses gestures").                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `SaiFiApp` / `MainActivity`     | App init (`Wearables.initialize` once); `MainActivity` is now just the DAT-registration deep-link callback host (`saiwearables`) — the CameraAccess sample UI was pruned in productization.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `fsm/` (13 files)                    | **The conversation state machine**, ported from the server: modes and transitions, the bounded effect grammar and its parse boundary, the admission rule that holds a mid-turn task instead of folding it in, the local held-task queue, the cost guard, and every line the FSM speaks. Everything but `Concierge.kt` is pure, which is what makes the 59-scenario golden catalog runnable as JVM tests; `Concierge.kt` serialises all four input kinds through one `Mutex`, because two forwards interleaving at a suspension point books the restaurant twice. Design: [`VOICE_FSM.md`](VOICE_FSM.md). **This drives every call** — `CallService.buildConcierge` builds one `VoiceSession` per call and feeds the model's tool calls straight into it.                        |

## 3. The client contract (frozen; the Kotlin app ports the browser reference client)

1. **Bootstrap is local.** There is no `POST /v1/concierge/session`. The prompt, tools, model and
   voice ship in `assets/voice-profile.json`, and the Gemini key comes from `local.properties` at
   build time. The cost of that trade is real and accepted: a prompt or model change is now an app
   release, not a server deploy. What it buys is a client that works without our server.
2. **Ephemeral token:** single-use (`uses:1`), ~2-min start window, ~30-min lifetime → every Live
   (re)connect is preceded by a fresh `POST /session`; mid-call Live reconnect is **baseline
   behavior**, not hardening. Connection uses the **`BidiGenerateContentConstrained`** method
   with `access_token=` (a plain `BidiGenerateContent?key=` fails 1007 — real-API-keys only).
3. **WS protocol** — the message and effect tables are
   [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) §2–3 (effects up;
   agent-event/agent-activity/speak/instruct/approval-timeout down; plus `usage`, `attachment`,
   Don't duplicate them here either. Note what the authority is now: there is no wire-message
   fixture, because there are no server→client frames — the SSE stream carries agent events and
   nothing else. What is still pinned is the RENDERED text (`ConciergeProtocolParityTest`,
   `ActivityLogParityTest`) and the orchestration (`FsmGoldenTest`, 59 scenarios).
4. **Nudge discipline (port exactly):** never inject a nudge mid-utterance (self-interruption);
   defer real nudges until `turnComplete`; on `serverContent.interrupted` flush queued playback
   (barge-in). There are no dead-air fills to speak or drop: the server-side watchdog that produced
   them was removed outright, so this is no longer a hazard to design around.
5. **Tool responses:** answer **every** function call or the model stalls — `getSaiStatus` →
   local `ActivityLog` read (never forwarded); everything else `{result:'ok'}`.
6. **Prompt-injection fencing:** agent-derived text is data, not instructions — keep the
   `describeAgentEvent` fencing (`"""…"""`) intact in the Kotlin port.
7. **Proactive opening greeting:** on the **first** Live `setupComplete` of a call, `CallService`
   injects the `GREETING_NUDGE` (a `[system]` client turn) so Sai greets the user first with one
   short warm line instead of waiting for "hello, can you hear me?". Gemini Live stays silent until
   it gets some input, so the nudge is what kicks off the opening turn — the same `injectNudge`
   mechanism used for capture-retry/completion nudges. **Once per call:** the greeting is gated by
   `GreetingGate` (re-armed in `startCall`), so a mid-call Live reconnect (token expiry / network)
   and a resume-after-pause — both of which re-run `setupComplete` — do **not** re-greet. It's model
   _output_, not mic input, so it does not wait on the mic being open, and barge-in is unaffected (the
   user can talk over it). The nudge text is fixture-pinned (`GREETING_NUDGE` in the parity constants)
   and consistent with the persona prompt's opening-greeting rule.

**Client-local voice tools** (declared server-side, handled on-device): `getSaiStatus` (status
pull), `recallHistory` (recent machine history via `GET /v1/agents/context` — recall questions
answered without waking the agent), `switchMachine` (reconnects the concierge WS to another owned
VM; Live audio session keeps running; its tool response resets the model's machine context),
`endCall` (asks about running/queued work first; spoken goodbye then teardown; fixed ~1.8s delay),
`captureImage` (DAT photo → upload → WS attachment → attached to the next forward; the client's half of
the obligation, including the two-part failure text, is
[`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) §4). **Where the user is** rides the same
rail but is not a tool: the
model sets `includeLocation` on the `forwardToAgent`/`relayToAgent` that needs it, and
`sendEffectsWithRequestedContext` reads a fresh fix (`PhoneLocation`) and sends it just before the
effects, exactly as `attachLatestImage` does for a photo. Neither flag reaches the server —
`parseEffect` ignores unknown fields — so both are purely client-side signalling. Unlike the photo
there is no clipboard: the fix is read at send time, because freshness is the point and there is no
user action to hold. When no fix is available the request **still goes**, and a
`location-unavailable` nudge tells it to say so rather than name a city. **Manual capture** exists
too: the in-call "📷 Attach photo"
button (`ACTION_CAPTURE` → `manualCapture()`) runs the same capture/upload/stash plumbing and
tells the model via a context nudge — for when the _user_ wants a specific view attached without
asking the model to look. The phone UI is the trigger because DAT surfaces no gesture to bind it
to (no physical capture-button event; the only three temple gestures are all taken and none is
remappable — see §6 "Glasses gestures").

## 4. Build phases (status)

| Phase                                    | Scope                                                                                                                                                                                                                                                | Status                                                                                                                                                                                                                                                                                                                                                                               |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **0 — De-risk Gemini Live on Android**   | Ephemeral-token Live session via raw OkHttp WS (Java GenAI SDK skipped); spike Activity                                                                                                                                                              | Implemented; the `Constrained`+`access_token` finding was the key unlock                                                                                                                                                                                                                                                                                                             |
| **1 — Full concierge loop, phone audio** | ConciergeSocket, effect relay, nudge gating, ActivityLog port, baseline reconnect (WS backoff + Live re-mint single-flight)                                                                                                                          | Implemented                                                                                                                                                                                                                                                                                                                                                                          |
| **2 — Audio through the glasses**        | SCO/HFP route selection inside `AudioIo`, live route switch, route-loss fallback; **one persistent HFP/SCO full-duplex session** — the mic stays live while the model speaks over SCO, so voice barge-in works on the glasses (no A2DP hi-fi switch) | Code complete; **NOT done — on-device audio validation is a hard gate** (§9). SCO is the _primary_ output path (no HUD in dev mode) and has never run on real glasses: playback fidelity/volume over SCO, and whether the always-live mic + platform AEC keep the model from self-interrupting during SCO playback, are unverified. Treat as a blocking milestone, not an open item. |
| **3 — DAT session + HUD status**         | Render `agent-activity` on the display HUD, throttled                                                                                                                                                                                                | Waiting on display-capable hardware                                                                                                                                                                                                                                                                                                                                                  |
| **4 — Background + hands-free**          | Foreground service, voice hang-up + machine switch, reconnect hardening, network-change kicks, temple-button pause/resume/stop                                                                                                                       | Implemented (soak/battery + gesture-delivery confirmation pending)                                                                                                                                                                                                                                                                                                                   |

Productization pass (commit `962b8f16`): error handling (permanent-failure teardown), de-spiked
naming, pruned the CameraAccess sample UI, `MainActivity` slimmed to a callback host.

## 5. Auth & security

- **On-device login is built** (`SaiAuth.kt`): the user signs in with **Google → Firebase Auth**,
  and every cloud-api call sends a fresh **Firebase ID token** as `Authorization: Bearer` on
  **both** `/session` and the WS upgrade — never in a URL (the old `?auth=` query fallback
  existed solely for the retired browser demo and has been removed). There is **no compiled-in `sapi_` key anymore**. Firebase is
  initialized manually from `local.properties` (`firebase_app_id`, `firebase_api_key`,
  `firebase_project_id`, `web_client_id`; the app's package + signing SHA-1 must be registered
  in the Firebase project — **no `google-services.json`, and no `google-services` Gradle plugin**).
  That was previously true only by accident: the plugin WAS applied, and since it hard-fails without
  the (gitignored) JSON file, a fresh clone could not build at all. It existed solely for
  `firebase-analytics`, which nothing imported. Both are gone, so this now holds by construction.
- **Re-login is rare by design:** Firebase Auth persists the session across launches and
  `getIdToken()` auto-refreshes the ~1h ID tokens via the stored refresh token, so the user
  stays signed in until they sign out or the refresh token is revoked (password reset, account
  disable). A fresh token is fetched per session mint/reconnect.
- The server authorizes `machineId` ownership on the upgrade (403 otherwise).
- The Gemini key is the user's own, from `local.properties`, and never reaches a Simular endpoint.
  It is a plaintext constant in the built APK, so it travels with any build you share.
- Voice-resolution limits (link-only credentials → finish in the app) are **server-enforced
  persona rules**; don't weaken them client-side.
- Ideal end-state: a **device-scoped credential** provisioned at glasses pairing, revocable
  per-device (today the credential is the user's own Firebase session).
- **Privacy (open, and it got worse rather than better).** The glasses mic streams straight to Google
  Gemini Live client-side, so everything it picks up — including people who never consented — leaves the
  device. Tuned VAD only stops bystanders from _triggering_ Sai; it does not stop their voices being
  captured and sent. The narrowing option this doc used to name was **tap-to-talk, and it has been
  removed** (with the VAD off the model only replied on `activityEnd`, so every exchange cost two taps
  and Sai could never answer while you were still talking — the tap now toggles mute). **Mute is not a
  replacement:** it silences Sai's output and leaves the mic wide open, so exactly as much bystander
  audio reaches Google. The only control that actually stops capture today is **Pause**, which drops
  the mic and the Live session outright — a manual, all-or-nothing switch, not the per-turn gating this
  item is asking for. Also unanswered: there is no recording indicator for continuous audio (Meta
  requires a visible one for the _camera_; the audio equivalent on face-worn hardware has no answer),
  no documented stance on what Google retains of the PCM and transcripts, and `recallHistory` puts
  prior-session transcripts into the same model context, so the retention question covers conversation
  history too, not just live audio. Treat all of this as unresolved before any non-lab use.
- Footgun: `local.properties` `concierge_url` may point at `localhost:8080` — the git-tracked
  default is staging; double-check before building for a device.

## 6. DAT platform facts & app setup

Carried forward from the retired 2026-07-02 integration plan/runbook — these still hold:

- **minSdk 31** — required by the audio path (`AudioManager.setCommunicationDevice()` for HFP
  routing and runtime `BLUETOOTH_CONNECT` are API 31+); both official DAT samples set 31.
- **Manifest:** `com.meta.wearable.mwdat.APPLICATION_ID` + `com.meta.wearable.mwdat.CLIENT_TOKEN`
  meta-data (fed from `manifestPlaceholders`, empty by default — filled with Developer-Center
  credentials, or left empty when registering through Developer Mode), and a registration callback
  `<intent-filter>` (`VIEW`/`DEFAULT`/`BROWSABLE` + `<data android:scheme="saiwearables">`) on
  `MainActivity` — required for the Meta AI app to return after DAT registration.
- **Permissions:** `BLUETOOTH`, `BLUETOOTH_CONNECT`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
  `INTERNET`, `POST_NOTIFICATIONS` (API 33+), `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`.
  Mic is a standard OS permission; **camera** (and `.speech`) are DAT `Permission` grants made
  through the Meta AI app.
- **Location is asked for once, at sign-in** (`maybeAutoRequestLocation`, remembered in
  `Prefs.locationAutoPrompted`) — never per call and never mid-conversation, because a system
  dialog is unanswerable by someone whose eyes and hands are busy. A decline is respected; Android
  settings is the way back, same contract as the DAT camera grant. Both granularities are requested
  together since the sheet lets the user pick approximate, and `PhoneLocation` honours whichever it
  gets. `CallService` therefore declares `foregroundServiceType="microphone|location"`: it reads
  the fix while foregrounded, and from Android 14 that combination without the declared type is a
  `SecurityException`.
- **Dev Mode / registration:** Meta AI app (`com.facebook.stella`) v272+, glasses firmware
  v125+ (v127 for DAT 0.8); tap App Version 5× to enable Developer Mode; **only one third-party
  app can be registered at a time** (registering a new one auto-unregisters the previous).
- **Audio reality:** the glasses mic is **HFP** (8 kHz mono narrowband unless wideband
  mSBC/LC3 negotiates) with wearer-isolating beamforming; **HFP and A2DP are mutually
  exclusive**. The mic-access research weighed two designs: **A** (one persistent HFP
  session, lo-fi both ways, full-duplex) vs **B** (per-turn HFP↔A2DP switch for hi-fi
  playback, but half-duplex — the mic is down while Sai speaks). `AudioIo` implements
  **Design A**: one persistent HFP/SCO session for the whole call, the model's TTS played
  back over SCO while the mic stays live. This is full-duplex, so **voice barge-in works on
  the glasses** just like on the phone. The tradeoff is playback fidelity — mono, SCO-quality,
  not A2DP hi-fi — which is the deliberate call: we want always-on barge-in far more than
  hi-fi TTS, and A2DP would force the mic down mid-utterance.
- **Glasses gestures (hard SDK ceiling).** DAT 0.8 exposes **no** gesture/touch/wear-state API to
  a third-party app — gestures are hardwired to session lifecycle, and all the app observes is the
  resulting `DeviceSessionState`. Confirmed against the official DAT docs (Wearables MCP): _"Users
  can pause, resume, or stop your session by closing the hinges, taking the glasses off, or tapping
  the glasses."_ The **complete** set of temple gestures is three, none remappable:
  - **tap** → `PAUSED ⇄ STARTED` (pause/resume the call)
  - **tap-and-hold** → `STOPPED` (stops the session → we end the call)
  - **doff / fold / drop** → `STOPPED` — **indistinguishable** from tap-and-hold (same signal, no
    distinguishing end-reason). **Losing Bluetooth is the same signal**, so a call cannot survive the
    glasses being folded, taken off, or walking out of range: it ends. That is a platform property, not
    a bug to fix — so the app makes it _expected_ instead. An in-call line says it up front, and the
    teardown says which one happened as far as we can tell ("Glasses folded, removed, or out of range
    — call ended") in the status **and** a notification, since the wearer may not be looking at the
    phone. It used to tear down silently and reset the status to "Idle".
  - **two-finger "back" tap** → ends a **display** session only (needs the display capability +
    display hardware; N/A on our non-display glasses)

  There is **no double-tap, swipe, or drag**, and no physical capture-button event. So a third
  distinct gesture-bound action (e.g. photo capture) is **not possible** on DAT 0.8 — capture stays
  on the phone button / voice (see §3, §9). The only on-device unknown is whether a
  **capability-less** session (ours attaches none) still receives taps; the docs describe gesture
  handling "during an active stream/session", so if taps don't arrive we would attach a throwaway
  camera stream to keep the session live. ⚠ **That fallback is not free** and shouldn't be treated
  as a drop-in: a camera stream (a) needs the DAT **camera permission** granted, (b) lights the
  **capture/privacy LED** for the whole call (a privacy signal, and a battery draw), and (c)
  reintroduces a camera capability the design deliberately omits. All three hands-free controls
  (pause/resume/end) ride on the unverified "capability-less session delivers taps" assumption, so
  confirm it on real hardware **before** committing to the camera-stream workaround — if taps do
  arrive on a bare session, none of these costs apply.

- Distribution is gated: invite-only release channels during the developer preview, and the iOS App
  Store is blocked outright by the `ExternalAccessory`/MFi requirement. This is the constraint the
  mic-access research found hardest, and it is a platform fact rather than something the app can work
  around — Android-only, side-loaded, for as long as the preview lasts.

## 7. Running it (dev)

**No manual deploy or build commands.** cloud-api staging is deployed by **CI per PR** (nothing to
run locally), and the app runs straight from **Android Studio over USB debugging** (Run ▶). Setup:

1. **Point at the cloud-api under test** — in `meta-android-app/local.properties`:
   - `concierge_url=https://staging.cloud-api.simular.cloud` (the shared staging gateway; the build
     default, so usually nothing to set).
   - To pin **a specific PR's staging revision**, add `sai_version_tag=<the PR's version tag>`; the app
     sends it as the `x-sai-version` header on every cloud-api call so the gateway routes to that
     revision instead of whatever staging is currently serving. Leave it blank against a local server
     — it means nothing there. Check 1 of `ON_DEVICE_CHECK.md` is how you confirm the pin took.
2. **Firebase sign-in config** (once) — `firebase_app_id`, `firebase_api_key`, `firebase_project_id`,
   `web_client_id` from the simular Firebase project (see §5); register the app's package + signing
   SHA-1 there.
3. **Run over USB** — phone in developer/USB-debugging mode, plugged in and on the **staging VPN**;
   select it in Android Studio and Run ▶. Open "sai-fi", **sign in with Google**, pick a machine
   (remembered next launch), Start call.

Logs: `adb logcat | grep SaiFi`. (A local cloud-api is still possible via
`concierge_url=http://localhost:8080` + `adb reverse tcp:8080 tcp:8080`, but staging is the normal
path and the only one that survives unplugging.)

## 8. Testing strategy (device-side)

Layered the same way the server's own suite is — deterministic first, by-ear last:

1. **Cross-port parity fixtures — IMPLEMENTED.** The nudge strings, the activity log and the WS
   protocol exist twice (TypeScript there, Kotlin here), and committed JSON fixtures in
   `app/src/test/resources/parity/` are the seam: agent-event nudges including the prompt-injection
   cases, ask-first, activity-render, `statusText()`/`msSinceTaskStart()` sequences on a fixed injected
   clock, and the shared constants. They are generated by calling the server's real functions, so
   `ConciergeProtocolParityTest` and `ActivityLogParityTest` replaying them here means **TS↔Kotlin drift
   breaks a test, not a demo**. Refreshing them is a manual cross-repository copy — the README's
   "Keeping the parity fixtures in sync" is the whole procedure, and nothing automated does it.
2. **Kotlin JVM unit tests** — `app/src/test/…/saispike/` (run `./gradlew :app:testDebugUnitTest`):
   `ConciergeProtocolTest` (nudge helpers — choice≠approve/deny, link-only never voice-resolves,
   ask-first waits for availability, **prompt-injection fencing**), `ActivityLogTest` (elapsed/steps
   - `msSinceTaskStart` on an injected clock; org.json ships a real impl so JSONObject works
     off-device), plus the two parity suites above. Still to add: effect relay (`getSaiStatus`
     filtered + answered locally, tool-response for every call), the nudge-gating FSM, and PCM helpers
     — these live in `GeminiLiveClient`/`AudioIo`, which depend on Android framework classes, so they
     need Robolectric or instrumentation rather than a plain JVM test.
3. **JVM integration harness** (MockWebServer) — Bearer on both endpoints, 401/403 handling,
   scripted agent-event→nudge→effects round trip, single-flight Live re-mint, WS drop recovery.
4. **On-device audio checks** — Phase 0 gate (spoken exchange + barge-in), AEC sanity (model must
   not hear itself; verify with wired headphones if it does), SCO route + wideband check,
   route-loss drill. Check 7 of [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) is the by-ear form of the
   first two, including the three ways a barge-in fails and which layer each one implicates.
5. **Manual E2E voice smoke** per phase exit — [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) is the short
   form kept here; re-run prior phases' checks as regression.

## 9. Open items

- **SCO full-duplex validation (on-device, by ear):** confirm the model's TTS is intelligible
  and loud enough over the mono SCO link on real glasses, and — critically — that the
  always-live mic + platform AEC keep the model from hearing its own SCO playback and
  self-interrupting (check 7 of [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) is the drill; wired
  headphones on the phone route isolate an AEC fault). Also confirm the `endCall` goodbye (still
  behind the fixed 1.8 s delay) lands before teardown — check 8 there.
- Confirm a **capability-less DAT session delivers temple gestures** (the docs describe gesture
  handling "during an active stream/session"; fallback: attach a throwaway camera stream to keep
  the session live — which the capture stream doubles as). This is the _only_ open gesture
  question — the gesture _set_ itself is settled (§6): three non-remappable gestures, so no third
  action can be gesture-bound. Three-control-by-gesture would require display-capable hardware +
  the display capability's on-glasses buttons (a hardware pivot, not a code change).
- On-device E2E for Phases 1/2/4 checklists; 30-min screen-off battery soak.
- `endCall`: playback-drained signal instead of the fixed 1.8s delay. (The hang-up-vs-work
  question is decided server-side and is not this app's to re-litigate: the persona contract requires
  the model to ask whether to keep or stop running/queued work before `endCall`, interrupting first if
  the answer is stop.)
- Machine-switch: prompt staleness is handled (the `switchMachine` tool response carries a
  context update naming the new machine), but the fuzzy `contains` name match could still
  mis-target similar names.
- Settings persistence: the **selected machine is persisted** (`Prefs`/SharedPreferences — picker,
  call start, and a voice `switchMachine` all update it, so it defaults on the next auto-login); the
  ask-first threshold is still in-memory — add DataStore if it should stick.
- Cold start by voice needs a wake word (mic is off when the service isn't running); HUD status
  waits on display hardware; deferred completion ping (SMS/push) needs an out-of-band transport.
- DAT SDK init is unconditional with errors swallowed — verify registration doesn't silently
  fail when permissions are missing.
- ~~No client `watchdog` handler.~~ **Closed by deletion** — the server watchdog, both wire
  messages and `ConciergeSocket.setWatchdog` are gone. There is nothing to handle, so the missing
  handler is no longer a latent bug; the message tables in
  [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) §2 are the full set.
