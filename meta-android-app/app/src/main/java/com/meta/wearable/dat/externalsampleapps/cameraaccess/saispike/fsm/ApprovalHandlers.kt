/* sai-fi — voice concierge. */

// approve / approveAlways / deny / chooseOption.
//
// The security invariant is here: a pick must be an option that was actually offered. The selection
// is handed to the agent as the user's TRUSTED choice, so a value that was never on the table —
// hallucinated by the model, mistranscribed from speech — must not be able to resolve a guardrail.
// `allowOther` is the one exception, and it is explicit.
//
// Ported from cloud-api `services/concierge/voice/core/effect-handlers/approvals.ts`.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm

/**
 * Resolve a pending approval with a plain decision.
 *
 * With nothing pending this is a model misfire and is IGNORED — silently, with no state change and
 * nothing sent to the agent. Faking progress here would be worse: a task starts via forwardToAgent,
 * never via approve.
 */
suspend fun applyApprovalDecision(ctx: EffectCtx, effect: Effect) {
  val id = ctx.state.pendingApprovalId
  if (id == null) {
    ctx.log("approval decision with nothing pending — ignoring")
    return
  }

  val decision =
      when (effect) {
        is Effect.Deny -> ApprovalDecision.DENIED
        is Effect.ApproveAlways -> ApprovalDecision.APPROVED_ALWAYS
        else -> ApprovalDecision.APPROVED
      }

  // Link-only cards are completed by the user in the browser; the server rejects a resolution for
  // them. The FSM state still clears — the concierge is no longer waiting on a spoken answer.
  if (ctx.state.pendingApprovalLinkOnly != true) {
    ctx.agent.resolveApproval(id, decision)
  }

  ctx.clearApprovalTimer()
  ctx.state = ctx.state.noPendingApproval().copy(mode = Mode.WORKING, awaiting = null)
}

/**
 * Resolve a `choice` approval with the option(s) the user picked.
 *
 * A rejected pick — at this guard or at the bridge's own write boundary — keeps the request PENDING
 * and its timer running, and tells the model to re-present. It never says anything to the user: the
 * client has already tool-acked the call, so without a nudge the model would go on to confirm a pick
 * that never happened.
 */
suspend fun applyChooseOption(ctx: EffectCtx, effect: Effect.ChooseOption) {
  val id = ctx.state.pendingApprovalId
  if (id == null) {
    ctx.log("chooseOption with nothing pending — ignoring")
    return
  }

  val offered = ctx.state.pendingApprovalOptions
  if (offered != null && ctx.state.pendingApprovalAllowOther != true) {
    // Exact string equality against the option VALUE — not the label, not case-insensitive, not
    // trimmed. A single un-offered value rejects the whole call.
    val bad = effect.values.filter { v -> offered.none { it.value == v } }
    if (bad.isNotEmpty()) {
      ctx.log("chooseOption rejected, not offered: $bad")
      ctx.voice.instruct(RESELECT_NUDGE)
      return
    }
  }

  // Exactly one pick uses the singular field; two or more use the plural. A single-element list is
  // never sent as `selectedOptions` — that shape is what askChoice reads back.
  val selection =
      if (effect.values.size == 1) ApprovalSelection(selectedOption = effect.values.first())
      else ApprovalSelection(selectedOptions = effect.values)

  try {
    ctx.agent.resolveApproval(id, ApprovalDecision.APPROVED, selection)
  } catch (e: Exception) {
    // The bridge rejected it for something this guard cannot see — an un-offered value on a
    // multi-question choice, say. Keep the pending state AND the timer so the choice is still
    // resolvable, and tell the model to re-present.
    ctx.log("bridge rejected the selection: ${e.message}")
    ctx.voice.instruct(RESELECT_NUDGE)
    return
  }

  ctx.clearApprovalTimer()
  ctx.state = ctx.state.noPendingApproval().copy(mode = Mode.WORKING, awaiting = null)
}
