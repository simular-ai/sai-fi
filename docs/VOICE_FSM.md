# The Voice FSM — the conversation this app now owns

The state machine that decides what happens between the user speaking and the agent working, and
between the agent reporting and the user hearing about it. It lives in
`meta-android-app/…/saispike/fsm/`.

**Status:** ported and unit-tested; not yet wired into `CallService`. See "Where this is going" at
the end.

This is a design doc, not an API reference — the KDoc on each class covers the *what*. What follows
is the *why*, because most of these rules exist to prevent a specific failure that was seen on a real
device, and every one of them looks like something you could simplify until you know what it cost.

**If you are forking this repo, read this before changing anything under `fsm/`.**

---

## 1. Why the client owns this at all

The server does no LLM inference for voice. The brain is the Gemini Live model running *on this
device*, with your own key; audio never touches the server at all. What the server used to
contribute was pure orchestration — hold this task, ask before cancelling, don't resolve that
approval — and orchestration is exactly what a forked client needs to be able to change.

So the split is:

| Concern | Where | Why |
| --- | --- | --- |
| Agent access, approval writes | **server** | Someone else's computer doing real work — the trust boundary, and the billed half |
| Gemini credential | **this app** | Your key, from `local.properties` |
| System prompt, tools, voice | **this app** | Bundled — there is no server call that delivers them |
| **The FSM, spoken lines, nudges** | **this app** | The conversation is yours to change |

The server has **no voice-related endpoint at all**. This app reaches the agent through
`/v1/agents/*` — the same API a script or a CI job would use — and everything else about a call is
here. That is deliberate: a fork should not need a server change to work, and the only thing a
channel of its own ever bought was the concierge bypass, which `api` already has because it is a
programmatic channel.

### Your own Gemini key

Put `gemini_api_key` in `meta-android-app/local.properties` and build. That is the same route
`presenter_key`, `firebase_api_key` and `concierge_url` already take, and it is listed with them in
the README key table.

There is no server-minted token and no fallback to one. **The voice half of this app needs no
Simular server at all** — which is the point of the repo being public. You pay Google directly for
what you use.

**Voice is not billed by us.** Only agent calls are: the work Sai does on your machine, through
`/v1/agents/message`. Talking to the concierge costs whatever your Gemini key costs you, and nothing
else.

One caveat to hold onto: a `BuildConfig` field is a plaintext constant in the built APK, so **the key
travels with any build you share**. That is fine for building and testing yourself, and it is the
same bargain `presenter_key` already makes. It is not fine for handing that APK to someone else, and
not fine for a published release. Never log it, never let it into the presenter feed — and note it is
never sent to a Simular endpoint, because the server has no reason to see it.

## 2. The shape: pure core, thin driver

Everything in `State.kt`, `Effects.kt`, `Speech.kt` and `AgentIngest.kt` is pure — a state and an
input go in, a new state comes out. No coroutines, no clock, no I/O. `Concierge.kt` is the only part
that suspends, and the ports (`AgentBridge`, `VoiceChannel`) are the only way it reaches the world.

That split is what makes 59 golden scenarios runnable as plain JVM tests. It is the same shape
`GlassesLink` uses, for the same reason.

**Do not make the pure parts call the clock or the network.** The moment they do, the catalog stops
being runnable and the only way to check a behaviour change is a device.

## 3. Modes and transitions

`idle · clarifying · working · awaiting-user · negotiating`

`clarifying` and `negotiating` are reachable only through `askAndWait`. Nothing guards on them — they
are descriptive labels for the UI. Every guard in the system tests `working`, `idle`, or
`awaiting-user`.

### The asymmetry that looks like a bug and is not

Two agent events end a turn, and they are handled differently **on purpose**:

```kotlin
// status: idle|error  — GUARDED
if (state.mode == Mode.WORKING) state.endTurn().withMode(Mode.IDLE) else state

// complete | error    — UNGUARDED, and clears the approval too
state.endTurn().noPendingApproval().copy(mode = Mode.IDLE, awaiting = null)
```

A stray `idle` status must **not** end a turn that is merely blocked on an approval — the turn is
waiting, not over, and its in-flight requests stand.

A `complete` **must** end it even from `awaiting-user`, because the turn that owned the approval is
genuinely finished. This was once guarded the same way as the first, and the result was a permanent
wedge: the FSM sat in `awaiting-user` with a dead `pendingApprovalId`, the queue never drained
(draining needs `idle`), and every later forward was held behind an approval nobody could answer.
Nothing started again for the rest of the call.

Unifying these two into one rule reinstates that wedge. They are not inconsistent; they are answers
to different questions.

## 4. Effects: the conversation is open, the capabilities are not

The model can say anything. It can *do* only the fifteen things in `Effects.kt`. `parseEffect` is the
boundary, and an effect it does not recognise — or whose payload is the wrong shape — is **dropped**,
not guessed at. A newer model inventing a capability does not get to exercise it.

The parse rules are deliberately asymmetric, and the asymmetry is the contract:

- `chooseOption` **filters** junk values and only fails when nothing survives — a partly-malformed
  pick list should still resolve what it can.
- `askAndWait` and `setState` **reject outright** on a bad enum — an invented mode must never become
  a state.
- An unrecognised `urgency` **degrades to normal** rather than dropping the task.

`askAndWait` does **not** speak. It is a pure state signal: the Live model has already voiced the
question, and speaking it again doubles it up and interrupts the model mid-sentence.

## 5. `say` vs `instruct` — the axis that keeps producing bugs

```kotlin
voice.say(text)      // the user hears this VERBATIM
voice.instruct(text) // the MODEL reads this; the user hears only its reply
```

The client wraps a `say` in "say this to the user, verbatim". So a `say` must *be* the sentence,
never a description of what to do.

Both recorded regressions in this area were the same mistake. `RESELECT_NUDGE` went out as `say`,
and a user heard *"call chooseOption with the exact option value"* read aloud, function name
included.

The rule: **reporting a fact to the user is `say`; correcting the model is `instruct`.** Every
`instruct` string starts `[system]` and says what did *not* happen, because the model has usually
already tool-acked the call and will otherwise confirm something that never occurred.

## 6. Admission: a task arriving mid-turn is held, not folded in

If an approval is pending, or anything is already in flight, a new task is **queued** rather than
forwarded.

This matters because every forward lands in the same chat session, so one agent turn routinely
carries several unrelated requests — and `abort()` has no scope. Folding a restaurant booking into a
running email check means "cancel the booking" kills the email check too, silently. That is a real
device report from 2026-07-30.

The acknowledgement must not sound like it started. A queued task is *not* underway, and a user who
hears "on it" waits for a result nothing is producing. So the spoken line names what it is behind:

> "Got it — I'll start that as soon as I'm done with: …"

## 7. The queue is local, and that is a trade

Held tasks live in this FSM and **nowhere else**. Nothing is written server-side when a task is
held; the agent is told about it only when `maybeDrainQueue` forwards it.

**What this costs.** A held task does not survive a dropped call, a killed app, or a crash. Work the
user was *promised out loud* disappears with nothing said. This is the worst property of the current
design, and it is not an oversight — the queue used to be a durable Firestore doc precisely so that
could not happen.

**What it buys.** Nothing else can start a task this FSM is holding. That single fact removes an
entire class of failure, described below.

The consequence to hold onto when changing anything here: **`maybeDrainQueue` is the only thing in
the world that starts a held task.** So every path that can leave `mode` at `IDLE` has to reach it.
It runs after each agent event, which covers every way a turn ends today; miss one and a task the
user was told was coming simply never runs.

## 8. The races that used to be here

`Races.kt` is gone. It guarded three cases that arose because held work lived in two uncoordinated
places — this FSM's queue, and a durable doc the agent drained at its own turn boundary. Between the
user asking and the code acting, the agent could already have started the task being cancelled.

With one copy of the queue, two of the three cannot happen:

- **`dropDurably`** — an entry may already have been drained, so a cancel had to report which tasks
  got away and count a throw as `started`. Now removing the entry *is* the cancellation, and
  "that's off the list" is unconditionally true.
- **`abortTaskThatBeatTheCancel`** — the cancel lost, so the named task was already running and had
  to be aborted (stopping everything else in the turn with it, since `abort()` has no scope). It
  cannot lose a race that does not exist.

The third survives, in `ApprovalHandlers.kt`:

- **`denyApprovalKilledByAbort`** — the abort killed the turn an approval belonged to. `denied` is
  the honest status: the user stopped the task, they did not agree to it. Without this the card can
  only expire, and the user hears "that request timed out" about work they cancelled minutes ago.
  This was never a race with the queue, which is why it is still needed.

Three golden scenarios went with them — S47, S48 and S50. Each pinned a sequence that can no longer
be produced, and none was dropped to make the suite pass.

### Ordering constraints that survive

1. **`takePendingAttachments` at ENQUEUE, not at drain** — the bridge's photo stash is drained by
   whoever writes next, so a held task must take its own or drain with someone else's picture.
2. **`denyApprovalKilledByAbort` before the state clear** — it reads `pendingApprovalId`.
3. **`resolveApproval` before clearing the timer and state** — so a throw leaves the approval still
   resolvable.

## 9. The approval guard is a security boundary

A pick is handed to the agent as the user's **trusted** choice. A value that was never offered —
hallucinated by the model, mistranscribed from speech — must not be able to resolve a guardrail.

Matching is **exact string equality against the option `value`**. Not the label, not
case-insensitive, not trimmed. `allowOther` is the one exception and it is explicit: the question
itself opted into free text.

A rejected pick keeps the request **pending**, keeps its **timer running**, sends **nothing to the
agent**, and nudges the model to re-present. The server re-checks this independently at
`/v1/agents/approve`, so a client bug degrades to a rejected approval rather than a forged one — but
keep the client guard, because it is what gets the model to ask again.

Encoding matters too. The agent resolves a choice **positionally** — one non-empty group per
question, in the card's order — and a spoken pick carries no question index. `groupSelections` puts a
flat answer back into its slots: a value goes to the first question that offered it, and free text
to the first question that accepts it.

A question left with no pick yields an **empty group**, deliberately. The agent then refuses the
whole resolution, which surfaces as a re-present nudge. Inventing a pick to make the shape valid
would answer for the user, and silently omitting the group would shift every later answer one
question to the left.

## 10. Concurrency: why a Mutex, not the house idiom

The rest of this app serialises by confining work to `Dispatchers.Main.immediate` and marking
cross-thread reads `@Volatile`. The FSM does **not**. It owns a `Mutex` and stays
dispatcher-agnostic.

Two reasons, and the second is the decisive one:

**What needs serialising.** Every handler is read-state → suspend on I/O → write-state. Without a
lock, two interleave at the suspension point and the second writes over a state the first already
changed. Concretely: two forwards both observe an empty `inFlight` before either records a turn, both
take the immediate path, and the user's restaurant is booked twice.

**Testability.** `Dispatchers.Main` has no implementation in a plain JUnit run, and nothing in this
suite installs one. An FSM that named it could not be unit-tested at all — and the 59 golden
scenarios are the only thing that proves this port matches the server's behaviour.

The server does the same job with a promise-tail chain. One `Mutex` is the same guarantee.

## 11. The golden catalog is the spec

`FsmGoldenTest` runs the catalog in `GoldenScenarios.kt`: a fixed input sequence per scenario, driven
through the real FSM against fakes, asserting the **effect and state trace**.

It never asserts phrasing. The live model's wording varies, and phrasing quality is the eval's job.

Scenario names match the server's catalog exactly so the two can be reconciled mechanically. If you
add behaviour, add a scenario; if you change behaviour, a scenario should fail. One that does not is
either untested or wrong.

`PORTED_SCENARIO_COUNT` is pinned deliberately — a catalog that silently shrinks is a suite that goes
green with less in it.

---

## Where this is going

The FSM is ported, tested, and reaching the agent over `/v1/agents/*`, but it is **not yet driving a
call**. The remaining work is all in `CallService`:

1. `CallController` delegates to the FSM instead of reacting to server directives; `AgentEventRouter`
   and `HeldNudgeQueue` fold in rather than running a second queue.
2. `VoiceSession.start()` is called for the call, and the model's tool calls routed into
   `applyEffects`.

Two things worth deciding on rather than inheriting:

- **A held task lost to a dropped call is currently silent.** Saying something on reconnect — even
  just naming what was waiting — would cost little and is the obvious mitigation for §7.
- **Nothing is heard between turns.** An approval resolved elsewhere while the agent is idle will not
  reach the FSM. If that turns out to matter in practice, a poll of
  `GET /v1/agents/context` at turn boundaries is the cheapest fix that needs no server change.

## See also

- [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) — the app's architecture and the two links
- [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) — the wire contract with the server
- [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) — what unit tests cannot cover
