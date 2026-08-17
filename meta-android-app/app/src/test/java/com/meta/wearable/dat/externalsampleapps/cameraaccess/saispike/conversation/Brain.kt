/* sai-fi — voice concierge. */

// The model, behind one seam.
//
// [ScriptedBrain] is deterministic and free, and is what gates CI. A live brain calling the real
// model slots in behind the same interface, which is the point of having one: the scenarios do not
// know which is behind it, so the same conversation can be run for its structure (deterministic) and
// for its wording (judged).
//
// A brain sees the FSM's state because the real one effectively does — the model is told what is
// running and what is waiting via `session-state` and `getSaiStatus`, so a scripted brain that had
// to answer "what's going on?" blind would be a worse model than the real one, not a simpler one.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.conversation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import org.json.JSONArray
import org.json.JSONObject

/**
 * One model turn: what it says out loud, and what it calls.
 *
 * Both are optional and the combinations are meaningful. Speech with no calls is a plain reply;
 * calls with no speech is the silent forward the prompt asks for; neither is a correctly empty turn
 * (an overheard remark, or a muted turn).
 */
data class BrainTurn(val speech: String? = null, val calls: JSONArray = JSONArray())

interface Brain {
  suspend fun turn(input: String, state: ConciergeState): BrainTurn
}

/** `fc("forwardToAgent", "text" to "check my email")` — one function call, as the model emits it. */
fun fc(name: String, vararg args: Pair<String, Any?>): JSONObject =
    JSONObject().put("name", name).apply {
      if (args.isNotEmpty()) {
        put("args", JSONObject().apply { args.forEach { (k, v) -> put(k, v) } })
      }
    }

fun callsOf(vararg calls: JSONObject) = JSONArray().apply { calls.forEach { put(it) } }

/**
 * A brain whose rules are tried in order, first match wins.
 *
 * Matching on the input rather than a fixed turn sequence, because the loop decides how many turns
 * there are: a nudge the gate held and released later arrives as an extra input that no fixed script
 * could have predicted the position of.
 */
class ScriptedBrain(private val rules: List<Rule> = emptyList()) : Brain {

  data class Rule(
      val match: (String) -> Boolean,
      val reply: (String, ConciergeState) -> BrainTurn,
  )

  /** Inputs the brain was given, in order — including every nudge, which is often the assertion. */
  val seen = mutableListOf<String>()

  override suspend fun turn(input: String, state: ConciergeState): BrainTurn {
    seen += input
    rules.firstOrNull { it.match(input) }?.let {
      return it.reply(input, state)
    }
    // The FSM's `say` reaches the model wrapped in "say this verbatim", and a real model says it.
    // Built in rather than left to each scenario because it is mechanical, not a judgment: a test
    // that had to restate it every time would be restating the contract LiveVoiceChannel already has.
    verbatim(input)?.let {
      return BrainTurn(speech = it)
    }
    return BrainTurn()
  }

  /** Nudges the model was told about, by their `[agent]` / `[system]` prefix. */
  fun sawNudgeContaining(fragment: String) = seen.any { it.contains(fragment) }

  companion object {
    /** The text inside LiveVoiceChannel's verbatim wrapper, or null if this is not one. */
    fun verbatim(input: String): String? =
        Regex("""Say to the user, briefly and verbatim: "(.*)"""", RegexOption.DOT_MATCHES_ALL)
            .find(input)
            ?.groupValues
            ?.get(1)

    fun of(vararg rules: Rule) = ScriptedBrain(rules.toList())

    /**
     * An input the client injected rather than something the user said.
     *
     * The distinction is load-bearing for test authors, not decoration. A completion nudge quotes the
     * agent's summary back at the model, so a rule matching "email" written to catch the user asking
     * about email ALSO catches the nudge reporting "3 new emails" — and replies by forwarding the
     * task again, forever. That is a bug in the test, not in the product, and it is easy enough to
     * write that the two kinds of input are kept apart here rather than in every scenario.
     */
    fun isNudge(input: String) = input.startsWith("[agent]") || input.startsWith("[system]")

    /** `on({ it.contains("email") }) { … }` — matches any input, nudges included. */
    fun on(match: (String) -> Boolean, reply: (String, ConciergeState) -> BrainTurn) =
        Rule(match, reply)

    /** Match something the USER said, case-insensitively. Never matches an injected nudge. */
    fun whenSaid(fragment: String, reply: (String, ConciergeState) -> BrainTurn) =
        Rule({ !isNudge(it) && it.contains(fragment, ignoreCase = true) }, reply)

    /** Match a nudge the client injected — a completion, an approval, a mute. */
    fun whenNudged(fragment: String, reply: (String, ConciergeState) -> BrainTurn) =
        Rule({ isNudge(it) && it.contains(fragment, ignoreCase = true) }, reply)

    /** Match any injected nudge. */
    fun whenNudged(reply: (String, ConciergeState) -> BrainTurn) = Rule({ isNudge(it) }, reply)
  }
}
