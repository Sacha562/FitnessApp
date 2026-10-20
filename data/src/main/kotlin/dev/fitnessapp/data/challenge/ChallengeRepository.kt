package dev.fitnessapp.data.challenge

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.AuditRow
import dev.fitnessapp.algo.challenge.eca.ChallengeNotification
import dev.fitnessapp.algo.challenge.eca.Outcome
import dev.fitnessapp.algo.challenge.eca.RuleEngine
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventCodec
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.PauseTools
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.entity.ChallengeEnrollmentEntity
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.ChallengeRuleEventEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.SortedMap
import java.util.UUID

/** An input event the app writes (CHALLENGE_ENGINE 4.2); facts default to the provider's current snapshot. */
data class ChallengeEventDraft(
    val type: String,
    val payload: EventPayload,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val sourceSessionId: String? = null,
    val sourceWellnessLogId: String? = null,
    val supersedesEventId: String? = null,
    val facts: EngineFacts? = null,
)

/** The folded enrollment as the app sees it: the cached row, its definition and the state. */
class FoldedEnrollment(
    val enrollment: ChallengeEnrollmentEntity,
    val def: CompiledDefinition,
    val state: EnrollmentState,
    internal val lastKey: Pair<Long, String>?,
    internal val eventCount: Int,
)

/** What one write did: the new state, the notifications to show (5.7) and engine warnings. */
class ChallengeOutcome(val folded: FoldedEnrollment, val notifications: List<ChallengeNotification>, val warnings: List<String>)

/** The tick catch-up of 4.6 across open enrollments. */
class CatchUpResult(val absencePromptDue: List<String>, val notifications: List<ChallengeNotification>, val ticks: Int)

/**
 * Challenge storage on the phone (CHALLENGE_ENGINE 4, DATA_MODEL 9.4, 9.7, 11): appends input events to
 * `challenge_events`, folds them with `:algo` into the cached `challenge_enrollments` row, writes the rule audit
 * (`challenge_rule_events`) and queues everything for sync. An event that sorts before the last folded one, an
 * amendment or a retraction, and events pulled from another device trigger a full refold with
 * `fold_generation + 1` (4.11); a refold re-sends no notification whose dedupe key was already sent.
 */
class ChallengeRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val params: ParamView,
    val library: ChallengeLibrary,
    private val facts: ChallengeFactsProvider,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, FoldedEnrollment>()
    private val ecaVersion: String = RuleEngine.ref.version.toString()

    // ------------------------------------------------------------------------------------------ definitions

    /** Stores the bundled definitions locally unless the server copy (same id) is already there (11.10). */
    suspend fun ensureBundled() {
        for ((text, def) in library.bundled) {
            val id = ChallengeLibrary.idOf(def).toString()
            if (db.challengeDefinitions().get(id) == null) db.challengeDefinitions().upsert(library.row(text, def, "bundled"))
        }
    }

    suspend fun definition(definitionId: String): CompiledDefinition {
        val row = requireNotNull(db.challengeDefinitions().get(definitionId)) { "unknown definition $definitionId" }
        return library.compile(row)
    }

    suspend fun definitions(): List<Pair<String, CompiledDefinition>> =
        db.challengeDefinitions().all().filter { it.status == "published" }.mapNotNull { row ->
            runCatching { row.id to library.compile(row) }.getOrNull()
        }

    // ------------------------------------------------------------------------------------------ reads

    fun observeOpen(): Flow<List<ChallengeEnrollmentEntity>> = db.enrollments().observeOpen()
    fun observeAll(): Flow<List<ChallengeEnrollmentEntity>> = db.enrollments().observeAll()

    suspend fun open(): List<ChallengeEnrollmentEntity> = db.enrollments().open()

    /** The current fold of [enrollmentId], refolding from the stored events when the cache is stale. */
    suspend fun folded(enrollmentId: String): FoldedEnrollment = mutex.withLock { load(enrollmentId) }

    // ------------------------------------------------------------------------------------------ writes

    /**
     * Setup wizard Start (10.2): `enrollment.created` plus one `rules.approval_changed` per proposal with the same
     * `occurred_at` base and increasing times, folded at once; then today's ticks.
     */
    suspend fun create(
        definitionId: String,
        enrollmentParams: SortedMap<String, Value>,
        onboard: SortedMap<String, Value>,
        import: ImportBlock?,
        decisions: Map<String, String>,
    ): FoldedEnrollment = mutex.withLock {
        val uid = ctx.requireUser()
        val row = requireNotNull(db.challengeDefinitions().get(definitionId)) { "unknown definition $definitionId" }
        val def = library.compile(row)
        require(db.enrollments().open().none { it.definition_slug == def.slug }) { "an open ${def.slug} enrollment exists" }
        val missing = def.doc.proposals.map { it.id }.filter { it !in decisions }
        require(missing.isEmpty()) { "undecided proposals: $missing" }
        val enrollmentId = ctx.ids.next()
        val now = Instant.ofEpochMilli(ctx.nowMs())
        val today = now.atZone(ctx.zone()).toLocalDate()
        val f = facts.facts(def, null)
        val created = ChallengeEvent(
            UUID.fromString(ctx.ids.next()), EventTypes.CREATED, now, today,
            EventPayload.Created(UUID.fromString(enrollmentId), enrollmentParams, onboard, import, def.version, def.jcsSha256), f,
        )
        val approvals = def.doc.proposals.mapIndexed { i, p ->
            ChallengeEvent(
                UUID.fromString(ctx.ids.next()), EventTypes.APPROVAL, now.plusMillis(1L + i), today,
                EventPayload.ApprovalChanged(p.id, decisions.getValue(p.id)), f,
            )
        }
        // The creation day's ticks (00:00.002 and later) sort before `enrollment.created`; synthesizing them now and
        // folding everything in (occurred_at, id) order keeps the stored order and the fold order the same (4.3).
        val ticks = EnrollmentFold.catchUp(def, params, EnrollmentFold.create(def, params, created).state, now, ctx.zone(), null).first
        val events = (listOf(created) + approvals + ticks).sortedWith(ChallengeEvent.ORDER)
        val fold = EnrollmentFold.fold(def, params, events)
        val base = ChallengeEnrollmentEntity(
            id = enrollmentId, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
            app_version = ctx.appVersion, definition_id = row.id, definition_slug = def.slug, definition_version = def.version,
            definition_jcs_sha256 = def.jcsSha256, engine_version = EngineVersion.ENGINE_VERSION, started_on = today.toString(),
        )
        val revs = revsOf(fold.trail)
        db.withTransaction {
            val enr = enrollmentRow(base, fold.state, 0, events.last().id.toString())
            db.enrollments().upsert(enr)
            outbox.enqueue("challenge_enrollments", enr.id, now.toEpochMilli())
            for (e in events) insertEvent(eventRow(uid, enrollmentId, def, e, dedupeOf(e)), now.toEpochMilli())
            insertAudits(auditRows(uid, enrollmentId, def, fold.audits, revs, 0), now.toEpochMilli())
            cache[enrollmentId] = FoldedEnrollment(enr, def, fold.state, keyOf(events.last()), events.size)
        }
        scheduler.request()
        load(enrollmentId)
    }

    /**
     * Appends one input event. Ticks due before it are folded first (4.6), so an event of today never sorts before
     * today's `day.tick`. An event earlier than the last folded one, or one that supersedes another, refolds.
     */
    suspend fun record(enrollmentId: String, draft: ChallengeEventDraft): ChallengeOutcome = mutex.withLock {
        val now = Instant.ofEpochMilli(ctx.nowMs())
        var f = load(enrollmentId)
        catchUpLocked(f, minOf(now, draft.occurredAt), force = true)
        f = load(enrollmentId)
        val uid = ctx.requireUser()
        val e = ChallengeEvent(
            UUID.fromString(ctx.ids.next()), draft.type, draft.occurredAt.truncatedTo(ChronoUnit.MILLIS), draft.localDate, draft.payload,
            draft.facts ?: facts.facts(f.def, f.state), draft.supersedesEventId?.let(UUID::fromString),
        )
        val row = eventRow(uid, enrollmentId, f.def, e, null, draft.sourceSessionId, draft.sourceWellnessLogId)
        val late = f.lastKey != null && compareKeys(keyOf(e), f.lastKey) < 0
        if (e.supersedesEventId != null || late) {
            db.withTransaction { insertEvent(row, now.toEpochMilli()) }
            val before = f.state.notificationsSent
            val g = refoldLocked(f.enrollment, f.def)
            scheduler.request()
            val fresh = g.second.filter { (it.msgKey + "|" + it.scopeKey) !in before }
            return@withLock ChallengeOutcome(g.first, fresh, g.third)
        }
        val step = EnrollmentFold.apply(f.def, params, f.state, e)
        val next = db.withTransaction {
            insertEvent(row, now.toEpochMilli())
            insertAudits(
                auditRows(uid, enrollmentId, f.def, step.audits, mapOf(e.id to (f.state.stateRev to step.state.stateRev)), f.enrollment.fold_generation),
                now.toEpochMilli(),
            )
            val enr = enrollmentRow(f.enrollment, step.state, f.enrollment.fold_generation, e.id.toString())
            db.enrollments().upsert(enr)
            outbox.enqueue("challenge_enrollments", enr.id, now.toEpochMilli())
            FoldedEnrollment(enr, f.def, step.state, keyOf(e), f.eventCount + 1).also { cache[enrollmentId] = it }
        }
        scheduler.request()
        ChallengeOutcome(next, step.notifications, step.warnings)
    }

    /**
     * 4.6: synthesizes and folds the ticks due at [now] for every open enrollment. An enrollment whose last challenge
     * session is at least `absence_prompt_days` ago is left for the absence prompt unless [force].
     */
    suspend fun catchUp(now: Instant = Instant.ofEpochMilli(ctx.nowMs()), force: Boolean = false): CatchUpResult = mutex.withLock {
        if (ctx.userId() == null) return@withLock CatchUpResult(emptyList(), emptyList(), 0)
        val prompts = ArrayList<String>()
        val notes = ArrayList<ChallengeNotification>()
        var ticks = 0
        for (enr in db.enrollments().open()) {
            val f = load(enr.id)
            if (!force && PauseTools.absencePromptDue(f.state, now.atZone(ctx.zone()).toLocalDate(), params)) {
                prompts += enr.id
                continue
            }
            val (n, t) = catchUpLocked(f, now, force = true)
            notes += n
            ticks += t
        }
        if (ticks > 0) scheduler.request()
        CatchUpResult(prompts, notes, ticks)
    }

    /** After a sync pull: refolds every enrollment whose stored events no longer match its cached fold (4.11). */
    suspend fun reconcile() = mutex.withLock {
        cache.clear()
        for (enr in db.enrollments().open()) load(enr.id)
    }

    // ------------------------------------------------------------------------------------------ fold plumbing

    private fun keyOf(e: ChallengeEvent): Pair<Long, String> = e.occurredAt.toEpochMilli() to e.id.toString()

    private fun compareKeys(a: Pair<Long, String>, b: Pair<Long, String>): Int =
        compareValuesBy(a, b, { it.first }, { it.second })

    /** The cached fold, or a fold of the stored events; a fold that differs from the stored row becomes a new generation. */
    private suspend fun load(enrollmentId: String): FoldedEnrollment {
        val enr = requireNotNull(db.enrollments().get(enrollmentId)) { "unknown enrollment $enrollmentId" }
        cache[enrollmentId]?.let { c -> if (c.enrollment.state_rev == enr.state_rev && c.enrollment.fold_generation == enr.fold_generation) return c }
        val def = definition(enr.definition_id)
        val rows = db.challengeEvents().forEnrollment(enrollmentId)
        val events = rows.map { decode(def, it) }
        val fold = EnrollmentFold.fold(def, params, events)
        val last = events.lastOrNull()
        val fresh = enr.state_hash == StateJson.hash(fold.state) && enr.last_folded_event_id == last?.id?.toString()
        if (!fresh) return refoldLocked(enr, def).first
        return FoldedEnrollment(enr, def, fold.state, last?.let(::keyOf), events.size).also { cache[enrollmentId] = it }
    }

    /** Full refold with `fold_generation + 1`: new audit rows for the generation, the row updated (4.11). */
    private suspend fun refoldLocked(enr: ChallengeEnrollmentEntity, def: CompiledDefinition): Triple<FoldedEnrollment, List<ChallengeNotification>, List<String>> {
        val uid = enr.user_id
        val events = db.challengeEvents().forEnrollment(enr.id).map { decode(def, it) }
        val fold = EnrollmentFold.fold(def, params, events)
        val generation = enr.fold_generation + 1
        val now = ctx.nowMs()
        val next = db.withTransaction {
            insertAudits(auditRows(uid, enr.id, def, fold.audits, revsOf(fold.trail), generation), now)
            val row = enrollmentRow(enr, fold.state, generation, events.lastOrNull()?.id?.toString())
            db.enrollments().upsert(row)
            outbox.enqueue("challenge_enrollments", row.id, now)
            FoldedEnrollment(row, def, fold.state, events.lastOrNull()?.let(::keyOf), events.size).also { cache[enr.id] = it }
        }
        return Triple(next, fold.notifications, fold.warnings)
    }

    /** Synthesizes and folds the due ticks of one enrollment; returns their notifications and the number of day ticks. */
    private suspend fun catchUpLocked(f: FoldedEnrollment, now: Instant, force: Boolean): Pair<List<ChallengeNotification>, Int> {
        if (!force) return emptyList<ChallengeNotification>() to 0
        val (events, steps) = EnrollmentFold.catchUp(f.def, params, f.state, now, ctx.zone(), null)
        if (events.isEmpty()) return emptyList<ChallengeNotification>() to 0
        val uid = f.enrollment.user_id
        val nowMs = ctx.nowMs()
        var ignored = false
        val notes = steps.flatMap { it.notifications }
        db.withTransaction {
            var state = f.state
            for ((e, step) in events.zip(steps)) {
                if (!insertEvent(eventRow(uid, f.enrollment.id, f.def, e, dedupeOf(e)), nowMs)) ignored = true
                insertAudits(auditRows(uid, f.enrollment.id, f.def, step.audits, mapOf(e.id to (state.stateRev to step.state.stateRev)), f.enrollment.fold_generation), nowMs)
                state = step.state
            }
            val row = enrollmentRow(f.enrollment, state, f.enrollment.fold_generation, events.last().id.toString())
            db.enrollments().upsert(row)
            outbox.enqueue("challenge_enrollments", row.id, nowMs)
            cache[f.enrollment.id] = FoldedEnrollment(row, f.def, state, keyOf(events.last()), f.eventCount + events.size)
            val dayTicks = events.filter { it.type == EventTypes.DAY_TICK }
            if (dayTicks.size > 1) {
                telemetry.write(
                    EventDraft(
                        type = "tick_catchup", entityType = "enrollment", entityId = f.enrollment.id,
                        payload = mapOf(
                            "enrollment_id" to JsonPrimitive(f.enrollment.id),
                            "from_date" to JsonPrimitive(dayTicks.first().localDate.toString()),
                            "to_date" to JsonPrimitive(dayTicks.last().localDate.toString()),
                            "ticks" to JsonNumbers.int(dayTicks.size.toLong()),
                        ),
                    ),
                )
            }
        }
        // Another device synthesized some of these ticks first: fold the stored events instead.
        if (ignored) refoldLocked(db.enrollments().get(f.enrollment.id)!!, f.def)
        return notes to events.count { it.type == EventTypes.DAY_TICK }
    }

    private fun dedupeOf(e: ChallengeEvent): String? = when (val p = e.payload) {
        is EventPayload.DayTick -> "day.tick:${p.date}"
        is EventPayload.TestDue -> "test.due:${p.date}"
        is EventPayload.WeekBoundary -> "${e.type}:${p.isoWeek}"
        else -> null
    }

    private fun revsOf(trail: List<Pair<ChallengeEvent, EnrollmentState>>): Map<UUID, Pair<Int, Int>> {
        val out = LinkedHashMap<UUID, Pair<Int, Int>>()
        var before = 0
        for ((e, s) in trail) {
            out[e.id] = before to s.stateRev
            before = s.stateRev
        }
        return out
    }

    private fun decode(def: CompiledDefinition, r: ChallengeEventEntity): ChallengeEvent = EventCodec.decode(
        def, UUID.fromString(r.id), r.event_type, Instant.ofEpochMilli(r.occurred_at), LocalDate.parse(r.local_date),
        Json.parseToJsonElement(r.payload).jsonObject, r.supersedes_event_id?.let(UUID::fromString),
    )

    private fun eventRow(
        uid: String,
        enrollmentId: String,
        def: CompiledDefinition,
        e: ChallengeEvent,
        dedupe: String?,
        sourceSession: String? = null,
        sourceWellness: String? = null,
    ) = ChallengeEventEntity(
        id = e.id.toString(), user_id = uid, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
        enrollment_id = enrollmentId, event_type = e.type, occurred_at = e.occurredAt.toEpochMilli(), local_date = e.localDate.toString(),
        dedupe_key = dedupe, payload = JsonText.canonical(EventCodec.encode(def, e)), source_session_id = sourceSession,
        source_wellness_log_id = sourceWellness, supersedes_event_id = e.supersedesEventId?.toString(),
        definition_version = def.version, definition_jcs_sha256 = def.jcsSha256, engine_version = EngineVersion.ENGINE_VERSION,
    )

    /** False when the row was already there (same id or dedupe key). */
    private suspend fun insertEvent(row: ChallengeEventEntity, nowMs: Long): Boolean {
        val inserted = db.challengeEvents().insertIgnore(row) != -1L
        if (inserted) outbox.enqueue("challenge_events", row.id, nowMs)
        return inserted
    }

    private suspend fun insertAudits(rows: List<ChallengeRuleEventEntity>, nowMs: Long) {
        if (rows.isEmpty()) return
        val results = db.ruleEvents().insertIgnore(rows)
        rows.zip(results).filter { it.second != -1L }.forEach { outbox.enqueue("challenge_rule_events", it.first.id, nowMs) }
    }

    private fun auditRows(
        uid: String,
        enrollmentId: String,
        def: CompiledDefinition,
        audits: List<AuditRow>,
        revs: Map<UUID, Pair<Int, Int>>,
        generation: Int,
    ): List<ChallengeRuleEventEntity> = audits.map { a ->
        val (before, after) = revs[a.eventId] ?: (0 to 0)
        ChallengeRuleEventEntity(
            id = Ids.fitness("challenge_rule_events:${a.evaluationId}:${a.ruleId}:$generation").toString(),
            user_id = uid, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, enrollment_id = enrollmentId,
            challenge_event_id = a.eventId.toString(), evaluation_id = a.evaluationId.toString(), fold_generation = generation,
            rule_id = a.ruleId, rule_tag = a.ruleTag, proposal_id = a.proposalId, rule_decision = a.ruleDecision, stage = a.stage,
            prio = a.prio, outcome = a.outcome.code, cond_value = a.condValue, eval_error = a.evalError?.take(MAX_EVAL_ERROR),
            bindings = JsonText.canonical(JsonObject(a.bindings.mapValues { JsonPrimitive(it.value) })),
            proposals = JsonText.canonical(
                JsonArray(
                    a.writes.map { w ->
                        JsonObject(
                            listOfNotNull(
                                "target" to JsonPrimitive(w.target), "old" to (w.old?.let(::JsonPrimitive) ?: JsonNull), "new" to JsonPrimitive(w.new),
                                "outcome" to JsonPrimitive(w.outcome.code), w.suppressedBy?.let { "suppressed_by" to JsonPrimitive(it) },
                                w.notes.takeIf { it.isNotEmpty() }?.let { n -> "notes" to JsonArray(n.map(::JsonPrimitive)) },
                            ).toMap(),
                        )
                    },
                ),
            ),
            applied = JsonText.canonical(
                JsonObject(
                    a.writes.filter { it.outcome == Outcome.APPLIED }.associate { w ->
                        w.target to JsonObject(mapOf("old" to (w.old?.let(::JsonPrimitive) ?: JsonNull), "new" to JsonPrimitive(w.new)))
                    },
                ),
            ),
            suppressed_by = a.suppressedBy, state_rev_before = before, state_rev_after = after, state_hash_before = a.stateHashBefore,
            state_hash_after = a.stateHashAfter, cascade_depth = a.cascadeDepth, derived_event_type = a.derivedEventType,
            definition_slug = def.slug, definition_version = def.version, definition_jcs_sha256 = def.jcsSha256,
            module_version = ecaVersion, engine_version = EngineVersion.ENGINE_VERSION,
        )
    }

    private fun enrollmentRow(base: ChallengeEnrollmentEntity, s: EnrollmentState, generation: Int, lastEventId: String?): ChallengeEnrollmentEntity {
        val now = ctx.nowMs()
        fun json(m: Map<String, JsonElement>) = JsonText.canonical(JsonObject(m))
        return base.copy(
            client_updated_at = ctx.nextClientMs(),
            status = s.status, mode = s.mode, phase = s.phase, block_index = s.block.index, block_week = s.block.week, week_ordinal = s.weekOrdinal,
            params = json(s.params.mapValues { ValueJson.encode(it.value) }),
            rule_decisions = json(s.proposalDecisions.mapValues { JsonPrimitive(it.value.decision) }),
            state = JsonText.canonical(StateJson.encode(s)), state_rev = s.stateRev, state_hash = StateJson.hash(s),
            fold_generation = generation, last_folded_event_id = lastEventId, engine_version = EngineVersion.ENGINE_VERSION,
            paused_at = if (s.status == EnrollmentState.PAUSED) base.paused_at ?: now else null,
            completed_at = if (s.status == EnrollmentState.COMPLETED) base.completed_at ?: now else null,
            abandoned_at = if (s.status == EnrollmentState.ABANDONED) base.abandoned_at ?: now else null,
        )
    }

    private companion object {
        /** DATA_MODEL 9.7.2: `eval_error` length <= 500. */
        const val MAX_EVAL_ERROR = 500
    }
}
