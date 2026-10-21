package dev.fitnessapp.app.ui.testday

import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import java.math.BigDecimal
import java.util.SortedMap
import java.util.TreeMap

/** The card a test-day step shows (UX 14.9), chosen from the item's `record` and metric type. */
enum class StepKind { WEIGH, WARMUP, ATTEMPTS, REPS, HOLD, LADDER, BOOL }

/** One wizard step: one test item, or the warm-up block's items together. [skipped] carries the template's skip reason. */
data class TestStep(val number: Int, val key: String, val kind: StepKind, val items: List<PlannedItem>, val skipped: String?) {
    val item: PlannedItem get() = items.first()
    val metric: String? get() = item.metric
    val sides: List<String> get() = item.rows.map { it.side }.distinct().ifEmpty { listOf("both") }
}

/** A logged row of the test session as the wizard reads it back from `set_logs`. */
data class LoggedRow(
    val id: String,
    val itemKey: String,
    val side: String,
    val setKind: String,
    val reps: Int?,
    val addedKg: BigDecimal?,
    val pin: BigDecimal?,
    val durationS: BigDecimal?,
    val flags: List<String>,
    val completedAt: Long,
) {
    /** "Not clean" and "Failed" are stored as flags (E6), so a row without them is a clean attempt. */
    val clean: Boolean get() = FAILED_FLAG !in flags && NOT_CLEAN_FLAG !in flags

    companion object {
        const val FAILED_FLAG = "failed_rep"
        const val NOT_CLEAN_FLAG = "technique_breakdown"
    }
}

/** The next load a card proposes: [value] in the item's unit, then [points] more stepper points (when the protocol has no step). */
data class NextLoad(val value: BigDecimal?, val points: Int = 0)

/**
 * The test-day wizard's rules that need no Android (UX 14.9, D36): steps in the plan's order, when a side or step is
 * finished, the next load, and the metric values that Submit sends. Values come from logged rows only, so a restarted
 * wizard rebuilds the same state from Room.
 */
object TestDayModel {
    private val RM = Regex("""(\d+)rm""")

    /** The steps of [plan] in its order (C65): excluded items are left out, consecutive warm-up items form one step. */
    fun steps(plan: SessionPlan, metricType: (String) -> String?): List<TestStep> {
        val out = ArrayList<TestStep>()
        var warm = ArrayList<PlannedItem>()
        fun flushWarm() {
            if (warm.isEmpty()) return
            out += TestStep(out.size + 1, WARMUP_KEY, StepKind.WARMUP, warm, null)
            warm = ArrayList()
        }
        for (item in plan.items) {
            if (item.status == "excluded") continue
            if (item.role == "warmup") {
                warm += item
                continue
            }
            flushWarm()
            val type = item.metric?.let(metricType)
            val kind = when {
                item.exerciseKey == null -> StepKind.WEIGH
                item.record == "heaviestClean" -> StepKind.ATTEMPTS
                item.record == "lowestClean" -> StepKind.LADDER
                item.record == "bool" || type == "bool" -> StepKind.BOOL
                type == "duration" -> StepKind.HOLD
                else -> StepKind.REPS
            }
            out += TestStep(out.size + 1, item.key, kind, listOf(item), item.statusReason.takeIf { item.status == "skipped" })
        }
        flushWarm()
        return out
    }

    /** Reps a clean strength attempt needs: the row's target, else the metric's `<n>rm`, else one. */
    fun requiredReps(item: PlannedItem): Int =
        item.rows.firstNotNullOfOrNull { it.targetReps } ?: item.metric?.let { RM.find(it)?.groupValues?.get(1)?.toInt() } ?: 1

    /** Attempts allowed per side: the protocol's maximum, else the row's `attempts`, else none (strength, ladder) or one. */
    fun maxAttempts(step: TestStep): Int? = step.item.steps?.maxAttempts ?: step.item.rows.firstNotNullOfOrNull { it.attempts }
        ?: if (step.kind == StepKind.ATTEMPTS || step.kind == StepKind.LADDER) null else 1

    private fun test(rows: List<LoggedRow>, step: TestStep, side: String): List<LoggedRow> =
        rows.filter { it.itemKey == step.item.key && it.setKind == "test" && (it.side == side || side == "both") }.sortedBy { it.completedAt }

    /** Ramp sets logged as warm-ups of a strength step (D36). */
    fun rampDone(rows: List<LoggedRow>, step: TestStep): List<BigDecimal> =
        rows.filter { it.itemKey == step.item.key && it.setKind == "warmup" }.mapNotNull { it.addedKg }

    /** A side is finished when its attempts are used up, the ladder failed or reached [minPin], or a yes/no attempt succeeded. */
    fun sideDone(step: TestStep, side: String, rows: List<LoggedRow>, minPin: BigDecimal? = null): Boolean {
        val r = test(rows, step, side)
        val max = maxAttempts(step)
        val used = max != null && r.size >= max
        return when (step.kind) {
            StepKind.LADDER -> used || r.any { !it.clean } || (minPin != null && r.lastOrNull()?.let { it.clean && it.pin != null && it.pin <= minPin } == true)
            StepKind.BOOL -> used || r.any { it.clean }
            StepKind.HOLD, StepKind.REPS -> r.size >= (max ?: 1)
            StepKind.ATTEMPTS -> used
            StepKind.WEIGH, StepKind.WARMUP -> false
        }
    }

    /** The side being tested ([TestStep.sides] order, C65), or null once every side is finished. */
    fun currentSide(step: TestStep, rows: List<LoggedRow>, minPin: BigDecimal? = null): String? =
        step.sides.firstOrNull { !sideDone(step, it, rows, minPin) }

    /** Attempts logged on [side] so far. */
    fun attempts(step: TestStep, side: String, rows: List<LoggedRow>): Int = test(rows, step, side).size

    /**
     * The next attempt's load: the protocol's start (D36), else the previous valid test's value; after a clean attempt
     * that load plus the protocol's step (or one stepper point towards harder); after a missed one the same load.
     */
    fun nextLoad(step: TestStep, side: String, rows: List<LoggedRow>, previous: BigDecimal?): NextLoad {
        val r = test(rows, step, side)
        val s = step.item.steps
        val last = r.lastOrNull() ?: return NextLoad(s?.start ?: previous)
        val load = if (step.kind == StepKind.LADDER) last.pin else last.addedKg
        if (!last.clean) return NextLoad(load)
        val harder = if (step.kind == StepKind.LADDER) -1 else 1
        return s?.step?.let { NextLoad(load?.add(it)) } ?: NextLoad(load, harder)
    }

    /** The step's metric value from its rows (the template's `record`); null when nothing usable was logged. */
    fun value(step: TestStep, rows: List<LoggedRow>): Value? {
        fun side(side: String): Value? {
            val r = test(rows, step, side)
            return when (step.kind) {
                StepKind.ATTEMPTS -> r.filter { it.clean && (it.reps ?: 0) >= requiredReps(step.item) }.mapNotNull { it.addedKg }.maxOrNull()?.let { QV(Dim.MASS, it) }
                StepKind.REPS -> r.mapNotNull { it.reps }.maxOrNull()?.let { QV(Dim.INT, BigDecimal(it)) }
                StepKind.HOLD -> r.mapNotNull { it.durationS }.maxOrNull()?.let { QV(Dim.DURATION, it) }
                StepKind.LADDER -> r.filter { it.clean }.mapNotNull { it.pin }.minOrNull()?.let { QV(Dim.PIN, it) }
                StepKind.BOOL -> if (r.isEmpty()) null else BoolV.of(r.any { it.clean })
                StepKind.WEIGH, StepKind.WARMUP -> null
            }
        }
        return if (step.sides == listOf("both") || step.sides == listOf("none")) {
            side(step.sides.first())
        } else {
            val l = side("L")
            val r = side("R")
            if (l == null && r == null) null else SideV(l, r)
        }
    }

    /** Everything Submit sends: the weigh-in and each step's value, by metric code. */
    fun metrics(steps: List<TestStep>, rows: List<LoggedRow>, bwKg: BigDecimal?): SortedMap<String, Value> {
        val out = TreeMap<String, Value>()
        for (s in steps) {
            val m = s.metric ?: continue
            if (s.kind == StepKind.WEIGH) bwKg?.let { out[m] = QV(Dim.MASS, it) } else value(s, rows)?.let { out[m] = it }
        }
        return out
    }

    /** Attempts per `metric|side` for the observations' `attempt_count`. */
    fun attemptCounts(steps: List<TestStep>, rows: List<LoggedRow>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (s in steps) {
            val m = s.metric ?: continue
            if (s.kind == StepKind.WEIGH || s.kind == StepKind.WARMUP) continue
            for (side in s.sides) {
                val n = attempts(s, side, rows)
                if (n > 0) out["$m|${if (side == "both") "none" else side}"] = n
            }
        }
        return out
    }

    /** The step key of the warm-up block (`test_step_completed.step_key`). */
    const val WARMUP_KEY = "warmup"
}
