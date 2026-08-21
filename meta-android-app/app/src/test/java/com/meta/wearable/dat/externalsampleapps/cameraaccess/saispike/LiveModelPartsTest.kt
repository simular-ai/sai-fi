/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveModelPartsTest {

  private fun part(json: String) = JSONObject(json)

  private fun frame(vararg parts: JSONObject) =
      JSONArray().apply { parts.forEach { put(it) } }

  @Test
  fun `ordinary audio is played and is not a transcript fallback`() {
    val actions =
        LiveModelParts.classifyFrame(
            frame(part("""{"inlineData":{"mimeType":"audio/pcm","data":"AAAA"}}""")))
    assertEquals(1, actions.size)
    assertEquals("AAAA", actions[0].playAudioB64)
    assertNull("played audio is transcribed separately", actions[0].transcriptFallback)
    assertNull(actions[0].log)
  }

  @Test
  fun `thought audio is not played — it is a different untranscribed voice`() {
    // Device 2026-08-20: during a flower capture the wearer heard a different voice talking about
    // waiting for the camera, and those words were not in the transcript. Live thought parts carry
    // their own audio; outputAudioTranscription skips them.
    val actions =
        LiveModelParts.classifyFrame(
            frame(
                part(
                    """{"thought":true,"text":"waiting for the camera to start","inlineData":{"data":"THOUGHT"}}""")))
    assertEquals(1, actions.size)
    assertNull("thought audio must not reach the speaker", actions[0].playAudioB64)
    assertNull("thoughts are not speech", actions[0].transcriptFallback)
    assertEquals("[live] dropped thought audio — not speech", actions[0].log)
  }

  @Test
  fun `thought text without audio is dropped, not spoken or transcribed`() {
    val actions =
        LiveModelParts.classifyFrame(frame(part("""{"thought":true,"text":"paragraph writing"}""")))
    assertEquals(1, actions.size)
    assertNull(actions[0].playAudioB64)
    assertNull(actions[0].transcriptFallback)
    assertEquals("[live] dropped thought text — not speech", actions[0].log)
  }

  @Test
  fun `a text-only frame surfaces on the transcript so untranscribed speech is still visible`() {
    val actions =
        LiveModelParts.classifyFrame(frame(part("""{"text":"let me take a look"}""")))
    assertEquals(1, actions.size)
    assertNull(actions[0].playAudioB64)
    assertEquals("let me take a look", actions[0].transcriptFallback)
    assertNull("contents stay off the projector log", actions[0].log)
  }

  @Test
  fun `text beside playable audio is not also transcribed — that would double the line`() {
    val actions =
        LiveModelParts.classifyFrame(
            frame(
                part("""{"text":"let me take a look"}"""),
                part("""{"inlineData":{"data":"AAAA"}}""")))
    assertEquals(2, actions.size)
    assertNull(actions[0].playAudioB64)
    assertNull("the audio in this frame owns the transcript", actions[0].transcriptFallback)
    assertEquals("AAAA", actions[1].playAudioB64)
    assertNull(actions[1].transcriptFallback)
  }

  @Test
  fun `speech audio still plays when a thought part is sitting next to it`() {
    val actions =
        LiveModelParts.classifyFrame(
            frame(
                part("""{"thought":true,"inlineData":{"data":"THOUGHT"}}"""),
                part("""{"inlineData":{"data":"SPEECH"}}""")))
    assertNull(actions[0].playAudioB64)
    assertEquals("SPEECH", actions[1].playAudioB64)
    assertNull(actions[1].transcriptFallback)
  }
}
