/*
 * sai-fi — voice concierge.
 */

// GeminiLiveClient — raw-WebSocket client for the Gemini Live API (BidiGenerateContent).
//
// The concierge server mints an ephemeral token (SessionBootstrap.token) that we use against the
// v1alpha Live endpoint. We implement the wire protocol directly with OkHttp (the docs cover the
// Python/JS SDKs + a raw-WS guide + an official ephemeral-token WS example). This is the client-side,
// low-latency Live session: mic PCM16 up, model audio down, native VAD/turn-taking/barge-in. The
// model's function-calls are relayed to the concierge WS as effects (see handleToolCall).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class GeminiLiveClient(
    /** 24 kHz PCM16 audio to play (model speaking). */
    private val onAudio: (ByteArray) -> Unit,
    /** User barged in — flush any queued playback immediately. */
    private val onInterrupted: () -> Unit,
    /** A transcription delta for the current turn (role = "you" | "sai"); coalesce per turn. */
    private val onTranscript: (role: String, delta: String) -> Unit,
    /** The model's turn ended — finalize the current transcript lines. */
    private val onTurnComplete: () -> Unit,
    /** The model's function-calls (fcToEffect'd) to forward to the concierge — never getSaiStatus. */
    private val onEffects: (JSONArray) -> Unit,
    /** Answer the local getSaiStatus tool (activity buffer); never forwarded as an effect. */
    private val onGetSaiStatus: () -> String,
    /**
     * Answer the local recallHistory tool: fetch recent machine history (GET /v1/agents/context) and
     * call [respond] with a compact transcript. Async — the tool response is deferred until it lands.
     */
    private val onRecallHistory: (respond: (String) -> Unit) -> Unit,
    /** Handle the local switchMachine tool: switch the concierge to [name]; return a spoken result. */
    private val onSwitchMachine: (name: String) -> String,
    /**
     * Handle the local endCall tool: the user is done — end the call (client-side).
     *
     * [spokeThisTurn] says whether Sai produced any speech in the turn that called it. The prompt
     * requires a spoken goodbye BEFORE endCall, so a hang-up out of a silent turn is evidence that
     * something other than a farewell got it here — see CallService's hang-up guard.
     */
    private val onEndCall: (spokeThisTurn: Boolean) -> Unit,
    /**
     * Handle the local captureImage tool: capture a glasses photo + upload it, then call [respond] with
     * a short result ("captured" / an error). Async — the tool response is deferred until it completes,
     * and the attachment is sent to the server before [respond] so it's stashed before the next forward.
     */
    private val onCaptureImage: (respond: (ok: Boolean, message: String) -> Unit) -> Unit,
    /** Cumulative Live token usage (from usageMetadata) to report to the server for billing. */
    private val onUsage: (promptTokens: Int, responseTokens: Int, totalTokens: Int) -> Unit,
    /**
     * A task that ASKS FOR the in-flight photo has been held for it, so the photo is already spoken
     * for. Lets the UI say "sending" instead of "held" during the seconds the capture still needs —
     * the window in which the phone read "not sent" while the task carrying it was already queued.
     */
    private val onPhotoDestined: () -> Unit,
    /** Human-readable status/event lines for the UI (not transcripts). */
    private val onLog: (String) -> Unit,
    /**
     * The Live session finished setup and is ready to talk (setupComplete). Fires on EVERY connect —
     * initial start, a mid-call reconnect (token expiry / network blip), and resume-after-pause — so
     * anything that must happen only once per call has to gate itself (see CallService's greeting).
     */
    private val onReady: () -> Unit,
    /** Socket closed or failed — the caller tears down the call or reconnects. */
    private val onClosed: () -> Unit,
) {
  // Nudge gating: server-pushed nudges make the model talk; firing one mid-utterance cuts it off, so
  // defer until the turn ends (or drop it, for a low-value nudge the caller marks dropIfBusy).
  @Volatile private var modelSpeaking = false
  // A glasses capture is running. Task-starting effects are held for its duration — not just within
  // the batch that triggered it, since the model can now speak first and forward a beat later.
  @Volatile private var captureInFlight = false
  // Whether the CURRENT capture's outcome has already been relayed to the model (see the coalescing
  // note in handleToolCall — several tool calls can share one capture and one result).
  private val outcomeNudged = java.util.concurrent.atomic.AtomicBoolean(false)
  // Did this turn hear the user, and did Sai answer? A turn with the first and not the second is Sai
  // correctly ignoring speech that wasn't for it — worth a log line, since silence otherwise reads
  // as a fault. Reset at each turn boundary.
  @Volatile private var heardUserSinceLastTurn = false
  @Volatile private var spokeThisTurn = false
  /**
   * Sai's transcript for the CURRENT turn, and whatever of it we are withholding.
   *
   * A turn whose entire text is a mechanical placeholder ("Empty-Response", "No response received.")
   * is not speech — see [isPlaceholderSpeech]. Deltas arrive in fragments, so the test has to run
   * against the accumulated turn rather than each delta: `Empty-` on its own matches nothing. What is
   * withheld is kept, so a turn that STARTS placeholder-shaped and then turns into real speech is
   * released in full instead of losing its opening words.
   *
   * Plain @Volatile Strings rather than StringBuilders: deltas are appended on the Live reader thread
   * while `connect()` clears them from the main thread, and an immutable value swapped atomically can't
   * be read half-written. Deltas are a few words, so the copying is irrelevant.
   */
  @Volatile private var saiTurn = ""
  @Volatile private var withheld = ""
  /** A nudge in this turn explicitly asked Sai not to speak, so silence here is instructed, not judged. */
  @Volatile private var silenceWasRequested = false
  private val heldTaskEffects = mutableListOf<JSONObject>()
  private val heldTaskNames = mutableListOf<String>()
  // Set on barge-in: ignore audio of the interrupted turn that is still arriving (see handleServerContent).
  @Volatile private var discardAudioUntil = 0L
  // Deferred nudges keep their kind alongside the body, so the log can name what was held.
  private val deferredNudges = mutableListOf<Pair<String, String>>()
  // Nudges injected before setupComplete, replayed the moment it lands. Distinct from
  // deferredNudges (which waits on a TURN ending, not on the session existing): a call can be muted
  // in the second before it connects, and that state has to survive the wait.
  private val preConnectNudges = mutableListOf<Pair<String, String>>()
  /**
   * A nudge describing session-level STATE rather than an event — mute is the only one. Every fresh
   * Live session (initial connect, token-expiry reconnect, resume-after-pause) starts knowing nothing,
   * so this is re-asserted at each setupComplete and needs no pre-connect buffering: being told at
   * setup IS the delivery. Null when there is no such state to carry.
   */
  @Volatile private var sessionState: Pair<String, String>? = null
  // setupComplete has landed on the CURRENT socket, so a client turn is deliverable. Cleared on
  // connect() and close(): a nudge injected between them would be sent ahead of the setup frame.
  @Volatile private var ready = false

  private val client =
      OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null

  fun connect(boot: SessionBootstrap) {
    // Ephemeral tokens (the `auth_tokens/…` value from authTokens.create) use the *Constrained*
    // method + the `access_token` param — NOT `BidiGenerateContent?key=` (that's for a real API key,
    // and passing an ephemeral token there fails with 1007 "api key not valid"). This mirrors how
    // @google/genai builds the Live WS URL for a token starting with `auth_tokens/`.
    val url =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained" +
            "?access_token=${boot.token}"
    // Fresh session ⇒ fresh turn state (also correct on a reconnect: the old turn is gone).
    modelSpeaking = false
    ready = false
    discardAudioUntil = 0L
    saiTurn = ""
    withheld = ""
    synchronized(deferredNudges) {
      // Say what is being thrown away. A nudge held for a turn that never ended (a barge-in, then a
      // token-expiry reconnect) died here without a trace, which is one candidate cause for a
      // completion the user never heard.
      if (deferredNudges.isNotEmpty()) {
        onLog("✗ nudge: dropping ${deferredNudges.joinToString(", ") { it.first }} — session replaced")
      }
      deferredNudges.clear()
    }
    // preConnectNudges deliberately SURVIVES a reconnect: it holds session-level state (mute) that a
    // fresh Live session needs re-asserted anyway, and the reconnect is exactly when it's re-injected.
    runCatching { ws?.cancel() } // drop any prior socket before replacing it (reconnect path)
    ws = client.newWebSocket(Request.Builder().url(url).build(), Listener(boot))
  }

  /** Send a frame of mic PCM16 (16 kHz mono, little-endian) as realtime input. */
  fun sendAudio(pcm: ByteArray) {
    val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
    val msg =
        JSONObject()
            .put(
                "realtimeInput",
                JSONObject()
                    .put(
                        "audio",
                        JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000"),
                    ),
            )
    ws?.send(msg.toString())
  }

  /**
   * Send a typed user turn as text (testing without a mic). Same path as speech — the model treats it
   * as a complete user turn and replies; if it arrives mid-reply it barges in.
   */
  fun sendText(text: String) = sendClientTurn(text)

  /** Send a client-content turn (a user/text turn) and mark it complete. Used by text + nudges. */
  private fun sendClientTurn(text: String) {
    val msg =
        JSONObject()
            .put(
                "clientContent",
                JSONObject()
                    .put(
                        "turns",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put(
                                        "parts",
                                        JSONArray().put(JSONObject().put("text", text)),
                                    ),
                            ),
                    )
                    .put("turnComplete", true),
            )
    ws?.send(msg.toString())
  }

  fun close() {
    ready = false
    ws?.close(1000, null)
    ws = null
  }

  private inner class Listener(private val boot: SessionBootstrap) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      onLog("live: socket open — sending setup")
      webSocket.send(buildSetup(boot).toString())
    }

    // Live sends JSON in text OR binary frames depending on transport; handle both.
    override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
        handle(webSocket, bytes.utf8())

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      Log.e(TAG, "live socket failure (${response?.code})", t)
      onLog("live: FAILED ${response?.code ?: ""} ${t.message}")
      onClosed()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      onLog("live: closed $code $reason")
      onClosed()
    }
  }

  private fun handle(webSocket: WebSocket, raw: String) {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
    // usageMetadata can ride along any server message; report cumulative totals for billing.
    json.optJSONObject("usageMetadata")?.let { u ->
      onUsage(
          u.optInt("promptTokenCount"),
          u.optInt("responseTokenCount"),
          u.optInt("totalTokenCount"),
      )
    }
    when {
      json.has("setupComplete") -> {
        ready = true // client turns are deliverable from here (see injectNudge)
        onLog("live: setup complete — start talking")
        // BEFORE onReady: the greeting is injected from there, and a mute asserted while connecting
        // has to reach the model first — otherwise Sai is told to greet, then told to be silent, and
        // obeys the last thing it read. State first, then anything that was waiting on the session.
        sessionState?.let { injectNudge("${it.first} (re-asserted for this session)", it.second) }
        flushPreConnectNudges()
        onReady()
      }
      json.has("serverContent") -> handleServerContent(json.getJSONObject("serverContent"))
      json.has("toolCall") -> handleToolCall(webSocket, json.getJSONObject("toolCall"))
      // goAway / sessionResumptionUpdate etc. ignored — the call self-heals via onClosed → reconnect.
    }
  }

  private fun handleServerContent(sc: JSONObject) {
    if (sc.optBoolean("interrupted", false)) {
      modelSpeaking = false
      // Flushing the track only empties what's already queued. Audio chunks of the interrupted turn
      // that were ALREADY in flight keep arriving in the next few messages, get written, and refill
      // it — so Sai talked straight through the barge-in even though the interrupt fired. Drop the
      // stragglers for a beat. Safe against clipping the reply: the model only starts speaking again
      // after end-of-speech plus silenceDurationMs (1.2s), well past this window.
      discardAudioUntil = System.currentTimeMillis() + INTERRUPT_DISCARD_MS
      onInterrupted()
    }

    sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
      heardUserSinceLastTurn = true
      onTranscript("you", it)
    }
    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
      emitSaiTranscript(it)
    }

    val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
    if (parts != null) {
      val discarding = System.currentTimeMillis() < discardAudioUntil
      for (i in 0 until parts.length()) {
        val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
        if (discarding) continue // straggler from the turn the user just barged in on
        modelSpeaking = true // model is producing audio → mid-turn
        onAudio(Base64.decode(data, Base64.NO_WRAP))
      }
    }

    // `generationComplete` also ends the model's output — it just doesn't end the TURN. Both are
    // flush points for nudge gating, and taking either one closes a hole: a held nudge was only ever
    // released on `turnComplete`, so a turn that produced a generation and no turn-end frame left
    // `modelSpeaking` stuck true and EVERY later nudge deferred behind it, silently, for the rest of
    // the call. A completion the user never hears is exactly that shape. Safe to send here: nothing
    // further is being generated, and audio already queued still plays in order.
    val generationEnded = sc.optBoolean("generationComplete", false)
    val turnEnded = sc.optBoolean("turnComplete", false)
    if (generationEnded || turnEnded) {
      modelSpeaking = false
      flushNudges() // deliver anything held back during the turn
      // Only a real turn boundary ends the transcript entry; a generation boundary mid-turn does not.
      if (turnEnded) onTurnComplete()
    }
    // A turn that heard the user and said nothing is worth a line: an ignored side conversation used
    // to look exactly like a swallowed utterance or a wedged session.
    //
    // It states the FACT and not a motive, because the first version guessed at one and guessed wrong:
    // it read "stayed silent — judged it wasn't for Sai" over a turn where Sai had been explicitly
    // TOLD to stay silent (the ask-first completion nudge) and then answered the user perfectly well
    // in the very next turn. A log line that invents a reason is worse than one that reports what
    // happened, so it now reports what happened — and says nothing at all when we know a nudge asked
    // for the silence, since in that case the silence is ours, not Sai's.
    if (turnEnded) {
      // A withheld placeholder that never became speech: say so once, here, rather than per delta.
      // Silent suppression would trade a visible wrong line for an invisible one, and this is the
      // evidence that says whether the model or the API produced it (see isPlaceholderSpeech).
      if (withheld.isNotEmpty()) {
        onLog("✗ dropped a placeholder turn (\"${withheld.trim()}\") — not speech")
      }
      if (heardUserSinceLastTurn && !spokeThisTurn && !silenceWasRequested) {
        onLog("— no reply to that (Sai may have judged it wasn't meant for it) —")
      }
      heardUserSinceLastTurn = false
      spokeThisTurn = false
      silenceWasRequested = false
      saiTurn = ""
      withheld = ""
    }
  }

  /**
   * Forward a transcript delta from Sai, unless the turn so far is only a placeholder.
   *
   * Withholding rather than dropping matters: the test is against the accumulated turn, so the first
   * fragment of a real sentence can look placeholder-shaped for one frame ("Empty" before
   * "Empty-handed, sorry"). Anything held back is released the moment the turn stops matching.
   */
  private fun emitSaiTranscript(delta: String) {
    saiTurn += delta
    if (isPlaceholderSpeech(saiTurn)) {
      withheld += delta
      return
    }
    val out = withheld + delta
    withheld = ""
    modelSpeaking = true
    spokeThisTurn = true
    onTranscript("sai", out)
  }

  /**
   * Relay the model's function-calls to the concierge as effects, then tool-respond to EVERY call so
   * the model continues its turn. `getSaiStatus` is answered locally (never forwarded).
   */
  private fun handleToolCall(webSocket: WebSocket, toolCall: JSONObject) {
    val calls = toolCall.optJSONArray("functionCalls") ?: return
    val effects = JSONArray()
    val responses = JSONArray()
    // The model routinely emits captureImage AND forwardToAgent in the SAME batch ("what am I
    // looking at?"). Sending that forward straight through raced the photo: the task reached the
    // agent with no attachment, so it answered from the REMOTE COMPUTER's own screen and described
    // that as what the user was looking at. So a task that asks for the photo waits for it — and
    // must not go at all if the capture failed, or we forward a vision task blind. A capture
    // ANYWHERE in the batch counts, because the calls are processed in arrival order and the forward
    // can precede the captureImage that `captureInFlight` would otherwise report.
    val hasCapture =
        (0 until calls.length()).any { calls.getJSONObject(it).optString("name") == "captureImage" }
    for (i in 0 until calls.length()) {
      val c = calls.getJSONObject(i)
      val name = c.optString("name")
      // captureImage is async (capture + upload) — defer its tool response until it completes so the
      // attachment is stashed server-side before the model's next forwardToAgent. Never an effect.
      // recallHistory is async (a network read) — defer its tool response like captureImage.
      // Capture THIS socket: if the Live session gets replaced (token-expiry reconnect) before the
      // deferred response lands, the call id doesn't exist in the new session — drop it instead.
      if (name == "recallHistory") {
        onLog("🕘 recallHistory")
        val id = c.opt("id")
        onRecallHistory { history ->
          sendToolResponseTo(webSocket, id, name, JSONObject().put("history", history))
        }
        continue
      }
      if (name == "captureImage") {
        onLog("📷 captureImage")
        val id = c.opt("id")
        // A captureImage arriving while one is already running COALESCES onto it (the glasses expose a
        // single stream), and every waiter is answered from the one result — so the outcome nudge fired
        // once per waiter and the model was told the same thing twice ("→ nudge: capture-failed" twice
        // in the device log). Each tool CALL still gets its own response, as it must; only the spoken
        // outcome is deduped, and this latch makes the first responder the one that speaks.
        if (!captureInFlight) outcomeNudged.set(false)
        captureInFlight = true
        // Answer IMMEDIATELY. The model cannot produce speech while a tool call in the batch is
        // unanswered, so deferring this response until the photo landed is what bought the user
        // several seconds of dead air on every "what am I looking at?" — the model wasn't refusing
        // to speak, it was blocked. Every other tool answers instantly, which is exactly why they
        // never had this problem. The outcome arrives later as a nudge, and the ordering that the
        // deferral used to protect (attachment stashed before the task is forwarded) is now held by
        // the task gate below instead.
        sendToolResponseTo(
            webSocket,
            id,
            name,
            JSONObject()
                .put("result", "capture-started")
                .put(
                    "note",
                    "The photo is being taken now, and the camera often needs more than one attempt. Say a " +
                        "brief acknowledgment out loud right now, setting that expectation in the same breath: " +
                        "\"let me take a look, this might take a few tries\" is the shape. ONE short line, and " +
                        "about the tries rather than a duration — never put a number on how long it will take. " +
                        "Do not describe anything yet: you have no photo. I will tell you the moment it lands " +
                        "or fails.",
                ),
        )
        onCaptureImage { ok, message ->
          captureInFlight = false
          val released = synchronized(heldTaskEffects) {
            val effs = JSONArray().also { a -> heldTaskEffects.forEach { a.put(it) } }
            val names = heldTaskNames.toList()
            heldTaskEffects.clear()
            heldTaskNames.clear()
            effs to names
          }
          val (effs, names) = released
          // One outcome, one nudge, however many calls coalesced onto this capture.
          if (!outcomeNudged.compareAndSet(false, true)) {
            onLog("📷 outcome already relayed — not telling Sai twice")
            return@onCaptureImage
          }
          if (ok) {
            // The photo is uploaded and held on the device; a released task carries it only if the
            // model set attachLatestImage on it (CallService.sendEffectsWithRequestedContext).
            if (effs.length() > 0) {
              onLog("→ effect: released ${names.joinToString()} (photo held, attaching if asked)")
              onEffects(effs)
            }
            injectNudge(
                if (effs.length() > 0) "capture-landed+task" else "capture-landed",
                if (effs.length() > 0)
                    "[agent] The glasses photo landed and the task you queued has now started. Don't " +
                        "re-describe the photo — you haven't seen it, the task has."
                else
                    // Deliberately does NOT tell Sai to ask what to do with it: a photo with no request
                    // is the resting state of a clipboard, not a loose end to chase.
                    "[agent] The glasses photo landed. It is SAVED on the device and has NOT been sent " +
                        "anywhere — it goes only when a request carries it. Acknowledge in a few words " +
                        "that you have it; do not say you sent it or that anything is underway.",
            )
          } else {
            // Only tasks that asked for the photo are in this list, so dropping them is right: each
            // one exists to act on a picture that does not exist. Anything unrelated the user said
            // during the capture was never held in the first place.
            names.forEach {
              onLog("✗ dropped $it — it needed the photo, and the capture failed")
            }
            injectNudge(
                "capture-failed",
                "[agent] The glasses capture FAILED: $message. Nothing was forwarded and no photo " +
                    "exists. Tell the user plainly that the capture failed and why, and offer to try " +
                    "again. Do NOT claim any task is running or done, and do NOT describe what they " +
                    "are looking at — you cannot see it.",
            )
          }
        }
        continue
      }
      // Client-local tools (getSaiStatus/switchMachine/endCall) are handled on-device and NEVER
      // forwarded to the concierge as effects — same contract as the reference web client's getSaiStatus.
      val response: JSONObject =
          when (name) {
            "getSaiStatus" -> JSONObject().put("status", onGetSaiStatus())
            "switchMachine" -> {
              val target = c.optJSONObject("args")?.optString("machine").orEmpty()
              onLog("↺ switchMachine: $target")
              JSONObject().put("result", onSwitchMachine(target))
            }
            "endCall" -> {
              onLog("⏻ endCall")
              onEndCall(spokeThisTurn)
              JSONObject().put("result", "ok")
            }
            // A task that ASKS FOR the photo waits for it. Approvals, interrupts and state signals go
            // through immediately, as does any task that isn't about the photo.
            //
            // The test is `attachLatestImage`, for all three kinds. It used to be unconditional for
            // forwardToAgent/enqueue — a hangover from before the flag existed, when a vision task
            // couldn't be identified any other way — and that read the gate as "a capture is running"
            // rather than "this request needs the picture". Since a capture can take ~30 s with
            // retries, everything the user said in the meantime was swept in: asking for the weather
            // during a capture had its forward HELD and then DROPPED when the camera failed, so the
            // request ran nowhere and nothing said so. Silently discarding work the user asked for is
            // a worse failure than the one the unconditional wait was insuring against — and that one
            // is now covered by the rubric plus the attach-with-nothing-captured correction.
            "forwardToAgent",
            "enqueue",
            "relayToAgent" -> {
              val wantsPhoto = c.optJSONObject("args")?.optBoolean("attachLatestImage") == true
              if (wantsPhoto && (hasCapture || captureInFlight)) {
                synchronized(heldTaskEffects) {
                  heldTaskEffects.add(fcToEffect(c))
                  heldTaskNames.add(name)
                }
                onPhotoDestined() // the photo now has somewhere to go — the UI should say so
                onLog("⏸ holding $name (it asked for the photo) until the capture resolves")
                // Answered immediately (truthfully) rather than deferred — an unanswered call in the
                // batch would keep the model mute for the whole capture.
                JSONObject()
                    .put("result", "held-for-photo")
                    .put(
                        "note",
                        "NOT started yet — waiting for the glasses photo so the task has it. It will " +
                            "start by itself the moment the photo lands, or be cancelled if the " +
                            "capture fails. Do not claim it is running.",
                    )
              } else {
                effects.put(fcToEffect(c))
                // Name the one case the narrowed gate can get wrong: a task that IS about the photo
                // but never set the flag goes out blind. The prompt and rubric are what prevent it;
                // this line is how we'd find out they didn't.
                if (hasCapture || captureInFlight) {
                  onLog("→ effect: $name (during a capture, but it didn't ask for the photo)")
                } else {
                  onLog("→ effect: $name")
                }
                JSONObject().put("result", "ok")
              }
            }
            else -> {
              effects.put(fcToEffect(c)) // { kind: name, ...args }
              onLog("→ effect: $name")
              JSONObject().put("result", "ok")
            }
          }
      responses.put(JSONObject().put("id", c.opt("id")).put("name", name).put("response", response))
    }
    if (effects.length() > 0) onEffects(effects)
    // Guard: if the only call was the deferred captureImage, there's nothing to respond to yet.
    if (responses.length() > 0) {
      webSocket.send(
          JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString(),
      )
    }
  }

  /**
   * Send a deferred tool response by call id, but only if [sock] is still the live session —
   * a response for a call from a superseded session would confuse the replacement.
   */
  private fun sendToolResponseTo(sock: WebSocket, id: Any?, name: String, response: JSONObject) {
    if (ws !== sock) {
      Log.w(TAG, "dropping deferred $name tool response — Live session was replaced")
      return
    }
    sendToolResponse(id, name, response)
  }

  /** Send a single (possibly deferred) tool response by call id — used by async tools. */
  private fun sendToolResponse(id: Any?, name: String, response: JSONObject) {
    ws?.send(
        JSONObject()
            .put(
                "toolResponse",
                JSONObject()
                    .put(
                        "functionResponses",
                        JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", response)),
                    ),
            )
            .toString(),
    )
  }

  /** { kind: fc.name, ...fc.args } — the concierge effect shape. */
  private fun fcToEffect(fc: JSONObject): JSONObject {
    val eff = JSONObject().put("kind", fc.optString("name"))
    fc.optJSONObject("args")?.let { args -> args.keys().forEach { eff.put(it, args.get(it)) } }
    return eff
  }

  // ── Nudges (server → model), gated so they don't cut the model off mid-sentence ──────────────────

  /**
   * Inject a server-pushed nudge as a user turn. Deferred if the model is speaking (unless dropIfBusy).
   *
   * [kind] is a short tag for the log — "complete", "muted", "capture-retry". EVERY outcome is logged,
   * because a nudge has four of them (sent, held, dropped, discarded) and until now the log showed
   * none: an agent event appeared as `✓ done: …` and then whatever Sai said next, with no way to tell
   * a nudge Sai ignored from one that never reached it. That gap is what made several reports
   * unattributable — a muted call whose MUTED_NUDGE hit a null socket looked identical to the model
   * disregarding it.
   *
   * The nudge BODY is deliberately never logged: it carries agent-derived text (summaries, page
   * content) and this log is mirrored to a projector.
   */
  fun injectNudge(kind: String, turns: String, dropIfBusy: Boolean = false) {
    if (modelSpeaking) {
      if (dropIfBusy) {
        onLog("→ nudge: $kind — dropped (mid-utterance)")
        return
      }
      synchronized(deferredNudges) { deferredNudges.add(kind to turns) }
      onLog("→ nudge: $kind — held until the turn ends")
      return
    }
    // A client turn before setup completes is not deliverable: `ws` may not exist yet, and even an
    // open socket must receive the setup frame first, so anything sent ahead of it is at best racing
    // that frame. HOLD it and send it the moment setup lands, rather than dropping it: muting during
    // the second or two a call takes to connect used to leave the model unaware it was muted for the
    // whole call, because MUTED_NUDGE went to a null socket and nothing said so.
    if (!ready) {
      // Session state needs no buffer entry — setupComplete re-asserts it by definition, and buffering
      // it too would deliver the same instruction twice at the start of the call.
      if (sessionState?.first == kind) {
        onLog("→ nudge: $kind — will be asserted when the session is ready")
        return
      }
      synchronized(preConnectNudges) { preConnectNudges.add(kind to turns) }
      onLog("→ nudge: $kind — held until the session is ready")
      return
    }
    // These are the nudges that ask for silence; a quiet turn after one of them is obedience, and
    // must not be reported as Sai judging the speech wasn't for it.
    if (kind.startsWith("muted") || kind.startsWith("complete (ask-first")) silenceWasRequested = true
    onLog("→ nudge: $kind")
    sendClientTurn(turns)
  }

  /**
   * Inject a nudge AND record whether it describes state the next Live session must be told about.
   *
   * `sticky = true` keeps it (mute); `false` clears whatever was kept (unmute). This replaces the
   * caller having to re-assert mute itself on every connect — it did, from `greetOnFirstReady`, which
   * is why the greeting could then override it.
   */
  fun injectSessionState(kind: String, turns: String, sticky: Boolean) {
    sessionState = if (sticky) kind to turns else null
    injectNudge(kind, turns)
  }

  /** Deliver anything injected before the session was ready, oldest first, as one turn. */
  private fun flushPreConnectNudges() {
    val pending =
        synchronized(preConnectNudges) {
          if (preConnectNudges.isEmpty()) return
          val kinds = preConnectNudges.joinToString(", ") { it.first }
          val joined = preConnectNudges.joinToString("\n\n") { it.second }
          preConnectNudges.clear()
          kinds to joined
        }
    onLog("← nudge: delivering ${pending.first} (held until the session was ready)")
    sendClientTurn(pending.second)
  }

  private fun flushNudges() {
    val pending =
        synchronized(deferredNudges) {
          if (deferredNudges.isEmpty()) return
          val kinds = deferredNudges.joinToString(", ") { it.first }
          val joined = deferredNudges.joinToString("\n\n") { it.second }
          deferredNudges.clear()
          kinds to joined
        }
    onLog("← nudge: delivering ${pending.first} (held during the turn)")
    sendClientTurn(pending.second)
  }

  /** Build the setup frame from the server-provided config (model/prompt/tools/voice). */
  private fun buildSetup(boot: SessionBootstrap): JSONObject {
    val model = if (boot.model.startsWith("models/")) boot.model else "models/${boot.model}"

    // Tools arrive as [{name, description, parameters}]; Gemini wants `parametersJsonSchema`
    // (raw JSON schema), not `parameters` (which expects the uppercase-Type Gemini Schema).
    val declarations = JSONArray()
    runCatching {
      val tools = JSONArray(boot.toolsJson)
      for (i in 0 until tools.length()) {
        val t = tools.getJSONObject(i)
        declarations.put(
            JSONObject()
                .put("name", t.optString("name"))
                .put("description", t.optString("description"))
                .put("parametersJsonSchema", t.opt("parameters")),
        )
      }
    }

    val generationConfig =
        JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject()
                    .put(
                        "voiceConfig",
                        JSONObject()
                            .put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", boot.voice),
                            ),
                    ),
            )

    val setup =
        JSONObject()
            .put("model", model)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", boot.systemPrompt))),
            )
            // MUST stay an empty object. A `languageCodes: ["en-US"]` hint was tried here to stop the
            // ASR inventing Spanish phantom words from noise — that field exists on VERTEX's Live API,
            // not on the Developer API we connect to, where AudioTranscriptionConfig is an empty
            // message. Unknown fields are NOT dropped: the server rejects the whole setup frame and
            // closes the socket with 1007 ("Unknown name \"languageCodes\" at
            // 'setup.input_audio_transcription'"), so every call died before "setup complete". The
            // AudioIo noise gate and the prompt are the real defenses against phantom words.
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
    if (declarations.length() > 0) {
      setup.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
    }

    // Always hands-free. Tap-to-talk (automatic VAD off, mic bracketed by activityStart/activityEnd)
    // was removed: with the VAD disabled the model only replies once the client sends activityEnd, so
    // every exchange cost two taps and Sai could never answer while you were still talking. The tap now
    // toggles MUTE instead (see CallService) — Sai always listens; the tap only decides if it speaks.
    val activityDetection = JSONObject()
    run {
      // Use HIGH start-of-speech sensitivity so the model registers the user the
      // instant they start talking and cuts itself off promptly. With LOW, barge-in only landed AFTER
      // the model finished its sentence — the mic is full-duplex and AEC'd (MODE_IN_COMMUNICATION +
      // VOICE_COMMUNICATION), so its own playback won't self-trigger the VAD. Overheard/ambient speech
      // is filtered by the prompt ("only respond to what's clearly addressed to you"), NOT by dulling
      // the VAD — a slow barge-in is worse than an occasional stray trigger the model can ignore. Keep
      // the default activityHandling so the user can still barge in, and a longer end-of-speech silence
      // so we don't clip the user mid-sentence.
      //
      // HIGH sensitivity also makes the VAD more willing to treat ambient noise/near-silence as speech
      // (phantom words). The client-side noise gate in AudioIo (RMS energy gate) is the primary defense
      // — sub-threshold frames never reach this VAD. prefixPaddingMs is the complementary guard: it's
      // the amount of *detected* speech required before start-of-speech commits, so a brief noise blip
      // that sneaks past the gate won't open a turn. Bumped 300→400 ms: enough to reject short blips
      // without noticeably slowing barge-in (a real interruption is easily >400 ms of speech).
      activityDetection
          .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
          .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
          .put("prefixPaddingMs", 400)
          .put("silenceDurationMs", 1200)
    }
    setup.put(
        "realtimeInputConfig",
        JSONObject().put("automaticActivityDetection", activityDetection),
    )
    return JSONObject().put("setup", setup)
  }

  companion object {
    private const val TAG = "SaiFi:Live"
    // How long to ignore model audio after a barge-in. Covers chunks of the interrupted turn that
    // were already in flight; comfortably shorter than the pause before the model's next reply
    // (end-of-speech + silenceDurationMs).
    private const val INTERRUPT_DISCARD_MS = 700L
  }
}
