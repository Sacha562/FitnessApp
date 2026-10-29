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
 * The constraints of one block (12.2); the allocator's targets go to [Elig.gate]. [availableStations] null = no gym
 * profile yet, every station counts as available. [prefs] by slug; a missing entry is neutral. [skipGroups] are the
 * groups with goal `skip`. [goalGroups] are the groups whose goal is improve or maintain and that no challenge covers,
 * which C50's `onlyIfGoalsAsk` reads; null when the goals are not known (light pull then counts as asked for).
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
    val goalGroups: Set<String>? = null,
)

/** The filter output: the pool by slug, the removed exercises with their first reason, and counts per reason. */
data class EligResult(val pool: List<CatalogExercise>, val removed: Map<String, EligReason>, val filtered: Map<String, Int>)

/** One block's targets for the necessity gate (12.3): FS per group, goals, and this week's coverage against `T`. */
data class GateInput(
    val target: Map<String, Double>,
    val goals: Map<String, String>,
    val weeklyCoverage: Map<String, Double> = emptyMap(),
    val weeklyTarget: Map<String, Double> = emptyMap(),
    /** Disliked exercises already unlocked earlier this ISO week. */
    val unlockedThisWeek: Set<String> = emptySet(),
)

/** A disliked exercise the gate let in because nothing else trains [group] (reason `ONLY_OPTION`). */
data class Unlock(val slug: String, val group: String)

/** The gate's output (12.2): the block pool (liked ∪ unlocked), the unlocks and the groups nothing can train. */
data class GateResult(val pool: List<CatalogExercise>, val unlocked: List<Unlock>, val coverageGaps: List<String>)

/**
 * ELIG-1 (ALGORITHMS_PLANNING 12, C11, C12, C26, C33, C34, C50): the hard filters (built in M3.6, D49), the C26
 * necessity gate that lets a disliked exercise in only when no other exercise can train a group that needs work
 * ([gate], M4.3), and the allocator's group availability ([groupAvailability]). C50's `onlyIfGoalsAsk` clause reads
 * [EligContext.goalGroups]; without goals it counts as satisfied, as WE-S4 does.
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
            ctx.gapDay && pullFamily(e, catalog, forbid) && !light(e, catalog, ctx, forbid) -> EligReason.GAP_DAY_PULL
            ctx.skipGroups.any { g(it) >= params.double("elig.skip_coeff") } -> EligReason.SKIP_GOAL
            ctx.addOn != null && !addOnFits(e, catalog, ctx.addOn, forbid) -> EligReason.ADDON_SLOT
            else -> null
        }
    }

    private fun pullFamily(e: CatalogExercise, catalog: Catalog, forbid: Double): Boolean =
        catalog.taxonomy.activeGroups.any { it.family == "pull" && catalog.groupCoeff(e, it.id) >= forbid }

    /** C50 light pull: low grip, a biceps coefficient below the limit, and every pull group it trains asked for by the goals. */
    private fun light(e: CatalogExercise, catalog: Catalog, ctx: EligContext, forbid: Double): Boolean {
        val rule = ctx.lightPull ?: return false
        if (e.gripDemand > rule.maxGripDemand || catalog.groupCoeff(e, "biceps") >= rule.bicepsCoefficientBelow) return false
        val asked = ctx.goalGroups ?: return true
        return catalog.taxonomy.activeGroups.filter { it.family == "pull" && catalog.groupCoeff(e, it.id) >= forbid }.all { it.id in asked }
    }

    /**
     * The C26 necessity gate (12.3): for each group with a block target of at least one FS and goal improve or
     * maintain, if no liked or neutral exercise of [result]'s pool reaches the gate coefficient, unlock the best
     * disliked one (coefficient desc, sfr desc, slug) unless it was unlocked earlier this week and the group already has
     * half its weekly target; a group nothing in the pool reaches is a coverage gap.
     */
    fun gate(result: EligResult, catalog: Catalog, ctx: EligContext, input: GateInput, params: ParamView): GateResult {
        fun tier(e: CatalogExercise) = ctx.prefs[e.slug]?.tier ?: PrefTier.NEUTRAL
        val liked = result.pool.filter { tier(it) != PrefTier.DISLIKE }
        val dis = result.pool.filter { tier(it) == PrefTier.DISLIKE }
        val unlocked = ArrayList<Unlock>()
        val gaps = ArrayList<String>()
        val repeatFrac = params.double("elig.unlock_repeat_frac")
        for (g in catalog.taxonomy.activeGroups.map { it.id }) {
            val goal = input.goals[g] ?: continue
            if ((input.target[g] ?: 0.0) < 1.0 || goal !in setOf("improve", "maintain")) continue
            val thr = params.double(if (goal == "improve") "elig.gate_improve" else "elig.gate_maintain")
            if (liked.any { catalog.groupCoeff(it, g) >= thr }) continue
            val cand = dis.filter { e ->
                catalog.groupCoeff(e, g) >= thr &&
                    (e.slug !in input.unlockedThisWeek || (input.weeklyCoverage[g] ?: 0.0) < repeatFrac * (input.weeklyTarget[g] ?: 0.0))
            }
            val u = cand.sortedWith(compareByDescending<CatalogExercise> { catalog.groupCoeff(it, g) }.thenByDescending { it.sfr }.thenBy { it.slug }).firstOrNull()
            when {
                u != null -> if (unlocked.none { it.slug == u.slug }) unlocked += Unlock(u.slug, g)
                result.pool.none { catalog.groupCoeff(it, g) >= thr } -> gaps += g
            }
        }
        val keep = liked.map { it.slug }.toSet() + unlocked.map { it.slug }
        return GateResult(result.pool.filter { it.slug in keep }, unlocked, gaps)
    }

    /**
     * `groupAvailability` (12.4): whether some exercise of the pool built with the day's constraints (preferences
     * ignored except `never` and a hidden row) trains the group directly, at `elig.avail_coeff` or more.
     */
    fun groupAvailability(catalog: Catalog, ctx: EligContext, params: ParamView): Map<String, Boolean> {
        val strict = ctx.copy(prefs = ctx.prefs.filterValues { it.tier == PrefTier.NEVER || it.hidden })
        val pool = pool(catalog, strict, params).pool
        val avail = params.double("elig.avail_coeff")
        val out = java.util.TreeMap<String, Boolean>()
        for (g in catalog.taxonomy.activeGroups) out[g.id] = pool.any { catalog.groupCoeff(it, g.id) >= avail }
        return out
    }

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
