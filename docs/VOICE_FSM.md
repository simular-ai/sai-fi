# The Voice FSM — the conversation this app now owns

The state machine that decides what happens between the user speaking and the agent working, and
between the agent reporting and the user hearing about it. It lives in
`meta-android-app/…/saispike/fsm/`.

**Status:** ported and unit-tested; not yet wired into `CallService`. The app still runs the
WebSocket path while the port is proven. See "Where this is going" at the end.

This is a design doc, not an API reference — the KDoc on each class covers the *what*. What follows
is the *why*, because most of these rules exist to prevent a specific failure that was seen on a real
device, and every one of them looks like something you could simplify until you know what it cost.

**If you are forking this repo, read this before changing anything under `fsm/`.**

---

## 1. Why the client owns this at all

The server does no LLM inference for voice. The brain is the Gemini Live model running *on this
device*; audio never touches the server, which only mints the ephemeral token. What the server used
to contribute was pure orchestration — hold this task, ask before cancelling, don't resolve that
approval — and orchestration is exactly what a forked client needs to be able to change.

So the split is:

| Concern | Where | Why |
| --- | --- | --- |
| Gemini ephemeral token | **server** | The API key can never ship to a handset |
| Billing / credit gate | **server** | Client-side metering is not a control |
| System prompt + tool declarations | **server** | Model config, delivered per session |
| Agent access, approval writes | **server** | The trust boundary |
| **The FSM, spoken lines, nudges** | **this app** | The conversation is yours to change |

A fork still needs a server for the first four. It does not need permission for the rest.

## 2. The shape: pure core, thin driver

Everything in `State.kt`, `Effects.kt`, `Speech.kt` and `AgentIngest.kt` is pure — a state and an
input go in, a new state comes out. No coroutines, no clock, no I/O. `Concierge.kt` is the only part
that suspends, and the ports (`AgentBridge`, `VoiceChannel`) are the only way it reaches the world.

That split is what makes 62 golden scenarios runnable as plain JVM tests. It is the same shape
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

## 7. Durable vs non-durable queue entries

This is the single most load-bearing distinction in the queue, and getting it wrong runs a task
twice.

| | `pendingId` present | `pendingId` absent |
| --- | --- | --- |
| Created by | admission (`forwardToAgent` while busy) | the model's `enqueue` effect |
| Lives in | a durable pending doc, server-side | this FSM only |
| Who starts it | **the agent**, on its own schedule | only `maybeDrainQueue` / `sendQueuedNow` |
| FSM entry is | a *display copy* | the whole truth |

So `maybeDrainQueue` **never forwards an entry with a `pendingId`**. The agent will drain that doc
itself; forwarding it here books the table twice.

A durable entry also survives a dropped call, which an in-memory queue does not — and a queued task
the user was promised out loud is the worst thing to lose on a reconnect.

## 8. The three races

Held work lives in two uncoordinated places: this FSM's queue, and the durable doc the agent drains
at its own turn boundary. Between the user asking and the code acting, the agent may already have
started the task being cancelled. `Races.kt` guards three cases.

**`dropDurably`** — the entry may already have been drained. A cancel that *throws* is counted as
`started`, not `dropped`: the outcome is unknown, so claim nothing. An unreported failure here is how
"that's off the list" gets said about a task that is still going to run.

**`abortTaskThatBeatTheCancel`** — the cancel lost. Aborting without asking is justified narrowly:
the user *named* this task and asked for it to stop. Note it still stops everything else in the turn,
because `abort()` has no scope. It says both halves out loud — it had started, and it is stopped now
— since either alone misleads.

**`denyApprovalKilledByAbort`** — the abort killed the turn an approval belonged to. `denied` is the
honest status: the user stopped the task, they did not agree to it. Without this the card can only
expire, and the user hears "that request timed out" about work they cancelled minutes ago.

### Ordering constraints that follow

These look arbitrary in the code. They are not:

1. **`takePendingAttachments` before `queueTask`** — the bridge's photo stash is drained by whoever
   writes next, so a held task must take its own or drain with someone else's picture attached.
2. **`dropDurably` before `abort()`** — the other way round, the abort ends the turn and the agent
   drains the next queued doc seconds later. "Stop" would launch a task.
3. **`denyApprovalKilledByAbort` before the state clear** — it reads `pendingApprovalId`.
4. **`resolveApproval` before clearing the timer and state** — so a throw leaves the approval still
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

Encoding matters too: exactly one pick uses `selectedOption`, two or more use `selectedOptions`. A
single-element list sent as the plural approves the card and silently drops the user's answer.

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
suite installs one. An FSM that named it could not be unit-tested at all — and the 62 golden
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

The FSM is ported and tested but not yet driving a call. The remaining work:

1. `ConciergeClient` moves to `POST /v1/voice/message` + SSE `GET /v1/voice/stream`.
2. `ConciergeSocket` is deleted; `CallController` delegates to the FSM instead of reacting to server
   directives; `AgentEventRouter` and `HeldNudgeQueue` fold in rather than running a second queue.
3. The server's WebSocket and its copy of the FSM are removed.

Until then this app still runs the WebSocket path, and the FSM here is inert.

## See also

- [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) — the app's architecture and the two links
- [`CONCIERGE_CLIENT_PROTOCOL.md`](CONCIERGE_CLIENT_PROTOCOL.md) — the wire contract with the server
- [`ON_DEVICE_CHECK.md`](ON_DEVICE_CHECK.md) — what unit tests cannot cover
