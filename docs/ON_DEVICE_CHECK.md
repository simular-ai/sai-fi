# On-device check — verifying a build on real hardware

**What this is.** Seven checks, ~15 minutes, each naming what it actually exercises. Run it after any
change that touches the call — and after any server change to the concierge, because half of what
these checks exercise lives there.

**Why it can't be automated.** The JVM suite (`./gradlew :app:testDebugUnitTest`) and the server's
vitest suites cover the ports, the state machines and the protocol. None of them exercises the
persona prompt end to end, the FSM against a real model, the audio path, or the camera path. There is
no test for "did she talk over me" or "did the photo arrive upright".

**You need:** the glasses, a paired phone, and a reachable cloud-api.

---

## 0. Pre-flight

### The server

The app talks to whatever `concierge_url` in `local.properties` points at — the shared staging
gateway by default, or a laptop on the same LAN if you are testing unmerged server work. Either way,
confirm it is up and serving the concierge routes before you touch the glasses:

```bash
CONCIERGE_URL=$(grep '^concierge_url=' meta-android-app/local.properties | cut -d= -f2-)
curl -s "$CONCIERGE_URL/health"                                              # → {"status":"ok",…}
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$CONCIERGE_URL/v1/concierge/session"
#   401 → the concierge routes exist (unauthenticated, as expected)
#   404 → you are pointed at a build without them
```

If you are testing a server branch, **start cloud-api from that branch** or you are testing something
else, and confirm the process you are hitting is the one you just started:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
ps -o lstart=,command= -p <pid> | cut -c1-80
```

> **`sai_version_tag` only means something against staging.** It pins the app to one server revision
> via the `x-sai-version` header. Pointed at a local server it is inert — `/health` reports
> `versionTag: default` — so blank it rather than wonder. Pointed at staging, set it to the revision
> you actually want to test, and check 1 below is what confirms it took.

### The app

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd meta-android-app && ./gradlew :app:installDebug
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
cd presenter && npm install && npm run presenter -- --port 8899 --key <secret>
```

The presenter mirrors the conversation, the activity log, call state, glasses photos and both audio
streams to a browser. DEBUG builds only, LAN only.

---

## 1. The seven checks

Each names **what it actually exercises** — that is the point of running these specific seven rather
than "have a chat with it". Kotlin names (`GreetingGate`, `HangupPolicy`, `CallService`) are in this
repo; `.ts` paths are in the server repo,
[`simular-ai/simular-pro-unified-ui`](https://github.com/simular-ai/simular-pro-unified-ui), under
`cloud-api/src/services/concierge/voice/`.

### 1. Greeting

Start a call and say nothing.

- **Expect:** she speaks first, within a couple of seconds, without waiting for you.
- **Exercises:** `GREETING_NUDGE` and the greeting gate. The nudge text lives in
  `contract/nudges.ts` and is fixture-pinned on both sides; the gate is Kotlin's `GreetingGate`. If
  she waits silently, the nudge is not reaching the model.

### 2. A forwarded task

> "Can you check what's in my downloads folder?"

- **Expect:** one short acknowledgement, then **silence** while it runs, then the result spoken once.
- **Exercises:** the whole spine — `InProcessAgentBridge` → `routeInboundMessage` on the `voice`
  channel → `streamResponse` → `mapSseEvent` → the FSM. Also the "acknowledge once, then go quiet"
  rule: **periodic "still working" updates are a failure**, and the dead-air watchdog that used to
  cause them was deleted outright — there is nothing left that should fill a quiet stretch.
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
  hold-vs-drop decision — all covered by JVM tests, but the audio side only by ear. Also `saiMuted`,
  which is main-thread-confined; a spoken "(I'll stay quiet)" is the exact failure.

### 6. A second task, queued behind the first

Start a long task. While it runs:

> "Also, book me a table for two on Friday"

- **Expect:** she says it will happen **after** the current one — and then it actually runs when the
  first finishes. Not steered into the running turn.
- **Exercises:** the highest-risk path here, and the newest. `deliveryMode: 'queue'` + `onPending` in
  `inbound-router.ts`, `queueTask` in the bridge, the `queued-task-started` correlation, and
  `effect-handlers/queue.ts`. If she says "I'll do that next" and then it never
  runs, or it gets folded into the first task, that is the bug this check exists for.

### 7. endCall

> "Thanks, that's everything — bye"

- **Expect:** she says goodbye, *then* the call ends a beat later.
- **Then test the guard:** start a fresh call, and with her never having spoken, say something that
  sounds like a farewell aimed at someone else ("yeah, bye!" as if to another person).
  **Expect:** she does NOT hang up — she asks "did you want me to hang up?"
- **Exercises:** `HangupPolicy` (covered by JVM tests, but only the decision — not the audio). The
  failure mode is cutting you off mid-sentence with another human.
- **Also try:** talk over the goodbye window. The hangup should abort and she should carry on without
  saying goodbye twice.

---

## 2. Recording the result

Write down, for each of the seven: **pass / fail / not-reached** — and for any failure, the log
excerpt and what you actually heard. "Not reached" is a real result and worth recording; it usually
means an earlier check left the session in a state the later one could not be provoked from.

**A failure here is not automatically a regression.** Several of these behaviours have never been
perfect, and the model is a noisy instrument — the behavioral eval on the server side routinely fails
rows on model capability rather than on the prompt. What makes a failure a regression is it being
**new**: compare against the last build you ran this on before concluding anything.

If you are running this to gate a change, attach the results to that change's PR. A by-ear failure
worth keeping should become an executable spec rather than a memory — the server's golden catalog
(`core/golden/scenarios.ts`) exists so that every behaviour once fixed by ear has a test watching it.

## 3. If something goes wrong

| Symptom | Likely cause |
| --- | --- |
| No session at all, app shows an error | Server not running, or `concierge_url` unreachable **from the phone** — check the network path, not the code. A LAN address that resolves on your laptop need not resolve on the handset |
| `401` on session mint | Signed out, or the Firebase ID token expired. Sign out and back in |
| Glasses never connect | DAT registration lapsed, or another DAT app claimed the single registration slot |
| Audio one-way | The classic one. Check `SaiFi:Audio` in logcat for the route it picked |
| She narrates every step | The update-discipline prompt block is not reaching her — inspect the prompt in the `POST /session` response, or dump it in the server repo with `npm run -w cloud-api prompt:dump glasses` |
| Nothing in the presenter | DEBUG build? `presenter_url` set? Same LAN? It is best-effort and never blocks a call |

**Going further.** This is the short gate. The cumulative by-ear matrix — 60-odd rows, one per bug
ever found on a device — is `TESTING_CONCIERGES.md` §6 in the
[server repo](https://github.com/simular-ai/simular-pro-unified-ui/blob/main/docs/TESTING_CONCIERGES.md),
along with the demo runbook and its on-stage recovery notes. Run those for a release or a stage
rehearsal; run these seven for a change.
