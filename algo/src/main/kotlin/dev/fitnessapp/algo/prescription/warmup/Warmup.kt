package dev.fitnessapp.algo.prescription.warmup

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.rcm.RepCurve

/**
 * The inputs of one exercise's warm-up (B10). [wWork] is the first working `w_sys`; [m] SSE-1's posterior mean (null
 * without a state); [loadables] the system loads of the equipment (any order); [bodyweightW] `f_bw * BW` for the
 * bodyweight family (null otherwise) and [assisted] the assisted machine's system loads when the exercise allows
 * that mode and the machine exists. [warm]: the primary group already got `warmup.warm_min_sets` working sets today;
 * [firstForGroup]: the first exercise of today for its primary group.
 */
data class WarmupInput(
    val wWork: Double,
    val m: Double?,
    val k: Double,
    val isolation: Boolean,
    val loadables: List<Double>,
    val bodyweightW: Double? = null,
    val assisted: List<Double> = emptyList(),
    val warm: Boolean = false,
    val firstForGroup: Boolean = true,
    val rStar: Int? = null,
    val testSession: Boolean = false,
)

enum class WarmupTier(val code: String) { NONE("none"), LOW("low"), MID("mid"), HIGH("high") }

/** One warm-up set: its system load, reps, the rest after it and the tier fraction it came from. */
data class WarmupSet(val wSys: Double, val reps: Int, val restS: Double, val frac: Double, val bodyweight: Boolean)

data class WarmupPlan(val tier: WarmupTier, val pW: Double?, val sets: List<WarmupSet>)

/**
 * WARMUP-1 (ALGORITHMS.md B10): brief specific warm-up sets scaled to the intensity `p_w = w_work / M`. A challenge
 * warm-up block replaces it for that session (the caller does not call it then). Without an SSE-1 state `p_w` is
 * unknown and a compound gets the mid tier (M3.3 build note).
 */
object Warmup : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("warmup")

    override val paramSpecs: List<ParamSpec> = WarmupRest.paramSpecs + listOf(
        ParamSpec.number("warmup.max_cap_frac", "of capacity", 0.3, 0.7),
        ParamSpec.number("warmup.p_high", "ratio of M", 0.6, 0.95),
        ParamSpec.number("warmup.p_mid", "ratio of M", 0.4, 0.8),
        ParamSpec.table("warmup.tier_high", "-", 0.3, 15.0),
        ParamSpec.table("warmup.tier_low", "-", 0.3, 15.0),
        ParamSpec.table("warmup.tier_mid", "-", 0.3, 15.0),
        ParamSpec.table("warmup.tier_singles", "-", 0.3, 15.0),
        ParamSpec.integer("warmup.warm_min_sets", "sets", 1.0, 5.0),
    )

    fun plan(input: WarmupInput, params: ParamView): WarmupPlan {
        val pW = input.m?.let { input.wWork / it }
        val tier = when {
            input.isolation || input.warm || (pW != null && pW < params.double("warmup.p_mid")) ->
                if (input.firstForGroup && !input.warm) WarmupTier.LOW else WarmupTier.NONE
            pW == null || pW < params.double("warmup.p_high") -> WarmupTier.MID
            else -> WarmupTier.HIGH
        }
        val steps = when (tier) {
            WarmupTier.NONE -> emptyList()
            WarmupTier.LOW -> tier(params, "warmup.tier_low")
            WarmupTier.MID -> tier(params, "warmup.tier_mid")
            WarmupTier.HIGH -> tier(params, "warmup.tier_high") +
                (if ((input.rStar != null && input.rStar <= 3) || input.testSession) tier(params, "warmup.tier_singles") else emptyList())
        }
        val maxCap = params.double("warmup.max_cap_frac")
        val eps = params.double("load.epsilon_kg")
        val raw = ArrayList<WarmupSet>()
        for ((frac, reps) in steps) {
            val target = frac * input.wWork
            val bw = input.bodyweightW
            val (w, isBw) = when {
                bw != null && target < bw - eps -> {
                    val a = easierOrEqual(input.assisted, target, eps)
                    if (a != null) a to false else bw to true
                }
                else -> (easierOrEqual(input.loadables, target, eps) ?: continue) to false
            }
            // At most half of the capacity at that load (B10); without a model the tier's reps stand.
            val capReps = input.m?.let { kotlin.math.floor(maxCap * RepCurve.cap(w, it, input.k)).toInt() } ?: reps
            raw += WarmupSet(Grid.kg(w), maxOf(1, minOf(reps, capReps)), 0.0, frac, isBw)
        }
        // De-duplicate equal loads (keep the higher reps and the first fraction), drop sets at or above the work load.
        val merged = ArrayList<WarmupSet>()
        for (s in raw) {
            if (s.wSys >= Grid.kg(input.wWork) - eps) continue
            val i = merged.indexOfFirst { kotlin.math.abs(it.wSys - s.wSys) < eps }
            if (i < 0) merged += s else if (s.reps > merged[i].reps) merged[i] = merged[i].copy(reps = s.reps)
        }
        val withRest = merged.mapIndexed { i, s ->
            s.copy(restS = WarmupRest.afterWarmup(s.frac, i == merged.size - 1, params))
        }
        return WarmupPlan(tier, pW?.let { Grid.ratio(it) }, withRest)
    }

    private fun tier(params: ParamView, key: String): List<Pair<Double, Int>> {
        val t = params.tableArrays(key)
        val fracs = t.getValue("fracs")
        val reps = t.getValue("reps")
        return fracs.indices.map { fracs[it] to reps[it].toInt() }
    }

    private fun easierOrEqual(options: List<Double>, x: Double, eps: Double): Double? = options.filter { it <= x + eps }.maxOrNull()
}
