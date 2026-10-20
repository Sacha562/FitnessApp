package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.enrollment.WeekSession
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.EvalHost
import dev.fitnessapp.algo.challenge.fitexpr.FactsMath
import dev.fitnessapp.algo.challenge.fitexpr.ListV
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import java.math.BigDecimal
import java.time.LocalDate
import java.util.SortedMap

/** A test being processed: `t.*`, `pt.*` and its validity (section 3.7). */
class TestEval(val t: RecordV, val pt: RecordV, val valid: Boolean)

/**
 * The frozen evaluation context of one event (5.1, 3.9): the identifier roots of section 3.7 and the
 * engine-exposed functions, all computed from the pre-event state, the event payload and its engine facts.
 */
class RuleEnv(
    val def: CompiledDefinition,
    val params: ParamView,
    val records: Records,
    val state: EnrollmentState,
    val today: LocalDate,
    val facts: EngineFacts,
    val event: ChallengeEvent? = null,
    val session: SessionLog? = null,
    val weekIso: String? = null,
    val test: TestEval? = null,
    /** Extra roots (in-session `set` / `pair`, materialization `item`). */
    val extra: Map<String, Value> = emptyMap(),
) {
    val effectiveParams: SortedMap<String, Value> = effectiveParams(def, state)
    val math = FactsMath(params, facts, def.machineId)

    private val base: Map<String, Value> by lazy {
        val m = LinkedHashMap<String, Value>()
        m["p"] = RecordV("p", effectiveParams)
        m["s"] = RecordV("s", state.vars)
        m["block"] = RecordV(
            "block",
            linkedMapOf("index" to Values.int(state.block.index), "week" to Values.int(state.block.week), "startDate" to DateV(state.block.startDate)),
        )
        m["phase"] = Values.int(state.phase)
        m["mode"] = StrV(state.mode)
        facts.bwKg?.let { m["bw"] = QV(Dim.MASS, it) }
        m["today"] = DateV(today)
        session?.let { s ->
            m["sess"] = RecordV(
                "sess",
                linkedMapOf(
                    "slot" to StrV(s.slot), "templateId" to StrV(s.templateId), "kind" to StrV(s.kind), "date" to DateV(s.localDate),
                    "completionRatio" to QV(Dim.NUM, s.completionRatio),
                    "painReported" to BoolV.of(s.painReported),
                    "durationMin" to (s.durationMin?.let { QV(Dim.NUM, it) } ?: NullV),
                ),
            )
            m["ex"] = RecordV("ex", allItemKeys().associateWith { records.item("ex.$it", s.items[it]) })
        }
        weekIso?.let { iso ->
            val sessions = state.weekLogs[iso].orEmpty()
            val start = isoWeekStart(iso, sessions)
            val fields = linkedMapOf<String, Value>("iso" to StrV(iso), "start" to DateV(start), "ordinal" to Values.int(state.weekOrdinal))
            if (event?.type == "week.closed") {
                fields["ex"] = RecordV("week.ex", def.weekItemKeys.associateWith { k -> records.weekItem("week.ex.$k", sessions.map { it.date to (it.items[k] ?: emptyList()) }) })
            }
            m["week"] = RecordV("week", fields)
        }
        test?.let {
            m["t"] = it.t
            m["pt"] = it.pt
            m["test"] = RecordV("test", mapOf("valid" to BoolV.of(it.valid)))
        }
        if (event?.type == "attempt.logged") m["m"] = RecordV("m", state.phaseObservations)
        event?.let { m["event"] = RecordV("event", eventFields(it)) }
        m.putAll(extra)
        m
    }

    private fun allItemKeys(): List<String> =
        (def.templates.values.flatMap { t -> t.items.map { it.key } } + session?.items?.keys.orEmpty()).distinct().sorted()

    private fun isoWeekStart(iso: String, sessions: List<WeekSession>): LocalDate {
        sessions.firstOrNull()?.let { return Week.isoWeekStart(it.date) }
        val e = event ?: return Week.isoWeekStart(today)
        // A boundary event happens on the Monday after the closed week (or of the started week).
        return if (e.type == "week.closed") Week.isoWeekStart(e.localDate).minusDays(7) else Week.isoWeekStart(e.localDate)
    }

    private fun eventFields(e: ChallengeEvent): Map<String, Value> = when (val p = e.payload) {
        is EventPayload.Pain -> linkedMapOf(
            "painKinds" to ListV(p.symptomCodes.map { StrV(it) }), "location" to StrV(p.bodyRegion), "side" to StrV(p.side),
            "severity" to StrV(p.severity), "painScore" to Values.int(p.painScore),
        )
        is EventPayload.Readiness -> linkedMapOf(
            "elbowDiscomfort" to Values.int(p.elbowScore), "painKinds" to ListV(p.symptomCodes.map { StrV(it) }),
        )
        is EventPayload.Resume -> linkedMapOf("gapDays" to Values.days(p.gapDays), "reason" to (p.reasonCode?.let { StrV(it) } ?: NullV))
        is EventPayload.Pause -> linkedMapOf("reason" to (p.reasonCode?.let { StrV(it) } ?: NullV))
        is EventPayload.Abandon -> linkedMapOf("reason" to (p.reasonCode?.let { StrV(it) } ?: NullV))
        is EventPayload.AttemptLogged -> linkedMapOf("metric" to StrV(p.metric), "side" to StrV(p.side), "success" to BoolV.of(p.success))
        else -> emptyMap()
    }

    /** Derived facts of section 2.4.3 for this event, evaluated on the pre-state; an error leaves the fact missing. */
    val derived: Map<String, Value> by lazy {
        val out = LinkedHashMap<String, Value>()
        val type = event?.type ?: return@lazy out
        for (d in def.derived) {
            if (type !in d.spec.events) continue
            try {
                out[d.name] = d.expr.evaluate(base + ("d" to RecordV("d", emptyMap())), host)
            } catch (_: EvalError) {
                // missing: rules that read d.<name> see `missing:d.<name>`
            }
        }
        out
    }

    fun roots(lets: Map<String, Value> = emptyMap()): Map<String, Value> {
        val m = LinkedHashMap(base)
        m["d"] = RecordV("d", derived)
        m.putAll(lets)
        return m
    }

    /** `valid(itemKey)` of section 6.11 for the event's session. */
    fun valid(key: String): Boolean {
        val s = session ?: throw EvalError.missing("fn:valid")
        val item = s.items[key] ?: return false
        if (item.status == "skipped" || item.status == "excluded") return false
        if (records.working(item.rows).isEmpty()) return false
        if (item.progressionExcluded) return false
        if (s.painReported) return false
        val reasons = item.rows.mapNotNull { it.deviationReasonCode } + listOfNotNull(item.statusReasonCode, item.substitutionReasonCode)
        return reasons.none { it in INVALIDATING_REASONS }
    }

    fun reasonOn(key: String): String? {
        val s = session ?: throw EvalError.missing("fn:reasonOn")
        val item = s.items[key] ?: return null
        return item.rows.lastOrNull { it.deviationReasonCode != null }?.deviationReasonCode ?: item.statusReasonCode ?: item.substitutionReasonCode
    }

    /** Required slots of the closing week with a qualifying session (`sessionsDone`, 3.8). */
    fun sessionsDone(minRatio: BigDecimal): Int {
        val iso = weekIso ?: throw EvalError.missing("fn:sessionsDone")
        val required = requiredSlots(state.mode).toSet()
        return state.weekLogs[iso].orEmpty().filter { s ->
            val slot = s.slot ?: return@filter false
            slot in required && (if (minRatio.signum() == 0) s.loggedWorkingRows >= 1 else s.completionRatio >= minRatio)
        }.mapNotNull { it.slot }.distinct().size
    }

    fun requiredSlots(mode: String): List<String> {
        val base = def.doc.schedule.slotsPerWeek[mode].orEmpty()
        val alias = def.doc.schedule.testDay.postponedSlotBecomes
        return if (def.doc.structure.block.testSlot in base) (base + alias).distinct() else base
    }

    fun daysSincePain(location: String): Long {
        val dates = listOfNotNull(state.painLast[location], facts.painLastDate[location])
        val last = dates.maxOrNull() ?: return NEVER_DAYS
        return Week.calendarDays(last, today)
    }

    fun nextFixedTestDate(from: LocalDate): LocalDate? {
        val list = (effectiveParams["fixedTestDates"] as? ListV)?.items?.mapNotNull { (it as? DateV)?.d } ?: throw EvalError.missing("p.fixedTestDates")
        val gap = (effectiveParams["fixedDateMinGapDays"] as? QV)?.v?.toLong() ?: throw EvalError.missing("p.fixedDateMinGapDays")
        val lastTest = state.lastValidTest?.date
        return list.sorted().firstOrNull { d -> !d.isBefore(from) && (lastTest == null || Week.calendarDays(lastTest, d) >= gap) }
    }

    fun gatePasses(phase: Int): Boolean {
        val gate = def.gates[phase] ?: return false
        val roots = LinkedHashMap(base)
        if (gate.source == "anyValidObservation") {
            roots["m"] = RecordV("m", state.phaseObservations)
        } else {
            val t = test ?: return false
            if (!t.valid) return false
        }
        return gate.criteria.all { c ->
            try {
                (c.evaluate(roots, host) as? BoolV)?.b == true
            } catch (_: EvalError) {
                false
            }
        }
    }

    val host: EvalHost = object : EvalHost {
        override fun today(): LocalDate = this@RuleEnv.today
        override fun assistKg(pin: BigDecimal) = math.assistKg(pin)
        override fun oneArmLoad(pin: BigDecimal) = math.oneArmLoad(pin)
        override fun lbKg() = math.lbKg()
        override fun roundLoad(m: BigDecimal, profile: String, mode: String) = math.roundLoad(m, profile, mode)
        override fun roundPin(x: BigDecimal, mode: String) = math.roundPin(x, mode)
        override fun userK(exerciseKey: String) = math.userK(exerciseKey)
        override fun userKQuantile(exerciseKey: String, q: BigDecimal) = math.userKQuantile(exerciseKey, q)
        override fun has(equipmentId: String): Boolean = equipmentId in facts.has
        override fun todayHas(slot: String): Boolean = facts.weekPlanSlots[this@RuleEnv.today]?.contains(slot) == true
        override fun valid(itemKey: String): Boolean = this@RuleEnv.valid(itemKey)
        override fun reasonOn(itemKey: String): String? = this@RuleEnv.reasonOn(itemKey)
        override fun validInBlock(itemKey: String): Int {
            val own = state.blockValid[itemKey] ?: 0
            val imported = if (state.importBlock == state.block.index) state.importBlockCounts[itemKey] ?: 0 else 0
            return own + imported
        }
        override fun sessionsDone(minRatio: BigDecimal): Int = this@RuleEnv.sessionsDone(minRatio)
        override fun daysSincePain(location: String): Long = this@RuleEnv.daysSincePain(location)
        override fun nextFixedTestDate(from: LocalDate): LocalDate? = this@RuleEnv.nextFixedTestDate(from)
        override fun phaseGatePasses(phase: Int): Boolean = gatePasses(phase)
    }

    companion object {
        /** `daysSincePain` when no pain was ever reported at the region (section 3.8: `9999d`). */
        const val NEVER_DAYS: Long = 9999

        /** Reasons that make an item invalid for progression (6.11; DATA_MODEL 9.1.3 codes, C57). */
        val INVALIDATING_REASONS: Set<String> = setOf("feeling_bad", "too_tired", "no_time", "occupied", "equipment_missing", "pain")

        /** Definition default -> enrollment params -> proposal decisions (2.4.1). */
        fun effectiveParams(def: CompiledDefinition, state: EnrollmentState): SortedMap<String, Value> {
            val out = sortedMapOf<String, Value>()
            for ((k, p) in def.params) out[k] = p.default
            for ((k, v) in state.params) if (def.params.containsKey(k)) out[k] = v
            for (p in def.doc.proposals) out["approved_${p.id}"] = BoolV.of(state.approved(p.id))
            return out
        }

        fun frozen(mode: String): Boolean = Modes.frozen(mode)
    }
}
