package dev.fitnessapp.algo.planning.weekplan

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.challenge.contract.ContractWindow
import dev.fitnessapp.algo.challenge.contract.GapRule
import dev.fitnessapp.algo.challenge.plan.BlockedDay
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.planning.allocator.AddOnSlot
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.targets.WeeklyTargets
import dev.fitnessapp.algo.targets.runs.Run
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A gym item the planner does not choose (8.2, 8.4): a logged session ([kind] `done`), a challenge session or test from
 * the calendar or `challenge.plan` (`challenge_session`, `test`), or a general gym event the user planned
 * (`planned_gym`). A future `planned_gym` is simulated as a general session of [plannedType] (every type when null,
 * "AUTO") within [budgetMin]; the other kinds act through [doses] (logged, or materialized for a challenge session).
 */
data class FixedItem(
    val key: String,
    val kind: String,
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val label: String,
    val slot: String? = null,
    val doses: List<Dose> = emptyList(),
    /** Credited quads + hamstrings + glutes FS of a general session (H6); 0 for challenge sessions. */
    val heavyLowerFs: Double = 0.0,
    /** Session tags of a general session (H1's pairwise check). */
    val tags: List<String> = emptyList(),
    val plannedType: SessionType? = null,
    val budgetMin: Double? = null,
    /** The add-on slot after a future challenge session (C34), when the session offers one. */
    val addOn: AddOnSpec? = null,
) {
    val general: Boolean get() = kind == KIND_DONE_GENERAL || kind == KIND_PLANNED_GYM

    companion object {
        const val KIND_CHALLENGE = "challenge_session"
        const val KIND_TEST = "test"
        const val KIND_PLANNED_GYM = "planned_gym"
        const val KIND_DONE = "done"
        const val KIND_DONE_GENERAL = "done_general"
    }
}

/** One family an add-on may train, with the allocator's slot (allowed groups from ELIG-1 with the slot filter). */
data class AddOnFamily(val type: SessionType, val slot: AddOnSlot)

/** The add-on after a challenge session: it starts at [start] (after the challenge block) and fits [capacityMin]. */
data class AddOnSpec(val start: Instant, val capacityMin: Double, val families: List<AddOnFamily>, val forbiddenSessionTags: Set<String> = emptySet())

/**
 * ELIG-1's group availability (12.4) for a general session: on a day without a challenge session or not ([gapDay], the
 * C50 light-pull filter), with the gap day's forbidden session tags and inside the given hard windows.
 */
fun interface GroupAvailability {
    fun of(gapDay: Boolean, gapTags: List<String>, hardWindowIds: List<String>): Map<String, Boolean>
}

/**
 * `today`'s own start and budget for the session it asks about (9.2: the time chip, or start = now). [anyTime] makes a day
 * without a free slot free at that start for `today`'s "Train anyway" options (D76).
 */
data class TodayOverride(val date: LocalDate, val start: Instant, val budgetMin: Double, val anyTime: Boolean = false)

/** The inputs of 8.2. [done] and [committed] are this week's Vhyp per group (3); [doses] the readiness doses of both. */
data class WeekPlanInput(
    val isoWeek: String,
    val monday: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    /** Availability per date (the user's windows inside the gym's opening hours). */
    val windows: Map<LocalDate, List<Interval>>,
    /** Busy calendar anchors that are not gym sessions. */
    val busy: List<Interval>,
    val fixed: List<FixedItem>,
    /**
     * The previous ISO week's logged general sessions (D74): only H6 and H1's pairwise check read them, so a heavy leg
     * day on Sunday keeps Monday free of heavy legs. They are no visits, add no volume and lock no day.
     */
    val priorGeneral: List<FixedItem> = emptyList(),
    /** `challenge.plan`'s rest days before a slot (scope `all_gym`) and gap days (scope `tags`). */
    val blocked: List<BlockedDay> = emptyList(),
    val gapRule: GapRule? = null,
    val protectedWindows: List<ContractWindow> = emptyList(),
    val ownedGroups: Set<String> = emptySet(),
    /** Session tags no general session may carry this week (the safety state and the challenge's mode). */
    val forbiddenSessionTags: Set<String> = emptySet(),
    val tagRules: List<SessionTagRule> = emptyList(),
    val runs: List<Run> = emptyList(),
    val runningPriority: String = "low",
    val targets: WeeklyTargets,
    val done: Map<String, Double>,
    val committed: Map<String, Double>,
    /** Sessions this week (done and committed) whose Vhyp of a group reaches `setcredit.trained_min_credit`. */
    val sessionsTraining: Map<String, Int>,
    val doses: List<Dose>,
    val maxVisits: Int,
    val defaultSessionMin: Double,
    val changeoverMin: Double,
    val availability: GroupAvailability,
    /** The day keys of the last plan of this ISO week ([WeeklyPlanner.dayKeys]), for the stability term. */
    val previous: Map<LocalDate, String> = emptyMap(),
    val provisional: Boolean = false,
    val todayOverride: TodayOverride? = null,
    val qBase: Double = 2.0,
)

/** One reason code with its parameters (0.6). */
data class ReasonCode(val code: String, val params: Map<String, String> = emptyMap())

data class PlanItem(
    val key: String,
    val kind: String,
    val slot: String?,
    val type: String?,
    val label: String,
    val start: Instant,
    val end: Instant,
    val estMinutes: Double,
    val direct: Map<String, Double> = emptyMap(),
    val credit: Map<String, Double> = emptyMap(),
    val rirOffset: Map<String, Int> = emptyMap(),
    val writePolicy: String,
    val source: String,
)

data class PlanDay(
    val date: LocalDate,
    val status: String,
    val items: List<PlanItem>,
    val restReason: ReasonCode?,
    /** The families a free day allows when not all four do (8.7), and whether pull is light pull only (C50). */
    val allowedFamilies: List<String>? = null,
    val lightPullOnly: Boolean = false,
    /** The free day's slot (start and budget) as the day model found it. */
    val slotStart: Instant? = null,
    val slotBudgetMin: Double? = null,
)

data class Visits(val done: Int, val fixed: Int, val recommended: Int, val max: Int)

data class Projected(val done: Double, val committed: Double, val plannedGeneral: Double, val end: Double, val target: Double, val targetEffective: Double)

data class Band(val jStar: Double, val tieBand: Double, val candidatesInBand: Int, val tieKeyUsed: String)

/** A compressed evaluated plan for the log (8.11): J, its terms and its choices. */
data class PlanSummary(val j: Double, val terms: Map<String, Double>, val choices: String)

data class WeekPlan(
    val isoWeek: String,
    val planId: String,
    val provisional: Boolean,
    val days: List<PlanDay>,
    val visits: Visits,
    val recommendedN: Int,
    val projected: Map<String, Projected>,
    val j: Double,
    val terms: Map<String, Double>,
    val band: Band,
    val warnings: List<ReasonCode>,
    val top: List<PlanSummary>,
    val evaluated: Int,
    val kMax: Int,
    val kMaxReduced: Boolean,
    /** The readiness doses of the chosen plan's general sessions and add-ons (next week's planner starts from them, 8.8). */
    val planDoses: List<Dose>,
)
