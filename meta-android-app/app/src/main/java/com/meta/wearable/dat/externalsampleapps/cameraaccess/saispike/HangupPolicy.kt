/*
 * sai-fi — voice concierge (when to actually hang up).
 */

// Whether an `endCall` really ends the call, and whether a hangup already in flight should be
// aborted. A pure decision, so it can be tested without a device.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

/** What [HangupPolicy.decide] concluded about an `endCall`. */
sealed interface HangupAction {
  /**
   * Hang up, but keep audio running briefly so the spoken sign-off lands before it is cut. The
   * window is cancellable — see [HangupPolicy.shouldCancel].
   */
  data object EndAfterGoodbye : HangupAction

  /** Hang up now. Muted, there is no goodbye to hear and nothing for the user to talk over. */
  data object EndNow : HangupAction

  /** Do not hang up; ask instead. [why] is for the log, [nudge] is what she is told. */
  data class HoldAndAsk(val why: String, val nudge: String) : HangupAction

  /**
   * Do not hang up, and do not ask either — muted, so a confirmation could not be heard.
   *
   * Keeping the call up is still right: the user chose silence, not a hang-up, and the server's idle
   * guard ends an abandoned call on its own.
   */
  data class HoldSilently(val why: String) : HangupAction
}

/**
 * Whether an `endCall` is a farewell being answered, or a farewell being overheard.
 *
 * This is the client half of a rule the prompt already states — say goodbye BEFORE calling endCall —
 * and it exists because the model does not always obey it. The failure mode is the reason this is the
 * first piece of hangup logic to become testable: getting it wrong cuts the user off mid-sentence,
 * usually mid-sentence *with another human*, and costs the entire call. There is no recovering from
 * it and no retry; the call is simply gone.
 *
 * The evidence for that: a call once died on `⏻ endCall` with no farewell from either side and no
 * `you:` line at all. Nothing in the log could say why. Hence both the guard below and the evidence
 * line the service logs beside it.
 *
 * The guard fires ONCE per call. Hanging up by voice has to stay possible — a user who says "hang up"
 * twice means it — so the second `endCall` goes through whatever this concluded about the first.
 */
object HangupPolicy {
  /**
   * @param spokeThisTurn she produced audio during the turn that called `endCall`
   * @param lastUserSpeechAt elapsed-realtime ms of the user's last utterance, 0 if never
   * @param lastSaiSpeechAt elapsed-realtime ms of her last utterance, 0 if never
   * @param lastSaiText her last utterance, used only to tell "spoke" from "emitted nothing"
   * @param muted the user has muted her, so nothing she says can be heard
   * @param guardUsed the hold has already fired once this call
   */
  fun decide(
      spokeThisTurn: Boolean,
      lastUserSpeechAt: Long,
      lastSaiSpeechAt: Long,
      lastSaiText: String,
      muted: Boolean,
      guardUsed: Boolean,
  ): HangupAction {
    // "She said goodbye" is either audio in THIS turn, or a previous utterance that came after the
    // user last spoke — the shape of an answered farewell. Text matters as well as timing: an empty
    // last utterance is a turn that produced nothing, not a sign-off.
    val saidGoodbye = spokeThisTurn || (lastSaiSpeechAt > lastUserSpeechAt && lastSaiText.isNotBlank())
    val userAsked = lastUserSpeechAt != 0L

    if ((saidGoodbye && userAsked) || guardUsed) {
      return if (muted) HangupAction.EndNow else HangupAction.EndAfterGoodbye
    }

    val why =
        if (!userAsked) "the user hasn't said anything this call"
        else "she hasn't spoken since the user's last turn — no goodbye"
    if (muted) return HangupAction.HoldSilently(why)
    return HangupAction.HoldAndAsk(why, UNCONFIRMED_NUDGE)
  }

  /**
   * The user spoke while we were winding down, so they were not done — abort the hangup.
   *
   * [stragglerGuardMs] exists because transcription for the utterance that PRODUCED the goodbye can
   * still be arriving when the window opens. Cancelling on that would make a genuine "hang up"
   * impossible: her own farewell would keep re-opening the call.
   *
   * @param openedAt when the goodbye window opened, 0 if no hangup is pending
   */
  fun shouldCancel(openedAt: Long, now: Long, stragglerGuardMs: Long): Boolean {
    if (openedAt == 0L) return false
    return now - openedAt >= stragglerGuardMs
  }

  /** Told to her when a hang-up is held back: the call is still open, so ask rather than assume. */
  const val UNCONFIRMED_NUDGE =
      "[system] You called endCall, but you had not said goodbye and nothing the user said asked " +
          "you to hang up — so the call is STILL OPEN and nothing was ended. If you think you " +
          "heard a farewell, it was probably not aimed at you. Ask in ONE short line whether they " +
          "want you to hang up (\"did you want me to hang up?\") and wait for their answer. Do not " +
          "say goodbye and do not call endCall again unless they say yes."

  /** Told to her when the user talked over the goodbye window: carry on, do not sign off twice. */
  const val CANCELLED_NUDGE =
      "[system] You were about to hang up, but the user carried on talking, so the call is STILL " +
          "OPEN. Do not say goodbye again and do not end the call unless they clearly tell you to. " +
          "Just respond to what they said. If their earlier farewell was aimed at someone else, " +
          "treat it as overheard and stay out of it."
}
