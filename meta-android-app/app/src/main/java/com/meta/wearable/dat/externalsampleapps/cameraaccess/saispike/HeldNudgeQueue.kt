/*
 * sai-fi — voice concierge (muted-call nudge holding).
 */

// HeldNudgeQueue — what Sai would have said while muted, kept until she can be heard again.
//
// While muted the client drops her audio, so injecting a nudge that makes her speak would burn the
// result: she'd say it to nobody and the agent event is not repeated. So we hold those nudges and
// replay them on unmute (CallService injects them, using the ask-first wording for completions so she
// waits for a natural gap rather than blurting).
//
// The collapsing rules exist so unmuting produces ONE short offer, not a monologue:
//   · only the newest `complete` survives — an older result is superseded by definition;
//   · `approval-request` / `error` are what actually block the user, so they come out first;
//   · anything else (progress chatter) is not worth replaying at all once it's stale.
//
// Pure and Android-free so it unit-tests directly — the sequencing is the part worth pinning.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

class HeldNudgeQueue(private val max: Int = 5) {
  data class Held(val kind: String, val nudge: String)

  private val items = mutableListOf<Held>()

  /** True for the event kinds worth waking the user for the moment she's audible again. */
  private fun urgent(kind: String) = kind == "approval-request" || kind == "error"

  /**
   * Hold [nudge]. Returns false when it was deliberately discarded rather than queued, so the caller
   * can log honestly instead of claiming everything was kept.
   */
  fun add(kind: String, nudge: String): Boolean =
      synchronized(items) {
        // Progress/status chatter is worthless by the time the user can hear it — the completion or
        // the current state supersedes it. Dropping it here is what keeps the replay to one line.
        if (!urgent(kind) && kind != "complete") return false
        if (kind == "complete") items.removeAll { it.kind == "complete" }
        if (urgent(kind)) items.add(0, Held(kind, nudge)) else items.add(Held(kind, nudge))
        // Trim from the back: the front is urgent, and the newest complete is already deduped.
        while (items.size > max) items.removeAt(items.size - 1)
        true
      }

  /** Take everything held, in delivery order (urgent first), and clear. */
  fun drain(): List<Held> = synchronized(items) { items.toList().also { items.clear() } }

  fun clear() = synchronized(items) { items.clear() }
}
