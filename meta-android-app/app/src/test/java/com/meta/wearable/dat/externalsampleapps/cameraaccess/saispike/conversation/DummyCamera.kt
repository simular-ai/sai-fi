/* sai-fi — voice concierge. */

// A glasses camera, without glasses.
//
// The camera is the one part of a call a scripted double cannot stand in for, because the photo has
// to physically EXIST somewhere the agent can fetch it: the device uploads it and the task carries a
// reference. So this puts a real JPEG through the real `/v1/agents/upload` and leaves the real
// attachment on the bridge. From the agent's side it is indistinguishable from a glasses photo —
// only the photons are fake.
//
// The frame is a committed resource rather than something drawn here: Android unit tests compile
// against `android.jar`, which has no `java.awt` and no `javax.imageio`, so there is nothing to draw
// with. It was generated on a Mac (see the PR) and says SIMULATED CAPTURE across the bottom, because
// a demo recording showing a photograph invites the question "did that really come off the glasses?"
// and a frame that answers it on its face cannot mislead anyone watching.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.ConciergeClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.TaskAttachment
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.toTaskAttachment

object DummyCamera {

  const val WIDTH = 1024
  const val HEIGHT = 768

  /** What the frame depicts, so a scenario can check the answer against something knowable. */
  const val DEPICTS = "A red notebook and a coffee mug"

  fun frame(): ByteArray =
      checkNotNull(DummyCamera::class.java.getResourceAsStream("/capture/simulated-capture.jpg")) {
            "missing /capture/simulated-capture.jpg — the simulated glasses frame"
          }
          .use { it.readBytes() }

  /**
   * Take the frame, upload it, and hand back the attachment the next forward should carry.
   *
   * Same endpoint and same shape as the device's own path, including the width and height the
   * uploader adds afterwards — the server does not measure the image, so a client that omits them
   * leaves the agent guessing at the aspect ratio.
   */
  suspend fun capture(baseUrl: String, bearerToken: String): TaskAttachment =
      ConciergeClient.uploadAttachment(baseUrl, bearerToken, frame(), "glasses.jpg")
          .put("width", WIDTH)
          .put("height", HEIGHT)
          .toTaskAttachment()
}
