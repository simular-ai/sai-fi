# On-device check — the last acceptance gate for the refactor

**What this is.** The one criterion from the cleanup plan's §2G that has never been run. Everything
else was verified by typecheck, unit suite, compile and a 3-run LLM eval — none of which exercises
the persona prompt end to end, the FSM against a real model, the audio path or the camera path. Those
are precisely what the refactor moved.

**Why it matters now.** It gates **4B**: publishing this repo, opening the server PR, and merging.
The split itself (4A) is done and needed no hardware.

**Time:** ~15 minutes. **You need:** the glasses, a paired phone, and cloud-api running.

---

## 0. Pre-flight

### The server

The app talks to whatever `concierge_url` points at. In the current setup that is **your laptop over
the VPN**, not shared staging:

```bash
grep '^concierge_url=' meta-android-app/local.properties     # → http://10.8.0.19:8080
curl -s http://10.8.0.19:8080/health                          # → {"status":"ok",…}
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://10.8.0.19:8080/v1/concierge/session
#   401 → the concierge routes exist (unauthenticated, as expected)
#   404 → you are running a build without them
```

**Start it from the branch under test**, or you are testing something else:

```bash
cd ~/Downloads/mentra-app-voice-concierge
git switch tmp/integrate        # or feat/voice-concierge-server — same tree
npm run cloud-api:dev
```

Confirm the process you are hitting is the one you just started:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
ps -o lstart=,command= -p <pid> | cut -c1-80
```

> `sai_version_tag` is currently `feat-glasses-voice-concierge-1635`. It is **inert here** — it only
> means something to the staging gateway, and you are pointed at a local server, whose `/health`
> reports `versionTag: default`. Harmless, but blank it if it confuses you. It matters only if you
> switch `concierge_url` back to `https://staging.cloud-api.simular.cloud`, in which case set it to
> the revision you actually want to test.

### The app

```bash
cd ~/Downloads/sai-fi/meta-android-app
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
adb devices          # your phone should be listed
```

### The glasses

1. Meta AI app → **Developer Mode ON**, glasses paired and connected.
2. Launch **sai-fi**, sign in with Google.
3. Register with Meta AI when prompted. Remember: **only one third-party DAT app can be registered at
   a time** — this unregisters whatever else you had, and you will want to put that back afterwards.
4. Pick your machine. Start the call.

### Watch it happen

Two windows, both worth having:

```bash
adb logcat -c && adb logcat | grep -E 'SaiFi:'      # Live, Audio, Concierge, Presenter, WindowCapture
```

```bash
cd ~/Downloads/sai-fi/presenter && npm install && npm run presenter -- --port 8899 --key hunter2
```

The presenter mirrors the conversation, the activity log, call state, glasses photos and both audio
streams to a browser. DEBUG builds only, LAN only.

---

## 1. The seven checks

Each names **what in the refactor it actually exercises** — that is the point of running these
specific seven rather than "have a chat with it".

### 1. Greeting

Start a call and say nothing.

- **Expect:** she speaks first, within a couple of seconds, without waiting for you.
- **Exercises:** `GREETING_NUDGE` and the greeting gate. The nudge text moved into
  `voice/contract/nudges.ts` and is now fixture-pinned on both sides; the gate is Kotlin's
  `GreetingGate`. If she waits silently, the nudge is not reaching the model.

### 2. A forwarded task

> "Can you check what's in my downloads folder?"

- **Expect:** one short acknowledgement, then **silence** while it runs, then the result spoken once.
- **Exercises:** the whole spine — `InProcessAgentBridge` → `routeInboundMessage` on the `voice`
  channel → `streamResponse` → `mapSseEvent` → the FSM. Also the "acknowledge once, then go quiet"
  rule: **periodic "still working" updates are a failure**, and the watchdog that used to cause them
  was deleted in 2B.
- **Watch for:** progress lines in the log but not in your ear.

### 3. An approval

Ask for something that needs one — e.g. deleting a file, or anything the agent gates.

> "Delete that draft file for me"

- **Expect:** she reads the request out and waits. Say "yes" → it proceeds.
- **Exercises:** `effect-handlers/approvals.ts`, and the `agent-ingest.ts` extraction that sets
  `awaiting: 'approval'`. This is the path where a bug parks the FSM forever, so also check that
  **after** the approval resolves she goes back to idle and will take a new task.

### 4. Capture, then forward

> "Have a look at this and tell me what it says"

- **Expect:** the capture happens on the **glasses** camera, and she does not claim to have *sent*
  anything until it is attached to a task.
- **Exercises:** `GlassesCamera`, `PhotoClipboard`'s state in `CallService`, the WS `attachment`
  message, and `isAcceptableAttachment` in `attach-ws`. The persona prompt has a paragraph on
  photo-taken ≠ photo-sent, found by hearing it fail.
- **Watch for:** "I've sent that over" when nothing was forwarded.

### 5. Mute / unmute

Press the **temple button** mid-call. Have a task complete while muted. Unmute.

- **Expect:** she goes silent immediately, keeps listening and working, and **does not announce being
  muted**. On unmute the held completion is delivered — once, not replayed as a pile.
- **Exercises:** `HeldNudgeQueue`, `MUTED_NUDGE`/`UNMUTED_NUDGE`, and `AgentEventRouter`'s
  hold-vs-drop decision (11 JVM tests, but the audio side is untested until now). Also `saiMuted`,
  which 2E confirmed is main-thread-confined — a spoken "(I'll stay quiet)" is the exact failure.

### 6. A second task, queued behind the first

Start a long task. While it runs:

> "Also, book me a table for two on Friday"

- **Expect:** she says it will happen **after** the current one — and then it actually runs when the
  first finishes. Not steered into the running turn.
- **Exercises:** the highest-risk thing in this whole change. `deliveryMode: 'queue'` +
  `onPending` in `inbound-router.ts`, `queueTask` in the bridge, the `queued-task-started`
  correlation, and `effect-handlers/queue.ts`. If she says "I'll do that next" and then it never
  runs, or it gets folded into the first task, that is the bug this check exists for.

### 7. endCall

> "Thanks, that's everything — bye"

- **Expect:** she says goodbye, *then* the call ends a beat later.
- **Then test the guard:** start a fresh call, and with her never having spoken, say something that
  sounds like a farewell aimed at someone else ("yeah, bye!" as if to another person).
  **Expect:** she does NOT hang up — she asks "did you want me to hang up?"
- **Exercises:** `HangupPolicy` (15 JVM tests as of this week, previously untestable). The failure
  mode is cutting you off mid-sentence with another human.
- **Also try:** talk over the goodbye window. The hangup should abort and she should carry on without
  saying goodbye twice.

---

## 2. Recording the result

The plan's §2G is the place this belongs:
`docs/plans/2026-08-05-glasses-voice-concierge-cleanup.md`, under **Results**.

Write down, for each of the seven: pass / fail / not-reached, and for any failure the log excerpt and
what you heard. A failure here is not automatically a refactor regression — several of these
behaviours were already imperfect, and the eval found the instrument noisier than the effect. What
makes a failure a regression is it being **new**, and `origin/feat/glasses-voice-concierge` is the
pre-refactor build to compare against if you need to.

If it passes: 4B is unblocked apart from **rotating the DAT client token**, which is independent and
still owed.

## 3. If something goes wrong

| Symptom | Likely cause |
| --- | --- |
| No session at all, app shows an error | Server not running, or `concierge_url` unreachable from the phone — check the VPN, not the code |
| `401` on session mint | Signed out, or the Firebase ID token expired. Sign out and back in |
| Glasses never connect | DAT registration lapsed, or another DAT app claimed the single registration slot |
| Audio one-way | The classic one. Check `SaiFi:Audio` in logcat for the route it picked |
| She narrates every step | The update-discipline prompt block is not reaching her — check the session response's prompt, `npm run -w cloud-api prompt:dump glasses` |
| Nothing in the presenter | DEBUG build? `presenter_url` set? Same LAN? It is best-effort and never blocks a call |

On-stage recovery, and the fuller demo arc, are in [`SAI_GLASSES_DEMO.md`](SAI_GLASSES_DEMO.md) §5.
