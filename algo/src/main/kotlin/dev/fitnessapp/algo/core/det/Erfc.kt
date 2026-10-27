package dev.fitnessapp.algo.core.det

/**
 * The complementary error function by the Numerical Recipes rational approximation (fractional error below 1.2e-7,
 * ALGORITHMS.md B14.1), with `exp` through StrictMath so every runtime gives the same bits. The coefficients are the
 * approximation's, not tunables.
 */
object Erfc {
    fun erfc(x: Double): Double {
        val z = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val poly = -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
            t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))))
        val ans = t * Det.exp(poly)
        return if (x >= 0.0) ans else 2.0 - ans
    }

    /** The standard normal CDF `Phi(x) = 0.5 * erfc(-x / sqrt(2))`. */
    fun phi(x: Double): Double = 0.5 * erfc(-x / Det.sqrt(2.0))
}
