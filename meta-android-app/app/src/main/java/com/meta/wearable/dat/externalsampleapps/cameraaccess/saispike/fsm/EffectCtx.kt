/* sai-fi — voice concierge. */

// The entire surface an effect handler gets.
//
// `state` is a live alias of the orchestrator's field, not a copy: a handler assigns to it and the
// next handler in the same batch sees the assignment. That is why it is a getter/setter pair here
// rather than a value — a captured snapshot would silently lose every mutation after the first.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/ctx.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

class EffectCtx(
    val agent: AgentBridge,
    val voice: VoiceChannel,
    private val getState: () -> ConciergeState,
    private val setState: (ConciergeState) -> Unit,
    /** Cancel the pre-expiry warning for the approval that is being resolved. */
    val clearApprovalTimer: () -> Unit,
    /**
     * Whether a relay should ALSO resolve the pending approval.
     *
     * An allowlist, not a denylist: true only for a free-text `user_input` question with no offered
     * options. It was a denylist once, and an `exec` "Command Approval Required" got silently
     * approved by a relay about a photo.
     */
    val relayResolvesApproval: () -> Boolean,
    /** Push the session projection to the client's activity log. */
    val publishSessionState: suspend () -> Unit,
    val log: (String) -> Unit = {},
) {
  var state: ConciergeState
    get() = getState()
    set(value) = setState(value)
}
