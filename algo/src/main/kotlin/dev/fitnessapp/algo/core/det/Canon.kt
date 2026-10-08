package dev.fitnessapp.algo.core.det

import java.math.BigDecimal

/**
 * Canonical number text (ALGORITHMS.md A3.4): the ECMAScript Number.prototype.toString form that
 * RFC 8785 (JCS) requires, produced from the grid decimal without any double-to-string routine.
 */
object Canon {
    const val MAX_SIGNIFICANT_DIGITS: Int = 15

    /** Grid-rounds [x] and renders it in ECMAScript form. */
    fun esNumber(x: Double, kind: GridKind): String = esDecimal(Grid.decimal(x, kind))

    /** ECMAScript form of an integer (counts, reps, epoch milliseconds). */
    fun esInt(x: Long): String = esDecimal(BigDecimal(x))

    /**
     * ECMAScript form of a decimal. The decimal must have at most 15 significant digits, so that the
     * shortest round-trip text of the nearest double equals its own digits.
     */
    fun esDecimal(value: BigDecimal): String {
        if (value.signum() == 0) return "0"
        val d = value.stripTrailingZeros()
        val s = d.unscaledValue().abs().toString()
        val k = s.length
        require(k <= MAX_SIGNIFICANT_DIGITS) { "number has $k significant digits; grid-round it first" }
        val n = k - d.scale()
        val out = when {
            n in k..21 -> s + "0".repeat(n - k)
            n in 1..21 -> s.substring(0, n) + "." + s.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + s
            else -> {
                val e = n - 1
                val exponent = if (e >= 0) e else -e
                s.substring(0, 1) + (if (k > 1) "." + s.substring(1) else "") +
                    "e" + (if (e >= 0) "+" else "-") + exponent.toString()
            }
        }
        return if (d.signum() < 0) "-$out" else out
    }
}
