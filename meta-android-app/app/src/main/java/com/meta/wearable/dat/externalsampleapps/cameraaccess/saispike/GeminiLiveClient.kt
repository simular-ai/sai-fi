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
  /**
   * The turn/nudge state machine — what this session does with a nudge, a tool call and a turn
   * boundary. It lives in [LiveTurnGate] rather than here because it is the part worth testing and
   * this class is untestable: a WebSocket, an `android.util.Base64` and an `android.util.Log`.
   *
   * The gate performs no I/O. It returns [GateAction]s and [run] is the interpreter — so the ordering
   * between a log line and the turn it describes is explicit rather than incidental.
   */
  private val gate = LiveTurnGate()

  /** Perform what the gate decided, in order. */
  private fun run(actions: List<GateAction>) {
    for (action in actions) {
      when (action) {
        is GateAction.SendTurn -> sendClientTurn(action.text)
        is GateAction.Log -> onLog(action.text)
        is GateAction.SaiTranscript -> onTranscript("sai", action.text)
        is GateAction.UserTranscript -> onTranscript("you", action.text)
        is GateAction.TurnComplete -> onTurnComplete()
        is GateAction.FlushPlayback -> onInterrupted()
        is GateAction.ReleaseEffects -> onEffects(action.effects)
      }
    }
  }

  private val client =
      OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null

  fun connect(boot: SessionBootstrap, apiKey: String) {
    // A real API key uses the plain BidiGenerateContent method + `?key=`. The *Constrained* method
    // with `?access_token=` is for an ephemeral `auth_tokens/…` value, and there is no longer any
    // such thing here — the device holds the user's own key. Passing a key to the Constrained form
    // (or a token to this one) fails with 1007 "api key not valid", so the pair is not swappable.
    val url =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent" +
            "?key=$apiKey"
    // Fresh session ⇒ fresh turn state (also correct on a reconnect: the old turn is gone).
    run(gate.onConnect())
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
    gate.onClose()
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
        // The gate re-asserts session state and flushes pre-connect nudges BEFORE onReady: the
        // greeting is injected from there, and a mute asserted while connecting has to reach the
        // model first — otherwise Sai is told to greet, then told to be silent, and obeys the last
        // thing it read. State first, then anything that was waiting on the session.
        run(gate.onSetupComplete())
        onReady()
      }
      json.has("serverContent") -> handleServerContent(json.getJSONObject("serverContent"))
      json.has("toolCall") -> handleToolCall(webSocket, json.getJSONObject("toolCall"))
      // goAway / sessionResumptionUpdate etc. ignored — the call self-heals via onClosed → reconnect.
    }
  }

  private fun handleServerContent(sc: JSONObject) {
    if (sc.optBoolean("interrupted", false)) run(gate.onInterrupted())

    sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
      run(gate.onUserTranscript(it))
    }
    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
      run(gate.onSaiTranscript(it))
    }

    val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
    if (parts != null) {
      // Asked once per frame, not per part: re-reading the clock inside the loop could split a single
      // frame across the discard window's edge.
      val discarding = gate.shouldDiscardAudio()
      for (i in 0 until parts.length()) {
        val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
        if (discarding) continue // straggler from the turn the user just barged in on
        gate.onAudioAccepted() // model is producing audio → mid-turn
        onAudio(Base64.decode(data, Base64.NO_WRAP))
      }
    }

    run(
        gate.onGenerationOrTurnEnd(
            generationEnded = sc.optBoolean("generationComplete", false),
            turnEnded = sc.optBoolean("turnComplete", false),
        ))
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
        gate.onCaptureStarted()
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
          val (effs, names) = gate.onCaptureSettled()
          // One outcome, one nudge, however many calls coalesced onto this capture.
          if (!gate.claimOutcomeNudge()) {
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
              onEndCall(gate.didSpeakThisTurn)
              JSONObject().put("result", "ok")
            }
            // A task that ASKS FOR the photo waits for it (see LiveTurnGate.routeTaskCall for why the
            // test is `attachLatestImage` and not merely "a capture is running"). Approvals,
            // interrupts and state signals go through immediately, as does any task that isn't about
            // the photo.
            "forwardToAgent",
            "enqueue",
            "relayToAgent" -> {
              val wantsPhoto = c.optJSONObject("args")?.optBoolean("attachLatestImage") == true
              when (val routing = gate.routeTaskCall(name, fcToEffect(c), wantsPhoto, hasCapture)) {
                is TaskRouting.HeldForPhoto -> {
                  onPhotoDestined() // the photo now has somewhere to go — the UI should say so
                  onLog(routing.log)
                  routing.response
                }
                is TaskRouting.Emit -> {
                  effects.put(fcToEffect(c))
                  onLog(routing.log)
                  JSONObject().put("result", "ok")
                }
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
  fun injectNudge(kind: String, turns: String, dropIfBusy: Boolean = false) =
      run(gate.injectNudge(kind, turns, dropIfBusy))

  /**
   * Inject a nudge AND record whether it describes state the next Live session must be told about.
   *
   * `sticky = true` keeps it (mute); `false` clears whatever was kept (unmute). This replaces the
   * caller having to re-assert mute itself on every connect — it did, from `greetOnFirstReady`, which
   * is why the greeting could then override it.
   */
  fun injectSessionState(kind: String, turns: String, sticky: Boolean) =
      run(gate.injectSessionState(kind, turns, sticky))

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
  }
}
