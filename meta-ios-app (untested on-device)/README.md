# meta-ios-app (untested on-device) — the iOS client

The iOS port of [`meta-android-app`](../meta-android-app): the Sai voice concierge on Meta Ray-Ban
glasses. Same two links, same conversation state machine, same bundled voice profile — see
[`docs/SAI_GLASSES_APP.md`](../docs/SAI_GLASSES_APP.md) for the architecture and
[`docs/VOICE_FSM.md`](../docs/VOICE_FSM.md) for the state machine, both of which describe this app as
much as they describe the Android one.

Derived from Meta's public iOS **CameraAccess** DAT sample, the same way the Android app was derived
from the Android one.

## Status

**In progress. Not tested on a physical iPhone or real Ray-Ban Meta glasses.** Simulator +
`saifi-check` are the verification so far. HFP duplex, Meta AI registration, and a live agent POST
need hardware and accounts.

| | |
| --- | --- |
| `SaiFiCore/` | The pure half — FSM, protocol, activity log, AgentEventRouter, VoiceConverters, LiveTurnGate, VoiceProfile, every pure policy, agent HTTP (no network), and the conversation harness. **471 checks passing** (`swift run saifi-check`) |
| `SaiFi.xcodeproj` | Seeded from the CameraAccess sample, renamed, Info.plist / xcconfig / Secrets wired, SaiFiCore linked as a local package. FirebaseAuth + GoogleSignIn via SPM. Compiles for generic iOS without signing |
| `SaiFi/Glasses/` | `GlassesGestureSession` + `GlassesCamera`. MockDeviceKit on iPhone 17: a second `DeviceSession` is refused, so capture attaches to the gesture session; `stream.stop()` then `addStream` still delivers frames. 7/7 `MockDeviceTests` green |
| `SaiFi/Call/` | `AudioIo`, `GeminiLiveClient`, `CallCoordinator` (Android `CallService` + `CallController` merged). Simulator tests green. **HFP duplex unverified on hardware** |
| `SaiFi/Support/` + `SaiFi/UI/` | Prefs, PhoneLocation, Theme, ended-reason notification, `SaiAuth`, the four screens (sign-in gate, Home, Settings, Logs). CameraAccess sample UI is no longer the user-facing app |
| Live Gemini / live agent / Meta AI registration | Need keys and a phone. **Not verified on-device** |

The check registry is pinned at ≥471 in `GateTests` so a shrinking catalog cannot go green quietly.

## How to test

Three layers. The first needs no device; the last is the only one that proves the glasses.

### 1. Parity — no Xcode, no device

```bash
cd "meta-ios-app (untested on-device)/SaiFiCore"
swift run saifi-check     # 471 checks
swift test                # the same checks under XCTest
```

This is the Android↔iOS contract. If it is green, the FSM, the spoken strings, and the protocol
match the goldens.

### 2. Simulator — no glasses, no Bluetooth

The iOS Simulator **cannot** talk to a real pair of Ray-Ban Meta glasses. There is no host Bluetooth
passthrough, and Meta AI (the companion) is a physical-device app. The stand-in is Meta's
**MockDeviceKit**, already linked in this target.

Copy [`Secrets.xcconfig.example`](Secrets.xcconfig.example) to `Secrets.xcconfig` and fill in at
least `GEMINI_API_KEY`. Firebase keys are required for a real sign-in; Google Sign-In also needs an
**iOS** OAuth client (`IOS_CLIENT_ID` / `REVERSED_CLIENT_ID`), which is not the Android one. Without
those, a DEBUG build offers **Continue without account** so you can still reach Home and start a
Mac-mic Gemini call. Agent POSTs will 401 until you sign in.

```bash
export DEVELOPER_DIR=/Users/jamielim/Downloads/Xcode-beta.app/Contents/Developer   # if using the beta
open SaiFi.xcodeproj
```

Run on **iPhone 17 / iOS 27**. DEBUG overlay, **bottom-right**: **Mock glasses** (ladybug). Tap it,
then **Set up Simulator glasses** — fakes DAT registration, pairs a Ray-Ban Meta, powers/unfolds/dons
it, and plants `plant.mp4` / `plant.png` so capture has a still. Temple **Tap** = mute, **Tap & Hold**
= end call.

Then: Continue without account (or Sign in) → Home → Start. Audio line stays `phone` (Mac mic). Agent
POSTs 401 until you sign in; that must not hang up the Gemini call. It does **not** prove HFP, Meta AI
registration, or a live agent.

Unit tests. DAT `Wearables.configure()` is process-global, so parallel clones crash — keep it serial:

```bash
export DEVELOPER_DIR=/Users/jamielim/Downloads/Xcode-beta.app/Contents/Developer   # if using the beta
xcodebuild test -scheme SaiFi \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:SaiFiTests \
  -parallel-testing-enabled NO
```

### 3. Phone + glasses

Pre-flight the API, then the ten-check script:

```bash
curl -s https://api.sai.simular.ai/health
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.sai.simular.ai/v1/agents/message
# 401 is the pass (unauthenticated)
```

1. Meta AI → Developer Mode ON. Registering this app **unregisters** the Android DAT app on the
   same Meta account.
2. Fill `Secrets.xcconfig` with the **iOS** Firebase app values (bundle id `ai.simular.saiglasses`) plus
   Gemini and `WEB_CLIENT_ID`.
3. Run on a physical iPhone. Sign in, Home → Register glasses, Settings → Developer mode ON, pick
   a machine, Start.
4. Walk [`docs/IOS_ON_DEVICE_CHECK.md`](../docs/IOS_ON_DEVICE_CHECK.md) — the same ten checks as
   Android.

What this still cannot claim until you run it: HFP duplex / AEC on the glasses, a live
`POST /v1/agents/message`, and a real Meta AI registration round trip. Those need your accounts
and hardware.

## The two halves, and why they are separate packages

**`SaiFiCore/` is a local SwiftPM package with no MWDAT, no AVFoundation, no SwiftUI, no UIKit.** It
holds the conversation state machine, the effect grammar, every string the concierge speaks, and the
pure policies. That is the same boundary [`docs/VOICE_FSM.md`](../docs/VOICE_FSM.md) §10 draws on
Android, where the FSM stays dispatcher-agnostic so the golden catalog can run as plain JVM tests —
made structural here instead of conventional.

The practical payoff: **the core's gate needs no Xcode and no simulator.**

```bash
cd SaiFiCore
swift run saifi-check     # the gate, with no test framework at all
swift test                # the same checks under XCTest, where one is available
```

`saifi-check` exists because a machine with only Command Line Tools installed has neither `XCTest`
nor `Testing`, and a gate you cannot run is not a gate. Both entry points walk one registry
(`Support/Checks.swift`), so nothing is asserted twice.

**The app target** holds everything that touches the platform: the DAT session and gestures, the HFP
audio path, the Gemini Live socket, the agent link, and the UI. It depends on `SaiFiCore`.

## Building the app

Needs **Xcode** (the SDK slices are `ios-arm64` and `ios-arm64_x86_64-simulator`, so the Simulator
works, but Command Line Tools alone is not enough):

```bash
open SaiFi.xcodeproj                 # then fill in Secrets.xcconfig and Run
xcodebuild build test -scheme SaiFi -destination 'platform=iOS Simulator,name=iPhone 17'
```

Copy [`Secrets.xcconfig.example`](Secrets.xcconfig.example) to `Secrets.xcconfig` first. Every key is
documented there, including the two xcconfig traps and the fact that the Firebase values are **not**
the Android ones.

Deployment target iOS 17.0; DAT SDK pinned to `0.8.0` via Swift Package Manager. Unlike the Android
build there is no `github_token` — the iOS SDK is a public repo, not GitHub Packages.

## Running it without glasses

The DAT SDK ships `MockDeviceKit`, and it goes further than you would expect: it fakes registration
*and* the camera permission, so the Meta AI app is not involved at all. In the Simulator you get the
session lifecycle, the temple gestures (`captouch.tap()` / `tapAndHold()` — coverage the Android CI
does not have), video from an HEVC file or the Mac's camera, and photo capture. `AVAudioSession` uses
the Mac's mic and speakers, so you can hold a real conversation with Sai on a laptop.

What the Simulator cannot tell you: there is no Bluetooth, so `.bluetoothHFP` never appears in
`availableInputs` and the whole audio-route path is untested there. That, the glasses mic's
narrowband character, and the real Meta AI round trip are device-only.

## The parity gate

`Tests/SaiFiCoreTests/Resources/parity/*.json` are a **pinned copy** of the Android fixtures — see
`PINNED_REF.txt`. They are the only thing holding two implementations of the same wording equal,
which is the drift that killed the TypeScript/Kotlin pair before this port existed
([`docs/CONCIERGE_CLIENT_PROTOCOL.md`](../docs/CONCIERGE_CLIENT_PROTOCOL.md) §8 asks a port to pin a
ref and diff it, and this is that pin).

`speech.json` is new: it was added to the Android generator as part of this port, because checking
turned up that `fsm/Speech.kt` — all fifteen lines the concierge speaks about its own queue — was
pinned by nothing on either side.

**When you reword a pinned string on either platform**, regenerate on the Android side, read the
diff, then copy the file across and bump `PINNED_REF.txt`:

```bash
cd ../meta-android-app
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SAI_REGEN_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun --tests "*RegenerateGoldensTest*"
git diff app/src/test/resources/parity/
```

## Distribution

**The App Store is closed to this app**, and not because of anything in it: the DAT SDK uses the
`ExternalAccessory` framework and ships no privacy manifest, so Apple requires *Meta* to authorise
each third-party app on their MFi Product Plan first
([facebook/meta-wearables-dat-ios#149](https://github.com/facebook/meta-wearables-dat-ios/issues/149)
is that request, still unanswered). Meta's own docs say publishing is not currently supported.

Use **internal TestFlight** — up to 100 App Store Connect users on your team, and no Beta App Review
at all. External TestFlight goes through Beta App Review, which is where the MFi rejection lands.

On the Meta side you also need Developer Mode per glasses pair, a Wearables Developer Center **iOS**
app config (a bundle id, not a package name — and no hyphens allowed in it), a written permission
justification that Meta reviews, and a release channel with your testers on it. The release channel
does **not** ship the binary: it authorises registration for those accounts, and the IPA still
arrives via TestFlight. A tester needs to be on both lists.
