# sai-fi

A standalone Android app that puts the **Sai voice concierge** on Meta Ray-Ban glasses.

The phone runs a client-side Gemini Live audio session and relays to Sai's cloud API over a
WebSocket; the glasses are the microphone, speaker and camera, reached through Meta's Device Access
Toolkit via the Meta AI companion app. Talk to Sai hands-free, hand work to an agent running on your
own machine, and hear about it when it is done.

The wire contract between this app and the server —
`POST /v1/concierge/session`, the WS message tables, the close codes, and the five tools this client
is obliged to answer itself — is documented in
[`docs/CONCIERGE_CLIENT_PROTOCOL.md`](https://github.com/simular-ai/simular-pro-unified-ui/blob/main/docs/CONCIERGE_CLIENT_PROTOCOL.md)
in the server repository.

- [`docs/SAI_GLASSES_APP.md`](docs/SAI_GLASSES_APP.md) — this app's architecture: modules, call
  phases, DAT platform facts, dev runbook.
- [`docs/SAI_GLASSES_DEMO.md`](docs/SAI_GLASSES_DEMO.md) — the live-demo runbook, glasses and
  presenter together.

## Prerequisites

Each of these stops a fresh clone dead, and none of them is discoverable from an error message.

**JDK 21, from Android Studio.** Gradle 8.14.1 rejects newer JDKs with a bare `* What went wrong: 26.0.1`.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**`local.properties`.** Copy [`meta-android-app/local.properties.example`](meta-android-app/local.properties.example)
to `meta-android-app/local.properties` and fill it in. Every key is commented there; the ones that
block a build or a call:

| Key | What it is |
| --- | --- |
| `github_token` | A GitHub PAT (classic) with `read:packages`. Meta's DAT SDK resolves from GitHub Packages, and it is on the unit-test compile classpath — without this, even `testDebugUnitTest` cannot run |
| `mwdat_application_id`, `mwdat_client_token` | Your DAT registration, from the Wearables Developer Center. Injected as manifest placeholders, never committed |
| `concierge_url` | The cloud-api base. Defaults to the shared staging gateway |
| `firebase_*`, `web_client_id` | Google sign-in. There is no `google-services.json` — these four values replace it. `web_client_id` is the OAuth **web** client, which is what Credential Manager needs for a server-verifiable ID token |
| `sai_version_tag` | Optional. Pins the app to one server revision via the `x-sai-version` header, so you can test a specific PR's backend instead of whatever staging is serving |

A missing key does **not** fail the build. It becomes an empty `BuildConfig` field and surfaces later
at runtime looking like a bug — so fill in the whole required section.

## Build and test

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd meta-android-app && ./gradlew :app:installDebug
cd meta-android-app && ./gradlew :app:testDebugUnitTest --rerun
```

`--rerun` matters. Without it the test task reports `UP-TO-DATE` from cache and verifies nothing.

There is no CI here yet, so `gradlew` is the whole gate — 87 JVM tests across 13 classes, including
the cross-port parity tests below.

## Registration

The app is unusable until it is registered. Turn on **Developer Mode** in the Meta AI app, then
register from the Wearables Developer Center and put the `APPLICATION_ID` / `CLIENT_TOKEN` in
`local.properties`.

**Only one third-party DAT app can be registered at a time** on a given account — registering this
one unregisters whatever else you had, and you will need to re-register that afterwards.

`app/sample.keystore` is tracked on purpose. It is the debug signing config, from Meta's public
sample, and a **stable debug signature is what the registration binds to**. Note that the root
`.gitignore` ignores `*.keystore` and then negates this one file specifically.

## The presenter

A demo dashboard: the live conversation, the log, call state, glasses photos, and both audio streams,
mirrored from the phone to a laptop.

```bash
cd presenter && npm install && npm run presenter -- --port 8899 --key hunter2
```

Then set `presenter_url` and `presenter_key` in `local.properties`. **DEBUG builds only**, LAN-only
plaintext `ws://`, and nothing is persisted. It can crash, be restarted, or be abandoned mid-demo
with no consequence for the call.

## Keeping the parity fixtures in sync

The nudge strings, the activity log and the WS protocol are implemented twice — TypeScript on the
server, Kotlin here — and JSON fixtures in `meta-android-app/app/src/test/resources/parity/` are what
keep the two ports honest. The Kotlin tests replay them; a drift fails a test.

They are **generated in the server repository**, and this is the one procedure that crosses both:

```bash
# in simular-pro-unified-ui
npm run -w cloud-api concierge:fixtures
cp cloud-api/src/services/concierge/voice/contract/fixtures/*.json \
   <sai-fi>/meta-android-app/app/src/test/resources/parity/
```

While both trees shared a checkout, a server-side test compared the two directories automatically.
After the split nothing does — each side only catches drift against *its own* port. Until a CI job
fetches the fixtures at a pinned ref and diffs them, **this copy step is the entire mechanism, and it
is a human one.**

## Provenance and licensing

This app is derived from Meta's public **CameraAccess** Device Access Toolkit sample. The build
configuration and the manifest still carry Meta's copyright headers; the concierge implementation
under `meta-android-app/app/src/main/java/.../saispike/` is Simular's.

Released under the MIT licence — see [`LICENSE`](LICENSE), which carries both copyright lines. Meta's
sample is also MIT, so the two are compatible.

The two bundled fonts are **not** MIT: JetBrains Mono and Manrope are SIL OFL 1.1, which requires the
licence to travel with the font — including inside the APK. Their texts are in
[`licenses/`](licenses/README.md). Do not rename the font files; the OFL reserves the original names.
