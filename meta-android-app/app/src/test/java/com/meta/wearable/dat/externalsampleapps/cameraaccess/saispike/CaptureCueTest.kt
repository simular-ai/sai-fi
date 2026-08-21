/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCueTest {

  @Test
  fun `the capture cue is a two-note tone, not speech`() {
    // Device 2026-08-20: the wearer heard words about waiting for the camera in a different voice
    // during capture. playCaptureCue is a tone; if those words were heard, they did not come from
    // this cue. A spoken sentence at 24 kHz is seconds of PCM; this is two 70 ms blips and a gap.
    assertEquals(185, CaptureCue.DURATION_MS)
    assertTrue("a spoken line is longer than this", CaptureCue.DURATION_MS < 300)
    val pcm = CaptureCue.pcm
    val expectedBytes = CaptureCue.SAMPLE_RATE * CaptureCue.DURATION_MS / 1000 * 2
    assertEquals(expectedBytes, pcm.size)
    assertTrue("the cue is not silence", pcm.any { it != 0.toByte() })
  }

  @Test
  fun `the cue is built once`() {
    assertTrue(CaptureCue.pcm === CaptureCue.pcm)
  }
}
