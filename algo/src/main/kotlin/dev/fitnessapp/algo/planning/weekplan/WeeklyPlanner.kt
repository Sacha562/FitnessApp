package dev.fitnessapp.algo.planning.weekplan

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.readiness.Readiness
import dev.fitnessapp.algo.accounting.readiness.ReadinessOut
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Canon
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Sha256Hex
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.planning.allocator.AllocInput
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.Allocator
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.runs.RunRestDay
import dev.fitnessapp.algo.targets.runs.Runs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** A point where the planner chooses (8.6): a free day, a user-planned gym event, or an add-on slot. */
internal class Point(
    val index: Int,
    val kind: String,
    val date: LocalDate,
    val start: Instant,
    val budgetMin: Double,
    /** null = no session (free days and add-ons); a type for the rest. */
    val options: List<SessionType?>,
    val fixed: FixedItem? = null,
    val addOn: AddOnSpec? = null,
) {
    val standalone: Boolean get() = kind != ADDON

    companion object {
        const val FREE = "free"
        const val PLANNED = "planned"
        const val ADDON = "addon"
    }
}

/** One simulated general session or add-on of a plan. */
internal class SimSession(
    val point: Point,
    val type: SessionType,
    val alloc: Allocation,
    val readiness: ReadinessOut,
    /** The Vhyp accounted before this session (done, committed and the plan's earlier sessions). */
    val vBefore: Map<String, Double>,
    val runPenalty: Double,
)

/** One evaluated plan: the choice per point, the simulated sessions, J and its terms. */
internal class Eval(
    val choice: IntArray,
    val sessions: List<SimSession>,
    val j: Double,
    val terms: Map<String, Double>,
    val doses: List<Dose>,
    val vEnd: Map<String, Double>,
)

/** The day model of 8.4 for one date. */
internal class DayModel(
    val date: LocalDate,
    val status: String,
    val start: Instant? = null,
    val budgetMin: Double? = null,
    val reason: ReasonCode? = null,
)

/**
 * `weekplan@1.0.0` WeeklyPlanner.v1 (ALGORITHMS_PLANNING 8): the recommended schedule of an ISO week. The day model
 * (8.4) finds the free days and their slots; every plan of up to `kMax` general sessions on free days, with a type for
 * each and a family (or none) for each add-on slot, is simulated in time order with the allocator (7) and readiness (4),
 * checked against H1-H8 (8.5) and scored by the objective of 8.6; the C52 tie band and key pick the plan. Exhaustive,
 * so the choice is optimal for the model and explainable; allocations and readiness are memoised on the plan prefix.
 */
object WeeklyPlanner : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("weekplan")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("weekplan.addon_cost", "J per add-on", 0.0, 1.0),
        ParamSpec.integer("weekplan.busy_buffer_min", "min", 0.0, 60.0),
        ParamSpec.integer("weekplan.consec_free", "days", 2.0, 5.0),
        ParamSpec.number("weekplan.consec_w", "J per day", 0.0, 2.0),
        ParamSpec.number("weekplan.freq_bonus", "J per group", 0.0, 0.5),
        ParamSpec.number("weekplan.g_low", "G", 0.4, 0.7),
        ParamSpec.number("weekplan.heavy_lower_fs", "FS", 2.0, 8.0),
        ParamSpec.integer("weekplan.heavy_lower_min_h", "h", 24.0, 72.0),
        ParamSpec.integer("weekplan.k_cap", "sessions", 1.0, 7.0),
        ParamSpec.number("weekplan.lowg_w", "J per session", 0.0, 2.0),
        ParamSpec.integer("weekplan.max_evals", "plans", 100000.0, 10000000.0),
        ParamSpec.integer("weekplan.min_slot_min", "min", 30.0, 60.0),
        ParamSpec.number("weekplan.no_rest_w", "J", 0.0, 1.0),
        ParamSpec.number("weekplan.protect_w", "J per R deficit", 1.0, 20.0),
        ParamSpec.number("weekplan.session_cost", "J per session", 0.0, 1.0),
        ParamSpec.number("weekplan.skip_w", "J per spilled FS/4", 0.0, 1.0),
        ParamSpec.number("weekplan.stability_w", "J per day", 0.0, 0.2),
        ParamSpec.number("weekplan.tie_band", "J", 0.0, 0.2),
    )

    /** The add-on order of the tie key (C52): none < LEGS < PUSH < CORE, then the combined families. */
    internal fun addOnRank(t: SessionType?): Int = when (t) {
        null -> 0
        SessionType.LEGS -> 1
        SessionType.PUSH -> 2
        SessionType.CORE -> 3
        else -> 4 + t.ordinal
    }

    fun plan(input: WeekPlanInput, params: ParamView): WeekPlan {
        val ctx = Context(input, params)
        val tieBand = params.double("weekplan.tie_band")
        var best: Eval? = null
        val band = ArrayList<Eval>()
        val top = ArrayList<Eval>()
        var evaluated = 0
        ctx.enumerate { e ->
            evaluated++
            val bj = best?.let { round6(it.j) }
            if (bj == null || round6(e.j) > bj) best = e
            val star = round6(best!!.j)
            band.removeAll { round6(it.j) < star - tieBand - 1e-9 }
            if (round6(e.j) >= star - tieBand - 1e-9) band += e
            top += e
            if (top.size > TOP_KEEP * 4) { top.sortWith(compareByDescending<Eval> { round6(it.j) }.then(ctx.tieKey)); while (top.size > TOP_KEEP) top.removeAt(top.size - 1) }
        }
        val star = best ?: error("weekplan: no feasible plan (the empty plan is always feasible)")
        band.sortWith(ctx.tieKey)
        val chosen = band.first()
        top.sortWith(compareByDescending<Eval> { round6(it.j) }.then(ctx.tieKey))
        val keyUsed = if (band.size < 2) "only" else ctx.tieKeyUsed(band[0], band[1])
        return ctx.output(chosen, Band(Grid.ratio(star.j), tieBand, band.size, keyUsed), top.take(TOP_KEEP).let { l -> if (chosen in l) l else l + chosen }, evaluated)
    }

    /** The day keys of a plan (8.6 stability term): the recommended type, the add-on family, or none, per date. */
    fun dayKeys(plan: WeekPlan): Map<LocalDate, String> {
        val out = TreeMap<LocalDate, String>()
        plan.days.forEach { d ->
            val g = d.items.firstOrNull { it.kind == KIND_RECOMMENDED }
            val a = d.items.firstOrNull { it.kind == KIND_ADDON }
            out[d.date] = when {
                g != null -> "general:${g.type}"
                a != null -> "addon:${a.type}"
                else -> "none"
            }
        }
        return out
    }

    internal fun round6(x: Double): Double = kotlin.math.round(x * 1e6) / 1e6

    const val KIND_RECOMMENDED = "recommended_general"
    const val KIND_ADDON = "addon"
    private const val TOP_KEEP = 20

    /** One planning run: the day model, the decision points and the memoised simulation. */
    internal class Context(val input: WeekPlanInput, val params: ParamView) {
        val zone = input.zone
        val today: LocalDate = Week.localDate(input.now, zone)
        val dates: List<LocalDate> = (0L..6L).map { input.monday.plusDays(it) }
        private val changeover = input.changeoverMin
        private val trainedMin = params.double("setcredit.trained_min_credit")
        private val heavyFs = params.double("weekplan.heavy_lower_fs")
        private val heavyH = params.double("weekplan.heavy_lower_min_h")
        private val runDoses = input.runs.map { Runs.dose(it, params) }
        val baseDoses: List<Dose> = (input.doses + runDoses).sortedWith(compareBy({ it.t }, { it.id }))
        val gapDays: Map<LocalDate, List<String>> = TreeMap<LocalDate, List<String>>().also { m ->
            input.blocked.filter { it.scope == "tags" }.forEach { b -> m[b.date] = (m[b.date].orEmpty() + b.tags).distinct().sorted() }
        }
        private val restDays = input.blocked.filter { it.scope == "all_gym" }.associateBy { it.date }
        private val challengeDates: Set<LocalDate> =
            input.fixed.filter { it.kind == FixedItem.KIND_CHALLENGE || it.kind == FixedItem.KIND_TEST || (it.kind == FixedItem.KIND_DONE && it.slot != null) }.map { it.date }.toSet()

        val days: List<DayModel> = dates.map { dayModel(it) }
        val points: List<Point>
        val kMax: Int
        val kMaxReduced: Boolean
        private val freeCount: Int

        init {
            val pts = ArrayList<Point>()
            for (d in days.filter { it.status == "free" }) pts += Point(0, Point.FREE, d.date, d.start!!, d.budgetMin!!, listOf<SessionType?>(null) + SessionType.entries)
            for (f in input.fixed.filter { it.kind == FixedItem.KIND_PLANNED_GYM && !it.date.isBefore(today) && it.end.isAfter(input.now) }) {
                val start = if (input.todayOverride?.date == f.date) input.todayOverride.start else maxOf(f.start, input.now)
                val budget = if (input.todayOverride?.date == f.date) input.todayOverride.budgetMin
                else f.budgetMin ?: (Duration.between(f.start, f.end).toMinutes() - changeover)
                // `today` asks for the best week without a session today too (9.4), so its planned event may be left out.
                val types: List<SessionType?> = f.plannedType?.let { listOf(it) } ?: SessionType.entries
                pts += Point(0, Point.PLANNED, f.date, start, budget, if (input.todayOverride?.date == f.date) listOf<SessionType?>(null) + types else types, fixed = f)
            }
            for (f in input.fixed.filter { it.addOn != null && it.addOn.start.isAfter(input.now) }) {
                val a = f.addOn!!
                pts += Point(0, Point.ADDON, f.date, a.start, a.capacityMin, listOf<SessionType?>(null) + a.families.map { it.type }, fixed = f, addOn = a)
            }
            pts.sortWith(compareBy<Point>({ it.start }, { it.kind }, { it.fixed?.key ?: "" }))
            points = pts.mapIndexed { i, p -> Point(i, p.kind, p.date, p.start, p.budgetMin, p.options, p.fixed, p.addOn) }
            freeCount = points.count { it.kind == Point.FREE }
            val used = visitDates().size
            var k = minOf(input.maxVisits - used, freeCount, params.int("weekplan.k_cap")).coerceAtLeast(0)
            var reduced = false
            val maxEvals = params.int("weekplan.max_evals").toDouble()
            while (k > 0 && count(k) > maxEvals) { k--; reduced = true }
            kMax = k
            kMaxReduced = reduced
        }

        /** The dates that already hold a gym visit: logged sessions, fixed challenge sessions and tests, planned gym events. */
        fun visitDates(): Set<LocalDate> = input.fixed.map { it.date }.toSortedSet()

        private fun count(k: Int): Double {
            var other = 1.0
            points.filter { it.kind != Point.FREE }.forEach { other *= it.options.size }
            var n = 0.0
            var types = 1.0
            for (i in 0..k) {
                n += choose(freeCount, i) * types
                types *= SessionType.entries.size
            }
            return n * other
        }

        private fun choose(n: Int, k: Int): Double {
            var r = 1.0
            for (i in 0 until k) r = r * (n - i) / (i + 1)
            return r
        }

        private fun dayModel(d: LocalDate): DayModel {
            if (d.isBefore(today)) return DayModel(d, "past", reason = ReasonCode("PAST_DAY"))
            if (input.fixed.any { it.date == d }) return DayModel(d, "fixed")
            restDays[d]?.let { b ->
                val slot = b.reason.substringAfterLast(' ')
                val next = input.fixed.filter { it.slot == slot && it.date.isAfter(d) }.minByOrNull { it.start }
                return DayModel(d, "blocked", reason = ReasonCode("REST_BEFORE", sortedMapOf("slot" to slot, "rule_id" to b.ruleId, "when" to (next?.start?.toString() ?: ""))))
            }
            val buffer = Duration.ofMinutes(params.int("weekplan.busy_buffer_min").toLong())
            var segs = input.windows[d].orEmpty().sortedBy { it.start }
            if (segs.isEmpty()) return DayModel(d, "no_slot", reason = ReasonCode("NO_SLOT", sortedMapOf("reason" to "outside_availability")))
            for (b in input.busy.sortedWith(compareBy({ it.start }, { it.end }))) segs = subtract(segs, Interval(b.start.minus(buffer), b.end.plus(buffer)))
            if (d == today) segs = subtract(segs, Interval(Instant.MIN, input.now))
            val need = params.int("weekplan.min_slot_min") + changeover
            val seg = segs.firstOrNull { minutes(it) >= need - 1e-9 }
                ?: return DayModel(d, "no_slot", reason = ReasonCode("NO_SLOT", sortedMapOf("reason" to if (d == today) "late" else "busy")))
            var start = seg.start
            if (d == today) start = maxOf(seg.start, ceil5(input.now))
            val budget = minOf(input.defaultSessionMin, minutes(Interval(start, seg.end)) - changeover)
            val o = input.todayOverride?.takeIf { it.date == d }
            return DayModel(d, "free", o?.start ?: start, o?.budgetMin ?: budget)
        }

        private fun ceil5(t: Instant): Instant {
            val m = 5 * 60_000L
            val e = t.toEpochMilli()
            return Instant.ofEpochMilli(((e + m - 1) / m) * m)
        }

        private fun minutes(i: Interval): Double = Duration.between(i.start, i.end).toMillis() / 60_000.0

        private fun subtract(segs: List<Interval>, cut: Interval): List<Interval> = segs.flatMap { s ->
            if (!s.overlaps(cut)) listOf(s) else listOfNotNull(
                Interval(s.start, cut.start).takeIf { it.start.isBefore(it.end) },
                Interval(cut.end, s.end).takeIf { it.start.isBefore(it.end) },
            )
        }

        // ---- Enumeration (8.6) ----

        fun enumerate(visit: (Eval) -> Unit) {
            val choice = IntArray(points.size)
            fun rec(i: Int, freeUsed: Int) {
                if (i == points.size) {
                    simulate(choice)?.let(visit)
                    return
                }
                val p = points[i]
                for (c in p.options.indices) {
                    val t = p.options[c]
                    val nf = freeUsed + if (p.kind == Point.FREE && t != null) 1 else 0
                    if (nf > kMax) continue
                    choice[i] = c
                    rec(i + 1, nf)
                }
                choice[i] = 0
            }
            rec(0, 0)
        }

        private val allocMemo = TreeMap<String, Allocation>()
        private val readyMemo = TreeMap<String, ReadinessOut>()

        private fun ready(t: Instant, extra: List<Dose>, key: String): ReadinessOut =
            readyMemo.getOrPut("${t.toEpochMilli()}|$key") { Readiness.evaluate(baseDoses + extra, t, null, null, params) }

        private val protectMemo = TreeMap<String, ReadinessOut>()

        /** Readiness at a penalty window's end over the base doses and the plan doses [keep] passes, memoised on their allocation keys. */
        private fun protectReady(wi: Int, t: Instant, extra: List<Dose>, keys: List<String>, keep: (Dose) -> Boolean): ReadinessOut {
            val kept = extra.indices.filter { keep(extra[it]) }
            val key = "$wi|" + kept.joinToString(";") { extra[it].id + "=" + keys[it] }
            return protectMemo.getOrPut(key) { Readiness.evaluate(baseDoses + kept.map { extra[it] }, t, null, null, params) }
        }

        private val membersMemo = TreeMap<SessionType, Set<String>>()

        private fun members(t: SessionType): Set<String> = membersMemo.getOrPut(t) {
            val m = t.families.flatMap { Groups.FAMILIES.getValue(it) }.toMutableSet()
            if (t != SessionType.CORE) m += Groups.FAMILIES.getValue("CORE")
            m
        }

        fun hardWindows(start: Instant, budgetMin: Double) = input.protectedWindows.filter {
            it.mode == "hard" && it.from.isBefore(start.plusMillis((budgetMin * 60_000.0).toLong())) && start.isBefore(it.to)
        }

        /** Why the last [simulate] returned null: the reason code (0.6) and the point it failed at. */
        var failure: Pair<String, Int>? = null

        /** Simulates one plan in time order; null when a hard constraint fails or an allocation is infeasible. */
        fun simulate(choice: IntArray): Eval? {
            failure = null
            val chosen = points.filter { it.options[choice[it.index]] != null }
            val standalone = chosen.filter { it.standalone }
            // H8 (one visit per day) holds by construction: free days have no gym item; H5 by kMax.
            val vh = TreeMap<String, Double>()
            Groups.CANONICAL.forEach { g -> vh[g] = (input.done[g] ?: 0.0) + (input.committed[g] ?: 0.0) }
            val trained = TreeMap(input.sessionsTraining)
            val extra = ArrayList<Dose>()
            // The allocation memo key behind each dose of [extra]: equal keys give equal doses (the protection memo).
            val extraKeys = ArrayList<String>()
            val sessions = ArrayList<SimSession>()
            // The week's general sessions and, across the week boundary, the previous week's (D74).
            val generalDone = input.priorGeneral + input.fixed.filter { it.general }
            val heavy = generalDone.filter { it.heavyLowerFs >= heavyFs - 1e-9 }.map { it.start }.toMutableList()
            val tagged = generalDone.filter { it.tags.any { t -> t in input.gapRule?.appliesToTags.orEmpty() } }.map { it.date }.toMutableList()
            var prefix = ""
            var runPen = 0.0
            var lowG = 0
            var spill = 0.0
            for (p in chosen) {
                val type = p.options[choice[p.index]]!!
                if (input.protectedWindows.any { it.mode == "hard" && it.scope == "all_gym" } && p.standalone &&
                    hardWindows(p.start, p.budgetMin).any { it.scope == "all_gym" }
                ) { failure = "REST_BEFORE" to p.index; return null } // H3
                val r = ready(p.start, extra, prefix)
                val opp = TreeMap<String, Int>()
                val later = standalone.filter { it.start >= p.start }
                for (g in Groups.CANONICAL) opp[g] = later.count { s -> g in members(s.options[choice[s.index]]!!) }.coerceAtLeast(1)
                val allocKey = "${p.index}:${choice[p.index]}|$prefix|${opp.values.joinToString(",")}"
                val alloc = allocMemo.getOrPut(allocKey) { allocate(p, type, vh, opp, r) }
                if (!alloc.feasible) { failure = (alloc.infeasibleReason ?: "NOTHING_TO_TRAIN") to p.index; return null }
                // H6: heavy-lower sessions at least HEAVY_LOWER_MIN_H apart.
                val lower = listOf("quads", "hamstrings", "glutes").sumOf { alloc.credit[it] ?: 0.0 }
                if (lower >= heavyFs - 1e-9) {
                    if (heavy.any { kotlin.math.abs(Week.elapsedHours(it, p.start)) < heavyH - 1e-9 }) { failure = "HEAVY_LOWER_GAP" to p.index; return null }
                    heavy += p.start
                }
                // H1 pairwise: general sessions with a gap-rule tag keep `minGapDays` apart.
                val gr = input.gapRule
                if (gr != null && alloc.predictedTags.any { it in gr.appliesToTags }) {
                    if (tagged.any { kotlin.math.abs(Week.calendarDays(it, p.date)) <= gr.days }) { failure = "GAP_DAY" to p.index; return null }
                    tagged += p.date
                }
                val before = TreeMap(vh)
                alloc.vhyp.forEach { (g, v) -> vh[g] = (vh[g] ?: 0.0) + v }
                Groups.CANONICAL.filter { (alloc.vhyp[it] ?: 0.0) >= trainedMin }.forEach { trained[it] = (trained[it] ?: 0) + 1 }
                alloc.dose?.let { extra += it.copy(id = "plan:${p.index}:${choice[p.index]}"); extraKeys += allocKey }
                val legFs = Groups.FAMILIES.getValue("LEGS").sumOf { alloc.direct[it] ?: 0.0 }
                val pen = Runs.penalty(p.start, p.start.plusMillis((alloc.estMinutes * 60_000.0).toLong()), legFs, input.runs, input.runningPriority, params).value
                runPen += pen
                if (p.standalone && r.g < params.double("weekplan.g_low")) lowG++
                input.targets.groups.forEach { (g, gt) -> if (gt.status == "skip") spill += alloc.credit[g] ?: 0.0 }
                sessions += SimSession(p, type, alloc, r, before, pen)
                prefix += "${p.index}:${choice[p.index]};"
            }
            return score(choice, sessions, vh, trained, extra, extraKeys, runPen, lowG, spill)
        }

        private fun allocate(p: Point, type: SessionType, vBefore: Map<String, Double>, opp: Map<String, Int>, r: ReadinessOut): Allocation {
            val hard = hardWindows(p.start, p.budgetMin).filter { it.scope != "all_gym" }
            val a = p.addOn
            val fam = a?.families?.first { it.type == type }
            val forbiddenGroups = input.ownedGroups + hard.flatMap { it.groups } + fam?.slot?.forbidGroups.orEmpty()
            val tags = (gapDays[p.date].orEmpty() + hard.flatMap { it.sessionTags } + input.forbiddenSessionTags + a?.forbiddenSessionTags.orEmpty()).toSortedSet()
            val available = if (fam != null) Groups.CANONICAL.associateWith { it in fam.slot.allowedGroups }
            else input.availability.of(p.date !in challengeDates, gapDays[p.date].orEmpty(), hard.map { it.id }.sorted())
            return Allocator.allocate(
                AllocInput(
                    type = type, start = p.start, budgetMin = p.budgetMin, vBefore = vBefore, opp = opp, readiness = r.r, targets = input.targets,
                    available = available, forbiddenGroups = forbiddenGroups.toSortedSet(), forbiddenSessionTags = tags, tagRules = input.tagRules,
                    addOn = fam?.slot, qBase = input.qBase, doseId = "plan:${p.index}",
                ),
                params,
            )
        }

        private fun score(
            choice: IntArray,
            sessions: List<SimSession>,
            vEnd: Map<String, Double>,
            trained: Map<String, Int>,
            extra: List<Dose>,
            extraKeys: List<String>,
            runPen: Double,
            lowG: Int,
            spill: Double,
        ): Eval {
            val t = input.targets
            var util = 0.0
            var freq = 0.0
            for (g in Groups.CANONICAL) {
                val gt = t.groups[g] ?: continue
                util += gt.w * Targets.utility(gt, vEnd[g] ?: 0.0, t.base, params)
                if (gt.status == "improve" && (trained[g] ?: 0) >= gt.freqTarget) freq += gt.w
            }
            freq *= params.double("weekplan.freq_bonus")
            // Protection (8.6): the marginal deficit the plan's sessions inside a penalty window cause at its reference time.
            var protect = 0.0
            for ((wi, w) in input.protectedWindows.withIndex()) {
                if (w.mode != "penalty" || !w.to.isAfter(input.now)) continue
                val inside = sessions.filter { !it.point.start.isBefore(w.from) && it.point.start.isBefore(w.to) }
                if (inside.isEmpty()) continue
                val ids = inside.map { "plan:${it.point.index}:${choice[it.point.index]}" }.toSet()
                val with = protectReady(wi, w.to, extra, extraKeys) { true }
                val without = protectReady(wi, w.to, extra, extraKeys) { it.id !in ids }
                val rMin = w.rMin ?: 0.0
                for (g in w.groups) protect += maxOf(0.0, rMin - (with.r[g] ?: 1.0)) - maxOf(0.0, rMin - (without.r[g] ?: 1.0))
                for ((k, min) in w.tissueRMin.toSortedMap()) protect += maxOf(0.0, min - (with.tissueR[k] ?: 1.0)) - maxOf(0.0, min - (without.tissueR[k] ?: 1.0))
            }
            protect *= params.double("weekplan.protect_w")
            // Consecutive gym days and the week without a rest day.
            val gymDates = (input.fixed.map { it.date } + sessions.filter { it.point.standalone }.map { it.point.date }).toSortedSet()
            var run = 0
            var maxRun = 0
            for (d in dates) { run = if (d in gymDates) run + 1 else 0; maxRun = maxOf(maxRun, run) }
            val consec = params.double("weekplan.consec_w") * maxOf(0, maxRun - params.int("weekplan.consec_free"))
            val nGeneral = sessions.count { it.point.kind == Point.FREE }
            val nAddOn = sessions.count { !it.point.standalone }
            val sessionCost = params.double("weekplan.session_cost") * nGeneral
            val addOnCost = params.double("weekplan.addon_cost") * nAddOn
            val noRest = if (dates.all { it in gymDates }) params.double("weekplan.no_rest_w") else 0.0
            val skip = params.double("weekplan.skip_w") * spill / 4.0
            val low = params.double("weekplan.lowg_w") * lowG
            var stable = 0
            if (input.previous.isNotEmpty()) {
                val keys = keys(sessions)
                for (d in dates.filter { !it.isBefore(today) }) if (input.previous[d] != null && input.previous[d] == (keys[d] ?: "none")) stable++
            }
            val stability = params.double("weekplan.stability_w") * stable
            val j = util + freq - protect - runPen - consec - sessionCost - addOnCost - noRest - skip - low + stability
            val terms = TreeMap<String, Double>()
            terms["addons"] = -addOnCost; terms["consecutive"] = -consec; terms["freq"] = freq; terms["low_g"] = -low; terms["no_rest"] = -noRest
            terms["protect"] = -protect; terms["runs"] = -runPen; terms["sessions"] = -sessionCost; terms["skip"] = -skip; terms["stability"] = stability
            terms["utility"] = util
            terms.replaceAll { _, v -> Grid.ratio(v) }
            return Eval(choice.copyOf(), sessions, round6(j), terms, extra, vEnd)
        }

        private fun keys(sessions: List<SimSession>): Map<LocalDate, String> {
            val out = TreeMap<LocalDate, String>()
            sessions.filter { it.point.kind == Point.FREE }.forEach { out[it.point.date] = "general:${it.type.code}" }
            sessions.filter { !it.point.standalone }.forEach { if (out[it.point.date] == null) out[it.point.date] = "addon:${it.type.code}" }
            return out
        }

        // ---- Tie key (C52) ----

        private fun firstStart(e: Eval): Instant? = e.sessions.firstOrNull { it.point.kind == Point.FREE }?.point?.start
        private fun general(e: Eval) = e.sessions.filter { it.point.standalone }
        private fun addOnTuple(e: Eval): List<Int> = points.filter { it.kind == Point.ADDON }.map { addOnRank(it.options[e.choice[it.index]]) }

        val tieKey: Comparator<Eval> = Comparator { a, b -> tieCompare(a, b).first }

        fun tieKeyUsed(a: Eval, b: Eval): String = tieCompare(a, b).second

        private fun tieCompare(a: Eval, b: Eval): Pair<Int, String> {
            val fa = firstStart(a)
            val fb = firstStart(b)
            val c1 = when {
                fa == null && fb == null -> 0
                fa == null -> 1
                fb == null -> -1
                else -> fa.compareTo(fb)
            }
            if (c1 != 0) return c1 to "earlier_session"
            val c2 = a.sessions.count { it.point.kind == Point.FREE }.compareTo(b.sessions.count { it.point.kind == Point.FREE })
            if (c2 != 0) return c2 to "fewer_sessions"
            val c3 = round6(b.j).compareTo(round6(a.j))
            if (c3 != 0) return c3 to "higher_j"
            val c4 = lex(general(a).map { it.point.date.toEpochDay().toInt() }, general(b).map { it.point.date.toEpochDay().toInt() })
            if (c4 != 0) return c4 to "dates"
            val c5 = lex(general(a).map { it.type.ordinal }, general(b).map { it.type.ordinal })
            if (c5 != 0) return c5 to "type_order"
            return lex(addOnTuple(a), addOnTuple(b)) to "addon"
        }

        private fun lex(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return a.size.compareTo(b.size)
        }

        // ---- Output (8.3, 8.7) ----

        fun output(chosen: Eval, band: Band, top: List<Eval>, evaluated: Int): WeekPlan {
            val byDate = chosen.sessions.groupBy { it.point.date }
            val visitDates = (visitDates() + chosen.sessions.filter { it.point.standalone }.map { it.point.date }).toSortedSet()
            val doneDates = input.fixed.filter { it.kind == FixedItem.KIND_DONE || it.kind == FixedItem.KIND_DONE_GENERAL }.map { it.date }.toSortedSet()
            val fixedDates = input.fixed.filter { it.kind != FixedItem.KIND_DONE && it.kind != FixedItem.KIND_DONE_GENERAL }.map { it.date }.filter { it !in doneDates }.toSortedSet()
            val recommended = chosen.sessions.count { it.point.kind == Point.FREE }
            val planDays = days.map { dm ->
                val items = ArrayList<PlanItem>()
                for (f in input.fixed.filter { it.date == dm.date }.sortedWith(compareBy({ it.start }, { it.key }))) {
                    val sim = byDate[dm.date]?.firstOrNull { it.point.fixed === f && it.point.kind == Point.PLANNED }
                    items += PlanItem(
                        f.key, f.kind, f.slot, sim?.type?.code ?: f.plannedType?.code, sim?.alloc?.label ?: f.label, f.start, f.end,
                        sim?.alloc?.estMinutes ?: Duration.between(f.start, f.end).toMinutes().toDouble(), sim?.alloc?.direct.orEmpty(),
                        sim?.alloc?.credit.orEmpty(), sim?.alloc?.rirOffset.orEmpty(),
                        writePolicy = when (f.kind) { FixedItem.KIND_CHALLENGE, FixedItem.KIND_TEST -> "auto"; else -> "never" }, source = "fixed",
                    )
                    byDate[dm.date]?.firstOrNull { it.point.fixed === f && it.point.kind == Point.ADDON }?.let { s ->
                        items += item(s, KIND_ADDON, "in_parent_description", "${f.key}:addon")
                    }
                }
                byDate[dm.date]?.filter { it.point.kind == Point.FREE }?.forEach { s -> items += item(s, KIND_RECOMMENDED, "on_plan_it", "gen:${dm.date}") }
                val gym = items.isNotEmpty()
                val reason = if (gym) null else dm.reason ?: when {
                    visitDates.size >= input.maxVisits -> ReasonCode("MAX_VISITS", sortedMapOf("used" to visitDates.size.toString(), "max" to input.maxVisits.toString()))
                    recovering(dm, chosen) != null -> recovering(dm, chosen)
                    else -> ReasonCode("NOT_NEEDED")
                }
                val fams = if (dm.status == "free" && !gym) allowed(dm) else null
                PlanDay(
                    dm.date, dm.status, items, reason, fams?.first, fams?.second ?: false, dm.start, dm.budgetMin,
                )
            }
            val projected = TreeMap<String, Projected>()
            for (g in Groups.CANONICAL) {
                val gt = input.targets.groups[g] ?: continue
                val planned = chosen.sessions.sumOf { it.alloc.vhyp[g] ?: 0.0 }
                projected[g] = Projected(
                    Grid.ratio(input.done[g] ?: 0.0), Grid.ratio(input.committed[g] ?: 0.0), Grid.ratio(planned), Grid.ratio(chosen.vEnd[g] ?: 0.0),
                    gt.t, gt.targetEffective,
                )
            }
            val warnings = ArrayList<ReasonCode>()
            val runRest = input.blocked.filter { it.scope == "all_gym" }.map { RunRestDay(it.date, it.reason.substringAfterLast(' ')) }
            Runs.restDayWarnings(input.runs, runRest, zone).forEach { warnings += ReasonCode(it.code, sortedMapOf("run_id" to it.runId, "slot" to it.slot)) }
            val fixedVisits = visitDates().size
            if (fixedVisits > input.maxVisits) warnings += ReasonCode("MAX_VISITS", sortedMapOf("used" to fixedVisits.toString(), "max" to input.maxVisits.toString()))
            val plan = WeekPlan(
                isoWeek = input.isoWeek, planId = "", provisional = input.provisional, days = planDays,
                visits = Visits(doneDates.size, fixedDates.size, recommended, input.maxVisits), recommendedN = visitDates.size, projected = projected,
                j = Grid.ratio(chosen.j), terms = chosen.terms, band = band, warnings = warnings,
                top = top.map { PlanSummary(Grid.ratio(it.j), it.terms, describe(it)) }, evaluated = evaluated, kMax = kMax, kMaxReduced = kMaxReduced,
                planDoses = chosen.doses,
            )
            return plan.copy(planId = Sha256Hex.ofUtf8(canonical(plan)).take(16))
        }

        private fun item(s: SimSession, kind: String, policy: String, key: String): PlanItem {
            val end = s.point.start.plusMillis((s.alloc.estMinutes * 60_000.0).toLong())
            return PlanItem(
                key, kind, s.point.fixed?.slot, s.type.code, s.alloc.label, s.point.start, end, s.alloc.estMinutes, s.alloc.direct, s.alloc.credit,
                s.alloc.rirOffset, policy, "weekplan",
            )
        }

        /** `RECOVERING` (8.7): every family's best group is below its readiness target at the day's start. */
        private fun recovering(dm: DayModel, chosen: Eval): ReasonCode? {
            val start = dm.start ?: return null
            val r = Readiness.evaluate(baseDoses + chosen.doses, start, null, null, params)
            val next = TreeMap<String, String>()
            for (f in listOf("LEGS", "PUSH", "PULL", "CORE")) {
                val groups = Groups.FAMILIES.getValue(f).filter { val gt = input.targets.groups[it]; gt != null && (gt.status == "improve" || gt.status == "maintain") }
                if (groups.isEmpty()) continue
                val target = { g: String -> params.double(if (input.targets.groups.getValue(g).status == "improve") "readiness.r_target_improve" else "readiness.r_target_maintain") }
                if (groups.any { (r.r[it] ?: 1.0) >= target(it) }) return null
                next[f.lowercase()] = groups.minOf { Readiness.nextReady(it, target(it), baseDoses + chosen.doses, start, null, params) }.toString()
            }
            return if (next.isEmpty()) null else ReasonCode("RECOVERING", next)
        }

        /** `allowedFamilies` of a free day (8.7): the families with an available, allowed group; light pull on a gap day (C50). */
        private fun allowed(dm: DayModel): Pair<List<String>, Boolean>? {
            val start = dm.start ?: return null
            val hard = hardWindows(start, dm.budgetMin ?: 0.0).filter { it.scope != "all_gym" }
            val gap = dm.date !in challengeDates
            val avail = input.availability.of(gap, gapDays[dm.date].orEmpty(), hard.map { it.id }.sorted())
            val forbidden = input.ownedGroups + hard.flatMap { it.groups }
            val fams = listOf("LEGS", "PUSH", "PULL", "CORE").filter { f ->
                Groups.FAMILIES.getValue(f).any { g ->
                    val st = input.targets.groups[g]?.status
                    avail[g] == true && g !in forbidden && (st == "improve" || st == "maintain")
                }
            }
            // C50: on a gap day pull is light pull only; it is a flag, not one of the allowed families ("legs / push / core; light pull").
            val light = "PULL" in fams && gapDays[dm.date] != null
            return if (fams.size == 4 && !light) null else (if (light) fams - "PULL" else fams) to light
        }

        private fun describe(e: Eval): String = points.joinToString(" ") { p ->
            val t = p.options[e.choice[p.index]]
            "${p.date}/${p.kind}=${t?.code ?: "-"}"
        }

        private fun canonical(p: WeekPlan): String = buildString {
            append(p.isoWeek).append('|').append(p.provisional).append('|').append(Canon.esNumber(p.j, GridKind.RATIO))
            p.days.forEach { d ->
                append('|').append(d.date).append(':').append(d.status).append(':').append(d.restReason?.code ?: "")
                d.items.forEach { i -> append(',').append(i.kind).append('/').append(i.type ?: i.slot ?: "").append('@').append(i.start) }
            }
        }
    }
}
