package dev.fitnessapp.algo.accounting.setcredit

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** Planned challenge credit of one session plan (one reserved session or one habit day). */
data class PlannedSessionCredit(val slot: String, val templateId: String, val date: LocalDate, val credits: List<SetCreditOut>) {
    val vhyp: Map<String, Double> get() = SetCredit.sessionVhyp(credits)
}

/**
 * Challenge coverage (CHALLENGE_ENGINE 8.2, C10): SetCredit on the materialized rows of the week's challenge sessions
 * and habits, with the item's `creditRir` from the definition as planned RIR; warm-ups never count. Planned holds
 * take the hold factor from their target time (a range uses its midpoint) and a near-max chip when `creditRir` is at
 * most `setcredit.hold_near_max_sir` (3.2). Committed coverage is the planned coverage of the plans not done yet.
 */
object Coverage {
    private val HOLDS = setOf("isometric", "hang")

    /** The definition's `creditRir` of a planned item: the template's item, else the habit's item of the same key. */
    fun creditRir(def: CompiledDefinition, plan: SessionPlan, item: PlannedItem): Int? {
        def.templates[plan.templateId]?.items?.firstOrNull { it.spec.key == item.key }?.let { return it.spec.creditRir ?: it.spec.rirTarget }
        return item.rirTarget
    }

    /** The planned rows of [plan] as SetCredit inputs, at [start] (the reserved session's start). */
    fun plannedSets(def: CompiledDefinition, plan: SessionPlan, catalog: Catalog, start: Instant, params: ParamView): List<CreditSet> {
        val nearMax = params.double("setcredit.hold_near_max_sir")
        val out = ArrayList<CreditSet>()
        for (item in plan.items) {
            if (item.status != "planned") continue
            // Challenge-local exercises (`oap_assisted_machine`, the holds) have their catalog row under the exercise key.
            val slug = listOfNotNull(item.catalogSlug, item.exerciseKey).firstOrNull { catalog.exercise(it) != null } ?: continue
            val ex = catalog.exercise(slug)!!
            val credit = creditRir(def, plan, item)?.toDouble()
            for (row in item.rows) {
                if (row.setKind == "warmup") continue
                val hold = ex.contraction in HOLDS
                val dur = row.durationS?.toDouble()
                    ?: row.targetHoldS?.let { lo -> row.targetHoldMaxS?.let { hi -> (lo.toDouble() + hi.toDouble()) / 2.0 } ?: lo.toDouble() }
                val felt = if (hold) (if (credit != null && credit <= nearMax) "hard" else "ok") else null
                out += CreditSet(
                    id = "${plan.templateId}/${item.key}/${row.side}/${row.setIndex}", sessionId = "planned:${plan.templateId}:${plan.date}",
                    exerciseSlug = slug, side = row.side, setKind = row.setKind, completed = true, ts = start, durationS = dur, felt = felt,
                    plannedRir = if (hold) null else credit,
                )
            }
        }
        return out
    }

    /**
     * Planned credit per plan. [lastDone] gives the novelty history per exercise slug (planned fatigue feeds the
     * readiness projection; coverage itself reads only Vhyp).
     */
    fun planned(
        def: CompiledDefinition,
        plans: List<Pair<SessionPlan, Instant>>,
        catalog: Catalog,
        lastDone: Map<String, LocalDate>,
        zone: ZoneId,
        params: ParamView,
    ): List<PlannedSessionCredit> = plans.map { (plan, start) ->
        PlannedSessionCredit(
            plan.slot, plan.templateId, plan.date,
            plannedSets(def, plan, catalog, start, params).map { s ->
                SetCredit.credit(s, catalog.exercise(s.exerciseSlug)!!, catalog, lastDone[s.exerciseSlug], zone, params)
            },
        )
    }

    /** The contract's `coverage` object (CHALLENGE_ENGINE 8.1): planned, performed, committed (group -> Vhyp) and bySide. */
    fun contractJson(
        planned: List<PlannedSessionCredit>,
        performed: List<SetCreditOut>,
        committed: List<PlannedSessionCredit>,
    ): JsonObject {
        fun obj(m: Map<String, Double>) = buildJsonObject { m.forEach { (g, v) -> put(g, v) } }
        val bySide = TreeMap<String, Pair<Double, Double>>()
        for (g in Groups.CANONICAL) {
            val l = performed.sumOf { it.sideFs[g]?.get("L") ?: 0.0 }
            val r = performed.sumOf { it.sideFs[g]?.get("R") ?: 0.0 }
            if (l != 0.0 || r != 0.0) bySide[g] = Grid.ratio(l) to Grid.ratio(r)
        }
        return buildJsonObject {
            put("planned", obj(SetCredit.sum(planned.map { it.vhyp })))
            put("performed", obj(SetCredit.sum(performed.map { it.vhyp })))
            put("committed", obj(SetCredit.sum(committed.map { it.vhyp })))
            put("bySide", buildJsonObject { bySide.forEach { (g, p) -> put(g, buildJsonObject { put("L", p.first); put("R", p.second) }) } })
        }
    }
}
