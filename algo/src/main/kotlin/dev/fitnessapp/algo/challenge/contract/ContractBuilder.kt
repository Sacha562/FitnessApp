package dev.fitnessapp.algo.challenge.contract

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.challenge.plan.BlockedDay
import dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AddOnSlot(
    val capacityMin: Int?,
    val offered: Boolean,
    val allowPatterns: List<String>,
    val forbidGroups: List<String>,
    val forbidSessionTags: List<String>,
    val maxGripDemand: Int?,
    val maxSets: Int?,
    val minRir: Int?,
    val generalWarmup: Boolean,
)

data class ReservedSession(
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val slot: String,
    val templateId: String?,
    val source: String,
    val bookedMin: Int,
    val budgetMin: Int,
    val estMin: BigDecimal?,
    val sessionTags: List<String>,
    val addOnSlot: AddOnSlot?,
)

/** A protected window as instants (C12; `clock` keeps 24 h real hours across DST, 8.5). */
data class ContractWindow(
    val id: String,
    val from: Instant,
    val to: Instant,
    val mode: String,
    val groups: List<String>,
    val regions: List<String>,
    val sessionTags: List<String>,
    val scope: String?,
    val rMin: Double?,
    val tissueRMin: Map<String, Double>,
    val tag: String,
    val slot: String,
)

data class GapRule(val days: Int, val appliesToTags: List<String>)

data class LightPull(val maxGripDemand: Int, val bicepsCoefficientBelow: Double, val outsideProtectedWindows: Boolean, val onlyIfGoalsAsk: Boolean)

data class GeneralSessionsPolicy(
    val allowedOnGapDaysIfNoTags: List<String>,
    val neverOn: List<String>,
    val forbidGroupsInHardWindows: Boolean,
    val oneGymVisitPerDay: Boolean,
    val lightPullOnGapDays: LightPull,
)

/**
 * The `ChallengeContract` of CHALLENGE_ENGINE 8.1 for one ISO week. Coverage (`planned`, `performed`,
 * `committed`) is computed by `setcredit` and arrives with it in M4 (D32); [coverage] stays null until then.
 */
data class ChallengeContract(
    val enrollmentId: UUID,
    val definitionSha: String,
    val isoWeek: String,
    val status: String,
    val mode: String,
    val phase: Int,
    val blockIndex: Int,
    val blockWeek: Int,
    val mesocycleWeek: Int,
    val deloadWeek: Boolean,
    val reservedSessions: List<ReservedSession>,
    val blockedDays: List<BlockedDay>,
    val gapRule: GapRule?,
    val protectedWindows: List<ContractWindow>,
    val ownsMuscles: List<String>,
    val sessionTagRules: JsonObject,
    val forbiddenSessionTags: List<String>,
    val forbiddenExerciseTags: List<String>,
    val jointFlags: Map<String, Int>,
    val generalSessions: GeneralSessionsPolicy?,
    val coverage: JsonObject? = null,
) {
    /** A paused, completed or abandoned enrollment exports an empty contract (8.1). */
    val empty: Boolean get() = reservedSessions.isEmpty() && blockedDays.isEmpty() && protectedWindows.isEmpty() && ownsMuscles.isEmpty()
}

/** `challenge.contract@1.0.0` (CHALLENGE_ENGINE 8). */
object ContractBuilder : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.contract")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec("challenge.contract.add_on_recompute_every_set", ParamType.BOOLEAN, "boolean"),
        ParamSpec.number("challenge.contract.gap_day_pull_biceps_coef_below", "coeff (strict below)", 0.5, 0.5),
        ParamSpec.integer("challenge.contract.gap_day_pull_max_grip_demand", "grip demand level", 1.0, 1.0),
        ParamSpec.number("challenge.contract.window_penalty_weight", "per unit readiness deficit", 1.0, 20.0),
    )

    /** Deload synchronisation for the general engine (F8, C13): deload, extra deload, and a test-pending week in deload form. */
    fun deloadWeek(state: EnrollmentState, def: CompiledDefinition): Boolean = when (state.mode) {
        Modes.DELOAD, Modes.EXTRA_DELOAD -> true
        Modes.TEST_PENDING -> def.modes[Modes.TEST_PENDING]?.spec?.proposal?.let { state.approved(it) } ?: false
        else -> false
    }

    fun build(
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        plan: ChallengeWeekPlan,
        changeoverMin: Int,
        sessionPlans: Map<String, SessionPlan> = emptyMap(),
    ): ChallengeContract {
        val active = state.status == EnrollmentState.ACTIVE
        val ig = def.doc.integration
        if (!active) {
            return ChallengeContract(
                state.enrollmentId, def.jcsSha256, plan.isoWeek, state.status, state.mode, state.phase, state.block.index, state.block.week,
                state.block.week, false, emptyList(), emptyList(), null, emptyList(), emptyList(), JsonObject(emptyMap()), emptyList(), emptyList(),
                emptyMap(), null,
            )
        }
        val reserved = plan.sessions.map { p ->
            val booked = Duration.between(p.interval.start, p.interval.end).toMinutes().toInt()
                .takeIf { it > 0 } ?: sessionPlans[p.slot]?.bookedMin ?: 0
            val addOnSpec = ig.addOnSlots.firstOrNull { it.slot == p.slot && it.position == "end" }
            val sp = sessionPlans[p.slot]
            ReservedSession(
                p.date, p.interval.start, p.interval.end, p.slot, p.templateId, p.source, booked, booked - changeoverMin, sp?.estMin, p.tags,
                addOnSpec?.let { a ->
                    AddOnSlot(
                        capacityMin = sp?.addOnCapacityMin, offered = sp?.addOnOffered ?: false, allowPatterns = a.allowPatterns,
                        forbidGroups = when (val f = a.forbidGroups) {
                            is JsonPrimitive -> if (f.content == "ownsMuscles") ig.ownsMuscles else listOf(f.content)
                            null -> emptyList()
                            else -> dev.fitnessapp.algo.challenge.cds.TemplateResolver.strings(f)
                        },
                        forbidSessionTags = a.forbidSessionTags, maxGripDemand = a.maxGripDemand, maxSets = a.maxSets, minRir = a.minRir,
                        generalWarmup = a.generalWarmup ?: false,
                    )
                },
            )
        }
        val windows = ArrayList<ContractWindow>()
        for (pw in ig.protectedWindows) {
            if (pw.proposal != null && !state.approved(pw.proposal)) continue
            val h = Duration.ofHours(pw.hours.toLong())
            for (p in plan.sessions.filter { it.slot in pw.slots }) {
                val (from, to) = if (pw.relation == "before") p.interval.start.minus(h) to p.interval.start else p.interval.end to p.interval.end.plus(h)
                windows += ContractWindow(pw.id, from, to, pw.mode, pw.groups, pw.regions, pw.sessionTags, pw.scope, pw.rMin, pw.tissueRMin, pw.tag, p.slot)
            }
        }
        val gap = def.doc.schedule.hard.firstOrNull { it.type == "minGapDays" }?.let { GapRule(it.days ?: 0, it.appliesToTags) }
        val effects = ig.modeEffects[state.mode]
        val general = GeneralSessionsPolicy(
            allowedOnGapDaysIfNoTags = ig.generalSessions.allowedOnGapDaysIfNoTags,
            neverOn = ig.generalSessions.neverOn,
            forbidGroupsInHardWindows = ig.generalSessions.forbidGroupsInHardWindows,
            oneGymVisitPerDay = true,
            lightPullOnGapDays = LightPull(
                params.int("challenge.contract.gap_day_pull_max_grip_demand"), params.double("challenge.contract.gap_day_pull_biceps_coef_below"),
                outsideProtectedWindows = true, onlyIfGoalsAsk = true,
            ),
        )
        return ChallengeContract(
            enrollmentId = state.enrollmentId, definitionSha = def.jcsSha256, isoWeek = plan.isoWeek, status = state.status, mode = state.mode,
            phase = state.phase, blockIndex = state.block.index, blockWeek = state.block.week, mesocycleWeek = state.block.week,
            deloadWeek = deloadWeek(state, def), reservedSessions = reserved, blockedDays = plan.blockedDays, gapRule = gap,
            protectedWindows = windows.sortedWith(compareBy({ it.from }, { it.id })), ownsMuscles = ig.ownsMuscles,
            sessionTagRules = ig.sessionTagRules, forbiddenSessionTags = effects?.forbidSessionTags.orEmpty(),
            forbiddenExerciseTags = effects?.forbidExerciseTags.orEmpty(), jointFlags = effects?.jointFlags.orEmpty(), generalSessions = general,
        )
    }
}
