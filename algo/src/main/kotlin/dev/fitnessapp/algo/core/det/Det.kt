package dev.fitnessapp.algo.core.det

/**
 * Deterministic maths (ALGORITHMS.md A3.1, P5, P12). Transcendental functions go through StrictMath so
 * the JVM and ART produce identical bits; score comparisons use [cmpScore] with [TIE_EPS].
 */
object Det {
    const val TIE_EPS: Double = 1e-9

    fun exp(x: Double): Double = StrictMath.exp(x)
    fun ln(x: Double): Double = StrictMath.log(x)
    fun pow(x: Double, y: Double): Double = StrictMath.pow(x, y)
    fun sqrt(x: Double): Double = StrictMath.sqrt(x)

    /** -1, 0 or 1; scores within [TIE_EPS] (absolute) are equal and the caller's tie-break decides. */
    fun cmpScore(a: Double, b: Double): Int = when {
        a - b > TIE_EPS -> 1
        b - a > TIE_EPS -> -1
        else -> 0
    }
}
