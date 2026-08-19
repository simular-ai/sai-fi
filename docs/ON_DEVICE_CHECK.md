# On-device check — verifying a build on real hardware

**What this is.** Ten checks, ~30 minutes, each naming what it actually exercises. Three of them
(the queue, stopping work, and the hang-up) carry extra lettered cases, because those are where the
failures cluster.
Run it after any change that touches the call — and after any server change to the concierge, because
half of what these checks exercise lives there.

**Why it can't be automated.** The JVM suite (`./gradlew :app:testDebugUnitTest`) and the server's
vitest suites cover the ports, the state machines and the protocol. None of them exercises the
persona prompt end to end, the FSM against a real model, the audio path, or the camera path. There is
no test for "did Sai talk over me", "did Sai stop when I cut in", or "did the photo arrive upright".

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
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$CONCIERGE_URL/v1/agents/message"
#   401 → the agent API is there (unauthenticated, as expected)
#   404 → you are pointed at something that is not cloud-api
```

> **The voice half does not touch this server.** Audio goes straight from the phone to Google with
> your own key, and the prompt ships in the APK. What `concierge_url` reaches is your AGENT — so a
> server that is down means tasks fail, not that Sai stops talking. That distinction is worth holding
> while you read the checks below.

If you are testing a server branch, **start cloud-api from that branch** or you are testing something
else, and confirm the process you are hitting is the one you just started:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
ps -o lstart=,command= -p <pid> | cut -c1-80
```

> **There is no longer a way to run these against a stood-in agent.** `SAI_AGENT_SANDBOX` was
> reverted and no longer exists in cloud-api, so every run here wakes a real VM and bills a real
> agent. Budget for that, and prefer the off-device tiers for anything that does not actually need
> the glasses: the JVM suite now closes the model → FSM → agent loop against a scripted agent
> (`app/src/test/…/conversation/`), which covers the queue and the protocol half of barge-in for
> free. What is left for this checklist is what only hardware can answer — the audio path, the
> camera, and how it all feels.

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
2. Launch **sai-fi**. Signed out, the only thing on screen is *Sign in with Google* — that is the
   gate, not a stuck screen. Sign in.
3. Register with Meta AI when prompted (Home → **Register glasses**). Remember: **only one third-party
   DAT app can be registered at a time** — this unregisters whatever else you had, and you will want to
   put that back afterwards.
4. **Settings → Developer mode ON.** This is sai-fi's own switch, not Meta's, and it is off by default
   in every build including debug. Without it there is no Logs tab, so the in-app transcript and the
   text composer several checks below rely on are not there. It persists, so this is a once-per-install
   step.
5. Home: pick your machine, then Start the call.

### Watch it happen

Three windows, all worth having — the Logs tab from step 4, plus:

```bash
adb logcat -c && adb logcat | grep -E 'SaiFi:'      # Live, Audio, Concierge, Presenter, WindowCapture
```

```bash
cd presenter && npm install && npm run presenter -- --port 8899 --key <secret>
```

The presenter mirrors the conversation, the activity log, call state, glasses photos and both audio
streams to a browser. DEBUG builds only, LAN only.

---

> **First run after the 2026-08-12 change?** These five paths moved repositories and have never run
> on hardware. If something is going to be broken, it is one of them:
>
> | | What to watch |
> | --- | --- |
> | The turn stream connects | `POST /v1/agents/message` answers with the turn's events. If that response is not read, nothing arrives from the agent at all — checks 2, 3 and 6 all go quiet together |
> | Live opens with `?key=` | The URL form changed. The wrong one closes with `1007 api key not valid` |
> | An idle call ends itself | The cost guard moved from the server to the phone. Leave a call silent for five minutes; it should hang up and say why |
> | Waking a machine | The "it's waking / it's ready" lines now travel as `notice` over the stream |
> | Cancelling a queued task | Four endpoints deep, and the race answers are new — check 6 covers it |

## 1. The ten checks

Each names **what it actually exercises** — that is the point of running these specific ten rather
than "have a chat with it". Kotlin names (`GreetingGate`, `HangupPolicy`, `CallService`) are in this
repo; a bare `.ts` filename means the server's half of the same thing, and what the client owes it
either way is [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md).

### 1. Greeting

Start a call and say nothing.

- **Expect:** Sai speaks first, within a couple of seconds, without waiting for you.
- **Exercises:** `GREETING_NUDGE` and the greeting gate. The nudge text lives in
  `contract/nudges.ts` and is fixture-pinned on both sides; the gate is Kotlin's `GreetingGate`. If
  Sai waits silently, the nudge is not reaching the model.

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

- **Expect:** Sai reads the request out and waits. Say "yes" → it proceeds.
- **Exercises:** `effect-handlers/approvals.ts`, and the `agent-ingest.ts` extraction that sets
  `awaiting: 'approval'`. This is the path where a bug parks the FSM forever, so also check that
  **after** the approval resolves Sai goes back to idle and will take a new task.

### 4. Capture, then forward

> "Have a look at this and tell me what it says"

- **Expect:** the capture happens on the **glasses** camera, and Sai does not claim to have *sent*
  anything until it is attached to a task.
- **Exercises:** `GlassesCamera`, `PhotoClipboard`'s state in `CallService`, the WS `attachment`
  message, and `isAcceptableAttachment` in `attach-ws`. The persona prompt has a paragraph on
  photo-taken ≠ photo-sent, found by hearing it fail.
- **Watch for:** "I've sent that over" when nothing was forwarded.

### 5. Mute / unmute

Press the **temple button** mid-call. Have a task complete while muted. Unmute.

- **Expect:** Sai goes silent immediately, keeps listening and working, and **does not announce being
  muted**. On unmute the held completion is delivered — once, not replayed as a pile.
- **Exercises:** `HeldNudgeQueue`, `MUTED_NUDGE`/`UNMUTED_NUDGE`, and `AgentEventRouter`'s
  hold-vs-drop decision — all covered by JVM tests, but the audio side only by ear. Also `saiMuted`,
  which is main-thread-confined; a spoken "(I'll stay quiet)" is the exact failure.

### 6. A second task, queued behind the first

**6a — accepting it.** Start a long task. While it runs:

> "Also, book me a table for two on Friday"

- **Expect:** Sai says it will happen **after** the current one — and then it actually runs when the
  first finishes. Not steered into the running turn.
- **Exercises:** the highest-risk path here, and the newest. The admission rule in `TaskHandlers.kt`
  and `maybeDrainQueue` in `Concierge.kt`. Nothing server-side is involved: the second task exists
  only in this app's memory until the first turn ends, so if Sai says "I'll do that next" and then it
  never runs, the drain never fired. **A dropped call here loses the held task with nothing said** —
  that is known, and it is the cost of the queue being local.

Four more cases, run in the same call while 6a's queue is still standing. They fail differently, and
each is a way of *lying about the queue* rather than mishandling it — which is why they are by-ear
checks and not FSM tests.

**6b — status, with one running and one waiting.**

> "What's going on?"

- **Expect:** Sai separates them. The first is running; the second has **not started**. "I'm working
  on both" is the failure, and so is any wording that has the queued one underway.
- **Exercises:** the `session-state` event → `ActivityLog`'s `queued` projection → `statusText()` →
  the `getSaiStatus` tool. That projection is held as **state**, deliberately outside the rolling
  activity buffer, so a task waiting a long time cannot scroll out of what Sai can see. Ask again a
  few minutes later — still listed is the pass.
- **If Sai is parked on an approval instead** (check 3 left one open), the status must say it is
  blocked **on you**, and never that Sai is waiting to hear back from anyone else.
- **Watch for:** `⋯ 1 waiting: …` on the activity log. No such line means no `session-state` arrived,
  and Sai is answering from the past only.

**6c — cancelling something queued.**

> "Actually, forget the table booking"

- **Expect:** Sai confirms it is dropped, it **never runs**, and a follow-up "what's queued?" no
  longer mentions it.
- **Exercises:** the `cancelQueued` effect and the server replacing its `session-state` projection
  wholesale. Two distinct failures: `→ effect: cancelQueued` **absent** from logcat means Sai said
  yes and never called the tool — the booking will still run; present but still listed afterwards
  means the projection did not update.

**6d — jumping the queue.**

> "Do the Friday booking now instead"

- **Expect:** either it is promoted ahead of the running task, or Sai says plainly that it cannot be —
  both are passes. A cheerful "sure, doing that now" followed by nothing changing is not.
- **Exercises:** `sendQueuedNow` / `interrupt`. Same evidence as 6c: the effect is in the log or Sai
  never asked for anything.

**6e — two deep.** Queue a second and a third behind the running task.

- **Expect:** both wait, they run **in the order you asked for them**, and every result reaches you.
- **Exercises:** FIFO admission server-side, and the client's nudge gating when the second completion
  lands while Sai is still reporting the first — `→ nudge: complete — held until the turn ends`
  followed by `← nudge: delivering complete`. Two results reported in one breath is fine; a result
  you never hear at all is the failure, and `✗ nudge: dropping …` names it.

### 7. Barge-in

Ask for something with a long answer ("give me a rundown of what you can do"), then talk over Sai
mid-sentence.

- **Expect:** Sai stops within a beat — mid-word is correct, not at the end of the sentence — and
  answers what you just said. No tail of the abandoned reply after Sai goes quiet, and no resuming it.
- **Exercises:** the audio path, which nothing else here reaches. Gemini's server VAD raises
  `interrupted` → `CallService.onInterrupted` → `AudioIo.flushPlayback` (which clears the whole play
  queue, not just what the `AudioTrack` already holds) plus `discardAudioUntil` in `GeminiLiveClient`,
  a 700 ms window that drops chunks of the killed turn still arriving. Playback runs on its own
  thread precisely so the flush is not stuck behind a blocking `write` — that bug made interrupting
  land up to half a second late, which is most of what "it doesn't stop talking" ever was.
- **Look for:** `— barge-in —` in logcat, and ` — cut off —` appended to the abandoned line on the
  phone and in the presenter. No log line at all means the VAD never fired — a mic-side problem
  (route, noise gate), not a playback one.
- **Three failures, three different fixes:**
  - **a tail** — more of the old sentence plays after the interrupt: straggler audio getting past the
    discard window.
  - **self-interruption** — `— barge-in —` with nobody speaking, or Sai cuts itself off repeatedly:
    the platform AEC is not cancelling Sai's own playback out of the mic. Both routes hold
    `MODE_IN_COMMUNICATION` on `VOICE_COMMUNICATION` for exactly this. Retry on the phone route with
    wired headphones to isolate it — glasses SCO is the harder route and the one that matters.
  - **phantom words** — Sai answers something nobody said, sometimes in another language. The mic
    noise gate is letting room noise reach the VAD; `NOISE_GATE_RMS` in `AudioIo` is the knob, and
    the `you` side of the transcript is the evidence.
- **Then barge in while a task is running**, and let its completion land. Cutting Sai off must not
  cost you the result: it should still arrive after the exchange.

### 9. Stopping work

The other half of the queue: check 6 is about work that waits, this is about work that dies.

**9a — stop one thing.** Start a long task. While it runs:

> "Actually, stop that"

- **Expect:** it stops, Sai says so plainly, and — the part that matters — it does **not** describe the
  stopped task as finished. "That's done" about work that was killed is the failure.
- **Exercises:** `interrupt` → `applyInterrupt` → `POST abort`. Watch for `→ effect: interrupt` and then
  `→ POST abort` in logcat. The abort produces **no agent event by design** (the stream reader is torn
  down), so the handler has to close the turn out itself. If it doesn't, everything you ask afterwards
  queues behind a turn that will never end — so follow up with a fresh, quick task and check it runs.

**9b — stop everything, from two.** Start a long task, queue a second behind it (check 6a), then:

> "Stop"

- **Expect:** with two things outstanding "stop" is ambiguous, so Sai **asks which** rather than
  guessing. Answer "all of it" → both go, and a follow-up "what's queued?" reports nothing waiting.
- **Exercises:** the one-shot scope question in `applyInterrupt` — the first interrupt asks, the second
  goes straight through. Guessing silently is the failure this exists to prevent, and it stops the wrong
  task in half of all cases.

**9c — start fresh.** With **nothing** outstanding:

> "Let's start fresh"

- **Expect:** Sai confirms a clean slate, and a `recallHistory` question afterwards no longer reaches
  the old conversation.
- **Exercises:** `resetSession` → `POST new-session`. Then repeat it **with a task running**:
  **expect a refusal that names what is in the way**, not a rotation — rotating out from under live work
  orphans it. This path is worth the attention: its last bug rotated the *terminal's* conversation
  instead of this one, which no off-device test could see.

### 10. endCall

**10a — with work still outstanding.** Do this one *before* letting the queue drain — if check 6's tasks
have all finished by now, start one long one and say goodbye straight over it:

> "Thanks, that's everything — bye"

- **Expect:** Sai asks about the outstanding work before hanging up — keep it running, or stop it —
  rather than ending on it silently. Answer either way and the call should then end.
- **Exercises:** the persona contract's hang-up-vs-work rule, decided server-side, against the same
  `session-state` picture check 6b reads. Hanging up with a queued task unmentioned is the failure.

**10b — the plain goodbye.** Once nothing is outstanding, say it again.

- **Expect:** Sai says goodbye, *then* the call ends a beat later — not the other way round, and not
  both at once.

**10c — the guard.** Start a fresh call, and with Sai never having spoken, say something that sounds
like a farewell aimed at someone else ("yeah, bye!" as if to another person).

- **Expect:** Sai does **not** hang up — it asks "did you want me to hang up?"
- **Exercises:** `HangupPolicy` (covered by JVM tests, but only the decision — not the audio). The
  failure mode is cutting you off mid-sentence with another human.

**10d — talking over the goodbye.** Cut in during the goodbye window.

- **Expect:** the hangup aborts and Sai carries on, without saying goodbye a second time. Two triggers
  reach this — the barge-in itself and fresh speech after the goodbye finished playing — so also try
  speaking a moment *after* Sai stops, while the call is still up. Both must cancel it; check 7 is the
  same mechanism seen from the audio side.

---

## 2. Recording the result

Write down, for each of the ten — and separately for each lettered case under 6, 9 and 10 —
**pass / fail / not-reached**, and for any failure, the log excerpt and what you actually heard. "Not
reached" is a real result and worth recording; it usually means an earlier check left the session in a
state the later one could not be provoked from, which is itself worth knowing. The queue cases in
particular chain: 6c and 6d have nothing to act on if 6a never queued anything, so record them as
not-reached rather than as passes.

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
| Sai talks through a barge-in, or stops late | `— barge-in —` in the log means the VAD fired and the playback side is at fault (flush, or straggler audio past the discard window). No such line means it never fired — mic route or noise gate |
| Sai cuts itself off with nobody speaking | AEC: Sai's own playback is reaching the mic. Repeat on the phone route with wired headphones to confirm before touching anything |
| Sai calls a queued task "underway" | No `session-state` reached the client (nothing like `⋯ 1 waiting:` in the activity log), so `getSaiStatus` is answering from the rolling buffer alone |
| Sai agrees to cancel or reorder, and nothing changes | No `→ effect: cancelQueued` / `sendQueuedNow` in logcat — Sai never called the tool. A prompt or tool-declaration problem, not a queue bug |
| Sai narrates every step | The update-discipline prompt block is not reaching it — inspect the prompt in the `POST /session` response, or dump it in the server repo with `npm run -w cloud-api prompt:dump glasses` |
| Nothing in the presenter | DEBUG build? `presenter_url` set? Same LAN? It is best-effort and never blocks a call |

**Going further.** This is the short gate — run it for a change. The cumulative by-ear matrix (60-odd
rows, one per bug ever found on a device) and the demo runbook with its on-stage recovery notes live
with the server's test docs and are **not mirrored here**; ask for them before a release or a stage
rehearsal, because these ten checks are not a substitute for either.
