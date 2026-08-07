package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's wording, which is the only kind of bug this surface has ever had.
 *
 * It was previously unreachable from a JVM test: the `when` blocks were private members of an Android
 * Service, reading its fields. They are now a pure function of (muted, paused, machineLabel).
 */
class CallNotificationTextTest {

  // "Sai is listening" reads as "your mic is hot", which is the opposite of what a MUTED user wants to
  // see. The title has to say which of the two states the call is actually in.
  @Test
  fun `a muted call never claims to be listening in the title`() {
    val title = CallNotificationText.title(muted = true, paused = false)
    assertTrue("muted title should say muted, was: $title", title.contains("muted"))
    assertEquals("Sai is listening", CallNotificationText.title(muted = false, paused = false))
  }

  // ...but it IS still listening, and a muted user who thinks the mic is off is the other half of the
  // same confusion. The body has to carry both facts.
  @Test
  fun `a muted call says it still listens but will not speak`() {
    val body = CallNotificationText.body(muted = true, paused = false, machineLabel = "Main VM")
    assertTrue(body, body.contains("still listening"))
    assertTrue(body, body.contains("won't speak"))
  }

  /**
   * Pause dominates mute everywhere. While paused there are no mic frames, so no keepalives, so the
   * server's idle guard treats a long pause exactly like a walked-away call and ends it. Saying so beats
   * surprising someone on resume — and if pause did NOT dominate, a paused+muted call would advertise
   * itself as merely muted and the warning would never be shown.
   */
  @Test
  fun `pause dominates mute, and warns that a long pause ends the call`() {
    for (muted in listOf(true, false)) {
      val title = CallNotificationText.title(muted = muted, paused = true)
      val body = CallNotificationText.body(muted = muted, paused = true, machineLabel = "Main VM")
      assertEquals("Sai is paused", title)
      assertTrue(body, body.contains("can't hear you"))
      assertTrue(body, body.contains("ends the call"))
      assertEquals("Resume", CallNotificationText.secondaryAction(muted = muted, paused = true))
    }
  }

  // The action label must match the state, or the button does the opposite of what it says.
  @Test
  fun `the secondary action names what it will do`() {
    assertEquals("Mute", CallNotificationText.secondaryAction(muted = false, paused = false))
    assertEquals("Unmute", CallNotificationText.secondaryAction(muted = true, paused = false))
  }

  @Test
  fun `a live call names the machine it is working on`() {
    val body = CallNotificationText.body(muted = false, paused = false, machineLabel = "Main VM")
    assertTrue(body, body.contains("Main VM"))
  }
}
