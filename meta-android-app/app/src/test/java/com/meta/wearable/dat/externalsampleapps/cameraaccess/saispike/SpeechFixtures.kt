/* sai-fi — voice concierge. */

// The golden fixtures for `fsm/Speech.kt` — every line the FSM itself produces.
//
// WHY THIS FILE EXISTS. Until it did, `Speech.kt` was pinned by nothing. The string goldens covered
// `ConciergeProtocol.kt` and `ActivityLog.kt`; the FSM golden catalog asserts effect and state
// traces and deliberately never asserts phrasing (`docs/VOICE_FSM.md`). So the fifteen lines the
// concierge speaks about its OWN queue — "I'll start that as soon as I'm done with…", "Dropped that
// one…", the interrupt scope question, RESELECT_NUDGE — could be reworded and every test stayed
// green. They are the same kind of load-bearing wording as the rest: each was found by hearing it
// fail on a real call, and two of the recorded regressions in that file are misclassifications
// between `say` and `instruct` that a user heard read aloud.
//
// It became urgent with the iOS port. `meta-ios-app/SaiFiCore` reimplements this file in Swift, and
// these fixtures are the only thing holding the two equal — the same job the fixtures used to do
// across cloud-api's TypeScript and this Kotlin, and the same drift they existed to catch.
//
// The `input` object carries a `fn` discriminator so a port can replay each case without reading
// this source. Constants have a null input and are keyed by their own name.
//
// Regenerate with SAI_REGEN_GOLDENS=1 — see RegenerateGoldensTest.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ApprovalOption
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.COULD_NOT_START_TASK
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.CONFIRM_RESET_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ConciergeState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_AWAKE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKE_FAILED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.MACHINE_WAKING
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.NOTHING_QUEUED_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.NOTHING_QUEUED_TO_RUSH_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.QUEUED_BEHIND_APPROVAL
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.QUEUE_POSITION
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.QueuedTask
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.RESELECT_NUDGE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.RESET_FAILED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.RESET_RATE_LIMITED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.ROTATED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.Urgency
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.cannotDropOneOfManyNudge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.cannotResetWhileBusy
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.droppedQueuedLine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.interruptScopeQuestion
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.matchQueued
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.noQueuedMatchNudge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.nothingRunningNudge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.queuedBehindTask
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.readBackList
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.relayIntoBlockedTurnNudge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.startingNowLine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.stoppedRunningLine
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.unattributableApprovalNudge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike.fsm.whichQueuedToRushNudge

/**
 * A request longer than TASK_ECHO_MAX (70), so every read-back path pins the truncation AND the
 * ellipsis it appends. `shorten` is private, so this is the only way to exercise it.
 */
private const val LONG_TASK =
    "book a table for four at the italian place on the corner for eight o'clock this evening please"

/** Whitespace the read-back is supposed to collapse — newlines and runs of spaces both. */
private const val MESSY_TASK = "  check   my\n\nemail  "

private fun jstrs(vararg items: String): Jv = jarr(items.map { jstr(it) })

private fun fnInput(fn: String, vararg entries: Pair<String, Jv>): Jv =
    jobj(*(arrayOf("fn" to jstr(fn)) + entries))

/** Every line `fsm/Speech.kt` produces, over a canonical set of inputs. */
fun speechLines(): List<Jv> =
    listOf(
        // ── constants: spoken verbatim ───────────────────────────────────────
        fixtureNamed("QUEUED_BEHIND_APPROVAL", QUEUED_BEHIND_APPROVAL),
        fixtureNamed("QUEUE_POSITION", QUEUE_POSITION),
        fixtureNamed("COULD_NOT_START_TASK", COULD_NOT_START_TASK),
        fixtureNamed("MACHINE_WAKING", MACHINE_WAKING),
        fixtureNamed("MACHINE_AWAKE", MACHINE_AWAKE),
        fixtureNamed("MACHINE_WAKE_FAILED", MACHINE_WAKE_FAILED),
        fixtureNamed("ROTATED", ROTATED),
        fixtureNamed("RESET_RATE_LIMITED", RESET_RATE_LIMITED),
        fixtureNamed("RESET_FAILED", RESET_FAILED),

        // ── constants: model context, never voiced ───────────────────────────
        fixtureNamed("RESELECT_NUDGE", RESELECT_NUDGE),
        fixtureNamed("NOTHING_QUEUED_TO_RUSH_NUDGE", NOTHING_QUEUED_TO_RUSH_NUDGE),
        fixtureNamed("NOTHING_QUEUED_NUDGE", NOTHING_QUEUED_NUDGE),
        fixtureNamed("CONFIRM_RESET_NUDGE", CONFIRM_RESET_NUDGE),

        // ── readBackList: the shape every other line is built out of ─────────
        speechCase("readBackList empty", fnInput("readBackList", "tasks" to jstrs()), readBackList(emptyList())),
        speechCase(
            "readBackList one",
            fnInput("readBackList", "tasks" to jstrs("check my email")),
            readBackList(listOf("check my email"))),
        speechCase(
            "readBackList two",
            fnInput("readBackList", "tasks" to jstrs("check my email", "book a table")),
            readBackList(listOf("check my email", "book a table"))),
        speechCase(
            "readBackList three",
            fnInput("readBackList", "tasks" to jstrs("a", "b", "c")),
            readBackList(listOf("a", "b", "c"))),
        speechCase(
            "readBackList collapses whitespace",
            fnInput("readBackList", "tasks" to jstrs(MESSY_TASK)),
            readBackList(listOf(MESSY_TASK))),
        speechCase(
            "readBackList truncates at 70 and appends an ellipsis",
            fnInput("readBackList", "tasks" to jstrs(LONG_TASK)),
            readBackList(listOf(LONG_TASK))),

        // ── queuedBehindTask ─────────────────────────────────────────────────
        speechCase(
            "queuedBehindTask short",
            fnInput("queuedBehindTask", "running" to jstr("check my email")),
            queuedBehindTask("check my email")),
        speechCase(
            "queuedBehindTask truncated",
            fnInput("queuedBehindTask", "running" to jstr(LONG_TASK)),
            queuedBehindTask(LONG_TASK)),

        // ── cannotResetWhileBusy: one clause per blocker, in a fixed order ───
        speechCase(
            "cannotResetWhileBusy running only",
            fnInput("cannotResetWhileBusy", "inFlight" to jstrs("check my email")),
            cannotResetWhileBusy(ConciergeState(inFlight = listOf("check my email")))),
        speechCase(
            "cannotResetWhileBusy queued only",
            fnInput("cannotResetWhileBusy", "queue" to jstrs("book a table")),
            cannotResetWhileBusy(
                ConciergeState(queue = listOf(QueuedTask("book a table", Urgency.NORMAL))))),
        speechCase(
            "cannotResetWhileBusy approval only",
            fnInput("cannotResetWhileBusy", "pendingApprovalId" to jstr("a1")),
            cannotResetWhileBusy(ConciergeState(pendingApprovalId = "a1"))),
        speechCase(
            "cannotResetWhileBusy all three",
            fnInput(
                "cannotResetWhileBusy",
                "inFlight" to jstrs("check my email", "read the news"),
                "queue" to jstrs("book a table"),
                "pendingApprovalId" to jstr("a1")),
            cannotResetWhileBusy(
                ConciergeState(
                    inFlight = listOf("check my email", "read the news"),
                    queue = listOf(QueuedTask("book a table", Urgency.NORMAL)),
                    pendingApprovalId = "a1"))),

        // ── droppedQueuedLine: singular and plural are different sentences ───
        speechCase(
            "droppedQueuedLine one",
            fnInput("droppedQueuedLine", "dropped" to jstrs("book a table")),
            droppedQueuedLine(listOf("book a table"))),
        speechCase(
            "droppedQueuedLine many",
            fnInput("droppedQueuedLine", "dropped" to jstrs("book a table", "read the news")),
            droppedQueuedLine(listOf("book a table", "read the news"))),

        // ── startingNowLine ──────────────────────────────────────────────────
        speechCase(
            "startingNowLine",
            fnInput("startingNowLine", "tasks" to jstrs("book a table")),
            startingNowLine(listOf("book a table"))),

        // ── stoppedRunningLine: names what starts next, or says nothing does ─
        speechCase(
            "stoppedRunningLine nothing waiting",
            fnInput("stoppedRunningLine", "stopped" to jstrs("check my email"), "queued" to jstrs()),
            stoppedRunningLine(listOf("check my email"), emptyList())),
        speechCase(
            "stoppedRunningLine with a queue",
            fnInput(
                "stoppedRunningLine",
                "stopped" to jstrs("check my email"),
                "queued" to jstrs("book a table", "read the news")),
            stoppedRunningLine(
                listOf("check my email"), listOf("book a table", "read the news"))),

        // ── interruptScopeQuestion: running and queued named SEPARATELY ──────
        speechCase(
            "interruptScopeQuestion running only",
            fnInput("interruptScopeQuestion", "running" to jstrs("check my email"), "queued" to jstrs()),
            interruptScopeQuestion(listOf("check my email"), emptyList())),
        speechCase(
            "interruptScopeQuestion queued only",
            fnInput("interruptScopeQuestion", "running" to jstrs(), "queued" to jstrs("book a table")),
            interruptScopeQuestion(emptyList(), listOf("book a table"))),
        speechCase(
            "interruptScopeQuestion both",
            fnInput(
                "interruptScopeQuestion",
                "running" to jstrs("check my email"),
                "queued" to jstrs("book a table")),
            interruptScopeQuestion(listOf("check my email"), listOf("book a table"))),

        // ── nothingRunningNudge: the empty branch is a different reading ─────
        speechCase(
            "nothingRunningNudge nothing waiting",
            fnInput("nothingRunningNudge", "queued" to jstrs()),
            nothingRunningNudge(emptyList())),
        speechCase(
            "nothingRunningNudge with a queue",
            fnInput("nothingRunningNudge", "queued" to jstrs("book a table")),
            nothingRunningNudge(listOf("book a table"))),

        // ── the remaining model-facing nudges ────────────────────────────────
        speechCase(
            "cannotDropOneOfManyNudge",
            fnInput("cannotDropOneOfManyNudge", "inFlight" to jstrs("check my email", "book a table")),
            cannotDropOneOfManyNudge(listOf("check my email", "book a table"))),
        speechCase(
            "whichQueuedToRushNudge",
            fnInput("whichQueuedToRushNudge", "queued" to jstrs("book a table", "read the news")),
            whichQueuedToRushNudge(listOf("book a table", "read the news"))),
        speechCase(
            "noQueuedMatchNudge",
            fnInput("noQueuedMatchNudge", "queued" to jstrs("book a table")),
            noQueuedMatchNudge(listOf("book a table"))),
        speechCase(
            "unattributableApprovalNudge with a prompt",
            fnInput(
                "unattributableApprovalNudge",
                "inFlight" to jstrs("check my email", "book a table"),
                "prompt" to jstr("Allow access to your calendar?")),
            unattributableApprovalNudge(
                listOf("check my email", "book a table"), "Allow access to your calendar?")),
        speechCase(
            "unattributableApprovalNudge with no prompt",
            fnInput("unattributableApprovalNudge", "inFlight" to jstrs("check my email", "book a table")),
            unattributableApprovalNudge(listOf("check my email", "book a table"), null)),
        speechCase(
            "unattributableApprovalNudge truncates a long prompt",
            fnInput(
                "unattributableApprovalNudge",
                "inFlight" to jstrs("check my email"),
                "prompt" to jstr(LONG_TASK)),
            unattributableApprovalNudge(listOf("check my email"), LONG_TASK)),

        // ── relayIntoBlockedTurnNudge: three branches, and the fenced prompt ─
        speechCase(
            "relayIntoBlockedTurnNudge with options",
            fnInput(
                "relayIntoBlockedTurnNudge",
                "pendingApprovalOptions" to
                    jarr(
                        jobj("value" to jstr("sms"), "label" to jstr("Text message")),
                        jobj("value" to jstr("app"), "label" to jstr("Authenticator app")))),
            relayIntoBlockedTurnNudge(
                ConciergeState(
                    pendingApprovalOptions =
                        listOf(
                            ApprovalOption("sms", "Text message"),
                            ApprovalOption("app", "Authenticator app"))))),
        speechCase(
            "relayIntoBlockedTurnNudge link-only",
            fnInput("relayIntoBlockedTurnNudge", "pendingApprovalLinkOnly" to jbool(true)),
            relayIntoBlockedTurnNudge(ConciergeState(pendingApprovalLinkOnly = true))),
        speechCase(
            "relayIntoBlockedTurnNudge with no options at all",
            fnInput("relayIntoBlockedTurnNudge"),
            relayIntoBlockedTurnNudge(ConciergeState())),
        speechCase(
            "relayIntoBlockedTurnNudge fences the pending prompt",
            fnInput(
                "relayIntoBlockedTurnNudge",
                "pendingApprovalPrompt" to jstr(INJECTION)),
            relayIntoBlockedTurnNudge(ConciergeState(pendingApprovalPrompt = INJECTION))),

        // ── matchQueued: an index, and the two ways it must say "no" ─────────
        matchCase("matchQueued exact", listOf("check my email", "book a table"), "book a table"),
        matchCase("matchQueued needle inside haystack", listOf("check my email"), "email"),
        matchCase("matchQueued haystack inside needle", listOf("email"), "check my email please"),
        matchCase("matchQueued first match wins", listOf("email one", "email two"), "email"),
        matchCase("matchQueued no match", listOf("check my email"), "book a table"),
        matchCase("matchQueued blank needle never matches", listOf("check my email"), "   "),
        matchCase("matchQueued case insensitive", listOf("Check My Email"), "CHECK MY EMAIL"),
    )

/** A constant: no input, keyed by its own name. */
private fun fixtureNamed(name: String, value: String): Jv =
    jobj("name" to jstr(name), "input" to Jv.Nul, "expected" to jstr(value))

private fun speechCase(name: String, input: Jv, expected: String): Jv =
    jobj("name" to jstr(name), "input" to input, "expected" to jstr(expected))

/** `matchQueued` returns an index, so its expected value is a number rather than a string. */
private fun matchCase(name: String, queue: List<String>, task: String): Jv =
    jobj(
        "name" to jstr(name),
        "input" to
            fnInput("matchQueued", "queue" to jarr(queue.map { jstr(it) }), "task" to jstr(task)),
        "expected" to
            jnum(matchQueued(queue.map { QueuedTask(it, Urgency.NORMAL) }, task).toLong()))
