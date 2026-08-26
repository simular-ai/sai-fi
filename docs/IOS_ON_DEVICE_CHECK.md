# On-device check — iOS

**This checklist has not been run on a physical iPhone or real glasses yet.** The iOS port is
verified in the Simulator (MockDeviceKit) and by `swift run saifi-check`. Until someone walks this
file on hardware, do not treat HFP duplex, Meta AI registration, or a live agent POST as proven.

The same ten checks as [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md), for the iOS client in
`meta-ios-app/`. Behaviour, wording, and failure modes are identical; only the build, the secrets
file, the log surface, and a handful of platform facts change. Run this after any change that
touches the iOS call.

**Why it can't be automated.** `swift run saifi-check` (471 checks) covers the ports, the state
machine and the protocol. None of it exercises the persona prompt end to end, the FSM against a
real model, the audio path, or the camera path. There is no test for "did Sai talk over me".

**You need:** the glasses, a paired iPhone, and a reachable Sai API. The iOS Simulator cannot
answer this checklist: there is no Bluetooth, so `.bluetoothHFP` never appears, and Meta AI
registration is a real-phone round trip.

---

## 0. Pre-flight

### The server

The app talks to whatever `SAI_API_URL` in `Secrets.xcconfig` points at — production
(`https://api.sai.simular.ai`) by default. Confirm it is up before you touch the glasses:

```bash
SAI_API_URL=$(grep '^SAI_API_URL' meta-ios-app/Secrets.xcconfig | sed 's/.*= *//; s:/$()::')
SAI_API_URL="${SAI_API_URL:-https://api.sai.simular.ai}"
curl -s "$SAI_API_URL/health"                                              # → {"status":"ok",…}
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$SAI_API_URL/v1/agents/message"
#   401 → the agent API is there (unauthenticated, as expected)
#   404 → you are pointed at something that is not the Sai API
```

> **The voice half does not touch this server.** Audio goes straight from the phone to Google with
> your own key, and the prompt ships in the app bundle. What `SAI_API_URL` reaches is your AGENT —
> so a server that is down means tasks fail, not that Sai stops talking.

Every run here wakes a real VM and bills a real agent. Prefer the off-device gate for anything that
does not actually need the glasses:

```bash
cd meta-ios-app/SaiFiCore
swift run saifi-check     # 471 checks, no Xcode, no device
```

### Secrets

Copy [`meta-ios-app/Secrets.xcconfig.example`](../meta-ios-app/Secrets.xcconfig.example) to
`meta-ios-app/Secrets.xcconfig` if you have not already. Fill in:

- `GEMINI_API_KEY` — your own key from Google AI Studio. Audio never touches a Simular server.
- Firebase **iOS** values (`FIREBASE_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`,
  `IOS_CLIENT_ID`, `REVERSED_CLIENT_ID`). **These are not the Android ones.** Add an iOS app to the
  same Firebase project for bundle id `ai.simular.saiglasses`.
- `WEB_CLIENT_ID` — the OAuth **web** client, unchanged from Android. That is the audience the Sai
  API verifies.
- `SAI_API_URL` — already production in the example. xcconfig treats `//` as a comment, so a URL
  must be written `https:/$()/host`.

There is no `GoogleService-Info.plist`.

### The app

Needs Xcode (the DAT SDK slices include the Simulator, but Command Line Tools alone is not enough).
This repo's local loop uses Xcode 27 beta:

```bash
export DEVELOPER_DIR=/Users/jamielim/Downloads/Xcode-beta.app/Contents/Developer
open meta-ios-app/SaiFi.xcodeproj
```

Build and run on a physical iPhone. Internal TestFlight is the distribution path; the App Store is
closed (DAT uses `ExternalAccessory` and ships no privacy manifest — Meta has to authorise each
third-party app on their MFi Product Plan first).

### The glasses

1. Meta AI app → **Developer Mode ON**, glasses paired and connected.
2. Launch **sai-fi**. Signed out, the only thing on screen is *Sign in with Google* — that is the
   gate, not a stuck screen. Sign in.
3. Register with Meta AI when prompted (Home → **Register glasses**). Remember: **only one
   third-party DAT app can be registered at a time** — this unregisters the Android build
   (`ai.simular.saiglasses`) on the same Meta account, and vice versa. The URL scheme is `saifi://`.
4. **Settings → Developer mode ON.** This is sai-fi's own switch, not Meta's, and it is off by
   default in every build including debug. Without it there is no Logs tab. It persists.
5. Home: pick your machine, then Start the call.

### Watch it happen

The Logs tab from step 4, plus Xcode's console filtered for `SaiFi`. There is no `adb logcat`.

The presenter dashboard is Android DEBUG-only and is **not** on iOS (out of scope).

---

## 1. The ten checks

Identical to [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) §1. The Kotlin type names in that file map
as follows — behaviour is the same, the files live under `meta-ios-app/`:

| Android | iOS |
| --- | --- |
| `CallService` / `CallController` | `SaiFi/Call/CallCoordinator.swift` |
| `ConciergeProtocol.kt` | SaiFiCore `ConciergeProtocol.swift` |
| `GreetingGate` / `HangupPolicy` / `HeldNudgeQueue` / `LeavingWorkPolicy` / `WakePolicy` / `MachineSwitcher` | SaiFiCore, same names |
| `GlassesCamera` | `SaiFi/Glasses/GlassesCamera.swift` |
| `AudioIo` | `SaiFi/Call/AudioIo.swift` |
| `GeminiLiveClient` | `SaiFi/Call/GeminiLiveClient.swift` |
| `HttpAgentBridge` | SaiFiCore `HttpAgentBridge.swift` |
| `ApprovalHandlers.kt` / `TaskHandlers.kt` | SaiFiCore `Fsm/` |

Run all ten, including the lettered cases under 6, 9 and 10. iOS-specific things to listen for:

- **Check 5 (mute).** Temple tap is mute, not pause. Pause is the on-screen button. Same as Android.
- **Check 7 (barge-in).** Self-interruption on the glasses route is the unverified one: Simulator
  has no Bluetooth, so this check is the first time HFP duplex and platform AEC are proven. Retry
  on the phone route with wired headphones to isolate AEC before blaming the glasses mic.
- **Check 4 (capture).** iOS DAT 0.8 refuses a second `DeviceSession`, so capture attaches to the
  gesture session (`stream.stop()` then `addStream`). A denied capture with no **Grant glasses
  camera** button is the same Meta AI "allow once" / firmware-update trap as Android.

---

## 2. Recording the result

Same as Android: pass / fail / not-reached for each of the ten and every lettered case, with a log
excerpt and what you heard. Compare against the last iOS build you ran this on, not against
Android, when deciding whether a by-ear miss is a regression.

## 3. If something goes wrong

| Symptom | Likely cause |
| --- | --- |
| No session at all, app shows an error | Sai API unreachable **from the phone**, or `sai_api_url` empty (`no sai_api_url` in the log). A LAN address that resolves on your Mac need not resolve on the handset |
| `401` on a Sai API call | Signed out, or the Firebase ID token expired. Sign out and back in. Confirm the Firebase values are the **iOS** app's, not Android's |
| Sign-in button disabled | `Secrets.xcconfig` is missing the four Firebase keys. The gate names them |
| Glasses never connect | DAT registration lapsed, or another DAT app (including the Android build) claimed the single registration slot |
| Capture denied, and no **Grant glasses camera** button | Same as Android: Meta AI grants once; a firmware update can withdraw it. Greyed out means the glasses aren't linked |
| Audio one-way | Check the Audio line on Home. Glasses HFP needs the glasses connected **before** Start. Simulator will always say phone |
| Sai talks through a barge-in | `— barge-in —` in Logs means VAD fired (playback/flush). No such line: mic route or noise gate |
| Sai cuts itself off with nobody speaking | AEC. Repeat on the phone route with wired headphones |
| Nothing in a presenter | There isn't one on iOS |

**Going further.** The spoken script that turns these ten checks into three calls is
[`ON_DEVICE_DEMO_FLOW.md`](ON_DEVICE_DEMO_FLOW.md) — it is platform-agnostic.
