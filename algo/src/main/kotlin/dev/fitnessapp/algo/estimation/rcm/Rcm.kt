package dev.fitnessapp.algo.estimation.rcm

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.EstimatorKind
import dev.fitnessapp.algo.estimation.sse.PreparedSet
import dev.fitnessapp.algo.estimation.sse.Sse
import java.time.LocalDate

/** RCM-1 state per user and exercise: the `k` in use and its uncertainty. */
data class RcmState(val kUsed: Double, val sdLnk: Double)

/** One set of the fit data `D` with its window and, inside an informative window, its residual `e_i - mu_W`. */
data class RcmSetOut(
    val setId: String,
    val wSys: Double,
    val nEff: Double,
    val fatigueF: Double,
    val nFresh: Double,
    val window: Int,
    val residual: Double?,
)

/** `estimation.rcm` outputs (B5.1, DATA_MODEL 10.2); [kHat], [jMin] are null when no window is informative. */
data class RcmFit(
    val state: RcmState,
    val kPrev: Double,
    val kHat: Double?,
    val m: Int,
    val nSets: Int,
    val rateLimited: Boolean,
    val pooled: Boolean,
    val jMin: Double?,
    val jK0: Double?,
    val sets: List<RcmSetOut>,
)

/**
 * RCM-1 (ALGORITHMS.md B5, C3): the personal flatness `k` per user and exercise, fitted on a log grid inside
 * 14-day windows (so strength change is not mistaken for curve shape), shrunk toward the class prior and rate
 * limited. The curve functions themselves are [RepCurve].
 */
object Rcm : DeclaresParams {
    private val FIT_KINDS = setOf("working", "top", "backoff", "amrap", "calibration", "test")

    val ref: ModuleRef = ModuleRegistry.ref("rcm")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("rcm.grid_n", "points", 40.0, 200.0),
        ParamSpec.number("rcm.huber_delta", "sd", 1.0, 3.0),
        ParamSpec.table("rcm.k0", "reps", 12.0, 90.0),
        ParamSpec.number("rcm.k_max", "reps", 60.0, 120.0),
        ParamSpec.number("rcm.k_min", "reps", 8.0, 20.0),
        ParamSpec.integer("rcm.lookback_days", "days", 28.0, 112.0),
        ParamSpec.number("rcm.max_n_eff", "reps", 12.0, 30.0),
        ParamSpec.number("rcm.max_q_rep", "RIR", 2.0, 6.0),
        ParamSpec.integer("rcm.min_sets_per_window", "sets", 2.0, 4.0),
        ParamSpec.number("rcm.min_spread_reps", "reps", 2.0, 8.0),
        ParamSpec.number("rcm.rate_limit", "ratio", 0.02, 0.3),
        ParamSpec.number("rcm.shrink_pseudo", "windows", 0.0, 10.0),
        ParamSpec.number("rcm.sigma_lnk", "ln", 0.1, 0.6),
        ParamSpec.integer("rcm.window_days", "days", 7.0, 28.0),
    )

    fun k0(curveClass: CurveClass, params: ParamView): Double = params.table("rcm.k0").getValue(curveClass.code)

    /** The state before any fit: the class prior with the prior uncertainty (C59 cutover state). */
    fun initial(curveClass: CurveClass, params: ParamView): RcmState =
        RcmState(k0(curveClass, params), params.double("rcm.sigma_lnk"))

    /** One windowed observation of the grid objective. */
    private class Obs(val p: PreparedSet, val window: Int, val s: Double)

    /**
     * The L8 fit for one exercise (B5.1) on its prepared sets of the last `rcm.lookback_days` (older and later sets
     * are ignored) up to [fitDate]. [prev] null means the first fit (`k_prev = k0`). [pooledK] is the user's pooled
     * value for the class ([pooledClassK]), used when no window of this exercise is informative.
     */
    fun fit(
        sets: List<PreparedSet>,
        fitDate: LocalDate,
        curveClass: CurveClass,
        prev: RcmState?,
        pooledK: Double?,
        params: ParamView,
    ): RcmFit {
        val k0 = k0(curveClass, params)
        val kPrev = prev?.kUsed ?: k0
        val data = eligible(sets, fitDate, kPrev, params)
        val windows = data.groupBy { it.window }.toSortedMap().values.filter { informative(it, params) }
        val m = windows.size
        val sigma = params.double("rcm.sigma_lnk")

        val (kTarget, kHat, sdLnk, jMin, jK0) = if (m == 0) {
            Five(pooledK ?: k0, null, sigma, null, null)
        } else {
            val g = gridFit(windows, k0, params)
            val shrink = params.double("rcm.shrink_pseudo")
            val lnUsed = (m * Det.ln(g.kHat) + shrink * Det.ln(k0)) / (m + shrink)
            Five(Det.exp(lnUsed), g.kHat, g.sdLnk, g.jMin, objective(windows, Det.ln(k0), k0, params))
        }
        val rate = params.double("rcm.rate_limit")
        val limited = kTarget.coerceIn((1.0 - rate) * kPrev, (1.0 + rate) * kPrev)
        val kUsed = limited.coerceIn(params.double("rcm.k_min"), params.double("rcm.k_max"))
        val rateLimited = Det.cmpScore(limited, kTarget) != 0

        val residuals = kHat?.let { residuals(windows, it) } ?: emptyMap()
        val setsOut = data.map { o ->
            RcmSetOut(
                setId = o.p.set.id,
                wSys = Grid.kg(o.p.set.wSys!!),
                nEff = Grid.rep(o.p.q!!.nEff),
                fatigueF = Grid.ratio(o.p.fatigueF),
                nFresh = Grid.rep(o.p.nFresh!!),
                window = o.window,
                residual = residuals[o.p.set.id]?.let { Grid.stat(it) },
            )
        }
        return RcmFit(
            state = RcmState(Grid.stat(kUsed), Grid.stat(sdLnk)),
            kPrev = Grid.stat(kPrev),
            kHat = kHat?.let { Grid.stat(it) },
            m = m,
            nSets = data.size,
            rateLimited = rateLimited,
            pooled = m == 0 && pooledK != null,
            jMin = jMin?.let { Grid.stat(it) },
            jK0 = jK0?.let { Grid.stat(it) },
            sets = setsOut,
        )
    }

    /**
     * The same procedure pooled over the user's exercises of one class, with groups `(exercise, window)`, shrunk but
     * not rate limited; null when no group is informative. The per-set sd uses the class prior `k0`.
     */
    fun pooledClassK(setsByExercise: Map<String, List<PreparedSet>>, fitDate: LocalDate, curveClass: CurveClass, params: ParamView): Double? {
        val k0 = k0(curveClass, params)
        val groups = setsByExercise.toSortedMap().flatMap { (_, sets) ->
            eligible(sets, fitDate, k0, params).groupBy { it.window }.toSortedMap().values.filter { informative(it, params) }
        }
        if (groups.isEmpty()) return null
        val g = gridFit(groups, k0, params)
        val shrink = params.double("rcm.shrink_pseudo")
        return Grid.stat(Det.exp((groups.size * Det.ln(g.kHat) + shrink * Det.ln(k0)) / (groups.size + shrink)))
    }

    /** The data `D` of B5.1 in set order, with each set's window and sd (evaluated with [kPrev]). */
    private fun eligible(sets: List<PreparedSet>, fitDate: LocalDate, kPrev: Double, params: ParamView): List<Obs> {
        val lookback = params.int("rcm.lookback_days")
        val windowDays = params.int("rcm.window_days")
        val maxQ = params.double("rcm.max_q_rep")
        val maxN = params.double("rcm.max_n_eff")
        return sets.sortedWith(compareBy<PreparedSet> { it.set.performedAt }.thenBy { it.set.id }).mapNotNull { p ->
            val q = p.q ?: return@mapNotNull null
            val days = Week.calendarDays(p.set.localDate, fitDate)
            val kind = p.estimatorClass.kind
            val qRep = p.set.rirReported ?: q.qTrue
            val ok = p.skipCode == null && p.set.setKind in FIT_KINDS &&
                (kind == EstimatorKind.NORMAL || kind == EstimatorKind.DOWNWEIGHT) &&
                (qRep <= maxQ || q.failure) && q.nEff <= maxN && days >= 0L && days < lookback
            if (!ok) return@mapNotNull null
            val sd = Sse.obsSd(p.nFresh!!, q.sigmaQ, p.fatigueF, kPrev, params) * Det.sqrt(p.estimatorClass.m)
            Obs(p, days.toInt() / windowDays, sd)
        }
    }

    private fun informative(w: List<Obs>, params: ParamView): Boolean {
        if (w.size < params.int("rcm.min_sets_per_window")) return false
        val n = w.map { it.p.nFresh!! }
        return n.max() - n.min() >= params.double("rcm.min_spread_reps")
    }

    private class GridResult(val kHat: Double, val sdLnk: Double, val jMin: Double)

    private fun gridFit(windows: List<List<Obs>>, k0: Double, params: ParamView): GridResult {
        val n = params.int("rcm.grid_n")
        val lo = Det.ln(params.double("rcm.k_min"))
        val hi = Det.ln(params.double("rcm.k_max"))
        val h = (hi - lo) / (n - 1)
        val lnK0 = Det.ln(k0)
        val lks = (0 until n).map { lo + (hi - lo) * it / (n - 1) }
        val js = lks.map { objective(windows, it, k0, params) }
        var best = 0
        for (g in 1 until n) {
            val c = Det.cmpScore(js[g], js[best])
            val closer = Det.cmpScore(kotlin.math.abs(lks[g] - lnK0), kotlin.math.abs(lks[best] - lnK0))
            if (c < 0 || (c == 0 && (closer < 0 || (closer == 0 && lks[g] < lks[best])))) best = g
        }
        val sigma = params.double("rcm.sigma_lnk")
        val sd = if (best == 0 || best == n - 1) sigma else {
            val second = (js[best + 1] - 2.0 * js[best] + js[best - 1]) / (h * h)
            if (second <= 0.0) sigma else Det.sqrt(1.0 / second)
        }
        return GridResult(Det.exp(lks[best]), sd, js[best])
    }

    /** `J(k)` of B5.1 at `ln k` [lnK]: Huber loss of the standardized window residuals plus the log-normal prior. */
    private fun objective(windows: List<List<Obs>>, lnK: Double, k0: Double, params: ParamView): Double {
        val k = Det.exp(lnK)
        val delta = params.double("rcm.huber_delta")
        val sigma = params.double("rcm.sigma_lnk")
        var total = 0.0
        for (w in windows) {
            val e = w.map { Det.ln(it.p.set.wSys!!) + Det.ln(1.0 + (it.p.nFresh!! - 1.0) / k) }
            val mu = e.indices.sumOf { e[it] / (w[it].s * w[it].s) } / w.sumOf { 1.0 / (it.s * it.s) }
            for (i in w.indices) total += huber((e[i] - mu) / w[i].s, delta)
        }
        val d = lnK - Det.ln(k0)
        return total + d * d / (2.0 * sigma * sigma)
    }

    private fun residuals(windows: List<List<Obs>>, k: Double): Map<String, Double> {
        val out = java.util.TreeMap<String, Double>()
        for (w in windows) {
            val e = w.map { Det.ln(it.p.set.wSys!!) + Det.ln(1.0 + (it.p.nFresh!! - 1.0) / k) }
            val mu = e.indices.sumOf { e[it] / (w[it].s * w[it].s) } / w.sumOf { 1.0 / (it.s * it.s) }
            w.forEachIndexed { i, o -> out[o.p.set.id] = e[i] - mu }
        }
        return out
    }

    private fun huber(x: Double, d: Double): Double {
        val a = kotlin.math.abs(x)
        return if (a <= d) x * x / 2.0 else d * (a - d / 2.0)
    }

    private data class Five(val kTarget: Double, val kHat: Double?, val sdLnk: Double, val jMin: Double?, val jK0: Double?)
}
