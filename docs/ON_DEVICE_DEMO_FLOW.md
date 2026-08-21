# On-device demo flow — the ten checks, run as three calls

**What this is.** [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) says *what* to verify and *why*. This
says **what to say, in what order, and what you should hear back** — plus, for every beat, whether to
**wait** for Sai to finish or **cut in**, and which of the five kinds of interruption the beat is
supposed to produce. Those two things are the whole difficulty of running the checklist by ear: get
the wait/cut-in wrong and you test a different code path than the one the check names, and score a
pass or a fail that means nothing.

Verbatim lines below in **bold quotes** are `say` constants from `fsm/Speech.kt` — the client wraps
them in "say this to the user, verbatim", so you should hear close to the exact words. Near-verbatim
is a pass. A **changed meaning** is the failure, and the two most valuable failures in this whole
document are wording ones: *"on it"* where the line says *"I'll start that as soon as I'm done with…"*,
and *"that's done"* about work that was killed.

---

## 1. What actually needs verifying on device

Only the third column is worth device time. Column two is why: those parts already have a test that
fails when they break, so hearing them work again proves nothing new.

| Check | Already covered off-device | What only the hardware can answer |
| --- | --- | --- |
| **1. Greeting** | `GreetingGate` — the once-per-call latch, incl. reconnect/resume (JVM) | Does the nudge actually reach the model, and does Sai open its mouth within a couple of seconds without you speaking |
| **2. Forwarded task** | The whole spine — model → FSM → agent → back — against a scripted agent (JVM `conversation/`) | The **audio** of "one ack, then quiet": that Sai stays silent for a long minute and that the result arrives once. Also whether the summary carries a result or merely announces one (the 2026-08-19 bug) |
| **3. Approval** | `ApprovalHandlers`, the exact-match pick guard, `denyApprovalKilledByAbort` (JVM) | That the card is *read out* intelligibly, that "yes" resolves it, and — the part that has wedged before — that Sai returns to idle afterwards and takes new work |
| **4. Capture → forward** | Nothing meaningful. `PhotoClipboard` state is trivial | **All of it.** The glasses shutter, orientation, upload, the stash landing before the next forward, and the photo-taken≠photo-sent paragraph holding |
| **5. Mute / unmute** | `HeldNudgeQueue` collapsing, `AgentEventRouter` hold-vs-drop, `isPlaceholderSpeech` (JVM) | That the **temple tap** reaches the app at all (see §2.4 — this is an unresolved question in the code, not just an untested one), that Sai goes silent mid-word, and that unmute produces one offer not a monologue |
| **6a–6e. The queue** | Admission, FIFO, `matchQueued`, cancel/promote refusals, and the nudge gating — all golden-scenario'd | Whether the **model** reaches for `enqueue` / `cancelQueued` / `sendQueuedNow` at all, in real speech, at speed. Every failure mode listed under 6 is the model lying about the queue, and no FSM test can see that |
| **7. Barge-in** | Nothing. There is no test for any of it | **All of it**, and it is the single highest-value beat in the document: VAD firing, `flushPlayback` clearing the queue, the 700 ms discard window, AEC on the glasses SCO route, and the noise gate |
| **8. Switching + the wake** | `MachineSwitcher.resolve` (exact-then-containment, both directions) and `WakePolicy` — which line, and whether to speak at all (JVM, 9 cases) | Whether the model reaches for `switchMachine` from ordinary speech; whether the context correction stays **unspoken**; and **all of the wake**, which has never run on hardware — that the verbatim lines sound like sentences, that "waking" and "awake now" never arrive in one breath, and that the next task follows the switch |
| **9a–9c. Stopping** | `applyInterrupt` incl. the one-shot scope question, `applyResetSession` refusals (JVM) | That the abort **closes the turn out** — the FSM gets no event for an abort, so the proof is a *fresh task running afterwards*. And that Sai does not describe killed work as finished |
| **10a–10d. endCall** | `HangupPolicy.decide` and `shouldCancel` — the decision, exhaustively | The **timing**: goodbye-then-hangup ordering, and the 1.2 s window in which talking over the goodbye actually cancels it (§2.3) |

**Not in the ten checks, on-device-only, and cheap to fold in** — in this order of value. (Machine
switching used to be on this list; it is check 8 now, and the wake came with it.)

1. **The idle hang-up** (§5, Call C). The cost guard moved from server to phone and has never run on
   hardware. Five minutes of silence should end the call *and say why*.
2. **"…and don't ask me again"** (§5, beat B5). `approveAlways` folds to a one-time `approve` — the
   tool is not declared and the promise no longer exists. If Sai promises to stop asking, that is a
   new failure with nothing watching it.
3. **The placeholder-speech backstop** (beat B12). `Empty-Response` / `No response received.` spoken
   aloud after a mute was a real device failure; `isPlaceholderSpeech` is the client's guard.

## 2. Four things in `ON_DEVICE_CHECK.md` that will cost you a run

### 2.1 Ask-first will make check 2 look broken

Check 2 says "one short acknowledgement, then silence, then **the result spoken once**". You will not
get that on the default settings. `AgentEventRouter.route` sends the **ask-first** wording when a
completion lands and the user has been quiet longer than the ask-first threshold — **15 s by
default** — and that wording tells the model *"Say NOTHING at all right now… Silence IS the correct
output for this turn."* Any task that takes more than 15 s, run while you dutifully stay quiet,
finishes into deliberate silence.

**Pick one before the call — settings lock once a call starts:**

- **Settings → Ask-first = 3600** for beats B2–B4 and the queue beats. Completions are then always
  reported directly, which is the behaviour check 2 is written against.
- **Or leave it at 15** and expect the offer shape: nothing, then — once you speak — *"that thing's
  done — want it?"*, and the result only if you say yes. That is a **pass**.

Do **not** set it to 0. Zero means *everything* is ask-first, not none of it.

Muting forces ask-first regardless of the threshold, so on unmute (check 5) you should hear an
**offer**, not the result. That is the pass condition, and check 5's "the held completion is
delivered" is easy to misread as "the answer is read out".

### 2.2 Call A needs a machine that is really asleep

The wake beats are the newest code in the app and the only way to reach them is a **hibernated**
machine — `POST /v1/agents/wake` no-ops on anything else, and correctly says nothing. Hibernation is
not instant and not always yours to trigger, so sort this out *before* the session or A1–A3 and A11 are
**not-reached**, which is a real result but not the one you wanted. `GET /v1/agents/machines` now
reports `status`, so you can confirm rather than guess.

Budget for it too: A1→A2 is a real minute of waiting, and a failed wake is three.

> Also fixed in passing: the doc used to claim ten checks and contain nine — `8e110d5` numbered
> **Stopping work** as `### 9` with nothing at `### 8`. Switching machines is check 8 now, so "ten" is
> true and §2's "each of the ten" is scoreable as written.

### 2.3 Check 10d has a 1.2-second window, and the first 600 ms don't count

`GOODBYE_MS = 1_800`, `HANGUP_STRAGGLER_GUARD_MS = 600`. Speech inside the first 600 ms of the
goodbye window is treated as the tail of *your own* farewell being transcribed and is deliberately
ignored — otherwise a genuine "hang up" could never complete, because its own words would re-open the
call. So the cancel window is ~600–1800 ms after the goodbye starts.

**Cut in about a second into Sai's goodbye.** Too early is a pass for the straggler guard, not a
failure of 10d — and it is indistinguishable at the ear, so watch the log.

### 2.4 Stale pointers — don't chase these

| The check says | What is actually true |
| --- | --- |
| 6b/6c: `session-state` "arrives" | `Concierge.publishSessionState()` **generates** it locally from FSM state. A missing `⋯ 1 waiting:` line is a local projection bug, not a missing server event |
| Check 5: "the temple button" mutes | Behaviourally yes — `CallService` wires `onTap = { toggleMute() }`. DAT 0.8 exposes no gesture API, so the tap is only inferred from a `DeviceSessionState` transition. If the tap does nothing, mute from the phone UI or the notification action and record check 5 against those instead |

## 3. The interruption grammar

Five different things get called "interrupting", they are different code paths, and the demo only
works if you know which one you are asking for. **Sai's model picks** — the point of the beats below
is to see whether it picks right.

| You do | Should become | What Sai should say | Log evidence |
| --- | --- | --- | --- |
| Talk over Sai's **speech** | Nothing at the FSM level — pure audio. Gemini's VAD raises `interrupted`, playback flushes | Stops **mid-word**, answers what you said. No tail, no resuming | `— barge-in —`, and ` — cut off —` appended to the abandoned line |
| Ask for **new, unrelated work** while a task runs | `enqueue` — held locally, agent never told | **"Got it — I'll start that as soon as I'm done with: ⟨running task⟩."** | `→ effect: enqueue`, then `⋯ 1 waiting: …` |
| Answer a question, or **correct/narrow the running task** | `relayToAgent` — steers the live turn. Never queued | No queue line at all; it just lands | `→ effect: relayToAgent` |
| **"Stop that"** / "cancel everything" | `interrupt` — aborts the turn **and drops the queue**. No scope | Says it stopped. With >1 outstanding, asks scope **first** | `→ effect: interrupt`, then `→ POST abort` |
| **"Forget the ⟨queued thing⟩"** | `cancelQueued` — a list edit, no agent traffic | **"That one hadn't started yet, so it's off the list: ⟨task⟩."** | `→ effect: cancelQueued`. Absent = Sai agreed and did nothing |
| **"Do the ⟨queued thing⟩ now"** | `sendQueuedNow` — starts it *alongside*, stops nothing | **"Starting on that now, alongside what I'm already doing: ⟨task⟩."** | `→ effect: sendQueuedNow` |

**The one that matters most:** `interrupt` has **no scope**. "Stop the email one, leave the booking"
must go out as `relayToAgent`, not `interrupt` — if the model reaches for `interrupt` there, it kills
the booking too and will probably report the survivor as fine. That is beat B14.

### Waiting rules, for every beat that doesn't say otherwise

- **Wait for Sai to stop speaking, then leave about a second**, before your next line. Speaking into
  the tail of a turn that has already ended logs a spurious `— barge-in —` and muddies check 7.
- **Never put two tasks in one breath.** The model will fold them into one `forwardToAgent`, and then
  "cancel the booking" kills the email check silently — the 2026-07-30 device report, and the reason
  admission exists. One task, wait for the ack, then the next.
- **Answer an approval before adding work.** New work while an approval is pending queues behind it
  and you get **"I've got that, but I'm still waiting on the request in front of it…"** — correct, but
  a different check than the one you were running.
- **Project your first sentence.** `NOISE_GATE_RMS = 500` gates sub-threshold audio on purpose, so a
  mumbled opener is swallowed whole. **No `you:` line = the mic never heard you** (say it again,
  louder). **A `you:` line with no reply = it heard and judged it wasn't aimed at Sai.** Different
  problems.

---

## 4. Pre-flight delta

Everything in `ON_DEVICE_CHECK.md` §0, plus:

- **Settings → Ask-first = 3600** (§2.1), and **Developer mode ON** (Logs tab + the text composer).
- **Two tasks ready on the demo machine:** one **long** (≥60 s — "go through my downloads folder and
  summarise every file") and one **short**. Half the beats below need a running task to interrupt.
- **One task that trips a guardrail** (deleting a file, sending a message) for the approval beats.
- **One unlabelled object** for the capture beat — identifiable by shape, not logo.
- **A hibernated machine, and ideally two** (§2.2). Confirm with `status` on
  `GET /v1/agents/machines` rather than assuming — the whole wake half of Call A is unreachable
  without one, and a second is what makes A11 (the wake on a *switch*) possible.
- The presenter up (`cd presenter && npm run presenter -- --port 8899 --key <secret>`) and
  `adb logcat | grep -E 'SaiFi:'`. The two together are the only way to tell most of these failures
  apart.

---

## 5. The flow

Three calls. **Call B is the bulk of it and must be one unbroken call** — the queue beats chain, and
each one has nothing to act on if the last did not queue anything.

### Call A — ~10 min: machine targeting, and the wake

Two things, both about *which computer* Sai is aimed at, and as of 2026-08-20 both are live in the
client. **The wake half has never run on hardware** — it is the newest code in the app and the most
likely thing in this document to be broken.

**What now happens.** The call wakes its own machine, at bind and at every switch, and narrates it:
`MACHINE_WAKING` → `MACHINE_AWAKE`, or `MACHINE_WAKE_FAILED` after three minutes. `WakePolicy` decides
whether to speak and which line; `CallService.wakeMachine` calls `POST /v1/agents/wake` and then polls
`GET /machines` every 10 s. So the three constants that sat unreferenced in `fsm/Speech.kt` since the
port are finally spoken, which means this is also the first time anyone hears whether they *sound*
right.

**Prerequisite: a genuinely hibernated machine.** Without one there is nothing to wake and A1–A3 are
not-reached rather than passes — see §2.2. A **second** hibernated machine makes A11 possible too,
which is the one beat that exercises the switch path's wake.

| # | You say | Wait? | Expect |
| --- | --- | --- | --- |
| **A1** | *(nothing)* | wait | Greeting, then — as a **separate turn**, before you have asked for anything — **"The computer is waking up — it'll take about a minute. I'll let you know when it's ready."** Log: `wake: machine is starting up (dispatched=true) — watching for active`, and `→ nudge: speak:machine-state — held until the turn ends` followed by `← nudge: delivering` |
| **A2** | *(nothing, ~1 min)* | wait | **"The computer's awake now — I'm ready when you are."** unprompted, and **nothing in between** — no filler, no invented progress. Log: `wake: machine is active`. Up to 10 s of lag between the machine actually coming up and Sai saying so is the poll interval, not a fault |
| **A3** | "What's in my downloads folder?" | wait | An ack, then the result. **No waking notice at all** — the machine is already up. A `data-status` waking line *here* means the bind-time wake never happened and you are watching the old behaviour |
| **A4** | "Switch to my ⟨other machine's exact name⟩" | wait | Spoken: *"Switched to ⟨label⟩."* and **nothing else**. The tool reply carries a parenthetical context correction — **if you hear "context update, not to be spoken aloud" read out, that is a failure**, the same class as the `RESELECT_NUDGE` bug that read a function name aloud |
| **A5** | "Switch to ⟨partial name⟩" — e.g. "studio" for "Studio Mac" | wait | Resolves. `MachineSwitcher.resolve` matches exact first, then containment **in both directions**, so "studio" → "Studio Mac" and "my mac studio at home" → "Mac Studio" both work. A miss is more likely the model never calling the tool than a resolve bug — that half is JVM-tested |
| **A6** | "Switch to ⟨the machine you are already on⟩" | wait | *"You're already on ⟨label⟩."* No reconnect, no context nudge, **and no wake line** |
| **A7** | "Switch to my toaster" | wait | *"I couldn't find a machine called 'my toaster'. You have: …"* — it names what you actually have rather than guessing |
| **A8** | "What's in my downloads folder?" | wait | **The real proof of A4.** The result must come from the **machine you switched to**. Same answer as A3 means the switch never repointed anything |
| **A9** | *(switch machines from the phone picker, mid-call)* | — | **Nothing spoken about the switch itself.** A button press needs no narration — the correction goes in as `contextNudge`. Live audio stays up, so the conversation continues uninterrupted. A wake line here is correct **only** if the machine you picked is asleep |
| **A10** | "What were we doing on this machine earlier?" | wait | `recallHistory` reads `GET /v1/agents/context?machineId=` with the **new** id, so this must not answer from the machine you left |
| **A10b** | Start a long task, then immediately "switch to ⟨another machine⟩" | wait | **The switch does not happen yet.** Sai names the running task, says it keeps going on the machine you are leaving and that this call will not hear the result, and asks whether to stop it first. Say "leave it running" → the switch goes through (one-shot). A silent switch here is the failure, and it used to lose the queue outright |
| **A11** | "Switch to ⟨a hibernated machine⟩" | wait | *"Switched to ⟨label⟩."* **then** the waking line, then `MACHINE_AWAKE` a minute or so later — all before you ask for anything. Both switch paths go through `applyMachineSwitch`, so the picker behaves the same and needs no separate beat |
| **A12** | *(optional, hard to time)* mute immediately after the greeting on a hibernated machine, then unmute inside the minute | — | **No waking line** while muted (log: `wake: not spoken — muted…`), but the watch continues, so unmuting before it lands still gets **"The computer's awake now…"**. That is deliberate: the outcome is fresh news, unlike the opening line, which would be a stale replay. The wake itself happens regardless of mute |

**Five failure shapes, and they are not interchangeable:**

- **One merged sentence** — "Hey, I'm here — what can I do for you? The computer is waking up…". That
  was the 2026-08-20 device failure: the wake nudge landed ~200 ms after the greeting, before any frame
  had arrived, so `modelSpeaking` was still false, the gate let it through, and it **interrupted the
  greeting** — `— barge-in —` with nobody speaking, on every call. Fixed by `awaitingModelUntil` in
  `LiveTurnGate`, which treats a sent-but-unanswered turn as busy. Two separate turns is the pass; one
  merged sentence, or a `— barge-in —` before Sai has made a sound, means it is back.
- **The waking line never arrives, but the log says it was delivered** (`← nudge: delivering
  speak:machine-state`). The held-nudge preamble talked the model out of it — it says "you may already
  have covered some or all of it… saying nothing is the right output for a nudge you have already acted
  on", which is wrong for a verbatim `say` the greeting could not have covered. Known risk of routing
  the line through the held path; the same preamble demonstrably *under*-suppresses elsewhere, so this
  is a watch-for rather than an expectation.
- **Both lines in one breath** — "the computer is waking up… the computer's awake now". The supersede
  is broken: all three lines share the nudge kind `speak:machine-state` precisely so a later one
  *replaces* an earlier one still held for the end of a turn. Look for
  `→ nudge: speak:machine-state — held until the turn ends (replacing the stale one)`.
- **Paraphrased into "working on it"** — the verbatim rule failing, and the exact reason this line is
  a `say` constant: a softened "waking" is how a hibernated VM used to sound like an in-flight task.
- **Silence at bind on a machine you know is asleep** — the wake is not reaching the server. The
  `wake:` lines in logcat say which half: `could not reach the wake endpoint` (network/auth),
  `nothing to announce (status=… canWake=…)` (the server says it is not coming up), or no `wake:` line
  at all (the call never got to first-ready).
- **A wake announced for a machine that cannot be woken** — `canWake` ignored. That machine is asleep
  and staying that way, and the line promises a minute. `WakePolicy` has a test for this, so seeing it
  on device means the server's `canWake` is wrong, not the policy.
- **`MACHINE_WAKE_FAILED` after three minutes** — *"The computer didn't come back online…"*. Honest
  and possibly correct: `waking: true` only ever meant the wake was **dispatched**, since vm-service is
  fire-and-forget and nothing polls it. Check whether the machine really did come up late; if it did,
  the three-minute bound is the thing to argue with.

**Rejected: waking by sending a dummy "hello".** Worth keeping written down, because it is the obvious
thing to reach for and it breaks in a way nothing in the response would explain. The router **folds any
message arriving during a running turn into that turn** — not steer-specific; it is why admission holds
tasks locally at all (`VOICE_FSM.md` §6). So a hello turn the FSM does not know about absorbs the
user's first real task, and the task's own stream replays the hello's `finish` → `complete`, which is
**unguarded by design** (§3) and ends the turn *and* clears any pending approval. The FSM goes idle,
drains the queue, and the real result lands in a session that believes it finished. That is why
`/wake` takes no payload, and the reason is written into the endpoint's own docs.

End the call. Wake the machine properly before Call B — the queue beats need a responsive agent, not a
cold one.

### Call B — the main run, ~25 min, one call

**Ask-first = 3600. Do not hang up until B21.**

| # | You say | Wait / cut in | Expect | Interruption class | Check |
| --- | --- | --- | --- | --- | --- |
| **B1** | *(nothing at all)* | wait ~5 s | Sai speaks **first**, one warm sentence, within a couple of seconds | — | 1 |
| **B2** | "Can you check what's in my downloads folder?" | wait | One **short** ack, then **silence** for as long as it takes, then the result once. Periodic "still working" is a **failure** | new task → forward | 2 |
| **B3** | *(nothing)* | wait | The result **contains the listing**. "Done — that's the full listing" with no listing, or a confident "it's empty", is the 2026-08-19 failure | — | 2 |
| **B4** | "Delete that draft file for me" | wait | Sai reads the request out and **waits**. Nothing is deleted yet | new task → forward, then approval | 3 |
| **B5** | "Yes — and don't ask me about that again" | wait | It proceeds. It must **not** promise to stop asking: `approveAlways` folds to a one-time approve and the grant is retired | `approve` | 3 + extra |
| **B6** | "What day is it?" | wait | Answers immediately. **This is the real check 3** — proof the FSM went back to idle instead of parking in `awaiting-user` with a dead approval | — | 3 |
| **B7** | *(hold up the object)* "Have a look at this and tell me what it says" | wait | A shutter beat, no dead air, then what it is. **It must not claim to have *sent* anything** | capture → forward | 4 |
| **B8** | "Give me a rundown of everything you can do" | **cut in ~2 s into the answer** with "actually, what's the weather like?" | Stops **mid-word**, answers the weather. No tail of the abandoned sentence, no resuming it | **audio barge-in** | 7 |
| **B9** | "Go through my downloads folder and summarise every file" | wait for the ack only | Ack, then quiet. Leave this running for B10–B14 | new task → forward | 6a setup |
| **B10** | "Also, book me a table for two on Friday" | **cut in while B9 runs** | **"Got it — I'll start that as soon as I'm done with: go through my downloads folder…"** Not "on it", not steered into the running turn | **enqueue** | 6a |
| **B11** | "What's going on?" | wait | Separates them: the first **running**, the second **not started**. "I'm working on both" is the failure | — (local `getSaiStatus`) | 6b |
| **B12** | "And draft a reply to ⟨person⟩ about the pull request" | wait | Queued too, behind both. Now two waiting | enqueue | 6e |
| **B13** | "Actually, forget the table booking" | wait | **"That one hadn't started yet, so it's off the list: book me a table…"** — and a later "what's queued?" no longer mentions it | **cancelQueued** | 6c |
| **B14** | "Leave the downloads one running, but do the draft now instead" | wait | Either **"Starting on that now, alongside what I'm already doing: …"**, or a plain "I can't". Both pass. Cheerful agreement with nothing changing does not | **sendQueuedNow** (not `interrupt` — if the downloads task dies here, that is the bug) | 6d |
| **B15** | *(temple tap to mute)* then to a colleague: "…we should get everyone together tomorrow" | — | Sai goes silent **immediately, mid-word**, and **says nothing about being muted**. It must not answer the colleague either | — | 5 |
| **B16** | *(stay muted until B9 or B14 completes, then unmute)* | wait | On unmute: **one** short offer — *"that thing's done — want it?"* — not the result, not a pile, and no "I'm back". A spoken "(I'll stay quiet)" or `Empty-Response` is the exact failure | held nudge replay | 5 |
| **B17** | "Yes, go on" | wait | The held result, once | — | 5 |
| **B18** | *(let the queue finish, then)* "Go through my downloads folder again in detail" | wait for ack | Running. One thing outstanding | forward | 9a setup |
| **B19** | "Actually, stop that" | **cut in** | Stops. Says so plainly. **Never "that's done"** | **interrupt** (straight through — only one outstanding) | 9a |
| **B20** | "What's the time?" | wait | Answers. **This is the real check 9a** — the abort produces no agent event, so if the handler didn't close the turn out, this queues behind a turn that never ends and you hear nothing | — | 9a |
| **B21** | Start a long task, wait for the ack, then queue a second (as B9/B10), then: "Stop" | **cut in** | **"hold on — I'm working on ⟨X⟩, and ⟨Y⟩ hasn't started yet. Do you want me to stop all of it, or just part of it?…"** Guessing silently is the failure | interrupt → **scope question**, nothing aborted yet | 9b |
| **B22** | "All of it" | wait | Both go. "What's queued?" reports nothing waiting | interrupt, second time → straight through | 9b |
| **B23** | Start a long task, then: "Let's start fresh" | wait | **Refused, naming the blocker**: "I can't start fresh just yet — I'm still working on ⟨X⟩. Sort that out or tell me to stop it…" A rotation here orphans live work | resetSession, refused | 9c |
| **B24** | "Stop that" — then, once idle, "Let's start fresh" | wait | **"Alright, fresh start — I've cleared what we were talking about. The old conversation is still on your desktop if you need it."** Then a recall question ("what did we do earlier?") must **not** reach the old conversation | interrupt, then resetSession | 9c |
| **B25** | Start a long task, then: "Thanks, that's everything — bye" | **cut in over the running task** | Sai **asks about the outstanding work** before hanging up — naming it, and saying it keeps running and can be picked up in the Sai app. Hanging up with it unmentioned is the failure; so is describing it as finished | endCall → held by `LeavingWorkPolicy` | 10a |
| **B26** | "Leave it running, then hang up" | wait | It hangs up **without stopping the task** — the ask is one-shot, so the second endCall goes through. Then try the other answer on a later call: "stop it first" should `interrupt` and *then* hang up | endCall, second time | 10a |
| **B27** | *(during the goodbye, ~1 s in)* "wait, actually —" | **cut in** — but see §2.3 | The hangup **aborts**, Sai carries on, and does **not** say goodbye a second time | goodbye cancelled | 10d |
| **B28** | *(let Sai finish a reply, then a moment later)* "one more thing —" | wait, then speak | Also cancels a pending hangup. Both triggers must work | goodbye cancelled | 10d |
| **B29** | "Okay, that's everything — you can hang up" | wait | Sai says goodbye, **then** the call ends a beat later. Not the reverse, not both at once | endCall → `EndAfterGoodbye` | 10b |

**Where B27/B28 can drift:** if the hangup already went through at B25/B26 you cannot reach them.
Record them **not-reached** and retry in a short extra call — that is a real result, not a pass.

### Call C — 6 min, mostly waiting: the idle guard

Start a call, hear the greeting, then **say nothing for five minutes**. Put the phone down.

- **Expect:** the call ends itself at ~5 min, **and says why**. The guard moved from the server to the
  phone in the 2026-08-12 change and has never run on hardware.
- **Expect the whole sentence.** This was cut mid-word on device: teardown waited a flat 1.8 s and the
  line runs about four seconds. It now waits for the audio to actually drain (capped at 12 s), so
  "…Start again from your phone." should land complete before the call drops. A clipped ending means the wait is
  not seeing the playback queue; `sign-off:` lines in logcat say what it concluded.
- **Watch for:** a call still up at 6 min (guard not armed), or one that dies silently (it ends but
  the reason never reaches you).
- The 60-minute ceiling is the same code path with a different constant; not worth an hour.

---

## 6. Scoring

Ten checks, ~20 lettered cases. Record **pass / fail / not-reached** per row, and for each failure the
log excerpt **and what you actually heard** — for a wording failure the transcript *is* the evidence,
and three of Call A's five failure shapes are wording ones.

**10c, the farewell guard, is no longer here.** It passed cleanly on 2026-08-20 — Sai held the
hang-up and asked instead — and it needed a call of its own because the guard fires once per call.
`ON_DEVICE_CHECK.md` check 10c is still the durable gate for it; this doc is the run script, and a beat
that reliably passes is worth its minute somewhere else.

**Not-reached is a real result.** It usually means an earlier beat left the session somewhere the
later one cannot be provoked from, which is itself worth knowing. B13/B14 have nothing to act on if
B10 never queued; B27/B28 are unreachable if B25 hung up; A1–A3 and A11 need a machine that was
actually asleep.

**Score the wake separately from the switch.** They are one check but two mechanisms, and Call A can
easily pass every switch beat while the wake says nothing at all — that is a pass and a fail, not a
partial.

**A failure is not automatically a regression.** The model is a noisy instrument and several of these
have never been perfect. What makes it a regression is being **new** — compare against the last build
run on this hardware before concluding anything.

Anything found by ear that is worth keeping should become a golden scenario in
`fsm/GoldenScenarios.kt`, not a memory.
