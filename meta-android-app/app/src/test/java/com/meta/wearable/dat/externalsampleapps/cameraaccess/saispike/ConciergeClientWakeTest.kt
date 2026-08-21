package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The wake/machines wire parsing, against a real HTTP server on loopback.
 *
 * `ConciergeClient` talks `HttpURLConnection` directly, so there is no transport seam to fake the way
 * `HttpAgentBridge` has one — and the parsing is where the device surprises live. The first device run
 * logged `status=null canWake=false`, which was correct but was reasoned about rather than proven: what
 * `optString` does with a JSON `null`, and what an older server that sends neither field degrades to,
 * are exactly the questions a wake path must not get wrong.
 */
class ConciergeClientWakeTest {

  // A socket rather than a library: `com.sun.net.httpserver` is not on the Android unit-test
  // classpath, and MockWebServer would be a new dependency for one file. `ConciergeClient` is
  // deliberately dependency-free, and so is this.
  private lateinit var server: ServerSocket
  @Volatile private var body = ""
  @Volatile private var status = 200
  @Volatile private var lastRequestLine: String? = null
  @Volatile private var lastBody: String? = null
  @Volatile private var lastAuth: String? = null

  private val baseUrl: String
    get() = "http://127.0.0.1:${server.localPort}"

  @Before
  fun start() {
    server = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val socket = try { server.accept() } catch (e: Exception) { return@thread }
        socket.use {
          val input = it.getInputStream().bufferedReader()
          lastRequestLine = input.readLine()
          var contentLength = 0
          var auth: String? = null
          while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val (name, value) = line.split(":", limit = 2).let { p ->
              p[0].trim().lowercase() to p.getOrElse(1) { "" }.trim()
            }
            if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
            if (name == "authorization") auth = value
          }
          lastAuth = auth
          lastBody =
              if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                  val n = input.read(buf, read, contentLength - read)
                  if (n < 0) break
                  read += n
                }
                String(buf, 0, read)
              } else ""
          val bytes = body.toByteArray()
          // `Connection: close` and no keep-alive: one request per connection keeps this honest.
          it.getOutputStream().apply {
            write(
                ("HTTP/1.1 $status OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n")
                    .toByteArray())
            write(bytes)
            flush()
          }
        }
      }
    }
  }

  @After
  fun stop() = server.close()

  private fun requestedPath(): String? = lastRequestLine?.split(" ")?.getOrNull(1)

  // ── POST /v1/agents/wake ────────────────────────────────────────────────────────────────────────

  @Test
  fun `a dispatched wake parses every field`() = runTest {
    body =
        """{"ok":true,"waking":true,"startingUp":true,"status":"hibernated","canWake":true}"""
    val out = ConciergeClient.wakeMachine(baseUrl, "tok", "m1")

    assertTrue(out.waking)
    assertTrue(out.startingUp)
    assertEquals("hibernated", out.status)
    assertTrue(out.canWake)
    // The request itself, since a wake with the wrong body silently does nothing.
    assertEquals("/v1/agents/wake", requestedPath())
    assertEquals("""{"machineId":"m1"}""", lastBody)
    assertEquals("Bearer tok", lastAuth)
  }

  @Test
  fun `a machine already mid-wake is startingUp without being waking`() = runTest {
    // The distinction the whole path branches on. `waking` is false — correctly, nothing was
    // dispatched — and the user is still owed the "about a minute" line.
    body = """{"ok":true,"waking":false,"startingUp":true,"status":"wakingup","canWake":true}"""
    val out = ConciergeClient.wakeMachine(baseUrl, "tok", "m1")

    assertFalse(out.waking)
    assertTrue(out.startingUp)
  }

  @Test
  fun `a JSON null status parses as null, not as the string "null"`() = runTest {
    // What the server sends for a machine whose doc has no status — the BYOD case from the device log.
    // `optString` returns "" for JSON null, and the `.ifEmpty { null }` is what turns that into a
    // proper absence. Without it the status would read as the four characters "null" and compare
    // unequal to every real state while looking like a value.
    body = """{"ok":true,"waking":false,"startingUp":false,"status":null,"canWake":false}"""
    val out = ConciergeClient.wakeMachine(baseUrl, "tok", "m1")

    assertNull(out.status)
    assertFalse(out.startingUp)
    assertFalse(out.canWake)
  }

  @Test
  fun `an older server that sends none of the fields degrades to doing nothing`() = runTest {
    // Forward compatibility in the safe direction: absent `startingUp` must read as "not coming up",
    // so the client stays silent rather than announcing a minute it cannot vouch for.
    body = """{"ok":true}"""
    val out = ConciergeClient.wakeMachine(baseUrl, "tok", "m1")

    assertFalse("silence is the safe default", out.startingUp)
    assertFalse(out.waking)
    assertFalse(out.canWake)
    assertNull(out.status)
  }

  @Test
  fun `a non-2xx throws with the status, so the caller can tell 404 from a network fault`() =
      runTest {
        status = 404
        body = """{"error":"Machine not found or does not belong to your account."}"""
        val e =
            runCatching { ConciergeClient.wakeMachine(baseUrl, "tok", "nope") }.exceptionOrNull()

        assertTrue(e is ConciergeHttpException)
        assertEquals(404, (e as ConciergeHttpException).status)
      }

  // ── GET /v1/agents/machines ─────────────────────────────────────────────────────────────────────

  @Test
  fun `machines carry status and wakeability, and isActive is exact`() = runTest {
    body =
        """{"machines":[
          {"machineId":"m1","name":"Alpha","status":"active","canWake":true},
          {"machineId":"m2","name":"Beta","status":"hibernated","canWake":true},
          {"machineId":"m3","name":"Gamma","status":"WAKINGUP","canWake":false}
        ]}"""
    val ms = ConciergeClient.listMachines(baseUrl, "tok").associateBy { it.machineId }

    assertTrue(ms.getValue("m1").isActive)
    assertFalse(ms.getValue("m2").isActive)
    assertTrue(ms.getValue("m2").canWake)
    // Deliberately not case-insensitive: an unrecognised state must read as "not usable yet" rather
    // than be coerced into one we know. The wake watcher keeps waiting, which is the safe outcome.
    assertFalse("an unknown spelling is not active", ms.getValue("m3").isActive)
    assertFalse(ms.getValue("m3").canWake)
  }

  @Test
  fun `a machine from an older server has no status and cannot be woken`() = runTest {
    body = """{"machines":[{"machineId":"m1","name":"Alpha"}]}"""
    val m = ConciergeClient.listMachines(baseUrl, "tok").single()

    assertNull(m.status)
    assertFalse(m.canWake)
    assertFalse("never active, so a watcher waits rather than declaring success", m.isActive)
  }
}
