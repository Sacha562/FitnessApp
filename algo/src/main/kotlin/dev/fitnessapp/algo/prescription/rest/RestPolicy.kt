package dev.fitnessapp.algo.prescription.rest

import dev.fitnessapp.algo.core.det.Grid
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
 * REST-1 input for the rest after one logged set (B11.1), base policy (D21). [p] arrives with RX-1 (M3);
 * until then the strength-heavy rule uses the target reps only.
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
    val sideMode: SideMode = SideMode.NONE,
)

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
 * REST-1 (ALGORITHMS.md B11, C18) as `rest@1.0.0`, the base policy of D21: warm-up rests, challenge item
 * values, the user's `rest_pref_s`, the class table `rest.base` with the strength-heavy rule, and the clamps.
 * The outcome and fatigue modifiers, the alternating-pair and superset formulas (TIME-1), the live readout
 * and the nightly adaptation follow in M3 as `rest@1.1.0`; their params keys are declared already.
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
        val steps = ArrayList<String>()
        val timer = if (userBase != null) {
            steps += "rest.user"
            clamp(userBase, params.double("rest.user_min_s"), params.double("rest.user_max_s")) // D10: kept
        } else {
            val cls = effectiveClass(input, params)
            steps += if (cls == input.restClass) "rest.class" else "rest.class.strength_heavy"
            clamp(params.table("rest.base").getValue(cls.code), params.double("rest.min_s"), params.double("rest.max_s"))
        }
        val base = userBase ?: params.table("rest.base").getValue(effectiveClass(input, params).code)
        return RestDecision(
            Grid.s(minOf(timer, hardMax)), betweenSides(input, params), Grid.s(base),
            if (userBase != null) RestSource.USER else RestSource.ENGINE, steps, hardMax,
        )
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
