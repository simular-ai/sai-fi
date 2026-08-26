/* sai-fi — voice concierge. */

// Two ways to walk away from work in progress — hanging up, and changing machines — and one rule for
// both: ask first, and say where the results will be instead.
//
// A pure decision, so it can be tested without a device. The reason it is shared rather than written
// twice: the two paths lose work in exactly the same way, and the one that had no ask at all lost it
// silently. `applyMachineSwitch` builds a fresh VoiceSession, so the FSM — queue, in-flight turn,
// pending approval — is replaced wholesale. Work the user was PROMISED OUT LOUD disappears with
// nothing said, which is the hazard docs/VOICE_FSM.md §7 records for a dropped call, reachable by an
// ordinary voice command.
//
// Note what this does NOT do: it never stops anything. A task left running keeps running on the
// machine it started on, and the honest thing is to say so and point at the app, not to abort work
// nobody asked to cancel.
//
// Ported from Android `LeavingWorkPolicy.kt`.

/// Where the user is going, which is all that differs between the two questions.
public enum Leaving: Sendable {
  /// The call is ending. Results have nowhere to arrive.
  case call
  /// The machine is changing. The old machine keeps working; this call stops hearing about it.
  case machine
}

/// What `LeavingWorkPolicy.decide` concluded.
public enum LeavingWorkAction: Equatable, Sendable {
  /// Do not go yet. `nudge` is model-facing: it carries the facts and asks for a decision.
  case ask(nudge: String)
  /// Nothing outstanding, or the user has already been asked. Go.
  case proceed
}

public enum LeavingWorkPolicy {

  /// Ask before leaving work behind — once.
  ///
  /// One-shot for the same reason `applyInterrupt`'s scope question is: a user who says "hang up" or
  /// "switch to my laptop" twice means it, and a question that cannot be got past is a trap. The caller
  /// owns the flag so the two paths do not consume each other's ask.
  public static func decide(
    state: ConciergeState,
    leaving: Leaving,
    alreadyAsked: Bool,
    muted: Bool
  ) -> LeavingWorkAction {
    if alreadyAsked || muted { return .proceed }
    if !state.hasOutstandingWork() { return .proceed }
    return .ask(nudge: nudge(state, leaving))
  }

  /// The facts, and what to do with them. Model-facing, like `HangupPolicy.unconfirmedNudge` — the
  /// content varies with what is outstanding, so the phrasing is the model's and only the facts are
  /// ours.
  ///
  /// Running and queued are named SEPARATELY, the same rule `interruptScopeQuestion` follows: one is
  /// work in progress the user may not want to lose, the other has not happened at all, and reading
  /// them as one list describes a queued task as underway.
  private static func nudge(_ state: ConciergeState, _ leaving: Leaving) -> String {
    // Each clause stands on its own, and the subject of the first is YOU — the model. Written as
    // fragments hung off one lead-in ("they are …") they came out misattributing Sai's work to the
    // user, and an approval-only case rendered as "They are there's a request waiting on their answer".
    var clauses: [String] = []
    if !state.inFlight.isEmpty {
      clauses.append("you're still working on \(readBackList(state.inFlight))")
    }
    if !state.queue.isEmpty {
      clauses.append("\(readBackList(state.queue.map(\.text))) hasn't started yet")
    }
    if state.pendingApprovalId != nil {
      clauses.append("a request is waiting on their answer")
    }

    // Past participle: this slots into "you have NOT …", where a gerund reads as "you have not hanging
    // up".
    let going: String
    switch leaving {
    case .call: going = "hung up"
    case .machine: going = "moved to another machine"
    }
    // The part that makes the answer actionable, and the part that was missing: a task left running is
    // not lost, it is just somewhere else. Without this the choice reads as "abandon it or stay", and
    // the user picks staying for work they could simply have read later.
    let whereItGoes: String
    switch leaving {
    case .call:
      whereItGoes =
        "Anything left running keeps going on their machine — they just won't hear the result "
        + "here, and can pick it up in the Sai app."
    case .machine:
      whereItGoes =
        "Anything left running keeps going on the machine they're leaving — this call won't "
        + "hear the result once you move, and they can pick it up in the Sai app."
    }

    return "[system] NOTHING has happened yet — you have NOT \(going), and you must not until they "
      + "answer. Right now: \(clauses.joined(separator: "; ")). \(whereItGoes) "
      + "Tell them what's outstanding in ONE short line, and ask whether to stop it first or leave "
      + "it running. Then do what they say: to stop it, call interrupt; to leave it, ask again and "
      + "it will go through. Do not describe any of it as finished."
  }
}
