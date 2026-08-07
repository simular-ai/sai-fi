# Publishing this module as open source

**Status:** not started, 2026-08-07. Findings from an audit of `meta-android-app/` at `tmp/integrate`.
This module and `scripts/presenter/` leave the monorepo for **`simular-ai/sai-fi`**; see
`docs/plans/2026-08-05-glasses-voice-concierge-cleanup.md` Phase 4 for the split procedure. This doc
covers only what publication itself requires — a checklist that travels with the module, not a design
doc for the split.

Items 1–4 must be done before the repo is public. Items 5–8 should be, because the first outside
reader hits them immediately. Items 9–11 are decisions that are far cheaper now than after external
forks exist.

---

## 1. Rotate the DAT `CLIENT_TOKEN`

**Blocker. Treat the current token as exposed.**

The value in `local.properties` today is byte-identical to the one committed at `ef7be72bd` and
removed at `ea4b8bf0d`. Taking it out of the working tree did not un-expose it: the commit is
reachable from four local branches and four pushed `origin` refs, including
`origin/feat/glasses-filming-modes` and `origin/backup/glasses-pre-split-rebuild`.

sai-fi's history is a single root commit, so the token never reaches the public repo — but it is
readable by anyone with access to this one, which is a broader set than it should be.

- [ ] Rotate the client token in the Wearables Developer Center
- [ ] Update `local.properties` on every machine that builds the app
- [ ] Confirm the old token no longer grants a device session

Verify the new value never entered history:

```bash
git log --all --oneline -S"$(grep '^mwdat_client_token=' meta-android-app/local.properties | cut -d= -f2-)"
```

Empty output is the pass condition.

## 2. Ship a LICENSE at the repo root

**Blocker.** The repo has no LICENSE anywhere today (`packages/sai-cli/LICENSE` is unrelated). Four
files carry Meta copyright headers citing "the LICENSE file in the root directory of this source
tree", so those references currently dangle:

```
meta-android-app/build.gradle.kts
meta-android-app/settings.gradle.kts
meta-android-app/app/build.gradle.kts
meta-android-app/app/src/main/AndroidManifest.xml
```

The reference only resolves once `meta-android-app/` is a folder inside sai-fi and sai-fi has a root
LICENSE.

- [ ] Confirm the license on Meta's `meta-wearables-dat-android` sample — it governs the derived files
- [ ] Add that license at sai-fi's root rather than picking a fresh one
- [ ] Re-read the four headers once it exists and confirm each resolves

## 3. Bundle the font licenses

**Blocker, and the easiest to miss** — invisible while the repo is private, a compliance gap the
moment it is not.

`app/src/main/res/font/` ships two variable fonts with no accompanying license text:

| Font | File | License |
| --- | --- | --- |
| JetBrains Mono | `jetbrains_mono_variable.ttf` | SIL OFL 1.1 |
| Manrope | `manrope_variable.ttf` | SIL OFL 1.1 |

OFL 1.1 requires the license accompany any redistribution of the font files.

- [ ] Confirm each font's actual upstream license before writing it down — do not assume from the name
- [ ] Add the OFL 1.1 text alongside the fonts (`res/font/OFL.txt`, or a `licenses/` folder)
- [ ] Note both in a root `NOTICE` or the README's attribution section

## 4. Run the on-device call

**Blocker, carried over from the cleanup plan.** The last unrun acceptance criterion, covering the
audio and camera paths. Gates the PR and the sai-fi push.

---

## 5. Commit `local.properties.example`

It exists in the working tree and is **untracked**. It is the single most useful file here for an
outside reader — every key, why it exists, and what breaks without it — and it publishes nothing
secret.

- [ ] `git add meta-android-app/local.properties.example`
- [ ] Fix one detail: it says absent `release_*` keys make `assembleRelease` "fail loudly". They do
      not. `signingConfigs.findByName("release")` returns null and AGP emits
      `app-release-unsigned.apk` — uninstallable, but not an error. The README says this correctly.

## 6. Rewrite the README's first half

Everything above the "Secrets and local configuration" section is still Meta's sample text. It is
titled **"Camera Access App"** and describes "a sample Android application demonstrating integration
with Meta Wearables Device Access Toolkit". The app is sai-fi, a voice concierge.

- [ ] Retitle and rewrite the intro and Features list around the call, not photo capture
- [ ] Rewrite Running — the current steps are the sample's photo flow
- [ ] Fix the JDK prerequisite: it says "JDK 17 or newer", but the module is built with Android
      Studio's bundled JDK 21 (`compileOptions` targets 17, which is a different thing)
- [ ] Point the Secrets section at `local.properties.example` rather than repeating the key list
- [ ] Leave the rest of the Secrets section as-is; it was written against the current build

## 7. Add a CI build

Nothing in `.github/workflows` references `meta-android-app` or `gradlew` — the module has never been
compiled by CI. A public repo with no build check breaks on the first outside PR.

- [ ] Add a workflow running `./gradlew :app:assembleDebug` and `:app:test`
- [ ] The DAT SDK needs a `read:packages` token, and it is needed for **tests too** — the DAT modules
      are on the unit-test compile classpath. `settings.gradle.kts` already prefers the `GITHUB_TOKEN`
      env var over `local.properties`, and Actions provides one
- [ ] 10 test classes / 53 tests currently pass locally — that is the baseline to hold

## 8. State the provenance

- [ ] Say plainly in the README that this derives from Meta's DAT camera-access sample, with a link
- [ ] Keep the Meta copyright headers on the four files that carry them

---

## 9. Rename the `com.meta.wearable…` package

Every Kotlin source sits under `com.meta.wearable.dat.externalsampleapps.cameraaccess`, with the
app's own code in a `saispike` subpackage. Publishing Simular-authored code under Meta's reverse-DNS
namespace, in a Simular-owned public repo, is confusing at best.

The rename is deliberately deferred and bundled with the `applicationId` change, because Meta AI's
DAT registration binds to `ai.simular.saiglasses` — changing it forces re-registration in the
Wearables Developer Center plus a fresh install, and only an on-device call confirms it worked.
Registration is also exclusive: only one third-party DAT app per account, so re-registering
unregisters whatever else is bound.

**The argument for doing it at the split rather than after:** the cost is re-registration plus one
on-device call, which item 4 already requires. That cost only grows once external forks and issue
links exist. `saispike` also reads as a leftover spike name.

- [ ] Decide: rename at the split, or accept Meta's namespace in a public Simular repo
- [ ] If renaming, fold it into the same on-device verification run as item 4

## 10. Delete `app/google-services.json`

The Google Services Gradle plugin is **not applied** — `SaiAuth.kt` builds `FirebaseOptions` by hand
from `local.properties`. The file is read by nothing.

It is gitignored, so it will not publish. But it is a live Firebase config for the `simular-note`
project, including the `ai.simular.sai` desktop client and an iOS bundle id unrelated to this module.

- [ ] Delete it from the working tree so it cannot be committed by accident
- [ ] Keep the `google-services.json` line in `app/.gitignore` as a backstop

## 11. Fix the presenter references

`app/build.gradle.kts:66` tells the reader to run the presenter with
`npm run presenter --workspace=scripts`. **That script does not exist** — `scripts/package.json` has
only `chat`, `dev:cloud-agent`, `send-machine-event` and `verifier`. The working invocation, per
`scripts/presenter/server.ts:11`, is:

```bash
npx tsx scripts/presenter/server.ts --key <secret> --port 8899
```

The presenter also moves to sai-fi, so the path changes with the split.

- [ ] Fix the command in the `build.gradle.kts` comment
- [ ] Update the path in that comment and the README once sai-fi's layout is settled

---

## Checked and clean

Recorded so nobody re-audits these:

- **No personal identifiers** — no author emails or names in any tracked file in the module
- **No internal hostnames** beyond the documented `https://staging.cloud-api.simular.cloud` default
  and the `ai.simular.saiglasses` intent actions, both of which are meant to be public
- **No `.idea` files tracked**; `meta-android-app/.gitignore` covers `/local.properties` and `/.idea`,
  `app/.gitignore` covers `google-services.json`
- **All twelve manifest permissions** map to features the app actually uses — Bluetooth, camera,
  audio, location, foreground service, notifications
- **`app/sample.keystore` is committed on purpose** and is not a secret. Meta AI binds registration to
  a signing signature, so debug builds need a stable one; a per-machine auto-generated key would break
  registration on every fresh checkout. Password, alias and key password are all `sample`; SHA-1 is
  `D4:AC:72:C6:E0:D7:95:E6:2F:3B:61:A6:AE:C8:DD:1C:F5:D8:77:EB`. Release builds never fall back to it —
  see item 5.
