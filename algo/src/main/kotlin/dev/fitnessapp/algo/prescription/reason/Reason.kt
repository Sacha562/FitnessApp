package dev.fitnessapp.algo.prescription.reason

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.LocalDate

/** The session effect of a reason (B12.3 "Today" column); the AUTO-1 and SUB-1 parts are carried out by those modules. */
enum class SessionEffect(val code: String) {
    NONE("none"),
    AUTOREG_TRIGGER("autoreg_trigger"),
    TRANSFER_UP("transfer_up"),
    OFFER_LIGHTER("offer_lighter"),
    DROP_REMAINING("drop_remaining"),
    SUBSTITUTE("substitute"),
    END_EXERCISE("end_exercise"),
    GRIP_INFO("grip_info"),
    TECHNIQUE_ADJUST("technique_adjust"),
}

/**
 * One row of the REASON-1 table (B12.3) as flags the other modules read: [invalidatesExposure] (DOUBLE ladder and
 * fail counter, B9.6), [excludedFromStall] (STALL-1 exposure counts, B14.1), [excludedFromFailCounter] (B9.7),
 * [countsForRecovery] (the 3-in-14-days rule) and [offeredInSet] (offered on a logged set, not only on a skip).
 */
data class ReasonRow(
    val code: String,
    val session: SessionEffect,
    val invalidatesExposure: Boolean,
    val excludedFromStall: Boolean,
    val excludedFromFailCounter: Boolean,
    val countsForRecovery: Boolean,
    val offeredInSet: Boolean,
)

/** One exercise of today that the pain stop may remove: its catalog joint stress (`js_<joint>`, 0-3). */
data class JointLoad(val exerciseId: String, val jointStress: Map<String, Int>)

/**
 * REASON-1 (ALGORITHMS.md B12, C16): the fixed effect of every in-gym reason on today's session and on future
 * prescriptions. The estimator column and the missing-RIR imputation are [ReasonSemantics] (M3.1); this object
 * holds the rest of the table, the pain stop (D49) and the history rules that turn reasons into future effects.
 */
object Reason : DeclaresParams {
    private val ROWS: Map<String, ReasonRow> = listOf(
        ReasonRow("too_easy", SessionEffect.AUTOREG_TRIGGER, false, false, false, false, true),
        ReasonRow("too_hard", SessionEffect.AUTOREG_TRIGGER, false, false, false, false, true),
        ReasonRow("feeling_good", SessionEffect.TRANSFER_UP, false, false, false, false, true),
        ReasonRow("feeling_bad", SessionEffect.OFFER_LIGHTER, false, true, true, true, true),
        ReasonRow("too_tired", SessionEffect.OFFER_LIGHTER, false, true, true, true, true),
        ReasonRow("no_time", SessionEffect.DROP_REMAINING, true, true, true, false, false),
        ReasonRow("occupied", SessionEffect.SUBSTITUTE, true, true, true, false, false),
        ReasonRow("equipment_missing", SessionEffect.SUBSTITUTE, true, true, true, false, false),
        ReasonRow("pain", SessionEffect.END_EXERCISE, true, true, true, false, true),
        ReasonRow("grip_failure", SessionEffect.GRIP_INFO, true, false, true, false, true),
        ReasonRow("torso_rotation", SessionEffect.TECHNIQUE_ADJUST, false, false, false, false, true),
        ReasonRow("technique_breakdown", SessionEffect.TECHNIQUE_ADJUST, false, false, false, false, true),
        ReasonRow("other", SessionEffect.NONE, false, false, false, false, true),
        ReasonRow("none", SessionEffect.NONE, false, false, false, false, false),
    ).associateBy { it.code }

    val ref: ModuleRef = ModuleRegistry.ref("reason")

    override val paramSpecs: List<ParamSpec> = ReasonSemantics.paramSpecs + listOf(
        ParamSpec.integer("reason.busy_slot_hours", "h", 0.0, 3.0),
        ParamSpec.number("reason.busy_frac", "share", 0.2, 0.9),
        ParamSpec.integer("reason.busy_visits", "visits", 2.0, 12.0),
        ParamSpec.integer("reason.equipment_missing_days", "days", 7.0, 90.0),
        ParamSpec.integer("reason.equipment_missing_events", "events", 1.0, 5.0),
        ParamSpec.number("reason.feeling_bad_delta", "ln", -0.1, 0.0),
        ParamSpec.integer("reason.grip_days", "days", 7.0, 90.0),
        ParamSpec.integer("reason.grip_events", "events", 2.0, 10.0),
        ParamSpec.integer("reason.pain_followup_exposures", "exposures", 1.0, 6.0),
        ParamSpec.number("reason.pain_load_cut", "ratio", 0.05, 0.3),
        ParamSpec.number("reason.pain_min_q", "RIR", 1.0, 5.0),
        ParamSpec.integer("reason.pain_repeat_days", "days", 7.0, 42.0),
        ParamSpec.integer("reason.pain_repeat_events", "events", 2.0, 5.0),
        ParamSpec.integer("reason.pain_stop_js_min", "joint-stress level", 1.0, 3.0),
        ParamSpec.number("reason.pain_stop_score", "score 0-10", 1.0, 10.0),
        ParamSpec.integer("reason.recovery_days", "days", 7.0, 28.0),
        ParamSpec.integer("reason.recovery_events", "events", 2.0, 10.0),
        ParamSpec.number("reason.technique_next_cut", "ratio", 0.0, 0.1),
        ParamSpec.integer("reason.too_easy_exposures", "exposures", 1.0, 5.0),
        ParamSpec.number("reason.too_easy_b_step", "reps", 0.0, 1.0),
        ParamSpec.integer("reason.too_hard_exposures", "exposures", 1.0, 5.0),
    )

    /** The table row of [code]; an unknown code behaves like `other` without its estimator downweight (B12.3). */
    fun row(code: String?): ReasonRow = ROWS[code ?: "none"] ?: ROWS.getValue("other").copy(code = code!!)

    /**
     * The pain stop (B12.3, C64, D49): a score of at least `reason.pain_stop_score` removes, for the rest of today,
     * every remaining exercise with joint stress of at least `reason.pain_stop_js_min` on a joint of the region.
     */
    fun painStop(score: Double, regionJoints: List<String>, remaining: List<JointLoad>, params: ParamView): List<String> {
        if (score < params.double("reason.pain_stop_score")) return emptyList()
        val min = params.int("reason.pain_stop_js_min")
        return remaining.filter { e -> regionJoints.any { (e.jointStress[it] ?: 0) >= min } }.map { it.exerciseId }
    }

    /** The session offer of `feeling_bad` / `too_tired` (one card, D10): the load change and one set fewer. */
    fun lighterOffer(params: ParamView): Pair<Double, Int> = params.double("reason.feeling_bad_delta") to 1

    /**
     * `too_hard` future effect (B12.3): two consecutive exposures with `too_hard` raise `z_c` for the next
     * `reason.too_hard_exposures` exposures. [tooHard] lists the exposures of the exercise, newest first; the
     * result is the number of boosted exposures still due (0 = none).
     */
    fun tooHardBoostRemaining(tooHard: List<Boolean>, params: ParamView): Int {
        val n = params.int("reason.too_hard_exposures")
        for (i in 0 until tooHard.size - 1) {
            if (tooHard[i] && tooHard[i + 1]) return maxOf(0, n - i)
        }
        return 0
    }

    /** Pain follow-up (B12.3): active during the `reason.pain_followup_exposures` exposures after a pain event. */
    fun painFollowUp(exposuresSincePain: Int?, params: ParamView): Boolean =
        exposuresSincePain != null && exposuresSincePain < params.int("reason.pain_followup_exposures")

    /** The recovery check: `feeling_bad` + `too_tired` at least `reason.recovery_events` times within the window. */
    fun recoveryCheck(eventDates: List<LocalDate>, today: LocalDate, params: ParamView): Boolean =
        within(eventDates, today, params.int("reason.recovery_days")) >= params.int("reason.recovery_events")

    /** WE-S3 advice: `reason.pain_repeat_events` pain events in one body region within `reason.pain_repeat_days`. */
    fun painRepeat(regionEventDates: List<LocalDate>, today: LocalDate, params: ParamView): Boolean =
        within(regionEventDates, today, params.int("reason.pain_repeat_days")) >= params.int("reason.pain_repeat_events")

    /** "Mark as unavailable?" prompt: `equipment_missing` events within the window (never automatic). */
    fun equipmentMissingPrompt(eventDates: List<LocalDate>, today: LocalDate, params: ParamView): Boolean =
        within(eventDates, today, params.int("reason.equipment_missing_days")) >= params.int("reason.equipment_missing_events")

    /** WE-P10 (grip work): `grip_failure` events within the window. */
    fun gripPrompt(eventDates: List<LocalDate>, today: LocalDate, params: ParamView): Boolean =
        within(eventDates, today, params.int("reason.grip_days")) >= params.int("reason.grip_events")

    /**
     * Busy station (B12.3 `occupied`): busy in at least `reason.busy_frac` of the last `reason.busy_visits` visits at
     * the same weekday and hour (+/- `reason.busy_slot_hours`). [visits] are those visits, newest first.
     */
    fun busy(visits: List<Boolean>, params: ParamView): Boolean {
        val last = visits.take(params.int("reason.busy_visits"))
        if (last.size < params.int("reason.busy_visits")) return false
        return last.count { it }.toDouble() / last.size >= params.double("reason.busy_frac")
    }

    private fun within(dates: List<LocalDate>, today: LocalDate, days: Int): Int =
        dates.count { val d = Week.calendarDays(it, today); d >= 0 && d < days }
}
