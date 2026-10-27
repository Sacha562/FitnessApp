package dev.fitnessapp.algo.estimation.sse

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.core.trace.TraceStep
import dev.fitnessapp.algo.estimation.EstimatorClass
import dev.fitnessapp.algo.estimation.EstimatorKind
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.fatigue.CarrySet
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.rcm.RepCurve
import dev.fitnessapp.algo.estimation.rircal.QTrue
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.rircal.RirCalState
import dev.fitnessapp.algo.prescription.reason.ReasonSemantics
import java.time.Instant
import java.util.TreeMap

/** The key of one SSE-1 state: user (implicit), exercise and side (E1). */
data class SideKey(val exerciseId: String, val side: String) : Comparable<SideKey> {
    override fun compareTo(other: SideKey): Int = compareValuesBy(this, other, SideKey::exerciseId, SideKey::side)
}

/** SSE-1 state (B6.1): the trend in `ln M` and the `k` it was last updated with. */
data class SseState(val trend: TrendState, val k: Double)

/**
 * One set of a session after the shared estimation steps: REASON-1 class, RIRCAL-1 `q_true`, FAT-1 `F` and
 * `n_fresh`. [skipCode] is null when the set enters SSE-1 and RCM-1; otherwise it names why not (B6.1, C8).
 */
data class PreparedSet(
    val set: LoggedSet,
    val estimatorClass: EstimatorClass,
    val q: QTrue?,
    val fatigueF: Double,
    val nFresh: Double?,
    val skipCode: String?,
) {
    /** The reps the observation stands for (an explicit check adds its extra reps, B4.3). */
    val repsDone: Double get() = q?.let { it.nEff - it.qTrue } ?: set.reps.toDouble()
}

/** `estimation.sse` set output (B6.5, DATA_MODEL 10.2); the one-step-ahead keys are null on a cold start. */
data class SseSetOut(
    val setId: String,
    val qTrue: Double,
    val nEff: Double,
    val nFresh: Double,
    val fatigueF: Double,
    val z: Double,
    val s: Double,
    val estimatorClass: String,
    val e1rmKg: Double,
    val e1rmAddedKg: Double?,
    val kUsed: Double,
    val residual: Double?,
    val nEffPred: Double?,
    val zPredLo: Double?,
    val zPredHi: Double?,
)

/** `estimation.sse` state output (B6.5) with the innovation statistics for the trace. */
data class SseStateOut(
    val exerciseId: String,
    val side: String,
    val mKg: Double,
    val ci80LoKg: Double,
    val ci80HiKg: Double,
    val gPerWeek: Double,
    val lPrior: Double?,
    val pLLPrior: Double?,
    val huberApplied: Boolean,
    val lbApplied: Boolean,
    val nu: Double?,
    val innovationS: Double?,
    val k0: Double?,
    val k1: Double?,
)

data class SseResult(
    val sets: List<SseSetOut>,
    val states: List<SseStateOut>,
    val next: TreeMap<SideKey, SseState>,
    val trace: List<TraceStep>,
)

/**
 * SSE-1 (ALGORITHMS.md B6, C4): the strength-state estimator, a local-linear-trend Kalman filter on `ln M` per
 * exercise and side, fed by the fatigue-corrected e1RM of every eligible set.
 */
object Sse : DeclaresParams {
    private val LOAD_MODES = setOf(LoadMode.EXTERNAL, LoadMode.BODYWEIGHT, LoadMode.BODYWEIGHT_PLUS_EXTERNAL, LoadMode.ASSISTED_MACHINE)
    private val SET_KINDS = setOf("working", "top", "backoff", "amrap", "calibration", "test")

    val ref: ModuleRef = ModuleRegistry.ref("sse")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("sse.ci_z", "z", 1.2816, 1.2816),
        ParamSpec.number("sse.cold_start_sd", "ln", 0.03, 0.2),
        ParamSpec.number("sse.detrain_cap", "ln", 0.05, 0.4),
        ParamSpec.number("sse.detrain_per_week", "ln per week", 0.0, 0.03),
        ParamSpec.number("sse.detrain_var_per_week", "ln^2 per week", 0.0, 0.004),
        ParamSpec.number("sse.fatigue_err", "ln at F = 0.3", 0.0, 0.1),
        ParamSpec.number("sse.form_err_free_reps", "reps", 1.0, 10.0),
        ParamSpec.number("sse.form_err_per_rep", "ln per rep", 0.0, 0.02),
        ParamSpec.number("sse.grace_days", "days", 7.0, 42.0),
        ParamSpec.number("sse.huber_gate_sd", "sd", 2.0, 5.0),
        ParamSpec.number("sse.lb_gate_sd", "sd", 0.0, 2.0),
        ParamSpec.number("sse.lb_r_mult", "dimensionless", 1.0, 5.0),
        ParamSpec.number("sse.max_n_eff", "reps", 15.0, 40.0),
        ParamSpec.number("sse.q_g", "ln per week per sqrt(week)", 0.0001, 0.005),
        ParamSpec.number("sse.q_l", "ln per sqrt(week)", 0.002, 0.05),
        ParamSpec.number("sse.rho", "per week", 0.5, 1.0),
        ParamSpec.number("sse.sd_day", "ln", 0.01, 0.1),
        ParamSpec.number("sse.sg0", "ln per week", 0.001, 0.02),
        ParamSpec.number("sse.sigma_q_base_gt20", "reps", 1.0, 6.0),
        ParamSpec.number("sse.sigma_q_base_le12", "reps", 0.3, 3.0),
        ParamSpec.number("sse.sigma_q_base_le20", "reps", 0.5, 4.0),
        ParamSpec.number("sse.transfer_sd", "ln", 0.05, 0.4),
    )

    /** The C8 eligibility of B6.1 before any number is computed; null = eligible. */
    fun skipCode(set: LoggedSet): String? = when {
        set.contraction != "dynamic" -> "sse.skip.contraction"
        set.fingerAssisted -> "sse.skip.finger_assist"
        set.loadMode !in LOAD_MODES -> "sse.skip.load_mode"
        set.setKind !in SET_KINDS -> "sse.skip.set_kind"
        set.wSys == null || set.wSys <= 0.0 -> "sse.skip.w_sys"
        set.reps < 1 -> "sse.skip.reps"
        else -> null
    }

    /**
     * The shared estimation steps for one session (B4.2, B6.3, B7.3, B7.4): every dynamic non-warm-up set gets its
     * REASON-1 class, `q_true`, its fatigue `F` (per exercise and side, with carry-over from earlier exercises) and
     * `n_fresh`. Sets that cannot enter SSE-1 keep a skip code. [rircal] is the state in effect at session start.
     */
    fun prepare(sets: List<LoggedSet>, facts: Map<String, ExerciseFacts>, rircal: RirCalState, params: ParamView): List<PreparedSet> {
        val ordered = sets.sortedWith(compareBy<LoggedSet> { it.performedAt }.thenBy { it.id })
        val sem = ordered.associate { it.id to ReasonSemantics.forSet(it, params) }
        val counted = ordered.filter { it.contraction == "dynamic" && it.setKind != "warmup" }
        val q = counted.associate { s ->
            s.id to RirCal.qTrue(s, sem.getValue(s.id), facts[s.exerciseId]?.curveClass, rircal, params)
        }
        fun reps(s: LoggedSet) = q[s.id]?.let { it.nEff - it.qTrue } ?: s.reps.toDouble()
        fun cap(s: LoggedSet) = q[s.id]?.nEff ?: s.reps.toDouble()

        val firstAt = TreeMap<String, Instant>()
        counted.forEach { s -> firstAt.merge(s.exerciseId, s.performedAt) { a, b -> if (b.isBefore(a)) b else a } }
        val k = Fatigue.constants(params)
        val neutralRest = params.double("fatigue.rest_ref_s")
        val f = TreeMap<String, Double>()
        for ((key, group) in counted.groupBy { SideKey(it.exerciseId, it.side) }.toSortedMap()) {
            val target = facts[key.exerciseId]
            val f1 = if (target == null) 0.0 else Fatigue.carryOver(
                target,
                counted.filter { it.exerciseId != key.exerciseId && it.performedAt.isBefore(firstAt.getValue(key.exerciseId)) }
                    .mapNotNull { s -> facts[s.exerciseId]?.let { CarrySet(it, s.side != "both", reps(s), cap(s)) } },
                params,
            )
            val rests = group.zipWithNext { a, b ->
                if (key.side != "both") Week.elapsedHours(a.performedAt, b.performedAt) * 3600.0
                else b.restBeforeS ?: b.plannedRestBeforeS ?: neutralRest
            }
            val fs = Fatigue.observed(group.map { reps(it) }, group.map { cap(it) }, rests, f1, k)
            group.forEachIndexed { i, s -> f[s.id] = fs[i] }
        }

        val maxNEff = params.double("sse.max_n_eff")
        return ordered.map { s ->
            val cls = sem.getValue(s.id).estimatorClass
            val qt = q[s.id]
            val fatigue = f[s.id] ?: 0.0
            val code = skipCode(s) ?: when {
                qt == null -> "sse.skip.no_rir"
                cls.kind == EstimatorKind.EXCLUDED -> "sse.skip.excluded"
                qt.nEff > maxNEff -> "sse.skip.n_eff"
                else -> null
            }
            val nFresh = qt?.let { it.nEff / (1.0 - fatigue) }
            PreparedSet(s, cls, qt, fatigue, nFresh, code)
        }
    }

    /** The per-set sd of B6.3 in ln-load units, before the class multiplier. */
    fun obsSd(nFresh: Double, sigmaQ: Double, fatigueF: Double, k: Double, params: ParamView): Double {
        val rir = sigmaQ / (k + nFresh - 1.0)
        val form = params.double("sse.form_err_per_rep") * maxOf(0.0, nFresh - params.double("sse.form_err_free_reps"))
        val fat = params.double("sse.fatigue_err") * fatigueF / 0.3
        return Det.sqrt(rir * rir + form * form + fat * fat)
    }

    /** `z_i`: ln of the fatigue-corrected e1RM. */
    fun z(wSys: Double, nFresh: Double, k: Double): Double = Det.ln(wSys) + Det.ln(1.0 + (nFresh - 1.0) / k)

    /**
     * The L8 session update (B6.4). [priors] are the stored states, predicted here to [sessionStart]; [kByExercise]
     * is RCM-1's `k_used` after this session's fit (missing exercises keep their state's `k`); [transferM] is the
     * `ratio * M_related` prior of an exercise without state; [bwKg] is the session's bodyweight snapshot.
     */
    fun update(
        prepared: List<PreparedSet>,
        priors: Map<SideKey, SseState>,
        kByExercise: Map<String, Double>,
        transferM: Map<String, Double>,
        facts: Map<String, ExerciseFacts>,
        sessionStart: Instant,
        bwKg: Double?,
        params: ParamView,
    ): SseResult {
        val next = TreeMap<SideKey, SseState>(priors)
        val setsOut = ArrayList<SseSetOut>()
        val statesOut = ArrayList<SseStateOut>()
        val trace = ArrayList<TraceStep>()
        prepared.filter { it.skipCode != null }.forEach { trace += TraceStep(it.skipCode!!, it.set.id) }

        val sdDay = params.double("sse.sd_day")
        val z80 = params.double("sse.ci_z")
        val usable = prepared.filter { it.skipCode == null }
        for ((key, group) in usable.groupBy { SideKey(it.set.exerciseId, it.set.side) }.toSortedMap()) {
            val stored = priors[key]
            val k = kByExercise[key.exerciseId] ?: stored?.k
            if (k == null) {
                trace += TraceStep("sse.skip.no_k", key.exerciseId)
                continue
            }
            val fx = facts[key.exerciseId]
            var prior: TrendState? = stored?.let { Trend.predict(it.trend, sessionStart, params) }
            if (prior == null) {
                transferM[key.exerciseId]?.let { m ->
                    val sd = params.double("sse.transfer_sd")
                    prior = Trend.start(Det.ln(m), sd * sd, sessionStart, params)
                    trace += TraceStep("sse.cold_start.transfer", key.exerciseId)
                }
            }
            val rows = group.map { p ->
                val nFresh = p.nFresh!!
                val sd = obsSd(nFresh, p.q!!.sigmaQ, p.fatigueF, k, params)
                Triple(p, z(p.set.wSys!!, nFresh, k), sd * sd * p.estimatorClass.m)
            }
            val normalRows = rows.filter { it.first.estimatorClass.kind != EstimatorKind.LOWER_BOUND }
            val lbRows = rows.filter { it.first.estimatorClass.kind == EstimatorKind.LOWER_BOUND }

            var start = prior
            var updateRows = normalRows
            if (start == null) {
                val first = normalRows.firstOrNull()
                if (first == null) {
                    trace += TraceStep("sse.cold_start.no_normal_set", key.exerciseId)
                    continue
                }
                val sd = params.double("sse.cold_start_sd")
                start = Trend.start(first.second, sd * sd, sessionStart, params)
                updateRows = normalRows.drop(1)
                trace += TraceStep("sse.cold_start.first_set", first.first.set.id)
            }
            val upd = Trend.update(
                start,
                updateRows.map { TrendObs(it.second, it.third) },
                lbRows.map { TrendObs(it.second, it.third) },
                sdDay,
                sessionStart,
                0,
                params,
            )
            val used = normalRows.size + (if (upd.lbApplied) 1 else 0)
            val post = upd.state.copy(nObs = (stored?.trend?.nObs ?: 0) + used).rounded()
            // Nothing moved the state (a lower bound below the gate): keep the stored one, a re-prediction would drift.
            if (used > 0) next[key] = SseState(post, Grid.stat(k))
            if (upd.huberApplied) trace += TraceStep("sse.huber", key.exerciseId)

            for ((p, z, s2) in rows) {
                val e1rm = Det.exp(z)
                val added = fx?.let { if (it.fBw == 0.0) e1rm - it.implementBaseKg else bwKg?.let { bw -> e1rm - it.fBw * bw - fx.implementBaseKg } }
                val pr = prior
                val predSd = pr?.let { Det.sqrt(it.pLL + s2 + sdDay * sdDay) }
                setsOut += SseSetOut(
                    setId = p.set.id,
                    qTrue = Grid.rep(p.q!!.qTrue),
                    nEff = Grid.rep(p.q.nEff),
                    nFresh = Grid.rep(p.nFresh!!),
                    fatigueF = Grid.ratio(p.fatigueF),
                    z = Grid.stat(z),
                    s = Grid.stat(Det.sqrt(s2)),
                    estimatorClass = p.estimatorClass.kind.code,
                    e1rmKg = Grid.kg(e1rm),
                    e1rmAddedKg = added?.let { Grid.kg(it) },
                    kUsed = Grid.stat(k),
                    residual = pr?.let { Grid.stat(z - it.l) },
                    nEffPred = pr?.let { Grid.rep(RepCurve.cap(p.set.wSys!!, Det.exp(it.l), k) * (1.0 - p.fatigueF)) },
                    zPredLo = pr?.let { Grid.stat(it.l - z80 * predSd!!) },
                    zPredHi = pr?.let { Grid.stat(it.l + z80 * predSd!!) },
                )
            }
            if (used == 0) {
                trace += TraceStep("sse.no_update", key.exerciseId)
                continue
            }
            val sdPost = Det.sqrt(post.pLL)
            statesOut += SseStateOut(
                exerciseId = key.exerciseId,
                side = key.side,
                mKg = Grid.kg(Det.exp(post.l)),
                ci80LoKg = Grid.kg(Det.exp(post.l - z80 * sdPost)),
                ci80HiKg = Grid.kg(Det.exp(post.l + z80 * sdPost)),
                gPerWeek = Grid.stat(post.g),
                lPrior = prior?.let { Grid.stat(it.l) },
                pLLPrior = prior?.let { Grid.stat(it.pLL) },
                huberApplied = upd.huberApplied,
                lbApplied = upd.lbApplied,
                nu = upd.nu?.let { Grid.stat(it) },
                innovationS = upd.s?.let { Grid.stat(it) },
                k0 = upd.k0?.let { Grid.stat(it) },
                k1 = upd.k1?.let { Grid.stat(it) },
            )
        }
        return SseResult(setsOut, statesOut, next, trace)
    }
}
