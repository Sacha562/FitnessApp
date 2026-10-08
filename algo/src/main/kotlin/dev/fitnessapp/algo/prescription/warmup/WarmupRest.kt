package dev.fitnessapp.algo.prescription.warmup

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry

/**
 * The rest rule of WARMUP-1 (ALGORITHMS.md B10), which REST-1 uses around warm-up sets (B11.2): 45 s after
 * a light warm-up set, 60 s after a heavy one (fraction >= 0.65 of the working load) and 90 s before the
 * first working set. The warm-up set generator itself lands with M3.
 */
object WarmupRest : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("warmup")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("warmup.rest_heavy_from_frac", "ratio", 0.5, 0.9),
        ParamSpec.number("warmup.rest_heavy_s", "s", 30.0, 120.0),
        ParamSpec.number("warmup.rest_light_s", "s", 30.0, 90.0),
        ParamSpec.number("warmup.rest_to_work_s", "s", 60.0, 180.0),
    )

    /** Rest after a warm-up set of [frac] x working load (null = unknown, treated as light). */
    fun afterWarmup(frac: Double?, nextIsFirstWorking: Boolean, params: ParamView): Double = Grid.s(
        when {
            nextIsFirstWorking -> params.double("warmup.rest_to_work_s")
            (frac ?: 0.0) >= params.double("warmup.rest_heavy_from_frac") -> params.double("warmup.rest_heavy_s")
            else -> params.double("warmup.rest_light_s")
        },
    )
}
