# meta-ios-app — the iOS client

The iOS port of [`meta-android-app`](../meta-android-app): the Sai voice concierge on Meta Ray-Ban
glasses. Same two links, same conversation state machine, same bundled voice profile — see
[`docs/SAI_GLASSES_APP.md`](../docs/SAI_GLASSES_APP.md) for the architecture and
[`docs/VOICE_FSM.md`](../docs/VOICE_FSM.md) for the state machine, both of which describe this app as
much as they describe the Android one.

Derived from Meta's public iOS **CameraAccess** DAT sample, the same way the Android app was derived
from the Android one.

## Status

**In progress.** What is real and verified today:

| | |
| --- | --- |
| `SaiFiCore/` | The pure half — FSM, protocol, activity log, AgentEventRouter, VoiceConverters, LiveTurnGate, VoiceProfile, every pure policy. **410 checks passing** (`swift run saifi-check`) |
| `SaiFi.xcodeproj` | Seeded from the CameraAccess sample, renamed, Info.plist / xcconfig / Secrets wired, SaiFiCore linked as a local SPM package. Compiles for generic iOS without signing; Simulator runtimes are not required for that |
| Everything else | Not written yet — glasses session, audio, Gemini Live client, agent HTTP, UI |

The check registry is pinned at ≥410 in `GateTests` so a shrinking catalog cannot go green quietly.

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
