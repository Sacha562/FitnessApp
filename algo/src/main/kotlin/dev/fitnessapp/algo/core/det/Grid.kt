package dev.fitnessapp.algo.core.det

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Grid ids of ALGORITHMS.md A3.2 (C22, C42). */
enum class GridKind { KG, S, RATIO, REP, STAT }

/**
 * Canonical rounding (ALGORITHMS.md A3.3). The exact BigDecimal(Double) constructor is mandatory:
 * BigDecimal.valueOf goes through Double.toString, which differs between runtimes (P13).
 */
object Grid {
    private val STAT_MC = MathContext(12, RoundingMode.HALF_EVEN)

    fun decimal(x: Double, kind: GridKind): BigDecimal {
        require(!x.isNaN() && !x.isInfinite()) { "non-finite value on grid $kind" }
        val exact = BigDecimal(x)
        return when (kind) {
            GridKind.KG -> exact.setScale(2, RoundingMode.HALF_EVEN)
            GridKind.S -> exact.setScale(1, RoundingMode.HALF_EVEN)
            GridKind.RATIO -> exact.setScale(4, RoundingMode.HALF_EVEN)
            GridKind.REP -> exact.setScale(2, RoundingMode.HALF_EVEN)
            GridKind.STAT -> exact.round(STAT_MC)
        }
    }

    fun round(x: Double, kind: GridKind): Double {
        val scale = when (kind) {
            GridKind.KG, GridKind.REP -> 1e2
            GridKind.S -> 1e1
            GridKind.RATIO -> 1e4
            GridKind.STAT -> return decimal(x, kind).toDouble()
        }
        return fast(x, scale) ?: decimal(x, kind).toDouble()
    }

    /**
     * The same result as [decimal] for a fixed scale, without building the exact BigDecimal (the planners round
     * hundreds of thousands of values per run). `y = x * scale` carries an error below 1e-6 for |x| < 1e6, so unless
     * its fraction is within 1e-5 of one half the nearest integer `k` is certain, and `k / scale` is the correctly
     * rounded double of the decimal `k * 10^-n`, as `BigDecimal.toDouble` is. Near a half (or out of range) the
     * exact path decides; `null` asks for it.
     */
    private fun fast(x: Double, scale: Double): Double? {
        if (x.isNaN() || x.isInfinite() || kotlin.math.abs(x) >= 1e6) return null
        val y = x * scale
        val f = kotlin.math.floor(y)
        val frac = y - f
        if (kotlin.math.abs(frac - 0.5) <= 1e-5) return null
        val k = if (frac < 0.5) f else f + 1.0
        return (k / scale) + 0.0 // + 0.0 turns -0.0 into 0.0, as BigDecimal has no negative zero
    }

    /** The same grid for an exact decimal (FitExpr values, C22). */
    fun decimal(x: BigDecimal, kind: GridKind): BigDecimal = when (kind) {
        GridKind.KG -> x.setScale(2, RoundingMode.HALF_EVEN)
        GridKind.S -> x.setScale(1, RoundingMode.HALF_EVEN)
        GridKind.RATIO -> x.setScale(4, RoundingMode.HALF_EVEN)
        GridKind.REP -> x.setScale(2, RoundingMode.HALF_EVEN)
        GridKind.STAT -> x.round(STAT_MC)
    }

    fun kg(x: Double): Double = round(x, GridKind.KG)
    fun s(x: Double): Double = round(x, GridKind.S)
    fun ratio(x: Double): Double = round(x, GridKind.RATIO)
    fun rep(x: Double): Double = round(x, GridKind.REP)
    fun stat(x: Double): Double = round(x, GridKind.STAT)
}
