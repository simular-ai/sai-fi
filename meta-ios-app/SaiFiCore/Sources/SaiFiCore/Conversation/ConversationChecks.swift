/* sai-fi — voice concierge. */

// The conversation-harness checks, registered so `saifi-check` runs them with no XCTest.
//
// Ported from Android QueueConversationTest / AbortConversationTest / BargeInConversationTest /
// LongConversationTest / TimingMatrixTest. DemoFlow, LiveAgent, and the paid eval tiers stay out.

import Foundation

func conversationChecks() -> [Check] {
  queueConversationChecks()
    + abortConversationChecks()
    + bargeInConversationChecks()
    + longConversationChecks()
    + timingMatrixChecks()
}

func conversationTask(summary: String, doneAfterMs: Int64) -> [AgentBeat] {
  [
    AgentBeat(afterMs: 20, event: .status(.processing)),
    AgentBeat(afterMs: doneAfterMs, event: .complete(summary: summary)),
  ]
}
