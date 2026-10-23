package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.AuditRow
import dev.fitnessapp.algo.challenge.eca.Outcome
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.ItemLog
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.LoadProfile
import dev.fitnessapp.algo.challenge.fitexpr.MachineCalibration
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.UserKFact
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.challenge.materialize.InSessionRuleRunner
import dev.fitnessapp.algo.challenge.materialize.LiveItem
import dev.fitnessapp.algo.challenge.materialize.LogMode
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.PlannedRow
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.challenge.plan.DoneSession
import dev.fitnessapp.algo.challenge.plan.GymAnchor
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TreeMap
import java.util.UUID

/** One simulated week as it looked on Monday after the week boundary. */
data class WeekRecord(
    val iso: String,
    val state: EnrollmentState,
    val sessions: List<String>,
    val fired: TreeMap<String, TreeMap<String, Int>>,
    val notifications: List<String>,
)

data class SimResult(
    val persona: String,
    val approved: Boolean,
    val weeks: List<WeekRecord>,
    val finalState: EnrollmentState,
    val events: List<ChallengeEvent>,
    val audits: List<AuditRow>,
    val violations: List<String>,
)

/**
 * Validation stage 3 (CHALLENGE_ENGINE 2.6): enrolls a persona with the 14.1 import and replays 26 weeks
 * through the real engine: daily tick catch-up, the week planner, the materializer, in-session rules,
 * readiness checks, habits, test days, pain reports and pauses. Global invariants are checked as it goes.
 */
class ChallengeSimulator(
    private val def: CompiledDefinition,
    private val params: ParamView,
    private val catalog: CatalogDoc,
    private val persona: Persona,
    private val approve: Boolean,
    private val weeks: Int = 26,
) {
    val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val enrollmentId: UUID = UUID.fromString("0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b")
    private val createdOn: LocalDate = LocalDate.parse("2026-10-07")
    private val firstMonday: LocalDate = Week.isoWeekStart(createdOn)

    val facts = EngineFacts(
        bwKg = BigDecimal("70.70"),
        userK = sortedMapOf("weighted_pullup" to UserKFact("chinup_weighted", BigDecimal("30.0000"), BigDecimal("0.3000"), null, 0)),
        machines = sortedMapOf("assisted_pullup_machine" to MachineCalibration("lb", BigDecimal(5), BigDecimal.ZERO, null, BigDecimal("6.5"), BigDecimal.ONE, 1)),
        loadProfiles = sortedMapOf(
            "belt" to LoadProfile((0..24).map { BigDecimal("2.5").multiply(BigDecimal(it)) }, 1),
            // A cable stack in 5 lb steps (D40: the Pallof press starts at 25 lb).
            "cable" to LoadProfile((0..44).map { Grid.decimal(BigDecimal(5 * it).multiply(Dec.ofParam(params.double("load.lb_kg"))), GridKind.KG) }, 1),
        ),
        has = listOf(
            "adjustable_bench", "assisted_pullup_machine", "barbell", "cable_station", "chest_supported_row", "dip_belt", "dip_station",
            "dumbbells", "fixed_neutral_handle", "pullup_bar", "rower_erg", "seated_cable_row",
        ),
    )

    private val events = ArrayList<ChallengeEvent>()
    private val audits = ArrayList<AuditRow>()
    private val notes = ArrayList<String>()
    private val violations = ArrayList<String>()
    private val done = ArrayList<Pair<DoneSession, List<String>>>()
    private var state: EnrollmentState
    private var seq = 0
    private var aSessions = 0
    private var wpuSessions = 0
    private var tests = 0

    init {
        val created = event(EventTypes.CREATED, createdOn, LocalTime.NOON, EventPayload.Created(enrollmentId, sortedMapOf(), onboard(), import(), def.version, def.jcsSha256))
        state = EnrollmentFold.create(def, params, created).state
        events += created
        def.doc.proposals.forEachIndexed { i, p ->
            val e = ChallengeEvent(
                Ids.fitness("sim:${persona.id}:decision:${p.id}"), EventTypes.APPROVAL, at(createdOn, LocalTime.NOON).plusMillis(1L + i), createdOn,
                EventPayload.ApprovalChanged(p.id, if (approve) "approved" else "declined"), facts,
            )
            apply(e)
        }
    }

    private fun mass(x: String): Value = Values.mass(x)

    /** The CHALLENGE_ENGINE 14.1 import: phase 1, block 1 week 5, test pending since the 4 Oct postponement. */
    private fun import() = if (persona.testWeekStart) {
        // The setup's test week (UX 14.8): block 1 week 1, test pending, the test on the first Sunday.
        ImportBlock(mode = "test_pending", blockWeek = 1, plannedTestDate = firstMonday.plusDays(6))
    } else {
        imported()
    }

    private fun imported() = ImportBlock(
        phase = 1, blockIndex = 1, blockWeek = 5, mode = "test_pending", weekOrdinal = 6, plannedTestDate = LocalDate.parse("2026-10-11"),
        vars = TreeMap(
            mapOf(
                "e1rmAdded" to mass("39.38"), "e1rmAddedM110" to mass("42.82"), "wpuC" to mass("20"), "wpuA" to mass("22.5"),
                "wpuB" to mass("27.5"), "negB" to mass("47.5"), "negReset" to mass("45"), "pinA" to Values.pin("40"), "pinC" to Values.pin("50"),
                "pinHold90" to Values.pin("35"), "sthS" to Values.duration("6"), "plankS" to Values.duration("20"), "plankLoadKg" to mass("0"),
                "oaHangS" to Values.duration("10"), "kgPerRep" to mass("2.1667"), "needsCleanSession" to BoolV.TRUE,
            ),
        ),
    )

    private fun onboard() = if (persona.testWeekStart) TreeMap() else TreeMap<String, Value>(
        mapOf(
            "bw" to mass("70.7"), "wpu3rmAdded" to mass("32.5"), "bwMaxReps" to Values.int(18), "anchorLoad" to mass("20"),
            "pinA" to Values.pin("40"), "pinC" to Values.pin("50"), "pinHold90" to Values.pin("35"),
        ),
    )

    private fun at(d: LocalDate, t: LocalTime): Instant = Week.atLocal(d, t, zone)

    private fun event(type: String, d: LocalDate, t: LocalTime, payload: EventPayload): ChallengeEvent =
        ChallengeEvent(Ids.fitness("sim:${persona.id}:${seq++}:$type"), type, at(d, t), d, payload, facts)

    private fun apply(e: ChallengeEvent) {
        val before = state
        val step = EnrollmentFold.apply(def, params, before, e)
        state = step.state
        events += e
        audits += step.audits
        notes += step.notifications.map { "${e.localDate} ${it.kind}:${it.msgKey}" }
        checkStep(e, before, step)
    }

    private fun catchUp(now: Instant) {
        val (evs, steps) = EnrollmentFold.catchUp(def, params, state, now, zone, facts)
        var before = state
        evs.zip(steps).forEach { (e, step) ->
            events += e
            audits += step.audits
            notes += step.notifications.map { "${e.localDate} ${it.kind}:${it.msgKey}" }
            checkStep(e, before, step)
            before = step.state
        }
        steps.lastOrNull()?.let { state = it.state }
    }

    /** Invariants per folded event: declared bounds (2.6) and an audit row for every changed var. */
    private fun checkStep(e: ChallengeEvent, before: EnrollmentState, step: EnrollmentFold.Step) {
        val after = step.state
        for (v in def.stateVars) {
            val x = after.vars[v.name] as? QV ?: continue
            (v.min as? QV)?.let { if (x.v < it.v) violations += "${e.localDate} ${v.name} below min" }
            (v.max as? QV)?.let { if (x.v > it.v) violations += "${e.localDate} ${v.name} above max" }
        }
        // D37: after a valid test every load, pin and negative of the block snapshot has a value.
        if (e.type == EventTypes.TEST_COMPLETED && after.validTestCount > before.validTestCount) {
            for (name in def.snapshotVars) if (after.vars[name] == null || after.vars[name] is NullV) violations += "${e.localDate} $name empty after a valid test"
        }
        if (e.type == EventTypes.CREATED || e.type == EventTypes.OVERRIDE) return
        val written = step.audits.flatMap { a -> a.writes.filter { it.outcome == Outcome.APPLIED }.map { it.target } }.toSet()
        for ((k, v) in after.vars) {
            val old = before.vars[k]
            val same = old != null && (old == v || (old is QV && v is QV && old.v.compareTo(v.v) == 0))
            if (!same && "s.$k" !in written) violations += "${e.localDate} ${e.type}: $k changed without an audit row"
        }
    }

    private fun lastGymBefore(weekStart: LocalDate): GymAnchor? = done.lastOrNull { it.first.date.isBefore(weekStart) }?.let { (d, tags) ->
        GymAnchor(d.date, d.interval, tags)
    }

    /** Chess on Monday evenings (the 14.1 calendar), every week of the simulation. */
    private fun busy(weekStart: LocalDate) = listOf(Interval(at(weekStart, LocalTime.of(19, 30)), at(weekStart, LocalTime.of(22, 30))))

    fun run(): SimResult {
        val records = ArrayList<WeekRecord>()
        val end = firstMonday.plusWeeks(weeks.toLong())
        var lastRecordAudit = 0
        var lastRecordNote = 0
        var d = createdOn
        while (d.isBefore(end)) {
            val weekStart = Week.isoWeekStart(d)
            val weekIndex = Week.calendarDays(firstMonday, weekStart).toInt() / 7
            val dayIndex = d.dayOfWeek.value - 1
            val enrollmentDay = Week.calendarDays(createdOn, d).toInt()
            if (persona.appOpen(d)) {
                catchUp(at(d, LocalTime.of(6, 0)))
                if (d.dayOfWeek.value == 1 && d.isAfter(createdOn)) {
                    records += record(Week.isoWeek(d), lastRecordAudit, lastRecordNote)
                    lastRecordAudit = audits.size
                    lastRecordNote = notes.size
                }
                for (ev in persona.dayEvents(weekIndex, dayIndex, d, enrollmentDay)) dayEvent(ev, d)
                sessionsOf(d, weekStart, weekIndex, enrollmentDay)
                habits(d)
            }
            d = d.plusDays(1)
        }
        catchUp(at(end, LocalTime.of(6, 0)))
        records += record(Week.isoWeek(end), lastRecordAudit, lastRecordNote)
        // apply == fold: the incrementally folded state equals a full refold of the event log (4.11).
        val refold = EnrollmentFold.fold(def, params, events).state
        if (StateJson.hash(refold) != StateJson.hash(state)) violations += "incremental state differs from a full refold"
        val ids = events.map { it.id }
        if (ids.size != ids.toSet().size) violations += "duplicate event ids"
        val tickDates = events.filter { it.type == EventTypes.DAY_TICK }.map { it.localDate }
        if (tickDates.size != tickDates.toSet().size) violations += "a date has more than one day.tick"
        return SimResult(persona.id, approve, records, state, events.toList(), audits.toList(), violations.toList())
    }

    private fun record(iso: String, fromAudit: Int, fromNote: Int): WeekRecord {
        val fired = TreeMap<String, TreeMap<String, Int>>()
        for (a in audits.subList(fromAudit, audits.size)) {
            fired.getOrPut(a.ruleId) { TreeMap() }.merge(a.outcome.code, 1, Int::plus)
        }
        val previousWeek = Week.isoWeek(LocalDate.parse(isoMonday(iso)).minusDays(1))
        val sessions = done.filter { Week.isoWeek(it.first.date) == previousWeek }.map { "${it.first.slot} ${it.first.date}" }
        return WeekRecord(iso, state, sessions, fired, notes.subList(fromNote, notes.size).toList())
    }

    private fun isoMonday(iso: String): String {
        var d = firstMonday
        while (Week.isoWeek(d) != iso) d = d.plusDays(7)
        return d.toString()
    }

    private fun dayEvent(ev: DayEvent, d: LocalDate) {
        when (ev) {
            is DayEvent.Pain -> apply(event(EventTypes.PAIN, d, LocalTime.of(8, 0), EventPayload.Pain(ev.region, "R", listOf(ev.symptom), "mild", ev.score)))
            is DayEvent.Pause -> if (state.status == EnrollmentState.ACTIVE) apply(event(EventTypes.PAUSE, d, LocalTime.of(8, 0), EventPayload.Pause(ev.reason)))
            DayEvent.Resume -> if (state.status == EnrollmentState.PAUSED) {
                val gap = Week.calendarDays(state.pausedAt ?: d, d)
                apply(event(EventTypes.RESUME, d, LocalTime.of(8, 0), EventPayload.Resume(null, gap)))
            }
        }
    }

    private fun sessionsOf(d: LocalDate, weekStart: LocalDate, weekIndex: Int, enrollmentDay: Int) {
        if (state.status != EnrollmentState.ACTIVE) return
        val doneThisWeek = done.map { it.first }.filter { !it.date.isBefore(weekStart) && it.date.isBefore(weekStart.plusDays(7)) }
        val input = PlanInput(
            now = at(d, LocalTime.of(6, 0)), zone = zone, state = state, facts = facts, weekStart = weekStart, done = doneThisWeek,
            previousLastGym = lastGymBefore(weekStart), busy = busy(weekStart),
        )
        val plan = WeekPlanner.plan(def, params, input)
        val today = plan.sessions.filter { it.date == d && it.source == "suggested" }
        for (p in today) {
            if (!persona.trains(weekIndex, p.slot, doneThisWeek.size)) {
                // Not done: remember it as skipped so the planner does not offer it again today.
                continue
            }
            for (b in plan.blockedDays) if (b.date == d && b.scope == "all_gym") violations += "$d: ${p.slot} on a blocked day (${b.ruleId})"
            runSession(p.slot, d, p.interval, enrollmentDay)
            done += DoneSession(p.slot, d, p.interval) to p.tags
        }
    }

    private fun runSession(slot: String, d: LocalDate, iv: Interval, enrollmentDay: Int) {
        val score = persona.elbowScore(d, enrollmentDay)
        apply(event(EventTypes.READINESS, d, Week.localTime(iv.start.minus(Duration.ofMinutes(5)), zone), EventPayload.Readiness(score, emptyList())))
        if (state.status != EnrollmentState.ACTIVE) return
        val busyNow = slot == "A" && persona.machineBusy(++aSessions)
        val reasons = if (busyNow) mapOf("oaa" to "occupied") else emptyMap()
        val plan = Materializer.materialize(
            def, params,
            MaterializeInput(slot, d, state, facts, catalog, liveReasons = reasons, bookedMin = Duration.between(iv.start, iv.end).toMinutes().toInt(), changeoverMin = SimResources.CHANGEOVER_MIN),
        )
        checkLoads(plan)
        val result = if (slot == def.doc.structure.block.testSlot) persona.test(++tests) else null
        val log = perform(plan, slot, d, result, reasons)
        val endTime = Week.localTime(iv.end, zone)
        apply(event(EventTypes.SESSION_COMPLETED, d, endTime, EventPayload.Session(log)))
        if (result != null) {
            val metrics = testMetrics(plan, result)
            val codes = TreeMap<String, String>()
            for (m in metrics.keys) def.metrics[m]?.spec?.protocolCode?.let { codes[m] = it }
            apply(event(EventTypes.TEST_COMPLETED, d, endTime.plusSeconds(1), EventPayload.TestCompleted(log.sessionId, plan.templateId, metrics, codes)))
        }
    }

    /** Invariant: every prescribed load is loadable with the persona's equipment (2.6): belt loads, or the cable stack. */
    private fun checkLoads(plan: SessionPlan) {
        val belt = facts.loadProfiles.getValue("belt").achievableKg
        val cable = facts.loadProfiles.getValue("cable").achievableKg
        for (item in plan.items) for (r in item.rows) {
            val external = catalog.exercises.firstOrNull { it.slug == item.catalogSlug }?.loadMode == "external"
            val points = if (external) cable else belt
            r.addedKg?.let { a -> if (points.none { it.compareTo(a) == 0 }) violations += "${plan.date} ${item.key}: +$a kg not loadable" }
            r.pin?.let { p -> if (p.remainder(BigDecimal(5)).signum() != 0 || p.signum() < 0) violations += "${plan.date} ${item.key}: pin $p not on the machine" }
        }
    }

    private fun testMetrics(plan: SessionPlan, r: TestResult): TreeMap<String, Value> {
        val out = TreeMap<String, Value>()
        val metrics = plan.items.filter { it.status == "planned" && it.metric != null }.mapNotNull { it.metric }.toSet()
        fun put(code: String, v: Value) {
            if (code in metrics) out[code] = v
        }
        put("bw", mass("70.7"))
        put("wpu_3rm_added", QV(Dim.MASS, r.rm3Added))
        put("bw_max_reps", Values.int(r.maxReps))
        put("oa_hang_s", SideV(QV(Dim.DURATION, r.hangL), QV(Dim.DURATION, r.hangR)))
        put("oa_top_hold_s", SideV(QV(Dim.DURATION, r.topHold), QV(Dim.DURATION, r.topHold)))
        put("oa_least_assist_pin", SideV(Values.pin(r.leastAssistPin.toPlainString()), Values.pin(r.leastAssistPin.toPlainString())))
        put("oa_neg5_unassisted", SideV(BoolV.of(r.neg5), BoolV.of(r.neg5)))
        put("oa_one_finger_rep", SideV(BoolV.of(r.oneFinger), BoolV.of(r.oneFinger)))
        put("oa_rep", SideV(BoolV.of(r.rep), BoolV.of(r.rep)))
        return out
    }

    /** Logs every planned row as the persona performs it, running the in-session rules after each row. */
    private fun perform(plan: SessionPlan, slot: String, d: LocalDate, test: TestResult?, reasons: Map<String, String>): SessionLog {
        val items = TreeMap<String, ItemLog>()
        var plannedWorking = 0
        var loggedWorking = 0
        if (plan.items.any { it.key == "wpu" && it.status == "planned" }) wpuSessions++
        for (item in plan.items) {
            if (item.status != "planned") {
                items[item.key] = ItemLog(item.status, 0, null, null, emptyList())
                continue
            }
            var live = LiveItem(item, item.rows, emptyList())
            var i = 0
            while (i < live.rows.size) {
                val row = row(item, live.rows[i], slot, test)
                live = live.copy(logged = live.logged + row)
                if (item.inSessionRuleIds.isNotEmpty()) {
                    live = InSessionRuleRunner.onRowLogged(def, params, state, facts, d, slot, live).item
                }
                i++
            }
            val working = live.rows.count { it.setKind != "warmup" }
            plannedWorking += working
            loggedWorking += live.logged.count { it.setKind != "warmup" }
            val first = live.rows.firstOrNull { it.setKind != "warmup" }
            items[item.key] = ItemLog(
                status = if (item.substitutedFrom != null) "substituted" else "done", plannedRows = working,
                targetReps = first?.targetReps, targetHoldS = first?.targetHoldS, rows = live.logged,
                substitutionReasonCode = if (item.substitutedFrom != null) reasons[item.key] else null,
                progressionExcluded = item.progressionExcluded,
            )
        }
        val ratio = if (plannedWorking == 0) BigDecimal.ONE else BigDecimal(loggedWorking).divide(BigDecimal(plannedWorking), 4, RoundingMode.HALF_EVEN).min(BigDecimal.ONE)
        return SessionLog(Ids.fitness("sim:${persona.id}:session:${seq++}"), slot, plan.templateId, plan.kind, d, ratio, false, null, items)
    }

    private fun row(item: PlannedItem, p: PlannedRow, slot: String, test: TestResult?): RowLog {
        val base = RowLog(side = p.side, setKind = p.setKind, setIndex = p.setIndex)
        return when (p.logMode) {
            LogMode.DONE -> base
            LogMode.TEST_RECORD -> testRow(item, base, test)
            LogMode.HOLD_CHIPS -> base.copy(durationS = p.targetHoldS ?: BigDecimal(2), holdReserveS = BigDecimal(5), addedKg = p.addedKg, assistPinNative = p.pin)
            LogMode.ECC_SECONDS -> base.copy(reps = p.targetReps ?: 1, eccSActual = persona.eccSeconds(item.key), holdReserveS = BigDecimal(5), addedKg = p.addedKg)
            LogMode.ATTEMPT_CHECKLIST -> base.copy(reps = 0)
            LogMode.RIR_CHIPS -> {
                val slip = item.key == "wpu" && persona.gripSlip(wpuSessions, p.setIndex)
                val target = p.targetRepsMax ?: p.targetReps ?: 8
                base.copy(
                    // The slip is flagged on a completed set (as GF-CE-16), so the lock is what stops the increase.
                    reps = target, rirReported = BigDecimal(persona.rir(item.key, slot, item.role)), addedKg = p.addedKg,
                    assistPinNative = p.pin, assistKg = p.assistKg, assistOrdinal = p.fingers,
                    flags = if (slip) listOf("grip_slip_before_last") else emptyList(),
                )
            }
        }
    }

    private fun testRow(item: PlannedItem, base: RowLog, t: TestResult?): RowLog {
        t ?: return base
        return when (item.metric) {
            "wpu_3rm_added" -> base.copy(reps = 3, rirReported = BigDecimal.ZERO, addedKg = t.rm3Added)
            "bw_max_reps" -> base.copy(reps = t.maxReps, rirReported = BigDecimal.ZERO)
            "oa_hang_s" -> base.copy(durationS = if (base.side == "L") t.hangL else t.hangR)
            "oa_top_hold_s" -> base.copy(durationS = t.topHold)
            "oa_least_assist_pin" -> base.copy(reps = 1, assistPinNative = t.leastAssistPin)
            "oa_neg5_unassisted" -> base.copy(reps = 1, eccSActual = if (t.neg5) BigDecimal(5) else BigDecimal(3))
            else -> base
        }
    }

    private fun habits(d: LocalDate) {
        if (state.status != EnrollmentState.ACTIVE || !persona.homePlank(d)) return
        if (done.any { it.first.date == d }) return
        val plank = state.vars["plankS"] as? QV ?: return
        val load = (state.vars["plankLoadKg"] as? QV)?.v
        val rows = (1..3).flatMap { i ->
            listOf("L", "R").map { side -> RowLog(side, "working", i, durationS = plank.v, holdReserveS = BigDecimal(5), addedKg = load) }
        }
        val items = TreeMap(mapOf("plank" to ItemLog("done", 6, null, plank.v, rows)))
        apply(event(EventTypes.HABIT_COMPLETED, d, LocalTime.of(17, 30), EventPayload.HabitCompleted("home_plank", items)))
    }
}
