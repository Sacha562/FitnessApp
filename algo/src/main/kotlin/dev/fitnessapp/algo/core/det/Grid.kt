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

    fun round(x: Double, kind: GridKind): Double = decimal(x, kind).toDouble()

    fun kg(x: Double): Double = round(x, GridKind.KG)
    fun s(x: Double): Double = round(x, GridKind.S)
    fun ratio(x: Double): Double = round(x, GridKind.RATIO)
    fun rep(x: Double): Double = round(x, GridKind.REP)
    fun stat(x: Double): Double = round(x, GridKind.STAT)
}
