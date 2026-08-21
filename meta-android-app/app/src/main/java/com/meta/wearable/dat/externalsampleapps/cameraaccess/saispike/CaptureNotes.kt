/* sai-fi — voice concierge. */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/**
 * Tool-response notes the Live session is handed the instant a capture starts, and when a task is
 * held for the photo.
 *
 * These used to be spoken. The capture-started note told the model to "say a brief acknowledgment
 * out loud right now", so a turn that had already said "let me take a look" then kept talking — and
 * the held-for-photo note said "waiting for the glasses photo", which the model read as speech about
 * waiting for the camera (a different-sounding stretch that output transcription never caught). Both
 * notes now state the facts AND that they are silent: the user already heard the acknowledgment;
 * everything else in the turn is calls, not narration.
 *
 * Wording is load-bearing and pinned by test.
 */
object CaptureNotes {
  const val STARTED =
      "The photo is being taken now; the camera often needs more than one attempt. SILENT from " +
          "here: do not speak this note, do not narrate the wait or the camera, do not speak a " +
          "task description or tool argument, and do not ask whether they wanted anything else — " +
          "their request already said. If you have not yet given the brief acknowledgment, say " +
          "ONE short line now (\"let me take a look, this might take a few tries\") and nothing " +
          "more. If you already did, produce nothing. A forward emitted with this capture is held " +
          "until the photo lands; I will tell you when it lands or fails."

  const val HELD_FOR_PHOTO =
      "SILENT — do not speak this note, do not narrate the camera, do not tell the user you are " +
          "waiting, and do not ask whether they wanted anything else. NOT started yet. The task " +
          "is held until the glasses photo lands, then it starts by itself (or is cancelled if " +
          "the capture fails). Do not claim it is running."
}
