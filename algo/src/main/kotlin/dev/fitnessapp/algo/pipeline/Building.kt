package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.planning.allocator.AddOnSlot
import dev.fitnessapp.algo.planning.allocator.AllocInput
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.Allocator
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.GateInput
import dev.fitnessapp.algo.selection.order.Order
import dev.fitnessapp.algo.selection.select.Select
import dev.fitnessapp.algo.selection.select.SelectInput
import dev.fitnessapp.algo.selection.select.SelectResult
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.selection.time.TrimItem
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.algo.targets.WeeklyTargets
import java.time.Instant

/**
 * One general block to build (L6, ALGORITHMS_PLANNING 7, 12, 13, 17.7; UX 22.6). [budgetMin] is the block's whole
 * budget: the session budget for a standalone session (the "90+" chip is `today.max_budget_min`), the add-on capacity
 * for an add-on; the warm-up of [warmup] comes off it before SEL-1. [vBefore] is the week's Vhyp so far (done plus the
 * challenge's committed coverage), [opp] the opportunities per group (1 until the week planner, M4.5, counts them),
 * [readiness] `R_g` at [start]. [elig] holds the day's ELIG-1 constraints; [forbiddenGroups] the covered and
 * hard-window groups; [addOn] the allocator's view of an add-on slot. [anchors], [history] and [rotatedOut] come from
 * the mesocycle (ROT-1, D86 (3)): SEL-1's S0 anchors, its K set, and the exercises left out of automatic builds.
 */
data class BuildRequest(
    val type: SessionType,
    val start: Instant,
    val budgetMin: Double,
    val warmup: WarmupKind,
    val targets: WeeklyTargets,
    val vBefore: Map<String, Double>,
    val opp: Map<String, Int>,
    val readiness: Map<String, Double>,
    val elig: EligContext,
    val forbiddenGroups: Set<String> = emptySet(),
    val forbiddenSessionTags: Set<String> = emptySet(),
    val tagRules: List<SessionTagRule> = emptyList(),
    val addOn: AddOnSlot? = null,
    val sPref: Map<String, Int> = emptyMap(),
    val prefM: Map<String, Double> = emptyMap(),
    val stations: Set<String>? = null,
    val busyStations: Set<String> = emptySet(),
    val stalled: Set<String> = emptySet(),
    val history: Set<String> = emptySet(),
    val anchors: List<String> = emptyList(),
    val rotatedOut: Set<String> = emptySet(),
)

/** A built exercise in ORD-1 order: its sets after trimming and its TIME-1 seconds. */
data class BuiltExercise(val slug: String, val sets: Int, val estS: Double, val chainedAfter: String?)

/** One line of "To fit 60 min: ..." (UX 9.4): sets taken off an exercise, or the exercise left out. */
data class TrimLine(val slug: String, val setsRemoved: Int, val removed: Boolean)

/**
 * The built block. [empty] carries the reason when nothing can be built (the allocator found no feasible dose, or no
 * eligible exercise trains a group with a target). [label] names the families that receive sets (9.5).
 */
data class BuildResult(
    val type: SessionType,
    val label: String,
    val allocation: Allocation,
    val select: SelectResult?,
    val exercises: List<BuiltExercise>,
    val estMin: Double,
    val budgetMin: Double,
    val trimmed: List<TrimLine>,
    val coreDoesNotFit: Boolean,
    val empty: String?,
)

/**
 * The L6 build of one general block (UX 22.6 `SessionBuilder`): the allocator's dose per group (7), ELIG-1's pool and
 * necessity gate on that dose (12), SEL-1's exercises and sets with ORD-1 inside (13, 14), then UI-02 trimming of the
 * whole block to [BuildRequest.budgetMin] (17.7), since SEL-1 may run over its budget by `select.overrun_cap`. Pure and
 * deterministic: every input is passed in.
 */
object Building {
    /**
     * The allocation alone (7), cheap enough to run for every type of the New session sheet and every add-on family:
     * whether a type can train anything today and how much of the remaining targets it would cover.
     */
    fun allocate(req: BuildRequest, catalog: Catalog, params: ParamView): Allocation = Allocator.allocate(
        AllocInput(
            type = req.type, start = req.start, budgetMin = req.budgetMin, vBefore = req.vBefore, opp = req.opp,
            readiness = req.readiness, targets = req.targets, available = Elig.groupAvailability(catalog, req.elig, params),
            forbiddenGroups = req.forbiddenGroups, forbiddenSessionTags = req.forbiddenSessionTags, tagRules = req.tagRules,
            addOn = req.addOn,
        ),
        params,
    )

    /** The projected Vhyp an allocation adds (7.6): how the add-on family is preselected (D62). */
    fun value(a: Allocation): Double = Grid.ratio(a.vhyp.values.sum())

    /**
     * SEL-1's input for [req] after the allocation and ELIG-1 (7, 12), or the reason nothing can be built; also the
     * family run of a mesocycle start (ROT-1, D86 (2)).
     */
    fun selectInput(req: BuildRequest, catalog: Catalog, params: ParamView): Pair<Allocation, Result<SelectInput>> {
        val alloc = allocate(req, catalog, params)
        if (!alloc.feasible) return alloc to Result.failure(Empty(alloc.infeasibleReason ?: "INFEASIBLE"))
        if (alloc.credit.values.none { it > 0.0 }) return alloc to Result.failure(Empty("NOTHING_NEEDED"))

        val goals = req.targets.groups.mapValues { it.value.status }
        val pool = Elig.pool(catalog, req.elig, params).let { r -> r.copy(pool = r.pool.filter { it.slug !in req.rotatedOut }) }
        val gate = Elig.gate(pool, catalog, req.elig, GateInput(alloc.credit, goals), params)
        if (gate.pool.isEmpty()) return alloc to Result.failure(Empty("NO_ELIGIBLE"))
        val warmMin = TimeModel.warmupSeconds(req.warmup, params) / 60.0
        val mult = alloc.setFactor.values.minOrNull() ?: 1.0
        return alloc to Result.success(
            SelectInput(
                pool = gate.pool, target = alloc.credit, goals = goals, capS = req.targets.groups.mapValues { it.value.capS },
                budgetMin = maxOf(0.0, req.budgetMin - warmMin), focus = req.targets.groups.filterValues { it.focus }.keys,
                sPref = req.sPref, mult = mult, anchors = req.anchors, history = req.history, stalled = req.stalled, prefM = req.prefM,
                unlocked = gate.unlocked.associate { it.slug to it.group }, jointFlags = req.elig.jointFlags,
                forbiddenSessionTags = req.forbiddenSessionTags, tagRules = req.tagRules, maxSets = req.addOn?.maxSets?.toInt(),
                stations = req.stations, busyStations = req.busyStations,
            ),
        )
    }

    /** Why [selectInput] has nothing to select from. */
    class Empty(val reason: String) : RuntimeException(reason, null, false, false)

    fun build(req: BuildRequest, catalog: Catalog, params: ParamView): BuildResult {
        val (alloc, prepared) = selectInput(req, catalog, params)
        fun empty(reason: String) = BuildResult(req.type, alloc.label, alloc, null, emptyList(), 0.0, req.budgetMin, emptyList(), false, reason)
        val input = prepared.getOrElse { return empty((it as Empty).reason) }
        val goals = input.goals
        val mult = input.mult
        val sel = Select.select(input, catalog, params)
        if (sel.exercises.isEmpty()) return empty("NO_ELIGIBLE")

        // UI-02 trimming of the whole block (17.7): tier 1 compound of an improve group, 2 primary group maintained,
        // 3 isolation or accessory of an improve group; the engine's priority is the exercise's bracket of V.
        val search = Select.Search(input, catalog, params)
        val bySlug = input.pool.associateBy { it.slug }
        val items = sel.exercises.map { x ->
            val e = bySlug.getValue(x.slug)
            val primary = Elig.primaryGroup(e, catalog)
            val tier = when {
                "prehab" in e.tags -> 0
                primary != null && goals[primary] == "maintain" -> 2
                e.mechanics == "compound" && Order.exerciseClass(e) == 0 -> 1
                else -> 3
            }
            TrimItem(search.timeItem(e, x.sets), tier, req.prefM[x.slug] ?: 1.0, x.b)
        }
        val trim = TimeModel.trim(items, req.warmup, req.budgetMin, mult, params)
        val kept = trim.items.associateBy { it.time.key }
        val lines = sel.exercises.mapNotNull { x ->
            val k = kept[x.slug]
            when {
                k == null -> TrimLine(x.slug, x.sets, removed = true)
                k.time.sets < x.sets -> TrimLine(x.slug, x.sets - k.time.sets, removed = false)
                else -> null
            }
        }
        val exercises = sel.exercises.mapNotNull { x ->
            val k = kept[x.slug] ?: return@mapNotNull null
            BuiltExercise(x.slug, k.time.sets, Grid.s(TimeModel.exerciseSeconds(k.time, params)), x.chainedAfter?.takeIf { it in kept })
        }
        val label = Allocator.label(alloc.direct, req.addOn != null)
        return BuildResult(
            req.type, label, alloc, sel, exercises, TimeModel.minutes(trim.seconds), req.budgetMin, lines, trim.coreDoesNotFit, null,
        )
    }
}
