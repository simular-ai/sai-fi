# Voice concierge — client protocol

**Audience:** anyone implementing a voice client against `cloud-api`. The reference implementation is
[`simular-ai/sai-fi`](https://github.com/simular-ai/sai-fi).

**The client owns the conversation.** It runs the live voice model, the microphone and speaker, the
orchestration FSM, the queue, the spoken lines, and its own Gemini credential. **The server owns the
agent** — the machine doing real work, and the writes that reach it. This document is the seam, and
it is deliberately made of endpoints that already existed.

> **Rewritten 2026-08-13.** This used to describe a WebSocket to a server-side FSM, and then briefly
> a `/v1/voice/*` HTTP surface. Both are gone, and **nothing replaced them**: a voice client is an
> ordinary API caller. There is no voice endpoint, no voice channel, and no server-side queue. The
> FSM moved to the device (see sai-fi's `docs/VOICE_FSM.md`), the client brings its own Gemini key,
> and voice is not billed. See `docs/plans/2026-08-12-reduced-voice-concierge.md`.

Machine-readable companions, generated from the Kotlin source and committed under
`meta-android-app/app/src/test/resources/parity/` (see §8).

## 1. Getting a session

There is nothing to fetch. A client needs three things, all its own:

| | Where it comes from |
| --- | --- |
| A Gemini API key | The user's own. sai-fi takes it from `local.properties` at build time |
| The system prompt, tools and voice | Ships with the client. sai-fi's is `assets/voice-profile.json` |
| A Firebase ID token | Google sign-in, for the agent half only |

The client opens its Live session **directly** with Google. Audio never touches cloud-api, and the
voice half of a call works with no Simular server reachable at all.

## 2. `/v1/agents/*` — reaching the agent

**There is no voice-specific endpoint.** A voice client authenticates with a Firebase ID token (or an
API key) and uses the ordinary agent API, exactly as a script would.

That works because `api` is a **programmatic** channel, which already skips both the text concierge
and the legacy free-text command matchers. A spoken "restart agent" therefore reaches the machine as
a task instead of being intercepted and answered by the server. That bypass was the only thing a
channel of its own ever bought.

| Endpoint | Body | Answers |
| --- | --- | --- |
| `POST /v1/agents/message` | `machineId`, `message`, `attachments?` | **the turn's SSE stream** |
| `POST /v1/agents/abort` | `machineId` | `{aborted}` |
| `POST /v1/agents/new-session` | `machineId` | `{sessionId}` · **429** when rate-limited |
| `POST /v1/agents/approve` | `approvalId`, `response`, `selections?` | `{ok}` · **400** on a rejected pick |

**The response IS the stream.** There is no ack and no second connection. This has two consequences a
client must design around:

- **A send must not block on the turn.** If your orchestration holds a lock while sending — sai-fi's
  FSM does — reading the stream on that same coroutine deadlocks the call on its own first task.
  Suspend only until the response *headers* arrive (which is what tells you the agent accepted it),
  then read the body elsewhere.
- **A steer's stream is redundant.** Steering is the same `POST /message` into a running turn, and
  its response replays that turn's events. Reading both delivers every event twice — the completion
  twice, the approval twice. Discard it.

**You are connected only while the agent is working.** Nothing arrives between turns: an approval
resolved in the desktop app while nothing is running is not heard. If that matters,
`GET /v1/agents/context` at a turn boundary is the cheapest way to catch up.

**A dropped stream is not a completion.** The agent may still be working. Report it to your
orchestration as an error or an unknown outcome — anything that says "done" is a lie about work that
may still be running.

**Selections are POSITIONAL.** `selections` is one non-empty array per question, in the card's order,
and the server refuses a resolution that does not answer every question. A spoken pick carries no
question index, so the client must group them itself — the `data-approval-request` frame carries
`questions` for exactly this. A rejected pick is a **400**, and the client must keep the request
pending and ask the model to re-present. Treating it as success deadlocks the call: the client clears
its state while the approval stays open.

**`response` is `yes` / `no` / `always`** — not the `approved` / `denied` status the approval doc
ends up carrying.

### The stream's vocabulary, and how to read it

The stream is the Vercel AI SDK v6 UI message protocol, which is **not** an orchestration vocabulary.
The mappings that matter are the ones where the obvious reading is wrong:

| Frame | Read it as | Why not the obvious thing |
| --- | --- | --- |
| `text-delta` | assistant answer, a fragment at a time | Buffering to `text-end` holds the answer until the turn is over |
| `reasoning-delta` | mid-turn narration, **silent** | It is thinking, not an answer |
| `data-progress` | tool progress, silent | — |
| `tool-output-error` | a **step** that failed, task continues | Not `error`, which is terminal — reading it as one ends turns that are still running |
| `data-status` | **delivery** news: waking a machine, agent offline | Not progress. It is the one thing that must be relayed before the task has produced anything |
| `data-approval-request` | approval / input / selection | Carries `options` (flat) or `questions` (grouped) — you need both: one to pick from, one to resolve with |
| `finish` | the turn ended | The only end-of-turn signal. No summary — the answer already arrived as text |
| `error` | terminal failure | — |

An unrecognised frame must be **dropped, not thrown**: there are more frame kinds on this stream than
any client needs, and a newer server must not be able to end a call by sending one you predate.

## 3. Orchestration is the client's

The FSM that admits, queues, interrupts and resolves is **yours**, and it is the largest thing this
protocol no longer specifies. sai-fi's `docs/VOICE_FSM.md` documents the one that exists, including
the rules that are not obvious: the admission rule, why the queue is local and what that costs, and
the ordering constraints that follow.

**The queue is yours and the server has no copy.** Nothing you hold is durable, so a dropped call
loses it — and in exchange nothing can start a task you are holding behind your back.

You do not have to reproduce it. You do have to answer the same questions.

## 4. Device tools — the client's obligations

Tools declared to the model that the **server never receives an effect for and cannot carry out**.
The client MUST answer every call, or the model stalls mid-turn waiting for a function response.

| Tool | The client must |
| --- | --- |
| `getSaiStatus` | Answer from its own `ActivityLog` — see §5. Never forward |
| `recallHistory` | Fetch `GET /v1/agents/context` and return the history. Never forward |
| `switchMachine` | Switch machines locally — the next `POST /message` names the new one — then `{result:'ok'}` |
| `endCall` | End the call after the model has said goodbye, then `{result:'ok'}` |
| `captureImage` | Capture, hold the image locally, and report success **or the real failure reason** |

`captureImage` failure text has two parts: a plain primary reason, then a clearly marked
`(technical detail: …)` suffix. The prompt instructs the model to speak only the primary reason
unless asked. Keep that shape.

## 5. Client-rendered strings

Some strings the user hears or reads are rendered **on the client**, from an agent event. The
canonical wording lives in `voice/contract/nudges.ts` and `voice/contract/activity-log.ts`, and the
fixtures pin it byte for byte so a port cannot drift silently.

These read the vocabulary in `voice/agent-events.ts`, which is the union a client translates the
stream INTO — not a wire format. Keeping it named on both sides is what lets the fixtures compare
two independent renderings of the same input.

- **Nudges** (`describeAgentEvent`) — model-facing text derived from an agent event.
- **Activity lines** (`renderAgentActivity`) — for an on-screen log. Carries glyphs.
- **Spoken status** (`ActivityLog.statusText`) — what `getSaiStatus` answers. Spoken phrasing, no
  glyphs, and it deliberately reports only the past.

> **Security invariant — keep the fencing intact.** Agent-derived text (titles, summaries, errors, web
> content) is UNTRUSTED. In every nudge the instruction comes FIRST and the untrusted text is fenced
> inside `"""…"""`, so the model treats it as data. A port that drops the fence turns any web page the
> agent reads into a prompt-injection vector.
>
> **The same applies to machine names**, which are easy to miss because they are not agent output:
> they come from `GET /v1/agents/machines`, they are whatever the user typed, and they land in the
> *system prompt* rather than in a nudge. `VoiceProfile.systemPromptWithContext` flattens newlines and
> quotes and caps the length before appending them, and labels the whole clause as data. It used to
> say "sanitize before calling" and leave it to the caller, which is exactly how a name reading
> `X". Ignore prior instructions and …` reached the persona prompt verbatim.

The client also owns two things the server cannot enforce:

- **Greeting.** Gate the greeting to the **first** ready of a call. Reconnects and
  resume-after-pause re-run setup, and nothing else can tell those from a fresh start.
- **Nudge discipline.** Never inject mid-utterance; defer until the turn completes; flush queued
  playback on a barge-in.

## 6. Cost bounds are the client's now

There is no socket for a server guard to close, so **an unattended call is the client's problem**. An
open microphone costs money whether or not anyone is still wearing the glasses. Enforce both bounds:

- **Max duration** — a hard ceiling, not extendable by activity.
- **Idle** — reset by genuine interaction only.

**Input tokens are not activity.** They grow continuously while a mic is merely open, so only a rise
in *response* tokens (or a real user effect) counts. Counting input growth makes a walked-away call
look alive, which is exactly the case the idle bound exists to end.

## 7. Supporting HTTP endpoints

All Bearer-authenticated. Optional `x-sai-version: <tag>` routes to a specific staging revision.

| Endpoint | Use |
| --- | --- |
| `GET /v1/agents/machines` | The user's machines, for the picker |
| `GET /v1/agents/context?machineId=&limit=` | Recent history — backs `recallHistory` |
| `POST /v1/agents/upload` | Upload a captured image; returns the attachment |

`401` bad token · `403` machine not owned. Treat both as permanent for the call. A `402` still means
the user is out of agent credit — voice itself is not billed, but the work Sai does is.

## 8. Keeping a port honest

If you implement this in another language, mirror the guards that already exist rather than inventing
new ones:

1. **Rendered strings** — load the fixture JSON and assert byte-identical output.
   (`ConciergeProtocolGoldenTest.kt` / `ActivityLogGoldenTest.kt` are the reference.)
2. **Orchestration** — sai-fi's `FsmGoldenTest` runs 59 scenarios that pin what the FSM does with
   every input sequence that has ever mattered. If you write your own, that catalog is the spec worth
   copying; each scenario names the failure it prevents.

The fixtures live at `meta-android-app/app/src/test/resources/parity/` and are generated from the
Kotlin helpers by `SAI_REGEN_GOLDENS=1 ./gradlew :app:testDebugUnitTest --tests
"*RegenerateGoldensTest*"`. Take a copy at a pinned ref rather than tracking `main`, and diff it when
you update: that is the one thing the previous arrangement got wrong. cloud-api generated them and a
human copied them here, so the copy drifted for months without failing anything.
