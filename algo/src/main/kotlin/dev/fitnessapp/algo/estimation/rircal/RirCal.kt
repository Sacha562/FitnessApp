package dev.fitnessapp.algo.estimation.rircal

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.prescription.reason.ReasonSemantics
import dev.fitnessapp.algo.prescription.reason.SetSemantics
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap

/** The per-curve-class calibration of B4.1. */
data class ClassCal(val bC: Double, val nC: Int, val lastCheckDate: LocalDate?)

/**
 * RIRCAL-1 state (B4.1): the user row `{b, s_q, n}`, one [ClassCal] per curve class code, and the ISO week of the
 * last explicit check with the number of checks in it (so `rircal.max_checks_per_week` above 1 stays meaningful).
 */
data class RirCalState(
    val b: Double,
    val sQ: Double,
    val n: Int,
    val classes: SortedMap<String, ClassCal>,
    val lastCheckWeek: String?,
    val checksInLastCheckWeek: Int,
)

/** `q_true` and `sigma_q` of one set (B4.2); [nEff] = the reps the observation stands for + `q_true`. */
data class QTrue(val qTrue: Double, val sigmaQ: Double, val nEff: Double, val imputed: Boolean, val failure: Boolean)

/** One RIRCAL-1 update with the `estimation.rircal` snapshot keys (B4.6, DATA_MODEL 10.2). */
data class RirCalUpdate(
    val state: RirCalState,
    val type: String,
    val curveClass: String?,
    val bBefore: Double,
    val bAfter: Double,
    val sQBefore: Double,
    val sQAfter: Double,
    val e: Double,
    val nC: Int,
)

/** An exercise of the planned session that could carry the explicit check (B4.3), in plan order. */
data class CheckCandidate(
    val exerciseId: String,
    val planPosition: Int,
    val curveClass: CurveClass?,
    val safetyClass: String,
    val challenge: Boolean,
    val ruleMode: Boolean,
    val jointFlagged: Boolean,
)

/**
 * RIRCAL-1 (ALGORITHMS.md B4, C17): the user's RIR bias `b` and noise `s_q`. Applied only inside the estimators;
 * it never changes a stored fact, and challenge rules read the raw RIR. Updates computed in L8 take effect from
 * the next session: callers pass the state in effect at session start.
 */
object RirCal : DeclaresParams {
    private val SAFETY_CLASSES = listOf("machine", "cable", "isolation", "free_compound", "bodyweight", "high_risk")
    private val TO_FAILURE_KINDS = setOf("test", "amrap")
    private val IMPLICIT_BLOCKERS = setOf(
        "grip_failure", "pain", "technique_breakdown", "torso_rotation", "occupied", "equipment_missing", "no_time",
    )

    val ref: ModuleRef = ModuleRegistry.ref("rircal")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("rircal.b0", "reps", -1.0, 3.0),
        ParamSpec.number("rircal.b_max", "reps", 1.0, 5.0),
        ParamSpec.number("rircal.b_min", "reps", -3.0, 0.0),
        ParamSpec.integer("rircal.class_cooldown_days", "days", 7.0, 56.0),
        ParamSpec.integer("rircal.class_min_samples", "checks", 3.0, 20.0),
        ParamSpec("rircal.eligible_safety_classes", ParamType.ENUM_ARRAY, "enum list (safety classes, E42)", enumValues = SAFETY_CLASSES),
        ParamSpec.number("rircal.lr_explicit", "dimensionless", 0.05, 0.5),
        ParamSpec.number("rircal.lr_implicit", "dimensionless", 0.0, 0.3),
        ParamSpec.integer("rircal.max_checks_per_week", "checks", 0.0, 2.0),
        ParamSpec.number("rircal.sigma_per_rir", "per RIR", 0.0, 0.5),
        ParamSpec.number("rircal.sq0", "dimensionless", 0.5, 3.0),
        ParamSpec.number("rircal.sq_decay", "dimensionless", 0.5, 0.95),
        ParamSpec.number("rircal.sq_max", "dimensionless", 2.0, 5.0),
        ParamSpec.number("rircal.sq_min", "dimensionless", 0.2, 1.0),
    )

    fun initial(params: ParamView): RirCalState =
        RirCalState(params.double("rircal.b0"), params.double("rircal.sq0"), 0, TreeMap(), null, 0)

    /** `b_eff(class)`: the class value once it has `rircal.class_min_samples` checks, else the user value. */
    fun bEff(state: RirCalState, curveClass: CurveClass?, params: ParamView): Double {
        val c = curveClass?.let { state.classes[it.code] } ?: return state.b
        return if (c.nC >= params.int("rircal.class_min_samples")) c.bC else state.b
    }

    /** `q_true` and `sigma_q` of a set (B4.2), or null when it has no RIR and REASON-1 cannot impute one. */
    fun qTrue(set: LoggedSet, semantics: SetSemantics, curveClass: CurveClass?, state: RirCalState, params: ParamView): QTrue? {
        val extra = set.calibExtraReps
        if (extra != null) {
            // An explicit check is a to-failure observation of r* + extra reps (B4.3).
            return failure(set.reps + extra.toDouble(), 0.0, state, params)
        }
        val reported = set.rirReported
        val protocolFailure = set.setKind in TO_FAILURE_KINDS && reported == 0.0
        if (set.failedRep || semantics.techniqueLimited || protocolFailure) {
            return failure(set.reps.toDouble(), reported ?: 0.0, state, params)
        }
        if (reported == null) {
            val q = ReasonSemantics.imputeQ(set, params) ?: return null
            val n = set.reps + q
            val sigma = maxOf(sigmaQ(n, 0.0, state, params), ReasonSemantics.imputeSigmaQ(params))
            return QTrue(q, sigma, n, imputed = true, failure = false)
        }
        val q = (reported + bEff(state, curveClass, params)).coerceIn(0.0, 10.0)
        val n = set.reps + q
        return QTrue(q, sigmaQ(n, reported, state, params), n, imputed = false, failure = false)
    }

    private fun failure(reps: Double, qRep: Double, state: RirCalState, params: ParamView): QTrue =
        QTrue(0.0, sigmaQ(reps, qRep, state, params), reps, imputed = false, failure = true)

    /** `sigma_q = base(n) * s_q + rircal.sigma_per_rir * q_rep` with the rep buckets of `sse.sigma_q_base_*`. */
    fun sigmaQ(n: Double, qRep: Double, state: RirCalState, params: ParamView): Double {
        val base = when {
            n <= 12.0 -> params.double("sse.sigma_q_base_le12")
            n <= 20.0 -> params.double("sse.sigma_q_base_le20")
            else -> params.double("sse.sigma_q_base_gt20")
        }
        return base * state.sQ + params.double("rircal.sigma_per_rir") * qRep
    }

    /** Explicit calibration check (B4.3): the predicted RIR [qPred] at r* reps and the [extra] reps to failure. */
    fun explicit(
        state: RirCalState,
        curveClass: CurveClass?,
        qPred: Double,
        extra: Int,
        date: LocalDate,
        params: ParamView,
    ): RirCalUpdate {
        val bMin = params.double("rircal.b_min")
        val bMax = params.double("rircal.b_max")
        val lr = params.double("rircal.lr_explicit")
        val decay = params.double("rircal.sq_decay")
        val e = extra - qPred
        val r = e - state.b
        val b = Grid.stat((state.b + lr * r).coerceIn(bMin, bMax))
        val sQ = Grid.stat(
            Det.sqrt(decay * state.sQ * state.sQ + (1.0 - decay) * r * r)
                .coerceIn(params.double("rircal.sq_min"), params.double("rircal.sq_max")),
        )
        val classes = TreeMap(state.classes)
        var nC = 0
        if (curveClass != null) {
            val old = classes[curveClass.code] ?: ClassCal(params.double("rircal.b0"), 0, null)
            val c = ClassCal(Grid.stat((old.bC + lr * (e - old.bC)).coerceIn(bMin, bMax)), old.nC + 1, date)
            classes[curveClass.code] = c
            nC = c.nC
        }
        val week = Week.isoWeek(date)
        val inWeek = if (state.lastCheckWeek == week) state.checksInLastCheckWeek + 1 else 1
        val next = RirCalState(b, sQ, state.n + 1, classes, week, inWeek)
        return RirCalUpdate(next, "explicit", curveClass?.code, state.b, b, state.sQ, sQ, Grid.stat(e), nC)
    }

    /**
     * Implicit update from an unintended failure (B4.4) over one exercise and side of a session, in order:
     * [sets] with their semantics. [f1] is the exercise's carry-over. Null when no set qualifies.
     */
    fun implicit(
        state: RirCalState,
        curveClass: CurveClass?,
        sets: List<Pair<LoggedSet, SetSemantics>>,
        f1: Double,
        params: ParamView,
    ): RirCalUpdate? {
        val k = Fatigue.constants(params)
        for (j in 1 until sets.size) {
            val (cur, curSem) = sets[j]
            val (prev, prevSem) = sets[j - 1]
            val qPrev = prev.rirReported ?: continue
            if (cur.rirReported != 0.0) continue
            val target = cur.targetReps
            if (!(cur.failedRep || (target != null && cur.reps < target))) continue
            if (prev.wSys == null || cur.wSys == null || Grid.kg(prev.wSys) != Grid.kg(cur.wSys)) continue
            if (prev.reasonCode in IMPLICIT_BLOCKERS || cur.reasonCode in IMPLICIT_BLOCKERS) continue
            if (prevSem.techniqueLimited || curSem.techniqueLimited) continue
            val upTo = sets.subList(0, j + 1).map { it.first }
            val caps = upTo.map { it.reps + (it.rirReported ?: 0.0) + state.b }
            val neutral = params.double("fatigue.rest_ref_s") // c(rest) = c_ref when no rest is known
            val rests = upTo.drop(1).map { it.restBeforeS ?: it.plannedRestBeforeS ?: neutral }
            val fs = Fatigue.observed(upTo.map { it.reps.toDouble() }, caps, rests, f1, k)
            val implied = cur.reps * (1.0 - fs[j - 1]) / (1.0 - fs[j])
            val e = (implied - prev.reps) - qPrev
            val b = Grid.stat(
                (state.b + params.double("rircal.lr_implicit") * (e - state.b))
                    .coerceIn(params.double("rircal.b_min"), params.double("rircal.b_max")),
            )
            val nC = curveClass?.let { state.classes[it.code]?.nC } ?: 0
            return RirCalUpdate(state.copy(b = b), "implicit", curveClass?.code, state.b, b, state.sQ, state.sQ, Grid.stat(e), nC)
        }
        return null
    }

    /**
     * Reason hook (B4.5): `too_easy` on the same exercise in two consecutive exposures while the reported RIR was
     * at most `q*`. [lastTwo] holds the sets of the two most recent exposures of the exercise.
     */
    fun reasonHook(state: RirCalState, curveClass: CurveClass?, lastTwo: List<List<LoggedSet>>, params: ParamView): RirCalUpdate? {
        if (lastTwo.size < 2) return null
        val qualifies = lastTwo.all { exposure ->
            exposure.any { s -> s.reasonCode == "too_easy" && s.rirReported != null && s.qStar != null && s.rirReported <= s.qStar }
        }
        if (!qualifies) return null
        val b = Grid.stat(
            (state.b + params.double("reason.too_easy_b_step")).coerceIn(params.double("rircal.b_min"), params.double("rircal.b_max")),
        )
        val nC = curveClass?.let { state.classes[it.code]?.nC } ?: 0
        return RirCalUpdate(state.copy(b = b), "reason_hook", curveClass?.code, state.b, b, state.sQ, state.sQ, Grid.stat(b - state.b), nC)
    }

    /**
     * The explicit check of a planned session (B4.3), or null: opt-in, an eligible safety class, not a challenge
     * or RULE item, no flagged joint, not a deload week, the weekly limit and the class cooldown. Among the
     * eligible exercises the curve class with the oldest check wins (never checked first), ties by plan position.
     */
    fun chooseCheck(
        candidates: List<CheckCandidate>,
        optIn: Boolean,
        deloadWeek: Boolean,
        today: LocalDate,
        state: RirCalState,
        params: ParamView,
    ): CheckCandidate? {
        if (!optIn || deloadWeek) return null
        val week = Week.isoWeek(today)
        val done = if (state.lastCheckWeek == week) state.checksInLastCheckWeek else 0
        if (done >= params.int("rircal.max_checks_per_week")) return null
        val eligibleClasses = params.strings("rircal.eligible_safety_classes").toSet()
        val cooldown = params.int("rircal.class_cooldown_days")
        val eligible = candidates.filter { c ->
            val last = c.curveClass?.let { state.classes[it.code]?.lastCheckDate }
            c.safetyClass in eligibleClasses && c.safetyClass != "high_risk" && !c.challenge && !c.ruleMode &&
                !c.jointFlagged && (last == null || Week.calendarDays(last, today) >= cooldown)
        }
        return eligible.sortedWith(
            compareBy<CheckCandidate> { c -> c.curveClass?.let { state.classes[it.code]?.lastCheckDate } ?: LocalDate.MIN }
                .thenBy { it.planPosition }
                .thenBy { it.exerciseId },
        ).firstOrNull()
    }
}
