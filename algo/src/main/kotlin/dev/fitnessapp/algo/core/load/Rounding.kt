package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.core.params.ParamView

/**
 * B2.5 rounding directions over a canonical option list (from [Loadables]). Every direction is defined on
 * the system-load scale, where easier = lower (less external load or more assistance), so pins round
 * through the same functions and "nearest" ties go to more assistance (C2).
 *
 * Targets are on the [LoadPoint.scaleKg] scale by default; pass `key = { it.wSys!! }` with a `w_sys` target
 * when every option has a defined system load. Ordinal options are not on any kg scale and are rejected.
 */
object Rounding {
    private val SCALE: (LoadPoint) -> Double = { it.scaleKg }

    /** "down": the hardest option at or below the target; if none, the easiest option. */
    fun easierOrEqual(w: List<LoadPoint>, target: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): LoadPoint {
        val eps = eps(w, params)
        return w.lastOrNull { key(it) <= target + eps } ?: w.first()
    }

    /** "up": the easiest option at or above the target; if none, the hardest option. */
    fun harderOrEqual(w: List<LoadPoint>, target: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): LoadPoint {
        val eps = eps(w, params)
        return w.firstOrNull { key(it) >= target - eps } ?: w.last()
    }

    /** The closest option; distances within epsilon tie, and ties go to the easier option. */
    fun nearest(w: List<LoadPoint>, target: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): LoadPoint {
        val eps = eps(w, params)
        var best = w.first()
        var bestD = kotlin.math.abs(key(best) - target)
        for (p in w.drop(1)) {
            val d = kotlin.math.abs(key(p) - target)
            if (d < bestD - eps) {
                best = p
                bestD = d
            }
        }
        return best
    }

    /** The neighbour harder than [from], or null at the top. */
    fun nextHarder(w: List<LoadPoint>, from: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): LoadPoint? {
        val eps = eps(w, params)
        return w.firstOrNull { key(it) > from + eps }
    }

    /** The neighbour easier than [from], or null at the bottom. */
    fun nextEasier(w: List<LoadPoint>, from: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): LoadPoint? {
        val eps = eps(w, params)
        return w.lastOrNull { key(it) < from - eps }
    }

    /** The two keypad chips of UI-04: nearest lower-or-equal and nearest higher-or-equal option. */
    fun bracket(w: List<LoadPoint>, target: Double, params: ParamView, key: (LoadPoint) -> Double = SCALE): Pair<LoadPoint?, LoadPoint?> {
        val eps = eps(w, params)
        return w.lastOrNull { key(it) <= target + eps } to w.firstOrNull { key(it) >= target - eps }
    }

    private fun eps(w: List<LoadPoint>, params: ParamView): Double {
        require(w.isNotEmpty()) { "no loadable options" }
        require(w.none { it.ordinal }) { "ordinal options have no kg scale; round on the level" }
        return params.double("load.epsilon_kg")
    }
}
