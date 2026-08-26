/* sai-fi — voice concierge. */

// One scripted "brain" standing in for the live voice model, covering every move the golden scenarios
// exercise. Keyed on state + utterance, deterministic.
//
// Speech rides on `say`; `askAndWait` is the state signal only.
//
// Ported from the Android `fsm/GoldenHarness.kt`. This one IS transcribed rather than shared as a
// fixture, and it has to be: it is the input to the catalog, not an output of it, so there is nothing
// to serialise. The protection is that a divergence here changes the replayed trace and fails
// immediately — the brain cannot drift quietly the way the assertions could.

import Foundation

public func goldenBrain(input: DecisionInput, state: ConciergeState) -> [Effect] {
  switch input {

  case .approvalTimeout:
    return [.say(text: "heads up — this is about to time out")]

  case .agent(let event):
    switch event {
    case .approvalRequest(let request):
      let options = request.options
      // A CHOICE is not a yes/no: present the options and ask which — never a bare "okay to
      // proceed?". Resolve via chooseOption, not approve/deny.
      if request.approvalType == "choice", let options, !options.isEmpty {
        return [
          .say(text: "You can pick: \(options.map(\.label).joined(separator: ", ")). Which one?"),
          .askAndWait(question: "Which one?", waitingFor: .input),
        ]
      }
      // A link-only step can't be taken by voice — point the user at the app and DON'T resolve; the
      // browser completes it out of band.
      if request.isLinkOnly {
        return [.say(text: "Go ahead and enter that in the app — I can't take it by voice.")]
      }
      return [
        .say(text: "Okay to go ahead?"),
        .askAndWait(question: "Okay to go ahead?", waitingFor: .approval),
      ]

    case .complete(let summary):
      return [.say(text: summary ?? "All done.")]

    case .error:
      return [.say(text: "Ran into an error.")]

    case .approvalResolved:
      return [.say(text: "Got it — already handled.")]

    default:
      return [.noop]
    }

  case .user(let utterance):
    let u = utterance.lowercased()

    if state.mode == .awaitingUser, state.awaiting == .approval {
      return (u.contains("yes") || u.contains("go ahead"))
        ? [.approve]
        : [.deny(reason: "user declined")]
    }

    if state.mode == .clarifying {
      return [.forwardToAgent(text: "fix \(u)")]
    }

    if state.mode == .negotiating {
      if u.contains("now") {
        return [
          .say(text: "switching now"),
          .interrupt(scope: .everything),
          .forwardToAgent(text: "check email"),
        ]
      }
      return [
        .say(text: "sure, right after this"),
        .enqueue(task: "check email", urgency: .normal),
        .setState(mode: .working),
      ]
    }

    if u == "fix it" {
      return [
        .say(text: "What should I fix?"),
        .askAndWait(question: "What should I fix?", waitingFor: .clarification),
      ]
    }

    // A "relay: …" utterance steers the RUNNING turn rather than starting a fresh task. Also fires
    // while awaiting-user: when the agent asked a free-text question via an approval, the user simply
    // answering it IS a steer — the yes/no branch above already claimed the case where the approval is
    // a real approve/deny.
    if u.hasPrefix("relay:"), state.mode == .working || state.mode == .awaitingUser {
      // Substring after the FIRST colon, trimmed — matching Kotlin's indexOf(':').
      let afterColon = utterance[utterance.index(after: utterance.firstIndex(of: ":")!)...]
      return [.relayToAgent(answer: afterColon.trimmingCharacters(in: .whitespacesAndNewlines))]
    }

    if state.mode == .working {
      return [
        .say(text: "I'm mid-task — switch now, or after?"),
        .askAndWait(question: "Now or after?", waitingFor: .urgency),
      ]
    }

    return [.forwardToAgent(text: utterance)]
  }
}

/// A glasses capture sitting on the bridge, waiting for the next write.
public func goldenPhoto(_ name: String) -> TaskAttachment {
  TaskAttachment(
    path: "uploads/\(name)",
    name: name,
    mime: "image/jpeg",
    size: 1024,
    downloadUrl: "https://storage.example/\(name)")
}
