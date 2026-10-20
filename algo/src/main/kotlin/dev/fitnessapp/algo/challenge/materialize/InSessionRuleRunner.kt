package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.InSessionAction
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** One in-session rule outcome on a logged row (6.10); [rowsBefore] lets the logger undo a `suggest`. */
data class RuleApplication(
    val ruleId: String,
    val ruleTag: String,
    val proposalId: String?,
    /** `suggest`, `notify`, `capTarget`, `suppressed_first_writer` or `error`. */
    val kind: String,
    val field: String? = null,
    val from: String? = null,
    val to: String? = null,
    val rowsAffected: List<Int> = emptyList(),
    val msg: String? = null,
    val msgParams: Map<String, String> = emptyMap(),
    val rowsBefore: List<PlannedRow> = emptyList(),
    val evalError: String? = null,
)

/** The live state of one item: its current planned rows, the rows logged so far, and its rule history. */
data class LiveItem(
    val plan: PlannedItem,
    val rows: List<PlannedRow>,
    val logged: List<RowLog>,
    val appliedRules: Set<String> = emptySet(),
    val undoneRules: Set<String> = emptySet(),
)

/**
 * In-session challenge rules (CHALLENGE_ENGINE 6.10, D14, C46): after each logged row or completed side pair,
 * matching rules change the remaining planned rows at once. A `suggest` applies at most once per rule per
 * item and never again after an Undo (C71); `capTarget` and notify-only rules may act after every row; two
 * rules on the same field of the same row apply in rule-id order and the later one is `suppressed_first_writer`.
 * In-session rules never write enrollment state.
 */
object InSessionRuleRunner {
    class Result(val item: LiveItem, val applications: List<RuleApplication>)

    fun onRowLogged(
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        facts: EngineFacts,
        date: LocalDate,
        slot: String,
        item: LiveItem,
    ): Result {
        val records = Records(params)
        val env0 = RuleEnv(def, params, records, state, date, facts)
        val n = item.logged.size
        if (n == 0) return Result(item, emptyList())
        val last = item.logged.last()
        val plannedRow = item.rows.getOrNull(n - 1)
        val rules = def.inSessionRules.filter { it.id in item.plan.inSessionRuleIds && (it.spec.slots.isEmpty() || slot in it.spec.slots) }
            .sortedBy { it.id }
        val pairRows = pairOf(item, n)
        val apps = ArrayList<RuleApplication>()
        var rows = item.rows
        var applied = item.appliedRules
        val fieldsTouched = sortedMapOf<String, String>()
        for (r in rules) {
            val extra: Map<String, Value> = when (r.spec.on) {
                "set.logged" -> mapOf("set" to records.set(last, last.setIndex, plannedRow?.targetReps, plannedRow?.targetHoldS))
                else -> {
                    val pair = pairRows ?: continue
                    val target = item.rows.getOrNull(n - 1)
                    mapOf("pair" to records.pair(pair, last.setIndex, target?.targetReps, target?.targetHoldS))
                }
            }
            val env = RuleEnv(def, params, records, state, date, facts, extra = extra)
            val fired = try {
                (r.condition.evaluate(env.roots(), env.host) as? BoolV)?.b == true
            } catch (e: EvalError) {
                apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "error", evalError = e.code)
                false
            }
            if (!fired) continue
            for (a in r.actions) when (a) {
                is InSessionAction.Notify -> apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "notify", msg = a.msg)
                is InSessionAction.CapTarget -> {
                    val v = try {
                        (a.value.evaluate(env.roots(), env.host) as? QV)?.v?.setScale(0, RoundingMode.HALF_EVEN)?.toInt()
                    } catch (_: EvalError) {
                        null
                    } ?: continue
                    val side = Regex("""nextSet\((\w+)\)""").find(a.scope)?.groupValues?.get(1)
                    val idx = rows.indices.firstOrNull { it >= n && (side == null || rows[it].side == side) } ?: continue
                    val before = rows[idx]
                    val capped = minOf(before.targetReps ?: v, v)
                    if (capped != before.targetReps) {
                        rows = rows.toMutableList().also { it[idx] = before.copy(targetReps = capped) }
                        apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "capTarget", a.field, before.targetReps?.toString(), capped.toString(), listOf(idx), rowsBefore = listOf(before))
                    }
                }
                is InSessionAction.Suggest -> {
                    if (r.id in applied || r.id in item.undoneRules) continue
                    if (fieldsTouched.containsKey(a.field)) {
                        apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "suppressed_first_writer", a.field, msg = fieldsTouched[a.field])
                        continue
                    }
                    val value = try {
                        a.value.evaluate(env.roots(), env.host) as? QV
                    } catch (e: EvalError) {
                        apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "error", evalError = e.code)
                        null
                    } ?: continue
                    val remaining = rows.indices.filter { it >= n }
                    if (remaining.isEmpty()) continue
                    val before = remaining.map { rows[it] }
                    val updated = rows.toMutableList()
                    for (i in remaining) updated[i] = change(env0, updated[i], a, value.v, r.id, facts)
                    val from = fieldText(before.first(), a.field)
                    val to = fieldText(updated[remaining.first()], a.field)
                    rows = updated
                    applied = applied + r.id
                    fieldsTouched[a.field] = r.id
                    apps += RuleApplication(r.id, r.spec.tag, r.spec.proposal, "suggest", a.field, from, to, remaining, a.msg, mapOf("value" to (to ?: "")), before)
                }
            }
        }
        return Result(item.copy(rows = rows, appliedRules = applied), apps)
    }

    /** Undo restores the rows' planned values before the application; the rule does not fire again on the item. */
    fun undo(item: LiveItem, app: RuleApplication): LiveItem {
        val rows = item.rows.toMutableList()
        app.rowsAffected.forEachIndexed { k, i -> if (i < rows.size && k < app.rowsBefore.size) rows[i] = app.rowsBefore[k] }
        return item.copy(rows = rows, undoneRules = item.undoneRules + app.ruleId)
    }

    /** The rows of the pair the last logged row completed, in side order; null when the pair is incomplete. */
    private fun pairOf(item: LiveItem, n: Int): List<RowLog>? {
        val planned = item.rows.getOrNull(n - 1) ?: return null
        val pairIndex = planned.pairIndex ?: return null
        val idx = item.rows.indices.filter { item.rows[it].pairIndex == pairIndex && item.rows[it].setKind == planned.setKind }
        if (idx.any { it >= n }) return null
        return idx.map { item.logged[it] }
    }

    private fun fieldText(r: PlannedRow, field: String): String? = when (field) {
        "assistPin" -> r.pin?.let { ValueJson.text(QV(Dim.PIN, it)) }
        "added" -> r.addedKg?.let { ValueJson.text(QV(Dim.MASS, it)) }
        "reps" -> r.targetReps?.toString()
        "hold_s" -> r.targetHoldS?.toPlainString()
        "fingers" -> r.fingers?.toString()
        else -> null
    }

    private fun change(env: RuleEnv, row: PlannedRow, a: InSessionAction.Suggest, v: BigDecimal, ruleId: String, facts: EngineFacts): PlannedRow {
        fun op(old: BigDecimal?): BigDecimal = if (a.op == "add") (old ?: BigDecimal.ZERO).add(v) else v
        return when (a.field) {
            "assistPin" -> {
                val pin = env.host.roundPin(op(row.pin), a.roundMode ?: "nearest")
                val bw = facts.bwKg
                row.copy(
                    pin = Dec.norm(pin), assistKg = env.host.assistKg(pin),
                    oneArmLoadKg = if (bw != null) env.host.oneArmLoad(pin) else null,
                    systemLoadKg = if (bw != null) env.host.oneArmLoad(pin) else row.systemLoadKg, changedByRule = ruleId,
                )
            }
            "added" -> {
                val added = env.host.roundLoad(op(row.addedKg), a.roundProfile ?: "belt", a.roundMode ?: "nearest")
                val delta = added.subtract(row.addedKg ?: BigDecimal.ZERO)
                row.copy(addedKg = Dec.norm(added), systemLoadKg = row.systemLoadKg?.add(delta), plateBreakdown = null, changedByRule = ruleId)
            }
            "reps" -> row.copy(targetReps = op(row.targetReps?.let { BigDecimal(it) }).setScale(0, RoundingMode.HALF_EVEN).toInt(), changedByRule = ruleId)
            "hold_s" -> row.copy(targetHoldS = op(row.targetHoldS).setScale(1, RoundingMode.HALF_EVEN), changedByRule = ruleId)
            "fingers" -> row.copy(fingers = op(row.fingers?.let { BigDecimal(it) }).setScale(0, RoundingMode.HALF_EVEN).toInt(), changedByRule = ruleId)
            else -> row
        }
    }
}
