# Voice concierge — client protocol

The wire contract this app implements against Sai's cloud-api: what a client needs to start a call,
every endpoint it uses, and the obligations it cannot delegate to the server.

**This app owns the conversation** — the live voice model, the microphone and speaker, the
orchestration FSM ([`VOICE_FSM.md`](VOICE_FSM.md)), the spoken lines, and its own Gemini credential.
**The server owns the agent**: the machine doing real work, and the writes that reach it. This
document is the seam, and nothing else here restates it — the modules in
[`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) are described in terms of the obligations below.

> **Provenance.** This is the client's copy of a contract whose server half lives in another
> repository. Vendored so this repo is self-contained; the two are kept in step by hand, and the
> fixtures under `app/src/test/resources/parity/` are what actually catch drift.

> **Rewritten 2026-08-12.** This used to describe a WebSocket to a server-side FSM, bootstrapped by
> `POST /v1/concierge/session` with a server-minted Gemini token. All of that is gone. The FSM moved
> to the device (see sai-fi's `docs/VOICE_FSM.md`), the client brings its own API key, and voice is no
> longer billed. What remains is a small HTTP surface. See
> `docs/plans/2026-08-12-reduced-voice-concierge.md`.

Machine-readable companions live in `app/src/test/resources/parity/`, generated from the server's
source and committed here.

## 1. Getting a session

There is nothing to fetch. A client needs three things, all its own:

| | Where it comes from |
| --- | --- |
| A Gemini API key | The user's own. sai-fi takes it from `local.properties` at build time |
| The system prompt, tools and voice | Ships with the client. sai-fi's is `assets/voice-profile.json` |
| A Firebase ID token | Google sign-in, for the agent half only |

The client opens its Live session **directly** with Google. Audio never touches cloud-api, and the
voice half of a call works with no Simular server reachable at all.

## 2. `/v1/voice/*` — reaching the agent

Bearer-authenticated with the Firebase ID token. The **channel is pinned by the route**: a caller
cannot claim to be `voice` and so cannot buy the concierge bypass that goes with it.

| Endpoint | Body | Answers |
| --- | --- | --- |
| `POST /message` | `machineId`, `message`, `deliveryMode?`, `attachments?` | `{sessionId?, delivered, pendingId?, notice?}` |
| `GET /stream?machineId=` | — | SSE of agent events, for the life of the call |
| `POST /cancel-queued` | `machineId`, `pendingId` | `{outcome: 'cancelled'\|'already-started'}` |
| `POST /send-now` | `machineId`, `pendingId` | `{outcome: 'sent'\|'already-started'}` |
| `POST /abort` | `machineId` | `{aborted}` |
| `POST /reset` | `machineId` | `{outcome: 'ok'\|'rate-limited'\|'failed'}` |
| `POST /approve` | `machineId`, `approvalId`, `decision`, `values?` | `{resolved}` · **422** on a rejected pick |

**`deliveryMode`** decides how a BUSY agent receives the message: `steer` folds it into the running
turn, `queue` holds it and runs it as a fresh one. Omit it for a new task on an idle agent.

**The write acks; it does not stream.** The client holds one long-lived `/stream` connection and must
hear things no write of its own provoked — an approval raised mid-turn, a completion after a
reconnect, a resolution the user made in the desktop app, a turn the agent began by draining its own
queue. `delivered: false` means the router handled the message itself and nothing will stream; a
client that waits for events anyway will wait forever.

**`queue` can come back with no `pendingId`.** That means the agent turned out idle and the task
STARTED. Do not report it as waiting, and do not hold an id that will never exist.

**The two race answers are load-bearing.** `cancel-queued` and `send-now` race the agent's own drain
by construction, and `already-started` is the truth, not an error. Reporting a cancellation that did
not happen is how "that's off the list" gets said about a task that is still booking a table.

**Selections are sent FLAT.** A spoken pick carries no question index, so the server groups the
values per question from the approval doc — the client cannot do this correctly. A rejected pick is a
**422**, and the client must keep the request pending and ask the model to re-present. Treating it as
success deadlocks the call: the client clears its state while the approval stays open.

### Agent events (SSE)

`text` · `progress` · `status` · `complete` · `error` · `notice` · `approval-request` ·
`approval-resolved` · `queued-task-started` · `session-state`

An unrecognised frame must be **dropped, not thrown**: a newer server must not be able to end a call
by sending something the client predates.

**`notice` is not optional.** It is news about DELIVERY rather than work — a hibernated machine
waking, an agent that is offline. Dropping it is how a task sent to a sleeping machine bought a
silent minute with no explanation.

## 3. Orchestration is the client's

The FSM that admits, queues, interrupts and resolves is **this app's**, and it is the largest thing
this protocol no longer specifies. [`VOICE_FSM.md`](VOICE_FSM.md) documents the one this app runs, including
the rules that are not obvious: the admission rule, the durable/non-durable queue split, the three
races against the agent's drain, and the ordering constraints that follow from them.

A fork does not have to reproduce it. It does have to answer the same questions.

## 4. Device tools — the client's obligations

Tools declared to the model that the **server never receives an effect for and cannot carry out**.
The client MUST answer every call, or the model stalls mid-turn waiting for a function response.

| Tool | The client must |
| --- | --- |
| `getSaiStatus` | Answer from its own `ActivityLog` — see §5. Never forward |
| `recallHistory` | Fetch `GET /v1/agents/context` and return the history. Never forward |
| `switchMachine` | Switch machines locally, repoint the stream, then `{result:'ok'}` |
| `endCall` | End the call after the model has said goodbye, then `{result:'ok'}` |
| `captureImage` | Capture, hold the image locally, and report success **or the real failure reason** |

`captureImage` failure text has two parts: a plain primary reason, then a clearly marked
`(technical detail: …)` suffix. The prompt instructs the model to speak only the primary reason
unless asked. Keep that shape.

## 5. Client-rendered strings

Some strings the user hears or reads are rendered **on the client**, from an agent event. The
canonical wording lives in `voice/contract/nudges.ts` and `voice/contract/activity-log.ts`, and the
fixtures pin it byte for byte so a port cannot drift silently.

- **Nudges** (`describeAgentEvent`) — model-facing text derived from an agent event.
- **Activity lines** (`renderAgentActivity`) — for an on-screen log. Carries glyphs.
- **Spoken status** (`ActivityLog.statusText`) — what `getSaiStatus` answers. Spoken phrasing, no
  glyphs, and it deliberately reports only the past.

> **Security invariant — keep the fencing intact.** Agent-derived text (titles, summaries, errors, web
> content) is UNTRUSTED. In every nudge the instruction comes FIRST and the untrusted text is fenced
> inside `"""…"""`, so the model treats it as data. A port that drops the fence turns any web page the
> agent reads into a prompt-injection vector.

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

`401` bad token · `403` machine not owned. Treat both as permanent for the call. There is no longer a
`402`: voice is not billed, and only agent work is.

## 8. Keeping a port honest

If you implement this in another language, mirror the guards that already exist rather than inventing
new ones:

1. **Rendered strings** — load the fixture JSON and assert byte-identical output.
   (`ConciergeProtocolParityTest.kt` / `ActivityLogParityTest.kt` are the reference.)
2. **Orchestration** — `FsmGoldenTest` runs 62 scenarios that pin what the FSM does with
   every input sequence that has ever mattered. If you write your own, that catalog is the spec worth
   copying; each scenario names the failure it prevents.

Refresh the fixtures with `npm run -w cloud-api concierge:fixtures` and copy them across; the
generator writes a vendored copy automatically while both trees share a checkout.
