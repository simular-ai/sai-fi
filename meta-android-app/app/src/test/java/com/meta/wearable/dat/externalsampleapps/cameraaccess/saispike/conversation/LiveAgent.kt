/* sai-fi — voice concierge. */

// The agent half, against a real cloud-api.
//
// [ScriptedAgent] proves the FSM handles the events it is GIVEN. It cannot prove those are the events
// cloud-api actually sends — an SSE field that changed shape, a status the mapper does not know, an
// approval payload read one key deeper than it is written. That is what this is for, and it is the
// only thing it is for: contract drift. Behaviour is the scripted tier's job, where it is
// reproducible and free.
//
// It delegates to the REAL `VoiceChannelClient`, which turns out to be plain HttpURLConnection with
// no Android imports — so the SSE parsing and the event mapping under test here are the shipped ones,
// not a second implementation that could agree with the FSM while production disagrees.
//
// **It bills a real agent.** `SAI_AGENT_SANDBOX` — which ON_DEVICE_CHECK used to point at for exactly
// this — was reverted and no longer exists, so every scenario wakes a VM and costs money. Hence: a
// named subset, run deliberately, never on a branch build.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceChannelClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.VoiceTransport
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a live run needs, and where it comes from.
 *
 * The ID token is the awkward one and there is no way around it: `SaiAuth.idToken()` needs Firebase
 * and a signed-in Google account, which a headless JVM test has neither of. So the operator supplies
 * one — from the app (Settings → developer mode surfaces it) or from the Firebase CLI. It expires in
 * about an hour, which is another reason this is a short named subset rather than a full sweep.
 */
data class LiveAgentConfig(
    val baseUrl: String,
    val machineId: String,
    val idToken: String,
) {
  companion object {
    /** Read the config from the environment, or explain precisely what is missing. */
    fun fromEnv(): Result<LiveAgentConfig> {
      val missing = mutableListOf<String>()
      val url = System.getenv("SAI_CONCIERGE_URL")?.trimEnd('/') ?: run { missing += "SAI_CONCIERGE_URL"; "" }
      val machine = System.getenv("SAI_MACHINE_ID") ?: run { missing += "SAI_MACHINE_ID"; "" }
      val token = System.getenv("SAI_ID_TOKEN") ?: run { missing += "SAI_ID_TOKEN"; "" }
      return if (missing.isEmpty()) Result.success(LiveAgentConfig(url, machine, token))
      else
          Result.failure(
              IllegalStateException(
                  "the live-agent tier needs ${missing.joinToString(", ")}. " +
                      "SAI_ID_TOKEN is a Firebase ID token for a signed-in user — a headless test " +
                      "cannot mint one, so take it from the app or the Firebase CLI. It expires in " +
                      "about an hour."))
    }
  }
}

class LiveAgent(
    private val config: LiveAgentConfig,
    private val scope: CoroutineScope,
    /** Delivers an event to the FSM, exactly as VoiceSession.followTurn does. */
    private val deliver: suspend (AgentEvent) -> Unit,
    private val log: (String) -> Unit = {},
) : VoiceTransport {

  /** Task texts the agent was asked to start, in order. */
  val started = mutableListOf<String>()

  /** Every event that came back off the wire — the raw material for a contract check. */
  val received = mutableListOf<AgentEvent>()

  /**
   * Why a request was refused, if one was.
   *
   * The FSM catches a failed forward and apologises to the user, which is right on a device and
   * useless in a test: without this, an expired token surfaces as "the task never reached the agent"
   * and sends the operator looking for a contract change. The commonest failures here are all
   * credentials and cold machines, so the reason has to survive.
   */
  val errors = mutableListOf<String>()

  private var turnJob: Job? = null

  override suspend fun sendMessage(
      machineId: String,
      message: String,
      attachments: JSONArray?,
      follow: Boolean,
  ) {
    log("[live] → ${if (follow) "forward" else "steer"}: $message")
    val stream =
        try {
          VoiceChannelClient.openMessageStream(
              baseUrl = config.baseUrl,
              bearerToken = config.idToken,
              machineId = config.machineId,
              message = message,
              attachments = attachments,
          )
        } catch (e: Exception) {
          // Recorded and re-thrown: the FSM still needs to hear that this task did not start (that
          // is what makes it apologise rather than go quiet), but the reason must not be swallowed
          // on its way past.
          errors += "${e::class.simpleName}: ${e.message}"
          log("[live] ✗ refused: ${e.message}")
          throw e
        }
    // A steer lands in a turn already being read; reading its stream too would deliver every event
    // of that turn a second time. Same rule as VoiceSession.
    if (!follow) {
      stream.discard()
      return
    }
    started += message
    // Read on its own job so this returns once ACCEPTED, not once the turn is done — the FSM holds
    // its mutex across this call and needs to be out of it before these events arrive.
    turnJob?.cancel()
    turnJob =
        scope.launch {
          runCatching {
                stream.read(
                    onEvent = { e ->
                      received += e
                      log("[live] ← ${e::class.simpleName}")
                      deliver(e)
                    },
                    onLog = log,
                )
              }
              .onFailure { log("[live] stream dropped: ${it.message}") }
        }
  }

  override suspend fun post(path: String, body: JSONObject): JSONObject {
    log("[live] → POST $path")
    return VoiceChannelClient.postOperation(
        config.baseUrl, config.idToken, path, body.put("machineId", config.machineId))
  }

  /** Wait for the turn in flight, so a scenario can assert on what came back. */
  suspend fun awaitTurn() {
    turnJob?.join()
  }
}
