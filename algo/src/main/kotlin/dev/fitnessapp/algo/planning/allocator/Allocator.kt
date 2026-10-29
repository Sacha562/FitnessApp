package dev.fitnessapp.algo.planning.allocator

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.targets.GroupTargets
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.WeeklyTargets
import java.time.Instant
import java.util.TreeMap

/** The candidate session types of ALGORITHMS_PLANNING 0.3, in the fixed tie order. */
enum class SessionType(val code: String, val families: List<String>) {
    LEGS("LEGS", listOf("LEGS")),
    PUSH("PUSH", listOf("PUSH")),
    PULL("PULL", listOf("PULL")),
    PUSH_LEGS("PUSH+LEGS", listOf("PUSH", "LEGS")),
    PULL_LEGS("PULL+LEGS", listOf("PULL", "LEGS")),
    UPPER("UPPER", listOf("PUSH", "PULL")),
    FULL("FULL", listOf("PUSH", "PULL", "LEGS")),
    CORE("CORE", listOf("CORE"));

    companion object {
        fun of(code: String): SessionType = requireNotNull(entries.firstOrNull { it.code == code }) { "unknown session type $code" }
    }
}

/**
 * An add-on block after a challenge session (C34, the contract's `addOnSlot`): its family (LEGS, PUSH or CORE), the
 * groups its allowed patterns can train directly ([allowedGroups], from `Elig.groupAvailability` with the slot filter),
 * the forbidden groups, the set cap and the RIR floor.
 */
data class AddOnSlot(
    val family: String,
    val allowedGroups: Set<String>,
    val forbidGroups: Set<String> = emptySet(),
    val maxSets: Double? = null,
    val minRir: Int? = null,
)

/**
 * The inputs of one allocation (7.2). [vBefore] is the Vhyp accounted before this session (done, committed challenge
 * coverage and earlier sessions of the plan); [opp] the standalone general sessions of the plan from this one on whose
 * type includes the group; [readiness] `R_g` at [start]; [available] ELIG-1's group availability under the day's
 * constraints; [forbiddenGroups] the hard-window groups and the covered groups; [forbiddenSessionTags] the gap rule,
 * hard-window session tags and the safety state's forbidden session tags.
 */
data class AllocInput(
    val type: SessionType,
    val start: Instant,
    val budgetMin: Double,
    val vBefore: Map<String, Double>,
    val opp: Map<String, Int>,
    val readiness: Map<String, Double>,
    val targets: WeeklyTargets,
    val available: Map<String, Boolean>,
    val forbiddenGroups: Set<String> = emptySet(),
    val forbiddenSessionTags: Set<String> = emptySet(),
    val tagRules: List<SessionTagRule> = emptyList(),
    val addOn: AddOnSlot? = null,
    val qBase: Double = 2.0,
    /** The id of the projected readiness dose (7.6). */
    val doseId: String = "alloc",
)

/** A group the allocator left out, with its reason code (0.6). */
data class Dropped(val group: String, val reason: String)

/** `Allocation` of 7.3. Maps are keyed in canonical order and hold only non-zero entries. */
data class Allocation(
    val type: SessionType,
    val addOn: Boolean,
    val label: String,
    val feasible: Boolean,
    val infeasibleReason: String?,
    val alloc: Map<String, Double>,
    val normal: Map<String, Double>,
    val direct: Map<String, Double>,
    val credit: Map<String, Double>,
    val setFactor: Map<String, Double>,
    val rirOffset: Map<String, Int>,
    /** The add-on's RIR floor applied on top of the transforms: `max(q_base + offset, slot.minRir)` per group. */
    val rirTarget: Map<String, Double>,
    val estMinutes: Double,
    val normalMinutes: Double,
    val predictedTags: List<String>,
    val dropped: List<Dropped>,
    /** The projected Vhyp per group (7.6), what the planner adds to `Vbefore` of later sessions. */
    val vhyp: Map<String, Double>,
    val dose: Dose?,
)

/**
 * `allocator@1.0.0` SessionDoseAllocator.v1 (ALGORITHMS_PLANNING 7): how many fractional sets each group of one
 * candidate session gets, from the remaining weekly targets, readiness, the per-session caps, the day's windows and
 * gap-rule tag limits, the time budget (TIME-1's dose estimator) and this week's transforms. Cheap by design: no
 * exercise is chosen here (SEL-1 does that with [Allocation.credit] as its targets).
 */
object Allocator : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("allocator")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.table("allocator.indirect", "[ratio, share] (by source:target group)", 0.0, 1.0),
        ParamSpec.table("allocator.min_total", "FS (by session kind)", 1.0, 5.0),
        ParamSpec("allocator.primary_order", ParamType.ENUM_ARRAY, "group ids", enumValues = Groups.CANONICAL),
        ParamSpec.table("allocator.sys_rate", "systemic weight per direct FS (by group)", 0.0, 1.5),
        ParamSpec.table("allocator.tissue_rate", "tissue weight per direct FS (by group:tissue)", 0.0, 1.0),
    )

    private val TAG_CAPPED = setOf("pull_heavy", "elbow_flexor_heavy")

    /** The indirect-credit table (7.4): source -> list of (target, ratio · share), sources and targets in canonical order. */
    fun indirectTable(params: ParamView): Map<String, List<Pair<String, Double>>> {
        val t = params.tableArrays("allocator.indirect")
        val out = TreeMap<String, List<Pair<String, Double>>>()
        for (src in Groups.CANONICAL) {
            val row = Groups.CANONICAL.mapNotNull { tgt -> t["$src:$tgt"]?.let { tgt to it[0] * it[1] } }
            if (row.isNotEmpty()) out[src] = row
        }
        return out
    }

    /** Expected indirect FS per target from direct FS [d] (7.4). */
    fun indirect(d: Map<String, Double>, table: Map<String, List<Pair<String, Double>>>): Map<String, Double> {
        val out = TreeMap<String, Double>()
        for (src in Groups.CANONICAL) {
            val x = d[src] ?: continue
            if (x <= 0.0) continue
            table[src]?.forEach { (tgt, k) -> out[tgt] = (out[tgt] ?: 0.0) + k * x }
        }
        return out
    }

    /** `credit = direct + indirect(direct)` on the RATIO grid, non-zero entries only. */
    fun credit(d: Map<String, Double>, table: Map<String, List<Pair<String, Double>>>): Map<String, Double> {
        val ind = indirect(d, table)
        val out = TreeMap<String, Double>()
        for (g in Groups.CANONICAL) {
            val v = (d[g] ?: 0.0) + (ind[g] ?: 0.0)
            if (v > 0.0) out[g] = Grid.ratio(v)
        }
        return out
    }

    fun allocate(input: AllocInput, params: ParamView): Allocation {
        val addon = input.addOn != null
        val gts = input.targets.groups
        val table = indirectTable(params)
        val stimBase = SetCredit.stim(input.qBase, params)
        val rExclude = params.double("readiness.r_exclude")
        val rFull = params.double("readiness.r_full")
        val minTotal = params.table("allocator.min_total").getValue(if (addon) "addon" else "standalone")
        val dropped = ArrayList<Dropped>()

        val families = if (addon) listOf(input.addOn!!.family) else input.type.families
        val members = LinkedHashSet<String>()
        families.forEach { members += Groups.FAMILIES.getValue(it) }
        if (!addon && input.type != SessionType.CORE) members += Groups.FAMILIES.getValue("CORE")
        if (addon) members.retainAll { it in input.addOn!!.allowedGroups && it !in input.addOn.forbidGroups }

        val a = TreeMap<String, Double>()
        for (g in Groups.CANONICAL) {
            if (g !in members) continue
            val gt = gts[g] ?: continue
            if (gt.status == "skip") continue
            if (gt.status == "covered") { dropped += Dropped(g, "COVERED_BY_CHALLENGE"); continue }
            if (g in input.forbiddenGroups) { dropped += Dropped(g, "PROTECT_REST"); continue }
            if (input.available[g] != true) { dropped += Dropped(g, "NO_ELIGIBLE_EXERCISE"); continue }
            val remFS = maxOf(0.0, gt.t - (input.vBefore[g] ?: 0.0)) / stimBase
            if (remFS <= 1e-9) { dropped += Dropped(g, "TARGETS_MET"); continue }
            val r = input.readiness[g] ?: 1.0
            if (r < rExclude) { dropped += Dropped(g, "RECOVERING"); continue }
            var x = Groups.ceilHalf(remFS / maxOf(1, input.opp[g] ?: 1)).coerceIn(gt.minTouch, gt.capS)
            if (r < rFull) x = Groups.roundHalf(x * r)
            if (x > 0.0) a[g] = x
        }
        if (a.values.sum() < minTotal) return infeasible(input, "NOTHING_TO_TRAIN", a, dropped)

        // 1. Expected indirect credit: primaries first, then the rest in canonical order.
        val normal = TreeMap<String, Double>()
        val primary = params.strings("allocator.primary_order")
        val order = primary.filter { it in a } + Groups.CANONICAL.filter { it in a && it !in primary }
        for (g in order) {
            val ind = table.entries.sumOf { (src, row) -> (normal[src] ?: 0.0) * (row.firstOrNull { it.first == g }?.second ?: 0.0) }
            val x = maxOf(0.0, Groups.ceilHalf(a.getValue(g) - ind))
            if (x > 0.0) normal[g] = x
        }

        // 2. Tag caps (C11, C12, C33): the session must not acquire a forbidden group-count tag.
        for (rule in input.tagRules.filter { it.tag in input.forbiddenSessionTags && it.tag in TAG_CAPPED && it.countsGroups }) {
            while (rule.groups.sumOf { credit(normal, table)[it] ?: 0.0 } > rule.minHardSets - 1.0 + 1e-9) {
                // The rule groups and the groups that credit them indirectly (a gap day leaves the rule groups without
                // direct sets; the reverse pec deck's upper-back credit is what reaches the cap).
                val feeders = normal.keys.filter { g -> g in rule.groups || table[g]?.any { it.first in rule.groups } == true }
                val g = feeders.minWithOrNull(
                    compareBy<String> { gts.getValue(it).w }.thenByDescending { normal.getValue(it) }.thenBy { Groups.CANONICAL.indexOf(it) },
                ) ?: break
                reduce(normal, g, 0.5)
            }
        }

        // 3. Time fit with the dose estimator: drop the cheapest utility per minute first.
        val beforeFit = normal.keys.toList()
        val budgetS = input.budgetMin * 60.0
        while (TimeModel.estDose(normal, !addon, params) > budgetS + 1e-9) {
            val base = TimeModel.estDose(normal, !addon, params)
            val uBase = utility(normal, input, table, stimBase, params)
            var best: String? = null
            var bestRatio = Double.MAX_VALUE
            var bestCand: Map<String, Double>? = null
            for (g in Groups.CANONICAL) {
                val x = normal[g] ?: continue
                val cand = TreeMap(normal)
                reduce(cand, g, 0.5)
                if ((credit(cand, table)[g] ?: 0.0) < gts.getValue(g).minTouch - 1e-9) cand.remove(g)
                val dm = (base - TimeModel.estDose(cand, !addon, params)) / 60.0
                if (dm <= 1e-9 || x <= 0.0) continue
                val ratio = round6((uBase - utility(cand, input, table, stimBase, params)) / dm)
                if (ratio < bestRatio) { bestRatio = ratio; best = g; bestCand = cand }
            }
            if (best == null) return infeasible(input, "TIME_SHORT", a, dropped)
            normal.clear()
            normal.putAll(bestCand!!)
        }
        beforeFit.filter { it !in normal }.forEach { dropped += Dropped(it, "TIME_SHORT") }

        // 4. Session transforms (C13, C27, C51): set factor min, RIR offset max, never below one set.
        val direct = TreeMap<String, Double>()
        val setFactor = TreeMap<String, Double>()
        val rirOffset = TreeMap<String, Int>()
        for ((g, x) in normal) {
            val tr = gts.getValue(g).transform
            val f = tr?.setFactor ?: 1.0
            direct[g] = if (f < 1.0) maxOf(1.0, Groups.roundHalf(x * f)) else x
            setFactor[g] = f
            rirOffset[g] = tr?.rirOffset ?: 0
        }
        val rirTarget = TreeMap<String, Double>()
        if (addon) {
            val cap = input.addOn!!.maxSets
            while (cap != null && direct.values.sum() > cap + 1e-9) {
                val g = direct.keys.minWithOrNull(
                    compareBy<String> { gts.getValue(it).w }.thenByDescending { direct.getValue(it) }.thenBy { Groups.CANONICAL.indexOf(it) },
                ) ?: break
                reduce(direct, g, 0.5)
            }
        }
        for (g in direct.keys) {
            val q = input.qBase + (rirOffset[g] ?: 0)
            rirTarget[g] = maxOf(q, input.addOn?.minRir?.toDouble() ?: q)
        }
        val credit = credit(direct, table)
        if (credit.values.sum() < minTotal) return infeasible(input, "NOTHING_TO_TRAIN", a, dropped)
        val tags = input.tagRules.filter { it.reached(credit) }.map { it.tag }
        val estS = TimeModel.estDose(direct, !addon, params)
        val (vhyp, dose) = projectedDose(input, direct, credit, rirTarget, estS, params)
        return Allocation(
            input.type, addon, label(direct, addon), true, null, grid(a), grid(normal), grid(direct), credit, setFactor, rirOffset,
            rirTarget, TimeModel.minutes(estS), TimeModel.minutes(TimeModel.estDose(normal, !addon, params)), tags, dropped, vhyp, dose,
        )
    }

    /** `utility(d)` of 7.4: the weighted utility gain of the credit of [d], with the quality factor `0.5 + 0.5 · R`. */
    private fun utility(d: Map<String, Double>, input: AllocInput, table: Map<String, List<Pair<String, Double>>>, stimBase: Double, params: ParamView): Double {
        val c = credit(d, table)
        var u = 0.0
        for (g in Groups.CANONICAL) {
            val x = c[g] ?: continue
            val gt: GroupTargets = input.targets.groups[g] ?: continue
            val v0 = input.vBefore[g] ?: 0.0
            val q = 0.5 + 0.5 * (input.readiness[g] ?: 1.0)
            u += gt.w * (Targets.utility(gt, v0 + x * stimBase * q, input.targets.base, params) - Targets.utility(gt, v0, input.targets.base, params))
        }
        return u
    }

    /** The projected dose of the planned session (7.6), at `start + estMinutes`. */
    private fun projectedDose(
        input: AllocInput,
        direct: Map<String, Double>,
        credit: Map<String, Double>,
        q: Map<String, Double>,
        estS: Double,
        params: ParamView,
    ): Pair<Map<String, Double>, Dose> {
        val qBase = input.qBase
        val novelty = params.table("setcredit.novelty").getValue("over_21d")
        val tissueRate = params.table("allocator.tissue_rate")
        val sysRate = params.table("allocator.sys_rate")
        val vhyp = TreeMap<String, Double>()
        val fat = TreeMap<String, Double>()
        for ((g, c) in credit) {
            val gq = q[g] ?: (qBase + (input.targets.groups[g]?.transform?.rirOffset ?: 0))
            val r = input.readiness[g] ?: 1.0
            vhyp[g] = Grid.ratio(c * SetCredit.stim(gq, params) * (0.5 + 0.5 * r))
            val nov = if (input.targets.groups[g]?.reentry?.active == true) novelty else 1.0
            fat[g] = Grid.ratio(c * SetCredit.fat(gq, params) * nov)
        }
        val tissue = TreeMap<String, Double>()
        for (k in Groups.TISSUES) {
            val v = direct.entries.sumOf { (g, x) -> x * (tissueRate["$g:$k"] ?: 0.0) * SetCredit.fat(q[g] ?: qBase, params) }
            if (v > 0.0) tissue[k] = Grid.ratio(v)
        }
        val sys = Grid.ratio(direct.entries.sumOf { (g, x) -> x * sysRate.getValue(g) } / params.double("setcredit.sys_divisor"))
        val t = input.start.plusMillis(kotlin.math.round(estS * 1000.0).toLong())
        return vhyp to Dose(input.doseId, t, fat, tissue, sys)
    }

    /** The session label of 7.5 from the groups that receive direct FS. */
    fun label(direct: Map<String, Double>, addon: Boolean): String {
        val groups = direct.filterValues { it > 0.0 }.keys
        val words = ArrayList<String>()
        if (groups.any { it in Groups.FAMILIES.getValue("LEGS") }) words += "legs"
        if (groups.any { it in Groups.FAMILIES.getValue("PUSH") }) words += "push"
        val pull = groups.filter { it in Groups.FAMILIES.getValue("PULL") }.toSet()
        if (pull.isNotEmpty()) words += when {
            pull.all { it == "rear_delts" } -> "rear delts"
            pull.all { it == "rear_delts" || it == "upper_back" } -> "upper back"
            else -> "pull"
        }
        if (words.isEmpty() && groups.isNotEmpty()) words += "core"
        val text = words.joinToString(" + ").replaceFirstChar { it.uppercaseChar() }
        return if (addon) "$text add-on" else text
    }

    private fun reduce(m: MutableMap<String, Double>, g: String, by: Double) {
        val v = (m[g] ?: 0.0) - by
        if (v <= 1e-9) m.remove(g) else m[g] = v
    }

    private fun infeasible(input: AllocInput, reason: String, a: Map<String, Double>, dropped: List<Dropped>) = Allocation(
        input.type, input.addOn != null, label(emptyMap(), input.addOn != null), false, reason, grid(a), emptyMap(), emptyMap(), emptyMap(),
        emptyMap(), emptyMap(), emptyMap(), 0.0, 0.0, emptyList(), dropped, emptyMap(), null,
    )

    private fun grid(m: Map<String, Double>): Map<String, Double> = m.filterValues { it > 0.0 }.mapValuesTo(TreeMap()) { Grid.ratio(it.value) }

    private fun round6(x: Double): Double = kotlin.math.round(x * 1e6) / 1e6
}
