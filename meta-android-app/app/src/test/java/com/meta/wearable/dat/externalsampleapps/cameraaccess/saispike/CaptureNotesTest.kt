/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureNotesTest {

  @Test
  fun `the capture-started note does not tell the model to keep talking`() {
    // The old note said "Say a brief acknowledgment out loud right now", so a turn that had already
    // said "let me take a look" then kept talking — including reading the wait itself.
    val note = CaptureNotes.STARTED
    assertTrue(note.contains("SILENT from here"))
    assertTrue(note.contains("do not speak this note"))
    assertTrue(note.contains("do not narrate the wait"))
    assertTrue(note.contains("do not ask whether they wanted anything else"))
    assertFalse(
        "the old 'say it out loud right now' instruction must not return",
        note.contains("out loud right now"))
  }

  @Test
  fun `the held-for-photo note does not invite camera-wait narration`() {
    val note = CaptureNotes.HELD_FOR_PHOTO
    assertTrue(note.contains("do not speak this note"))
    assertTrue(note.contains("do not tell the user you are waiting"))
    assertTrue(note.contains("NOT started yet"))
    assertTrue(note.contains("Do not claim it is running"))
    assertFalse(
        "the old 'waiting for the glasses photo' phrasing was spoken as camera-wait speech",
        note.contains("waiting for the glasses photo"))
  }
}
