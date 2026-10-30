package dev.fitnessapp.algo.selection.select

import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.injury.Injury
import dev.fitnessapp.algo.selection.injury.PenaltyWindow
import dev.fitnessapp.algo.selection.order.Order
import dev.fitnessapp.algo.selection.order.OrderInput
import dev.fitnessapp.algo.selection.order.Ordered
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.algo.selection.time.TimeModel
import java.util.TreeMap

/** A user must-include exercise with its sets (13.2 `pinned`). */
data class PinnedItem(val slug: String, val sets: Int)

/**
 * The inputs of one block (13.2). [target] is the allocator's `credit` per group (FS); [goals] the status of each group
 * (`improve`, `maintain`, `skip` or `covered`) with [focus] for focus groups; [budgetMin] the block's exercise budget
 * (a general session's budget minus its warm-up, an add-on's capacity minus its specific warm-up). [sPref] holds the
 * preferred sets per exercise from `rep_preferences` (default [defaultSPref]); [prefM] PREF-1's multipliers (default
 * neutral); [reps], [holdS] and [restS] the per-set work and rest TIME-1 uses (defaults: [defaultReps], 30 s, REST-1's
 * class base). [maxSets] is an add-on slot's set cap.
 */
data class SelectInput(
    val pool: List<CatalogExercise>,
    val target: Map<String, Double>,
    val goals: Map<String, String>,
    val capS: Map<String, Double>,
    val budgetMin: Double,
    val focus: Set<String> = emptySet(),
    val sPref: Map<String, Int> = emptyMap(),
    val defaultSPref: Int = 3,
    val mult: Double = 1.0,
    val pinned: List<PinnedItem> = emptyList(),
    val anchors: List<String> = emptyList(),
    val history: Set<String> = emptySet(),
    val stalled: Set<String> = emptySet(),
    val prefM: Map<String, Double> = emptyMap(),
    val unlocked: Map<String, String> = emptyMap(),
    val regionNeeds: List<String> = emptyList(),
    val jointFlags: Map<String, Int> = emptyMap(),
    val penaltyWindows: List<PenaltyWindow> = emptyList(),
    val forbiddenSessionTags: Set<String> = emptySet(),
    val tagRules: List<SessionTagRule> = emptyList(),
    val autoAdjust: Boolean = false,
    val maxSets: Int? = null,
    val reps: Map<String, Double> = emptyMap(),
    val defaultReps: Double = 10.0,
    val holdS: Map<String, Double> = emptyMap(),
    val restS: Map<String, Double> = emptyMap(),
    /** The equipment keys of category `station` (ORD-1 chaining); null counts every key. */
    val stations: Set<String>? = null,
    /**
     * Stations busy at this weekday and hour (REASON-1's `occupied` rule, D62): an exercise on one is a group's direct
     * trainer pick and a single only after the exercises on free stations.
     */
    val busyStations: Set<String> = emptySet(),
)

/** The terms of `J(S)` (13.3), on the 1e-6 grid. */
data class JTerms(val v: Double, val o: Double, val rb: Double, val k: Double, val x: Double, val t: Double, val j: Double)

/** A chosen exercise (13.5): sets, TIME-1 seconds, its bracket `B_e` of `V`, its multiplier and its unlock group. */
data class SelectedExercise(
    val slug: String,
    val sets: Int,
    val estS: Double,
    val b: Double,
    val m: Double,
    val pinned: Boolean,
    val unlockedFor: String?,
    val chainedAfter: String?,
)

data class Candidate(val slug: String, val j: Double)

/**
 * SEL-1's result (13.5): the ordered exercises, coverage per group, the `J` terms, the exercise minutes against the
 * budget, the ten best single candidates, and the D10 signal: [shortfall] per group when the targets cannot be met
 * inside the preference bounds, and [prefLimitsVolume] when every trainer of a short group sits at its upper bound.
 */
data class SelectResult(
    val exercises: List<SelectedExercise>,
    val coverage: Map<String, Double>,
    val terms: JTerms,
    val estMin: Double,
    val budgetMin: Double,
    val topCandidates: List<Candidate>,
    val shortfall: Map<String, Double>,
    val prefLimitsVolume: Boolean,
)

/**
 * `select@1.0.0` SEL-1 (ALGORITHMS_PLANNING 13): the exercises and set counts of one general block (a standalone
 * general session or an add-on block, C34) that meet the allocator's per-group targets, respect preferences, the time
 * budget, injuries and challenge constraints, and stay stable over the mesocycle. A beam search over the candidates
 * (each target group's best direct trainers, then the best singles; D56), a set-tuning pass and a time fit, then
 * ORD-1. Deterministic: every tie is broken by slug.
 */
object Select : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("select")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("select.axial_cap", "axial points", 2.0, 8.0),
        ParamSpec.number("select.b_sfr", "quality per sfr point", 0.0, 0.1),
        ParamSpec.number("select.b_stretch", "quality per stretch level", 0.0, 0.2),
        ParamSpec.integer("select.beam_width", "plans", 4.0, 50.0),
        ParamSpec.number("select.consistency", "J per exercise", 0.0, 2.0),
        ParamSpec.number("select.dedup_cos", "cosine", 0.8, 1.0),
        ParamSpec.number("select.lam_axial", "J per axial point", 0.0, 1.0),
        ParamSpec.number("select.lam_cap", "J per FS", 0.0, 3.0),
        ParamSpec.number("select.lam_over", "J per FS", 0.0, 2.0),
        ParamSpec.number("select.lam_skip", "J per FS", 0.0, 10.0),
        ParamSpec.number("select.lam_time", "J per min", 0.0, 0.2),
        ParamSpec.number("select.lam_time_over", "J per min", 0.0, 2.0),
        ParamSpec.integer("select.max_ex", "exercises", 4.0, 12.0),
        ParamSpec.integer("select.max_per_pattern", "exercises", 1.0, 4.0),
        ParamSpec.number("select.over_credit", "ratio", 0.0, 0.5),
        ParamSpec.number("select.over_tol", "ratio of T", 1.0, 1.5),
        ParamSpec.number("select.overrun_cap", "ratio of budget", 1.0, 1.3),
        ParamSpec.integer("select.per_group_k", "exercises per group", 0.0, 5.0),
        ParamSpec.number("select.region_bonus", "J per region", 0.0, 1.0),
        ParamSpec.table("select.smax", "sets (by mechanics)", 2.0, 8.0),
        ParamSpec.number("select.stall_pen", "J per exercise", 0.0, 2.0),
        ParamSpec.integer("select.top_k", "exercises", 10.0, 60.0),
        ParamSpec.number("select.tune_eps", "J", 0.0, 0.5),
        ParamSpec.integer("select.tune_passes", "passes", 1.0, 50.0),
    )

    private const val DIRECT_COEFF = 0.75
    private const val REGION_COEFF = 0.5

    /** One exercise with its sets in a candidate plan. */
    data class Pick(val e: CatalogExercise, val s: Int)

    /** The objective and the search, with every per-exercise fact computed once. */
    class Search(val input: SelectInput, val catalog: Catalog, val params: ParamView) {
        private val groups = catalog.taxonomy.activeGroups.map { it.id }
        private val minHyp = params.double("setcredit.min_hyp_coeff")
        private val holdFactor = params.double("setcredit.hold_factor_long")
        private val w = params.table("targets.w")
        private val restBase = params.table("rest.base")
        private val overCredit = params.double("select.over_credit")
        private val overTol = params.double("select.over_tol")
        private val lamOver = params.double("select.lam_over")
        private val lamCap = params.double("select.lam_cap")
        private val lamSkip = params.double("select.lam_skip")
        private val bStretch = params.double("select.b_stretch")
        private val bSfr = params.double("select.b_sfr")
        private val regionBonus = params.double("select.region_bonus")
        private val consistency = params.double("select.consistency")
        private val stallPen = params.double("select.stall_pen")
        private val axialCap = params.double("select.axial_cap")
        private val lamAxial = params.double("select.lam_axial")
        private val lamTime = params.double("select.lam_time")
        private val lamTimeOver = params.double("select.lam_time_over")
        private val smax = params.table("select.smax")
        private val pinnedSlugs = input.pinned.map { it.slug }.toSet()

        private val coeff = TreeMap<String, DoubleArray>()
        private val credited = TreeMap<String, DoubleArray>()
        private val quality = TreeMap<String, Double>()
        private val perSetPen = TreeMap<String, Double>()
        private val secondsCache = TreeMap<String, Double>()

        init {
            for (e in input.pool) {
                val c = DoubleArray(groups.size) { catalog.groupCoeff(e, groups[it]) }
                coeff[e.slug] = c
                credited[e.slug] = DoubleArray(groups.size) { i ->
                    val ci = c[i]
                    when {
                        ci < minHyp -> 0.0
                        e.contraction == "hang" -> if (groups[i] == "forearms") ci * holdFactor else 0.0
                        e.contraction == "isometric" -> ci * holdFactor
                        else -> ci
                    }
                }
                quality[e.slug] = 1.0 + bStretch * e.stretchEmphasis + bSfr * (e.sfr - 3)
                perSetPen[e.slug] = Injury.jointPenaltyPerSet(e, input.jointFlags, params) +
                    Injury.windowPenaltyPerSet(e, input.penaltyWindows, catalog, params)
            }
        }

        fun m(e: CatalogExercise): Double = input.prefM[e.slug] ?: 1.0

        fun timeItem(e: CatalogExercise, s: Int): TimeItem {
            val hold = e.contraction == "isometric" || e.contraction == "hang"
            return TimeItem(
                key = e.slug, sets = s, restS = input.restS[e.slug] ?: restBase.getValue(e.restClass), setupS = e.setupS.toDouble(),
                reps = if (hold) null else input.reps[e.slug] ?: input.defaultReps,
                holdS = if (hold) input.holdS[e.slug] ?: 30.0 else null,
                unilateralPair = e.laterality != "bilateral",
            )
        }

        fun seconds(e: CatalogExercise, s: Int): Double = secondsCache.getOrPut(e.slug + "#" + s) { TimeModel.exerciseSeconds(timeItem(e, s), params) }

        fun minutes(plan: List<Pick>): Double = plan.sumOf { seconds(it.e, it.s) } / 60.0

        fun cov(plan: List<Pick>): DoubleArray {
            val out = DoubleArray(groups.size)
            for (p in plan) {
                val c = credited.getValue(p.e.slug)
                for (i in groups.indices) out[i] += c[i] * p.s
            }
            return out
        }

        /** `setBounds(e)` (13.4): the preference ± 1 (± 2 with auto-adjust), capped by `SMAX`, scaled by a transform. */
        fun bounds(e: CatalogExercise): Pair<Int, Int> {
            val pref = input.sPref[e.slug] ?: input.defaultSPref
            val span = if (input.autoAdjust) 2 else 1
            val cap = smax.getValue(if (e.mechanics == "compound") "compound" else "isolation").toInt()
            var lo = maxOf(1, pref - span)
            var hi = maxOf(lo, minOf(cap, pref + span))
            if (input.mult < 1.0) {
                lo = maxOf(1, kotlin.math.ceil(lo * input.mult - 1e-9).toInt())
                hi = maxOf(lo, kotlin.math.ceil(hi * input.mult - 1e-9).toInt())
            }
            return lo to hi
        }

        /** `alloc(e, R)` (13.4): the sets that cover the largest residual of a group the exercise trains directly. */
        fun alloc(e: CatalogExercise, residual: DoubleArray): Int {
            val (lo, hi) = bounds(e)
            val c = coeff.getValue(e.slug)
            fun need(min: Double): Double? =
                groups.indices.filter { c[it] >= min && residual[it] > 1e-9 }.maxOfOrNull { residual[it] / c[it] }
            val n = need(DIRECT_COEFF) ?: need(minHyp) ?: 0.0
            val pref = input.sPref[e.slug] ?: input.defaultSPref
            val s0 = if (input.mult < 1.0) hi else pref.coerceIn(lo, hi)
            return minOf(s0, kotlin.math.ceil(n - 0.25 - 1e-9).toInt()).coerceIn(lo, hi)
        }

        fun residual(plan: List<Pick>): DoubleArray {
            val c = cov(plan)
            return DoubleArray(groups.size) { maxOf(0.0, (input.target[groups[it]] ?: 0.0) - c[it]) }
        }

        /** `J(S)` of 13.3 with its terms and each exercise's bracket `B_e`. */
        fun evaluate(plan: List<Pick>): Pair<JTerms, Map<String, Double>> {
            val cov = cov(plan)
            val raw = DoubleArray(groups.size)
            for (p in plan) {
                val c = coeff.getValue(p.e.slug)
                for (i in groups.indices) raw[i] += c[i] * p.s
            }
            val share = DoubleArray(groups.size)
            var o = 0.0
            for (i in groups.indices) {
                val g = groups[i]
                val goal = input.goals[g] ?: "skip"
                val t = input.target[g] ?: 0.0
                if (goal == "skip") { o += (lamSkip / lamOver) * raw[i]; continue }
                if (cov[i] <= 0.0) continue
                if (t <= 0.0) { o += maxOf(0.0, cov[i] - 1.0); continue }
                val useful = minOf(cov[i], t) + overCredit * maxOf(0.0, minOf(cov[i], overTol * t) - t)
                share[i] = useful / cov[i]
                o += maxOf(0.0, cov[i] - maxOf(overTol * t, t + 1.0)) + (lamCap / lamOver) * maxOf(0.0, cov[i] - (input.capS[g] ?: Double.MAX_VALUE))
            }
            val brackets = TreeMap<String, Double>()
            var v = 0.0
            for (p in plan) {
                val c = credited.getValue(p.e.slug)
                var inner = 0.0
                for (i in groups.indices) {
                    if (c[i] <= 0.0 || share[i] <= 0.0) continue
                    val g = groups[i]
                    val wg = when (input.goals[g]) {
                        "improve" -> w.getValue(if (g in input.focus) "focus" else "improve")
                        "maintain" -> w.getValue("maintain")
                        else -> 0.0
                    }
                    inner += wg * c[i] * p.s * share[i]
                }
                val b = inner * m(p.e) * quality.getValue(p.e.slug)
                brackets[p.e.slug] = b
                v += b
            }
            val rb = regionBonus * input.regionNeeds.count { r -> plan.any { (it.e.stimulus[r] ?: 0.0) >= REGION_COEFF } }
            val k = consistency * plan.count { it.e.slug in input.history && (brackets[it.e.slug] ?: 0.0) > 0.0 } -
                stallPen * plan.count { it.e.slug in input.stalled }
            val x = lamAxial * maxOf(0.0, plan.sumOf { it.e.axial } - axialCap) + plan.sumOf { it.s * perSetPen.getValue(it.e.slug) }
            val t = minutes(plan)
            val j = v - lamOver * o + rb + k - x - lamTime * t - lamTimeOver * maxOf(0.0, t - input.budgetMin)
            return JTerms(r6(v), r6(o), r6(rb), r6(k), r6(x), r6(t), r6(j)) to brackets
        }

        fun j(plan: List<Pick>): Double = evaluate(plan).first.j

        /** Whether [plan] carries a forbidden session tag (C11): a group-count rule or the grip-heavy set count. */
        fun forbiddenTag(plan: List<Pick>): Boolean {
            if (input.forbiddenSessionTags.isEmpty()) return false
            val cov = cov(plan)
            val byGroup = groups.indices.associate { groups[it] to cov[it] }
            return input.tagRules.any { rule ->
                rule.tag in input.forbiddenSessionTags && when {
                    rule.countsGroups -> rule.reached(byGroup)
                    rule.minGripDemand != null -> plan.filter { it.e.gripDemand >= rule.minGripDemand }.sumOf { it.s } >= rule.minHardSets - 1e-9
                    else -> false
                }
            }
        }

        fun totalSets(plan: List<Pick>): Int = plan.sumOf { it.s }

        fun isPinned(slug: String): Boolean = slug in pinnedSlugs
    }

    fun select(input: SelectInput, catalog: Catalog, params: ParamView): SelectResult {
        val search = Search(input, catalog, params)
        val (raw, singles) = beam(search)
        var best = tune(search, raw, params)
        best = timeFit(search, best, input.budgetMin)
        return result(search, best, singles)
    }

    /** The beam's best plan before TUNE and TIME_FIT, with the single candidates by `J` (13.4). */
    fun beam(search: Search): Pair<List<Pick>, List<Candidate>> {
        val input = search.input
        val catalog = search.catalog
        val params = search.params
        val pool = input.pool.sortedBy { it.slug }
        val bySlug = pool.associateBy { it.slug }
        val maxEx = params.int("select.max_ex")
        val maxPerPattern = params.int("select.max_per_pattern")
        val dedupCos = params.double("select.dedup_cos")
        val overrun = params.double("select.overrun_cap") * input.budgetMin
        val beamWidth = params.int("select.beam_width")

        // S0: the pinned items, then the anchors that still train a group with a target.
        val s0 = ArrayList<Pick>()
        for (p in input.pinned) bySlug[p.slug]?.let { s0 += Pick(it, p.sets) }
        for (a in input.anchors.sorted()) {
            val e = bySlug[a] ?: continue
            if (s0.any { it.e.slug == a }) continue
            val trains = catalog.taxonomy.activeGroups.any { catalog.groupCoeff(e, it.id) >= DIRECT_COEFF && (input.target[it.id] ?: 0.0) > 0.0 }
            if (trains) s0 += Pick(e, capSets(search, s0, search.alloc(e, search.residual(s0))) ?: continue)
        }
        val inS0 = s0.map { it.e.slug }.toSet()
        val singles = pool.filter { it.slug !in inS0 }.mapNotNull { e ->
            val s = capSets(search, s0, search.alloc(e, search.residual(s0))) ?: return@mapNotNull null
            Candidate(e.slug, search.j(s0 + Pick(e, s)))
        }.sortedWith(compareByDescending<Candidate> { it.j }.thenBy { it.slug })
        // Candidates (D56): the best `select.per_group_k` direct trainers of every group with a target of at least one FS,
        // then the best singles until `select.top_k`; kept in single-J order. Exercises on a busy station come after
        // those on free stations in both picks (D62; the sort is stable, so J order holds within each part).
        fun busy(c: Candidate) = bySlug.getValue(c.slug).requiresStation.any { it in input.busyStations }
        val byFree = singles.sortedBy { busy(it) }
        val picked = java.util.TreeSet<String>()
        for (g in catalog.taxonomy.activeGroups.map { it.id }) {
            if ((input.target[g] ?: 0.0) < 1.0) continue
            byFree.filter { catalog.groupCoeff(bySlug.getValue(it.slug), g) >= DIRECT_COEFF }
                .take(params.int("select.per_group_k")).forEach { picked += it.slug }
        }
        for (c in byFree) {
            if (picked.size >= params.int("select.top_k")) break
            picked += c.slug
        }
        val topK = singles.filter { it.slug in picked }.map { bySlug.getValue(it.slug) }

        var beam = listOf(s0.toList())
        var best = s0.toList()
        var bestJ = search.j(best)
        for (step in 0 until maxEx - s0.size) {
            val next = LinkedHashMap<String, List<Pick>>()
            for (plan in beam) {
                val residual = search.residual(plan)
                for (e in topK) {
                    if (plan.any { it.e.slug == e.slug }) continue
                    val same = plan.filter { it.e.pattern == e.pattern }
                    if (same.size >= maxPerPattern) continue
                    if (same.any { catalog.similarity(it.e, e) >= dedupCos }) continue
                    val s = capSets(search, plan, search.alloc(e, residual)) ?: continue
                    val cand = plan + Pick(e, s)
                    val key = key(cand)
                    if (key in next) continue
                    if (search.minutes(cand) > overrun + 1e-9) continue
                    if (search.forbiddenTag(cand)) continue
                    next[key] = cand
                }
            }
            if (next.isEmpty()) break
            beam = next.entries.map { (k, p) -> Triple(k, p, search.j(p)) }
                .sortedWith(compareByDescending<Triple<String, List<Pick>, Double>> { it.third }.thenBy { it.first })
                .take(beamWidth).map { it.second }
            val top = search.j(beam[0])
            if (top > bestJ) { best = beam[0]; bestJ = top }
        }
        return best to singles
    }

    private fun result(search: Search, best: List<Pick>, singles: List<Candidate>): SelectResult {
        val input = search.input
        val catalog = search.catalog
        val params = search.params
        val bySlug = input.pool.associateBy { it.slug }
        val (terms, brackets) = search.evaluate(best)
        val improve = input.goals.filterValues { it == "improve" }.keys
        val ordered: List<Ordered> = Order.order(
            best.map { OrderInput(it.e, it.s, pinnedRank = if (search.isPinned(it.e.slug)) 0 else 99, anchor = it.e.slug in input.anchors) },
            improve, input.stations, catalog, params,
        )
        val chainS = params.double("order.chain_setup_s")
        val exercises = ordered.map { o ->
            val e = bySlug.getValue(o.slug)
            val item = search.timeItem(e, o.sets).let { if (o.chainedAfter != null) it.copy(setupS = chainS) else it }
            SelectedExercise(
                o.slug, o.sets, TimeModel.exerciseSeconds(item, params), r6(brackets[o.slug] ?: 0.0), search.m(e),
                search.isPinned(o.slug), input.unlocked[o.slug], o.chainedAfter,
            )
        }
        val cov = search.cov(best)
        val groups = catalog.taxonomy.activeGroups.map { it.id }
        val coverage = TreeMap<String, Double>()
        val shortfall = TreeMap<String, Double>()
        groups.forEachIndexed { i, g ->
            if (cov[i] > 0.0) coverage[g] = Grid.ratio(cov[i])
            val gap = (input.target[g] ?: 0.0) - cov[i]
            if (gap >= 0.5 - 1e-9 && input.goals[g] in setOf("improve", "maintain")) shortfall[g] = Grid.ratio(gap)
        }
        val limited = shortfall.isNotEmpty() && shortfall.keys.all { g ->
            val trainers = best.filter { catalog.groupCoeff(it.e, g) >= DIRECT_COEFF }
            trainers.isNotEmpty() && trainers.all { it.s >= search.bounds(it.e).second }
        }
        return SelectResult(
            exercises, coverage, terms, Grid.s(exercises.sumOf { it.estS } / 60.0), input.budgetMin, singles.take(10), shortfall, limited,
        )
    }

    /** The add-on set cap (C34): the sets an exercise may add to [plan], or null when none are left. */
    private fun capSets(search: Search, plan: List<Pick>, s: Int): Int? {
        val cap = search.input.maxSets ?: return s
        val left = cap - search.totalSets(plan)
        return if (left < 1) null else minOf(s, left)
    }

    /** TUNE (13.4): passes in slug order trying one set more, then one less, inside the bounds; kept when `ΔJ > TUNE_EPS`. */
    internal fun tune(search: Search, plan: List<Pick>, params: ParamView): List<Pick> {
        val eps = params.double("select.tune_eps")
        var cur = plan.sortedBy { it.e.slug }
        repeat(params.int("select.tune_passes")) {
            var changed = false
            for (i in cur.indices) {
                val p = cur[i]
                if (search.isPinned(p.e.slug)) continue
                val (lo, hi) = search.bounds(p.e)
                val j0 = search.j(cur)
                for (s in listOf(p.s + 1, p.s - 1)) {
                    if (s < lo || s > hi) continue
                    val cand = cur.toMutableList().also { it[i] = Pick(p.e, s) }
                    if (search.input.maxSets != null && search.totalSets(cand) > search.input.maxSets) continue
                    if (search.forbiddenTag(cand)) continue
                    if (search.j(cand) - j0 > eps + 1e-9) {
                        cur = cand
                        changed = true
                        break
                    }
                }
            }
            if (!changed) return cur
        }
        return cur
    }

    /**
     * TIME_FIT (13.4): while over the budget, remove the set (not below the lower bound, never a pinned one) that costs
     * the least `J` per minute saved; when no set can go, drop the non-pinned exercise that costs the least `J`.
     */
    internal fun timeFit(search: Search, plan: List<Pick>, budgetMin: Double): List<Pick> {
        var cur = plan.sortedBy { it.e.slug }
        while (search.minutes(cur) > budgetMin + 1e-9) {
            val j0 = search.j(cur)
            val t0 = search.minutes(cur)
            var best: List<Pick>? = null
            var bestKey = Double.MAX_VALUE
            for (i in cur.indices) {
                val p = cur[i]
                if (search.isPinned(p.e.slug) || p.s <= search.bounds(p.e).first) continue
                val cand = cur.toMutableList().also { it[i] = Pick(p.e, p.s - 1) }
                val dm = t0 - search.minutes(cand)
                if (dm <= 1e-9) continue
                val key = r6((j0 - search.j(cand)) / dm)
                if (key < bestKey) { bestKey = key; best = cand }
            }
            if (best == null) {
                var dropKey = Double.MAX_VALUE
                for (i in cur.indices) {
                    if (search.isPinned(cur[i].e.slug)) continue
                    val cand = cur.toMutableList().also { it.removeAt(i) }
                    val key = r6(j0 - search.j(cand))
                    if (key < dropKey) { dropKey = key; best = cand }
                }
            }
            cur = best ?: break
        }
        return cur
    }

    private fun key(plan: List<Pick>): String = plan.map { it.e.slug }.sorted().joinToString(",")

    private fun r6(x: Double): Double = kotlin.math.round(x * 1e6) / 1e6
}
