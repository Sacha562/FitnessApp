package dev.fitnessapp.algo.estimation.hold

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.core.trace.TraceStep
import dev.fitnessapp.algo.estimation.EstimatorClass
import dev.fitnessapp.algo.estimation.EstimatorKind
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.sse.SideKey
import dev.fitnessapp.algo.estimation.sse.Trend
import dev.fitnessapp.algo.estimation.sse.TrendObs
import dev.fitnessapp.algo.estimation.sse.TrendState
import java.time.Instant
import java.util.TreeMap

/**
 * One timed hold (`set_logs` with `contraction` isometric or hang, DATA_MODEL 9.6). [wSys] is LOAD-1's system
 * load (null for a hold without a load model); [externalKg] keys the time-only segment (a plate on a plank).
 * [reserveS] is `hold_reserve_s`, the seconds of the effort chip (`hold.chip_reserve_s`: Max 0, Hard 2, OK 5,
 * Easy 10, D9) and [reserveLowerBound] marks Easy; [roundId] groups the attempts of one top-hold round.
 */
data class HoldSet(
    val id: String,
    val exerciseId: String,
    val side: String,
    val performedAt: Instant,
    val setKind: String,
    val wSys: Double?,
    val externalKg: Double,
    val tDoneS: Double,
    val reserveS: Double,
    val reserveLowerBound: Boolean = false,
    val torsoRotation: Boolean = false,
    val reasonCode: String? = null,
    val restBeforeS: Double? = null,
    val plannedRestBeforeS: Double? = null,
    val roundId: String? = null,
)

/** The catalog facts HOLD-1 reads: the `hold_class` (C43), else the pattern for the B8.1 fallback, and `f_bw`. */
data class HoldFacts(val exerciseId: String, val holdClass: String?, val pattern: String, val fBw: Double)

/**
 * HOLD-1 state per exercise and side (B8.2): the trend in `ln T`, the reference load [wRef] fixed at the first
 * observation (null for the time-only model) and, for the time-only model, the external load of its segment.
 */
data class HoldState(val trend: TrendState, val wRef: Double?, val segmentKg: Double?)

data class HoldSetOut(
    val setId: String,
    val tDone: Double,
    val sRes: Double,
    val fatigueF: Double,
    val wSys: Double?,
    val z: Double,
    val sigma: Double,
    val estimatorClass: String,
)

data class HoldStateOut(
    val exerciseId: String,
    val side: String,
    val holdClass: String,
    val tS: Double,
    val ci80LoS: Double,
    val ci80HiS: Double,
    val gPerWeek: Double,
    val beta: Double,
    val wRefKg: Double?,
)

data class HoldResult(
    val sets: List<HoldSetOut>,
    val states: List<HoldStateOut>,
    val next: TreeMap<SideKey, HoldState>,
    val trace: List<TraceStep>,
)

/** A hold prescription (B8.3): the load or assistance as a system load ([wSys], null = bodyweight only) and `t*`. */
data class HoldRx(val wSys: Double?, val tTargetS: Double, val feasible: Boolean)

/**
 * HOLD-1 (ALGORITHMS.md B8): per-side hold capacity `t_cap(w) = T (w / w_ref)^beta` with the joint exponent of
 * Frey Law and Avin (2010), estimated with SSE-1's trend equations. For OAP holds the challenge rules decide and
 * this module runs in shadow.
 */
object Hold : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("hold")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.table("hold.beta", "dimensionless", -4.0, -0.5),
        ParamSpec.number("hold.c_ref", "dimensionless", 0.05, 0.5),
        ParamSpec.table("hold.chip_reserve_s", "s", 0.0, 30.0),
        ParamSpec.number("hold.default_t_hi_s", "s", 10.0, 300.0),
        ParamSpec.number("hold.default_t_lo_s", "s", 5.0, 120.0),
        ParamSpec.number("hold.obs_sd_base", "ln", 0.05, 0.5),
        ParamSpec.number("hold.obs_sd_timing_s", "s", 0.5, 5.0),
        ParamSpec.number("hold.reserve_frac", "dimensionless", 0.0, 0.5),
        ParamSpec.number("hold.reserve_min_s", "s", 0.0, 10.0),
        ParamSpec.number("hold.sd_day", "ln", 0.03, 0.3),
    )

    /** The hold class of B8.1: the catalog attribute, else the pattern fallback (trace `hold.class.fallback`). */
    fun holdClass(f: HoldFacts): String = f.holdClass ?: when {
        f.pattern == "hang" || f.pattern == "grip_hold" -> "grip"
        f.pattern == "straight_arm_pull" -> "shoulder"
        f.pattern == "skill_static" || f.pattern == "elbow_flex" -> "elbow"
        f.pattern.startsWith("core_") -> "trunk"
        f.pattern in setOf("squat", "squat_machine", "knee_ext", "lunge") -> "knee"
        f.pattern == "calf" -> "ankle"
        else -> "general"
    }

    fun beta(f: HoldFacts, params: ParamView): Double = params.table("hold.beta").getValue(holdClass(f))

    /** The reserve seconds and lower-bound mark of an effort chip (`max`, `hard`, `ok`, `easy`; D9). */
    fun chipReserve(chip: String?, params: ParamView): Pair<Double, Boolean> =
        (chip?.let { params.table("hold.chip_reserve_s")[it] } ?: 0.0) to (chip == "easy")

    /** A hold uses the time-only model when it has no load model: `f_bw = 0` and no system load (B8.1). */
    fun timeOnly(f: HoldFacts, s: HoldSet): Boolean = f.fBw == 0.0 || s.wSys == null

    /**
     * The L8 update of one session's holds (B8.2). [priors] are the stored states, predicted here to [sessionStart].
     * Top-hold rounds count once, by their best attempt; a time-only state whose segment load changed starts again.
     */
    fun update(
        sets: List<HoldSet>,
        facts: Map<String, HoldFacts>,
        priors: Map<SideKey, HoldState>,
        sessionStart: Instant,
        params: ParamView,
    ): HoldResult {
        val next = TreeMap<SideKey, HoldState>(priors)
        val setsOut = ArrayList<HoldSetOut>()
        val statesOut = ArrayList<HoldStateOut>()
        val trace = ArrayList<TraceStep>()
        val k = Fatigue.constants(params, cRef = params.double("hold.c_ref"))
        val neutralRest = params.double("fatigue.rest_ref_s")
        val ordered = sets.filter { it.setKind != "warmup" }.sortedWith(compareBy<HoldSet> { it.performedAt }.thenBy { it.id })

        for ((key, raw) in ordered.groupBy { SideKey(it.exerciseId, it.side) }.toSortedMap()) {
            val f = facts[key.exerciseId] ?: continue
            if (f.holdClass == null) trace += TraceStep("hold.class.fallback", key.exerciseId)
            val beta = beta(f, params)
            val group = bestOfRounds(raw)
            val sRes = group.map { s -> if (s.torsoRotation || s.reasonCode == "technique_breakdown") 0.0 else s.reserveS }
            val caps = group.indices.map { group[it].tDoneS + sRes[it] }
            val rests = group.zipWithNext { _, b -> b.restBeforeS ?: b.plannedRestBeforeS ?: neutralRest }
            val fs = Fatigue.observed(group.map { it.tDoneS }, caps, rests, 0.0, k)

            val stored = priors[key]
            val timeOnly = timeOnly(f, group.first())
            val segment = if (timeOnly) group.first().externalKg else null
            val usableStored = stored?.takeIf { !timeOnly || it.segmentKg == segment }
            if (stored != null && usableStored == null) trace += TraceStep("hold.segment.new", key.exerciseId)
            val wRef = usableStored?.wRef ?: if (timeOnly) null else group.first().wSys

            val rows = group.indices.mapNotNull { i ->
                val s = group[i]
                val cls = when {
                    s.reasonCode == "pain" -> EstimatorClass.EXCLUDED
                    s.reserveLowerBound -> EstimatorClass.LOWER_BOUND
                    else -> EstimatorClass.NORMAL
                }
                if (cls.kind == EstimatorKind.EXCLUDED || caps[i] <= 0.0) {
                    trace += TraceStep("hold.skip.excluded", s.id)
                    return@mapNotNull null
                }
                val loadTerm = if (wRef == null || s.wSys == null) 0.0 else beta * Det.ln(s.wSys / wRef)
                val z = Det.ln(caps[i] / (1.0 - fs[i])) - loadTerm
                val sigma = params.double("hold.obs_sd_base") + params.double("hold.obs_sd_timing_s") / caps[i]
                setsOut += HoldSetOut(
                    s.id, Grid.s(s.tDoneS), Grid.s(sRes[i]), Grid.ratio(fs[i]), s.wSys?.let { Grid.kg(it) },
                    Grid.stat(z), Grid.stat(sigma), cls.kind.code,
                )
                Triple(cls, z, sigma * sigma)
            }
            val normal = rows.filter { it.first.kind == EstimatorKind.NORMAL }
            val lbs = rows.filter { it.first.kind == EstimatorKind.LOWER_BOUND }
            var start = usableStored?.let { Trend.predict(it.trend, sessionStart, params) }
            var updateRows = normal
            if (start == null) {
                val first = normal.firstOrNull() ?: continue
                val sd = params.double("sse.cold_start_sd")
                start = Trend.start(first.second, sd * sd, sessionStart, params)
                updateRows = normal.drop(1)
            }
            val upd = Trend.update(
                start, updateRows.map { TrendObs(it.second, it.third) }, lbs.map { TrendObs(it.second, it.third) },
                params.double("hold.sd_day"), sessionStart, 0, params,
            )
            val used = normal.size + (if (upd.lbApplied) 1 else 0)
            if (used == 0) continue
            val post = upd.state.copy(nObs = (usableStored?.trend?.nObs ?: 0) + used).rounded()
            next[key] = HoldState(post, wRef?.let { Grid.kg(it) }, segment)
            val z80 = params.double("sse.ci_z")
            val sd = Det.sqrt(post.pLL)
            statesOut += HoldStateOut(
                key.exerciseId, key.side, holdClass(f), Grid.s(Det.exp(post.l)), Grid.s(Det.exp(post.l - z80 * sd)),
                Grid.s(Det.exp(post.l + z80 * sd)), Grid.stat(post.g), beta, wRef?.let { Grid.kg(it) },
            )
        }
        return HoldResult(setsOut, statesOut, next, trace)
    }

    /** One observation per top-hold round: the attempt with the longest `t_done + s_res`, ties by id. */
    private fun bestOfRounds(sets: List<HoldSet>): List<HoldSet> {
        val out = ArrayList<HoldSet>()
        val seenRounds = TreeMap<String, HoldSet>()
        for (s in sets) {
            val r = s.roundId
            if (r == null) {
                out += s
                continue
            }
            val best = seenRounds[r]
            val total = s.tDoneS + s.reserveS
            if (best == null) {
                seenRounds[r] = s
                out += s
            } else if (total > best.tDoneS + best.reserveS) {
                seenRounds[r] = s
                out[out.indexOf(best)] = s
            }
        }
        return out
    }

    /** `t_cap(w)` from a (predicted) state: the time-only model ignores [w]. */
    fun tCap(state: HoldState, w: Double?, beta: Double): Double {
        val t = Det.exp(state.trend.l)
        val ref = state.wRef
        return if (ref == null || w == null) t else t * Det.exp(beta * Det.ln(w / ref))
    }

    /** `t_target(w) = min(t_avail - reserve_min, t_avail / (1 + reserve_frac))` with `t_avail = t_cap (1 - F)`. */
    fun tTarget(tCap: Double, f: Double, params: ParamView): Double {
        val avail = tCap * (1.0 - f)
        return minOf(avail - params.double("hold.reserve_min_s"), avail / (1.0 + params.double("hold.reserve_frac")))
    }

    /**
     * The prescription of B8.3 from a state predicted to now. With [options] (the loadable system loads of the
     * equipment, any order) the hardest load whose `t_target` falls in `[tLo, tHi]` is chosen and `t*` is floored to
     * 0.5 s; without options (bodyweight only) `t* = clamp(floor(t_target), tLo, tHi)`. [feasible] is false when no
     * option reaches the range (the easiest option is then returned).
     */
    fun prescribe(state: HoldState, beta: Double, options: List<Double>, tLo: Double, tHi: Double, f: Double, params: ParamView): HoldRx {
        if (options.isEmpty() || state.wRef == null) {
            val t = tTarget(tCap(state, null, beta), f, params)
            return HoldRx(null, kotlin.math.floor(t).coerceIn(tLo, tHi), t >= tLo)
        }
        val sorted = options.sortedDescending()
        for (w in sorted) {
            val t = tTarget(tCap(state, w, beta), f, params)
            if (t >= tLo - Det.TIE_EPS && t <= tHi + Det.TIE_EPS) return HoldRx(Grid.kg(w), kotlin.math.floor(t * 2.0) / 2.0, true)
        }
        val easiest = sorted.last()
        val t = tTarget(tCap(state, easiest, beta), f, params)
        return HoldRx(Grid.kg(easiest), kotlin.math.floor(t * 2.0) / 2.0, false)
    }

    /**
     * The progression check of B8.3 after an exposure at the current load: true when every set reached [tHi] with a
     * reserve of at least `s* = max(reserve_min, reserve_frac * t*)`; the caller then moves to the next harder
     * loadable if its `t_target >= tLo`, else raises the time target.
     */
    fun reachedTop(sets: List<Pair<Double, Double>>, tHi: Double, tStar: Double, params: ParamView): Boolean {
        val sStar = maxOf(params.double("hold.reserve_min_s"), params.double("hold.reserve_frac") * tStar)
        return sets.isNotEmpty() && sets.all { (tDone, sRes) -> tDone >= tHi && sRes >= sStar }
    }
}
