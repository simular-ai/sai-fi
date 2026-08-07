# sai-fi — the Android module

The app itself: a foreground service that runs a Gemini Live audio session, relays to Sai's cloud API
over a WebSocket, and drives the glasses' microphone, speaker and camera through Meta's Device Access
Toolkit.

Start at the [repository README](../README.md) for what this is and how to get a call running. This
file is the module's own reference — the one thing you cannot get anywhere else being the
**secrets table** below, which is exhaustive.

- `docs/SAI_GLASSES_APP.md` — architecture: modules, call phases, DAT platform facts.
- `docs/SAI_GLASSES_DEMO.md` — the live-demo runbook.

Derived from Meta's public **CameraAccess** DAT sample; the build files and manifest still carry
Meta's copyright headers, and the concierge implementation under `app/src/main/java/.../saispike/`
is Simular's.

## Prerequisites

- **JDK 21**, from Android Studio's bundled runtime. Gradle 8.14.1 rejects the newer system JDKs with
  a bare `* What went wrong: 26.0.1`:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Android SDK 36, and Android Studio Narwhal (2025.1.1) or newer.
- A filled-in `local.properties` — see below. `github_token` is the one that blocks the build itself.
- Meta Ray-Ban glasses paired to the Meta AI app, with Developer Mode on, to run against hardware.
  Unit tests need none of it.

## Secrets and local configuration

Every credential this app needs lives in **`meta-android-app/local.properties`**, which is gitignored
(twice — `/local.properties` and `local.properties` in `.gitignore`) and never committed. There is no
`.env`, and — unlike most Firebase apps — **no `google-services.json`**: the Google Services Gradle
plugin is not applied, so a `google-services.json` dropped into `app/` is ignored by the build.
Firebase is configured manually in `SaiAuth.kt` from the values below.

Missing keys are not a build error. `app/build.gradle.kts` defaults every one of them to an empty
string, so the app always compiles — it just silently loses whichever feature the key powers. Check
this table when something "builds fine but doesn't work".

| Key | Needed for | If missing |
| --- | --- | --- |
| `sdk.dir` | Android SDK location | Gradle can't configure (Android Studio writes this for you) |
| `github_token` | Pulling the DAT SDK from GitHub Packages | Dependency resolution fails — **the one key that breaks the build** |
| `mwdat_application_id` | Meta DAT app registration (manifest placeholder) | Builds, but Meta AI won't grant a device session |
| `mwdat_client_token` | Meta DAT app registration (manifest placeholder) | Same as above |
| `concierge_url` | cloud-api base URL | Defaults to `https://staging.cloud-api.simular.cloud` |
| `sai_version_tag` | `x-sai-version` header, to pin a PR's staging revision | Shared staging default |
| `firebase_app_id` | Google Sign-In | Sign-in disabled (`SaiAuth.isConfigured` false) |
| `firebase_api_key` | Google Sign-In | Sign-in fails |
| `firebase_project_id` | Google Sign-In | Sign-in fails |
| `web_client_id` | Google Sign-In — the **Web** OAuth client id | Sign-in disabled |
| `presenter_url` | Demo dashboard feed (debug builds only) | Derived from `concierge_url` when that host is a LAN address; otherwise off |
| `presenter_key` | Demo dashboard shared secret | Presenter accepts unauthenticated publishers |
| `release_store_file` / `release_store_password` / `release_key_alias` / `release_key_password` | Signing release APKs | No `release` signing config → `app-release-unsigned.apk`, which won't install |

### Where to get each value

- **`github_token`** — a GitHub *classic* personal access token with the `read:packages` scope. It
  authenticates against `maven.pkg.github.com/facebook/meta-wearables-dat-android`. You can instead
  export it as the `GITHUB_TOKEN` environment variable; `settings.gradle.kts` prefers the env var.
  See [SDK for Android setup](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android#step-2-add-the-sdk-to-gradle).
- **`mwdat_application_id` / `mwdat_client_token`** — from the app registered in the Wearables
  Developer Center. The client token is a real credential; it used to be hardcoded in
  `AndroidManifest.xml` and was moved out precisely because this tree is open-source.
- **Firebase keys** — from the `ai.simular.saiglasses` Android app in the simular Firebase project.
  If you have a `google-services.json` for that project you can read all four out of it (the build
  won't use the file, but it's a convenient source): `firebase_app_id` is the client's
  `mobilesdk_app_id`, `firebase_api_key` is its `api_key[0].current_key`, `firebase_project_id` is
  `project_info.project_id`, and `web_client_id` is the `oauth_client` entry with `"client_type": 3`
  (**web**, not the Android client — Firebase rejects the token otherwise).
- **Presenter keys** — start the laptop server with
  `npm install && npm run presenter -- --key <secret> --port 8899` from `presenter/`; it prints the
  exact `presenter_url` / `presenter_key` lines to paste in.

### Signing keys

`app/sample.keystore` **is committed on purpose** and is not a secret. Meta AI binds app registration
to a signing signature, so debug builds need a *stable* one — a per-machine auto-generated debug key
would break registration on every fresh checkout. Its password, alias, and key password are all
`sample`, and its SHA-1 is:

```
D4:AC:72:C6:E0:D7:95:E6:2F:3B:61:A6:AE:C8:DD:1C:F5:D8:77:EB
```

Register that SHA-1 against the `ai.simular.saiglasses` app in the Firebase project, or Google
Sign-In will be rejected on debug builds.

Release builds must never use it. The `release_*` keys above point at a real keystore kept outside
the repo; when they're absent the build deliberately produces an unsigned APK rather than quietly
shipping one signed with a key published in Meta's public sample repo.

### Example

```properties
# meta-android-app/local.properties — gitignored, never commit
sdk.dir=/Users/you/Library/Android/sdk
github_token=ghp_xxxxxxxxxxxxxxxxxxxx

mwdat_application_id=
mwdat_client_token=

concierge_url=https://staging.cloud-api.simular.cloud
sai_version_tag=

firebase_app_id=1:000000000000:android:0000000000000000
firebase_api_key=AIzaSy...
firebase_project_id=simular-xxxxx
web_client_id=000000000000-xxxxxxxx.apps.googleusercontent.com

presenter_url=
presenter_key=
```

## Building and testing

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest --rerun
```

`--rerun` matters: without it the test task reports `UP-TO-DATE` from cache and verifies nothing.
There is no CI for this module, so those two commands are the entire gate — 87 JVM tests across 13
classes, including the cross-port parity tests that hold the Kotlin and TypeScript implementations of
the nudge strings, the activity log and the WS protocol to the same fixtures.

In Android Studio: open the `meta-android-app` folder (not its parent), fill in `local.properties`,
**File > Sync Project with Gradle Files**, then Run.

## Running

1. Turn on **Developer Mode** in the Meta AI app, and pair your glasses.
2. Launch sai-fi and sign in with Google.
3. Register with Meta AI when prompted — this is the DAT handshake, and only **one** third-party DAT
   app can be registered at a time on an account.
4. Pick the machine your agent runs on, and start the call. From then on it is hands-free: the temple
   button mutes, and everything else is spoken.

If the glasses report the on-device app is outdated, or firmware is required, the connection screen
offers the update flows.

## Troubleshooting

For the Device Access Toolkit itself, see Meta's
[developer documentation](https://wearables.developer.meta.com/docs/develop/dat/) and
[discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions).

For the call — what each phase does, what the log lines mean, and the on-device checklist — see
`docs/SAI_GLASSES_APP.md`.

## License

MIT — see [`LICENSE`](../LICENSE) in the repository root. Meta's CameraAccess sample, from which this
is derived, is also MIT.
