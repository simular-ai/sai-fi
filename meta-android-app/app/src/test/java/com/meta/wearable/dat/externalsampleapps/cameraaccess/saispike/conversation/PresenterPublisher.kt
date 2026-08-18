/* sai-fi — voice concierge. */

// Mirror a harness conversation to the presenter, so a test can be WATCHED.
//
// The presenter already renders a real call — the transcript, the activity log, call state. A
// scripted or judged conversation is the same shape, so it can go down the same pipe and be read in
// the same place. That is worth more than it sounds: an integration test that fails tells you an
// assertion tripped, whereas watching the exchange tells you whether it was a conversation a person
// would have wanted to have. The second question is the one this whole tier exists for and the one
// an assertion cannot answer.
//
// Speaks the wire format directly rather than reusing PresenterSocket, which needs android.os.Handler
// and a Looper and so cannot run in a JVM test. The frames are few and stable; the risk of drift is
// the price of not dragging Robolectric in.
//
// OFF unless asked for: publishing opens a socket, and a unit suite must not depend on a laptop
// service being up. `SAI_PRESENTER=1` turns it on and reads the address the phone uses.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject

class PresenterPublisher(url: String, private val paceMs: Long) {

  private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null
  private var nextId = 1L

  @Volatile private var open = false

  init {
    ws =
        client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("[presenter] could not publish: ${t.message}")
                open = false
              }
            })
    // The socket opens asynchronously and a conversation on a virtual clock is over in
    // milliseconds — without this the whole run is published to nobody.
    val deadline = System.currentTimeMillis() + 3_000
    while (!open && System.currentTimeMillis() < deadline) Thread.sleep(20)
    if (!open) println("[presenter] no presenter answered — continuing without it")
  }

  fun hello(machine: String) =
      send(
          JSONObject()
              .put("t", "hello")
              .put("app", "sai-fi (test harness)")
              .put("machine", machine)
              .put("sampleRates", JSONObject().put("mic", 16000).put("sai", 24000)))

  fun turn(role: String, text: String) =
      send(JSONObject().put("t", "turn").put("id", nextId++).put("role", role).put("text", text))

  /**
   * Publish a turn the way a real one arrives: one id, growing a word at a time.
   *
   * A real call's transcript is a stream of deltas, so the presenter renders a line filling in as it
   * is spoken. Publishing a finished sentence in a single frame is the tell that a rig is driving it
   * — the words appear all at once, at machine speed, and no one is fooled. This paces at ordinary
   * speech (~150 wpm by default) so a recording of the harness looks like a recording of a call.
   *
   * Deliberately not pretending to be a perfect impression: the delta boundaries here are words,
   * whereas a real model streams whatever chunk the API hands over. What matters is the cadence.
   */
  fun speak(role: String, text: String, wordsPerMinute: Int = 150) {
    val id = nextId++
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return
    val perWord = (60_000L / wordsPerMinute).coerceAtLeast(1)
    val sb = StringBuilder()
    for ((i, w) in words.withIndex()) {
      if (i > 0) sb.append(' ')
      sb.append(w)
      ws?.send(JSONObject().put("t", "turn").put("id", id).put("role", role).put("text", sb.toString()).toString())
      // A touch longer after a comma or a full stop, which is where a speaker actually breathes.
      val pause = if (w.endsWith(",") || w.endsWith(".") || w.endsWith("?")) perWord * 2 else perWord
      Thread.sleep(pause)
    }
  }

  /** A silence, for the gaps a conversation actually has. */
  fun pause(ms: Long) = Thread.sleep(ms)

  fun log(text: String) =
      send(JSONObject().put("t", "log").put("id", nextId++).put("text", text))

  fun interrupted() = send(JSONObject().put("t", "interrupted"))

  fun state(active: Boolean, status: String, machine: String, muted: Boolean) =
      send(
          JSONObject()
              .put("t", "state")
              .put("active", active)
              .put("status", status)
              .put("route", "harness")
              .put("machine", machine)
              .put("muted", muted)
              .put("paused", false))

  fun close() {
    ws?.close(1000, null)
    ws = null
  }

  private fun send(o: JSONObject) {
    ws?.send(o.toString())
    // A harness conversation runs on a virtual clock, so without pacing the entire exchange lands in
    // one frame and there is nothing to watch. This is the only real time the harness ever spends,
    // and only when somebody asked to watch.
    if (paceMs > 0) Thread.sleep(paceMs)
  }

  companion object {
    /** Build one from the same address the phone publishes to, or null when not asked for. */
    fun fromEnvOrNull(): PresenterPublisher? {
      if (System.getenv("SAI_PRESENTER") != "1") return null
      // Gradle runs unit tests with the MODULE as the working directory, so local.properties —
      // which lives beside settings.gradle — is one level up. Both are checked so this keeps working
      // whichever directory a runner picks.
      val props =
          listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.exists() }
      if (props == null) {
        println("[presenter] no local.properties found (looked in ./ and ../) — not publishing")
        return null
      }
      val text = props.readText()
      fun prop(k: String) = Regex("^$k=(.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
      val base = prop("presenter_url")?.trimEnd('/')
      if (base.isNullOrEmpty()) {
        println("[presenter] presenter_url is not set — not publishing")
        return null
      }
      val key = prop("presenter_key").orEmpty()
      val url = "$base/publish" + if (key.isNotEmpty()) "?k=$key" else ""
      val pace = System.getenv("SAI_PRESENTER_PACE_MS")?.toLongOrNull() ?: 220L
      println("[presenter] publishing this conversation to $base (pace ${pace}ms)")
      return PresenterPublisher(url, pace)
    }
  }
}
