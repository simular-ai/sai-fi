/* sai-fi — voice concierge. */

// Resolving a spoken machine name to a machine, and what to say about it. A pure decision, so it can
// be tested without a device. The reconnect it triggers stays in the service, where the socket is.
//
// Ported from Android `MachineSwitcher.kt`. `Machine` lives here rather than in the HTTP client
// because the switcher is the first (and currently only) pure consumer of it.

/// A Sai machine (VM) the user can target, from GET /v1/agents/machines.
public struct Machine: Sendable, Equatable {
  public var machineId: String
  public var name: String?
  /// VM state as the server stores it: `active` · `hibernated` · `hibernating` · `wakingup`, or nil
  /// when the machine has never reported one — read that as offline.
  public var status: String?
  /// Whether a hibernated machine can be woken remotely at all — a property of where it is hosted,
  /// not of what it is doing now.
  public var canWake: Bool

  public init(machineId: String, name: String? = nil, status: String? = nil, canWake: Bool = false) {
    self.machineId = machineId
    self.name = name
    self.status = status
    self.canWake = canWake
  }

  /// Display label for the picker.
  public var label: String {
    if let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return name }
    return machineId
  }

  /// Up and usable. Anything else — including an unrecognised state — is not.
  public var isActive: Bool { status == "active" }
}

/// What `MachineSwitcher.resolve` concluded about a `switchMachine` request.
public enum MachineSwitch: Equatable, Sendable {
  /// Nothing to switch to. `reply` goes back as the tool response.
  case noMachines(reply: String)
  /// No machine matched. `reply` names what the user actually has.
  case notFound(reply: String)
  /// Already there — a no-op, but one the user should hear about.
  case alreadyOn(reply: String)
  /// Switch, then answer with `reply`.
  case switchTo(machine: Machine, reply: String)
}

public enum MachineSwitcher {
  /// Match a spoken name against the user's machines.
  ///
  /// Exact (case-insensitive) first, then containment in EITHER direction — because speech gives both
  /// halves of the problem: "switch to studio" for a machine called "Studio Mac", and "my mac studio
  /// at home" for one called "Mac Studio". A single-direction `contains` handles one and not the
  /// other, and which one it misses depends on how the user happened to phrase it.
  public static func resolve(query: String, machines: [Machine], currentMachineId: String) -> MachineSwitch {
    let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
    if q.isEmpty || machines.isEmpty {
      return .noMachines(reply: "I don't have another machine to switch to.")
    }
    let match =
      machines.first { $0.label.caseInsensitiveCompare(q) == .orderedSame }
      ?? machines.first {
        $0.label.range(of: q, options: .caseInsensitive) != nil
          || q.range(of: $0.label, options: .caseInsensitive) != nil
      }
    guard let match else {
      let names = machines.map(\.label).joined(separator: ", ")
      return .notFound(reply: "I couldn't find a machine called \"\(q)\". You have: \(names).")
    }
    if match.machineId == currentMachineId {
      return .alreadyOn(reply: "You're already on \(match.label).")
    }
    // The session prompt's "active machine" context is set at call start and is now
    // stale — the Live session is deliberately NOT rebuilt (that would reset the conversation).
    // Reset the model's machine context through the tool response instead: it enters the model's
    // context without producing a spoken turn.
    return .switchTo(
      machine: match,
      reply: "Switched to \(match.label). "
        + "(Context update, not to be spoken aloud: the active machine for this session is now "
        + "\"\(match.label)\" — ignore any earlier context naming a different active machine.)")
  }

  /// The same correction, for a switch made from the UI picker rather than by voice.
  ///
  /// A tool call can carry the context update in its response; a button press has no response to
  /// carry it, so it goes in as a nudge. Both must say "not to be spoken aloud" — the user pressed a
  /// button and does not need to be told what they just did.
  public static func contextNudge(_ label: String) -> String {
    "[system] Context update (not to be spoken aloud unless the user asks): the active "
      + "machine for this session is now \"\(label)\" — ignore any earlier context "
      + "naming a different active machine."
  }
}
