package dev.fitnessapp.data.challenge

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ItemLog
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.materialize.HabitInput
import dev.fitnessapp.algo.challenge.materialize.HabitPlanner
import dev.fitnessapp.algo.challenge.materialize.HabitToday
import dev.fitnessapp.algo.challenge.materialize.InSessionRuleRunner
import dev.fitnessapp.algo.challenge.materialize.LiveItem
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.materialize.PlanJson
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.PlannedRow
import dev.fitnessapp.algo.challenge.materialize.RuleApplication
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.core.bodyweight.BwSession
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.db.local.ChallengeLiveSessionEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.BodyweightRepository
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.UUID

/** Today's home habits of one enrollment (UX 9.6) and the planner input they came from (for the mini-session plan). */
class HabitDay(val enrollmentId: String, val def: dev.fitnessapp.algo.challenge.cds.CompiledDefinition, val input: HabitInput, val rows: List<HabitToday>)

/** The live state of one challenge session: the plan at start and the current rows per item key. */
class ChallengeLive(val enrollmentId: String, val plan: SessionPlan, val items: Map<String, LiveItem>) {
    fun item(key: String?): LiveItem? = key?.let(items::get)
}

/**
 * Challenge sessions in the logger (CHALLENGE_ENGINE 6, UX 11): Start materializes the slot's template for the
 * current phase and mode and writes the session, its challenge block and one session exercise per item; the plan
 * and its live rows are kept locally ([ChallengeLiveSessionEntity]). After each logged set the in-session rules run
 * (6.10, D14: applied at once, undoable); Finish writes `session.completed` with the logged rows (6.12).
 */
class ChallengeSessionRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val equipment: EquipmentRepository,
    private val bodyweight: BodyweightRepository,
    private val profiles: ProfileRepository,
    private val challenges: ChallengeRepository,
    private val facts: ChallengeFactsProvider,
    private val params: ParamView,
    private val changeoverMin: Int,
) {
    /** The plan of [slot] on [date] for the enrollment's current state (Today preview and Start). */
    suspend fun materialize(enrollmentId: String, slot: String, date: LocalDate, eventMin: Int? = null): SessionPlan {
        val f = challenges.folded(enrollmentId)
        return Materializer.materialize(
            f.def, params, MaterializeInput(slot, date, f.state, facts.facts(f.def, f.state), catalog.doc, changeoverMin = changeoverMin, bookedMin = eventMin),
        )
    }

    /**
     * Start (UX 11.1): the session, one challenge block, one session exercise per item, the local live plan. [eventMin]
     * is the matched calendar event's length: the budget is then that minus the changeover (C49, `calendar`).
     */
    suspend fun start(enrollmentId: String, slot: String, eventMin: Int? = null): String {
        val date = Week.localDate(Instant.ofEpochMilli(ctx.nowMs()), ctx.zone())
        return open(enrollmentId, materialize(enrollmentId, slot, date, eventMin), slot = slot, habitId = null, startedFrom = "today", budgetSource = if (eventMin != null) "calendar" else "default")
    }

    /**
     * Home habits for [date] (UX 9.6, CHALLENGE_ENGINE 6.4): due, scaled, skipped or off, with the habits already
     * logged today. [weekPlanSlots] (this week's planned and done slots) feeds `todayHas` and `dayBefore(...)`.
     */
    suspend fun habitDay(enrollmentId: String, date: LocalDate, weekPlanSlots: java.util.SortedMap<LocalDate, List<String>>, generalGymDates: Set<LocalDate>): HabitDay {
        val f = challenges.folded(enrollmentId)
        val done = db.challengeEvents().forEnrollment(enrollmentId)
            .filter { it.event_type == EventTypes.HABIT_COMPLETED && it.local_date == date.toString() }
            .mapNotNull { e -> Json.parseToJsonElement(e.payload).jsonObject["habit_id"]?.jsonPrimitive?.content }.toSet()
        val input = HabitInput(date, f.state, facts.facts(f.def, f.state, weekPlanSlots), generalGymDates, done)
        return HabitDay(enrollmentId, f.def, input, HabitPlanner.today(f.def, params, input).first)
    }

    /** The home mini-session of a habit (UX 9.6, C34): `kind = home`, `template_source = habit`, one challenge item. */
    suspend fun startHabit(day: HabitDay, habitId: String, startedFrom: String): String {
        val row = requireNotNull(day.rows.firstOrNull { it.habitId == habitId }) { "habit $habitId is not scheduled today" }
        val plan = requireNotNull(HabitPlanner.plan(day.def, params, day.input, row, catalog.doc)) { "habit $habitId has no session" }
        return open(day.enrollmentId, plan, slot = null, habitId = habitId, startedFrom = startedFrom)
    }

    /**
     * Quick done (UX 9.6): the habit as prescribed, with no rows (`habit.completed` without items), for habits whose
     * progression does not read per-set results. The caller offers Undo before this is called.
     */
    suspend fun quickDone(enrollmentId: String, habitId: String): ChallengeOutcome {
        val now = ctx.nowMs()
        val date = Week.localDate(Instant.ofEpochMilli(now), ctx.zone())
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "habit_toggled", entityType = "none",
                    payload = mapOf("habit_id" to JsonPrimitive(habitId), "done" to JsonPrimitive(true), "via" to JsonPrimitive("checkbox")),
                ),
            )
        }
        return challenges.record(enrollmentId, ChallengeEventDraft(EventTypes.HABIT_COMPLETED, EventPayload.HabitCompleted(habitId, TreeMap()), Instant.ofEpochMilli(now), date))
    }

    /** Writes the session, its challenge block, one session exercise per planned item and the local live plan. */
    private suspend fun open(enrollmentId: String, plan: SessionPlan, slot: String?, habitId: String?, startedFrom: String, budgetSource: String = "default"): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val zone = ctx.zone()
        val date = plan.date
        val f = challenges.folded(enrollmentId)
        val source = if (habitId != null) "habit" else "challenge_slot"
        val budget = plan.budgetMin.takeIf { it > 0 }
        val id = ctx.ids.next()
        val profile = profiles.get()
        val snap = bodyweight.snapshot(
            BwSession(id, plan.kind, Instant.ofEpochMilli(now), date), profile?.bodyweight_fallback_kg?.let { Scaled.toDouble(it, 2) }, params,
        )
        val gym = if (plan.kind == "home") null else equipment.defaultGym()
        val s = f.state
        db.withTransaction {
            val session = WorkoutSessionEntity(
                id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                kind = plan.kind, status = "in_progress", template_source = source, enrollment_id = enrollmentId, challenge_slot = slot,
                challenge_template_id = plan.templateId, habit_id = habitId, phase = s.phase, block_index = s.block.index, block_week = s.block.week,
                enrollment_mode = s.mode, is_deload = s.mode in DELOAD_MODES, local_date = date.toString(), timezone = zone.id,
                iso_week = Week.isoWeek(date), gym_id = gym?.id, started_at = now, bodyweight_kg = snap.valueKg?.let { Scaled.fromDouble(it, 2) },
                bodyweight_source = snap.source.code, bodyweight_stale = snap.stale, time_budget_min = budget, time_budget_source = budget?.let { budgetSource },
                engine_version = EngineVersion.ENGINE_VERSION, params_hash = params.hash, catalog_version = catalog.version,
            )
            db.sessions().upsert(session)
            outbox.enqueue("workout_sessions", id, now)
            val block = SessionBlockEntity(
                id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                session_id = id, position = 0, block_kind = "challenge", origin = "challenge_template", enrollment_id = enrollmentId,
                challenge_template_id = plan.templateId, budget_min = budget, status = "active",
            )
            db.blocks().upsert(block)
            outbox.enqueue("session_blocks", block.id, now)
            plan.items.forEachIndexed { i, item ->
                // Challenge-local exercises are catalog rows under their definition key (DATA_MODEL 9.2.1, C10).
                val ex = (item.catalogSlug ?: item.exerciseKey)?.let(catalog::bySlug) ?: return@forEachIndexed
                val row = exerciseRow(uid, id, block.id, item, ex, (i + 1) * SessionRepository.POSITION_STEP)
                db.sessionExercises().upsert(row)
                outbox.enqueue("session_exercises", row.id, now)
            }
            db.challengeLive().upsert(
                ChallengeLiveSessionEntity(id, enrollmentId, JsonText.canonical(PlanJson.encode(plan)), JsonText.canonical(liveJson(plan.items.associate { it.key to LiveItem(it, it.rows, emptyList()) })), now),
            )
            telemetry.write(
                EventDraft(
                    type = "session_started", sessionId = id, entityType = "session", entityId = id,
                    payload = mapOf(
                        "kind" to JsonPrimitive(plan.kind), "template_source" to JsonPrimitive(source), "families" to JsonArray(emptyList()),
                        "started_from" to JsonPrimitive(startedFrom), "budget_min" to (budget?.let(::JsonPrimitive) ?: JsonNull),
                        "budget_source" to (budget?.let { JsonPrimitive(budgetSource) } ?: JsonNull),
                        "params_hash" to JsonPrimitive(params.hash), "engine_version" to JsonPrimitive(EngineVersion.ENGINE_VERSION),
                        "catalog_version" to JsonPrimitive(catalog.version),
                    ),
                ),
            )
        }
        scheduler.request()
        return id
    }

    private fun exerciseRow(uid: String, sessionId: String, blockId: String, item: PlannedItem, ex: dev.fitnessapp.algo.selection.catalog.CatalogExercise, position: Long): SessionExerciseEntity {
        val working = item.rows.filter { it.setKind != "warmup" || item.role == "warmup" }
        val first = working.firstOrNull()
        val unilateral = item.rows.any { it.side == "L" || it.side == "R" }
        val mode = when {
            item.rows.any { it.pin != null } -> LoadMode.ASSISTED_MACHINE
            item.rows.any { it.fingers != null } -> LoadMode.ASSISTED_ORDINAL
            item.rows.any { it.addedKg != null && it.addedKg!!.signum() > 0 } -> LoadMode.BODYWEIGHT_PLUS_EXTERNAL
            else -> LoadMode.of(ex.loadMode)
        }
        val sets = if (unilateral) working.count { it.side == "L" } else working.size
        return SessionExerciseEntity(
            id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
            session_id = sessionId, block_id = blockId, exercise_id = catalog.idOf(ex), challenge_item_key = item.key, position = position,
            origin = "challenge",
            status = if (item.status == "planned") "pending" else "skipped",
            status_reason_code = if (item.status == "planned") null else STATUS_REASON,
            load_mode = mode.code,
            side_mode = if (unilateral) "unilateral_sequential" else "bilateral", side_order = if (unilateral) "LR" else null,
            progression_mode = when (item.progressionMode) {
                "rule" -> "rule"
                "none" -> "none"
                else -> "model"
            },
            rep_scheme = when {
                first?.logMode == dev.fitnessapp.algo.challenge.materialize.LogMode.ATTEMPT_CHECKLIST -> "attempt"
                first?.targetHoldS != null || first?.durationS != null -> "hold"
                first?.targetRepsMax != null -> "range"
                else -> "fixed"
            },
            planned_sets = sets.coerceAtLeast(if (item.status == "planned") 1 else 0),
            planned_rep_min = first?.targetReps, planned_rep_max = first?.targetRepsMax ?: first?.targetReps,
            planned_rir = item.rirTarget?.let { Scaled.fromDouble(it.toDouble(), 1) },
            planned_load_input = (first?.pin ?: first?.addedKg)?.let { Scaled.toLong(it, 2) },
            planned_load_unit = if (first?.pin != null) "lb" else if (first?.addedKg != null) "kg" else null,
            planned_duration_s = (first?.targetHoldS ?: first?.durationS)?.let { Scaled.toLong(it, 1) },
            planned_ecc_s = first?.targetEccS?.let { Scaled.toLong(it, 1) },
            planned_rest_s = first?.restS,
        )
    }

    fun observeLive(sessionId: String): Flow<ChallengeLive?> = db.challengeLive().observe(sessionId).map { it?.let(::decodeLive) }

    suspend fun live(sessionId: String): ChallengeLive? = db.challengeLive().get(sessionId)?.let(::decodeLive)

    /**
     * After a set of a challenge item was logged (6.10): runs the item's in-session rules on its logged rows and keeps
     * the changed rows. Returns the applications for the rule line ("Rule: … · Undo") and notices.
     */
    suspend fun onSetLogged(sessionId: String, sessionExerciseId: String): List<RuleApplication> {
        val stored = db.challengeLive().get(sessionId) ?: return emptyList()
        val live = decodeLive(stored)
        val se = db.sessionExercises().get(sessionExerciseId) ?: return emptyList()
        val key = se.challenge_item_key ?: return emptyList()
        val item = live.items[key] ?: return emptyList()
        val f = challenges.folded(live.enrollmentId)
        val logged = db.setLogs().bySession(sessionId).filter { it.session_exercise_id == sessionExerciseId && it.deleted_at == null }
            .filter { item.plan.role == "warmup" || it.set_kind != "warmup" }.sortedBy { it.completed_at }.map(::rowLog)
        if (logged.isEmpty()) return emptyList()
        val r = InSessionRuleRunner.onRowLogged(f.def, params, f.state, facts.facts(f.def, f.state), live.plan.date, live.plan.slot, item.copy(logged = logged))
        val items = live.items + (key to r.item)
        val now = ctx.nowMs()
        db.withTransaction {
            db.challengeLive().upsert(stored.copy(live_json = JsonText.canonical(liveJson(items)), updated_at_ms = now))
            for (a in r.applications.filter { it.kind == "suggest" || it.kind == "capTarget" }) {
                telemetry.write(
                    EventDraft(
                        type = "challenge_rule_applied", sessionId = sessionId, entityType = "session_exercise", entityId = sessionExerciseId,
                        payload = mapOf(
                            "rule_id" to JsonPrimitive(a.ruleId), "rule_tag" to JsonPrimitive(a.ruleTag), "proposal_id" to (a.proposalId?.let(::JsonPrimitive) ?: JsonNull),
                            "session_exercise_id" to JsonPrimitive(sessionExerciseId), "field" to JsonPrimitive(a.field ?: ""),
                            "from" to JsonPrimitive(a.from ?: ""), "to" to JsonPrimitive(a.to ?: ""), "sets_affected" to JsonPrimitive(a.rowsAffected.size),
                        ),
                    ),
                )
            }
        }
        scheduler.request()
        return r.applications
    }

    /** Undo of an applied rule (D14, C46): the rule's change is taken back, the item is marked overridden with the reason. */
    suspend fun undo(sessionId: String, sessionExerciseId: String, app: RuleApplication, reasonCode: String, msSince: Long) {
        val stored = db.challengeLive().get(sessionId) ?: return
        val live = decodeLive(stored)
        val se = db.sessionExercises().get(sessionExerciseId) ?: return
        val key = se.challenge_item_key ?: return
        val item = live.items[key] ?: return
        val f = challenges.folded(live.enrollmentId)
        val undone = InSessionRuleRunner.undo(f.def, params, f.state, facts.facts(f.def, f.state), live.plan.date, item, app)
        val now = ctx.nowMs()
        db.withTransaction {
            db.challengeLive().upsert(stored.copy(live_json = JsonText.canonical(liveJson(live.items + (key to undone))), updated_at_ms = now))
            val row = se.copy(
                user_override = true, override_reason_code = reasonCode,
                override_fields = JsonText.canonical(JsonObject(mapOf((app.field ?: "rule") to JsonObject(mapOf("rule_id" to JsonPrimitive(app.ruleId), "from" to JsonPrimitive(app.to ?: ""), "to" to JsonPrimitive(app.from ?: "")))))),
                client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
            )
            db.sessionExercises().upsert(row)
            outbox.enqueue("session_exercises", row.id, now)
            telemetry.write(
                EventDraft(
                    type = "challenge_rule_undone", sessionId = sessionId, entityType = "session_exercise", entityId = sessionExerciseId,
                    payload = mapOf(
                        "rule_id" to JsonPrimitive(app.ruleId), "session_exercise_id" to JsonPrimitive(sessionExerciseId),
                        "reason_code" to JsonPrimitive(reasonCode), "ms_since" to JsonPrimitive(msSince),
                    ),
                ),
            )
        }
        scheduler.request()
    }

    /** Finish (6.12): `session.completed` with every item's logged rows; returns the engine's notifications. */
    suspend fun complete(sessionId: String): ChallengeOutcome? {
        val stored = db.challengeLive().get(sessionId) ?: return null
        val live = decodeLive(stored)
        val session = db.sessions().get(sessionId) ?: return null
        if (session.status != "finished") return null
        val exercises = db.sessionExercises().bySession(sessionId)
        val sets = db.setLogs().bySession(sessionId).filter { it.deleted_at == null }
        val pain = db.wellness().bySession(sessionId).any { it.kind == "pain" }
        val items = TreeMap<String, ItemLog>()
        for (item in live.plan.items) {
            val se = exercises.firstOrNull { it.challenge_item_key == item.key }
            val rows = se?.let { e -> sets.filter { it.session_exercise_id == e.id }.sortedBy { it.completed_at }.map(::rowLog) }.orEmpty()
            val current = live.items[item.key]?.rows ?: item.rows
            val plannedRows = current.count { it.setKind != "warmup" }
            val working = rows.count { it.setKind != "warmup" }
            val status = when {
                item.status != "planned" -> item.status
                se?.status == "skipped" -> "skipped"
                working == 0 && plannedRows > 0 -> "skipped"
                working < plannedRows -> "partial"
                else -> "done"
            }
            items[item.key] = ItemLog(
                status = status, plannedRows = plannedRows, targetReps = current.firstOrNull { it.setKind != "warmup" }?.targetReps,
                targetHoldS = current.firstOrNull { it.setKind != "warmup" }?.targetHoldS, rows = rows,
                statusReasonCode = se?.status_reason_code ?: item.statusReason, progressionExcluded = item.progressionExcluded,
            )
        }
        // A home mini-session reports the habit (UX 9.6): its rows feed the week's habit rows, not a slot.
        session.habit_id?.let { habitId ->
            return challenges.record(
                live.enrollmentId,
                ChallengeEventDraft(
                    EventTypes.HABIT_COMPLETED, EventPayload.HabitCompleted(habitId, items), Instant.ofEpochMilli(session.finished_at ?: ctx.nowMs()),
                    LocalDate.parse(session.local_date), sourceSessionId = sessionId,
                ),
            )
        }
        val log = SessionLog(
            sessionId = UUID.fromString(sessionId), slot = live.plan.slot, templateId = live.plan.templateId, kind = session.kind,
            localDate = LocalDate.parse(session.local_date),
            completionRatio = session.completion_ratio?.let { Scaled.toDecimal(it, 4) }?.let(Dec::norm) ?: BigDecimal.ZERO,
            painReported = pain,
            durationMin = session.finished_at?.let { end -> session.started_at?.let { BigDecimal((end - it) / 60_000) } },
            items = items,
        )
        return challenges.record(
            live.enrollmentId,
            ChallengeEventDraft(
                EventTypes.SESSION_COMPLETED, EventPayload.Session(log), Instant.ofEpochMilli(session.finished_at ?: ctx.nowMs()),
                LocalDate.parse(session.local_date), sourceSessionId = sessionId,
            ),
        )
    }

    /**
     * The elbow check of a template with `requiresReadiness: ["elbow"]` (CHALLENGE_ENGINE 11, UX 16.3): the wellness
     * log and `readiness.checked` with the score (0-3) and the symptom kinds asked at 2 or more. The safety rules run on
     * it at once (a stop signal enters the elbow protocol before the session is materialized).
     */
    suspend fun elbowCheck(enrollmentId: String, score: Int, symptoms: List<String>, sessionId: String? = null): ChallengeOutcome {
        val now = ctx.nowMs()
        val zone = ctx.zone()
        val id = ctx.ids.next()
        db.withTransaction {
            val w = dev.fitnessapp.data.db.entity.WellnessLogEntity(
                id = id, user_id = ctx.requireUser(), client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                kind = "elbow_check", phase = "pre", observed_at = now, local_date = Week.localDate(Instant.ofEpochMilli(now), zone).toString(),
                session_id = sessionId, elbow_score = score, symptom_codes = symptoms.distinct().sorted(),
            )
            db.wellness().upsert(w)
            outbox.enqueue("wellness_logs", id, now)
            telemetry.write(
                EventDraft(
                    type = "prompt_answered", sessionId = sessionId, entityType = "wellness_log", entityId = id,
                    payload = mapOf("prompt" to JsonPrimitive("elbow_check"), "value_code" to JsonPrimitive(score.toString()), "wellness_log_id" to JsonPrimitive(id)),
                ),
            )
        }
        return challenges.record(
            enrollmentId,
            ChallengeEventDraft(
                EventTypes.READINESS, EventPayload.Readiness(score, symptoms.distinct().sorted()), Instant.ofEpochMilli(now),
                Week.localDate(Instant.ofEpochMilli(now), zone), sourceSessionId = sessionId, sourceWellnessLogId = id,
            ),
        )
    }

    /** A pain report reaches every running challenge as `pain.reported` (C33): the safety rules decide. */
    suspend fun forwardPain(wellnessLogId: String, region: String, side: String, severity: String, symptoms: List<String>, sessionId: String?): List<ChallengeOutcome> {
        val now = ctx.nowMs()
        val date = Week.localDate(Instant.ofEpochMilli(now), ctx.zone())
        val score = when (severity) {
            "mild" -> 2
            "moderate" -> 5
            else -> 8
        }
        return challenges.open().filter { it.status == "active" || it.status == "paused" }.map { e ->
            challenges.record(
                e.id,
                ChallengeEventDraft(
                    EventTypes.PAIN, EventPayload.Pain(region, side, symptoms.distinct().sorted(), severity, score), Instant.ofEpochMilli(now), date,
                    sourceSessionId = sessionId, sourceWellnessLogId = wellnessLogId,
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------------------ mapping

    /** A logged set under its `set_logs` meaning (6.12). */
    fun rowLog(s: SetLogEntity): RowLog = RowLog(
        side = s.side, setKind = s.set_kind, setIndex = s.set_index, reps = s.reps,
        rirReported = s.rir_reported?.let { Dec.norm(Scaled.toDecimal(it, 1)) },
        addedKg = s.added_kg?.let { Dec.norm(Scaled.toDecimal(it, 2)) },
        assistPinNative = s.assist_pin_native?.let { Dec.norm(Scaled.toDecimal(it, 2)) },
        assistKg = s.assist_kg?.let { Dec.norm(Scaled.toDecimal(it, 2)) },
        assistOrdinal = s.assist_ordinal,
        durationS = s.duration_s?.let { Dec.norm(Scaled.toDecimal(it, 1)) },
        holdReserveS = s.hold_reserve_s?.let { Dec.norm(Scaled.toDecimal(it, 1)) },
        eccSActual = s.ecc_s_actual?.let { Dec.norm(Scaled.toDecimal(it, 1)) },
        flags = s.flags.sorted(), deviationReasonCode = s.deviation_reason_code,
    )

    private fun liveJson(items: Map<String, LiveItem>): JsonObject = JsonObject(
        items.toSortedMap().mapValues { (_, l) ->
            JsonObject(
                mapOf(
                    "rows" to PlanJson.rows(l.rows),
                    "applied" to JsonArray(l.appliedRules.sorted().map(::JsonPrimitive)),
                    "undone" to JsonArray(l.undoneRules.sorted().map(::JsonPrimitive)),
                    "logged" to JsonPrimitive(l.logged.size),
                ),
            )
        },
    )

    private fun decodeLive(e: ChallengeLiveSessionEntity): ChallengeLive {
        val plan = PlanJson.decode(Json.parseToJsonElement(e.plan_json).jsonObject)
        val live = Json.parseToJsonElement(e.live_json).jsonObject
        val items = plan.items.associate { item ->
            val o = live[item.key]?.jsonObject
            val rows = o?.get("rows")?.jsonArray?.let(PlanJson::decodeRows) ?: item.rows
            fun set(k: String): Set<String> = (o?.get(k) as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            item.key to LiveItem(item, rows, emptyList(), set("applied"), set("undone"))
        }
        return ChallengeLive(e.enrollment_id, plan, items)
    }

    /** The planned row the logger shows for a slot, or null when the item has no row there. */
    fun plannedRow(live: ChallengeLive, key: String, setIndex: Int, side: String, warmup: Boolean): PlannedRow? {
        val rows = live.items[key]?.rows ?: return null
        val kind = { r: PlannedRow -> (r.setKind == "warmup") == warmup || live.items[key]?.plan?.role == "warmup" }
        return rows.firstOrNull { it.setIndex == setIndex && (it.side == side || it.side == "both" || it.side == "none") && kind(it) }
    }

    private companion object {
        val DELOAD_MODES = setOf("deload", "test_pending", "extra_deload")

        /** `session_exercises` requires a reason code on skipped items; excluded challenge items carry the generic one. */
        const val STATUS_REASON = "other"
    }
}
