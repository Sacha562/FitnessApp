package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.GateCriteria
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A test value not valid for gates (UX 14.2 muted line): the newest one per metric after the last valid test. */
data class NonValidObservation(val metric: String, val date: LocalDate, val value: Value, val reason: String, val extras: Map<String, Value> = emptyMap())

data class GateSide(val side: String, val text: String, val fraction: Double)

data class GateRow(
    val index: Int,
    val title: String,
    /** The engine's evaluation on the test day's bodyweight (C62); null without a valid test. */
    val met: Boolean?,
    val target: String?,
    val sides: List<GateSide>,
    /** The test date of a single-valued metric (its value is on the bar line). */
    val detail: String?,
    val muted: String?,
    /** The D7 extras (x1.10 estimate, 80 % range), shown behind a tap (D35). */
    val more: String? = null,
)

data class GateRows(val header: String, val met: Int, val total: Int, val rows: List<GateRow>)

/**
 * `ui.progress.gateRows` (UX 19.7.3): one row per criterion of the current phase's exit gate, per arm where the
 * metric has sides. Bars run from 0 to the forward threshold at today's bodyweight (C32), the tick is the engine's
 * evaluation on the test day (C62), values from tests not valid for gates never fill a bar and the newest one is
 * shown on a muted line. Display conventions for derived metrics: `<metric>_m110` is the x1.10 rule and
 * `<metric>_lo` / `_hi` the 80 % range (D7), both in [GateRow.more]; a mass metric named `*_added` is shown with a sign.
 */
object UiGateRows {
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun build(
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        facts: EngineFacts,
        bwNow: BigDecimal?,
        nonValid: List<NonValidObservation>,
    ): GateRows {
        val criteria = GateCriteria.criteria(def, params, state, facts)
        val phase = def.doc.phases.firstOrNull { it.id == state.phase }
        val next = def.doc.phases.firstOrNull { it.id == state.phase + 1 }
        val header = if (phase?.onExit == "complete" || next == null) "Goals to finish" else "Goals for phase ${next.id}"
        val test = state.lastValidTest
        val rows = criteria.map { c ->
            val metric = c.metrics.firstOrNull()
            val info = metric?.let { def.metrics[it] }
            val thr = GateCriteria.rhsAt(def, params, state, facts, c.phaseId, c.index, bwNow ?: test?.metrics?.get("bw")?.let { (it as? QV)?.v })
            val value: Value? = when {
                metric == null -> null
                c.source == "anyValidObservation" -> state.phaseObservations[metric]
                test == null || metric in test.notComparable -> null
                else -> test.metrics[metric]
            }
            val sides = when {
                info == null -> emptyList()
                info.sided -> listOf("L", "R").map { side -> side(side, (value as? SideV)?.let { if (side == "L") it.l else it.r }, thr, c.higherIsBetter, c.met, info.type, metric) }
                else -> listOf(side("none", value, thr, c.higherIsBetter, c.met, info.type, metric))
            }
            val title = (c.label?.get("en") ?: "Goal ${c.index + 1}").replace(">=", "≥").replace("<=", "≤")
            val target = thr?.let { t ->
                val bwText = if (c.thresholdSource != null && bwNow != null) " at ${fmt(bwNow)} kg bodyweight" else ""
                "Target ${if (c.higherIsBetter) "≥" else "≤"} ${text(t, metric, precise = true)}$bwText"
            }
            val tested = if (info != null && !info.sided && value != null && test != null && metric != null) test else null
            val detail = tested?.let { "Tested ${DAY.format(it.date)}" }
            val more = ArrayList<String>()
            tested?.let { t ->
                t.metrics["${metric}_m110"]?.let { more += "×1.10 estimate ${text(it, metric)}" }
                val lo = t.metrics["${metric}_lo"]
                val hi = t.metrics["${metric}_hi"]
                if (lo != null && hi != null) more += "80 % range ${text(lo, metric)} to ${text(hi, metric)}"
            }
            val muted = metric?.let { m ->
                nonValid.filter { it.metric == m && (test == null || it.date.isAfter(test.date) || m in test.notComparable) }.maxByOrNull { it.date }?.let { o ->
                    o.extras["${m}_m110"]?.let { more += "${DAY.format(o.date)}: ×1.10 estimate ${text(it, m)}" }
                    "Newer result, not counted (${DAY.format(o.date)}, ${o.reason}): ${text(o.value, m)}"
                }
            }
            GateRow(c.index, title, c.met, target, sides, detail, muted, more.joinToString(" · ").ifEmpty { null })
        }
        return GateRows("$header · ${rows.count { it.met == true }} of ${rows.size} met", rows.count { it.met == true }, rows.size, rows)
    }

    private fun side(side: String, v: Value?, thr: QV?, higher: Boolean, met: Boolean?, type: Type, metric: String?): GateSide {
        val fraction = when {
            met == true -> 1.0
            v == null -> 0.0
            v is BoolV -> if (v.b) 1.0 else 0.0
            v is QV && thr != null && higher && thr.v.signum() > 0 -> (v.v.toDouble() / thr.v.toDouble()).coerceIn(0.0, 1.0)
            v is QV && thr != null && !higher && v.v.signum() > 0 -> (thr.v.toDouble() / v.v.toDouble()).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val label = if (side == "none") "" else "$side "
        val text = when {
            v == null && (type as? Type.SideT)?.elem == Type.BoolT || v == null && type == Type.BoolT -> "${label}not tested"
            v == null -> "${label}no result yet"
            v is BoolV -> label + if (v.b) "done" else "not yet"
            thr != null -> "$label${text(v, metric)} of ${text(thr, metric, precise = true)}"
            else -> label + text(v, metric)
        }
        return GateSide(side, text.trim(), BigDecimal(fraction).setScale(4, RoundingMode.HALF_EVEN).toDouble())
    }

    /**
     * A value for display: kg with one decimal (signed for `*_added`; [precise] keeps the kg grid, for thresholds),
     * seconds, pins with their unit.
     */
    fun text(v: Value, metric: String?, precise: Boolean = false): String = when (v) {
        is QV -> when (v.dim) {
            Dim.MASS -> {
                val n = (if (precise) v.v else v.v.setScale(1, RoundingMode.HALF_EVEN)).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
                (if (metric?.endsWith("_added") == true && v.v.signum() >= 0) "+" else "") + n + " kg"
            }
            Dim.DURATION -> fmt(v.v) + " s"
            Dim.PIN -> fmt(v.v) + " lb"
            Dim.DAYS -> fmt(v.v) + " d"
            else -> fmt(v.v)
        }
        is BoolV -> if (v.b) "yes" else "no"
        is SideV -> "L " + (v.l?.let { text(it, metric) } ?: "–") + " · R " + (v.r?.let { text(it, metric) } ?: "–")
        else -> v.toString()
    }

    private fun fmt(x: BigDecimal): String = x.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
}
