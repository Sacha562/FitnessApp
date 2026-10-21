package dev.fitnessapp.data.challenge

import androidx.room.withTransaction
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.ChallengeNotification
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.materialize.PlanJson
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap
import java.util.UUID

/** A test day's effect on the enrollment, before (dry run) or after Submit (UX 14.9 review and recalculation report). */
class TestOutcome(
    val def: CompiledDefinition,
    val before: EnrollmentState,
    val after: EnrollmentState,
    val valid: Boolean,
    val facts: EngineFacts,
    val notifications: List<ChallengeNotification>,
)

/**
 * The test-day wizard's storage (UX 14.9, CHALLENGE_ENGINE 10.6): a `kind = test` session of the definition's test
 * slot, the weigh-in as a test observation, and on Submit `session.completed`, `test.completed` and the metric
 * observations (raw per side, derived when the test is valid, C3). Sets go through [SessionRepository.logSet].
 */
class TestDayRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val challenges: ChallengeRepository,
    private val sessions: ChallengeSessionRepository,
    private val sessionRepository: SessionRepository,
    private val facts: ChallengeFactsProvider,
    private val params: ParamView,
    private val changeoverMin: Int,
) {
    private fun today(): LocalDate = Week.localDate(Instant.ofEpochMilli(ctx.nowMs()), ctx.zone())

    /** The test slot's plan for today (the overview before Start); nothing is written. */
    suspend fun plan(enrollmentId: String): SessionPlan {
        val f = challenges.folded(enrollmentId)
        return sessions.materialize(enrollmentId, f.def.doc.structure.block.testSlot, today())
    }

    /** Start: the `kind = test` session with one session exercise per item, as for any challenge slot. */
    suspend fun start(enrollmentId: String): String {
        val f = challenges.folded(enrollmentId)
        return sessions.start(enrollmentId, f.def.doc.structure.block.testSlot)
    }

    fun observeResults(sessionId: String): Flow<List<MetricObservationEntity>> = db.metrics().observeByTestSession(sessionId)

    /**
     * The step keys the wizard finished with Next or Skip (`test_step_completed`, UX 20.10). Item statuses cannot tell:
     * a logged set already marks an item done once its planned rows are reached, while attempts may continue.
     */
    fun observeFinishedSteps(sessionId: String): Flow<Set<String>> = db.events().observeBySessionType(sessionId, STEP_COMPLETED).map { rows ->
        rows.mapNotNull { e -> (Json.parseToJsonElement(e.payload).jsonObject["step_key"] as? JsonPrimitive)?.content }.toSet()
    }

    /**
     * The weigh-in (C32, C62): a `bw` observation with `source = test_day` that later sessions also use, then the plan
     * is materialized again so the protocol's ramp and first attempt use today's bodyweight (D36).
     */
    suspend fun weighIn(enrollmentId: String, sessionId: String, kg: BigDecimal): String {
        val now = ctx.nowMs()
        val id = ctx.ids.next()
        db.withTransaction {
            val row = MetricObservationEntity(
                id = id, user_id = ctx.requireUser(), client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, metric_code = "bw", value_num = Scaled.toLong(kg, 3), unit = "kg", observed_at = now,
                local_date = Week.localDate(Instant.ofEpochMilli(now), ctx.zone()).toString(), source = "test_day", session_id = sessionId,
                test_session_id = sessionId, enrollment_id = enrollmentId,
            )
            db.metrics().upsert(row)
            outbox.enqueue("metric_observations", id, now)
        }
        scheduler.request()
        refreshPlan(sessionId)
        return id
    }

    /** Undo of the weigh-in (a wrong tap): the observation is soft-deleted. */
    suspend fun undoWeighIn(observationId: String) {
        val now = ctx.nowMs()
        db.withTransaction {
            val row = db.metrics().byIds(listOf(observationId)).firstOrNull() ?: return@withTransaction
            db.metrics().upsert(row.copy(deleted_at = now, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId()))
            outbox.enqueue("metric_observations", observationId, now)
        }
        scheduler.request()
    }

    /** Re-materializes the stored plan with the current facts; the rows are unchanged, the protocol steps follow the weigh-in. */
    private suspend fun refreshPlan(sessionId: String) {
        val stored = db.challengeLive().get(sessionId) ?: return
        val old = PlanJson.decode(Json.parseToJsonElement(stored.plan_json).jsonObject)
        val f = challenges.folded(stored.enrollment_id)
        val fresh = Materializer.materialize(
            f.def, params, MaterializeInput(old.slot, old.date, f.state, facts.facts(f.def, f.state), catalog.doc, changeoverMin = changeoverMin),
        )
        val steps = fresh.items.associate { it.key to it.steps }
        val plan = old.copy(items = old.items.map { it.copy(steps = steps[it.key] ?: it.steps) })
        db.challengeLive().upsert(stored.copy(plan_json = JsonText.canonical(PlanJson.encode(plan)), updated_at_ms = ctx.nowMs()))
    }

    /** Next on a step: its items are done with what was logged; Skip marks them skipped. */
    suspend fun finishStep(sessionExerciseIds: List<String>, skipped: Boolean) {
        for (id in sessionExerciseIds) {
            if (skipped) sessionRepository.skip(id, SKIP_REASON, 0) else sessionRepository.markDone(id)
        }
    }

    /** The review's dry run: `test.completed` with [metrics] folded on the current state, nothing written. */
    suspend fun preview(enrollmentId: String, sessionId: String, templateId: String, metrics: SortedMap<String, Value>): TestOutcome {
        val f = challenges.folded(enrollmentId)
        val engineFacts = facts.facts(f.def, f.state)
        val e = ChallengeEvent(
            UUID.fromString(sessionId), EventTypes.TEST_COMPLETED, Instant.ofEpochMilli(ctx.nowMs()), today(),
            EventPayload.TestCompleted(UUID.fromString(sessionId), templateId, metrics, protocolCodes(f.def, metrics)), engineFacts,
        )
        val step = EnrollmentFold.apply(f.def, params, f.state, e)
        val valid = step.state.validTestCount > f.state.validTestCount
        return TestOutcome(f.def, f.state, step.state, valid, engineFacts, step.notifications)
    }

    /**
     * Submit (UX 14.9): finishes the session and writes `session.completed` for the test slot, then `test.completed`
     * with the raw metrics (the engine decides validity, derives the gate value and runs the test rules), then the
     * metric observations: one per metric and side with its attempts, the derived ones with `calc_params` when valid.
     */
    suspend fun submit(
        enrollmentId: String,
        sessionId: String,
        templateId: String,
        metrics: SortedMap<String, Value>,
        attempts: Map<String, Int>,
    ): TestOutcome {
        val before = challenges.folded(enrollmentId).state
        sessionRepository.finish(sessionId, null)
        sessions.complete(sessionId)
        val end = ctx.nowMs()
        val date = today()
        val f = challenges.folded(enrollmentId)
        val codes = protocolCodes(f.def, metrics)
        val out = challenges.record(
            enrollmentId,
            ChallengeEventDraft(
                EventTypes.TEST_COMPLETED, EventPayload.TestCompleted(UUID.fromString(sessionId), templateId, metrics, codes),
                Instant.ofEpochMilli(end), date, sourceSessionId = sessionId,
            ),
        )
        val after = out.folded.state
        val valid = after.lastValidTest?.sessionId?.toString() == sessionId
        writeObservations(enrollmentId, sessionId, f.def, metrics, codes, attempts, if (valid) after else null, end, date)
        return TestOutcome(out.folded.def, before, after, valid, facts.facts(out.folded.def, after), out.notifications)
    }

    private suspend fun writeObservations(
        enrollmentId: String,
        sessionId: String,
        def: CompiledDefinition,
        metrics: SortedMap<String, Value>,
        codes: SortedMap<String, String>,
        attempts: Map<String, Int>,
        valid: EnrollmentState?,
        now: Long,
        date: LocalDate,
    ) {
        val uid = ctx.requireUser()
        val bw = (metrics["bw"] as? QV)?.v
        db.withTransaction {
            val existing = db.metrics().byMetric("bw").filter { it.test_session_id == sessionId }
            for (w in existing) {
                val row = w.copy(valid_for_gate = valid != null, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId())
                db.metrics().upsert(row)
                outbox.enqueue("metric_observations", row.id, now)
            }
            val raw = ArrayList<String>(existing.map { it.id })
            fun row(code: String, side: String, v: Value, derivation: String = "measured", from: List<String> = emptyList(), calc: JsonObject? = null): MetricObservationEntity? {
                val unit = unitOf(def, code) ?: return null
                val num = (v as? QV)?.v
                val bool = (v as? BoolV)?.b
                if (num == null && bool == null) return null
                return MetricObservationEntity(
                    id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                    metric_code = code, value_num = num?.let { Scaled.toLong(it, 3) }, value_bool = bool, unit = unit, side = side, observed_at = now,
                    local_date = date.toString(), source = "test_day", session_id = sessionId, test_session_id = sessionId, enrollment_id = enrollmentId,
                    valid_for_gate = valid != null, protocol_code = codes[code], derivation = derivation, derived_from_ids = from,
                    calc_params = calc?.let(JsonText::canonical) ?: JsonText.EMPTY_OBJECT, attempt_count = attempts["$code|$side"],
                    bodyweight_kg = bw?.let { Scaled.toLong(it, 2) },
                )
            }
            for ((code, v) in metrics) {
                if (code == "bw") continue
                val rows = when (v) {
                    is SideV -> listOfNotNull(v.l?.let { row(code, "L", it) }, v.r?.let { row(code, "R", it) })
                    else -> listOfNotNull(row(code, "none", v))
                }
                for (r in rows) {
                    db.metrics().upsert(r)
                    outbox.enqueue("metric_observations", r.id, now)
                    raw += r.id
                }
            }
            // C3: derived values are computed once by the engine and stored with what they were computed from.
            valid?.lastValidTest?.let { t ->
                val calc = JsonObject(
                    mapOf("bw_kg" to JsonPrimitive(bw), "k" to JsonObject(valid.lastFacts?.userK.orEmpty().mapValues { JsonPrimitive(it.value.k) })),
                )
                for ((code, v) in t.metrics) {
                    if (def.metrics[code]?.derived == null || code in metrics) continue
                    val method = if (code.endsWith(M110_SUFFIX)) "multiplier3rm" else "personal_curve"
                    val r = row(code, "none", v, "calculated:$method", raw.toList(), JsonObject(calc + ("method" to JsonPrimitive(method)))) ?: continue
                    db.metrics().upsert(r)
                    outbox.enqueue("metric_observations", r.id, now)
                }
            }
        }
        scheduler.request()
    }

    private fun protocolCodes(def: CompiledDefinition, metrics: Map<String, Value>): SortedMap<String, String> =
        metrics.keys.mapNotNull { k -> def.metrics[k]?.spec?.protocolCode?.let { k to it } }.toMap().toSortedMap()

    /** `metric_defs.unit` of a definition metric (DATA_MODEL 9.6.6). */
    private fun unitOf(def: CompiledDefinition, code: String): String? = when (def.metrics[code]?.spec?.type) {
        "mass" -> "kg"
        "duration" -> "s"
        "int" -> "reps"
        "pin" -> "pin_lb"
        "bool" -> "bool"
        else -> null
    }

    private companion object {
        /** `session_exercises` requires a reason on a skipped item; a skipped test step carries the generic one. */
        const val SKIP_REASON = "other"

        const val STEP_COMPLETED = "test_step_completed"

        /** Display convention of UiGateRows: `<metric>_m110` is the x1.10 rule (D7). */
        const val M110_SUFFIX = "_m110"
    }
}
