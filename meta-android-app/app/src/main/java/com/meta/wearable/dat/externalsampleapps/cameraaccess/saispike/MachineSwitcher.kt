/*
 * sai-fi — voice concierge (which machine "switch to my laptop" means).
 */

// Resolving a spoken machine name to a machine, and what to say about it. A pure decision, so it can
// be tested without a device. The reconnect it triggers stays in the service, where the socket is.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/** What [MachineSwitcher.resolve] concluded about a `switchMachine` request. */
sealed interface MachineSwitch {
  /** Nothing to switch to. [reply] goes back as the tool response. */
  data class NoMachines(val reply: String) : MachineSwitch

  /** No machine matched. [reply] names what the user actually has. */
  data class NotFound(val reply: String) : MachineSwitch

  /** Already there — a no-op, but one the user should hear about. */
  data class AlreadyOn(val reply: String) : MachineSwitch

  /** Switch, then answer with [reply]. */
  data class SwitchTo(val machine: Machine, val reply: String) : MachineSwitch
}

object MachineSwitcher {
  /**
   * Match a spoken name against the user's machines.
   *
   * Exact (case-insensitive) first, then containment in EITHER direction — because speech gives both
   * halves of the problem: "switch to studio" for a machine called "Studio Mac", and "my mac studio
   * at home" for one called "Mac Studio". A single-direction `contains` handles one and not the
   * other, and which one it misses depends on how the user happened to phrase it.
   */
  fun resolve(query: String, machines: List<Machine>, currentMachineId: String): MachineSwitch {
    val q = query.trim()
    if (q.isEmpty() || machines.isEmpty()) {
      return MachineSwitch.NoMachines("I don't have another machine to switch to.")
    }
    val match =
        machines.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: machines.firstOrNull {
              it.label.contains(q, ignoreCase = true) || q.contains(it.label, ignoreCase = true)
            }
    if (match == null) {
      return MachineSwitch.NotFound(
          "I couldn't find a machine called \"$q\". " +
              "You have: ${machines.joinToString(", ") { it.label }}.")
    }
    if (match.machineId == currentMachineId) {
      return MachineSwitch.AlreadyOn("You're already on ${match.label}.")
    }
    // The session prompt's "active machine" context was baked in at POST /session time and is now
    // stale — the Live session is deliberately NOT re-minted (that would reset the conversation).
    // Reset the model's machine context through the tool response instead: it enters the model's
    // context without producing a spoken turn.
    return MachineSwitch.SwitchTo(
        match,
        "Switched to ${match.label}. " +
            "(Context update, not to be spoken aloud: the active machine for this session is now " +
            "\"${match.label}\" — ignore any earlier context naming a different active machine.)")
  }

  /**
   * The same correction, for a switch made from the UI picker rather than by voice.
   *
   * A tool call can carry the context update in its response; a button press has no response to
   * carry it, so it goes in as a nudge. Both must say "not to be spoken aloud" — the user pressed a
   * button and does not need to be told what they just did.
   */
  fun contextNudge(label: String): String =
      "[system] Context update (not to be spoken aloud unless the user asks): the active " +
          "machine for this session is now \"$label\" — ignore any earlier context " +
          "naming a different active machine."
}
