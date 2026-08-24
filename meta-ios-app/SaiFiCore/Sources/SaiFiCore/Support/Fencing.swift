/* sai-fi — voice concierge. */

// The prompt-injection fence, in one place.
//
// Agent-derived text — titles, summaries, errors, notices — and queue-derived text are UNTRUSTED:
// they may echo web content the agent read. So every nudge puts the INSTRUCTION first and wraps the
// DATA in this fence. `docs/SAI_GLASSES_APP.md` §3 lists keeping it intact as a rule, and the
// goldens assert the payload arrives fenced.
//
// It is a constant rather than three literal quotes at each site for two reasons: escaping three
// quotes inside a Swift string is easy to get wrong by one, and a security control spelled out in
// twenty places drifts in one of them.

/// The data fence: three double quotes.
let fence = "\"\"\""

/// A single double quote, for the lines that quote a value inline rather than fencing it.
let q = "\""
