# Voice-concierge client protocol

The wire contract this app implements against Sai's cloud-api: how a call is minted, every message
that crosses the socket, and the obligations a client cannot delegate to the server.

**The server owns the orchestrator** — the FSM that holds the task queue, the approvals and the agent
connection. **The client owns the voice** — it runs the live model, the microphone and the speaker, and
implements a handful of tools the server cannot carry out. This document is the seam, and nothing else
in this repository restates it: the modules in [`SAI_GLASSES_APP.md`](SAI_GLASSES_APP.md) are described
in terms of the obligations below.

> **Provenance.** This is the client's copy of a contract whose implementation lives in the server
> repository; the server is the source of truth for it. Two things keep the copy honest, and neither is
> this prose: the **parity fixtures** in `meta-android-app/app/src/test/resources/parity/`, which are
> generated from the server's own functions, and the Kotlin tests that replay them. If this document and
> a green fixture ever disagree, the fixture is right. See the README's "Keeping the parity fixtures in
> sync" for how they are refreshed — it is a manual, cross-repository step.

| Pinned by                                              | What it pins                          |
| ------------------------------------------------------ | ------------------------------------- |
| `app/src/test/resources/parity/ws-messages.json`       | one example of every message          |
| `app/src/test/resources/parity/*.json`                 | the exact client-rendered strings     |
| `ConciergeSocketParityTest` / `ConciergeProtocolTest`   | that every message reaches a handler  |
| `ActivityLogParityTest` / `ConciergeProtocolParityTest` | that the rendered strings are byte-identical |

---

## 1. Bootstrap — `POST /v1/concierge/session`

`Authorization: Bearer <Firebase ID token>`. Optional `?client=<profile>` (this app sends `glasses`).

Request body (all optional): `{ machineId?: string, machines?: Array<{ machineId, name }> }`.
`machines` is the list the client already fetched for its picker; the server folds the names into the
prompt so `switchMachine` has something to match against.

Response — `SessionBootstrap`:

| Field          | Meaning                                                                           |
| -------------- | --------------------------------------------------------------------------------- |
| `token`        | Ephemeral Gemini Live token: single-use, ~2-min start window, ~30-min lifetime    |
| `model`        | Live model id to connect with                                                     |
| `systemPrompt` | The full persona prompt, with session context appended                            |
| `tools`        | Function declarations — effect tools **and** this profile's device tools (see §4) |
| `voice`        | Prebuilt voice name, so the voice does not change between sessions                |
| `expiresAt`    | ISO 8601 token expiry; re-mint before this                                        |
| `client`       | The profile actually served                                                       |

Every field is opaque server config. **Never hardcode any of them** — model churn is meant to be
absorbed by a server change, not an app release.

**Re-mint per (re)connect** — the token is single-use.

**`client` is echoed because an unknown value does not fail.** A profile name the server does not
recognise resolves to the default rather than 4xx-ing: a typo must not turn into "your voice call
cannot start". Read `client` back if you need to know which you got.

Gates, in order: `503` voice disabled / not configured · `429` too many sessions minted · `402` out of
credits · `503` billing unavailable (fails **closed** — no free voice when billing errors).

## 2. The WebSocket — `WS /v1/concierge/ws?machineId=<id>`

`Authorization: Bearer <token>` on the upgrade. Mint a **fresh** ID token for every attempt: a long
call outlives a ~1h token.

### Server → client

| `type`             | Payload     | What the client must do                                        |
| ------------------ | ----------- | -------------------------------------------------------------- |
| `agent-event`      | `{ event }` | Nudge the model to react (it may speak)                        |
| `agent-activity`   | `{ event }` | **Record only.** Never nudge the model — see the warning below |
| `speak`            | `{ text }`  | Speak it **verbatim**                                          |
| `instruct`         | `{ text }`  | Inject as model context. **Never** speak it                    |
| `approval-timeout` | —           | A pending approval is about to expire; warn the user           |

> **`agent-event` vs `agent-activity` is the difference between working and broken.** Both carry an
> `AgentEvent`. The first is meant to make the model react; the second is a display-only mirror. Route
> activity into the model and it narrates every step — which the persona prompt explicitly forbids.
>
> **`speak` vs `instruct` likewise.** A `speak` is read out word for word, so it must BE the sentence.
> An `instruct` is context — "that value wasn't offered, present the options again". Send an instruct
> down the speak path and the user hears function names read aloud.

### Client → server

| `type`       | Payload          | When                                                                       |
| ------------ | ---------------- | -------------------------------------------------------------------------- |
| `effects`    | `{ effects }`    | The model called one or more effect tools                                  |
| `attachment` | `{ attachment }` | A captured image was uploaded — send **before** the effect that carries it |
| `location`   | `{ location }`   | A position fix, when the model set `includeLocation`                       |
| `usage`      | `{ usage }`      | Cumulative Live token counts, for billing and the idle guard               |
| `keepalive`  | —                | A human is present but the model is muted (see the obligation below)       |

**Ordering contract.** `attachment` and `location` must be written on the same socket **immediately
before** the `effects` message they belong to. The server stashes them and drains the stash on the next
write. Do not interleave anything between them.

**`keepalive` obligation.** The idle cost guard treats model output tokens as proof of life, so a call
where the user muted Sai looks identical to a walked-away open mic. Send `keepalive` **only** while
muted, **only** when the mic actually heard speech recently, and **at most once a minute**. The server
cannot verify any of this; a client that sends it unconditionally makes the guard unable to end an
abandoned call, which is the guard's entire purpose.

### Close codes

`4001` max call duration, `4002` idle — both from the cost guard. On either: **tear the call down and
do not reconnect.** Treat upgrade rejections `401` / `403` / `503` as permanent too. Every other close
is transient; reconnect with backoff and a fresh token.

## 3. Effects — what the model can ask the server to do

`say` and `noop` are **not** declared to the model: speech is native, and a do-nothing tool let the
model look like it acted while doing nothing. The rest are declared and each arrives as an `effects`
message: `askAndWait`, `forwardToAgent`, `relayToAgent`, `approve`, `approveAlways`, `deny`,
`chooseOption`, `enqueue`, `interrupt`, `cancelQueued`, `sendQueuedNow`, `setState`.

Two flags on `forwardToAgent` / `relayToAgent` are **client-side** — act on them, do not forward them:

- `attachLatestImage` — send the held image as an `attachment` first, then the effect.
- `includeLocation` — read a fix and send it as a `location` first, then the effect.

## 4. Device tools — the client's obligations

The profile's device tools are declared to the model but **the server never receives an effect for them
and cannot carry one out**. The client MUST answer every call, or the model stalls mid-turn waiting for
a function response.

| Tool            | The client must                                                                    |
| --------------- | ---------------------------------------------------------------------------------- |
| `getSaiStatus`  | Answer from its own `ActivityLog` — see §5. Never forward                          |
| `recallHistory` | Fetch `GET /v1/agents/context` and return the history. Never forward               |
| `switchMachine` | Switch machines locally, rebuild the socket, then `{result:'ok'}`                  |
| `endCall`       | End the call after the model has said goodbye, then `{result:'ok'}`                |
| `captureImage`  | Capture, hold the image locally, and report success **or the real failure reason** |

`captureImage` failure text has two parts: a plain primary reason, then a clearly marked
`(technical detail: …)` suffix. The prompt instructs the model to speak only the primary reason unless
asked. Keep that shape.

## 5. Client-rendered strings

Some strings the user hears or reads are rendered **on the client**, from an agent event. The server
holds the canonical wording, and the fixtures pin it byte for byte so this port cannot drift silently.

- **Nudges** (`ConciergeProtocol.describeAgentEvent`) — model-facing text derived from an agent event.
- **Activity lines** (`renderAgentActivity`) — for an on-screen log. Carries glyphs.
- **Spoken status** (`ActivityLog.statusText`) — what `getSaiStatus` answers. Spoken phrasing, no
  glyphs, and it deliberately reports only the past.

> **Security invariant — keep the fencing intact.** Agent-derived text (titles, summaries, errors, web
> content) is UNTRUSTED. In every nudge the instruction comes FIRST and the untrusted text is fenced
> inside `"""…"""`, so the model treats it as data. A port that drops the fence turns any web page the
> agent reads into a prompt-injection vector.

Two more client obligations the server cannot enforce:

- **Greeting.** Gate the greeting nudge to the **first** ready of a call. Reconnects and
  resume-after-pause re-run setup, and the server cannot tell those from a fresh start. (`GreetingGate`.)
- **Nudge discipline.** Never inject a nudge mid-utterance; defer until the turn completes; flush
  queued playback on a barge-in.

## 6. Supporting HTTP endpoints

All Bearer-authenticated. Optional `x-sai-version: <tag>` routes to a specific staging revision.

| Endpoint                                   | Use                                             |
| ------------------------------------------ | ----------------------------------------------- |
| `GET /v1/agents/machines`                  | The user's machines, for the picker             |
| `GET /v1/agents/context?machineId=&limit=` | Recent history — backs `recallHistory`          |
| `POST /v1/agents/upload`                   | Upload a captured image; returns the attachment |

`401` bad token · `402` out of credits · `403` machine not owned · `503` voice disabled. Treat all four
as permanent for the call.

## 7. Keeping this port honest

The two guards that already exist, and the ones to mirror rather than reinvent if this is ever ported
again:

1. **Wire protocol** — drive every message in `ws-messages.json` through the dispatcher and assert each
   reaches the right handler. An unhandled server variant must fail a test, not get silently dropped.
   (`ConciergeSocketParityTest`.)
2. **Rendered strings** — load the fixture JSON and assert byte-identical output.
   (`ConciergeProtocolParityTest`, `ActivityLogParityTest`.)

Both run in `./gradlew :app:testDebugUnitTest`. They only catch drift against the fixtures currently
committed here — refreshing those from the server is the manual step the README describes, and it is
the one thing in this contract that no test can enforce.
