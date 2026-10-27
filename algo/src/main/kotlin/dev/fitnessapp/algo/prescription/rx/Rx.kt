package dev.fitnessapp.algo.prescription.rx

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.hold.Hold
import dev.fitnessapp.algo.estimation.hold.HoldState
import dev.fitnessapp.algo.estimation.rcm.RepCurve
import dev.fitnessapp.algo.estimation.stall.TierAction

enum class RxMode(val code: String) {
    MODEL("model"), DOUBLE("double"), RULE("rule"), FIXED("fixed"), NONE("none"), HOLD("hold")
}

/** SSE-1's state predicted to the session start (B9.2): `L_pred` and `P_LL`. */
data class RxEstimate(val lPred: Double, val pLL: Double)

/**
 * The last valid exposure of this exercise and side (B9.2; for a deload week, the last non-deload one). [wLast] is the
 * heaviest working `w_sys`; [targets] and [ext] the DOUBLE state of its prescription; [failed] per B9.7.
 */
data class RxLast(
    val wLast: Double,
    val reps: List<Int>,
    val qTrue: List<Double?>,
    val valid: Boolean = true,
    val probe: Boolean = false,
    val targets: List<Int>? = null,
    val ext: Int = 0,
    val failed: Boolean = false,
)

/** A RULE item as materialised by the challenge (B9.11): per-set reps or seconds, its system load, RIR target, rest. */
data class RuleItem(
    val reps: List<Int?>,
    val seconds: List<Double?> = emptyList(),
    val wSys: Double?,
    val rirTarget: Double?,
    val restS: Double?,
    /** `w_last` of the same item and side, for the cap comparison (B9.14, D13). */
    val wLastItem: Double? = null,
)

/** A timed hold dispatched to HOLD-1 (B9.1): its state predicted to now, the exponent, options and target range. */
data class HoldRx(val state: HoldState, val beta: Double, val options: List<Double>, val tLo: Double, val tHi: Double)

/** Transforms and history flags of B9.2. */
data class RxFlags(
    val deload: Boolean = false,
    val reentryRirAdd: Double? = null,
    val tier: TierAction = TierAction.NONE,
    val failCounter: Int = 0,
    val tooHardBoost: Boolean = false,
    val painFollowUp: Boolean = false,
    val usedDoubleThisMeso: Boolean = false,
    val daysSinceLast: Long? = null,
    val bwOverPrevious: Int = 0,
    /** In-session `delta_ex` (AUTO-1, B13); 0 at L6. */
    val delta: Double = 0.0,
)

/**
 * Everything RX-1 reads for one exercise and side (B9.2). [loadables] are the equipment's system loads (empty when
 * `w_sys` is undefined); [restsS] the planned rest after each set; [k] RCM-1's `k_used` (or the class prior);
 * [transferM] the transfer prior when there is no state; [nativeLadder] a native ladder exists without `w_sys`.
 */
data class RxInput(
    val exerciseId: String,
    val side: String,
    val isolation: Boolean,
    val highRisk: Boolean,
    val bodyweightOnly: Boolean,
    val scheme: Scheme,
    val sets: Int,
    val restsS: List<Double>,
    val loadables: List<Double>,
    val k: Double,
    val estimate: RxEstimate?,
    val f1: Double = 0.0,
    val transferM: Double? = null,
    val last: RxLast? = null,
    val flags: RxFlags = RxFlags(),
    val rule: RuleItem? = null,
    val hold: HoldRx? = null,
    val nativeLadder: Boolean = false,
)

/** One planned set: target reps (or seconds), the target RIR, the system load, the predicted RIR and the rest after it. */
data class RxSet(
    val setIndex: Int,
    val side: String,
    val targetReps: Int?,
    val targetSeconds: Double? = null,
    val rir: Double?,
    val wSys: Double?,
    val rirPred: Double?,
    val restS: Double?,
)

/** `caps_applied[]` (B9.14): `up`, `down`, `fast`, `fail`, `tier1`, `pain`, `double_one_step`, `rule_excess`. */
data class CapApplied(val type: String, val limitW: Double, val unclampedW: Double)

/** One row of the MODEL candidate table (trace, B9.15). */
data class RxCandidate(val w: Double, val capFresh: Double, val reps: List<Int>, val rir: List<Double>, val j: Double?, val feasible: Boolean?)

data class DoubleState(val targets: List<Int>, val ext: Int)

/** `rx.prescription` (B9.15). [shadow] is the model's proposal for a RULE item; [mUse] and [zC] the M_use parts. */
data class RxOutput(
    val mode: RxMode,
    val scheme: Scheme,
    val sets: List<RxSet>,
    val capsApplied: List<CapApplied> = emptyList(),
    val double: DoubleState? = null,
    val probe: Boolean = false,
    val failCounter: Int = 0,
    val shadow: RxOutput? = null,
    val mUse: Double? = null,
    val zC: Double? = null,
    val candidates: List<RxCandidate> = emptyList(),
    val repsPerStep: Double? = null,
    val bwOver: Boolean = false,
    val variantSuggestion: String? = null,
    val trace: List<String> = emptyList(),
)

/**
 * RX-1 (ALGORITHMS.md B9, C15): sets, reps and load for one planned exercise and side. MODEL optimises over the
 * loadables with FAT-1 and the personal curve, DOUBLE runs the Gravl rep ladder where one load step is coarse, RULE
 * copies the challenge item and logs the model's shadow, HOLD dispatches to HOLD-1, NONE repeats the last exposure.
 * Every engine load respects the one cap table; RULE loads are only compared with it (D13, C45).
 */
object Rx : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("rx")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("rx.bw_r_hi", "reps", 12.0, 40.0),
        ParamSpec.integer("rx.bw_switch_exposures", "exposures", 1.0, 5.0),
        ParamSpec.integer("rx.calibration_gap_days", "days", 14.0, 60.0),
        ParamSpec.number("rx.cap_down", "ratio", 0.05, 0.25),
        ParamSpec.number("rx.cap_fast_rir_margin", "RIR", 1.0, 4.0),
        ParamSpec.number("rx.cap_up_compound", "ratio", 0.02, 0.1),
        ParamSpec.number("rx.cap_up_fast", "ratio", 0.05, 0.2),
        ParamSpec.number("rx.cap_up_isolation", "ratio", 0.05, 0.2),
        ParamSpec.number("rx.coarse_reps_per_step", "reps", 0.8, 3.0),
        ParamSpec.integer("rx.default_hyp_r_hi", "reps", 4.0, 40.0),
        ParamSpec.integer("rx.default_hyp_r_lo", "reps", 3.0, 30.0),
        ParamSpec.number("rx.default_q_hyp_compound", "RIR", 0.0, 4.0),
        ParamSpec.number("rx.default_q_hyp_isolation", "RIR", 0.0, 4.0),
        ParamSpec.number("rx.default_q_maintain", "RIR", 1.0, 4.0),
        ParamSpec.number("rx.default_q_strength", "RIR", 1.0, 4.0),
        ParamSpec.integer("rx.default_strength_r_star", "reps", 1.0, 8.0),
        ParamSpec.integer("rx.double_entry_below", "reps", 0.0, 6.0),
        ParamSpec.integer("rx.double_extension_step", "reps", 1.0, 4.0),
        ParamSpec.integer("rx.double_max_extension", "reps", 0.0, 8.0),
        ParamSpec.number("rx.double_success_rir_tol", "RIR", 0.0, 1.5),
        ParamSpec.number("rx.fixed_tol", "RIR", 0.0, 1.0),
        ParamSpec.number("rx.min_q_high_risk", "RIR", 1.0, 3.0),
        ParamSpec.number("rx.probe_rir_add", "RIR", 1.0, 4.0),
        ParamSpec.number("rx.range_under_tol", "RIR", 0.0, 2.0),
        ParamSpec.number("rx.range_w_clamp", "-", 0.0, 10.0),
        ParamSpec.number("rx.range_w_dev", "-", 0.0, 10.0),
        ParamSpec.number("rx.range_w_negative", "-", 10.0, 1000.0),
        ParamSpec.number("rx.range_w_under", "-", 0.0, 20.0),
        ParamSpec.number("rx.window_hi", "ratio of M", 0.9, 1.0),
        ParamSpec.number("rx.window_lo", "ratio of M", 0.2, 0.6),
        ParamSpec.number("rx.z_c", "sd", 0.0, 1.5),
        ParamSpec.number("rx.z_c_after_too_hard", "sd", 0.5, 2.0),
        ParamSpec.integer("rx.z_c_after_too_hard_exposures", "exposures", 1.0, 5.0),
        ParamSpec.number("rx.z_c_sd_floor", "ln", 0.0, 0.05),
    )

    /** `roundHalfDown(x) = ceil(x - 0.5)`: 9.5 -> 9, ties toward fewer reps (B9.5). */
    fun roundHalfDown(x: Double): Int = kotlin.math.ceil(x - 0.5 - 1e-9).toInt()

    fun prescribe(input: RxInput, params: ParamView): RxOutput {
        require(input.restsS.isNotEmpty()) { "RX-1 needs the planned rest after each set" }
        input.rule?.let { return rule(input, it, params) }
        input.hold?.let { return hold(input, it, params) }
        if (input.loadables.isEmpty()) {
            return if (input.nativeLadder) double(input, input.last?.wLast, null, params) else none(input)
        }
        val gap = input.flags.daysSinceLast?.let { it > params.int("rx.calibration_gap_days") } ?: false
        if ((input.estimate == null && input.transferM == null) || gap) return probe(input, params)
        if (input.flags.deload) return deload(input, params)
        if (input.bodyweightOnly || input.loadables.size == 1) return double(input, input.loadables.first(), null, params)

        val model = model(input, input.scheme, input.sets, input.flags.delta, true, params)
        val w = model.sets.first().wSys!!
        val harder = input.loadables.filter { it > w + EPS }.minOrNull()
        if (harder == null) return model.copy(trace = model.trace + "rx.top_of_equipment")
        val cap1 = RepCurve.cap(w, model.mUse!!, input.k) * (1.0 - input.f1)
        val rps = Grid.rep(repsPerStep(w, harder, input.k, cap1))
        if (input.flags.usedDoubleThisMeso || rps > params.double("rx.coarse_reps_per_step")) {
            return double(input, input.last?.wLast ?: w, rps, params)
        }
        return model.copy(repsPerStep = rps)
    }

    /** MODEL alone (B9.5) with the cap band, without the mode selection of B9.4 (fixtures GF-RX-01 to 04). */
    fun modelMode(input: RxInput, params: ParamView): RxOutput = model(input, input.scheme, input.sets, input.flags.delta, true, params)

    /** The coarse-step figure of B9.4 step 6: reps one load step from [w] to [next] is worth at capacity [cap1]. */
    fun repsPerStep(w: Double, next: Double, k: Double, cap1: Double): Double = (next - w) / w * (k + cap1 - 1.0)

    // ------------------------------------------------------------------------------------------------ MODEL (B9.5)

    private fun zC(input: RxInput, params: ParamView): Double {
        val est = input.estimate ?: return 0.0
        if (Det.sqrt(est.pLL) < params.double("rx.z_c_sd_floor")) return 0.0
        return if (input.flags.tooHardBoost) params.double("rx.z_c_after_too_hard") else params.double("rx.z_c")
    }

    private fun mUse(input: RxInput, zc: Double, delta: Double): Double {
        val est = input.estimate
        return if (est != null) Det.exp(est.lPred - zc * Det.sqrt(est.pLL) + delta) else input.transferM!! * Det.exp(delta)
    }

    /**
     * The `M` of the predicted RIR: `M_use` with `z_c` and `delta`, the one the prescription was made with, so review
     * metric M4 (`q_true - rir_pred`) shows a too large `z_c` as a positive mean (Part D; C3.1, C3.3).
     */
    private fun mPred(input: RxInput, params: ParamView): Double? =
        if (input.estimate == null && input.transferM == null) null else mUse(input, zC(input, params), input.flags.delta)

    /** Per-set reps and capacity at load [w] (RANGE fills reps from capacity; FIXED uses `r*`). */
    private fun plan(w: Double, m: Double, k: Double, scheme: Scheme, sets: Int, rests: List<Double>, f1: Double, params: ParamView): Pair<List<Int>, List<Double>> {
        val kc = Fatigue.constants(params)
        val capFresh = RepCurve.cap(w, m, k)
        val reps = ArrayList<Int>()
        val caps = ArrayList<Double>()
        var f = f1
        for (j in 0 until sets) {
            val cap = capFresh * (1.0 - f)
            val r = if (scheme.kind == SchemeKind.FIXED) scheme.rStar else roundHalfDown(cap - scheme.qStar).coerceIn(scheme.rLo, scheme.rHi)
            reps += r
            caps += cap
            if (j < sets - 1) f = Fatigue.step(f, r.toDouble(), cap, rest(rests, j), kc)
        }
        return reps to caps
    }

    private fun rest(rests: List<Double>, j: Int): Double = rests.getOrElse(j) { rests.last() }

    private fun candidate(w: Double, m: Double, input: RxInput, scheme: Scheme, sets: Int, params: ParamView): RxCandidate {
        val (reps, caps) = plan(w, m, input.k, scheme, sets, input.restsS, input.f1, params)
        val rir = caps.indices.map { caps[it] - reps[it] }
        return if (scheme.kind == SchemeKind.RANGE) {
            var j = 0.0
            caps.indices.forEach { i ->
                val ideal = roundHalfDown(caps[i] - scheme.qStar)
                j += params.double("rx.range_w_dev") * sq(rir[i] - scheme.qStar)
                j += params.double("rx.range_w_under") * sq(maxOf(0.0, scheme.qStar - params.double("rx.range_under_tol") - rir[i]))
                j += params.double("rx.range_w_clamp") * sq((reps[i] - ideal).toDouble())
            }
            if (rir.any { it < 0.0 }) j += params.double("rx.range_w_negative")
            RxCandidate(w, caps[0], reps, rir, j, null)
        } else {
            RxCandidate(w, caps[0], reps, rir, null, rir.min() >= scheme.qStar - params.double("rx.fixed_tol"))
        }
    }

    /** The cap band of B9.14 around `w_last` (null bounds = open), with the type of each binding bound. */
    private data class Band(val lo: Double?, val hi: Double?, val hiType: String, val loType: String)

    private fun band(input: RxInput, scheme: Scheme, params: ParamView): Band? {
        val last = input.last?.takeIf { it.valid && !it.probe } ?: return null
        val w = last.wLast
        val fast = !input.highRisk && (last.qTrue.lastOrNull() ?: Double.NEGATIVE_INFINITY) >= scheme.qStar + params.double("rx.cap_fast_rir_margin")
        val up = when {
            fast -> params.double("rx.cap_up_fast")
            input.isolation -> params.double("rx.cap_up_isolation")
            else -> params.double("rx.cap_up_compound")
        }
        var hi = w * (1.0 + up)
        var hiType = if (fast) "fast" else "up"
        fun bound(limit: Double, type: String) {
            val e = easierOrEqual(input.loadables, limit)
            val x = e ?: limit
            if (x < hi) {
                hi = x
                hiType = type
            }
        }
        val fastPath = input.flags.failCounter >= params.int("stall.fail_threshold")
        if (fastPath) bound(w * (1.0 - params.double("stall.fail_load_cut")), "fail")
        if (input.flags.painFollowUp) bound(w * (1.0 - params.double("reason.pain_load_cut")), "pain")
        if (input.flags.tier == TierAction.TIER1) {
            if (scheme.strength) bound(w, "tier1") else bound(w * (1.0 - params.double("stall.tier1_load_cut")), "tier1")
        }
        val detraining = (input.flags.daysSinceLast ?: 0L) > params.int("rx.calibration_gap_days")
        val downExempt = input.flags.painFollowUp || detraining || input.flags.tooHardBoost || fastPath || input.flags.tier == TierAction.TIER1
        val lo = if (downExempt) null else w * (1.0 - params.double("rx.cap_down"))
        return Band(lo, hi, hiType, "down")
    }

    /** MODEL (B9.5) with the cap band; [capped] false for the shadow of a RULE item without `w_last`. */
    private fun model(input: RxInput, scheme: Scheme, setsIn: Int, delta: Double, capped: Boolean, params: ParamView): RxOutput {
        val zc = zC(input, params)
        val m = mUse(input, zc, delta)
        val trace = ArrayList<String>()
        var window = input.loadables.filter { it >= params.double("rx.window_lo") * m - EPS && it <= params.double("rx.window_hi") * m + EPS }.sorted()
        if (window.isEmpty()) {
            val lo = params.double("rx.window_lo") * m
            val hi = params.double("rx.window_hi") * m
            window = listOf(input.loadables.minByOrNull { if (it < lo) lo - it else it - hi }!!)
            trace += "rx.window.empty"
        }
        var sets = setsIn
        val all = window.map { candidate(it, m, input, scheme, sets, params) }
        val free = choose(all, scheme)
        val b = if (capped) band(input, scheme, params) else null
        var pool = all
        val caps = ArrayList<CapApplied>()
        if (b != null) {
            val inBand = input.loadables.filter { (b.lo == null || it >= b.lo - EPS) && (b.hi == null || it <= b.hi + EPS) }
            pool = if (inBand.isEmpty()) {
                trace += "rx.caps.no_loadable_in_band"
                val last = input.last!!.wLast
                listOfNotNull(nextEasier(input.loadables, last), input.loadables.firstOrNull { kotlin.math.abs(it - last) < EPS } ?: last)
                    .distinct().map { candidate(it, m, input, scheme, sets, params) }
            } else {
                all.filter { it.w in inBand }.ifEmpty {
                    // The window and the band do not overlap: the band edge nearest to the window.
                    val edge = if (inBand.max() < window.first()) inBand.max() else inBand.min()
                    listOf(candidate(edge, m, input, scheme, sets, params))
                }
            }
        }
        var chosen = choose(pool, scheme)
        if (chosen == null) {
            chosen = pool.minBy { it.w }
            sets = maxOf(1, sets - 1)
            chosen = candidate(chosen.w, m, input, scheme, sets, params)
            trace += "rx.fixed.infeasible"
        }
        if (free != null && b != null && kotlin.math.abs(free.w - chosen.w) > EPS) {
            if (free.w > chosen.w) caps += CapApplied(b.hiType, Grid.kg(b.hi!!), Grid.kg(free.w))
            else caps += CapApplied(b.loType, Grid.kg(b.lo ?: chosen.w), Grid.kg(free.w))
        }
        return RxOutput(
            mode = RxMode.MODEL, scheme = scheme, sets = sets(input, chosen.w, chosen.reps, scheme.qStar, params, sets),
            capsApplied = caps, mUse = m, zC = zc, candidates = all, trace = trace,
            bwOver = input.bodyweightOnly && chosen.capFresh - scheme.qStar > params.int("rx.bw_r_hi"),
        )
    }

    private fun choose(cands: List<RxCandidate>, scheme: Scheme): RxCandidate? = if (scheme.kind == SchemeKind.RANGE) {
        cands.sortedBy { it.w }.reduceOrNull { best, c -> if (Det.cmpScore(c.j!!, best.j!!) < 0) c else best }
    } else {
        cands.filter { it.feasible == true }.maxByOrNull { it.w }
    }

    /** The planned sets at load [w] with [reps] and their predicted RIR. */
    private fun sets(input: RxInput, w: Double?, reps: List<Int?>, qStar: Double?, params: ParamView, count: Int = reps.size): List<RxSet> {
        val pred = predicted(input, w, reps.take(count), params)
        return (0 until count).map { j ->
            RxSet(j + 1, input.side, reps.getOrNull(j), null, qStar, w?.let { Grid.kg(it) }, pred?.getOrNull(j)?.let { Grid.rep(it) },
                Grid.s(rest(input.restsS, j)))
        }
    }

    /** Predicted RIR per set (FAT-1 prescription recursion at [mPred]); null without a model or reps. */
    private fun predicted(input: RxInput, w: Double?, reps: List<Int?>, params: ParamView): List<Double>? {
        val m = mPred(input, params) ?: return null
        if (w == null || reps.any { it == null }) return null
        val kc = Fatigue.constants(params)
        val capFresh = RepCurve.cap(w, m, input.k)
        val out = ArrayList<Double>()
        var f = input.f1
        reps.forEachIndexed { j, r ->
            val cap = capFresh * (1.0 - f)
            out += cap - r!!
            if (j < reps.size - 1) f = Fatigue.step(f, r.toDouble(), cap, rest(input.restsS, j), kc)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ DOUBLE (B9.6)

    private fun ladderStart(input: RxInput, w: Double, top: Int, params: ParamView): List<Int> {
        val m = if (input.estimate != null || input.transferM != null) mUse(input, zC(input, params), 0.0) else null
        if (m == null) return List(input.sets) { input.scheme.rLo }
        val kc = Fatigue.constants(params)
        val capFresh = RepCurve.cap(w, m, input.k)
        val out = ArrayList<Int>()
        var f = input.f1
        for (j in 0 until input.sets) {
            val cap = capFresh * (1.0 - f)
            val t = roundHalfDown(cap - input.scheme.qStar).coerceIn(1, top)
            out += t
            if (j < input.sets - 1) f = Fatigue.step(f, t.toDouble(), cap, rest(input.restsS, j), kc)
        }
        return out
    }

    private fun ladderStep(achieved: List<Int>, top: Int): List<Int> {
        val t = achieved.map { minOf(it, top) }.toMutableList()
        for (j in 1 until t.size) {
            if (t[j] < t[j - 1]) {
                t[j] += 1
                return t
            }
        }
        if (t.isNotEmpty() && t[0] < top) t[0] += 1
        return t
    }

    /** DOUBLE at the last exposure's load [lLast] (or the entry load); [rps] is the coarse-step figure if checked. */
    private fun double(input: RxInput, lLast: Double?, rps: Double?, params: ParamView): RxOutput {
        val s = input.scheme
        val trace = ArrayList<String>()
        val caps = ArrayList<CapApplied>()
        val last = input.last
        if (lLast == null) return none(input)
        var load = lLast
        var ext = last?.ext ?: 0
        val prevTargets = last?.targets
        val validPrev = last != null && last.valid && !last.probe && prevTargets != null && kotlin.math.abs(last.wLast - lLast) < EPS
        var targets: List<Int>
        if (input.flags.failCounter >= params.int("stall.fail_threshold") && last != null) {
            val limit = last.wLast * (1.0 - params.double("stall.fail_load_cut"))
            load = easierOrEqual(input.loadables, limit) ?: load
            caps += CapApplied("fail", Grid.kg(limit), Grid.kg(last.wLast))
            ext = 0
            targets = ladderStart(input, load, s.rHi, params)
            trace += "stall.fast_path"
        } else if (!validPrev) {
            targets = ladderStart(input, load, s.rHi, params)
            ext = 0
            trace += "rx.double.start"
        } else {
            val top = s.rHi + ext
            val success = last!!.reps.indices.map { j ->
                j < prevTargets!!.size && last.reps[j] >= prevTargets[j] &&
                    (last.qTrue.getOrNull(j) ?: Double.NEGATIVE_INFINITY) >= s.qStar - params.double("rx.double_success_rir_tol")
            }
            val planned = last.reps.take(prevTargets!!.size)
            if (planned.size == prevTargets.size && planned.all { it >= top } && success.take(prevTargets.size).all { it }) {
                val next = input.loadables.filter { it > load + EPS }.minOrNull()
                val start = next?.let { ladderStart(input, it, s.rHi, params) }
                val entry = if (input.highRisk) s.rLo else s.rLo - params.int("rx.double_entry_below")
                if (next != null && start!!.first() >= entry) {
                    val upCap = if (input.isolation) params.double("rx.cap_up_isolation") else params.double("rx.cap_up_compound")
                    if (next > load * (1.0 + upCap) + EPS) caps += CapApplied("double_one_step", Grid.kg(load * (1.0 + upCap)), Grid.kg(next))
                    load = next
                    targets = start
                    ext = 0
                    trace += "rx.double.advance"
                } else if (ext < params.int("rx.double_max_extension")) {
                    ext += params.int("rx.double_extension_step")
                    targets = ladderStep(planned, s.rHi + ext)
                    trace += "rx.double.extend"
                } else {
                    targets = prevTargets
                    trace += "rx.double.range_too_narrow"
                }
            } else if (success.take(prevTargets.size).all { it } && planned.size == prevTargets.size) {
                targets = ladderStep(planned, top)
                trace += "rx.double.step"
            } else {
                targets = planned.ifEmpty { prevTargets }
                trace += "rx.double.repeat"
            }
        }
        targets = (targets + List(maxOf(0, input.sets - targets.size)) { targets.lastOrNull() ?: s.rLo }).take(input.sets)
        return RxOutput(
            mode = RxMode.DOUBLE, scheme = s, sets = sets(input, load, targets, s.qStar, params),
            capsApplied = caps, double = DoubleState(targets, ext), failCounter = input.flags.failCounter,
            mUse = if (input.estimate != null || input.transferM != null) mUse(input, zC(input, params), 0.0) else null,
            zC = zC(input, params), repsPerStep = rps, trace = trace,
        )
    }

    // ------------------------------------------------------------------------------------------------ transforms

    /**
     * Deload week (B9.9): the load of the last non-deload exposure, reps `max(1, min(planned_j, floor(cap_j - (q* + 2))))`
     * through FAT-1; without a model `max(1, t_j - 2)`. Combined with a re-entry the single offset is the larger (C51).
     */
    private fun deload(input: RxInput, params: ParamView): RxOutput {
        val s = input.scheme
        val offset = maxOf(2.0, input.flags.reentryRirAdd ?: 0.0)
        val last = input.last
        val w = last?.wLast ?: model(input, s, input.sets, 0.0, false, params).sets.first().wSys!!
        val planned = last?.targets ?: List(input.sets) { if (s.kind == SchemeKind.FIXED) s.rStar else s.rHi }
        val m = mPred(input, params)
        val reps = ArrayList<Int>()
        if (m == null) {
            for (j in 0 until input.sets) reps += maxOf(1, planned.getOrElse(j) { planned.last() } - 2)
        } else {
            val kc = Fatigue.constants(params)
            val capFresh = RepCurve.cap(w, mUse(input, zC(input, params), 0.0), input.k)
            var f = input.f1
            for (j in 0 until input.sets) {
                val cap = capFresh * (1.0 - f)
                val r = maxOf(1, minOf(planned.getOrElse(j) { planned.last() }, kotlin.math.floor(cap - (s.qStar + offset)).toInt()))
                reps += r
                if (j < input.sets - 1) f = Fatigue.step(f, r.toDouble(), cap, rest(input.restsS, j), kc)
            }
        }
        // A deload exposure never advances a ladder or counts as a failure; the mode stays the exercise's (B9.9).
        val mode = if (input.flags.usedDoubleThisMeso || input.bodyweightOnly) RxMode.DOUBLE else RxMode.MODEL
        return RxOutput(mode, s, sets(input, w, reps, s.qStar + offset, params), double = last?.targets?.let { DoubleState(it, last.ext) },
            trace = listOf("rx.deload"))
    }

    /** Calibration probe (B9.8, C27): set 1 at `r_hi` (or `r*`) with `q* + 2` in reserve; sets 2..S follow from it. */
    private fun probe(input: RxInput, params: ParamView): RxOutput {
        val s = input.scheme
        val reps = if (s.kind == SchemeKind.FIXED) s.rStar else s.rHi
        val probeScheme = s.copy(kind = SchemeKind.FIXED, rLo = reps, rHi = reps, qStar = s.qStar + params.double("rx.probe_rir_add"))
        val w = if (input.estimate != null || input.transferM != null) {
            model(input.copy(last = null), probeScheme, 1, 0.0, false, params).sets.first().wSys
        } else null
        val first = RxSet(1, input.side, reps, null, probeScheme.qStar, w, w?.let { predicted(input, it, listOf(reps), params)?.first()?.let(Grid::rep) },
            Grid.s(rest(input.restsS, 0)))
        val rest = (2..input.sets).map { RxSet(it, input.side, null, null, s.qStar, null, null, Grid.s(rest(input.restsS, it - 1))) }
        return RxOutput(
            mode = RxMode.MODEL, scheme = s, sets = listOf(first) + rest, probe = true,
            trace = listOf(if (w == null) "rx.probe.pick_weight" else "rx.probe"),
        )
    }

    /** Fallback `none` (B9.10): the last exposure's reps, no predicted RIR. */
    private fun none(input: RxInput): RxOutput {
        val reps = input.last?.reps?.map { it as Int? } ?: List(input.sets) { null }
        return RxOutput(RxMode.NONE, input.scheme, (0 until input.sets).map { j ->
            RxSet(j + 1, input.side, reps.getOrNull(j) ?: reps.lastOrNull(), null, null, input.last?.wLast?.let { Grid.kg(it) }, null,
                Grid.s(rest(input.restsS, j)))
        }, trace = listOf("rx.fallback.prefill_last"))
    }

    /** A timed hold (B9.1): HOLD-1's target time at its load for every set. */
    private fun hold(input: RxInput, h: HoldRx, params: ParamView): RxOutput {
        val rx = Hold.prescribe(h.state, h.beta, h.options, h.tLo, h.tHi, input.f1, params)
        val sets = (1..input.sets).map { RxSet(it, input.side, null, rx.tTargetS, null, rx.wSys, null, Grid.s(rest(input.restsS, it - 1))) }
        return RxOutput(RxMode.HOLD, input.scheme, sets, trace = listOf("rx.hold", "hold@" + Hold.ref.version))
    }

    // ------------------------------------------------------------------------------------------------ RULE (B9.11)

    private fun rule(input: RxInput, item: RuleItem, params: ParamView): RxOutput {
        val s = input.scheme
        val q = item.rirTarget?.let { if (input.highRisk) maxOf(it, params.double("rx.min_q_high_risk")) else it }
        val trace = ArrayList<String>()
        if (item.rirTarget != null && q != item.rirTarget) trace += "rx.safety.min_q_high_risk"
        val n = maxOf(item.reps.size, item.seconds.size, input.sets)
        val pred = predicted(input, item.wSys, item.reps.take(n), params)
        val sets = (0 until n).map { j ->
            RxSet(j + 1, input.side, item.reps.getOrNull(j), item.seconds.getOrNull(j), q, item.wSys?.let { Grid.kg(it) },
                pred?.getOrNull(j)?.let { Grid.rep(it) }, (item.restS ?: rest(input.restsS, j)).let { Grid.s(it) })
        }
        val caps = ArrayList<CapApplied>()
        if (item.wLastItem != null && item.wSys != null) {
            val up = if (input.isolation) params.double("rx.cap_up_isolation") else params.double("rx.cap_up_compound")
            val limit = item.wLastItem * (1.0 + up)
            if (item.wSys > limit + EPS) caps += CapApplied("rule_excess", Grid.kg(limit), Grid.kg(item.wSys))
        }
        val shadow = if ((input.estimate != null || input.transferM != null) && input.loadables.isNotEmpty() && item.reps.firstOrNull() != null) {
            val r = item.reps.first()!!
            val shadowScheme = s.copy(kind = SchemeKind.FIXED, rLo = r, rHi = r, qStar = q ?: params.double("rx.default_q_strength"))
            model(input.copy(rule = null), shadowScheme, n, 0.0, false, params)
        } else null
        return RxOutput(RxMode.RULE, s, sets, capsApplied = caps, shadow = shadow, trace = trace + "rx.rule.copy")
    }

    // ------------------------------------------------------------------------------------------------ unilateral (B9.13)

    /**
     * Combines the two sides (B9.13). `match_weaker` ([matchWeaker], strength goal and skill / challenge / heavy
     * unilateral tags): both sides get the weaker side's load (lower `M_use`, ties L) and the stronger side's rep targets
     * are capped at the weaker side's; `independent` keeps each side unless [oneSetting] (one pin for both arms).
     * Returns the weaker side first.
     */
    fun combineSides(left: RxOutput, right: RxOutput, matchWeaker: Boolean, oneSetting: Boolean): Pair<RxOutput, RxOutput> {
        val leftWeaker = (left.mUse ?: Double.MAX_VALUE) <= (right.mUse ?: Double.MAX_VALUE)
        val (weak, strong) = if (leftWeaker) left to right else right to left
        if (!matchWeaker && !oneSetting) return weak to strong
        val w = weak.sets.firstOrNull()?.wSys
        val adjusted = strong.copy(sets = strong.sets.mapIndexed { j, set ->
            val cap = if (matchWeaker) weak.sets.getOrNull(j)?.targetReps else null
            set.copy(wSys = w, targetReps = if (cap != null && set.targetReps != null) minOf(cap, set.targetReps) else set.targetReps)
        }, trace = strong.trace + if (matchWeaker) "rx.unilateral.match_weaker" else "rx.unilateral.one_setting")
        return weak to adjusted
    }

    /**
     * B9.12: a bodyweight-only exercise whose capacity at `q*` exceeded `rx.bw_r_hi` in `rx.bw_switch_exposures`
     * consecutive exposures gets a harder-variant suggestion (never automatic, D10); predicted reps below `r_lo` an
     * easier one.
     */
    fun variantSuggestion(out: RxOutput, previousOver: Int, params: ParamView): String? = when {
        out.bwOver && previousOver + 1 >= params.int("rx.bw_switch_exposures") -> "harder"
        out.candidates.isNotEmpty() && out.sets.first().targetReps != null && out.candidates.first().capFresh - out.scheme.qStar < out.scheme.rLo -> "easier"
        else -> null
    }

    private fun easierOrEqual(w: List<Double>, x: Double): Double? = w.filter { it <= x + EPS }.maxOrNull()
    private fun nextEasier(w: List<Double>, x: Double): Double? = w.filter { it < x - EPS }.maxOrNull()
    private fun sq(x: Double) = x * x

    /** Half of the KG grid: loads closer than this are the same load (`load.epsilon_kg`). */
    private const val EPS = 0.005
}
