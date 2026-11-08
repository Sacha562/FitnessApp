package dev.fitnessapp.app.ui.testday

import dev.fitnessapp.app.platform.AppClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.uidet.UiGateRows
import dev.fitnessapp.app.uidet.UiPrefill
import dev.fitnessapp.data.challenge.TestOutcome
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.repo.LogSetCommand
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class Phase { LOADING, OVERVIEW, STEP, REVIEW, REPORT }

/** The validity line at the start (UX 14.9 step 0): counts for the goals, or why not. */
data class ValidityUi(val valid: Boolean, val reason: String?, val reasonCode: String?)

/** One line of the review or the report: label, value and an optional muted note (a delta, "was"). */
data class LineUi(val label: String, val value: String, val note: String? = null)

data class ReviewUi(val lines: List<LineUi>, val valid: Boolean, val reason: String?, val goals: String?)

data class ReportUi(
    val headline: String,
    val reason: String?,
    val estimate: LineUi?,
    /** The D7 extras (x1.10 estimate, 80 % range) behind a folded row (D35). */
    val estimateMore: String?,
    val loads: List<LineUi>,
    val goals: String?,
    val nextTest: String?,
    val messages: List<String>,
)

/** The draft of the card on screen; the stored rows are the truth, this is only what the steppers show. */
data class Draft(val kg: BigDecimal? = null, val reps: Int? = null, val holdS: Double? = null, val loadIndex: Int? = null)

data class TestDayUi(
    val phase: Phase = Phase.LOADING,
    /** The test session once Start created it. */
    val sessionId: String? = null,
    val title: String = "Test day",
    val validity: ValidityUi? = null,
    val steps: List<TestStep> = emptyList(),
    val done: Set<Int> = emptySet(),
    val current: TestStep? = null,
    val side: String? = null,
    val rows: List<LoggedRow> = emptyList(),
    val points: List<LoadPoint> = emptyList(),
    val mode: LoadMode? = null,
    val draft: Draft = Draft(),
    val bwKg: BigDecimal? = null,
    val previous: Map<String, Value> = emptyMap(),
    val rest: RestTimerEntity? = null,
    val review: ReviewUi? = null,
    val report: ReportUi? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val elbowCheck: Boolean = false,
)

/**
 * The test-day wizard (UX 14.9, D35, D36): the overview with the validity line, one card per step in the template's
 * order, the review against the previous valid test, Submit, and the recalculation report. Room is the truth: the
 * current step and side are rebuilt from the session's rows, so the wizard survives a restart like a live session.
 */
class TestDayViewModel(private val c: AppContainer, private val enrollmentId: String, startSessionId: String?) : ViewModel() {
    private val _ui = MutableStateFlow(TestDayUi())
    val ui: StateFlow<TestDayUi> = _ui
    private val params get() = c.params.fork()

    @Volatile var sessionId: String? = startSessionId
        private set
    private var plan: SessionPlan? = null
    private var exercises: List<SessionExerciseEntity> = emptyList()
    private var weighInId: String? = null
    private var stepShownAt = AppClock.nowMs()
    private var shownStep: Int? = null

    init {
        viewModelScope.launch {
            val sid = sessionId
            if (sid == null) overview() else attach(sid)
        }
    }

    // ------------------------------------------------------------------------------------------------ overview

    private suspend fun overview() {
        val p = withContext(Dispatchers.Default) { runCatching { c.testDays.plan(enrollmentId) }.getOrNull() } ?: run {
            _ui.update { it.copy(phase = Phase.OVERVIEW, notice = "The test day could not be planned.") }
            return
        }
        plan = p
        val f = c.challenges.folded(enrollmentId)
        val v = validity(f.state, p)
        _ui.update {
            it.copy(
                phase = Phase.OVERVIEW, title = p.templateTitle(f.def), validity = v, steps = steps(p),
                previous = f.state.testBaseline.mapValues { it.value.value },
            )
        }
        c.fgsEvent("test_validity_shown", mapOf("valid" to v.valid, "reason_code" to v.reasonCode))
    }

    private fun SessionPlan.templateTitle(def: dev.fitnessapp.algo.challenge.cds.CompiledDefinition) =
        def.templates[templateId]?.name?.get("en") ?: "Test day"

    /** Metric code -> definition type (`mass`, `duration`, `bool`, ...), read when the enrollment is loaded. */
    private var metricTypes: Map<String, String> = emptyMap()

    private fun steps(p: SessionPlan): List<TestStep> = TestDayModel.steps(p) { metricTypes[it] }

    /** The engine's dry run of a test with today's bodyweight (C62); the reason names what is missing (P15: no codes). */
    private suspend fun validity(state: EnrollmentState, p: SessionPlan): ValidityUi {
        val def = c.challenges.folded(enrollmentId).def
        metricTypes = def.metrics.mapValues { it.value.spec.type }
        val bw = c.challengeFacts.facts(def, state).bwKg ?: FALLBACK_BW
        lastBw = bw
        val sid = sessionId ?: java.util.UUID.randomUUID().toString()
        val out = runCatching { c.testDays.preview(enrollmentId, sid, p.templateId, sortedMapOf("bw" to QV(dev.fitnessapp.algo.challenge.fitexpr.Dim.MASS, bw))) }.getOrNull()
        if (out == null || out.valid) return ValidityUi(true, null, null)
        val (text, code) = invalidReason(state)
        return ValidityUi(false, text, code)
    }

    /** Start (after the elbow check when the template asks for it): the test session is created now. */
    fun start() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            val f = c.challenges.folded(enrollmentId)
            val p = plan ?: return@launch
            val needsElbow = "elbow" in Materializer.templateFor(f.def, f.state, p.slot)?.requiresReadiness.orEmpty() &&
                c.db.wellness().countKindOn("elbow_check", LocalDate.now(ZONE).toString()) == 0
            if (needsElbow) _ui.update { it.copy(elbowCheck = true) } else launchSession()
        }
    }

    fun submitElbow(score: Int, symptoms: List<String>) {
        _ui.update { it.copy(elbowCheck = false) }
        viewModelScope.launch {
            val out = runCatching { c.challengeSessions.elbowCheck(enrollmentId, score, symptoms) }.getOrNull()
            out?.notifications?.firstOrNull { it.kind == "stop" || it.kind == "warn" }?.let { n -> _ui.update { it.copy(notice = ChallengeTexts.message(out.folded.def, n)) } }
            launchSession()
        }
    }

    fun cancelElbow() = _ui.update { it.copy(elbowCheck = false) }

    private suspend fun launchSession() {
        _ui.update { it.copy(busy = true) }
        val id = runCatching { withContext(Dispatchers.Default) { c.testDays.start(enrollmentId) } }
            .onFailure { e -> _ui.update { it.copy(notice = e.message ?: "The test could not start.") } }.getOrNull()
        _ui.update { it.copy(busy = false) }
        if (id != null) {
            SessionService.start(c.appContext)
            sessionId = id
            attach(id)
        }
    }

    // ------------------------------------------------------------------------------------------------ steps

    private suspend fun attach(sid: String) {
        val live = c.challengeSessions.live(sid)
        val f = c.challenges.folded(enrollmentId)
        metricTypes = f.def.metrics.mapValues { it.value.spec.type }
        lastBw = c.challengeFacts.facts(f.def, f.state).bwKg
        sessionBw = c.sessionRepository.session(sid)?.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
        _ui.update { it.copy(sessionId = sid, previous = f.state.testBaseline.mapValues { it.value.value }, title = live?.plan?.templateTitle(f.def) ?: it.title) }
        viewModelScope.launch {
            combine(
                c.challengeSessions.observeLive(sid),
                c.sessionRepository.observeExercises(sid),
                c.sessionRepository.observeSets(sid),
                c.testDays.observeResults(sid),
                c.restTimer.observe(sid),
            ) { l, exs, sets, obs, rest -> Snap(l?.plan, exs, sets, obs, rest) }
                .combine(c.testDays.observeFinishedSteps(sid)) { s, keys -> s.also { it.finished = keys } }
                .collect { rebuild(it) }
        }
    }

    private class Snap(
        val plan: SessionPlan?,
        val exs: List<SessionExerciseEntity>,
        val sets: List<SetLogEntity>,
        val obs: List<dev.fitnessapp.data.db.entity.MetricObservationEntity>,
        val rest: RestTimerEntity?,
    ) {
        var finished: Set<String> = emptySet()
    }

    private suspend fun rebuild(s: Snap) {
        val p = s.plan ?: return
        if (_ui.value.phase == Phase.REPORT) return
        plan = p
        exercises = s.exs
        val steps = steps(p)
        val keyOf = s.exs.associate { it.id to (it.challenge_item_key ?: "") }
        val rows = s.sets.filter { it.deleted_at == null }.map { r ->
            LoggedRow(
                r.id, keyOf[r.session_exercise_id] ?: "", r.side, r.set_kind, r.reps, r.added_kg?.let { Scaled.toDecimal(it, 2) },
                r.assist_pin_native?.let { Scaled.toDecimal(it, 2) }, r.duration_s?.let { Scaled.toDecimal(it, 1) }, r.flags, r.completed_at,
            )
        }
        val bw = s.obs.firstOrNull { it.metric_code == "bw" }
        weighInId = bw?.id
        val bwKg = bw?.value_num?.let { Scaled.toDecimal(it, 3) }
        val done = steps.filter { finished(it, s, bwKg) }.map { it.number }.toSet()
        val current = steps.firstOrNull { it.number !in done }
        val phase = if (current == null) Phase.REVIEW else Phase.STEP
        val min = minPin(current)
        val side = current?.let { TestDayModel.currentSide(it, rows, min) }
        val stepChanged = current?.number != shownStep || side != _ui.value.side
        val (points, mode) = if (current != null && stepChanged) loadables(current) else _ui.value.points to _ui.value.mode
        _ui.update { u ->
            u.copy(
                phase = phase, steps = steps, done = done, current = current, side = side, rows = rows, bwKg = bwKg,
                rest = s.rest?.takeIf { it.state != "ended" }, points = points, mode = mode,
                draft = if (stepChanged || u.rows.size != rows.size) draftFor(current, side, rows, points, bwKg) else u.draft,
            )
        }
        if (current?.number != shownStep) {
            shownStep = current?.number
            stepShownAt = AppClock.nowMs()
        }
        if (phase == Phase.REVIEW && _ui.value.review == null) review()
    }

    /** A step is finished by Next or Skip (its `test_step_completed`), the weigh-in by its observation, a skipped item by the template. */
    private fun finished(step: TestStep, s: Snap, bwKg: BigDecimal?): Boolean = when {
        step.kind == StepKind.WEIGH -> bwKg != null
        step.key in s.finished -> true
        else -> step.items.all { item -> s.exs.firstOrNull { it.challenge_item_key == item.key }?.status.let { it == null || it == "skipped" } }
    }

    private suspend fun loadables(step: TestStep): Pair<List<LoadPoint>, LoadMode?> {
        val mode = when (step.kind) {
            StepKind.ATTEMPTS -> LoadMode.BODYWEIGHT_PLUS_EXTERNAL
            StepKind.LADDER -> LoadMode.ASSISTED_MACHINE
            else -> return emptyList<LoadPoint>() to null
        }
        val se = exercises.firstOrNull { it.challenge_item_key == step.item.key } ?: return emptyList<LoadPoint>() to mode
        val ex = c.catalog.byId(se.exercise_id) ?: return emptyList<LoadPoint>() to mode
        return runCatching { c.equipment.loadables(ex, mode, sessionBw, params).second }.getOrDefault(emptyList()) to mode
    }

    private fun minPin(step: TestStep?): BigDecimal? =
        if (step?.kind == StepKind.LADDER) _ui.value.points.mapNotNull { it.assistPinNative }.minOrNull()?.let { BigDecimal(it) } else null

    /** The card's prefill: the protocol's next load or the previous valid test's value, snapped to what can be loaded. */
    private fun draftFor(step: TestStep?, side: String?, rows: List<LoggedRow>, points: List<LoadPoint>, bwKg: BigDecimal?): Draft {
        step ?: return Draft()
        val prev = previousValue(step, side)
        return when (step.kind) {
            StepKind.WEIGH -> Draft(kg = bwKg ?: _ui.value.bwKg ?: lastBw ?: FALLBACK_BW)
            StepKind.REPS -> Draft(reps = rows.lastOrNull { it.itemKey == step.item.key }?.reps ?: prev?.toInt() ?: DEFAULT_REPS)
            StepKind.HOLD -> Draft(holdS = rows.lastOrNull { it.itemKey == step.item.key && it.side == side }?.durationS?.toDouble() ?: prev?.toDouble() ?: DEFAULT_HOLD_S)
            StepKind.ATTEMPTS, StepKind.LADDER -> {
                val next = TestDayModel.nextLoad(step, side ?: "both", rows, prev)
                val native = { p: LoadPoint -> if (step.kind == StepKind.LADDER) p.assistPinNative else p.addedKg }
                // Points run from the lightest load (most help on the machine) up, so "harder" is a higher index for both kinds;
                // without a start the strength attempts begin at the lightest load and the ladder at the most help.
                val base = next.value?.let { v -> points.indices.minByOrNull { i -> native(points[i])?.let { kotlin.math.abs(it - v.toDouble()) } ?: Double.MAX_VALUE } }
                    ?: points.indices.firstOrNull()
                val idx = base?.let { b -> (b + next.points * if (step.kind == StepKind.LADDER) -1 else 1).coerceIn(0, (points.size - 1).coerceAtLeast(0)) }
                Draft(loadIndex = idx)
            }
            StepKind.BOOL, StepKind.WARMUP -> Draft()
        }
    }

    private var lastBw: BigDecimal? = null

    /** The session's bodyweight snapshot (BW-1): load points and their effective loads are computed with it, as in a live session. */
    private var sessionBw: Double? = null

    private fun previousValue(step: TestStep, side: String?): BigDecimal? {
        val v = step.metric?.let { _ui.value.previous[it] } ?: return null
        val one = if (v is SideV) (if (side == "R") v.r else v.l) else v
        return (one as? QV)?.v
    }

    // ------------------------------------------------------------------------------------------------ inputs

    fun stepKg(up: Boolean) = _ui.update { u ->
        val kg = (u.draft.kg ?: FALLBACK_BW).add(if (up) KG_STEP else KG_STEP.negate()).max(BigDecimal.ZERO)
        u.copy(draft = u.draft.copy(kg = kg))
    }

    fun setKg(kg: BigDecimal) = _ui.update { it.copy(draft = it.draft.copy(kg = kg.setScale(1, RoundingMode.HALF_EVEN))) }

    fun stepReps(up: Boolean) = _ui.update { u -> u.copy(draft = u.draft.copy(reps = UiPrefill.stepReps(u.draft.reps ?: 0, up))) }

    fun stepHold(up: Boolean) = _ui.update { u -> u.copy(draft = u.draft.copy(holdS = UiPrefill.stepHold(u.draft.holdS ?: 0.0, up, u.draft.holdS, params))) }

    fun setHold(s: Double) = _ui.update { u -> u.copy(draft = u.draft.copy(holdS = BigDecimal(s).setScale(1, RoundingMode.HALF_EVEN).toDouble())) }

    /** A typed value (tap on the stepper value, UX 16.7): loads snap to the nearest loadable point. */
    fun typeValue(x: Double) = _ui.update { u ->
        when (u.current?.kind) {
            StepKind.WEIGH -> u.copy(draft = u.draft.copy(kg = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN)))
            StepKind.REPS -> u.copy(draft = u.draft.copy(reps = x.toInt().coerceAtLeast(0)))
            StepKind.HOLD -> u.copy(draft = u.draft.copy(holdS = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()))
            StepKind.ATTEMPTS, StepKind.LADDER -> {
                val ladder = u.current.kind == StepKind.LADDER
                val i = u.points.indices.minByOrNull { i -> ((if (ladder) u.points[i].assistPinNative else u.points[i].addedKg) ?: Double.MAX_VALUE).let { kotlin.math.abs(it - x) } }
                u.copy(draft = u.draft.copy(loadIndex = i ?: u.draft.loadIndex))
            }
            else -> u
        }
    }

    /** One loadable point harder or easier (the ladder: less help is harder). */
    fun stepLoad(harder: Boolean) = _ui.update { u ->
        val i = u.draft.loadIndex ?: return@update u
        val next = i + if (harder) 1 else -1
        u.copy(draft = u.draft.copy(loadIndex = next.coerceIn(0, (u.points.size - 1).coerceAtLeast(0))))
    }

    // ------------------------------------------------------------------------------------------------ logging

    fun recordWeighIn() = act {
        val sid = sessionId ?: return@act
        // The step is taken at the tap: once the weigh-in is stored the screen already shows the next step.
        val step = _ui.value.current
        val kg = _ui.value.draft.kg ?: return@act
        lastBw = kg
        c.testDays.weighIn(enrollmentId, sid, kg)
        stepDone(step, skipped = false)
    }

    /** A warm-up item's Done (C63: no RIR), counted as the item's work. */
    fun warmupDone(itemKey: String) = act { logWarmup(listOf(itemKey)) }

    /** "Warm-up done": the items not yet done are logged as done and the step is finished. */
    fun warmupAllDone() = act {
        val step = _ui.value.current ?: return@act
        val logged = _ui.value.rows.map { it.itemKey }.toSet()
        logWarmup(step.items.map { it.key }.filter { it !in logged })
        stepDone(step, skipped = false)
    }

    private suspend fun logWarmup(keys: List<String>) {
        val sid = sessionId ?: return
        val now = AppClock.nowMs()
        for (key in keys) {
            val item = plan?.items?.firstOrNull { it.key == key } ?: continue
            val se = exercises.firstOrNull { it.challenge_item_key == key } ?: continue
            val row = item.rows.firstOrNull()
            c.sessionRepository.logSet(
                command(sid, se, row?.side ?: "both", "warmup", bodyweightPoint(se), reps = row?.targetReps ?: if (row?.durationS == null) 1 else null, now = now)
                    .copy(durationS = row?.durationS?.toDouble(), countsAsWork = true),
            )
        }
    }

    /** A ramp set of the strength step's protocol (D36), logged with Done as a warm-up set. */
    fun rampDone(addedKg: BigDecimal, reps: Int) = act {
        val sid = sessionId ?: return@act
        val step = _ui.value.current ?: return@act
        val se = exercises.firstOrNull { it.challenge_item_key == step.item.key } ?: return@act
        val point = _ui.value.points.minByOrNull { kotlin.math.abs(it.addedKg - addedKg.toDouble()) }
        c.sessionRepository.logSet(command(sid, se, "both", "warmup", point, reps = reps, now = AppClock.nowMs()))
    }

    /** Record (clean) or Not clean / Failed on the current card: one `set_kind = test` row (C63). */
    fun record(clean: Boolean) = act {
        val u = _ui.value
        val sid = sessionId ?: return@act
        val step = u.current ?: return@act
        val side = u.side ?: return@act
        val se = exercises.firstOrNull { it.challenge_item_key == step.item.key } ?: return@act
        val now = AppClock.nowMs()
        val prevCompleted = u.rows.maxOfOrNull { it.completedAt }
        val rest = c.restTimer.onNextSetLogged(sid, prevCompleted, now, null, null, 0.0)
        val flags = if (clean) emptyList() else listOf(if (step.kind == StepKind.ATTEMPTS) LoggedRow.NOT_CLEAN_FLAG else LoggedRow.FAILED_FLAG)
        val point = u.draft.loadIndex?.let { u.points.getOrNull(it) } ?: bodyweightPoint(se)
        val cmd = when (step.kind) {
            StepKind.ATTEMPTS -> command(sid, se, side, "test", point, reps = TestDayModel.requiredReps(step.item).takeIf { clean } ?: u.draft.reps ?: 0, now = now)
                .copy(rir = 0.0, rirSource = PROTOCOL, flags = flags)
            StepKind.REPS -> command(sid, se, side, "test", point, reps = u.draft.reps ?: 0, now = now).copy(rir = 0.0, rirSource = PROTOCOL)
            StepKind.HOLD -> command(sid, se, side, "test", point, reps = null, now = now).copy(durationS = u.draft.holdS ?: 0.0)
            StepKind.LADDER -> command(sid, se, side, "test", point, reps = if (clean) 1 else 0, now = now).copy(flags = flags)
            StepKind.BOOL -> command(sid, se, side, "test", point, reps = if (clean) 1 else 0, now = now).copy(flags = flags)
            StepKind.WEIGH, StepKind.WARMUP -> return@act
        }.copy(rest = rest)
        val setId = c.sessionRepository.logSet(cmd)
        val after = u.rows + LoggedRow(setId, step.item.key, side, "test", cmd.reps, point?.addedKg?.let(::BigDecimal), point?.assistPinNative?.let(::BigDecimal), cmd.durationS?.let(::BigDecimal), flags, now)
        // Rest between attempts uses the protocol's rest (UX 13.x: `rest_s` of the test template), not after the last one.
        if (TestDayModel.currentSide(step, after, minPin(step)) != null) {
            val restS = step.item.rows.firstNotNullOfOrNull { it.restS }
            if (restS != null) {
                val ex = c.catalog.byId(se.exercise_id)
                val hardMax = ex?.let { RestPolicy.decide(RestInput(RestClass.of(it.restClass), targetReps = TestDayModel.requiredReps(step.item)), params).hardMaxS.toInt() } ?: restS
                c.restTimer.startAfter(sid, setId, se.exercise_id, side, now, restS, maxOf(hardMax, restS), "rest", "challenge")
            }
        }
    }

    /** Undo of the step's last row (P5: a wrong tap is never permanent). */
    fun undoLast() = act {
        val sid = sessionId ?: return@act
        val step = _ui.value.current ?: return@act
        val last = _ui.value.rows.filter { it.itemKey in step.items.map { i -> i.key } }.maxByOrNull { it.completedAt } ?: return@act
        c.restTimer.cancelForUndo(sid, last.completedAt)
        c.sessionRepository.undoSet(last.id)
    }

    fun skipRest() = viewModelScope.launch { c.restTimer.skip("button") }

    /** The bodyweight load point of a bodyweight exercise (its `f_bw`, C1); null for any other load mode. */
    private suspend fun bodyweightPoint(se: SessionExerciseEntity): LoadPoint? {
        if (se.load_mode != LoadMode.BODYWEIGHT.code) return null
        val ex = c.catalog.byId(se.exercise_id) ?: return null
        return runCatching { c.equipment.loadables(ex, LoadMode.BODYWEIGHT, sessionBw, params).second.firstOrNull() }.getOrNull()
    }

    private fun command(sid: String, se: SessionExerciseEntity, side: String, kind: String, point: LoadPoint?, reps: Int?, now: Long) = LogSetCommand(
        sessionId = sid, sessionExerciseId = se.id, exerciseId = se.exercise_id, setIndex = nextIndex(se.id, side, kind), side = side, setKind = kind,
        load = point, reps = reps, durationS = null, rir = null, rirLowerBound = false, holdReserveS = null, holdReserveLowerBound = false,
        flags = emptyList(), deviationReason = null, planned = null, rest = null, startedAtMs = null, completedAtMs = now,
        telemetry = mapOf("prefill_source" to JsonPrimitive("engine_target"), "log_control" to JsonPrimitive("record"), "input_method" to JsonPrimitive("stepper")),
    )

    private fun nextIndex(seId: String, side: String, kind: String): Int {
        val key = exercises.firstOrNull { it.id == seId }?.challenge_item_key
        return _ui.value.rows.count { it.itemKey == key && it.side == side && it.setKind == kind } + 1
    }

    // ------------------------------------------------------------------------------------------------ steps

    /** Next: the step's items are done with what was logged (UX 14.9). */
    fun next() {
        val step = _ui.value.current
        act { stepDone(step, skipped = false) }
    }

    fun skipStep() {
        val step = _ui.value.current
        act { stepDone(step, skipped = true) }
    }

    private suspend fun stepDone(step: TestStep?, skipped: Boolean) {
        step ?: return
        val sid = sessionId ?: return
        val ids = step.items.mapNotNull { item -> exercises.firstOrNull { it.challenge_item_key == item.key }?.id }
        if (ids.isNotEmpty()) c.testDays.finishStep(ids, skipped)
        c.sessionEvent(
            sid, "test_step_completed",
            mapOf("step" to step.number, "step_key" to step.key, "duration_ms" to AppClock.nowMs() - stepShownAt, "skipped" to skipped),
            screen = SCREEN,
        )
    }

    // ------------------------------------------------------------------------------------------------ review

    private suspend fun review() {
        val sid = sessionId ?: return
        val p = plan ?: return
        val u = _ui.value
        val metrics = TestDayModel.metrics(u.steps, u.rows, u.bwKg)
        val out = runCatching { c.testDays.preview(enrollmentId, sid, p.templateId, metrics) }.getOrNull() ?: return
        val order = u.steps.mapNotNull { it.metric }
        val lines = metrics.entries.sortedBy { order.indexOf(it.key) }.map { (code, v) ->
            LineUi(ChallengeTexts.metricLabel(code), UiGateRows.text(v, code), delta(v, u.previous[code], code))
        }
        val goals = goals(out)
        val reason = if (out.valid) null else invalidReason(out.before).first
        _ui.update { it.copy(review = ReviewUi(lines, out.valid, reason, goals)) }
    }

    private fun goals(out: TestOutcome): String? = runCatching {
        val g = UiGateRows.build(out.def, c.params, out.after, out.facts, out.facts.bwKg, emptyList())
        if (g.total == 0) null else g.header
    }.getOrNull()

    /** "+6.5 kg" against the previous valid test, per side for sided metrics; null without a comparable value. */
    private fun delta(v: Value, prev: Value?, code: String): String? {
        prev ?: return null
        fun one(a: Value?, b: Value?): String? {
            val x = (a as? QV)?.v ?: return null
            val y = (b as? QV)?.v ?: return null
            val d = x.subtract(y)
            if (d.signum() == 0) return "same"
            return (if (d.signum() > 0) "+" else "−") + UiGateRows.text(QV(a.dim, d.abs()), null)
        }
        return when {
            v is SideV && prev is SideV -> listOfNotNull(one(v.l, prev.l)?.let { "L $it" }, one(v.r, prev.r)?.let { "R $it" }).joinToString(" · ").ifEmpty { null }
            v is BoolV -> null
            else -> one(v, prev)
        }
    }

    /** Submit: `session.completed`, `test.completed` and the observations; then the recalculation report. */
    fun submit() = act {
        val sid = sessionId ?: return@act
        val p = plan ?: return@act
        val u = _ui.value
        val metrics = TestDayModel.metrics(u.steps, u.rows, u.bwKg)
        c.restTimer.sessionFinished(sid)
        val out = c.testDays.submit(enrollmentId, sid, p.templateId, metrics, TestDayModel.attemptCounts(u.steps, u.rows))
        val skippedSteps = skippedSteps(u)
        c.sessionEvent(sid, "test_submitted", mapOf("valid" to out.valid, "steps_completed" to u.steps.size - skippedSteps, "steps_skipped" to skippedSteps), screen = SCREEN)
        val report = report(out)
        _ui.update { it.copy(phase = Phase.REPORT, report = report) }
        val lines = listOfNotNull(report.estimate).size + report.loads.size + listOfNotNull(report.goals, report.nextTest).size
        c.sessionRepository.event(
            EventDraft(
                type = "recalc_report_shown", sessionId = sid, entityType = "enrollment", entityId = enrollmentId, screen = SCREEN,
                payload = mapOf("lines" to JsonPrimitive(lines), "test_valid" to JsonPrimitive(out.valid)),
            ),
        )
        SessionService.stop(c.appContext)
        c.notifications.cancelSession()
    }

    private fun skippedSteps(u: TestDayUi): Int =
        u.steps.count { s -> s.items.any { item -> exercises.firstOrNull { it.challenge_item_key == item.key }?.status == "skipped" } }

    /** The recalculation report (UX 14.9, D35): a few plain lines, the D7 extras folded. */
    private fun report(out: TestOutcome): ReportUi {
        val def = out.def
        val gates = runCatching { UiGateRows.build(def, c.params, out.after, out.facts, out.facts.bwKg, emptyList()) }.getOrNull()
        val estimateRow = gates?.rows?.firstOrNull { it.more != null && it.detail != null }
        val loads = def.snapshotVars.mapNotNull { name ->
            val a = out.before.vars[name]
            val b = out.after.vars[name]
            if (a == b || b == null) return@mapNotNull null
            LineUi(ChallengeTexts.varLabel(name), ChallengeTexts.value(b, name.takeIf { it.endsWith("Added") }), a?.takeIf { it !is NullV }?.let { "was " + ChallengeTexts.value(it) })
        }
        return ReportUi(
            headline = if (out.valid) "Test saved" else "Test saved, not counted for your goals",
            reason = if (out.valid) null else invalidReason(out.before).first,
            estimate = estimateRow?.let { r -> LineUi(r.title, r.sides.firstOrNull()?.text?.substringBefore(" of ") ?: "") },
            estimateMore = estimateRow?.more,
            loads = loads,
            goals = gates?.header,
            // The next date is set when the block rolls over; until then the old date would mislead.
            nextTest = out.after.plannedTest.date?.takeIf { it != out.before.plannedTest.date }?.let { "Next test: ${ChallengeTexts.date(it)}" },
            messages = out.notifications.map { ChallengeTexts.message(def, it) },
        )
    }

    // ------------------------------------------------------------------------------------------------ other

    fun reportPain(region: String, side: String, severity: String, symptoms: List<String>) = act {
        val sid = sessionId
        val wid = c.sessionRepository.reportPain(sid, null, region, side, severity, "in_session", "test_wizard", symptoms)
        val outs = runCatching { c.challengeSessions.forwardPain(wid, region, side, severity, symptoms, sid) }.getOrDefault(emptyList())
        outs.flatMap { o -> o.notifications.map { ChallengeTexts.message(o.folded.def, it) } }.firstOrNull()?.let { n -> _ui.update { it.copy(notice = n) } }
    }

    /** Stop test: the session is abandoned; nothing is sent to the challenge. */
    fun abandon(done: () -> Unit) = viewModelScope.launch {
        val sid = sessionId
        if (sid != null) {
            c.restTimer.sessionFinished(sid)
            c.sessionRepository.abandon(sid)
            SessionService.stop(c.appContext)
            c.notifications.cancelSession()
        }
        done()
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    private fun act(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        // The app scope: leaving the screen right after a tap must not cancel the write.
        c.appScope.launch {
            runCatching { block() }.onFailure { e -> _ui.update { it.copy(notice = e.message ?: "That did not work.") } }
            _ui.update { it.copy(busy = false) }
        }
    }

    companion object {
        const val SCREEN = "test.wizard"
        val ZONE: java.time.ZoneId = java.time.ZoneId.of("Europe/Amsterdam")
        private val KG_STEP = BigDecimal("0.1")
        private val FALLBACK_BW = BigDecimal("70.0")
        private const val DEFAULT_REPS = 10
        private const val DEFAULT_HOLD_S = 10.0
        private const val PROTOCOL = "protocol"

        /**
         * Why a test would not count, from the state the template's validity reads (UX 14.9, P15: plain words). The
         * clean-session wait is the dashboard's precondition; any other failing condition gets the generic line.
         */
        fun invalidReason(s: EnrollmentState): Pair<String, String> = when {
            (s.vars["needsCleanSession"] as? BoolV)?.b == true -> "First one normal session without pain." to "needs_clean_session"
            s.mode == "normal" -> "It isn't a test week yet." to "not_test_week"
            s.mode == "injury" -> "The elbow protocol is on." to "injury"
            else -> "The program's test conditions aren't met." to "other"
        }
    }
}
