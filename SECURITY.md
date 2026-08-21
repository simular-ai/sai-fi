# Security policy

## Supported versions

Only the latest `0.x` tag is supported. Please report issues against `main` or that tag.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/simular-ai/sai-fi/security/advisories/new)
on this repository. Do not open a public issue or pull request that includes secrets, tokens, a
Gemini key, or a DAT client token.

## What this app does with credentials

This is a client. Several credentials live on the device by design:

| Credential | Where it lives | What to know |
| --- | --- | --- |
| Gemini API key | `local.properties` → compiled into the APK as `BuildConfig.GEMINI_API_KEY` | Audio goes phone ⇄ Google and never touches Simular servers. A debug APK you share **contains your key**. Do not publish or hand out a build that has one. |
| Sai Firebase config | `local.properties` → `BuildConfig` | Identifies the Firebase project used for Google sign-in. The Sai API verifies the resulting ID token. |
| DAT `CLIENT_TOKEN` | `local.properties` → AndroidManifest placeholder | A Meta Wearables credential. Never committed. |
| GitHub PAT (`github_token`) | `local.properties` or `GITHUB_TOKEN` | Needed only to resolve Meta's DAT SDK from GitHub Packages. Never committed. |
| Presenter key | `local.properties` | DEBUG builds only, LAN-only plaintext `ws://`. Nothing is persisted. |
| Debug keystore | `app/sample.keystore`, password `sample` | Tracked on purpose: Meta AI binds DAT registration to this signature. **Release builds must not use it.** |

The Sai API sees Firebase ID tokens and agent traffic, not Live audio. Mute silences Sai's output
and leaves the microphone open; Pause is the control that actually stops capture. See
[`docs/SAI_GLASSES_APP.md`](docs/SAI_GLASSES_APP.md) §4.

## Please don't

- Commit `local.properties`, `google-services.json`, or a release keystore.
- File a Gemini key, DAT token, or GitHub PAT in an issue.
- Ship a release APK signed with `sample.keystore`.
