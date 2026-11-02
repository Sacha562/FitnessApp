package dev.fitnessapp.algo.selection.time

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry

/**
 * One planned exercise as TIME-1 reads it (17.2). Exactly one of [reps], [holdS] and [attempts] describes the work
 * of a set; [eccS] marks eccentric-only reps (seconds per rep). A unilateral pair (L then R) counts as one set.
 */
data class TimeItem(
    val key: String,
    val sets: Int,
    val restS: Double,
    val setupS: Double,
    val reps: Double? = null,
    val eccS: Double? = null,
    val holdS: Double? = null,
    val attempts: Int? = null,
    val unilateralPair: Boolean = false,
)

/** The warm-up policy of a block (17.3). */
enum class WarmupKind {
    /** A challenge block with its own warm-up item: its duration is in the block's items. */
    CHALLENGE,

    /** A standalone general session: the general warm-up plus the specific sets of the first compound. */
    GENERAL,

    /** An add-on after a challenge session: specific warm-up sets only (C34). */
    ADDON,
}

/** Where a session budget came from (17.3, F11, C49). */
enum class BudgetSource(val code: String) { CHIP("chip"), CALENDAR("calendar"), DEFAULT("default") }

data class Budget(val minutes: Double, val source: BudgetSource)

/** The add-on capacity after a challenge session (17.6, C49): floored minutes and whether an add-on is offered. */
data class AddOnCapacity(val capacityMin: Int, val offered: Boolean)

/**
 * One exercise for UI-02 trimming (17.7). [tier] 0 is never trimmed (challenge items, prehab, safety work), 1 a
 * compound of an improve group, 2 an exercise whose primary group is a maintain group, 3 an isolation or accessory of
 * an improve group. [preference] is PREF-1's multiplier and [priority] the engine's value of the item; lower values of
 * both are trimmed first.
 */
data class TrimItem(val time: TimeItem, val tier: Int, val preference: Double, val priority: Double)

data class TrimResult(
    val items: List<TrimItem>,
    /** Keys of the exercises removed, in removal order (reason `TIME_SHORT`). */
    val removed: List<String>,
    val seconds: Double,
    /** The tier-0 work alone does not fit: offer "Short version" or "Pick another day". */
    val coreDoesNotFit: Boolean,
    /** The "Short version" of a block that does not fit: tier-0 sets halved (rounded up), loads unchanged. */
    val shortVersion: List<TrimItem>?,
)

/**
 * `time@1.0.0` TIME-1 (ALGORITHMS_PLANNING 17, C19, C34, C49, F11): the only duration model of the app. It predicts
 * how long a block takes, the allocator's minutes from group-level FS before exercises exist (17.5), the add-on
 * capacity after a challenge session (17.6), the UI-02 trimming for a shorter time chip (17.7), and the EWMA that
 * moves the setup, tempo and rest assumptions towards the user's real pace (17.4). Seconds are on the 0.1 grid.
 */
object TimeModel : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("time")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("time.attempt_reset_s", "s per attempt", 5.0, 30.0),
        ParamSpec.integer("time.calib_min_exposures", "exposures", 1.0, 6.0),
        ParamSpec.integer("time.ecc_extra_s", "s per rep", 0.0, 5.0),
        ParamSpec.number("time.ewma_alpha", "dimensionless", 0.1, 0.6),
        ParamSpec.integer("time.general_warmup_s", "s", 0.0, 1200.0),
        ParamSpec.table("time.group_profile", "[reps, rest s, setup s] (by group)", 0.0, 600.0),
        ParamSpec.integer("time.hold_overhead_s", "s per set", 5.0, 30.0),
        ParamSpec.integer("time.min_addon_min", "min", 5.0, 15.0),
        ParamSpec.table("time.min_sets", "sets (by trim tier)", 0.0, 5.0),
        ParamSpec.integer("time.per_set_s", "s per set", 0.0, 20.0),
        ParamSpec.integer("time.side_switch_s", "s", 0.0, 30.0),
        ParamSpec.integer("time.specific_warmup_sets", "sets", 0.0, 4.0),
        ParamSpec.integer("time.tempo_s", "s per rep", 2.0, 6.0),
        ParamSpec.integer("time.transition_s", "s", 20.0, 180.0),
    )

    /** `work(set)` (17.3): one set, or one L-then-R pair. */
    fun work(item: TimeItem, params: ParamView): Double {
        val tempo = params.double("time.tempo_s")
        val perSet = params.double("time.per_set_s")
        val one = when {
            item.attempts != null -> item.attempts * ((item.holdS ?: 0.0) + params.double("time.attempt_reset_s"))
            item.holdS != null -> item.holdS + params.double("time.hold_overhead_s")
            item.eccS != null -> (item.reps ?: 1.0) * (item.eccS + params.double("time.ecc_extra_s")) + perSet
            else -> (item.reps ?: 0.0) * tempo + perSet
        }
        return if (item.unilateralPair) 2.0 * one + params.double("time.side_switch_s") else one
    }

    /** `t(e, s) = setup + s · (work + rest) − rest + TRANSITION_S` (17.3); 0 for an exercise without sets. */
    fun exerciseSeconds(item: TimeItem, params: ParamView): Double {
        if (item.sets <= 0) return 0.0
        return Grid.s(item.setupS + item.sets * (work(item, params) + item.restS) - item.restS + params.double("time.transition_s"))
    }

    /** The specific warm-up sets of the first compound (17.3): `SPECIFIC_WARMUP_SETS × (8 · TEMPO_S + PER_SET_S + 60)`. */
    fun specificWarmupSeconds(params: ParamView): Double =
        params.double("time.specific_warmup_sets") * (8.0 * params.double("time.tempo_s") + params.double("time.per_set_s") + 60.0)

    /** The warm-up of a block (17.3); a challenge block's warm-up is one of its own items. */
    fun warmupSeconds(kind: WarmupKind, params: ParamView): Double = Grid.s(
        when (kind) {
            WarmupKind.CHALLENGE -> 0.0
            WarmupKind.GENERAL -> params.double("time.general_warmup_s") + specificWarmupSeconds(params)
            WarmupKind.ADDON -> specificWarmupSeconds(params)
        },
    )

    /** `T(block) = warm-up + Σ t(e, s_e)` (17.3). */
    fun blockSeconds(items: List<TimeItem>, kind: WarmupKind, params: ParamView): Double =
        Grid.s(warmupSeconds(kind, params) + items.sumOf { exerciseSeconds(it, params) })

    fun minutes(seconds: Double): Double = Grid.s(seconds / 60.0)

    /**
     * The session budget (17.3, F11, C49): a picked time chip, else the calendar event's duration minus the changeover
     * (calendar gym events are training time), else `default_session_min`. The "90+" chip is passed as its maximum.
     */
    fun budget(chipMin: Double?, eventMin: Double?, changeoverMin: Double, defaultSessionMin: Double): Budget = when {
        chipMin != null -> Budget(chipMin, BudgetSource.CHIP)
        eventMin != null -> Budget(maxOf(0.0, eventMin - changeoverMin), BudgetSource.CALENDAR)
        else -> Budget(defaultSessionMin, BudgetSource.DEFAULT)
    }

    /**
     * The allocator's dose estimator (17.5), in seconds: warm-up, then per group `x · (reps · TEMPO_S + PER_SET_S + rest)
     * − n · rest + n · (setup + TRANSITION_S)` with `n = ceil(x / 3)` exercises of about three sets. Groups in canonical order.
     */
    fun estDose(direct: Map<String, Double>, warm: Boolean, params: ParamView): Double {
        val profile = params.tableArrays("time.group_profile")
        val tempo = params.double("time.tempo_s")
        val perSet = params.double("time.per_set_s")
        val transition = params.double("time.transition_s")
        var s = (if (warm) params.double("time.general_warmup_s") else 0.0) + specificWarmupSeconds(params)
        for (g in Groups.CANONICAL) {
            val x = direct[g] ?: 0.0
            if (x <= 0.0) continue
            val (reps, rest, setup) = profile.getValue(g)
            val n = kotlin.math.ceil(x / 3.0 - 1e-9)
            s += x * (reps * tempo + perSet + rest) - n * rest + n * (setup + transition)
        }
        return Grid.s(s)
    }

    /**
     * [estDose] with its params read once, over direct FS in canonical group order: the allocator's time fit calls it
     * for every candidate step. The arithmetic and its order are [estDose]'s, so the results are identical.
     */
    class DoseModel(params: ParamView) {
        private val rows: List<List<Double>?> = params.tableArrays("time.group_profile").let { p -> Groups.CANONICAL.map { p[it] } }
        private val tempo = params.double("time.tempo_s")
        private val perSet = params.double("time.per_set_s")
        private val transition = params.double("time.transition_s")
        private val generalWarmup = params.double("time.general_warmup_s")
        private val specific = specificWarmupSeconds(params)

        fun estDose(direct: DoubleArray, warm: Boolean): Double {
            var s = (if (warm) generalWarmup else 0.0) + specific
            for (i in direct.indices) {
                val x = direct[i]
                if (x <= 0.0) continue
                val (reps, rest, setup) = requireNotNull(rows[i]) { "time.group_profile lacks ${Groups.CANONICAL[i]}" }
                val n = kotlin.math.ceil(x / 3.0 - 1e-9)
                s += x * (reps * tempo + perSet + rest) - n * rest + n * (setup + transition)
            }
            return Grid.s(s)
        }
    }

    /** [estDose] in minutes. */
    fun estDoseMinutes(direct: Map<String, Double>, warm: Boolean, params: ParamView): Double = minutes(estDose(direct, warm, params))

    /**
     * The add-on capacity (17.6, C49; the formula is CHALLENGE_ENGINE 6.9's `addOnCapacityMin`): `max(0, floor(booked −
     * T(challenge block) − buffer))`; no add-on below `time.min_addon_min`.
     */
    fun addOnCapacity(bookedMin: Double, challengeBlockMin: Double, bufferMin: Double, params: ParamView): AddOnCapacity {
        val cap = maxOf(0, kotlin.math.floor(bookedMin - challengeBlockMin - bufferMin + 1e-9).toInt())
        return AddOnCapacity(cap, cap >= params.int("time.min_addon_min"))
    }

    /**
     * UI-02 trimming (17.7) of a whole session to [budgetMin]: while it is over, take the first of the trimmable items
     * by (higher tier, lower preference, lower priority, key) and remove a set down to its minimum, then the item.
     * [mult] is the session transform factor (deload or re-entry), which scales the improve minimum.
     */
    fun trim(items: List<TrimItem>, kind: WarmupKind, budgetMin: Double, mult: Double, params: ParamView): TrimResult {
        val minSets = params.table("time.min_sets")
        fun floorOf(t: TrimItem): Int = when (t.tier) {
            1 -> kotlin.math.ceil(minSets.getValue("improve") * mult - 1e-9).toInt()
            2 -> minSets.getValue("maintain").toInt()
            else -> minSets.getValue("accessory").toInt()
        }
        val budgetS = budgetMin * 60.0
        val cur = items.toMutableList()
        val removed = ArrayList<String>()
        fun total() = blockSeconds(cur.map { it.time }, kind, params)
        var coreDoesNotFit = false
        while (total() > budgetS + 1e-9) {
            val e = cur.filter { it.tier >= 1 }
                .sortedWith(compareByDescending<TrimItem> { it.tier }.thenBy { it.preference }.thenBy { it.priority }.thenBy { it.time.key })
                .firstOrNull()
            if (e == null) {
                coreDoesNotFit = true
                break
            }
            val i = cur.indexOf(e)
            if (e.time.sets > floorOf(e) && e.time.sets > 1) {
                cur[i] = e.copy(time = e.time.copy(sets = e.time.sets - 1))
            } else {
                cur.removeAt(i)
                removed += e.time.key
            }
        }
        val short = if (coreDoesNotFit) {
            cur.map { if (it.tier == 0) it.copy(time = it.time.copy(sets = (it.time.sets + 1) / 2)) else it }
        } else {
            null
        }
        return TrimResult(cur, removed, total(), coreDoesNotFit, short)
    }

    /**
     * Calibration (17.4): the EWMA of [observations] (oldest first) from [prior] with `time.ewma_alpha`; the prior stays
     * until there are `time.calib_min_exposures` observations.
     */
    fun calibrate(prior: Double, observations: List<Double>, params: ParamView): Double {
        if (observations.size < params.int("time.calib_min_exposures")) return prior
        val a = params.double("time.ewma_alpha")
        var v = prior
        for (x in observations) v += a * (x - v)
        return Grid.s(v)
    }
}
