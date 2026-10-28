package dev.fitnessapp.algo.accounting.setcredit

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.hold.Hold
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * One set as SetCredit reads it (ALGORITHMS_PLANNING 3.2): a logged `set_logs` row, or a planned row with
 * `rirReported = null` and [plannedRir] set ([SetCredit.planned]). [imputedQ] is REASON-1's imputed RIR for a logged
 * set without a chip (`ReasonSemantics.imputeQ`), computed by the caller. [durationS] is the hold time of a timed
 * set; [felt] its effort chip (max, hard, ok, easy).
 */
data class CreditSet(
    val id: String,
    val sessionId: String,
    val exerciseSlug: String,
    val side: String,
    val setKind: String,
    val completed: Boolean,
    val ts: Instant,
    val durationS: Double? = null,
    val felt: String? = null,
    val rirReported: Double? = null,
    val imputedQ: Double? = null,
    val plannedRir: Double? = null,
)

/** SetCredit.v1 output (3.3); every map is keyed in canonical order and holds only non-zero entries. */
data class SetCreditOut(
    val setId: String,
    val qUsed: Double,
    val qSource: String,
    val fs: Map<String, Double>,
    val vhyp: Map<String, Double>,
    val vstr: Map<String, Double>,
    val fat: Map<String, Double>,
    val tissue: Map<String, Double>,
    val sys: Double,
    val hard: Boolean,
    val sideFs: Map<String, Map<String, Double>>,
) {
    companion object {
        fun zero(id: String) = SetCreditOut(id, 0.0, "default", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0.0, false, emptyMap())
    }
}

/** A credited set with its session, for the week account. */
data class CreditedSet(val set: CreditSet, val credit: SetCreditOut)

/** Per-group sums of the done sets of a window. */
data class GroupDone(val fs: Double, val vhyp: Double, val vstr: Double, val fat: Double)

/** The WeekAccount of 3.3 for one window (an ISO week or the rolling 7 days). */
data class WeekAccount(
    val from: Instant,
    val now: Instant,
    val done: Map<String, GroupDone>,
    val committed: Map<String, Double>,
    val plannedGeneral: Map<String, Double>,
    val sessionsTraining: Map<String, Int>,
    val daysSinceTrained: Map<String, Long?>,
    val perSide: Map<String, Map<String, Double>>,
    val asymmetry: Map<String, Double>,
    /** Share of done working sets whose RIR was imputed, planned or defaulted (3.6). */
    val missingRirShare: Double,
)

/**
 * `setcredit@1.0.0` SetCredit.v1 (ALGORITHMS_PLANNING 3, C6, C7, C8, C10, C44): the one accounting function. Every
 * logged or planned set becomes per-group fractional sets (FS), stimulus-weighted volume (Vhyp), strength credit,
 * fatigue, tissue dose and systemic load. Volume bars, targets, readiness and challenge coverage all read it.
 * Accounting uses the reported RIR, never the RIRCAL-corrected one (C44). A fractional `q` (an imputed RIR) indexes
 * the C6 tables at its nearest integer, half up.
 */
object SetCredit : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("setcredit")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("setcredit.ecc_fat", "multiplier", 1.0, 2.0),
        ParamSpec("setcredit.fat_rir", ParamType.NUMBER_ARRAY, "fatigue weight (by RIR 0-4)", 0.3, 2.0),
        ParamSpec.number("setcredit.fat_rir_above", "fatigue weight (RIR >= 5)", 0.3, 2.0),
        ParamSpec.table("setcredit.felt_to_q", "RIR (by hold chip)", 0.0, 5.0),
        ParamSpec("setcredit.grip_w", ParamType.NUMBER_ARRAY, "tissue weight (by grip demand 0-3)", 0.0, 1.0),
        ParamSpec.integer("setcredit.hard_rir_max", "RIR", 3.0, 5.0),
        ParamSpec.number("setcredit.hold_factor_long", "ratio", 0.25, 1.0),
        ParamSpec.number("setcredit.hold_factor_short", "ratio", 0.0, 0.5),
        ParamSpec.integer("setcredit.hold_min_s", "s", 5.0, 30.0),
        ParamSpec.integer("setcredit.hold_near_max_sir", "s", 0.0, 5.0),
        ParamSpec.number("setcredit.hold_sys", "multiplier", 0.0, 1.0),
        ParamSpec.table("setcredit.kind_fat", "multiplier (by contraction)", 0.2, 2.0),
        ParamSpec("setcredit.level_w", ParamType.NUMBER_ARRAY, "tissue weight (by stress level 0-3)", 0.0, 1.0),
        ParamSpec.number("setcredit.min_hyp_coeff", "coeff", 0.25, 0.75),
        ParamSpec.table("setcredit.novelty", "multiplier (by days since last exposure)", 1.0, 2.0),
        ParamSpec.integer("setcredit.q_missing", "RIR", 0.0, 5.0),
        ParamSpec.number("setcredit.side_factor", "sets per side row", 0.5, 0.5),
        ParamSpec("setcredit.stim_rir", ParamType.NUMBER_ARRAY, "stimulus weight (by RIR 0-5)", 0.0, 1.0),
        ParamSpec.number("setcredit.stim_rir_above", "stimulus weight (RIR >= 6)", 0.0, 0.5),
        ParamSpec.integer("setcredit.sys_divisor", "hard sets", 8.0, 25.0),
        ParamSpec.table("setcredit.sys_w", "systemic weight (by systemic level 1-3)", 0.0, 1.5),
        ParamSpec.number("setcredit.trained_min_credit", "Vhyp", 0.5, 2.0),
    )

    private val HOLDS = setOf("isometric", "hang")
    private val SIDES = setOf("L", "R")

    /** `qOf` (3.4): the RIR the set is credited at and where it came from. */
    fun qOf(set: CreditSet, ex: CatalogExercise, params: ParamView): Pair<Double, String> = when {
        ex.contraction in HOLDS -> requireNotNull(params.table("setcredit.felt_to_q")[set.felt ?: "ok"]) { "felt ${set.felt}" } to "felt"
        set.rirReported != null -> set.rirReported.coerceIn(0.0, 5.0) to "reported"
        set.setKind == "test" -> 0.0 to "test"
        set.imputedQ != null -> set.imputedQ to "imputed_reason"
        set.plannedRir != null -> set.plannedRir to "planned"
        else -> params.double("setcredit.q_missing") to "default"
    }

    /** `STIM(q)` of C6: the table for q 0-5, then `stim_rir_above`. */
    fun stim(q: Double, params: ParamView): Double = table(q, params.doubles("setcredit.stim_rir"), params.double("setcredit.stim_rir_above"))

    /** `FAT(q)` of C6: the table for q 0-4, then `fat_rir_above`. */
    fun fat(q: Double, params: ParamView): Double = table(q, params.doubles("setcredit.fat_rir"), params.double("setcredit.fat_rir_above"))

    private fun table(q: Double, t: List<Double>, above: Double): Double {
        val i = Groups.roundInt(maxOf(0.0, q))
        return if (i < t.size) t[i] else above
    }

    /**
     * The novelty factor (3.4, repeated-bout effect): `over_21d` if the exercise was never done or more than 21 local
     * days ago, `d14_21` for 14-21 days, else 1.
     */
    fun novelty(lastDone: LocalDate?, at: LocalDate, params: ParamView): Double {
        val t = params.table("setcredit.novelty")
        val days = lastDone?.let { Week.calendarDays(it, at) }
        return when {
            days == null || days > 21 -> t.getValue("over_21d")
            days >= 14 -> t.getValue("d14_21")
            else -> 1.0
        }
    }

    /** `credit(set, ex, plannedRir, history)` (3.4). [lastDone] is the local date of the exercise's last working set before the set. */
    fun credit(set: CreditSet, ex: CatalogExercise, catalog: Catalog, lastDone: LocalDate?, zone: ZoneId, params: ParamView): SetCreditOut {
        if (!set.completed || set.setKind == "warmup") return SetCreditOut.zero(set.id)
        val (q, src) = qOf(set, ex, params)
        val minHyp = params.double("setcredit.min_hyp_coeff")
        val hardMax = params.double("setcredit.hard_rir_max")
        val unilateralSide = ex.laterality == "unilateral" && set.side in SIDES
        val side = if (unilateralSide) params.double("setcredit.side_factor") else 1.0
        val k = ex.contraction
        val hold = k in HOLDS
        val hf = if (hold) {
            val sir = Hold.chipReserve(set.felt ?: "ok", params).first
            val nearMax = (set.durationS ?: 0.0) >= params.double("setcredit.hold_min_s") && sir <= params.double("setcredit.hold_near_max_sir")
            params.double(if (nearMax) "setcredit.hold_factor_long" else "setcredit.hold_factor_short")
        } else 1.0
        val nov = novelty(lastDone, Week.localDate(set.ts, zone), params)
        val kf = requireNotNull(params.table("setcredit.kind_fat")[k]) { "contraction $k" }
        val ecc = if (k == "dynamic" && ex.eccEmphasis) params.double("setcredit.ecc_fat") else 1.0
        val stimQ = stim(q, params)
        val fatQ = fat(q, params)
        val fs = TreeMap<String, Double>()
        val vhyp = TreeMap<String, Double>()
        val vstr = TreeMap<String, Double>()
        val fat = TreeMap<String, Double>()
        val sideFs = TreeMap<String, Map<String, Double>>()
        for (g in Groups.CANONICAL) {
            val c = catalog.groupCoeff(ex, g)
            if (c == 0.0) continue
            val credited = c >= minHyp && set.setKind != "attempt"
            var f = 0.0
            var v = 0.0
            var s = 0.0
            when (k) {
                "dynamic", "eccentric_only" -> {
                    f = if (credited) c * side else 0.0
                    v = f * stimQ
                    s = if (c == 1.0 && q <= hardMax && set.setKind != "attempt") side else 0.0
                }
                "isometric" -> {
                    f = if (credited) c * side * hf else 0.0
                    v = f
                }
                "hang" -> {
                    f = if (credited && g == "forearms") c * side * hf else 0.0
                    v = f
                }
            }
            put(fs, g, f)
            put(vhyp, g, v)
            put(vstr, g, s)
            put(fat, g, c * side * fatQ * kf * ecc * nov)
            if (unilateralSide && c >= minHyp) sideFs[g] = mapOf(set.side to Grid.ratio(c))
        }
        val tissue = TreeMap<String, Double>()
        val levelW = params.doubles("setcredit.level_w")
        val gripW = params.doubles("setcredit.grip_w")
        for (t in Groups.TISSUES) {
            val w = if (t == "grip") gripW[ex.gripDemand] else levelW[ex.js(t)]
            put(tissue, t, w * side * fatQ * kf * ecc)
        }
        val hard = if (hold) set.felt in setOf("max", "hard", "ok") || set.felt == null else q <= hardMax
        val sysW = requireNotNull(params.table("setcredit.sys_w")[ex.systemic.toString()]) { "systemic ${ex.systemic}" }
        val sys = if (hard) sysW * (if (hold) params.double("setcredit.hold_sys") else 1.0) * side / params.double("setcredit.sys_divisor") else 0.0
        return SetCreditOut(set.id, Grid.ratio(q), src, fs, vhyp, vstr, fat, tissue, Grid.ratio(sys), hard, sideFs)
    }

    private fun put(m: TreeMap<String, Double>, k: String, v: Double) {
        val r = Grid.ratio(v)
        if (r != 0.0) m[k] = r
    }

    /**
     * The week account (3.4 "Weekly aggregation") of the window [from, now]. [history] holds every credited set the
     * app has (any date; only sets with ts <= now count); [committedSessions] the per-session Vhyp of challenge sessions
     * and habits not done yet this ISO week (planned credit, C10); [plannedSessions] the per-session Vhyp of the week
     * plan's remaining general sessions.
     */
    fun account(
        history: List<CreditedSet>,
        committedSessions: List<Map<String, Double>>,
        plannedSessions: List<Map<String, Double>>,
        from: Instant,
        now: Instant,
        zone: ZoneId,
        params: ParamView,
    ): WeekAccount {
        val minCredit = params.double("setcredit.trained_min_credit")
        val past = history.filter { it.set.ts <= now }.sortedWith(compareBy({ it.set.ts }, { it.set.id }))
        val inWindow = past.filter { it.set.ts >= from }
        val done = TreeMap<String, GroupDone>()
        val perSide = TreeMap<String, Map<String, Double>>()
        for (g in Groups.CANONICAL) {
            val fs = inWindow.sumOf { it.credit.fs[g] ?: 0.0 }
            val v = inWindow.sumOf { it.credit.vhyp[g] ?: 0.0 }
            val s = inWindow.sumOf { it.credit.vstr[g] ?: 0.0 }
            val f = inWindow.sumOf { it.credit.fat[g] ?: 0.0 }
            if (fs != 0.0 || v != 0.0 || s != 0.0 || f != 0.0) done[g] = GroupDone(Grid.ratio(fs), Grid.ratio(v), Grid.ratio(s), Grid.ratio(f))
            val l = inWindow.sumOf { it.credit.sideFs[g]?.get("L") ?: 0.0 }
            val r = inWindow.sumOf { it.credit.sideFs[g]?.get("R") ?: 0.0 }
            if (l != 0.0 || r != 0.0) perSide[g] = mapOf("L" to Grid.ratio(l), "R" to Grid.ratio(r))
        }
        val asym = TreeMap<String, Double>()
        perSide.forEach { (g, m) ->
            val l = m.getValue("L")
            val r = m.getValue("R")
            asym[g] = Grid.ratio(kotlin.math.abs(l - r) / maxOf(l, r))
        }
        val bySessionWindow = sessionVhyp(inWindow)
        val bySessionAll = sessionVhyp(past)
        val today = Week.localDate(now, zone)
        val trained = TreeMap<String, Int>()
        val since = TreeMap<String, Long?>()
        for (g in Groups.CANONICAL) {
            val n = (bySessionWindow.values.map { it.second } + committedSessions + plannedSessions).count { (it[g] ?: 0.0) >= minCredit }
            if (n > 0) trained[g] = n
            val last = bySessionAll.entries.filter { (it.value.second[g] ?: 0.0) >= minCredit }.maxOfOrNull { it.value.first }
            since[g] = last?.let { Week.calendarDays(Week.localDate(it, zone), today) }
        }
        val working = inWindow.filter { it.set.completed && it.set.setKind != "warmup" }
        val missing = working.count { it.credit.qSource in setOf("imputed_reason", "planned", "default") }
        return WeekAccount(
            from, now, done, sum(committedSessions), sum(plannedSessions), trained, since, perSide, asym,
            if (working.isEmpty()) 0.0 else Grid.ratio(missing.toDouble() / working.size),
        )
    }

    /** Session id -> (last set time, per-group Vhyp). */
    private fun sessionVhyp(sets: List<CreditedSet>): TreeMap<String, Pair<Instant, Map<String, Double>>> {
        val out = TreeMap<String, Pair<Instant, Map<String, Double>>>()
        sets.groupBy { it.set.sessionId }.toSortedMap().forEach { (sid, ss) ->
            val m = TreeMap<String, Double>()
            for (g in Groups.CANONICAL) {
                val v = ss.sumOf { it.credit.vhyp[g] ?: 0.0 }
                if (v != 0.0) m[g] = Grid.ratio(v)
            }
            out[sid] = ss.maxOf { it.set.ts } to m
        }
        return out
    }

    /** Per-group sum of per-session maps, on the RATIO grid. */
    fun sum(sessions: List<Map<String, Double>>): Map<String, Double> {
        val out = TreeMap<String, Double>()
        for (g in Groups.CANONICAL) {
            val v = sessions.sumOf { it[g] ?: 0.0 }
            if (v != 0.0) out[g] = Grid.ratio(v)
        }
        return out
    }

    /** Per-group Vhyp of one session's credits. */
    fun sessionVhyp(credits: List<SetCreditOut>): Map<String, Double> = sum(credits.map { it.vhyp })
}
