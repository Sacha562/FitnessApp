package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.params.ParamView
import java.time.Duration
import java.time.Instant

/** The prompts of F15 (UX 19.4); [code] is the `prompt` value of the `prompt_*` events. */
enum class Prompt(val code: String, val phase: String) {
    READINESS("readiness", "pre"),
    ELBOW_CHECK("elbow_check", "pre"),
    SORENESS("soreness", "pre"),
    SRPE("srpe", "post"),
    JOINT_PAIN("joint_pain", "post"),
    ;

    companion object {
        fun of(code: String): Prompt? = entries.firstOrNull { it.code == code }
    }
}

/** The You > Prompts switches (`profiles.prompt_*`, F15). */
data class PromptToggles(
    val readiness: Boolean = true,
    val elbowCheck: Boolean = true,
    val soreness: Boolean = true,
    val srpe: Boolean = true,
    val painAfter: Boolean = true,
)

/** What happened to one prompt in one earlier session, or a Reset from You > Prompts. */
enum class PromptOutcome { ANSWERED, SKIPPED, REDUCED, BUDGET, RESET }

/** One prompt's outcome in one session, in chronological order (rebuilt from the `prompt_*` events). */
data class PromptHistoryEntry(val prompt: Prompt, val outcome: PromptOutcome)

/** One prompt's adaptive state: reduced after [skipStreak] user skips, shown every `reduced_every` sessions. */
data class PromptAdaptive(val reduced: Boolean, val skipStreak: Int, val sinceShown: Int, val reducedAnswers: Int)

data class PromptInput(
    /** A home habit session: no pre-session prompts, only the pain prompt after it. */
    val home: Boolean,
    /** Planned direct fractional sets per group of the session to start (soreness candidates). */
    val plannedFs: Map<String, Double>,
    /** The last time each group was trained with direct sets (soreness asks only within `sore_window_h`). */
    val lastTrained: Map<String, Instant>,
    val now: Instant,
    val toggles: PromptToggles,
    val history: List<PromptHistoryEntry>,
    /** The newest pain report, for the pain guard (`pain_guard_d`). */
    val lastPainAt: Instant?,
    /** An active challenge has a pain rule: the elbow check and the pain prompt are never reduced or turned off. */
    val challengePainRule: Boolean,
    /** The session's template asks the elbow check (`requiresReadiness: ["elbow"]`). */
    val elbowRequired: Boolean = false,
    /** The elbow check was answered earlier today: it is not asked twice. */
    val elbowCheckedToday: Boolean = false,
)

/** One prompt to show; [group] for a soreness row; [reduced] when it is shown in reduced mode. */
data class PromptItem(val prompt: Prompt, val group: String? = null, val reduced: Boolean = false)

/** A candidate that is not shown, with the `prompt_skipped` cause (`budget` or `reduced`). */
data class PromptDropped(val prompt: Prompt, val group: String?, val cause: String)

data class PromptPlan(val pre: List<PromptItem>, val post: List<PromptItem>, val dropped: List<PromptDropped>, val taps: Int)

/**
 * `ui.prompts` (UI-07, F15, ALGORITHMS_PLANNING 19.3): which prompts a session asks before and after. Every prompt is one
 * expected tap; PRE + POST stay within `tap_budget` by dropping in reverse priority (pain > elbow check > sRPE >
 * readiness > soreness; soreness rows one group at a time, lowest planned FS first, ties the later group of the
 * canonical order first). The elbow check while a challenge pain rule is active and the pain prompt within
 * `pain_guard_d` of a pain report (or under a challenge pain rule) are guarded: never reduced, dropped or turned off.
 * The elbow kind follow-up at a score of 2 or more is a safety tap outside the budget (C69).
 */
object UiPrompts {
    fun plan(input: PromptInput, params: ParamView): PromptPlan {
        val budget = params.int("ui.prompts.tap_budget")
        val adaptive = adaptive(input.history, params)
        val everyN = params.int("ui.prompts.reduced_every")
        val painGuard = input.challengePainRule ||
            input.lastPainAt?.let { Duration.between(it, input.now) < Duration.ofDays(params.int("ui.prompts.pain_guard_d").toLong()) } == true
        val elbowGuard = input.challengePainRule || input.elbowRequired
        val dropped = ArrayList<PromptDropped>()

        // Candidates by priority (0 highest), each with whether it is guarded.
        data class Cand(val item: PromptItem, val priority: Int, val guarded: Boolean, val fs: Double = 0.0, val rank: Int = 0)
        val cands = ArrayList<Cand>()
        fun consider(p: Prompt, on: Boolean, guarded: Boolean, priority: Int, group: String? = null, fs: Double = 0.0, rank: Int = 0) {
            if (!on && !guarded) return
            val a = adaptive[p]
            if (!guarded && a != null && a.reduced && a.sinceShown + 1 < everyN) {
                dropped += PromptDropped(p, group, "reduced")
                return
            }
            cands += Cand(PromptItem(p, group, reduced = !guarded && a?.reduced == true), priority, guarded, fs, rank)
        }
        consider(Prompt.JOINT_PAIN, input.toggles.painAfter, painGuard, 0)
        if (!input.home) {
            if (!input.elbowCheckedToday) consider(Prompt.ELBOW_CHECK, input.toggles.elbowCheck, elbowGuard, 1)
            consider(Prompt.SRPE, input.toggles.srpe, false, 2)
            consider(Prompt.READINESS, input.toggles.readiness, false, 3)
            if (input.toggles.soreness) {
                val window = Duration.ofHours(params.int("ui.prompts.sore_window_h").toLong())
                val rank = Groups.CANONICAL.withIndex().associate { it.value to it.index }
                input.plannedFs.filter { (g, fs) -> fs > 0.0 && input.lastTrained[g]?.let { Duration.between(it, input.now) <= window } == true }
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { rank[it.key] ?: Int.MAX_VALUE })
                    .take(params.int("ui.prompts.sore_max"))
                    .forEach { (g, fs) -> consider(Prompt.SORENESS, true, false, 4, g, fs, rank[g] ?: Int.MAX_VALUE) }
            }
        }
        // Drop for the budget: lowest priority first; soreness rows by planned FS ascending, ties the later group first.
        val kept = cands.toMutableList()
        val dropOrder = cands.filter { !it.guarded }.sortedWith(
            compareByDescending<Cand> { it.priority }.thenBy { it.fs }.thenByDescending { it.rank },
        )
        for (c in dropOrder) {
            if (kept.size <= budget) break
            kept -= c
            dropped += PromptDropped(c.item.prompt, c.item.group, "budget")
        }
        val pre = kept.filter { it.item.prompt.phase == "pre" }.sortedWith(
            compareBy<Cand> { SHEET_ORDER.indexOf(it.item.prompt) }.thenByDescending { it.fs }.thenBy { it.rank },
        ).map { it.item }
        val post = kept.filter { it.item.prompt.phase == "post" }.sortedBy { SHEET_ORDER.indexOf(it.item.prompt) }.map { it.item }
        return PromptPlan(pre, post, dropped, kept.size)
    }

    /** The sheet order of UX 16.3 (readiness, elbow check, soreness) and the Summary's (session effort, pain). */
    private val SHEET_ORDER = listOf(Prompt.READINESS, Prompt.ELBOW_CHECK, Prompt.SORENESS, Prompt.SRPE, Prompt.JOINT_PAIN)

    /**
     * The adaptive state per prompt: [PromptOutcome.SKIPPED] `skip_streak` sessions in a row reduces a prompt to every
     * `reduced_every` sessions; `restore_after` answers while reduced restore it; a Reset restores it at once. Prompts not
     * shown for the budget leave the state as it is.
     */
    fun adaptive(history: List<PromptHistoryEntry>, params: ParamView): Map<Prompt, PromptAdaptive> {
        val skipStreak = params.int("ui.prompts.skip_streak")
        val restore = params.int("ui.prompts.restore_after")
        val out = sortedMapOf<Prompt, PromptAdaptive>()
        for (e in history) {
            val s = out[e.prompt] ?: PromptAdaptive(false, 0, 0, 0)
            out[e.prompt] = when (e.outcome) {
                PromptOutcome.RESET -> PromptAdaptive(false, 0, 0, 0)
                PromptOutcome.BUDGET -> s
                PromptOutcome.REDUCED -> s.copy(sinceShown = s.sinceShown + 1)
                PromptOutcome.ANSWERED -> if (s.reduced) {
                    if (s.reducedAnswers + 1 >= restore) PromptAdaptive(false, 0, 0, 0) else s.copy(sinceShown = 0, reducedAnswers = s.reducedAnswers + 1)
                } else s.copy(skipStreak = 0)
                PromptOutcome.SKIPPED -> if (s.reduced) s.copy(sinceShown = 0) else {
                    val n = s.skipStreak + 1
                    if (n >= skipStreak) PromptAdaptive(true, n, 0, 0) else s.copy(skipStreak = n)
                }
            }
        }
        return out
    }
}
