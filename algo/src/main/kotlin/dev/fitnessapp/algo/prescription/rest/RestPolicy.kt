package dev.fitnessapp.algo.prescription.rest

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry

/** DATA_MODEL E41; [code] is the stored spelling and the key of `rest.base`. */
enum class RestClass(val code: String) {
    STRENGTH_HEAVY("strength_heavy"),
    COMPOUND_LOWER("compound_lower"),
    COMPOUND_UPPER("compound_upper"),
    ISOLATION("isolation"),
    HOLD("hold"),
    CORE("core");

    companion object {
        fun of(code: String): RestClass = requireNotNull(entries.firstOrNull { it.code == code }) { "unknown rest_class $code" }
    }
}

/** How the two sides of a unilateral exercise are worked (B11.2 "Unilateral"). */
enum class SideMode { NONE, SEQUENTIAL, ALTERNATING }

enum class RestSource(val code: String) { WARMUP("warmup"), CHALLENGE("challenge"), USER("user"), ENGINE("engine") }

/**
 * REST-1 input for the rest after one logged set (B11.1). [p] is `w / M` of the prescription; the last set's
 * [lastQTrue], [lastReps] against [lastTarget] and the target RIR [qStar] drive the outcome modifiers, [fNext] (FAT-1
 * before the next set) the fatigue modifier, and [adaptedBaseS] replaces the class base after the nightly adaptation.
 * The user's rest preference is [userRestPrefS] (seconds, replaces the base) or [userRestMult] (scales the class base,
 * D50); at most one is set.
 * Without them the decision is the base policy of `rest@1.0.0` (D21).
 */
data class RestInput(
    val restClass: RestClass,
    val nextIsWarmup: Boolean = false,
    val prevWasWarmup: Boolean = false,
    /** The WARMUP-1 rest for this gap ([dev.fitnessapp.algo.prescription.warmup.WarmupRest]). */
    val warmupRestS: Double? = null,
    val challengeRestS: Double? = null,
    val challengeBetweenSidesS: Double? = null,
    val targetReps: Int? = null,
    val p: Double? = null,
    val userRestPrefS: Double? = null,
    val userRestMult: Double? = null,
    val sideMode: SideMode = SideMode.NONE,
    val lastQTrue: Double? = null,
    val lastReps: Int? = null,
    val lastTarget: Int? = null,
    val qStar: Double? = null,
    val fNext: Double? = null,
    val adaptedBaseS: Double? = null,
)

/** One timed set of a class for the nightly adaptation (B11.4): actual rest, the timer it had and its residual. */
data class AdaptSet(val restBeforeS: Double, val timerS: Double, val resid: Double?)

/** `rest.adapt` outputs (B11.4, DATA_MODEL 10.2); [baseNewS] equals [baseOldS] when nothing changed. */
data class RestAdapt(val restClass: RestClass, val baseOldS: Double, val baseNewS: Double, val earlyShare: Double, val residMean: Double?, val nSets: Int)

data class RestDecision(
    val timerS: Double,
    /** Alternating sides: the pause between the two sides (0 = "switch now"); null otherwise. */
    val betweenSidesS: Double?,
    val baseS: Double,
    val source: RestSource,
    /** Trace codes of the applied rules, in application order. */
    val steps: List<String>,
    /** C18: UI adjustments stop here (`rest.timer_hard_max_s`). */
    val hardMaxS: Double,
)

/**
 * REST-1 (ALGORITHMS.md B11, C18) as `rest@1.2.0`: warm-up rests, challenge item values, the user's
 * `rest_pref_s` or `rest_pref_mult` (D50, new in 1.2.0), the class table `rest.base` with the strength-heavy rule, the outcome and fatigue modifiers and the
 * clamps (`decide`); the alternating pair and superset formulas, the live readout and the nightly adaptation.
 * `rest@1.0.0` (D21) was the base policy alone; with no last-set inputs `decide` still gives exactly that.
 */
object RestPolicy : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("rest")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("rest.adapt_cooldown_days", "days", 1.0, 28.0),
        ParamSpec.number("rest.adapt_early_frac", "share", 0.5, 1.0),
        ParamSpec.number("rest.adapt_early_s", "s", 5.0, 60.0),
        ParamSpec.table("rest.adapt_floor", "s", 30.0, 240.0),
        ParamSpec.integer("rest.adapt_min_sets", "sets", 5.0, 30.0),
        ParamSpec.number("rest.adapt_resid_bad", "reps", -3.0, 0.0),
        ParamSpec.number("rest.adapt_resid_ok", "reps", -2.0, 0.0),
        ParamSpec.number("rest.adapt_step_s", "s", 5.0, 30.0),
        ParamSpec.table("rest.base", "s", 30.0, 300.0),
        ParamSpec.number("rest.between_sides_s", "s", 0.0, 120.0),
        ParamSpec.number("rest.max_s", "s", 180.0, 600.0),
        ParamSpec.number("rest.min_s", "s", 30.0, 120.0),
        ParamSpec.number("rest.mod_easy_rir_margin", "RIR", 1.0, 4.0),
        ParamSpec.number("rest.mod_easy_s", "s", 0.0, 60.0),
        ParamSpec.number("rest.mod_fatigue_f", "F", 0.1, 0.5),
        ParamSpec.number("rest.mod_fatigue_s", "s", 0.0, 60.0),
        ParamSpec.number("rest.mod_hard_rir", "RIR", 0.0, 1.5),
        ParamSpec.number("rest.mod_hard_s", "s", 0.0, 90.0),
        ParamSpec.number("rest.mod_short_reps", "reps", 0.0, 3.0),
        ParamSpec.number("rest.side_switch_s", "s", 0.0, 30.0),
        ParamSpec.number("rest.strength_heavy_max_reps", "reps", 3.0, 8.0),
        ParamSpec.number("rest.strength_heavy_min_p", "ratio of M", 0.75, 0.95),
        ParamSpec.number("rest.superset_between_s", "s", 0.0, 120.0),
        ParamSpec.number("rest.timer_hard_max_s", "s", 900.0, 900.0),
        ParamSpec.number("rest.user_max_s", "s", 600.0, 600.0),
        ParamSpec.number("rest.user_min_s", "s", 30.0, 30.0),
    )

    fun decide(input: RestInput, params: ParamView): RestDecision {
        val hardMax = params.double("rest.timer_hard_max_s")

        if (input.nextIsWarmup || input.prevWasWarmup) {
            val t = Grid.s(requireNotNull(input.warmupRestS) { "warm-up gap without a WARMUP-1 rest" })
            return RestDecision(t, null, t, RestSource.WARMUP, listOf("rest.warmup"), hardMax)
        }

        if (input.challengeRestS != null) {
            // C18 override: the challenge value exactly, no modifiers; only the hard maximum applies.
            val t = Grid.s(minOf(input.challengeRestS, hardMax))
            val between = input.challengeBetweenSidesS?.let { Grid.s(minOf(it, hardMax)) } ?: betweenSides(input, params)
            return RestDecision(t, between, t, RestSource.CHALLENGE, listOf("rest.challenge"), hardMax)
        }

        val userBase = input.userRestPrefS
        val mult = input.userRestMult.takeIf { userBase == null }
        val user = userBase != null || mult != null
        val steps = ArrayList<String>()
        val cls = effectiveClass(input, params)
        val base = if (userBase != null) {
            steps += "rest.user"
            userBase
        } else {
            steps += if (cls == input.restClass) "rest.class" else "rest.class.strength_heavy"
            if (input.adaptedBaseS != null) steps += "rest.adapted"
            val classBase = input.adaptedBaseS ?: params.table("rest.base").getValue(cls.code)
            // D50: a multiplier scales the (adapted) class base and makes it the user's base.
            if (mult != null) steps += "rest.user_mult"
            classBase * (mult ?: 1.0)
        }
        val mods = modifiers(input, params, steps)
        val timer = if (user) {
            clamp(base + mods, params.double("rest.user_min_s"), params.double("rest.user_max_s")) // D10: the user's base is kept
        } else {
            clamp(base + mods, params.double("rest.min_s"), params.double("rest.max_s"))
        }
        return RestDecision(
            Grid.s(minOf(timer, hardMax)), betweenSides(input, params), Grid.s(base),
            if (user) RestSource.USER else RestSource.ENGINE, steps, hardMax,
        )
    }

    /** The outcome modifier (hard or easy last set, exclusive) plus the fatigue modifier (B11.2). */
    private fun modifiers(input: RestInput, params: ParamView, steps: MutableList<String>): Double {
        var mods = 0.0
        val q = input.lastQTrue
        val short = input.lastReps != null && input.lastTarget != null && input.lastReps < input.lastTarget - params.double("rest.mod_short_reps")
        if ((q != null && q <= params.double("rest.mod_hard_rir")) || short) {
            mods += params.double("rest.mod_hard_s")
            steps += "rest.mod.hard"
        } else if (q != null && input.qStar != null && q >= input.qStar + params.double("rest.mod_easy_rir_margin")) {
            mods -= params.double("rest.mod_easy_s")
            steps += "rest.mod.easy"
        }
        if (input.fNext != null && input.fNext > params.double("rest.mod_fatigue_f")) {
            mods += params.double("rest.mod_fatigue_s")
            steps += "rest.mod.fatigue"
        }
        return mods
    }

    /**
     * Alternating sides (B11.2): the rest after the pair, so the first side still gets about [timerS] of rest;
     * [workSecondSideS] is TIME-1's duration of the second side's set (reps x tempo + 5 s).
     */
    fun pairTimer(timerS: Double, workSecondSideS: Double, params: ParamView): Double =
        Grid.s(maxOf(params.double("rest.min_s"), timerS - workSecondSideS - params.double("rest.side_switch_s")))

    /** Supersets (B11.2, F13): the rest after the pair, so each muscle gets roughly its own base. */
    fun supersetAfterPair(baseA: Double, baseB: Double, workA: Double, workB: Double, params: ParamView): Double {
        val between = params.double("rest.superset_between_s")
        return Grid.s(maxOf(params.double("rest.min_s"), maxOf(baseA - between - workB, baseB - between - workA)))
    }

    /** The superset's rest between its members (B11.2). */
    fun supersetBetween(params: ParamView): Double = Grid.s(params.double("rest.superset_between_s"))

    /**
     * The live readout (B11.3): reps at the target RIR [qStar] the next set allows after [elapsedS] of rest, from the
     * set just done (fatigue [f], [reps] against its capacity [cap]) and the fresh capacity [capFresh]; REP grid.
     */
    fun readout(capFresh: Double, f: Double, reps: Double, cap: Double, elapsedS: Double, qStar: Double, params: ParamView): Double =
        Grid.rep(Fatigue.predictNext(capFresh, f, reps, cap, elapsedS, Fatigue.constants(params)) - qStar)

    /**
     * The nightly adaptation of an engine base (B11.4) over the class's timed sets since its last change, oldest
     * first; null when it may not run (fewer than `rest.adapt_min_sets` sets, inside the cooldown, or a user
     * `rest_pref_s` for the scope; a multiplier scales the adapted base, so it does not stop the adaptation, D50).
     */
    fun adapt(cls: RestClass, baseS: Double, sets: List<AdaptSet>, daysSinceChange: Int?, userPreference: Boolean, params: ParamView): RestAdapt? {
        if (userPreference) return null
        if (sets.size < params.int("rest.adapt_min_sets")) return null
        if (daysSinceChange != null && daysSinceChange < params.int("rest.adapt_cooldown_days")) return null
        val last = sets.takeLast(params.int("rest.adapt_min_sets"))
        val early = last.count { it.restBeforeS <= it.timerS - params.double("rest.adapt_early_s") }.toDouble() / last.size
        val resids = last.drop(1).mapNotNull { it.resid }
        val resid = if (resids.isEmpty()) null else resids.sum() / resids.size
        val meanRest = last.sumOf { it.restBeforeS } / last.size
        val meanTimer = last.sumOf { it.timerS } / last.size
        val step = params.double("rest.adapt_step_s")
        val newBase = when {
            early >= params.double("rest.adapt_early_frac") && resid != null && resid >= params.double("rest.adapt_resid_ok") ->
                maxOf(params.table("rest.adapt_floor").getValue(cls.code), baseS - step)
            resid != null && resid <= params.double("rest.adapt_resid_bad") && meanRest >= meanTimer ->
                minOf(params.double("rest.max_s"), baseS + step)
            else -> baseS
        }
        return RestAdapt(cls, Grid.s(baseS), Grid.s(newBase), Grid.ratio(early), resid?.let { Grid.rep(it) }, last.size)
    }

    /** `strength_heavy` when the set is heavy (few reps or high %1RM), else the exercise's class. */
    fun effectiveClass(input: RestInput, params: ParamView): RestClass {
        val heavyReps = input.targetReps != null && input.targetReps <= params.double("rest.strength_heavy_max_reps")
        val heavyP = input.p != null && input.p >= params.double("rest.strength_heavy_min_p")
        return if (heavyReps || heavyP) RestClass.STRENGTH_HEAVY else input.restClass
    }

    private fun betweenSides(input: RestInput, params: ParamView): Double? =
        if (input.sideMode == SideMode.ALTERNATING) Grid.s(params.double("rest.between_sides_s")) else null

    private fun clamp(x: Double, lo: Double, hi: Double) = minOf(maxOf(x, lo), hi)
}
