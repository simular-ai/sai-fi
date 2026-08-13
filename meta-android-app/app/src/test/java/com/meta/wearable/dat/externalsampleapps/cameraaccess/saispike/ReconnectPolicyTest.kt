/*
 * sai-fi — voice concierge.
 */

// Retry classification and the sentence the user gets when retrying is pointless.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

  @Test
  fun `the four permanent codes`() {
    assertTrue(ReconnectPolicy.isPermanent(401))
    assertTrue(ReconnectPolicy.isPermanent(402))
    assertTrue(ReconnectPolicy.isPermanent(403))
    assertTrue(ReconnectPolicy.isPermanent(503))
  }

  @Test
  fun `503 counts as permanent here, against the usual reading`() {
    // From this endpoint 503 is not "busy, try later" — it is voice being disabled or unkeyed for
    // the service, which no amount of retrying changes. Retrying it would leave the user hearing
    // "reconnecting…" forever for a call that is never coming back.
    assertTrue(ReconnectPolicy.isPermanent(503))
  }

  @Test
  fun `transient failures are retried`() {
    assertFalse(ReconnectPolicy.isPermanent(500))
    assertFalse(ReconnectPolicy.isPermanent(502))
    assertFalse(ReconnectPolicy.isPermanent(504))
    assertFalse(ReconnectPolicy.isPermanent(429))
    assertFalse(ReconnectPolicy.isPermanent(0))
  }

  @Test
  fun `each permanent code gets its own reason, because they are different problems`() {
    assertEquals("You're out of credits for voice.", ReconnectPolicy.reasonFor(402))
    assertEquals("Voice isn't available right now.", ReconnectPolicy.reasonFor(503))
    assertEquals("Voice access was denied.", ReconnectPolicy.reasonFor(401))
    assertEquals("Voice access was denied.", ReconnectPolicy.reasonFor(403))
  }

  @Test
  fun `an unrecognised code still gets a sentence, not a status number`() {
    assertEquals("The voice call couldn't continue.", ReconnectPolicy.reasonFor(418))
  }

  @Test
  fun `every reason is one short spoken sentence`() {
    // These are read aloud verbatim by the model. A code, a stack frame or a clause about retries
    // would all be wrong out loud.
    for (code in listOf(401, 402, 403, 503, 999)) {
      val reason = ReconnectPolicy.reasonFor(code)
      assertTrue(reason.endsWith("."))
      assertTrue(reason.length < 60)
      assertFalse(reason.contains(code.toString()))
    }
  }

  @Test
  fun `backoff doubles`() {
    assertEquals(3_000L, ReconnectPolicy.nextBackoff(1_500L))
    assertEquals(6_000L, ReconnectPolicy.nextBackoff(3_000L))
  }

  @Test
  fun `backoff is capped, so a long outage does not become an infinite wait`() {
    assertEquals(15_000L, ReconnectPolicy.nextBackoff(12_000L))
    assertEquals(15_000L, ReconnectPolicy.nextBackoff(15_000L))
    assertEquals(15_000L, ReconnectPolicy.nextBackoff(60_000L))
  }

  @Test
  fun `the first retry is quick, because most drops are a blip`() {
    assertEquals(1_500L, ReconnectPolicy.INITIAL_BACKOFF_MS)
  }
}
