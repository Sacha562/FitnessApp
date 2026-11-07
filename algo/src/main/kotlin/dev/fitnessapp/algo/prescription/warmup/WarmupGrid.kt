package dev.fitnessapp.algo.prescription.warmup

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.load.LoadModel
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.params.ParamView

/**
 * D106 (1): the warm-up grid of WARMUP-1. Warm-ups need no fine loads, so on plate-loaded equipment (a barbell, a
 * plate-loaded machine, the Smith machine) only options whose shown load (bar and plates, without a machine's own mass)
 * is a multiple of `warmup.grid_plates_kg` count, and with held plates or the belt multiples of `warmup.grid_belt_kg`.
 * Dumbbells, stacks and assistance pins keep every option (null).
 */
object WarmupGrid {
    /** The system loads (`w_sys`, ascending) of the [points] of [scheme] on the warm-up grid; null = no grid. */
    fun options(scheme: LoadScheme, points: List<LoadPoint>, params: ParamView): List<Double>? {
        val key = when (scheme) {
            is LoadScheme.Plates -> "warmup.grid_plates_kg"
            is LoadScheme.Belt -> "warmup.grid_belt_kg"
            else -> return null
        }
        // Params before 1.0.8 have no grid: the warm-ups use every loadable, as WARMUP-1 1.0.0 did.
        if (!params.has(key)) return null
        val step = params.double(key)
        val stepCents = LoadModel.cents(step)
        if (stepCents <= 0L) return null
        return points.filter { p -> p.wSys != null && !p.ordinal && LoadModel.cents(p.addedKg) % stepCents == 0L }
            .map { Grid.kg(it.wSys!!) }.distinct().sorted()
    }
}
