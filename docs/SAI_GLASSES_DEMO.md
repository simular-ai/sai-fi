# sai-fi — Demo Playbook

Two deliverables, built on **one shared ten-beat script** (§3):

- **Track A — Live room demo** (§3–§6): the stage runbook. One continuous, unbroken call in front
  of an audience, performed at a desk, with the presenter dashboard up.
- **Track B — Promo video** (§7–§10): the _same script_, re-recorded as audio and cut over glasses
  footage of one day — a birthday cake baked between a full day's work, none of it at a computer.

**They share a script on purpose.** Every rehearsal of the live demo rehearses the voice-over; the
film can never claim something the stage can't do; and a capability change only has to be written
down once. Track A proves _it is real_, Track B shows _what it's for_, and both say the same
sentences.

**This doc does not re-list the pre-flight checks.** The demo is only as reliable as the last
on-device run, so the gate is the checklist in **`docs/TESTING_CONCIERGES.md` §6** ("Voice
concierge — on-device"), which is written to be exactly that gate. Run it end-to-end, same hardware,
same network, same day. **§11 maps every beat of the script to the §6 row that gates it** — if a row
failed, cut the beat. (Track B needs the same gate on every shoot day.)

Related docs: service behavior is `docs/VOICE_CONCIERGE.md`; client + dev setup is
`docs/SAI_GLASSES_APP.md` (§6 DAT/dev-mode facts, §7 build & run); the two-concierge model is
`docs/CONCIERGE_OVERVIEW.md`.

---

# SHARED FOUNDATIONS

## 1. Before you demo (pre-flight)

1. **Run the dry run.** `docs/TESTING_CONCIERGES.md` §6 — the whole checklist, same hardware and
   network, same day. A demo beat that hasn't passed its §6 item is a beat you shouldn't show. The
   mapping from each core-script beat to its gating check is in §11 of _this_ doc.
2. **Build & point at the right backend** (`docs/SAI_GLASSES_APP.md` §7): staging is the git-tracked
   default; to demo a specific PR's revision set `sai_version_tag=<the PR's version tag>` in
   `meta-android-app/local.properties`. Confirm Firebase sign-in config is present. Phone on the
   **staging VPN**, USB-debugging, plugged in.
3. **Hardware:** glasses charged + paired, DAT-registered, Meta AI app in Developer Mode
   (`docs/SAI_GLASSES_APP.md` §6). Remember **only one third-party DAT app can be registered at a
   time** — registering sai-fi unregisters anything else.
4. **Pick a demo machine (VM) with a known, safe task** you can trigger on cue, and — if you want to
   show approvals — one that trips a guardrail (e.g. sending a message) so the approval relay fires.
5. **Confirm the audio route** (see the ⚠ below). Sign in and reach "listening" once in
   rehearsal so the first on-stage mint is warm. The app auto-picks glasses when SCO is
   connected, otherwise phone — no toggle.
6. **Rehearse your voice, not just your lines.** Speak up and speak clearly, especially your **first**
   sentence. The client gates sub-threshold audio on purpose (`NOISE_GATE_RMS`) so room noise can't
   make the ASR invent words, and the cost of that is a mumbled or trailed-off opener being swallowed
   whole — it is the single most common reason a demo beat appears to be ignored. Say it again, louder,
   rather than waiting: it is not a hang. **The log tells you which happened** — no `you:` line means
   the mic never heard you, whereas a `you:` line with no reply means she heard it and judged it wasn't
   addressed to her. Different problems, and only the second one is about her.

### Environment reality (so nothing surprises you)

- Voice is **deliberately unbilled** in the demo (`VOICE_CONCIERGE_BILLING_ENABLED` unset) — no
  wallet gate, so no 402 to fear.
- The **cost guard** is on (`docs/VOICE_CONCIERGE.md` §7): default **5-min idle** and **60-min max**
  end a call, and `POST /session` is rate-limited. None bite a live demo — but don't leave a call
  **idle > 5 min** during Q&A or it will hang up (tap to restart). If you expect long pauses, bump
  `CONCIERGE_IDLE_TIMEOUT_MS` on the staging service beforehand.

---

## 2. Stage setup (≈60 seconds)

1. Open **sai-fi** → **Sign in with Google** (usually already signed in — the session
   persists; see `docs/SAI_GLASSES_APP.md` §5).
2. **Pick the machine** — Machines section: dropdown label shows how many were found
   (`Machine (N found)`). Use **Reload machines** if the list failed (dropdown stays disabled until
   a successful load). The picker remembers your last choice.
3. **DAT** — status is a label (`DAT: REGISTERED` persists even when glasses are off). If you still
   need to register, tap **Register glasses**; if camera was denied, tap **Grant glasses camera**.
   Live link status is the **Route:** line (glasses SCO vs phone), not DAT.
4. **Confirm the route** — read-only status: glasses if SCO is up (and the §6 audio check
   passed), otherwise phone (see ⚠ above). Pair/power the glasses before Start to land on
   glasses automatically.
5. **Start.** Once Sai greets you, you're live. **Your first line sets the
   tone — project it.** A swallowed opener reads to the room as the product not
   working, and the recovery is just saying it again more clearly.
6. **Know where Mute is.** Sai starts **unmuted**. If she talks over you or over a question from the
   audience, hit **🔇 Mute Sai** (on-screen, the temple tap, or the notification action) — she stops
   mid-word but keeps listening and working, and anything that finishes while muted is held and offered
   when you unmute. **Pause mic** is the heavier one: it drops the mic entirely so she hears nothing,
   mute is disabled while paused, and a long pause ends the call.

### Presenter dashboard (so the room can hear and read the call)

Sai's replies come out of the **glasses speaker** — only the wearer hears them, and the phone screen
is unreadable past a metre. The presenter feed mirrors the live call to a laptop: conversation text at
projector scale, logs down the side, a waveform that lights up only while someone is actually talking
(tinted by speaker), glasses photos inline, **the phone's own screen**, and **both voices played aloud
through the laptop**.

On the laptop:

```bash
npm run presenter --workspace=scripts -- --key <secret>
```

It prints the dashboard URL and the exact `local.properties` lines to paste. Then in
`meta-android-app/local.properties`:

```
presenter_url=ws://<laptop-ip>:8899   # optional — derived from concierge_url's host if that's a LAN address
presenter_key=<secret>
```

Open the printed URL and **click "▶ Start audio"** (browsers block autoplay until a gesture — do this
before you go on). It then becomes a **Pause/Resume** toggle: pausing drops incoming frames rather than
queueing them, so resuming rejoins the live call instead of replaying whatever you missed. The header
dot turns green when the phone connects; the phone's own log shows `presenter: connected`.

Notes that matter on stage:

- **DEBUG builds only.** Run ▶ from Studio. It is compiled out of release builds entirely.
- **The phone column needs no setup and no permission.** It mirrors the app's own window (PixelCopy,
  not MediaProjection), so there's no consent prompt, no cast indicator, and nothing outside the app —
  notification banners included — is ever captured. It only updates while the app is on screen, which
  is the only time there's anything to show.
- **It cannot break the call.** Every publish is fire-and-forget: if the laptop is closed, the server
  dies, or wifi drops, frames are dropped and the call continues. Restarting the server reconnects on
  its own.
- **Set `--key` on a shared network.** Unlike cloud-api, this server has no user auth — without a key,
  anyone on the network can listen to the live microphone _and watch the app's screen_. Use a real
  secret, not the placeholder from the docs.
- ⚠ **Feedback risk.** Laptop speakers playing Sai's voice can reach the glasses mic, and the platform
  AEC only cancels the phone's _own_ playback — room audio is indistinguishable from a person talking,
  so Sai can interrupt herself. Angle the speakers away from the wearer and keep the volume moderate.
  Rehearse this; it's an acoustics problem, not something fixable on the night.

---

# TRACK A — LIVE ROOM DEMO

## 3. The demo arc

Three acts, ≈9 minutes. Act I earns trust that the loop is live, **Act II is the core script** and
the reason everyone's in the room, Act III shows she's the same coworker as the one in your laptop.

**Set the demo where the audience already lives: a desk.** The hands-busy scenarios are in Track B,
where they belong — lovely on film, a liability on stage. An office worker demoing an office
worker's day needs no props, and the hands-free moments are more persuasive for being mundane.

### Act I — "This is a real, live link" (≈2 min)

Start the call and ask something small and verifiable — the time, the weather where you are, what
day it is. Nothing clever. The room watches the transcript keep pace on the dashboard and stops
wondering whether this is a recording. Say out loud that the audio link is glasses ⇄ Gemini Live
direct, which is why she can be interrupted (§4).

### Act II — an ordinary working day (≈5 min)

**Walk away from the laptop** at the top of this act and don't go back until Act III. The visual
argument is you, standing in open space, talking to the room, while real work gets done. Then run
the core script below, unbroken.

### The core script (shared with the film — §8)

**This exact sequence is the spine of both deliverables.** On stage you perform it live at a desk;
in the film the same lines are re-recorded as audio and cut over glasses footage (§8). One script
means every rehearsal of the live demo is also a rehearsal of the voice-over, and the film can never
promise something the stage can't do.

Ten beats, ≈5 minutes live. Phrases are examples — say them naturally; the Live model classifies
intent. The right-hand column is the `TESTING_CONCIERGES.md` §6 row that gates the beat (full map
in §11) — **if that row didn't pass this morning, cut the beat.**

| #   | You say                                                                                                                                                                            | Sai does                                                                                                                                                             | Capability shown                                                                             | §6 gate        |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | -------------- |
| 1   | "Check my unread emails and Slack messages."                                                                                                                                       | One short ack, then silence while she works, then a digest — counts first, then only what matters.                                                                   | Multi-source read; **update discipline** — no periodic filler while she works.               | 9, 10          |
| 2   | _(cutting in while she's still reading it back)_ "Also — book a table for tonight, my mum's birthday, at ⟨restaurant⟩."                                                            | Takes the second task without dropping the first, then **asks how many people**.                                                                                     | Barge-in + multitasking + clarify-once on a missing parameter.                               | 34, 47         |
| 3   | "Six."                                                                                                                                                                             | Books it and confirms — restaurant, time, party size.                                                                                                                | Parameter filled → action completed → **completion honesty** (she claims only what she did). | 14, 47         |
| 4   | "Draft a reply to Ang on Slack about the pull request I opened."                                                                                                                   | Pulls the actual PR as context and **reads the draft aloud**.                                                                                                        | Drafting grounded in real work context, surfaced for review before anything leaves.          | 47             |
| 5   | "Yeah, send it."                                                                                                                                                                   | **Asks for confirmation before sending** — repeats who it's going to and what it says in one line — and only sends on your yes.                                      | The trust beat: nothing irreversible happens without an explicit confirm.                    | 12, 47         |
| 6   | "Yes, send it." → then "What's on my calendar tomorrow?"                                                                                                                           | Sends, confirms it's sent; then reads tomorrow back.                                                                                                                 | Approval honoured; plain retrieval as a breather between the busy beats.                     | 14, 21         |
| 7   | _(barge in over her answer)_ "And what's the weather going to be like?"                                                                                                            | Stops cleanly mid-sentence, takes the new question, and **answers for where you actually are** — no "which location?", because the phone told her.                   | Full-duplex barge-in + the location beat: she knows where you are without being told.        | 34, 47, 58     |
| 8   | **Mute Sai** and turn to a colleague: "…we should get everyone together tomorrow to go through the migration — say eleven?" _(unmute)_ "Sai, you got that? Put it in my calendar." | Stays silent through the muted stretch — **she is still listening, her output is muted** — then creates the meeting from what was said: right day, time and subject. | Mute is output-only; context survives it. Natural-language event creation.                   | **31**, 8      |
| 9   | 👁️ _(hold up an unlabelled object)_ "Order me another one of this."                                                                                                                | A shutter beat, no dead air, then: identifies it, finds it, gives a price, waits. → "Yes." → orders, confirms.                                                       | Camera → identification → commerce, gated by approval.                                       | 19, 23, 26, 30 |
| 10  | "What have you helped me do today?" → then **"Thanks Sai, you can hang up now."**                                                                                                  | Recaps the session — the digest, the booking, the Slack reply, the meeting, the order — then ends the call cleanly and releases the mic.                             | `recallHistory`; clean `endCall`.                                                            | 13, 16, 17     |

> **Beat 10 is doing double duty.** The recall query makes Sai narrate everything the audience just
> watched, in her own voice, as evidence — a better closer than any summary slide. Then hang up _out
> loud_ so the room sees the call end on a spoken instruction rather than a tap.

#### Notes on the two beats that need care

**Beat 5 — say the quiet part.** When she asks before sending, tell the room that's deliberate and
configurable, not a limitation. Reading a draft aloud and waiting is the difference between an
assistant people let near Slack and one they don't. This is the beat enterprise buyers remember.

**Beat 8 — mute means output-muted, still listening.** That's the design: she can't talk over your
side conversation, but she keeps context so "you got that?" works. Say this plainly and unprompted
when you mute — volunteering it reads as confidence, and someone will ask otherwise. Retention and
handling are in `docs/VOICE_CONCIERGE.md` §6 if pressed. ⚠️ §6 rows **31–33 are marked _not yet
tested on device_** — this beat carries the most risk in the script. Dry-run it hard, and have the
explicit fallback ready: turn back and say _"Sai — put a meeting in for eleven tomorrow about the
migration."_ Loses a little magic, costs nothing in credibility.

**Beat 9 — choosing the object.** It must be _unreadable_ from row five (identified by shape, not
logo), plausibly reorderable, and obviously not planted.

- **Default: a whiteboard marker.** Already on stage, clearly a consumable, strong silhouette. Hold
  it during beat 8 so picking it up isn't a separate move.
- **Best if the room's warm: borrow one from the audience.** Unanswerable as a setup, and the room
  feels the risk. Only if you've rehearsed with 5–6 random objects; keep the marker as backup.
- **Other safe picks:** coffee bag (label turned) · cable or adapter · plug/power strip · batteries ·
  sticky notes. Cables are the best _graceful failure_ — the category answer still impresses if the
  exact SKU is off.
- **Avoid:** big legible branding · clothing or shoes (sizing turns it into a long clarify) · small,
  dark or shiny things (both cameras struggle) · anything obviously carried in for the demo ·
  anything personal or medical.
- **Dry-run with the actual object**, not a stand-in — capture latency and identification confidence
  vary a lot by object, and the failure mode is her confidently naming the wrong thing (§6 rows 19,
  23, 26, 30).
- Beat 9 is live-only now (cut from the film, §8), so the audience-object risk costs you nothing on
  camera.

### Act III — "Same coworker, everywhere" (≈2 min)

Before beat 10's hang-up — or as a coda after it, restarting the call — hand a task to the desktop
agent: _"take this over on my laptop."_ Walk back and let the room watch it continue on screen.
Landing here, straight after an act spent away from the machine, is the whole point: same assistant,
same context, different surface. (Gated by §6 row 15.)

### If the room is a specific industry

Don't restage anything. Run the office script as written and **play the matching vignette from
Track B** (§9) as a thirty-second clip either side. A finished film of a bakery at 5am beats you
miming one.

---

## 4. Talking points (the "why it's built this way")

- **Two independent links, one call.** The **audio link** is client ⇄ Gemini Live _directly_ —
  cloud-api never sees the PCM, so latency/barge-in stay native. The **agent link** is client ⇄
  cloud-api WS — the trust boundary (auth, machine ownership, billing). They meet only as _effects_
  (up) and _agent-events_ (down). (`docs/VOICE_CONCIERGE.md` §3.)
- **The app is thin.** No STT/TTS/VAD/chat-parsing on-device — Gemini Live owns audio, the concierge
  owns the agent. Model/voice/prompt/tools are all server config; a preview-model swap is a server
  change, not an app release.
- **Update discipline over chattiness.** One ack, then quiet until asked or done. This is a product
  stance, enforced server-side: there is no mechanism that can fill a quiet stretch.
- **Credentials never go over voice.** Link-only approvals (logins, connecting an account) are
  structurally excluded from voice resolution — she sends you to the app. Good security beat.
- **Privacy, said plainly.** The glasses mic streams to Google; bystander audio leaves the device
  even though tuned VAD keeps bystanders from triggering Sai. (Tap-to-talk used to narrow this and has been removed; **mute silences her voice but keeps the mic
  open**, so it is not a privacy control — Pause is.) (Open items
  in `docs/VOICE_CONCIERGE.md` §6 "Privacy & data handling" — be honest if asked.)
- **Cost is bounded.** A live session bills by duration; idle/max guards + a mint rate limit cap it
  (`docs/VOICE_CONCIERGE.md` §7).

---

## 5. If something goes wrong (on-stage recovery)

- **Brief silence / a blip:** the WS auto-reconnects with backoff and the Live session re-mints —
  usually self-heals in a second or two. Keep talking; don't hammer Start.
- **"Voice isn't available" / "out of credits" / "access denied":** a permanent failure (503 / 402 /
  401·403). She says the reason and stops retrying (also a notification). Not recoverable on stage —
  check the backend/version tag; this is why the same-day dry run matters.
- **Route lost (glasses walked out of range):** audio falls back to the **phone** without dropping
  the call — just keep going on the phone route.
- **The call ended the moment you folded the glasses / put them down / Bluetooth dropped:** expected,
  and unavoidable. DAT reports folding, doffing, going out of range and a temple press-and-hold as one
  indistinguishable "session stopped", and the call ends with it. The status and a notification now say
  so ("Glasses folded, removed, or out of range — call ended"); just tap Start again. **On stage: don't
  take the glasses off between beats.**
- **Call hung up unexpectedly after a quiet stretch:** that's the **idle cost guard** (default
  5 min). Tap to start again; bump `CONCIERGE_IDLE_TIMEOUT_MS` next time.
- **Gestures do nothing:** expected if the capability-less-session tap check hasn't passed — use the
  in-app buttons / voice (see §1 ⚠).

---

## 6. After the demo

- End with "thanks Sai, you can hang up now" (beat 10) so `endCall` is part of the show, or Stop from the notification.
- The foreground service tears down; the mic is released.
- If you registered sai-fi for the demo and normally use another DAT app, **re-register that
  app** afterward (single-app registration limit, `docs/SAI_GLASSES_APP.md` §6).

---

# TRACK B — PROMO VIDEO

## 7. What the film is

A cut film, not a demo capture — and **not a different script**. The audio is the ten beats of §3,
re-recorded clean and trimmed; the picture is glasses footage of one day. Because it's edited it can
do the two things the stage cannot: leave the building, and only ever show the moment that lands.

**Premise:** _It's my mum's birthday. I want to bake her a cake — and I still have a day's work to
get through._ Sai is an autonomous computer, so the work happens without me being at one.

**Non-negotiable rule — nothing fake.** Every response in the cut must be one Sai actually gave, and
every UI frame a real frame. Editing may _remove_ dead time; it may not _invent_ an answer that
didn't happen. Latency is trimmed, never faked to zero. If a capability isn't shipping, it isn't in
the film — and if its §6 row (§11) doesn't pass on shoot day, it isn't in the film either. This
matters more here than on stage: a film is permanent and gets watched by people who'll hold you to
it.

**Deliverables.** Only the hero film is required — it's the thing the story was written for.
The rest are optional cuts of the same footage, worth doing if there's edit time and worth dropping
without regret if there isn't.

| Cut                                        | Length       | Use                                                                                                 |
| ------------------------------------------ | ------------ | --------------------------------------------------------------------------------------------------- |
| **Hero film** _(required)_                 | ≈94 s        | Site header, launch post, keynote opener                                                            |
| **Short** _(optional)_                     | 30 s         | Paid social, pre-roll                                                                               |
| **"Flour hands"** _(optional)_             | 15 s         | Scene 2 alone — the single strongest argument in the film                                           |
| **Vignettes** _(optional, separate shoot)_ | 12–20 s each | §9 — a drip campaign, the b-roll bank, and the industry clips Track A plays alongside the live demo |

---

## 8. Hero film — the same script, one day

**The film's audio is the live demo's script.** Re-record the beats of §3 clean — your lines and
Sai's real responses — trim the latency, and lay them over glasses footage. The viewer hears an
ordinary working day; they see someone who never sits down at a computer.

**The film uses eight of the ten beats.** Two are cut:

- **Beat 7** (barge-in → weather, answered for where you're standing) — a capability proof on stage,
  but on film it's the one beat that doesn't move the story, and the day already has enough
  happening.
- **Beat 9** (👁️ "order another one of this") — the strongest line in the live demo, and wrong
  here. Stopping to buy something while walking to dinner is an errand, in a film whose whole
  argument is that the errands are already done. It fights the ending.

Neither capability vanishes from the footage: barge-in still shows in beat 2 (you cut in over her
mid-answer), and the camera still shows in the chocolate insert. Keep both beats in the live demo —
just don't shoot them.

### The story

**It's my mum's birthday. I want to bake her a cake before dinner — and I still have a day's work to
get through.**

The work doesn't get skipped and it doesn't get done at a desk: the inbox is triaged in the
supermarket, the table is booked between aisles, the Slack reply to Ang is written and sent with
flour on both hands. The cake comes out, gets iced — and because the whole day's work went with it,
there's an hour spare before dinner. That hour is the point of the film: it goes on a workout, not
on catching up.

**The line the film is arguing:** _Sai is an autonomous computer, so you don't have to be at one._
Not "productivity" — **time given back**, and spent on the cake, on the wall, and at the table. Say
it once at the end, or not at all. The pictures make the argument better than a voice-over can.

### Scene → beat map

Five scenes in story order across one day. The conversation runs continuously underneath while the
picture moves — that continuity is the trick, and it's also literally true, because it's one call.

| Scene                              | Picture                                                                                                                                                                  | Beats                                                                                                                                             | Sec   | Why here                                                                                                                                                                                                                                              |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- | ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1. Grocery run**                 | Morning. POV down the aisles, then both arms full of bags. Cake ingredients, candles, flowers into the basket.                                                           | **1** (inbox + Slack digest) · **2–3** (book the table, "six")                                                                                    | 0–22  | The day's work starts somewhere that isn't an office. The shopping is _for the cake_ — story and demo in one shot.                                                                                                                                    |
| **— insert —**                     | 👁️ In the aisle, two brands of chocolate held up.                                                                                                                        | **Extra beat G:** "Ask my sister which of these Mum likes." → she messages, and **the reply comes back in your ear** a few shots later, mid-shop. | 22–30 | The only round-trip in the film: sent _and_ answered hands-free. Quietly establishes the family around this cake.                                                                                                                                     |
| **2. Baking at home**              | Weighing, mixing, hands in the bowl, tin into the oven. Flour everywhere. Then the cake out, cooling, iced, finished, set aside.                                         | **4** (draft the reply to Ang) · **5** (she asks to confirm) · **6** (send it, then tomorrow's calendar)                                          | 30–56 | **The heart of the film.** The most consequential work beat happens at the least office-like moment — hands visibly, unarguably occupied. Hold on the hands during "yeah, send it." The scene ends with the day's work _and_ the cake both done.      |
| **3. Bouldering with a colleague** | Late afternoon, the spare hour. A gym or a boulder field, climbing with someone from work. Chalky hands, mid-problem, then both of you sitting on the mat.               | **8** (mute → they raise the migration meeting → unmute → "Sai, you got that?")                                                                   | 56–74 | The hour the cake didn't cost you. And the meeting comes up the way it actually does — a colleague mentions it between attempts, so muting and then catching it is completely natural rather than staged.                                             |
| **4. Walking to dinner**           | Golden hour. Somewhere unmistakably beautiful — waterfront, old town, a skyline — walking toward the restaurant. Dressed up, cake box in one hand, flowers in the other. | **10** (the recap → "thanks Sai, you can hang up now")                                                                                            | 74–90 | The recap plays over the best-looking footage in the film, uninterrupted. Everything she lists happened while you were doing _all of this_. Both hands are full walking to your mum's birthday dinner — the closing image of what the day bought you. |
| **5. Close**                       | The restaurant door, or cut to black.                                                                                                                                    | —                                                                                                                                                 | 90–94 | `Sai` · `sai.com`                                                                                                                                                                                                                                     |

### Continuity rules

- **One voice take, four places.** Record the whole conversation as a single unbroken session, then
  cut picture against it. Never record beat-by-beat — the joins will sound assembled and the timing
  will drift.
- **Ambient sound cuts with the picture** even though the dialogue runs through: supermarket hum → a
  kitchen → chalk and breathing → street and gulls. That contrast tells the viewer time is passing
  while the conversation isn't restarting.
- **The cake is the clock, and the workout is the payoff.** Ingredients bought (1), baked and iced
  (2), _free hour_ (3), carried to dinner (4). The audience tracks the whole day without a single
  time-stamp super — and feels the gap in the middle as earned.
- **The booking pays off.** Table booked in scene 1 is the restaurant you're walking to in scene 4.
  Nobody consciously notices; everybody feels the film hangs together.
- **Nothing fake.** Every Sai response in the cut must be one she actually gave. Trim dead air; never
  assemble an exchange that didn't happen, and never cut latency to literal zero — leave a
  believable half-beat.
- **Never show a phone in use.** One stowed phone is allowed (back pocket, bag).
- **Subtitles on every line, both sides**, styled differently for you vs. Sai. Assume muted autoplay.

### Shorter cuts from the same shoot _(optional)_

**The hero film is the only required deliverable.** Everything below is a nice-to-have pulled from
footage you'll already have — cut them if the edit budget or the schedule is tight, and don't let
them shape the shoot.

| Cut               | Contents                                                                  | Length       |
| ----------------- | ------------------------------------------------------------------------- | ------------ |
| **Short**         | Scene 1 + the chocolate insert + scene 2 + the last shot of scene 4       | 30 s         |
| **"Flour hands"** | Scene 2 entire — the strongest single argument in the film                | 15 s         |
| **Vignettes**     | §9 — _optional, separate shoot_; the drip campaign and industry clip bank | 12–20 s each |

---

## 9. Vignette library _(optional — separate shoot)_

**This is not part of the hero film shoot and nothing in Track A or §8 depends on it.** Treat it as
a later, cheaper second unit: a drip campaign, a b-roll bank for sales decks, and the industry clips
the live demo can play alongside itself (§3). Valuable, but skip it entirely until the hero film is
cut and out.

Each is a self-contained 12–20 s film with the same grammar: **hands busy → one spoken line → Sai
does a real thing → a beat of the hands still busy.** Shoot every one even if the hero film uses
only a slice; they are the drip campaign and the industry clip bank.

| #   | Scenario                        | The line                                                           | What Sai visibly does                      | Location / props                       | Cam |
| --- | ------------------------------- | ------------------------------------------------------------------ | ------------------------------------------ | -------------------------------------- | --- |
| V1  | **Generic — hero**              | "Order me another one of this."                                    | Identifies the held object, price, confirm | Anywhere, any object                   | 👁️  |
| V2  | **Generic — recall**            | "What was that thing I said this morning about the invoice?"       | Reads it back                              | Desk, coffee                           | —   |
| V3  | **Generic — 👁️ read**           | "What does this say?" (label / form / foreign menu)                | Reads and explains it                      | Café                                   | 👁️  |
| V4  | **Home cooking**                | "Scale this for six and start the timers."                         | Scaled amounts + two named timers          | Home kitchen                           | —   |
| V5  | **Home cooking 👁️**             | "Does this look done?"                                             | Verdict with a reason                      | Home kitchen                           | 👁️  |
| V6  | **Home cooking — restock**      | "We're out of stock — add it to the list and reorder."             | List updated, order placed after a confirm | Open cupboard                          | —   |
| V7  | **Commercial baking**           | "We're short on butter — reorder from the supplier."               | Prep list updated, supplier order placed   | Bakery, 5am, trays                     | —   |
| V8  | **Commercial baking 👁️**        | "Check this invoice against what we ordered."                      | Reconciles, flags the discrepancy          | Loading door                           | 👁️  |
| V9  | **Commercial baking — costing** | "Butter's up eleven percent — update the costing sheet."           | Sheet updated, new margin read back        | Office nook off the kitchen            | —   |
| V10 | **Pottery**                     | "Log the glaze — cone six, twelve hours."                          | Written to the studio notebook             | Wheel, wet hands                       | —   |
| V11 | **Pottery 👁️**                  | "Catalog this one and put it on the shop."                         | Photo, dimensions, listing drafted         | Shelf of finished work                 | 👁️  |
| V12 | **Painting**                    | "Catalog this and schedule the post for Friday."                   | Title + description drafted, scheduled     | Studio, easel                          | 👁️  |
| V13 | **Painting — restock**          | "I'm nearly out of white — reorder it."                            | Reorder, confirmed                         | Bench of paint tubes                   | —   |
| V14 | **Climbing**                    | "Log the send — 6C, second go." / "Share my location with Mia."    | Logged; location shared                    | Boulder field, chalky hands            | —   |
| V15 | **Kayak / paddleboard**         | "What's the wind doing?"                                           | Wind/tide read aloud                       | Flat water, phone visibly in a dry bag | —   |
| V16 | **Trail running**               | "Add that to my list — call the landlord."                         | Captured to notes, run logged              | Trail, breathing hard                  | —   |
| V17 | **Dog walking**                 | "Book the vet for Thursday and reorder his food."                  | Both done, confirmed                       | Park, lead in hand                     | —   |
| V18 | **Personal training**           | "Client's on set four of five."                                    | Logged to the client's record              | Gym floor, spotting                    | —   |
| V19 | **Guitar / piano**              | "Loop bars nine to sixteen at eighty."                             | Metronome/loop changes audibly             | Practice room                          | —   |
| V20 | **Airport**                     | "I've missed it — get me on the next one."                         | Rebooked, checked in, pickup messaged      | Concourse, walking                     | —   |
| V21 | **Grocery**                     | "Which of these is better value?"                                  | Unit-price comparison                      | Supermarket aisle, two boxes           | 👁️  |
| V22 | **Childcare**                   | "Reorder the formula and add the six-month check to the calendar." | Both done                                  | Infant in one arm                      | —   |

**Casting the strongest six** for a launch drip, in order: V1, V20, V22, V14, V8, V11. They cover
the four distinct emotional registers — _effortless_ (V1), _rescued_ (V20), _outnumbered_ (V22),
_out there_ (V14) — plus the two that prove real work gets done (V8, V11).

---

## 10. Production notes

**Capture.** Every scenario needs three passes: (1) **POV** through the glasses for the 👁️ beats,
(2) **third-person wide** so the audience sees hands genuinely occupied, (3) **a tight cutaway** of
the hands. Record Sai's audio clean off the app, not off the room mic, and re-sync in the edit —
room audio of glasses playback sounds thin and undersells her.

**Get the hands right.** The whole premise dies if the talent could plausibly have reached a phone.
Flour, gloves, clay, chalk, a lead in one hand and a bag in the other, an infant on the hip. Wet or
dirty hands read best on camera.

**Never show a phone in use.** A phone may appear once, visibly _stowed_ — in the dry bag (V15), in
a back pocket (V20). That's the argument, made visually.

**Faces and consent.** Public locations (airport, supermarket, park) need releases or a permit; the
supermarket vignette is often refused — have a corner-shop fallback.

**Subtitles.** Assume muted autoplay. Every spoken line is burned in, both sides, styled differently
for you vs. Sai.

**Legal/claims pass before publishing.** Anything on screen that looks like a purchase, a booking or
a message being sent must match what actually shipped and must show the approval step. See
`docs/VOICE_CONCIERGE.md` §6 for what can be said about recording and data handling — the same
honesty rules as the stage demo apply, and a film is permanent.

---

# REFERENCE

## 11. Demo beat → gating check (the confidence map)

Each beat of the core script (§3) is safe to show **only if its `TESTING_CONCIERGES.md` §6 row
passed the same-day dry run** on the exact device, glasses and network you'll demo on. If a row
failed, cut the beat — don't hope.

| Core-script beat                               | Gating §6 row(s)                                                                                                                          | What the row proves                                                                                            |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| **1** Inbox + Slack digest                     | **9** Update discipline · **10** No plumbing narration                                                                                    | One ack then silence while she works; no periodic filler, no narrating tool calls                              |
| **2** Cut in with the booking → "how many?"    | **34** Clarify-once · **47** Manual E2E voice arc                                                                                         | She takes a second task mid-answer and asks for the missing parameter exactly once                             |
| **3** "Six" → booked                           | **14** Completion honesty · **47**                                                                                                        | She claims only what actually completed                                                                        |
| **4** Draft the Slack reply to Ang             | **47** Manual E2E voice arc                                                                                                               | Task round-trip with real context, surfaced for review                                                         |
| **5** She asks to confirm before sending       | **12** Ask-before-update · **47** (approval + always-allow)                                                                               | Nothing irreversible without an explicit yes                                                                   |
| **6** Sent → tomorrow's calendar               | **14** Completion honesty · **21**                                                                                                        | Confirms the send truthfully; plain retrieval                                                                  |
| **7** Barge in → weather, for where you are    | **34** Barge-in · **58–60** Phone location · **47**                                                                                       | Clean interruption mid-sentence, then the right city without being asked — and never a guessed one             |
| **8** Mute, side conversation, "you got that?" | **31–33** Mute behaviour ⚠️ · **8** Ambient + no phantom words                                                                            | Output muted while still listening; side talk neither triggers her nor is lost                                 |
| **9** 👁️ "Order another one of this"           | **19** Shutter beat · **23** · **26** · **30**                                                                                            | Capture works, no dead air, no claim to see what she can't, approval before purchase                           |
| **10** Recap → "you can hang up now"           | **13** `recallHistory` · **16** `endCall` over in-flight work · **16b** Overheard farewell doesn't hang up · **17** Goodbye isn't clipped | She recalls the session, ends cleanly, releases the mic — and didn't hang up during beat 8's side conversation |
| **Act III** machine handoff                    | **15** Machine switch, both paths                                                                                                         | The task continues on the desktop                                                                              |
| **Presenter dashboard** (throughout)           | **43** App-window mirror ⚠️ · **44** Audio + waveform · **45** No feedback loop                                                           | The room can see and hear the call without the dashboard re-triggering the glasses mic                         |
| **Whole-call stability**                       | **46** 30-min screen-off soak · **1** Right backend · **2** DAT registration                                                              | The call survives the length of the demo, on the build you think you're demoing                                |

### ⚠️ Two rows carry known risk

- **Rows 31–33 (mute) are marked _not yet tested on device_.** Beat 8 is the single riskiest beat in
  the script. Dry-run it repeatedly; if it's shaky, use the explicit fallback in §3 ("Sai — put a
  meeting in for eleven tomorrow about the migration") and lose nothing but a flourish.
- **Row 43 (app-window mirror) is _not yet tested_.** If the mirror misbehaves, fall back to the
  audio + waveform view (row 44), which is the part the room actually needs.

### Also worth knowing before you walk on

- **Row 16b matters more than it looks.** Beat 8 has you holding a conversation with someone else in
  front of her, and beat 10 ends the call by voice. Row 16b is what stops the first from triggering
  the second mid-demo.
- **Row 45 is a room-acoustics check, not a code check.** It only means anything if you run it at
  demo volume and demo distance, in the actual room.
- **Don't leave the call idle > 5 min** during Q&A or it hangs up (see §1). If you expect a long Q&A,
  bump `CONCIERGE_IDLE_TIMEOUT_MS` beforehand.

The deterministic CI goldens, the behavioral eval and the cross-port parity fixtures behind these
on-device checks are in `docs/TESTING_CONCIERGES.md` §1–5 and `docs/SAI_GLASSES_APP.md` §8.
