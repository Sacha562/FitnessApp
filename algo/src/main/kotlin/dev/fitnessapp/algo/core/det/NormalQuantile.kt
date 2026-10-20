package dev.fitnessapp.algo.core.det

/**
 * Inverse of the standard normal CDF by Acklam's rational approximation (relative error below 1.15e-9),
 * evaluated with [Det] (StrictMath) so that every runtime returns the same bits. Used by FitExpr
 * `userKQuantile` (CHALLENGE_ENGINE 3.8). The coefficients are the published constants of the method.
 */
object NormalQuantile {
    private val A = doubleArrayOf(
        -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
        1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
    )
    private val B = doubleArrayOf(
        -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
        6.680131188771972e+01, -1.328068155288572e+01,
    )
    private val C = doubleArrayOf(
        -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
        -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
    )
    private val D = doubleArrayOf(
        7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00,
    )
    private const val P_LOW = 0.02425

    /** z with P(Z <= z) = [p], for p in (0, 1). */
    fun z(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p must be in (0, 1)" }
        return when {
            p < P_LOW -> tail(p)
            p <= 1.0 - P_LOW -> {
                val q = p - 0.5
                val r = q * q
                (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q /
                    (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1.0)
            }
            else -> -tail(1.0 - p)
        }
    }

    private fun tail(p: Double): Double {
        val q = Det.sqrt(-2.0 * Det.ln(p))
        return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) /
            ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1.0)
    }
}
