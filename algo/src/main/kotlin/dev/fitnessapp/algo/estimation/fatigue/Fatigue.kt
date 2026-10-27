package dev.fitnessapp.algo.estimation.fatigue

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.ExerciseFacts
import java.util.TreeMap

/** One set of an earlier exercise today, for the carry-over of B7.4. [sideRow] = a side row of unilateral work. */
data class CarrySet(val facts: ExerciseFacts, val sideRow: Boolean, val reps: Double, val cap: Double)

/** One set of an exercise instance for the nightly fit (B7.5): observed reps, `n_eff`, reported RIR, rest before. */
data class FitSet(val reps: Double, val nEff: Double, val qRep: Double?, val restBeforeS: Double)

/** One exercise instance of a curve class with its first-set carry-over [f1]. */
data class FitInstance(val f1: Double, val sets: List<FitSet>)

/** B7.5 result; the bucket maps hold the mean residual `n_eff_obs - n_eff_pred` of the non-empty buckets. */
data class FatigueFit(
    val cHat: Double?,
    val cUsed: Double,
    val dHat: Double?,
    val dUsed: Double,
    val m: Int,
    val residByRestBucket: TreeMap<String, Double>,
    val residByRirBucket: TreeMap<String, Double>,
    val residBySetIndex: TreeMap<String, Double>,
)

/**
 * FAT-1 (ALGORITHMS.md B7): within-session fatigue `F`. The same recursion serves prescription (planned reps
 * against the predicted capacity, B7.2) and estimation (observed reps against the observed `n_eff`, B7.3).
 */
object Fatigue : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("fatigue")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.table("fatigue.c_grid", "dimensionless", 0.02, 0.5),
        ParamSpec.number("fatigue.c_ref", "dimensionless", 0.1, 0.5),
        ParamSpec.number("fatigue.carry_cap", "dimensionless", 0.1, 0.5),
        ParamSpec.number("fatigue.carry_per_set", "per set equivalent", 0.0, 0.1),
        ParamSpec.number("fatigue.d", "dimensionless", 0.5, 0.9),
        ParamSpec.integer("fatigue.d_fit_min_sets", "sets", 50.0, 500.0),
        ParamSpec.table("fatigue.d_grid", "dimensionless", 0.02, 0.9),
        ParamSpec.integer("fatigue.fit_window_exercises", "instances", 20.0, 200.0),
        ParamSpec.number("fatigue.gamma", "dimensionless", 1.0, 3.0),
        ParamSpec.number("fatigue.overlap_primary_coeff", "coeff", 0.5, 1.0),
        ParamSpec.number("fatigue.overlap_secondary_coeff", "coeff", 0.25, 0.75),
        ParamSpec.number("fatigue.overlap_secondary_weight", "dimensionless", 0.0, 1.0),
        ParamSpec.number("fatigue.rest_clamp_hi", "dimensionless", 1.2, 2.5),
        ParamSpec.number("fatigue.rest_clamp_lo", "dimensionless", 0.2, 0.8),
        ParamSpec.number("fatigue.rest_exp", "dimensionless", -1.2, -0.2),
        ParamSpec.number("fatigue.rest_ref_s", "s", 120.0, 120.0),
        ParamSpec.integer("fatigue.shrink_pseudo_sets", "sets", 0.0, 100.0),
        ParamSpec.number("fatigue.unilateral_side_weight", "dimensionless", 0.25, 1.0),
    )

    /** The recursion constants; [cRef] and [d] are overridable for the hold variant (B8.2) and the fit (B7.5). */
    data class Constants(val cRef: Double, val d: Double, val gamma: Double, val restRefS: Double, val restExp: Double, val clampLo: Double, val clampHi: Double)

    fun constants(params: ParamView, cRef: Double? = null, d: Double? = null): Constants = Constants(
        cRef = cRef ?: params.double("fatigue.c_ref"),
        d = d ?: params.double("fatigue.d"),
        gamma = params.double("fatigue.gamma"),
        restRefS = params.double("fatigue.rest_ref_s"),
        restExp = params.double("fatigue.rest_exp"),
        clampLo = params.double("fatigue.rest_clamp_lo"),
        clampHi = params.double("fatigue.rest_clamp_hi"),
    )

    /** `c(rest)` of B7.1; a rest of zero or less takes the upper clamp. */
    fun c(restS: Double, k: Constants): Double {
        val factor = if (restS <= 0.0) k.clampHi else Det.exp(k.restExp * Det.ln(restS / k.restRefS)).coerceIn(k.clampLo, k.clampHi)
        return k.cRef * factor
    }

    /** One step `F_j -> F_{j+1}` after [reps] done against the capacity [cap] and a rest of [restS]. */
    fun step(f: Double, reps: Double, cap: Double, restS: Double, k: Constants): Double {
        val ratio = if (cap <= 0.0) 1.0 else minOf(1.0, reps / cap)
        return k.d * f + c(restS, k) * Det.exp(k.gamma * Det.ln(maxOf(ratio, MIN_RATIO))) * (1.0 - f)
    }

    /**
     * Prescription recursion (B7.2): `F_1 .. F_n` for the planned [reps] from the fresh capacity [capFresh];
     * [restsAfter] holds the rest after set j (at least `n - 1` values).
     */
    fun planned(capFresh: Double, reps: List<Double>, restsAfter: List<Double>, f1: Double, k: Constants): List<Double> {
        val out = ArrayList<Double>(reps.size)
        var f = f1
        for (j in reps.indices) {
            out += f
            if (j < reps.size - 1) f = step(f, reps[j], capFresh * (1.0 - f), restsAfter[j], k)
        }
        return out
    }

    /** Estimation recursion (B7.3): the capacity of set j is its observed `n_eff` [caps] (reps + `q_true`). */
    fun observed(reps: List<Double>, caps: List<Double>, restsAfter: List<Double>, f1: Double, k: Constants): List<Double> {
        val out = ArrayList<Double>(reps.size)
        var f = f1
        for (j in reps.indices) {
            out += f
            if (j < reps.size - 1) f = step(f, reps[j], caps[j], restsAfter[j], k)
        }
        return out
    }

    /**
     * Live readout (B7.6): the capacity of the next set after [elapsedS] of rest, given the set just done with
     * fatigue [f], [reps] and capacity [cap], and the fresh capacity [capFresh].
     */
    fun predictNext(capFresh: Double, f: Double, reps: Double, cap: Double, elapsedS: Double, k: Constants): Double =
        capFresh * (1.0 - step(f, reps, cap, elapsedS, k))

    /** Carry-over `F_1(e)` from the working sets of earlier exercises today (B7.4). */
    fun carryOver(target: ExerciseFacts, earlier: List<CarrySet>, params: ParamView): Double {
        val gamma = params.double("fatigue.gamma")
        val primary = params.double("fatigue.overlap_primary_coeff")
        val secondary = params.double("fatigue.overlap_secondary_coeff")
        val secondaryWeight = params.double("fatigue.overlap_secondary_weight")
        val sideWeight = params.double("fatigue.unilateral_side_weight")
        var sum = 0.0
        for (s in earlier) {
            val ov = overlap(s.facts, target, primary, secondary, secondaryWeight)
            if (ov == 0.0) continue
            val ratio = if (s.cap <= 0.0) 1.0 else minOf(1.0, s.reps / s.cap)
            sum += ov * (if (s.sideRow) sideWeight else 1.0) * Det.exp(gamma * Det.ln(maxOf(ratio, MIN_RATIO)))
        }
        return minOf(params.double("fatigue.carry_cap"), params.double("fatigue.carry_per_set") * sum)
    }

    private fun overlap(a: ExerciseFacts, b: ExerciseFacts, primary: Double, secondary: Double, secondaryWeight: Double): Double {
        val groups = a.groupCoeffs.keys.intersect(b.groupCoeffs.keys).sorted()
        if (groups.any { a.groupCoeffs.getValue(it) >= primary && b.groupCoeffs.getValue(it) >= primary }) return 1.0
        if (groups.any { a.groupCoeffs.getValue(it) >= secondary && b.groupCoeffs.getValue(it) >= secondary }) return secondaryWeight
        return 0.0
    }

    /** Nightly personalisation per curve class (B7.5), over the newest instances first as given by the caller. */
    fun fit(instances: List<FitInstance>, params: ParamView): FatigueFit {
        val data = instances.filter { it.sets.size >= 2 }.take(params.int("fatigue.fit_window_exercises"))
        val cDefault = params.double("fatigue.c_ref")
        val dDefault = params.double("fatigue.d")
        val shrink = params.double("fatigue.shrink_pseudo_sets")
        val m = data.sumOf { it.sets.size - 1 }
        if (m == 0) {
            return FatigueFit(null, cDefault, null, dDefault, 0, TreeMap(), TreeMap(), TreeMap())
        }
        val cHat = argmin(grid(params, "fatigue.c_grid"), cDefault) { c -> sse(data, constants(params, cRef = c, d = dDefault)) }
        val cUsed = Grid.ratio((m * cHat + shrink * cDefault) / (m + shrink))
        var dHat: Double? = null
        var dUsed = dDefault
        if (m >= params.int("fatigue.d_fit_min_sets")) {
            val best = argmin(grid(params, "fatigue.d_grid"), dDefault) { d -> sse(data, constants(params, cRef = cUsed, d = d)) }
            dHat = best
            dUsed = Grid.ratio((m * best + shrink * dDefault) / (m + shrink))
        }
        val k = constants(params, cRef = cUsed, d = dUsed)
        val byRest = Buckets()
        val byRir = Buckets()
        val byIndex = Buckets()
        for (inst in data) {
            val preds = predictions(inst, k)
            for (j in 1 until inst.sets.size) {
                val s = inst.sets[j]
                val r = s.nEff - preds[j]
                byRest.add(restBucket(s.restBeforeS), r)
                s.qRep?.let { byRir.add(rirBucket(it), r) }
                byIndex.add(if (j >= 4) "s5_plus" else "s${j + 1}", r)
            }
        }
        return FatigueFit(cHat, cUsed, dHat, dUsed, m, byRest.means(), byRir.means(), byIndex.means())
    }

    /** Predicted `n_eff_j` for every set of an instance: `n_fresh_1 * (1 - F_j)` (index 0 is set 1 itself). */
    private fun predictions(inst: FitInstance, k: Constants): List<Double> {
        val reps = inst.sets.map { it.reps }
        val caps = inst.sets.map { it.nEff }
        val rests = inst.sets.drop(1).map { it.restBeforeS }
        val fs = observed(reps, caps, rests, inst.f1, k)
        val nFresh1 = inst.sets[0].nEff / (1.0 - fs[0])
        return fs.map { nFresh1 * (1.0 - it) }
    }

    private fun sse(data: List<FitInstance>, k: Constants): Double {
        var total = 0.0
        for (inst in data) {
            val preds = predictions(inst, k)
            for (j in 1 until inst.sets.size) {
                val r = inst.sets[j].nEff - preds[j]
                total += r * r
            }
        }
        return total
    }

    /** Ties: closer to [default], then the lower value. */
    private fun argmin(grid: List<Double>, default: Double, f: (Double) -> Double): Double {
        var best = grid.first()
        var bestV = f(best)
        for (x in grid.drop(1)) {
            val v = f(x)
            val cmp = Det.cmpScore(v, bestV)
            val better = cmp < 0 || (cmp == 0 && (Det.cmpScore(kotlin.math.abs(x - default), kotlin.math.abs(best - default)) < 0))
            if (better) {
                best = x
                bestV = v
            }
        }
        return best
    }

    private fun grid(params: ParamView, key: String): List<Double> {
        val t = params.table(key)
        val min = t.getValue("min")
        val max = t.getValue("max")
        val step = t.getValue("step")
        val out = ArrayList<Double>()
        var i = 0
        while (true) {
            val x = Grid.ratio(min + i * step)
            if (x > max + Det.TIE_EPS) break
            out += x
            i++
        }
        return out
    }

    private fun restBucket(s: Double): String = when {
        s < 75.0 -> "lt75"
        s < 105.0 -> "b75_105"
        s < 150.0 -> "b105_150"
        s < 210.0 -> "b150_210"
        else -> "gt210"
    }

    private fun rirBucket(q: Double): String = when {
        q < 2.0 -> "q0_1"
        q < 4.0 -> "q2_3"
        else -> "q4_plus"
    }

    private class Buckets {
        private val sums = TreeMap<String, Pair<Double, Int>>()
        fun add(key: String, v: Double) {
            val (s, n) = sums[key] ?: (0.0 to 0)
            sums[key] = (s + v) to (n + 1)
        }
        fun means(): TreeMap<String, Double> = TreeMap(sums.mapValues { (_, v) -> Grid.rep(v.first / v.second) })
    }

    /** `ln 0` guard for a set of zero reps: its cost is negligible either way. */
    private const val MIN_RATIO = 1e-9
}
