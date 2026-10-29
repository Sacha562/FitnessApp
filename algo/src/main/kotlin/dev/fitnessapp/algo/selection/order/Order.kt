package dev.fitnessapp.algo.selection.order

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.algo.selection.time.TimeModel

/**
 * One exercise to order. [pinnedRank] is the position a challenge block or a skill item defines (99 for general work);
 * [anchor] marks a ROT-1 anchor (the grip fix-up keeps grip-heavy work after it).
 */
data class OrderInput(val exercise: CatalogExercise, val sets: Int, val pinnedRank: Int = 99, val anchor: Boolean = false)

/** An ordered exercise; [setupS] is the chained setup (`order.chain_setup_s`) when [chainedAfter] is set, else null. */
data class Ordered(val slug: String, val sets: Int, val pinnedRank: Int, val chainedAfter: String? = null, val setupS: Double? = null)

/** A superset (14.2): the two members, the rests from REST-1 and the seconds it saves. */
data class Superset(val a: String, val b: String, val betweenS: Double, val afterS: Double, val pairS: Double, val savedS: Double)

/**
 * `order@1.0.0` ORD-1 (ALGORITHMS_PLANNING 14, F13): challenge and skill items first, then multi-joint before
 * single-joint before core and prehab, the main work of improve groups first, then by axial load and skill; three
 * fix-ups (no pre-fatigue of a compound's group, grip-heavy work after a grip-heavy anchor, same-station chaining) and
 * the optional supersets of dissimilar exercises with REST-1's rests (C56). Every tie is broken by slug.
 */
object Order : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("order")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("order.chain_setup_s", "s", 0.0, 60.0),
        ParamSpec.integer("order.fixup_passes", "passes", 1.0, 5.0),
        ParamSpec.integer("order.grip_threshold", "grip demand level", 1.0, 3.0),
        ParamSpec.number("order.pair_cos_max", "cosine", 0.0, 0.5),
    )

    private const val CREDIT_COEFF = 0.5

    /** The class of `key(e)` (14.1): 0 multi-joint, 1 single-joint, 2 core, prehab and conditioning. */
    fun exerciseClass(e: CatalogExercise): Int = when {
        e.mechanics == "conditioning" || "prehab" in e.tags || CatalogDomains.FAMILY_OF_PATTERN[e.pattern] == "core" -> 2
        e.mechanics == "isolation" -> 1
        else -> 0
    }

    /**
     * The stations of [e] that chaining and supersets compare: its `requires_station` keys of category `station`
     * ([stations], from `equipment_types`); implements such as dumbbells are shared freely. Null [stations] counts every key.
     */
    fun stationsOf(e: CatalogExercise, stations: Set<String>?): List<String> = e.requiresStation.filter { stations == null || it in stations }

    /**
     * ORD-1 (14.1): the sort key, then up to `order.fixup_passes` passes of the three fix-ups. [improve] holds the
     * improve groups; [stations] the equipment keys that are stations ([stationsOf]).
     */
    fun order(items: List<OrderInput>, improve: Set<String>, stations: Set<String>?, catalog: Catalog, params: ParamView): List<Ordered> {
        fun improveWork(i: OrderInput) = improve.sumOf { g -> catalog.groupCoeff(i.exercise, g) * i.sets }
        val sorted = items.sortedWith(
            compareBy<OrderInput> { it.pinnedRank }
                .thenBy { exerciseClass(it.exercise) }
                .thenByDescending { round6(improveWork(it)) }
                .thenByDescending { it.exercise.axial }
                .thenByDescending { it.exercise.skill }
                .thenBy { it.exercise.slug },
        ).toMutableList()
        val chained = java.util.TreeMap<String, String>()
        val gripMin = params.int("order.grip_threshold")
        // (a) pre-fatigue: a single-joint exercise before a compound that trains its primary group moves after it.
        fun preFatigue(): Boolean {
            for (i in sorted.indices) {
                val ei = sorted[i]
                if (ei.pinnedRank != 99 || exerciseClass(ei.exercise) != 1) continue
                val primary = Elig.primaryGroup(ei.exercise, catalog) ?: continue
                val j = (i + 1 until sorted.size).lastOrNull { k ->
                    sorted[k].exercise.mechanics == "compound" && catalog.groupCoeff(sorted[k].exercise, primary) >= CREDIT_COEFF
                } ?: continue
                sorted.add(j, sorted.removeAt(i))
                return true
            }
            return false
        }

        // (b) grip: grip-heavy work before a pinned or anchor exercise that is grip-heavy too moves after it.
        fun grip(): Boolean {
            for (i in sorted.indices) {
                val ei = sorted[i]
                if (ei.pinnedRank != 99 || ei.anchor || ei.exercise.gripDemand < gripMin) continue
                val j = (i + 1 until sorted.size).lastOrNull { k ->
                    (sorted[k].pinnedRank != 99 || sorted[k].anchor) && sorted[k].exercise.gripDemand >= gripMin
                } ?: continue
                sorted.add(j, sorted.removeAt(i))
                return true
            }
            return false
        }

        // (c) same-station chaining: an exercise on an earlier exercise's station moves right after it when nothing in
        // between trains one of its groups.
        fun chain(): Boolean {
            for (i in sorted.indices) {
                val ei = sorted[i]
                val si = stationsOf(ei.exercise, stations)
                if (si.isEmpty()) continue
                for (j in i + 1 until sorted.size) {
                    val ej = sorted[j]
                    if (ej.pinnedRank != 99 || ej.exercise.slug in chained || stationsOf(ej.exercise, stations).none { it in si }) continue
                    val groups = catalog.taxonomy.activeGroups.map { it.id }.filter { catalog.groupCoeff(ej.exercise, it) >= CREDIT_COEFF }
                    if (sorted.subList(i + 1, j).any { b -> groups.any { catalog.groupCoeff(b.exercise, it) >= CREDIT_COEFF } }) continue
                    // Pre-fatigue wins over chaining: a single-joint exercise never moves ahead of a compound for its group.
                    val primary = Elig.primaryGroup(ej.exercise, catalog)
                    if (exerciseClass(ej.exercise) == 1 && primary != null && sorted.subList(i + 1, j).any { b ->
                            b.exercise.mechanics == "compound" && catalog.groupCoeff(b.exercise, primary) >= CREDIT_COEFF
                        }
                    ) continue
                    sorted.add(i + 1, sorted.removeAt(j))
                    chained[ej.exercise.slug] = ei.exercise.slug
                    return true
                }
            }
            return false
        }

        repeat(params.int("order.fixup_passes")) {
            var changed = false
            for (fix in listOf(::preFatigue, ::grip, ::chain)) {
                var n = 0
                while (n < sorted.size && fix()) { n++; changed = true }
            }
            if (!changed) return finish(sorted, chained, params)
        }
        return finish(sorted, chained, params)
    }

    private fun finish(sorted: List<OrderInput>, chained: Map<String, String>, params: ParamView): List<Ordered> {
        val chainS = params.double("order.chain_setup_s")
        return sorted.mapIndexed { i, it ->
            val after = chained[it.exercise.slug]?.takeIf { prev -> i > 0 && sorted[i - 1].exercise.slug == prev }
            Ordered(it.exercise.slug, it.sets, it.pinnedRank, after, if (after != null) chainS else null)
        }
    }

    /**
     * Supersets (14.2, F13): pairs of non-pinned exercises with stimulus cosine at most `order.pair_cos_max`, axial and
     * skill at most 1, on different free stations. The pair with the largest saving goes first (ties by slug pair),
     * until the plan fits [budgetS] or, when the user enabled supersets ([allow]), until no saving pair remains.
     * [times] are the exercises' TIME-1 items keyed by slug.
     */
    fun supersets(
        ordered: List<Ordered>,
        times: Map<String, TimeItem>,
        restBase: Map<String, Double>,
        totalS: Double,
        budgetS: Double,
        allow: Boolean,
        occupiedStations: Set<String>,
        stations: Set<String>?,
        catalog: Catalog,
        params: ParamView,
    ): List<Superset> {
        val cosMax = params.double("order.pair_cos_max")
        val free = ordered.filter { o ->
            val e = catalog.exercise(o.slug) ?: return@filter false
            o.pinnedRank == 99 && e.axial <= 1 && e.skill <= 1 && e.requiresStation.none { it in occupiedStations }
        }.sortedBy { it.slug }
        val cands = ArrayList<Superset>()
        for (x in free.indices) for (y in x + 1 until free.size) {
            val ea = catalog.exercise(free[x].slug)!!
            val eb = catalog.exercise(free[y].slug)!!
            if (stationsOf(ea, stations).any { it in stationsOf(eb, stations) }) continue
            if (catalog.similarity(ea, eb) > cosMax + 1e-9) continue
            val s = pair(times.getValue(ea.slug), times.getValue(eb.slug), restBase.getValue(ea.slug), restBase.getValue(eb.slug), params)
            if (s.savedS > 0.0) cands += s
        }
        cands.sortWith(compareByDescending<Superset> { it.savedS }.thenBy { it.a }.thenBy { it.b })
        val used = java.util.TreeSet<String>()
        val out = ArrayList<Superset>()
        var total = totalS
        for (c in cands) {
            if (!allow && total <= budgetS + 1e-9) break
            if (c.a in used || c.b in used) continue
            out += c
            used += c.a
            used += c.b
            total -= c.savedS
        }
        return out
    }

    /**
     * One pair (14.2): `between` is REST-1's member rest, `after` REST-1's after-pair rest, `t_pair = setup_a + setup_b
     * + n · (work_a + between + work_b + after) − after + TRANSITION_S` with `n = max(s_a, s_b)`, and the saving
     * `t(a) + t(b) − t_pair`.
     */
    fun pair(a: TimeItem, b: TimeItem, baseA: Double, baseB: Double, params: ParamView): Superset {
        val workA = TimeModel.work(a, params)
        val workB = TimeModel.work(b, params)
        val between = RestPolicy.supersetBetween(params)
        val after = RestPolicy.supersetAfterPair(baseA, baseB, workA, workB, params)
        val n = maxOf(a.sets, b.sets)
        val tPair = Grid.s(a.setupS + b.setupS + n * (workA + between + workB + after) - after + params.double("time.transition_s"))
        val saved = Grid.s(TimeModel.exerciseSeconds(a, params) + TimeModel.exerciseSeconds(b, params) - tPair)
        return Superset(a.key, b.key, between, after, tPair, saved)
    }

    private fun round6(x: Double): Double = kotlin.math.round(x * 1e6) / 1e6
}
