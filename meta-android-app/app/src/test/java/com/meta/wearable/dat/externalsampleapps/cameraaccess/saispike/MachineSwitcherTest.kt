/*
 * sai-fi — voice concierge.
 */

// Matching a spoken machine name. The interesting cases are all speech-shaped: the user says less
// than the label, or more than it, and both have to land on the same machine.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineSwitcherTest {

  private val laptop = Machine("m-1", "Work Laptop")
  private val studio = Machine("m-2", "Mac Studio")
  private val all = listOf(laptop, studio)

  @Test
  fun `no machines at all`() {
    val d = MachineSwitcher.resolve("laptop", emptyList(), "m-1")
    assertTrue(d is MachineSwitch.NoMachines)
    assertEquals("I don't have another machine to switch to.", (d as MachineSwitch.NoMachines).reply)
  }

  @Test
  fun `a blank name is not a request`() {
    assertTrue(MachineSwitcher.resolve("   ", all, "m-1") is MachineSwitch.NoMachines)
  }

  @Test
  fun `an exact name, whatever the casing`() {
    val d = MachineSwitcher.resolve("work laptop", all, "m-2")
    assertEquals(laptop, (d as MachineSwitch.SwitchTo).machine)
  }

  @Test
  fun `the user says LESS than the label — "studio" finds "Mac Studio"`() {
    val d = MachineSwitcher.resolve("studio", all, "m-1")
    assertEquals(studio, (d as MachineSwitch.SwitchTo).machine)
  }

  @Test
  fun `the user says MORE than the label — "my mac studio at home" finds "Mac Studio"`() {
    // The other direction, and the reason the match is bidirectional. A single-direction `contains`
    // handles one of these two phrasings and silently misses the other.
    val d = MachineSwitcher.resolve("my mac studio at home", all, "m-1")
    assertEquals(studio, (d as MachineSwitch.SwitchTo).machine)
  }

  @Test
  fun `an exact match wins over a containment match`() {
    val exact = Machine("m-3", "Studio")
    val d = MachineSwitcher.resolve("studio", listOf(studio, exact), "m-1")
    assertEquals(exact, (d as MachineSwitch.SwitchTo).machine)
  }

  @Test
  fun `no match names what the user actually has, rather than just failing`() {
    val d = MachineSwitcher.resolve("the server in the cupboard", all, "m-1")
    assertTrue(d is MachineSwitch.NotFound)
    val reply = (d as MachineSwitch.NotFound).reply
    assertTrue(reply.contains("the server in the cupboard"))
    assertTrue(reply.contains("Work Laptop"))
    assertTrue(reply.contains("Mac Studio"))
  }

  @Test
  fun `already on it is a no-op that still says so`() {
    val d = MachineSwitcher.resolve("Mac Studio", all, "m-2")
    assertTrue(d is MachineSwitch.AlreadyOn)
    assertEquals("You're already on Mac Studio.", (d as MachineSwitch.AlreadyOn).reply)
  }

  @Test
  fun `the switch reply carries a context update the model must not speak`() {
    val reply = (MachineSwitcher.resolve("laptop", all, "m-2") as MachineSwitch.SwitchTo).reply
    assertTrue(reply.startsWith("Switched to Work Laptop."))
    // The session prompt still names the OLD machine; this is what corrects it without re-minting
    // the Live session, and it must not become a spoken turn.
    assertTrue(reply.contains("not to be spoken aloud"))
    assertTrue(reply.contains("ignore any earlier context"))
  }

  @Test
  fun `the UI picker's nudge says the same thing, as a system line`() {
    val nudge = MachineSwitcher.contextNudge("Work Laptop")
    assertTrue(nudge.startsWith("[system]"))
    assertTrue(nudge.contains("not to be spoken aloud"))
    assertTrue(nudge.contains("Work Laptop"))
    assertTrue(nudge.contains("ignore any earlier context"))
  }
}
