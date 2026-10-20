package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.cds.Scopes
import dev.fitnessapp.algo.challenge.enrollment.ItemLog
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.ListV
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal

/**
 * Runtime records of the 3.7 row scopes (`ex.<key>.*`, `week.ex.<key>.*`, `set.*`, `pair.*`), built from
 * logged rows under the field names of [Scopes.ROW_FIELDS]. Warm-up rows are not part of `ex` (C63: rules
 * read the working rows of the item).
 */
class Records(params: ParamView) {
    /** HOLD-1 chip mapping (`hold.chip_reserve_s`): the chip with the largest reserve not above the value. */
    private val chips: List<Pair<String, BigDecimal>> =
        params.table("hold.chip_reserve_s").entries.map { it.key to BigDecimal(it.value.toLong()) }.sortedByDescending { it.second }

    fun effort(reserve: BigDecimal?): Value {
        reserve ?: return NullV
        return chips.firstOrNull { reserve >= it.second }?.let { StrV(it.first) } ?: StrV(chips.last().first)
    }

    private fun q(dim: Dim, v: BigDecimal?): Value = v?.let { QV(dim, it) } ?: NullV
    private fun i(v: Int?): Value = v?.let { Values.int(it) } ?: NullV

    /** One field of one row. */
    fun field(r: RowLog, name: String): Value = when (name) {
        "reps" -> i(r.reps)
        "rir" -> q(Dim.NUM, r.rirReported)
        "added" -> q(Dim.MASS, r.addedKg)
        "pin" -> q(Dim.PIN, r.assistPinNative)
        "assist" -> q(Dim.MASS, r.assistKg)
        "fingers" -> i(r.assistOrdinal)
        "hold_s" -> q(Dim.DURATION, r.durationS)
        "hold_reserve_s" -> q(Dim.DURATION, r.holdReserveS)
        "ecc_s" -> q(Dim.DURATION, r.eccSActual)
        "effort" -> effort(r.holdReserveS)
        "side" -> StrV(r.side)
        "kind" -> StrV(r.setKind)
        else -> if (name in Scopes.SET_FLAGS) BoolV.of(name in r.flags) else throw IllegalArgumentException("unknown row field $name")
    }

    private fun lists(path: String, rows: List<RowLog>): Map<String, Value> =
        Scopes.ROW_FIELDS.keys.associateWith { f -> ListV(rows.map { field(it, f) }) }.let { LinkedHashMap(it) }

    fun working(rows: List<RowLog>): List<RowLog> = rows.filter { it.setKind != "warmup" }

    /** `ex.<key>` for one item of a session; an absent item has empty lists and zero planned rows. */
    fun item(path: String, log: ItemLog?): RecordV {
        val rows = working(log?.rows ?: emptyList())
        val fields = LinkedHashMap(lists(path, rows))
        fields["plannedRows"] = Values.int(log?.plannedRows ?: 0)
        fields["targetReps"] = log?.targetReps?.let { Values.int(it) } ?: NullV
        fields["targetHold_s"] = log?.targetHoldS?.let { QV(Dim.DURATION, it) } ?: NullV
        fields["L"] = RecordV("$path.L", lists("$path.L", rows.filter { it.side == "L" }))
        fields["R"] = RecordV("$path.R", lists("$path.R", rows.filter { it.side == "R" }))
        return RecordV(path, fields)
    }

    /** `week.ex.<key>`: the week's rows of the item over all sessions and habit occurrences, plus `days`. */
    fun weekItem(path: String, rowsByDate: List<Pair<java.time.LocalDate, List<RowLog>>>): RecordV {
        val rows = rowsByDate.flatMap { working(it.second) }
        val fields = LinkedHashMap(lists(path, rows))
        fields["plannedRows"] = Values.int(0)
        fields["targetReps"] = NullV
        fields["targetHold_s"] = NullV
        fields["L"] = RecordV("$path.L", lists("$path.L", rows.filter { it.side == "L" }))
        fields["R"] = RecordV("$path.R", lists("$path.R", rows.filter { it.side == "R" }))
        fields["days"] = Values.int(rowsByDate.filter { working(it.second).isNotEmpty() }.map { it.first }.distinct().size)
        return RecordV(path, fields)
    }

    /** `set.*` for an in-session `set.logged` rule. */
    fun set(r: RowLog, index: Int, targetReps: Int?, targetHoldS: BigDecimal?): RecordV {
        val fields = LinkedHashMap<String, Value>()
        for (f in Scopes.SET_FIELDS.keys) {
            fields[f] = when (f) {
                "index" -> Values.int(index)
                "targetReps" -> i(targetReps)
                "targetHold_s" -> q(Dim.DURATION, targetHoldS)
                else -> field(r, f)
            }
        }
        return RecordV("set", fields)
    }

    /** `pair.*` for an in-session `pair.logged` rule: the L and R rows as two-element lists. */
    fun pair(rows: List<RowLog>, index: Int, targetReps: Int?, targetHoldS: BigDecimal?): RecordV {
        val fields = LinkedHashMap(lists("pair", rows))
        fields["index"] = Values.int(index)
        fields["targetReps"] = i(targetReps)
        fields["targetHold_s"] = q(Dim.DURATION, targetHoldS)
        return RecordV("pair", fields)
    }
}
