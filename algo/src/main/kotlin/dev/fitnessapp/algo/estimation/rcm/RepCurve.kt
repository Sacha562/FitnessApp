package dev.fitnessapp.algo.estimation.rcm

import dev.fitnessapp.algo.core.det.Det

/**
 * The generalised Epley curve of RCM-1 (ALGORITHMS.md B5, C3) with a personal flatness `k`. The only place that
 * converts between load, reps, RIR and the 1RM; review SQL never recomputes it.
 */
object RepCurve {
    /** `cap(w; M, k)`: reps to failure from fresh at load [w]. */
    fun cap(w: Double, m: Double, k: Double): Double = 1.0 + k * (m / w - 1.0)

    /** `load(n; M, k)`: the load that allows exactly [n] reps. */
    fun load(n: Double, m: Double, k: Double): Double = m / (1.0 + (n - 1.0) / k)

    /** `e1RM(w, r, q; k)`: the 1RM from [r] reps with [q] in reserve at [w]. */
    fun e1rm(w: Double, r: Double, q: Double, k: Double): Double = w * (1.0 + (r + q - 1.0) / k)

    /** `pctPerRep(n, k)`: the relative load change per rep, `-d ln w / d n`. */
    fun pctPerRep(n: Double, k: Double): Double = 1.0 / (k + n - 1.0)

    /** The interval of [e1rm] for `k * exp(-/+ z * sdLnk)`: a higher `k` gives the lower bound. */
    fun e1rmInterval(w: Double, r: Double, q: Double, k: Double, sdLnk: Double, z: Double): Pair<Double, Double> =
        e1rm(w, r, q, k * Det.exp(z * sdLnk)) to e1rm(w, r, q, k * Det.exp(-z * sdLnk))

    /** The closed form through two maximal points `(w1, n1)` and `(w2, n2)` (B5.2): `k = (w2 (n2 - 1) - w1 (n1 - 1)) / (w1 - w2)`. */
    fun twoPointK(w1: Double, n1: Double, w2: Double, n2: Double): Double = (w2 * (n2 - 1.0) - w1 * (n1 - 1.0)) / (w1 - w2)
}
