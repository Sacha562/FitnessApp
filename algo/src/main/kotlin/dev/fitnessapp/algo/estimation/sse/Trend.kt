package dev.fitnessapp.algo.estimation.sse

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import java.time.Instant

/**
 * The local-linear-trend state in log space shared by SSE-1 (`L = ln M`, B6.1) and HOLD-1 (`ln T`, B8.2): level
 * [l], weekly trend [g], their covariance, the Instant of the last update and the number of observations used.
 * Stored on the STAT grid.
 */
data class TrendState(
    val l: Double,
    val g: Double,
    val pLL: Double,
    val pLg: Double,
    val pgg: Double,
    val tLast: Instant,
    val nObs: Int,
) {
    fun rounded(): TrendState = copy(l = Grid.stat(l), g = Grid.stat(g), pLL = Grid.stat(pLL), pLg = Grid.stat(pLg), pgg = Grid.stat(pgg))
}

/** One observation for the trend update: value [z] (log space) and variance [s2] (class multiplier included). */
data class TrendObs(val z: Double, val s2: Double)

/** The outcome of one session update; [nu], [s], [k0], [k1] are the innovation statistics of the NORMAL step. */
data class TrendUpdate(
    val state: TrendState,
    val huberApplied: Boolean,
    val lbApplied: Boolean,
    val nu: Double?,
    val s: Double?,
    val k0: Double?,
    val k1: Double?,
)

/** The predict and update equations of SSE-1 (ALGORITHMS.md B6.2, B6.4), also used by HOLD-1 with its own `sd_day`. */
object Trend {
    /**
     * Predict to [at] (B6.2): the trend continues through `sse.grace_days`; beyond it, detraining lowers the level
     * (capped), zeroes the trend and widens the variance (C27). Elapsed time is real time, so a 25-hour DST day counts
     * as 25 hours. A prediction to an Instant at or before [TrendState.tLast] returns the state unchanged.
     */
    fun predict(t: TrendState, at: Instant, params: ParamView): TrendState {
        val dd = Week.elapsedHours(t.tLast, at) / 24.0
        if (dd <= 0.0) return t
        val grace = params.double("sse.grace_days")
        if (dd <= grace) return predictTrend(t, dd / 7.0, params)
        val p = predictTrend(t, grace / 7.0, params)
        val wd = (dd - grace) / 7.0
        val qL = params.double("sse.q_l")
        val sg0 = params.double("sse.sg0")
        return p.copy(
            l = p.l - minOf(params.double("sse.detrain_cap"), params.double("sse.detrain_per_week") * wd),
            g = 0.0,
            pLL = p.pLL + qL * qL * wd + params.double("sse.detrain_var_per_week") * wd,
            pLg = 0.0,
            pgg = sg0 * sg0,
        )
    }

    private fun predictTrend(t: TrendState, wk: Double, params: ParamView): TrendState {
        val rhoWk = Det.exp(wk * Det.ln(params.double("sse.rho")))
        val qL = params.double("sse.q_l")
        val qG = params.double("sse.q_g")
        return t.copy(
            l = t.l + t.g * wk,
            g = t.g * rhoWk,
            pLL = t.pLL + 2.0 * wk * t.pLg + wk * wk * t.pgg + qL * qL * wk,
            pLg = rhoWk * (t.pLg + wk * t.pgg),
            pgg = rhoWk * rhoWk * t.pgg + qG * qG * wk,
        )
    }

    /** A fresh state at level [l] with level variance [pLL] (B6.4 cold start and transfer prior). */
    fun start(l: Double, pLL: Double, at: Instant, params: ParamView): TrendState {
        val sg0 = params.double("sse.sg0")
        return TrendState(l, 0.0, pLL, 0.0, sg0 * sg0, at, 0)
    }

    /**
     * The session update of B6.4 on the predicted [prior]: one NORMAL step over [normal] (day-level noise [sdDay]
     * counted once, robust gate), then the LOWER BOUND step with the best of [lowerBounds], which can only raise the
     * level. [used] observations are added to `n_obs`; `t_last` becomes [at]. Not rounded.
     */
    fun update(
        prior: TrendState,
        normal: List<TrendObs>,
        lowerBounds: List<TrendObs>,
        sdDay: Double,
        at: Instant,
        used: Int,
        params: ParamView,
    ): TrendUpdate {
        var t = prior
        var huber = false
        var nu: Double? = null
        var sOut: Double? = null
        var k0: Double? = null
        var k1: Double? = null
        if (normal.isNotEmpty()) {
            val w = normal.sumOf { 1.0 / it.s2 }
            val zbar = normal.sumOf { it.z / it.s2 } / w
            var r = sdDay * sdDay + 1.0 / w
            val innovation = zbar - t.l
            var s = t.pLL + r
            val gate = params.double("sse.huber_gate_sd")
            if (kotlin.math.abs(innovation) > gate * Det.sqrt(s)) {
                val ratio = kotlin.math.abs(innovation) / (gate * Det.sqrt(s))
                r *= ratio * ratio
                s = t.pLL + r
                huber = true
            }
            val step = kalman(t, innovation, s)
            t = step.first
            nu = innovation
            sOut = s
            k0 = step.second
            k1 = step.third
        }
        var lb = false
        val best = lowerBounds.maxWithOrNull(compareBy<TrendObs> { it.z }.thenBy { -it.s2 })
        if (best != null && best.z > t.l + params.double("sse.lb_gate_sd") * Det.sqrt(t.pLL)) {
            val r = params.double("sse.lb_r_mult") * (sdDay * sdDay + best.s2)
            t = kalman(t, best.z - t.l, t.pLL + r).first
            lb = true
        }
        return TrendUpdate(t.copy(tLast = at, nObs = prior.nObs + used), huber, lb, nu, sOut, k0, k1)
    }

    /** The Kalman step with innovation [nu] and innovation variance [s]; every right-hand side uses the prior. */
    private fun kalman(t: TrendState, nu: Double, s: Double): Triple<TrendState, Double, Double> {
        val k0 = t.pLL / s
        val k1 = t.pLg / s
        val next = t.copy(
            l = t.l + k0 * nu,
            g = t.g + k1 * nu,
            pLL = t.pLL - t.pLL * t.pLL / s,
            pLg = t.pLg - t.pLL * t.pLg / s,
            pgg = t.pgg - t.pLg * t.pLg / s,
        )
        return Triple(next, k0, k1)
    }
}
