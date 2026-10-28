package dev.fitnessapp.algo.selection.elig

import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.injury.Injury
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import java.util.TreeMap

/** Why ELIG-1 removed an exercise (12.3), in the order the filters are tried. */
enum class EligReason(val code: String) {
    RETIRED("retired"), HIDDEN("hidden"), NEVER("never"), STATION("station_missing"), INJURY("injury"),
    SAFETY_TAG("safety_tag"), CHALLENGE_ONLY("challenge_only"), GAP_DAY_TAG("gap_day_tag"), HARD_WINDOW("hard_window"),
    GAP_DAY_PULL("gap_day_pull"), SKIP_GOAL("skip_goal"), ADDON_SLOT("addon_slot"),
}

/** A hard protected window active at the block's time (C12): the groups and regions it forbids. */
data class HardWindow(val id: String, val groups: List<String>, val regions: List<String>)

/** The add-on block's slot filters (C34, the contract's `addOnSlot`) and the groups of its add-on family. */
data class AddOnFilter(val allowPatterns: List<String>, val maxGripDemand: Int?, val forbidGroups: List<String>, val familyGroups: List<String>)

/** C50's light pull on a gap day: the limits a PULL-family exercise must keep to stay. */
data class LightPullRule(val maxGripDemand: Int, val bicepsCoefficientBelow: Double)

/**
 * The constraints of one block (12.2) without the allocator's targets (D49: the necessity gate arrives with M4).
 * [availableStations] null = no gym profile yet, every station counts as available. [prefs] by slug; a missing entry
 * is neutral. [skipGroups] are the groups with goal `skip` (none until the Muscle goals of M4).
 */
data class EligContext(
    val availableStations: Set<String>? = null,
    val prefs: Map<String, PrefValue> = emptyMap(),
    val jointFlags: Map<String, Int> = emptyMap(),
    val forbidExerciseTags: List<String> = emptyList(),
    val gapDay: Boolean = false,
    val forbiddenSessionTags: List<String> = emptyList(),
    val hardWindows: List<HardWindow> = emptyList(),
    val lightPull: LightPullRule? = null,
    val skipGroups: Set<String> = emptySet(),
    val addOn: AddOnFilter? = null,
    val retired: Set<String> = emptySet(),
)

/** The filter output: the pool by slug, the removed exercises with their first reason, and counts per reason. */
data class EligResult(val pool: List<CatalogExercise>, val removed: Map<String, EligReason>, val filtered: Map<String, Int>)

/**
 * ELIG-1's hard filters (ALGORITHMS_PLANNING 12.3, C11, C12, C26, C33, C34, C50; D49 builds them in M3.6 without the
 * necessity gate). Until the Muscle goals of M4, C50's `onlyIfGoalsAsk` clause is read as satisfied, as WE-S4 does.
 */
object Elig : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("elig")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("elig.avail_coeff", "coeff", 0.5, 1.0),
        ParamSpec.number("elig.forbid_coeff", "coeff", 0.25, 0.75),
        ParamSpec.number("elig.gate_improve", "coeff", 0.5, 1.0),
        ParamSpec.number("elig.gate_maintain", "coeff", 0.25, 0.75),
        ParamSpec.number("elig.skip_coeff", "coeff", 0.25, 0.75),
        ParamSpec.number("elig.unlock_repeat_frac", "ratio of T", 0.0, 1.0),
    )

    private val SESSION_TAG_FILTER = setOf("grip_heavy", "elbow_flexor_heavy")
    private val GENERAL_FORBIDDEN_TAGS = setOf("challenge", "heavy_unilateral")

    fun pool(catalog: Catalog, ctx: EligContext, params: ParamView): EligResult {
        val pool = ArrayList<CatalogExercise>()
        val removed = TreeMap<String, EligReason>()
        for (e in catalog.exercises) {
            val r = reason(e, catalog, ctx, params)
            if (r == null) pool += e else removed[e.slug] = r
        }
        val filtered = TreeMap<String, Int>()
        removed.values.forEach { filtered[it.code] = (filtered[it.code] ?: 0) + 1 }
        return EligResult(pool, removed, filtered)
    }

    /** The first filter of 12.3 that removes [e], or null when it is eligible. */
    fun reason(e: CatalogExercise, catalog: Catalog, ctx: EligContext, params: ParamView): EligReason? {
        val forbid = params.double("elig.forbid_coeff")
        val pref = ctx.prefs[e.slug]
        val tags = catalog.effectiveTags(e)
        fun g(id: String) = catalog.groupCoeff(e, id)
        return when {
            e.slug in ctx.retired -> EligReason.RETIRED
            pref?.hidden == true -> EligReason.HIDDEN
            pref?.tier == PrefTier.NEVER -> EligReason.NEVER
            ctx.availableStations != null && !ctx.availableStations.containsAll(e.requiresStation) -> EligReason.STATION
            Injury.excludes(e, ctx.jointFlags, params) != null -> EligReason.INJURY
            tags.any { it in ctx.forbidExerciseTags } -> EligReason.SAFETY_TAG
            tags.any { it in GENERAL_FORBIDDEN_TAGS } -> EligReason.CHALLENGE_ONLY
            tags.any { it in ctx.forbiddenSessionTags && it in SESSION_TAG_FILTER } -> EligReason.GAP_DAY_TAG
            ctx.hardWindows.any { w -> w.groups.any { g(it) >= forbid } || w.regions.any { (e.stimulus[it] ?: 0.0) >= forbid } } ->
                EligReason.HARD_WINDOW
            ctx.gapDay && pullFamily(e, catalog, forbid) && !light(e, catalog, ctx.lightPull) -> EligReason.GAP_DAY_PULL
            ctx.skipGroups.any { g(it) >= params.double("elig.skip_coeff") } -> EligReason.SKIP_GOAL
            ctx.addOn != null && !addOnFits(e, catalog, ctx.addOn, forbid) -> EligReason.ADDON_SLOT
            else -> null
        }
    }

    private fun pullFamily(e: CatalogExercise, catalog: Catalog, forbid: Double): Boolean =
        catalog.taxonomy.activeGroups.any { it.family == "pull" && catalog.groupCoeff(e, it.id) >= forbid }

    private fun light(e: CatalogExercise, catalog: Catalog, rule: LightPullRule?): Boolean =
        rule != null && e.gripDemand <= rule.maxGripDemand && catalog.groupCoeff(e, "biceps") < rule.bicepsCoefficientBelow

    private fun addOnFits(e: CatalogExercise, catalog: Catalog, a: AddOnFilter, forbid: Double): Boolean =
        (a.allowPatterns.isEmpty() || e.pattern in a.allowPatterns) &&
            (a.maxGripDemand == null || e.gripDemand <= a.maxGripDemand) &&
            a.forbidGroups.none { catalog.groupCoeff(e, it) >= forbid } &&
            (a.familyGroups.isEmpty() || primaryGroup(e, catalog) in a.familyGroups)

    /** The group with the largest coefficient, ties by canonical order (12.3). */
    fun primaryGroup(e: CatalogExercise, catalog: Catalog): String? {
        var best: String? = null
        var bestC = 0.0
        for (g in catalog.taxonomy.activeGroups) {
            val c = catalog.groupCoeff(e, g.id)
            if (c > bestC) { best = g.id; bestC = c }
        }
        return best
    }
}
