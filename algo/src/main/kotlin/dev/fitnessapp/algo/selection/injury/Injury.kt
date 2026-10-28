package dev.fitnessapp.algo.selection.injury

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import dev.fitnessapp.algo.selection.catalog.CatalogExercise

/** A penalty window of the contract (C12, mode `penalty`) active at the block's time. */
data class PenaltyWindow(val id: String, val groups: List<String>, val regions: List<String>, val protectsGrip: Boolean)

/** Why INJ-1 excludes an exercise: the flagged joint, its severity and the exercise's stress on it. */
data class JointExclusion(val joint: String, val severity: Int, val stress: Int)

/**
 * INJ-1 (ALGORITHMS_PLANNING 18, C12, C33): the joint flags of the `SafetyState` (joint -> severity 1-3) exclude an
 * exercise when its joint stress reaches the level `injury.exclude_levels` gives for the severity (3 -> js >= 1,
 * 2 -> js >= 2); severity 1 only penalises (SEL-1's per-set penalty, SUB-1's `inj` term). Penalty windows add
 * `injury.window_set_pen` per set. Joints are walked in the catalog's canonical order.
 */
object Injury : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("injury")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.table("injury.exclude_levels", "joint-stress level (by flag severity)", 1.0, 3.0),
        ParamSpec.number("injury.joint_pen", "J per stress unit per set", 0.0, 1.0),
        ParamSpec.number("injury.window_set_pen", "J per set", 0.0, 2.0),
    )

    /** The first joint (canonical order) whose flag excludes [e], or null when it stays in the pool. */
    fun excludes(e: CatalogExercise, flags: Map<String, Int>, params: ParamView): JointExclusion? {
        val levels = params.table("injury.exclude_levels")
        for (joint in CatalogDomains.JOINTS) {
            val sev = flags[joint] ?: continue
            val min = levels[sev.toString()] ?: continue
            if (e.js(joint) >= min) return JointExclusion(joint, sev, e.js(joint))
        }
        return null
    }

    /** SUB-1's `inj` term (15.1): the max over flagged joints of `(js / 3) * (severity / 3)`, on the RATIO grid. */
    fun subTerm(e: CatalogExercise, flags: Map<String, Int>): Double =
        Grid.ratio(CatalogDomains.JOINTS.filter { (flags[it] ?: 0) > 0 }.maxOfOrNull { (e.js(it) / 3.0) * (flags.getValue(it) / 3.0) } ?: 0.0)

    /** SEL-1's per-set penalty for severity-1 flags: `JOINT_PEN * js_j(e)` summed over the joints flagged at 1. */
    fun jointPenaltyPerSet(e: CatalogExercise, flags: Map<String, Int>, params: ParamView): Double =
        Grid.ratio(params.double("injury.joint_pen") * CatalogDomains.JOINTS.filter { flags[it] == 1 }.sumOf { e.js(it) })

    /** The per-set penalty of the penalty windows active at the block's time (18.2). */
    fun windowPenaltyPerSet(e: CatalogExercise, windows: List<PenaltyWindow>, catalog: Catalog, params: ParamView): Double {
        val pen = params.double("injury.window_set_pen")
        val hits = windows.sortedBy { it.id }.count { w ->
            w.groups.any { catalog.groupCoeff(e, it) >= HIT_COEFF } || w.regions.any { (e.stimulus[it] ?: 0.0) >= HIT_COEFF } ||
                (e.gripDemand >= 2 && w.protectsGrip)
        }
        return Grid.ratio(pen * hits)
    }

    private const val HIT_COEFF = 0.5
}
