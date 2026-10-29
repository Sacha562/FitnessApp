package dev.fitnessapp.algo.selection.select

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.W41Support
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.GateInput
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.algo.targets.WeeklyTargets
import dev.fitnessapp.algo.testing.TestCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The selection cases of ALGORITHMS_PLANNING 21.8 and 22 on the seed catalog 1.0.2 (OI-16). */
object SelectFixtures {
    val catalog = TestCatalog.catalog

    /** Equipment keys of category `station` (ORD-1 chaining). */
    val stations: Set<String> by lazy {
        Json.parseToJsonElement(TestCatalog.resource("reference/equipment_types.json")).jsonObject.getValue("rows").jsonArray
            .map { it.jsonObject }.filter { it.getValue("category").jsonPrimitive.content == "station" }
            .map { it.getValue("key").jsonPrimitive.content }.toSortedSet()
    }

    fun prefs(params: ParamView, tiers: Map<String, PrefTier>): Map<String, PrefValue> =
        tiers.mapValues { (_, t) -> PrefValue(Pref.mult(t, params), t, hidden = false, z = 0.0, explicit = true, prompt = false) }

    val legsPrefs = mapOf(
        "leg_press_45" to PrefTier.FAVORITE, "leg_extension" to PrefTier.LIKE, "seated_leg_curl" to PrefTier.LIKE, "walking_lunge_db" to PrefTier.DISLIKE,
    )
    val pushPrefs = mapOf(
        "incline_db_press" to PrefTier.LIKE, "machine_chest_press" to PrefTier.LIKE, "pec_deck" to PrefTier.LIKE,
        "db_lateral_raise" to PrefTier.LIKE, "triceps_pushdown_rope" to PrefTier.LIKE,
    )

    fun input(
        params: ParamView,
        ctx: EligContext,
        target: Map<String, Double>,
        goals: Map<String, String>,
        budgetMin: Double,
        regions: List<String> = emptyList(),
        mult: Double = 1.0,
        maxSets: Int? = null,
        capS: Map<String, Double> = emptyMap(),
    ): SelectInput {
        val pool = Elig.pool(catalog, ctx, params)
        val gate = Elig.gate(pool, catalog, ctx, GateInput(target, goals), params)
        return SelectInput(
            pool = gate.pool, target = target, goals = goals, capS = capS, budgetMin = budgetMin, mult = mult, maxSets = maxSets,
            prefM = ctx.prefs.mapValues { it.value.m }, unlocked = gate.unlocked.associate { it.slug to it.group }, regionNeeds = regions,
            jointFlags = ctx.jointFlags, stations = stations,
        )
    }

    fun legs(params: ParamView, ctx: EligContext = EligContext(prefs = prefs(params, legsPrefs))) = input(
        params, ctx,
        target = mapOf("quads" to 6.0, "glutes" to 5.0, "hamstrings" to 6.0, "adductors" to 2.0, "abductors" to 2.0, "calves" to 3.0),
        goals = Groups.CANONICAL.associateWith { if (it in setOf("quads", "glutes", "hamstrings")) "improve" else "maintain" },
        budgetMin = 65.0, regions = listOf("rectus_femoris", "hams_biarticular", "gastrocnemius"),
    )

    fun push(params: ParamView, ctx: EligContext = EligContext(prefs = prefs(params, pushPrefs))) = input(
        params, ctx,
        target = mapOf("chest" to 6.0, "side_delts" to 4.0, "triceps" to 4.0, "front_delts" to 2.0),
        goals = Groups.CANONICAL.associateWith { if (it in setOf("chest", "side_delts", "triceps")) "improve" else "maintain" },
        budgetMin = 60.0, regions = listOf("chest_clavicular", "triceps_long"),
    )

    val w41Prefs = legsPrefs + pushPrefs

    fun goalsOf(t: WeeklyTargets): Map<String, String> = t.groups.mapValues { it.value.status }

    /** The W41 gap-day context (C11, C50) with the user's goals. */
    fun gapDayCtx(params: ParamView, t: WeeklyTargets) = EligContext(
        prefs = prefs(params, w41Prefs), gapDay = true, forbiddenSessionTags = W41Support.gapDayTags.toList(),
        lightPull = LightPullRule(1, 0.5), goalGroups = t.groups.filterValues { it.status in setOf("improve", "maintain") }.keys,
    )

    /** Friday's block (21.8): the allocator's credit as targets, deload set bounds, 75 min minus the general warm-up. */
    fun friday(params: ParamView, t: WeeklyTargets, alloc: Allocation) = input(
        params, gapDayCtx(params, t), alloc.credit, goalsOf(t), 75.0 - TimeModel.warmupSeconds(WarmupKind.GENERAL, params) / 60.0,
        mult = 0.5, capS = t.groups.mapValues { it.value.capS },
    )

    val addOnPatterns = listOf(
        "squat", "squat_machine", "lunge", "hip_ext", "knee_ext", "knee_flex", "hip_abd", "hip_add", "calf", "horiz_push", "incline_push",
        "vert_push", "chest_fly", "lateral_raise", "elbow_ext", "elbow_ext_overhead", "core_flex", "core_anti_ext",
    )

    /** Thursday's legs add-on after A (21.8): the slot filter, capacity 34 minus the specific warm-up, at most 4 sets. */
    fun thursday(params: ParamView, t: WeeklyTargets, alloc: Allocation) = input(
        params,
        EligContext(
            prefs = prefs(params, w41Prefs),
            addOn = AddOnFilter(addOnPatterns, 1, W41Support.owned, Groups.FAMILIES.getValue("LEGS")),
            forbiddenSessionTags = listOf("grip_heavy", "elbow_flexor_heavy"),
        ),
        alloc.credit, goalsOf(t), 34.0 - TimeModel.warmupSeconds(WarmupKind.ADDON, params) / 60.0, mult = 0.5, maxSets = 4,
        capS = t.groups.mapValues { it.value.capS },
    )
}
