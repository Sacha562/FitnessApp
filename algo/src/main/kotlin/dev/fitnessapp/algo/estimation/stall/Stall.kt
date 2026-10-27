package dev.fitnessapp.algo.estimation.stall

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Erfc
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.LocalDate

enum class StallStatus(val code: String) {
    INSUFFICIENT_DATA("insufficient_data"), PROGRESSING("progressing"), STALL("stall"), REGRESS("regress")
}

enum class TierAction(val code: String) { NONE("none"), TIER1("tier1"), TIER2("tier2") }

/**
 * One exposure of an exercise and side as STALL-1 reads it (B14.1): its date, whether it counts (a valid exposure
 * without pain, `feeling_bad` or `too_tired`, not in a deload week, not a probe) and the SSE-1 session residual
 * `zbar - L_pred` (null when the session had no prior state).
 */
data class StallExposure(val date: LocalDate, val counts: Boolean, val residual: Double?)

/** One exposure for the fail counter (B9.7): valid, and failed (a set below its target with `q_true <= 0.5`). */
data class FailExposure(val valid: Boolean, val failed: Boolean)

/** The per-exercise evaluation of B14.1. [event] = a transition into STALL or REGRESS at this evaluation. */
data class StallEvaluation(
    val status: StallStatus,
    val g: Double,
    val sdG: Double,
    val pProg: Double,
    val nExposures: Int,
    val event: Boolean,
    val tierAction: TierAction,
)

/** The global trigger of B14.2 (feeds the one DeloadPolicy of `targets`). */
data class GlobalTrigger(val triggers: List<String>, val recommended: Boolean)

/**
 * STALL-1 (ALGORITHMS.md B14, C13, C15): stall and regression per exercise and side from SSE-1's trend, the tiered
 * actions, the fail counter (the fast path specified with RX-1, B9.7) and the global deload trigger. RULE items are
 * evaluated by the caller in shadow and never acted on.
 */
object Stall : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("stall")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("stall.fail_threshold", "exposures", 2.0, 4.0),
        ParamSpec.number("stall.fail_load_cut", "ratio", 0.025, 0.15),
        ParamSpec.integer("stall.global_active_window_days", "days", 14.0, 56.0),
        ParamSpec.integer("stall.global_bad_events", "events", 2.0, 10.0),
        ParamSpec.number("stall.global_delta", "ln", -0.1, 0.0),
        ParamSpec.integer("stall.global_delta_sessions", "of 4 sessions", 2.0, 4.0),
        ParamSpec.number("stall.global_frac", "share", 0.2, 0.8),
        ParamSpec.integer("stall.global_min_active", "exercises", 3.0, 15.0),
        ParamSpec.integer("stall.global_window_days", "days", 7.0, 28.0),
        ParamSpec.integer("stall.min_exposures", "exposures", 3.0, 10.0),
        ParamSpec.number("stall.p_progress_max", "probability", 0.05, 0.5),
        ParamSpec.integer("stall.regress_resid_count", "of 4 exposures", 2.0, 4.0),
        ParamSpec.number("stall.slope_progress", "ln per week", 0.001, 0.01),
        ParamSpec.number("stall.slope_regress", "ln per week", -0.02, 0.0),
        ParamSpec.number("stall.slope_stall", "ln per week", 0.0, 0.005),
        ParamSpec.number("stall.tier1_load_cut", "ratio", 0.0, 0.2),
        ParamSpec.integer("stall.tier1_q_add", "RIR", 0.0, 3.0),
        ParamSpec.number("stall.tier1_sets_mult", "ratio", 0.3, 0.9),
        ParamSpec.integer("stall.tier2_rep_shift", "reps", 2.0, 8.0),
        ParamSpec.integer("stall.tier2_weeks", "weeks", 4.0, 6.0),
        ParamSpec.integer("stall.tier_window_weeks", "weeks", 4.0, 16.0),
        ParamSpec.integer("stall.window_days", "days", 21.0, 84.0),
    )

    /**
     * B14.1 for one exercise and side on [today]: [g] and [pgg] are SSE-1's trend and its variance, [exposures] the
     * exposures (any order), [previousStatus] the status of the last evaluation, [eventDates] the dates of earlier
     * stall events and [exposuresSinceAction] the counted exposures since the last tier action (null = no action yet).
     */
    fun evaluate(
        g: Double,
        pgg: Double,
        exposures: List<StallExposure>,
        today: LocalDate,
        previousStatus: StallStatus?,
        eventDates: List<LocalDate>,
        exposuresSinceAction: Int?,
        params: ParamView,
    ): StallEvaluation {
        val window = params.int("stall.window_days")
        val e = exposures.filter { it.counts && Week.calendarDays(it.date, today).let { d -> d >= 0 && d < window } }
            .sortedByDescending { it.date }
        val sdG = Det.sqrt(maxOf(pgg, 0.0))
        val pProg = if (sdG == 0.0) (if (g > params.double("stall.slope_progress")) 1.0 else 0.0)
        else 1.0 - Erfc.phi((params.double("stall.slope_progress") - g) / sdG)
        val minExposures = params.int("stall.min_exposures")
        val negatives = e.take(4).count { (it.residual ?: 0.0) < 0.0 }
        val status = when {
            e.size < minExposures -> StallStatus.INSUFFICIENT_DATA
            g < params.double("stall.slope_regress") && negatives >= params.int("stall.regress_resid_count") -> StallStatus.REGRESS
            g < params.double("stall.slope_stall") && pProg < params.double("stall.p_progress_max") -> StallStatus.STALL
            else -> StallStatus.PROGRESSING
        }
        val stalled = status == StallStatus.STALL || status == StallStatus.REGRESS
        val wasStalled = previousStatus == StallStatus.STALL || previousStatus == StallStatus.REGRESS
        val enoughSinceAction = exposuresSinceAction == null || exposuresSinceAction >= minExposures
        val event = stalled && !wasStalled && enoughSinceAction
        val tierWindow = params.int("stall.tier_window_weeks") * 7L
        val recent = eventDates.count { Week.calendarDays(it, today).let { d -> d >= 0 && d < tierWindow } }
        val action = when {
            !event -> TierAction.NONE
            recent == 0 -> TierAction.TIER1
            else -> TierAction.TIER2
        }
        return StallEvaluation(status, g, sdG, pProg, e.size, event, action)
    }

    /** The fail counter (B9.7): consecutive failed valid exposures, newest first; invalid exposures are skipped. */
    fun failCounter(exposuresNewestFirst: List<FailExposure>): Int {
        var n = 0
        for (x in exposuresNewestFirst) {
            if (!x.valid) continue
            if (x.failed) n++ else break
        }
        return n
    }

    /** True when the fail counter reached `stall.fail_threshold`: the next exposure takes the fast-path load cut. */
    fun fastPath(counter: Int, params: ParamView): Boolean = counter >= params.int("stall.fail_threshold")

    /** The tier-2 rep range (B14.1): shifted down by `stall.tier2_rep_shift` when `r_lo - shift >= 3`, else up. */
    fun tier2Range(rLo: Int, rHi: Int, params: ParamView): Pair<Int, Int> {
        val shift = params.int("stall.tier2_rep_shift")
        return if (rLo - shift >= 3) (rLo - shift) to (rHi - shift) else (rLo + shift) to (rHi + shift)
    }

    /**
     * The global trigger (B14.2): [activeStatuses] holds, per active exercise (`>= 2` valid exposures in the active
     * window), whether it was STALL or REGRESS at some evaluation in the last `stall.global_window_days`;
     * [sessionDeltas] the session readiness offsets of the last sessions, newest first; [badEvents] the
     * `feeling_bad` + `too_tired` count in the window.
     */
    fun global(activeStatuses: List<Boolean>, sessionDeltas: List<Double>, badEvents: Int, params: ParamView): GlobalTrigger {
        val triggers = ArrayList<String>()
        if (activeStatuses.size >= params.int("stall.global_min_active") &&
            activeStatuses.count { it }.toDouble() / activeStatuses.size >= params.double("stall.global_frac")
        ) triggers += "T1"
        if (sessionDeltas.take(4).count { it <= params.double("stall.global_delta") } >= params.int("stall.global_delta_sessions")) triggers += "T2"
        if (badEvents >= params.int("stall.global_bad_events")) triggers += "T3"
        return GlobalTrigger(triggers, triggers.isNotEmpty())
    }
}
