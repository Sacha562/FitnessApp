package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.eca.AuditRow
import dev.fitnessapp.algo.challenge.eca.ChallengeNotification
import dev.fitnessapp.algo.challenge.eca.Outcome
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEngine
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.eca.TestEval
import dev.fitnessapp.algo.challenge.eca.WriteRecord
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.SortedMap
import java.util.TreeMap

/**
 * `challenge.enrollment@1.0.0` (CHALLENGE_ENGINE 4): the enrollment is `fold(initialState, inputEvents)` in
 * `(occurred_at, id)` order. This object owns the engine bookkeeping around the rule engine: creation and
 * import (D4), proposal decisions, pause / resume / abandon, manual overrides, the test record (`t`, `pt`,
 * validity, derived metrics), the observations of the phase, week logs and `validInBlock` counts.
 */
object EnrollmentFold : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.enrollment")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("challenge.enrollment.absence_prompt_days", "days", 7.0, 28.0),
        ParamSpec("challenge.enrollment.checkpoint_every", ParamType.ENUM, "enum (event type)", enumValues = listOf("block.started")),
        ParamSpec.integer("challenge.enrollment.max_block_weeks", "weeks", 6.0, 10.0),
        ParamSpec.integer("challenge.enrollment.snapshot_cache_blocks", "blocks", 4.0, 50.0),
        ParamSpec.integer("challenge.enrollment.week_close_local_time", "min after local midnight", 0.0, 360.0),
    )

    class Step(
        val state: EnrollmentState,
        val audits: List<AuditRow>,
        val notifications: List<ChallengeNotification>,
        val warnings: List<String>,
    )

    class FoldResult(
        val state: EnrollmentState,
        val audits: List<AuditRow>,
        val notifications: List<ChallengeNotification>,
        val warnings: List<String>,
        /** State after each folded event, for trajectories and checkpoints. */
        val trail: List<Pair<ChallengeEvent, EnrollmentState>>,
    )

    /** Folds an unordered event list (sorted here); amendments and retractions apply at the original's position. */
    fun fold(def: CompiledDefinition, params: ParamView, events: List<ChallengeEvent>): FoldResult {
        val sorted = events.sortedWith(ChallengeEvent.ORDER)
        val created = sorted.firstOrNull { it.type == EventTypes.CREATED } ?: throw IllegalArgumentException("no enrollment.created event")
        val ids = sorted.map { it.id }.toSet()
        val latest = TreeMap<String, ChallengeEvent>()
        for (e in sorted) {
            val target = e.supersedesEventId ?: continue
            var root = target
            // Follow chains of amendments to the original event; a cycle (corrupt data) stops at its first repeat.
            val visited = sortedSetOf<java.util.UUID>()
            while (visited.add(root)) {
                val parent = sorted.firstOrNull { it.id == root }?.supersedesEventId ?: break
                root = parent
            }
            latest[root.toString()] = e
        }
        var step = create(def, params, created)
        val audits = ArrayList(step.audits)
        val notes = ArrayList(step.notifications)
        val warnings = ArrayList(step.warnings)
        val trail = ArrayList<Pair<ChallengeEvent, EnrollmentState>>()
        trail += created to step.state
        for (e in sorted) {
            if (e === created) continue
            if (e.supersedesEventId != null) {
                if (e.supersedesEventId !in ids) warnings += "orphan_amendment:${e.id}"
                continue
            }
            val replacement = latest[e.id.toString()]
            val effective = when {
                replacement == null -> e
                replacement.payload is EventPayload.Pain && (replacement.payload as EventPayload.Pain).retracted -> continue
                else -> e.copy(payload = replacement.payload, engineFacts = replacement.engineFacts ?: e.engineFacts)
            }
            step = apply(def, params, step.state, effective)
            audits += step.audits
            notes += step.notifications
            warnings += step.warnings
            trail += effective to step.state
        }
        return FoldResult(step.state, audits, notes, warnings, trail)
    }

    /**
     * Synthesizes and folds the ticks due up to [now] one by one (4.6), so that a rule that schedules a test
     * on `week.started` gets its `test.due` in the same catch-up. Returns the synthesized events in order.
     */
    fun catchUp(
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        now: Instant,
        zone: ZoneId,
        facts: EngineFacts? = null,
    ): Pair<List<ChallengeEvent>, List<Step>> {
        val closeMin = params.int("challenge.enrollment.week_close_local_time")
        val emitted = sortedSetOf<String>()
        val events = ArrayList<ChallengeEvent>()
        val steps = ArrayList<Step>()
        var s = state
        while (true) {
            val batch = TickSynthesizer.synthesize(s, now, zone, closeMin, facts).events.filter { it.id.toString() !in emitted }
            if (batch.isEmpty()) break
            for (next in batch) {
                emitted += next.id.toString()
                events += next
                val before = s.plannedTest
                val step = apply(def, params, s, next)
                steps += step
                s = step.state
                // A rule moved the planned test: synthesize again so its `test.due` is not missed.
                if (s.plannedTest != before) break
            }
        }
        return events to steps
    }

    private fun factsOf(state: EnrollmentState?, e: ChallengeEvent): EngineFacts = e.engineFacts ?: state?.lastFacts ?: EngineFacts()

    /** `enrollment.created` (4.5): init vars in order, apply the import, take the block-start snapshot. */
    fun create(def: CompiledDefinition, params: ParamView, e: ChallengeEvent): Step {
        val p = e.payload as? EventPayload.Created ?: throw IllegalArgumentException("first event must be enrollment.created")
        val imp = p.import ?: ImportBlock()
        val date = e.localDate
        val warnings = ArrayList<String>()
        val pastValid = imp.pastTests.filter { it.validForGate }.sortedBy { it.date }
        var s = EnrollmentState(
            enrollmentId = p.enrollmentId,
            status = EnrollmentState.ACTIVE,
            mode = imp.mode,
            modeSince = date,
            phase = imp.phase,
            phaseSince = date,
            block = BlockPos(imp.blockIndex, imp.blockWeek, Week.isoWeekStart(date).minusWeeks((imp.blockWeek - 1).toLong())),
            weekOrdinal = imp.weekOrdinal,
            vars = sortedMapOf(),
            snapshots = sortedMapOf(),
            plannedTest = imp.plannedTestDate?.let { PlannedTest(it, if (imp.mode == Modes.TEST_PENDING) "postponed" else "blockRelative") } ?: PlannedTest.NONE,
            lastValidTest = pastValid.lastOrNull()?.let { TestRecord(it.date, null, it.metrics, it.protocolCodes, it.notComparable) },
            validTestCount = pastValid.size,
            proposalDecisions = sortedMapOf(),
            params = TreeMap(p.params),
            pausedAt = null,
            lastTickDate = date.minusDays(1),
            lastWeekBoundary = Week.isoWeekStart(date),
            notificationsSent = sortedSetOf(),
            definitionVersion = p.definitionVersion,
            definitionSha = p.jcsSha256,
            blockValid = sortedMapOf(),
            importBlock = imp.blockIndex,
            importBlockCounts = TreeMap(imp.blockItemCounts),
            weekLogs = sortedMapOf(),
            phaseObservations = sortedMapOf(),
            painLast = sortedMapOf(),
            lastChallengeSessionDate = null,
            lastFacts = e.engineFacts,
            stateRev = 0,
        )
        val records = Records(params)
        val vars = TreeMap<String, Value>()
        for (v in def.stateVars) {
            val env = RuleEnv(def, params, records, s.copy(vars = TreeMap(vars)), date, factsOf(null, e))
            val imported = imp.vars[v.name]
            val value = if (imported != null) {
                imported
            } else {
                try {
                    val roots = LinkedHashMap(env.roots())
                    roots["onboard"] = RecordV("onboard", p.onboard)
                    v.init?.evaluate(roots, env.host) ?: NullV
                } catch (err: EvalError) {
                    warnings += "init_error:${v.name}:${err.code}"
                    NullV
                }
            }
            vars[v.name] = RuleEngine.canonical(v.type, coerce(v.type, value), env)
        }
        s = s.copy(vars = vars)
        if (imp.snapshotCurrentAsBlockStart) {
            val snap = TreeMap<String, Value>()
            for (name in def.snapshotVars) vars[name]?.let { snap[name] = it }
            s = s.copy(snapshots = TreeMap(mapOf(0 to snap, imp.blockIndex to TreeMap(snap))))
        }
        return Step(s, emptyList(), emptyList(), warnings)
    }


    /** Re-tags an `Int` written to a `Num` var and the like; other values pass unchanged. */
    private fun coerce(t: Type, v: Value): Value = if (t is Type.Q && v is QV && v.dim != t.dim && v.dim == Dim.INT) QV(t.dim, v.v) else v

    /** Folds one event onto [s0]. Amendments and retractions change history, so they need [fold] (4.11). */
    fun apply(def: CompiledDefinition, params: ParamView, s0: EnrollmentState, e: ChallengeEvent): Step {
        require(e.supersedesEventId == null) { "${e.type} ${e.id} supersedes an event: refold instead (4.11)" }
        val records = Records(params)
        val facts = factsOf(s0, e)
        val warnings = ArrayList<String>()
        fun env(state: EnrollmentState, ev: ChallengeEvent, session: SessionLog? = null, week: String? = null, test: TestEval? = null) =
            RuleEnv(def, params, records, state, ev.localDate, ev.engineFacts ?: facts, ev, session, week, test)

        fun rules(state: EnrollmentState, session: SessionLog? = null, week: String? = null, test: TestEval? = null): RuleEngine.Result =
            RuleEngine.evaluate(def, params, { st, ev -> env(st, ev, session.takeIf { ev.type == e.type }, week.takeIf { ev.type == e.type }, test.takeIf { ev.type == e.type }) }, e, state)

        var audits: List<AuditRow> = emptyList()
        var notes: List<ChallengeNotification> = emptyList()
        var s = s0
        when (val p = e.payload) {
            is EventPayload.ApprovalChanged -> {
                require(p.decision == "approved" || p.decision == "declined") { "decision ${p.decision}" }
                s = s.copy(proposalDecisions = TreeMap(s.proposalDecisions).also { it[p.proposalId] = Decision(p.decision, e.localDate) })
            }
            // 4.5: pause only from active, resume only from paused; completed and abandoned are final. The rules
            // see the state before the event (5.3), the status changes after them.
            is EventPayload.Pause -> if (s.status != EnrollmentState.ACTIVE) warnings += "ignored:${e.type}:${s.status}" else {
                val r = rules(s)
                s = if (r.state.status == EnrollmentState.ACTIVE) r.state.copy(status = EnrollmentState.PAUSED, pausedAt = e.localDate) else r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
            }
            is EventPayload.Resume -> if (s.status != EnrollmentState.PAUSED) warnings += "ignored:${e.type}:${s.status}" else {
                val r = rules(s)
                s = if (r.state.status == EnrollmentState.PAUSED) r.state.copy(status = EnrollmentState.ACTIVE, pausedAt = null) else r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
            }
            is EventPayload.Abandon -> if (s.status != EnrollmentState.ACTIVE && s.status != EnrollmentState.PAUSED) {
                warnings += "ignored:${e.type}:${s.status}"
            } else {
                s = s.copy(status = EnrollmentState.ABANDONED)
            }
            is EventPayload.ManualOverride -> {
                val (st, row) = override(def, params, s, e, p, env(s, e))
                s = st
                audits = listOfNotNull(row)
            }
            is EventPayload.DayTick -> {
                if (p.date.isAfter(s.lastTickDate)) s = s.copy(lastTickDate = p.date)
                val r = rules(s)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
            }
            is EventPayload.WeekBoundary -> {
                if (e.type == EventTypes.WEEK_STARTED) {
                    val monday = Week.isoWeekStart(e.localDate)
                    s = s.copy(
                        lastWeekBoundary = if (monday.isAfter(s.lastWeekBoundary)) monday else s.lastWeekBoundary,
                        weekOrdinal = if (s.status == EnrollmentState.ACTIVE) s.weekOrdinal + 1 else s.weekOrdinal,
                    )
                }
                val r = rules(s, week = p.isoWeek)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
                if (e.type == EventTypes.WEEK_CLOSED) s = s.copy(weekLogs = TreeMap(s.weekLogs.filterKeys { it > p.isoWeek }))
            }
            is EventPayload.Session -> {
                val log = p.session
                val r = rules(s, session = log)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
                s = afterSession(def, s, log, env(s0, e, log))
            }
            is EventPayload.HabitCompleted -> {
                val r = rules(s)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
                s = addWeekRows(def, s, null, e.localDate, BigDecimal.ONE, p.items)
            }
            is EventPayload.Pain -> {
                val r = rules(s)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
                if (!p.retracted) {
                    val prev = s.painLast[p.bodyRegion]
                    if (prev == null || e.localDate.isAfter(prev)) s = s.copy(painLast = TreeMap(s.painLast).also { it[p.bodyRegion] = e.localDate })
                }
            }
            is EventPayload.TestCompleted -> {
                val test = testEval(def, params, records, s, e, p, facts)
                if (test.valid) s = s.copy(phaseObservations = observe(def, s.phaseObservations, test.t))
                val r = rules(s, test = test)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
                if (test.valid) {
                    val metrics = TreeMap(test.t.fields)
                    s = s.copy(lastValidTest = TestRecord(e.localDate, p.testSessionId, metrics, p.protocolCodes), validTestCount = s.validTestCount + 1)
                }
            }
            is EventPayload.AttemptLogged -> {
                val outcome = if (p.success) BoolV.TRUE else BoolV.FALSE
                val v = SideV(if (p.side == "L" || p.side == "both") outcome else null, if (p.side == "R" || p.side == "both") outcome else null)
                s = s.copy(phaseObservations = observe(def, s.phaseObservations, RecordV("t", mapOf(p.metric to v))))
                val r = rules(s)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
            }
            is EventPayload.TestDue, is EventPayload.Readiness, EventPayload.Empty -> {
                val r = rules(s)
                s = r.state; audits = r.audits; notes = r.notifications; warnings += r.warnings
            }
            is EventPayload.Created -> throw IllegalArgumentException("a second enrollment.created event")
        }
        s = s.copy(stateRev = s0.stateRev + 1, lastFacts = e.engineFacts ?: s0.lastFacts)
        return Step(s, audits, notes, warnings)
    }

    private fun afterSession(def: CompiledDefinition, s: EnrollmentState, log: SessionLog, env: RuleEnv): EnrollmentState {
        var out = addWeekRows(def, s, log.slot, log.localDate, log.completionRatio, log.items)
        if (!log.localDate.isBefore(out.block.startDate)) {
            val counts = TreeMap(out.blockValid)
            for (key in log.items.keys) if (env.valid(key)) counts[key] = (counts[key] ?: 0) + 1
            out = out.copy(blockValid = counts)
        }
        val last = out.lastChallengeSessionDate
        if (last == null || log.localDate.isAfter(last)) out = out.copy(lastChallengeSessionDate = log.localDate)
        return out
    }

    private fun addWeekRows(
        def: CompiledDefinition,
        s: EnrollmentState,
        slot: String?,
        date: LocalDate,
        ratio: BigDecimal,
        items: SortedMap<String, ItemLog>,
    ): EnrollmentState {
        val iso = Week.isoWeek(date)
        val kept = TreeMap<String, List<RowLog>>()
        for (k in def.weekItemKeys) items[k]?.let { kept[k] = it.rows }
        val working = items.values.sumOf { item -> item.rows.count { it.setKind != "warmup" } }
        val logs = TreeMap(s.weekLogs)
        logs[iso] = logs[iso].orEmpty() + WeekSession(slot, date, ratio, working, kept)
        return s.copy(weekLogs = logs)
    }

    /**
     * `m.*`: bool metrics OR per side, numeric metrics keep the latest value (3.7). A side of a bool metric
     * that has no true observation yet reads false, so `m.oa_rep.L || m.oa_rep.R` holds after a right-arm rep.
     */
    private fun observe(def: CompiledDefinition, current: SortedMap<String, Value>, t: RecordV): SortedMap<String, Value> {
        val out = TreeMap(current)
        for ((k, v) in t.fields) {
            val info = def.metrics[k] ?: continue
            val base = (info.type as? Type.SideT)?.elem ?: info.type
            out[k] = if (base == Type.BoolT) mergeBool(out[k], v) else v
        }
        return out
    }

    private fun mergeBool(old: Value?, new: Value): Value {
        fun or(a: Value?, b: Value?): Value = if (a == BoolV.TRUE || b == BoolV.TRUE) BoolV.TRUE else BoolV.FALSE
        return when {
            new is SideV -> {
                val o = old as? SideV
                SideV(or(o?.l, new.l), or(o?.r, new.r))
            }
            else -> or(old, new)
        }
    }

    /** Builds `t` (raw metrics plus derived metrics, on their grids), `pt` and `test.valid` (3.7, 2.4.3). */
    private fun testEval(
        def: CompiledDefinition,
        params: ParamView,
        records: Records,
        s: EnrollmentState,
        e: ChallengeEvent,
        p: EventPayload.TestCompleted,
        facts: EngineFacts,
    ): TestEval {
        val env = RuleEnv(def, params, records, s, e.localDate, facts, e)
        val t = LinkedHashMap<String, Value>()
        for ((k, v) in p.metrics) t[k] = grid(def, k, v)
        val prev = s.lastValidTest
        val pt = LinkedHashMap<String, Value>()
        if (prev != null) {
            for ((k, v) in prev.metrics) {
                if (k in prev.notComparable) continue
                val a = prev.protocolCodes[k]
                val b = p.protocolCodes[k]
                if (a != null && b != null && a != b) continue
                pt[k] = v
            }
        }
        for (m in def.metrics.values) {
            val d = m.derived ?: continue
            if (t.containsKey(m.id)) continue
            try {
                val roots = LinkedHashMap(env.roots())
                roots["t"] = RecordV("t", t)
                roots["pt"] = RecordV("pt", pt)
                t[m.id] = grid(def, m.id, d.evaluate(roots, env.host))
            } catch (_: EvalError) {
                // A derived metric that cannot be computed is absent (`defined(t.x)` is false).
            }
        }
        val tRec = RecordV("t", t)
        val ptRec = RecordV("pt", pt)
        val validity = def.templates[p.templateId]?.validity
        val valid = if (validity == null) {
            true
        } else {
            try {
                val roots = LinkedHashMap(env.roots())
                roots["t"] = tRec
                roots["pt"] = ptRec
                (validity.evaluate(roots, env.host) as? BoolV)?.b == true
            } catch (_: EvalError) {
                false
            }
        }
        return TestEval(tRec, ptRec, valid)
    }

    /** Observations are stored on the canonical grid of their metric type (C42). */
    private fun grid(def: CompiledDefinition, metric: String, v: Value): Value = when (v) {
        is SideV -> SideV(v.l?.let { grid(def, metric, it) }, v.r?.let { grid(def, metric, it) })
        is QV -> QV(
            v.dim,
            Dec.norm(
                when (v.dim) {
                    Dim.MASS -> Grid.decimal(v.v, GridKind.KG)
                    Dim.DURATION -> Grid.decimal(v.v, GridKind.S)
                    Dim.NUM -> Grid.decimal(v.v, GridKind.RATIO)
                    // A pin keeps its machine step (2.5 stays 2.5); the machine table already rounded it.
                    Dim.PIN -> Grid.decimal(v.v, GridKind.KG)
                    else -> v.v.setScale(0, RoundingMode.HALF_EVEN)
                },
            ),
        )
        else -> v
    }

    /** `manual.override` (4.9): type- and bound-checked, rounded, audited as `manual.override` / E. */
    private fun override(
        def: CompiledDefinition,
        params: ParamView,
        s: EnrollmentState,
        e: ChallengeEvent,
        p: EventPayload.ManualOverride,
        env: RuleEnv,
    ): Pair<EnrollmentState, AuditRow?> {
        var out = s
        var old: String? = null
        var new: String? = null
        var error: String? = null
        when (p.target) {
            "phase" -> {
                val v = (p.value as? QV)?.v?.intValueExact()
                if (v == null || kotlin.math.abs(v - s.phase) != 1 || def.doc.phases.none { it.id == v }) error = "phase moves by one" else {
                    old = s.phase.toString(); new = v.toString(); out = s.copy(phase = v, phaseSince = e.localDate, phaseObservations = sortedMapOf())
                }
            }
            "block.week" -> {
                val v = (p.value as? QV)?.v?.intValueExact()
                if (v == null || v < 1 || v > params.int("challenge.enrollment.max_block_weeks")) error = "block week out of range" else {
                    old = s.block.week.toString(); new = v.toString(); out = s.copy(block = s.block.copy(week = v))
                }
            }
            "plannedTest.date" -> {
                val v = (p.value as? DateV)?.d
                if (v == null) error = "a date is required" else {
                    old = s.plannedTest.date?.toString(); new = v.toString(); out = s.copy(plannedTest = s.plannedTest.copy(date = v))
                }
            }
            "mode", "status" -> error = "${p.target} cannot be overridden"
            else -> {
                val info = def.stateVarByName[p.target]
                if (info == null) error = "unknown target ${p.target}" else {
                    val v = RuleEngine.canonical(info.type, coerce(info.type, p.value), env)
                    val ok = when (val t = info.type) {
                        is Type.Q -> v is QV && v.dim == t.dim
                        is Type.EnumT -> v is StrV && v.s in t.values
                        Type.BoolT -> v is BoolV
                        Type.DateT -> v is DateV || (v is NullV && info.spec.nullable)
                        else -> false
                    }
                    val inBounds = v !is QV || ((info.min as? QV)?.let { v.v >= it.v } ?: true) && ((info.max as? QV)?.let { v.v <= it.v } ?: true)
                    if (!ok || !inBounds) error = "value does not fit ${p.target}" else {
                        old = s.vars[p.target]?.let(ValueJson::text); new = ValueJson.text(v)
                        out = s.copy(vars = TreeMap(s.vars).also { it[p.target] = v })
                    }
                }
            }
        }
        val row = AuditRow(
            Ids.fitness("challenge_eval:${e.id}:0:${e.type}"), e.id, e.type, null, 0, "manual.override", "E", null, "not_required",
            "structural", 0, if (error == null) Outcome.APPLIED else Outcome.ERROR, true, error,
            sortedMapOf("reason_code" to p.reasonCode, "target" to p.target),
            if (error == null) listOf(WriteRecord(p.target, old, new ?: "", Outcome.APPLIED)) else emptyList(),
            null, null, StateJson.hash(s), StateJson.hash(out),
        )
        return out to row
    }

    /** Convenience for tests and `:sim`: the values of an enrollment's derived `p.*` map. */
    fun effectiveParams(def: CompiledDefinition, s: EnrollmentState): SortedMap<String, Value> = RuleEnv.effectiveParams(def, s)
}
