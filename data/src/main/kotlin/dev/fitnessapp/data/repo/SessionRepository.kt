package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.materialize.PlanJson
import dev.fitnessapp.algo.core.bodyweight.BwSession
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.dao.SetWithSessionStart
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** One exercise of a block built by the L6 pipeline (D62), in ORD-1 order, with its set count. */
data class BuiltItem(val exercise: CatalogExercise, val sets: Int)

/** The rest columns of the set that follows a rest (DATA_MODEL 9.6.4, C55), filled by the rest timer. */
data class RestColumns(
    val plannedS: Int?,
    val adjustS: Int?,
    val overridden: Boolean,
    val beforeS: Double?,
    val source: String?,
    val endReason: String?,
)

/** The planned values shown on the card when the set was logged (`planned_*`, `planned_source`). */
data class PlannedValues(
    val reps: Int? = null,
    val inputValue: Double? = null,
    val inputUnit: String? = null,
    val addedKg: Double? = null,
    val assistNative: Double? = null,
    val effectiveKg: Double? = null,
    val durationS: Double? = null,
    val source: String,
    /** The planned lowering of an eccentric-only set (UX 11.7.5) and the planned fingers of finger assistance. */
    val eccS: Double? = null,
    val assistOrdinal: Int? = null,
    /** The target RIR and rep range of an engine prescription (RX-1), which RIRCAL-1 and the review read back. */
    val rir: Double? = null,
    val repMin: Int? = null,
    val repMax: Int? = null,
)

/** Everything one RIR / felt / Done tap stores (UX 11.8). [load] is the LOAD-1 point of the logged load. */
data class LogSetCommand(
    val sessionId: String,
    val sessionExerciseId: String,
    val exerciseId: String,
    val setIndex: Int,
    val side: String,
    val setKind: String,
    val load: LoadPoint?,
    val reps: Int?,
    val durationS: Double?,
    val rir: Double?,
    val rirLowerBound: Boolean,
    val holdReserveS: Double?,
    val holdReserveLowerBound: Boolean,
    val flags: List<String>,
    val deviationReason: String?,
    val planned: PlannedValues?,
    val rest: RestColumns?,
    val startedAtMs: Long?,
    val completedAtMs: Long,
    /** The `set_logged` payload fields owned by the UI (prefill source, deviation, input method, ...). */
    val telemetry: Map<String, JsonElement>,
    /** Top-hold rounds (UX 11.7.4): one row per attempt, `set_index` = `round_index` = the round. */
    val roundIndex: Int? = null,
    val attemptIndex: Int? = null,
    /** Eccentric-only sets (UX 11.7.5, C66): the shortest lowering of the set and its target. */
    val eccSActual: Double? = null,
    /** An explicit RIR check (F14, B4.3): the reps done after the chip, to technical failure (`set_kind` `calibration`). */
    val calibExtraReps: Int? = null,
    val eccSTarget: Double? = null,
    /** `assisted_ordinal` (fingers of assistance, 0-4): the load point is null and this is the load. */
    val assistOrdinal: Int? = null,
    /** A challenge warm-up item: its warm-up sets count toward its planned sets, so it completes. */
    val countsAsWork: Boolean = false,
    /** E5 `rir_source` when not tapped: `protocol` for a test-day 3RM or max-reps set entered with Record (C63). */
    val rirSource: String? = null,
)

/**
 * Sessions, exercises and sets (DATA_MODEL 9.6, UX 11). Every user action is one Room transaction that writes
 * the domain rows, the `session_events` row and the outbox entries (ANDROID.md 7.3, 8.4); a sync is requested
 * after the commit. D22: in M1a every session starts empty and exercises are added from the catalog.
 */
class SessionRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val equipment: EquipmentRepository,
    private val bodyweight: BodyweightRepository,
    private val profiles: ProfileRepository,
) {
    fun observeActive(): Flow<WorkoutSessionEntity?> = db.sessions().observeActive()
    fun observeSession(id: String): Flow<WorkoutSessionEntity?> = db.sessions().observe(id)
    fun observeExercises(sessionId: String): Flow<List<SessionExerciseEntity>> = db.sessionExercises().observeBySession(sessionId)
    fun observeSets(sessionId: String): Flow<List<SetLogEntity>> = db.setLogs().observeBySession(sessionId)
    fun observeRecent(limit: Int): Flow<List<WorkoutSessionEntity>> = db.sessions().observeRecent(limit)
    fun observeExerciseHistory(exerciseId: String, limit: Int): Flow<List<SetWithSessionStart>> = db.setLogs().observeHistoryForExercise(exerciseId, limit)
    fun observeUsageSince(sinceDate: String): Flow<List<dev.fitnessapp.data.db.dao.ExerciseUsage>> = db.setLogs().observeUsageSince(sinceDate)

    suspend fun session(id: String): WorkoutSessionEntity? = db.sessions().get(id)
    suspend fun observeActiveOnce(): WorkoutSessionEntity? = db.sessions().active()
    suspend fun sets(sessionId: String): List<SetLogEntity> = db.setLogs().bySession(sessionId)
    suspend fun exercises(sessionId: String): List<SessionExerciseEntity> = db.sessionExercises().bySession(sessionId)

    /** Earlier sets of an exercise, newest session first (the "last time" source of UI-04). */
    suspend fun history(exerciseId: String, excludeSessionId: String, limit: Int = 60): List<SetWithSessionStart> =
        db.setLogs().historyForExercise(exerciseId, excludeSessionId, limit)

    // ------------------------------------------------------------------------------------------ lifecycle

    /**
     * Starts a general session (D22) with the BW-1 snapshot and the pinned params hash (A8.5): empty, or a repeat of the
     * finished general session [repeatOf] (D49), whose exercises, order, load modes, sides, equipment and planned set
     * counts are copied. Loads and reps come from RX-1 when the live session asks for them, not from the copy.
     */
    suspend fun start(params: ParamView, repeatOf: String? = null): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val zone = ctx.zone()
        val date = Week.localDate(Instant.ofEpochMilli(now), zone)
        val id = ctx.ids.next()
        val profile = profiles.get()
        val snap = bodyweight.snapshot(
            BwSession(id, "gym", Instant.ofEpochMilli(now), date),
            profile?.bodyweight_fallback_kg?.let { Scaled.toDouble(it, 2) }, params,
        )
        val gym = equipment.defaultGym()
        val source = repeatOf?.let { db.sessions().get(it) }?.takeIf { it.status == "finished" && it.enrollment_id == null && it.deleted_at == null }
        val copied = source?.let { src -> db.sessionExercises().bySession(src.id).filter { it.status != "removed" } }.orEmpty()
        val templateSource = if (source != null) "repeat" else "empty"
        db.withTransaction {
            val s = WorkoutSessionEntity(
                id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, kind = "gym", status = "in_progress", template_source = templateSource,
                local_date = date.toString(), timezone = zone.id, iso_week = Week.isoWeek(date), gym_id = gym?.id,
                started_at = now, bodyweight_kg = snap.valueKg?.let { Scaled.fromDouble(it, 2) },
                bodyweight_source = snap.source.code, bodyweight_stale = snap.stale,
                time_budget_min = profile?.default_session_min, time_budget_source = "default",
                engine_version = EngineVersion.ENGINE_VERSION, params_hash = params.hash, catalog_version = catalog.version,
            )
            db.sessions().upsert(s)
            outbox.enqueue("workout_sessions", id, now)
            val block = SessionBlockEntity(
                id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, session_id = id, position = 0, block_kind = "general", origin = "user_built",
                status = "active",
            )
            db.blocks().upsert(block)
            outbox.enqueue("session_blocks", block.id, now)
            copied.forEachIndexed { i, from ->
                val row = SessionExerciseEntity(
                    id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                    app_version = ctx.appVersion, session_id = id, block_id = block.id, exercise_id = from.exercise_id,
                    position = (i + 1L) * POSITION_STEP, origin = "plan", load_mode = from.load_mode, side_mode = from.side_mode,
                    side_order = from.side_order, equipment_id = from.equipment_id, progression_mode = "none", rep_scheme = from.rep_scheme,
                    planned_sets = from.planned_sets ?: DEFAULT_SETS,
                )
                db.sessionExercises().upsert(row)
                outbox.enqueue("session_exercises", row.id, now)
            }
            telemetry.write(
                EventDraft(
                    type = "session_started", sessionId = id, entityType = "session", entityId = id,
                    payload = mapOf(
                        "kind" to JsonPrimitive("gym"), "template_source" to JsonPrimitive(templateSource),
                        "source_session_id" to (source?.id?.let { JsonPrimitive(it) } ?: JsonNull),
                        "families" to JsonArray(emptyList()), "started_from" to JsonPrimitive("today"),
                        "budget_min" to (profile?.default_session_min?.let { JsonPrimitive(it) } ?: JsonNull),
                        "budget_source" to JsonPrimitive("default"),
                        "params_hash" to JsonPrimitive(params.hash), "engine_version" to JsonPrimitive(EngineVersion.ENGINE_VERSION),
                        "catalog_version" to JsonPrimitive(catalog.version),
                    ),
                ),
            )
        }
        scheduler.request()
        return id
    }

    /**
     * Starts a general session built for a session type (D62): `template_source = family_combo`, one `standalone` general
     * block with [items] in ORD-1 order and their set counts, the session budget from the time chip ([budgetSource]
     * `chip`, `calendar` or `default`) and the [families] that receive sets. Loads and reps come from RX-1 in the live
     * session, as for any general exercise. Returns the session id and the block id.
     */
    suspend fun startBuilt(params: ParamView, items: List<BuiltItem>, families: List<String>, budgetMin: Int, budgetSource: String): Pair<String, String> {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val zone = ctx.zone()
        val date = Week.localDate(Instant.ofEpochMilli(now), zone)
        val id = ctx.ids.next()
        val blockId = ctx.ids.next()
        val profile = profiles.get()
        val snap = bodyweight.snapshot(
            BwSession(id, "gym", Instant.ofEpochMilli(now), date),
            profile?.bodyweight_fallback_kg?.let { Scaled.toDouble(it, 2) }, params,
        )
        val gym = equipment.defaultGym()
        db.withTransaction {
            val s = WorkoutSessionEntity(
                id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, kind = "gym", status = "in_progress", template_source = "family_combo",
                local_date = date.toString(), timezone = zone.id, iso_week = Week.isoWeek(date), gym_id = gym?.id,
                started_at = now, bodyweight_kg = snap.valueKg?.let { Scaled.fromDouble(it, 2) },
                bodyweight_source = snap.source.code, bodyweight_stale = snap.stale,
                time_budget_min = budgetMin, time_budget_source = budgetSource,
                engine_version = EngineVersion.ENGINE_VERSION, params_hash = params.hash, catalog_version = catalog.version,
            )
            db.sessions().upsert(s)
            outbox.enqueue("workout_sessions", id, now)
            val block = SessionBlockEntity(
                id = blockId, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, session_id = id, position = 0, block_kind = "general", origin = "standalone",
                budget_min = budgetMin, status = "active",
            )
            db.blocks().upsert(block)
            outbox.enqueue("session_blocks", block.id, now)
            items.forEachIndexed { i, it -> insertPlanned(uid, id, block.id, it, (i + 1L) * POSITION_STEP, now) }
            telemetry.write(
                EventDraft(
                    type = "session_started", sessionId = id, entityType = "session", entityId = id,
                    payload = mapOf(
                        "kind" to JsonPrimitive("gym"), "template_source" to JsonPrimitive("family_combo"),
                        "source_session_id" to JsonNull, "families" to JsonArray(families.map { JsonPrimitive(it) }),
                        "started_from" to JsonPrimitive("today"), "budget_min" to JsonPrimitive(budgetMin),
                        "budget_source" to JsonPrimitive(budgetSource),
                        "params_hash" to JsonPrimitive(params.hash), "engine_version" to JsonPrimitive(EngineVersion.ENGINE_VERSION),
                        "catalog_version" to JsonPrimitive(catalog.version),
                    ),
                ),
            )
        }
        scheduler.request()
        return id to blockId
    }

    /**
     * Adds the add-on block after a challenge session's challenge block (C34, D62): `block_kind = general`, `origin =
     * addon`, `status = planned`, its [items] after every existing row. Returns the block id.
     */
    suspend fun addAddOnBlock(sessionId: String, items: List<BuiltItem>, budgetMin: Int): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val id = ctx.ids.next()
        db.withTransaction {
            val position = (db.blocks().bySession(sessionId).maxOfOrNull { it.position } ?: 0) + 1
            writeBlock(
                SessionBlockEntity(
                    id = id, user_id = uid, client_updated_at = 0, device_id = "", app_version = "", session_id = sessionId,
                    position = position, block_kind = "general", origin = "addon", budget_min = budgetMin, status = "planned",
                ),
            )
            val start = (db.sessionExercises().bySession(sessionId).maxOfOrNull { it.position } ?: 0L) / POSITION_STEP + 1
            items.forEachIndexed { i, it -> insertPlanned(uid, sessionId, id, it, (start + i) * POSITION_STEP, now) }
        }
        scheduler.request()
        return id
    }

    /**
     * Rebuilds an add-on block (D62: a family change, or the live capacity once the challenge block is done): its rows
     * without logged sets are removed with [reason] (a `reason_codes` key: `other` for a family change, `no_time` for the
     * capacity) and [items] follow the rows that stay; the block takes [status] (`active` after the live rebuild, so it
     * runs once). Returns false when the block has started.
     */
    suspend fun rebuildBlock(blockId: String, sessionId: String, items: List<BuiltItem>, budgetMin: Int, reason: String, status: String = "planned"): Boolean {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        var ok = false
        db.withTransaction {
            val block = db.blocks().bySession(sessionId).firstOrNull { it.id == blockId } ?: return@withTransaction
            val sets = db.setLogs().bySession(sessionId)
            val rows = db.sessionExercises().bySession(sessionId).filter { it.block_id == blockId && it.status != "removed" }
            if (rows.any { r -> sets.any { it.session_exercise_id == r.id } }) return@withTransaction
            rows.forEach { writeExercise(it.copy(status = "removed", status_reason_code = reason)) }
            writeBlock(block.copy(budget_min = budgetMin, status = status))
            val start = (db.sessionExercises().bySession(sessionId).filter { it.status != "removed" }.maxOfOrNull { it.position } ?: 0L) / POSITION_STEP + 1
            items.forEachIndexed { i, it -> insertPlanned(uid, sessionId, blockId, it, (start + i) * POSITION_STEP, now) }
            ok = true
        }
        scheduler.request()
        return ok
    }

    /** Skip on the add-on header (UX 11.4, D62): the block and its open rows are skipped with [reason]. */
    suspend fun skipBlock(blockId: String, sessionId: String, reason: String) {
        db.withTransaction {
            val block = db.blocks().bySession(sessionId).firstOrNull { it.id == blockId } ?: return@withTransaction
            val open = db.sessionExercises().bySession(sessionId).filter { it.block_id == blockId && it.status in setOf("pending", "deferred", "active") }
            open.forEach { writeExercise(it.copy(status = "skipped", status_reason_code = reason, completed_at = ctx.nowMs())) }
            writeBlock(block.copy(status = "skipped"))
            telemetry.write(
                EventDraft(
                    type = "block_addon_skipped", sessionId = sessionId, entityType = "block", entityId = blockId,
                    payload = mapOf(
                        "block_id" to JsonPrimitive(blockId), "reason_code" to JsonPrimitive(reason), "exercises_skipped" to JsonPrimitive(open.size),
                    ),
                ),
            )
        }
        scheduler.request()
    }

    /**
     * UI-02 trimming of the remaining work (UX 11.3, D62): [changes] maps a session exercise to its new planned sets, or
     * null when it is left out (skipped, or ended when it has sets, reason `no_time`); logs `session_trimmed`.
     */
    suspend fun trimPlanned(sessionId: String, changes: Map<String, Int?>, budgetMin: Int, coreDoesNotFit: Boolean) {
        db.withTransaction {
            val sets = db.setLogs().bySession(sessionId)
            for ((id, n) in changes) {
                val e = db.sessionExercises().get(id) ?: continue
                val done = sets.count { it.session_exercise_id == id && it.set_kind != "warmup" }
                when {
                    n != null -> writeExercise(e.copy(planned_sets = n))
                    done > 0 -> writeExercise(e.copy(status = "done", status_reason_code = "no_time", completed_at = ctx.nowMs()))
                    else -> writeExercise(e.copy(status = "skipped", status_reason_code = "no_time", completed_at = ctx.nowMs()))
                }
            }
            telemetry.write(
                EventDraft(
                    type = "session_trimmed", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf(
                        "block_id" to JsonNull, "budget_min" to JsonPrimitive(budgetMin),
                        "trimmed" to JsonArray(changes.map { (id, n) -> kotlinx.serialization.json.buildJsonObject { put("session_exercise_id", JsonPrimitive(id)); put("planned_sets", n?.let { JsonPrimitive(it) } ?: JsonNull) } }),
                        "core_does_not_fit" to JsonPrimitive(coreDoesNotFit),
                    ),
                ),
            )
        }
        scheduler.request()
    }

    /** The session's time budget from the chips (UX 9.4, 11.3): stored with its source for TIME-1's calibration. */
    suspend fun setTimeBudget(sessionId: String, minutes: Int, source: String) {
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            writeSession(s.copy(time_budget_min = minutes, time_budget_source = source))
        }
        scheduler.request()
    }

    /** A planned row of a built block (D62), shaped like an added exercise (side mode, scheme, equipment). */
    private suspend fun insertPlanned(uid: String, sessionId: String, blockId: String, item: BuiltItem, position: Long, now: Long) {
        val e = item.exercise
        val mode = LoadMode.of(e.loadMode)
        val row = SessionExerciseEntity(
            id = ctx.ids.next(), user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
            app_version = ctx.appVersion, session_id = sessionId, block_id = blockId, exercise_id = catalog.idOf(e),
            position = position, origin = "plan", load_mode = mode.code,
            side_mode = when (e.laterality) {
                "unilateral" -> "unilateral_sequential"
                "alternating" -> "unilateral_alternating"
                else -> "bilateral"
            },
            side_order = if (e.laterality == "bilateral") null else "LR",
            equipment_id = equipment.resolve(e, mode).row?.id, progression_mode = "none",
            rep_scheme = when (e.measure) { "duration" -> "hold"; "attempt" -> "attempt"; else -> "range" },
            planned_sets = item.sets,
        )
        db.sessionExercises().upsert(row)
        outbox.enqueue("session_exercises", row.id, now)
    }

    /** "Repeat" (D49): the finished general sessions with at least one exercise, newest first. */
    suspend fun repeatable(limit: Int): List<WorkoutSessionEntity> =
        db.sessions().finishedChronological().asReversed()
            .filter { it.enrollment_id == null && it.kind == "gym" && db.sessionExercises().bySession(it.id).any { e -> e.status != "removed" } }
            .take(limit)

    /** Finishes the session; pending exercises become `not_done` (UX 11.14). */
    suspend fun finish(sessionId: String, endReason: String?) {
        val now = ctx.nowMs()
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            val exs = db.sessionExercises().bySession(sessionId)
            val sets = db.setLogs().bySession(sessionId)
            // A challenge warm-up item (role `warmup`) plans and logs warm-up sets only: neither planned nor done work.
            val warmupItems = warmupItemKeys(sessionId)
            val working = sets.filter { it.set_kind != "warmup" }
            // The attempts of a top-hold round share their set index: a round counts once per side.
            val doneUnits = working.distinctBy { Triple(it.session_exercise_id, it.set_index, it.side) }
                .sumOf { if (it.side == "both") 2 else 1 } / 2.0
            val planned = exs.filter { it.status != "removed" && it.challenge_item_key !in warmupItems }.sumOf { it.planned_sets ?: 0 }
            val notDone = exs.filter { it.status in setOf("pending", "active", "deferred") }
            for (e in notDone) {
                val done = sets.count { it.session_exercise_id == e.id && (it.set_kind != "warmup" || e.challenge_item_key in warmupItems) }
                val status = if (done > 0) "done" else "not_done"
                writeExercise(e.copy(status = status, completed_at = if (done > 0) now else e.completed_at))
            }
            val ratio = if (planned > 0) minOf(1.0, doneUnits / planned) else null
            for (b in db.blocks().bySession(sessionId)) {
                if (exs.none { it.block_id == b.id }) continue
                writeBlock(b.copy(status = "done"))
                blockEvent("block_finished", sessionId, b.id, "done")
            }
            writeSession(
                s.copy(
                    status = "finished", finished_at = now, end_reason_code = if (notDone.isNotEmpty()) endReason else null,
                    completion_ratio = ratio?.let { Scaled.fromDouble(it, 4) },
                ),
            )
            telemetry.write(
                EventDraft(
                    type = "session_finished", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf(
                        "duration_s" to JsonPrimitive(((now - (s.started_at ?: now)) / 1000)),
                        "working_sets_done" to JsonPrimitive(doneUnits),
                        "working_sets_planned" to JsonPrimitive(planned),
                        "exercises_not_done" to JsonPrimitive(notDone.size),
                        "not_done_reason_codes" to JsonArray(if (notDone.isNotEmpty() && endReason != null) listOf(JsonPrimitive(endReason)) else emptyList()),
                        "outbox_depth" to JsonPrimitive(db.outbox().depth()),
                    ),
                ),
            )
        }
        scheduler.expedited()
    }

    suspend fun abandon(sessionId: String) {
        val now = ctx.nowMs()
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            writeSession(s.copy(status = "abandoned", finished_at = now))
            val working = db.setLogs().bySession(sessionId).count { it.set_kind != "warmup" }
            telemetry.write(
                EventDraft(
                    type = "session_abandoned", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf("working_sets_done" to JsonPrimitive(working)),
                ),
            )
        }
        scheduler.request()
    }

    suspend fun undoAbandon(sessionId: String, msSince: Long) {
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            writeSession(s.copy(status = "in_progress", finished_at = null))
            telemetry.write(EventDraft(type = "session_abandon_undone", sessionId = sessionId, entityType = "session", entityId = sessionId, payload = mapOf("ms_since" to JsonPrimitive(msSince))))
        }
        scheduler.request()
    }

    /** Summary quick check (UX 12.3): session effort and an optional note. */
    suspend fun saveFeedback(sessionId: String, srpe: Int?, note: String?) {
        val now = ctx.nowMs()
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            if (note != null && note != s.notes) writeSession(s.copy(notes = note.take(2000)))
            if (srpe != null) {
                val w = wellness(sessionId, "srpe", "post").copy(srpe = srpe)
                db.wellness().upsert(w)
                outbox.enqueue("wellness_logs", w.id, now)
                telemetry.write(
                    EventDraft(
                        type = "prompt_answered", sessionId = sessionId, entityType = "wellness_log", entityId = w.id,
                        payload = mapOf("prompt" to JsonPrimitive("srpe"), "value_code" to JsonPrimitive(srpe.toString()), "wellness_log_id" to JsonPrimitive(w.id)),
                    ),
                )
            }
        }
        scheduler.request()
    }

    /** A pain report (C33, C64): severity chips map to 2 / 5 / 8 on the 0-10 scale. */
    suspend fun reportPain(
        sessionId: String?,
        sessionExerciseId: String?,
        region: String,
        side: String,
        severity: String,
        phase: String,
        context: String,
        symptoms: List<String> = emptyList(),
    ): String {
        val now = ctx.nowMs()
        val score = when (severity) { "mild" -> 2; "moderate" -> 5; else -> 8 }
        lateinit var id: String
        db.withTransaction {
            val w = wellness(sessionId, "pain", phase).copy(
                session_exercise_id = sessionExerciseId, pain_body_region = region, pain_side = side,
                pain_severity = severity, pain_score = score, symptom_codes = symptoms.distinct().sorted(),
            )
            id = w.id
            db.wellness().upsert(w)
            outbox.enqueue("wellness_logs", w.id, now)
            telemetry.write(
                EventDraft(
                    type = "pain_reported", sessionId = sessionId, entityType = "wellness_log", entityId = w.id,
                    payload = mapOf(
                        "wellness_log_id" to JsonPrimitive(w.id), "body_region" to JsonPrimitive(region), "side" to JsonPrimitive(side),
                        "severity" to JsonPrimitive(severity), "pain_score" to JsonPrimitive(score),
                        "symptom_codes" to JsonArray(symptoms.distinct().sorted().map(::JsonPrimitive)), "context" to JsonPrimitive(context),
                    ),
                ),
            )
        }
        scheduler.request()
        return id
    }

    // ------------------------------------------------------------------------------------------ exercises

    /** Adds a catalog exercise at the end (UX 16.6); planned sets = last time's working sets, else 3. */
    suspend fun addExercise(sessionId: String, e: CatalogExercise, mode: LoadMode = LoadMode.of(e.loadMode)): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val exerciseId = catalog.idOf(e)
        val resolved = equipment.resolve(e, mode)
        val lastSets = history(exerciseId, sessionId).let { h ->
            val last = h.firstOrNull()?.set?.session_id
            h.filter { it.set.session_id == last && it.set.set_kind != "warmup" }.map { it.set.set_index }.distinct().size
        }
        lateinit var id: String
        db.withTransaction {
            val block = db.blocks().bySession(sessionId).first()
            val existing = db.sessionExercises().bySession(sessionId)
            val position = ((existing.maxOfOrNull { it.position } ?: 0L) / POSITION_STEP + 1) * POSITION_STEP
            id = ctx.ids.next()
            val row = SessionExerciseEntity(
                id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, session_id = sessionId, block_id = block.id, exercise_id = exerciseId,
                position = position, origin = "user_added", load_mode = mode.code,
                side_mode = when (e.laterality) {
                    "unilateral" -> "unilateral_sequential"
                    "alternating" -> "unilateral_alternating"
                    else -> "bilateral"
                },
                side_order = if (e.laterality == "bilateral") null else "LR",
                equipment_id = resolved.row?.id, progression_mode = "none",
                rep_scheme = when (e.measure) { "duration" -> "hold"; "attempt" -> "attempt"; else -> "range" },
                planned_sets = if (lastSets > 0) lastSets else DEFAULT_SETS,
            )
            db.sessionExercises().upsert(row)
            outbox.enqueue("session_exercises", id, now)
            telemetry.write(
                EventDraft(
                    type = "exercise_added", sessionId = sessionId, entityType = "session_exercise", entityId = id,
                    payload = mapOf(
                        "exercise_id" to JsonPrimitive(exerciseId), "source" to JsonPrimitive("catalog"),
                        "position" to JsonPrimitive(existing.size + 1), "block_kind" to JsonPrimitive("general"),
                    ),
                ),
            )
        }
        scheduler.request()
        return id
    }

    /** The Swap sheet opened (UX 16.5): its candidates with their scores and what opened it. */
    suspend fun swapSheetShown(sessionExerciseId: String, candidates: List<Pair<String, Double>>, trigger: String) = exerciseEvent(sessionExerciseId) { e, _ ->
        EventDraft(
            type = "swap_sheet_shown", payload = mapOf(
                "from_exercise_id" to JsonPrimitive(e.exercise_id), "trigger_reason" to JsonPrimitive(trigger),
                "candidates" to JsonArray(candidates.map { (id, score) -> kotlinx.serialization.json.buildJsonObject { put("exercise_id", JsonPrimitive(id)); put("score", JsonPrimitive(score)) } }),
            ),
        )
    }

    /**
     * Swap (SUB-1, UX 16.5): a new row with origin `substitute` takes the place and planned sets of [sessionExerciseId],
     * which becomes `removed` with the reason (`user` when the menu swap gave none). Only before any working set.
     * Returns the new row's id and the event id for Undo.
     */
    suspend fun swap(
        sessionExerciseId: String, to: CatalogExercise, reason: String?, rank: Int?, similarity: Double?, via: String,
    ): Pair<String, String>? {
        val uid = ctx.requireUser()
        var out: Pair<String, String>? = null
        db.withTransaction {
            val old = db.sessionExercises().get(sessionExerciseId) ?: return@withTransaction
            val sets = db.setLogs().bySession(old.session_id).count { it.session_exercise_id == old.id && it.set_kind != "warmup" }
            if (sets > 0 || old.challenge_item_key != null) return@withTransaction
            val mode = LoadMode.of(to.loadMode)
            val resolved = equipment.resolve(to, mode)
            val id = ctx.ids.next()
            val code = reason ?: "other"
            writeExercise(old.copy(status = "removed", status_reason_code = code, completed_at = ctx.nowMs()))
            writeExercise(
                SessionExerciseEntity(
                    id = id, user_id = uid, client_updated_at = 0, device_id = "", app_version = "", session_id = old.session_id,
                    block_id = old.block_id, exercise_id = catalog.idOf(to), position = old.position, origin = "substitute",
                    substitutes_id = old.id, substitution_reason_code = code, load_mode = mode.code,
                    side_mode = when (to.laterality) {
                        "unilateral" -> "unilateral_sequential"
                        "alternating" -> "unilateral_alternating"
                        else -> "bilateral"
                    },
                    side_order = if (to.laterality == "bilateral") null else "LR",
                    equipment_id = resolved.row?.id, progression_mode = "none",
                    rep_scheme = when (to.measure) { "duration" -> "hold"; "attempt" -> "attempt"; else -> "range" },
                    planned_sets = old.planned_sets ?: DEFAULT_SETS, superset_group = old.superset_group,
                ),
            )
            val payload = buildMap {
                put("from_exercise_id", JsonPrimitive(old.exercise_id))
                put("to_exercise_id", JsonPrimitive(catalog.idOf(to)))
                rank?.let { put("rank", JsonPrimitive(it)) }
                similarity?.let { put("similarity", JsonPrimitive(it)) }
                put("via", JsonPrimitive(via))
                reason?.let { put("reason_code", JsonPrimitive(it)) }
            }
            val eventId = telemetry.write(EventDraft(type = "exercise_swapped", sessionId = old.session_id, entityType = "session_exercise", entityId = id, payload = payload))
            out = id to (eventId ?: "")
        }
        scheduler.request()
        return out
    }

    /** Undo of a swap (P6): the substitute goes (soft delete, it has no sets yet) and the original is pending again. */
    suspend fun undoSwap(newId: String, eventId: String, msSince: Long) {
        db.withTransaction {
            val new = db.sessionExercises().get(newId) ?: return@withTransaction
            val old = new.substitutes_id?.let { db.sessionExercises().get(it) } ?: return@withTransaction
            if (db.setLogs().bySession(new.session_id).any { it.session_exercise_id == new.id }) return@withTransaction
            writeExercise(new.copy(deleted_at = ctx.nowMs()))
            writeExercise(old.copy(status = "pending", status_reason_code = null, completed_at = null))
            telemetry.write(
                EventDraft(
                    type = "action_undone", sessionId = new.session_id, entityType = "session_exercise", entityId = newId,
                    payload = mapOf(
                        "undone_event_type" to JsonPrimitive("exercise_swapped"), "undone_event_id" to JsonPrimitive(eventId), "ms_since" to JsonPrimitive(msSince),
                    ),
                ),
            )
        }
        scheduler.request()
    }

    /** Switches the load mode of an exercise (for example "+ add weight" on a bodyweight exercise). */
    suspend fun setLoadMode(sessionExerciseId: String, mode: LoadMode) {
        db.withTransaction {
            val e = db.sessionExercises().get(sessionExerciseId) ?: return@withTransaction
            val ex = catalog.byId(e.exercise_id) ?: return@withTransaction
            val resolved = equipment.resolve(ex, mode)
            writeExercise(e.copy(load_mode = mode.code, equipment_id = resolved.row?.id))
        }
        scheduler.request()
    }

    suspend fun skip(sessionExerciseId: String, reason: String, chipPosition: Int) = exerciseEvent(sessionExerciseId) { e, sets ->
        writeExercise(e.copy(status = "skipped", status_reason_code = reason, completed_at = ctx.nowMs()))
        EventDraft(
            type = "exercise_skipped", payload = mapOf(
                "reason_code" to JsonPrimitive(reason), "chip_position" to JsonPrimitive(chipPosition), "sets_done" to JsonPrimitive(sets),
            ),
        )
    }

    /** End exercise (UX 11.12): logged sets stay, the remaining ones are not done, with a reason. */
    suspend fun end(sessionExerciseId: String, reason: String) = exerciseEvent(sessionExerciseId) { e, sets ->
        writeExercise(e.copy(status = if (sets > 0) "done" else "skipped", status_reason_code = reason, completed_at = ctx.nowMs()))
        EventDraft(
            type = "exercise_ended", payload = mapOf(
                "sets_done" to JsonPrimitive(sets), "sets_planned" to JsonPrimitive(e.planned_sets ?: 0), "reason_code" to JsonPrimitive(reason),
            ),
        )
    }

    /** A test-day step finished with Next (UX 14.9): the item is done with what was logged; no reason is needed. */
    suspend fun markDone(sessionExerciseId: String) {
        db.withTransaction {
            val e = db.sessionExercises().get(sessionExerciseId) ?: return@withTransaction
            if (e.status != "done") writeExercise(e.copy(status = "done", completed_at = ctx.nowMs()))
        }
        scheduler.request()
    }

    /** Remove (only with no logged sets): a soft delete. */
    suspend fun remove(sessionExerciseId: String) = exerciseEvent(sessionExerciseId) { e, _ ->
        writeExercise(e.copy(deleted_at = ctx.nowMs()))
        EventDraft(type = "exercise_removed", payload = mapOf("exercise_id" to JsonPrimitive(e.exercise_id)))
    }

    /** Add a set (C63): one more set with the targets of the last one. */
    suspend fun addSet(sessionExerciseId: String) = exerciseEvent(sessionExerciseId) { e, _ ->
        val from = e.planned_sets ?: 0
        writeExercise(e.copy(planned_sets = from + 1, status = if (e.status == "done") "active" else e.status, completed_at = null))
        EventDraft(type = "override_applied", payload = mapOf("field" to JsonPrimitive("sets"), "from" to JsonPrimitive(from), "to" to JsonPrimitive(from + 1)))
    }

    /** Reorders the session's exercises to [orderedIds]; [deferred] marks a do-later move (UI-05). */
    suspend fun reorder(sessionId: String, orderedIds: List<String>, movedId: String, deferred: Boolean, via: String) {
        db.withTransaction {
            val exs = db.sessionExercises().bySession(sessionId).associateBy { it.id }
            val before = db.sessionExercises().bySession(sessionId).map { it.id }
            val fromPos = before.indexOf(movedId) + 1
            val toPos = orderedIds.indexOf(movedId) + 1
            orderedIds.forEachIndexed { i, id ->
                val e = exs[id] ?: return@forEachIndexed
                val pos = (i + 1) * POSITION_STEP
                val moved = id == movedId
                if (e.position != pos || (moved && deferred)) {
                    writeExercise(
                        e.copy(
                            position = pos,
                            status = if (moved && deferred) "deferred" else e.status,
                            defer_count = if (moved && deferred) minOf(10, e.defer_count + 1) else e.defer_count,
                        ),
                    )
                }
            }
            val moved = exs.getValue(movedId)
            telemetry.write(
                if (deferred) {
                    EventDraft(
                        type = "exercise_deferred", sessionId = sessionId, entityType = "session_exercise", entityId = movedId,
                        payload = mapOf("defer_count" to JsonPrimitive(moved.defer_count + 1), "from_position" to JsonPrimitive(fromPos), "to_position" to JsonPrimitive(toPos)),
                    )
                } else {
                    EventDraft(
                        type = "exercise_reordered", sessionId = sessionId, entityType = "session_exercise", entityId = movedId,
                        payload = mapOf("from_position" to JsonPrimitive(fromPos), "to_position" to JsonPrimitive(toPos), "via" to JsonPrimitive(via)),
                    )
                },
            )
        }
        scheduler.request()
    }

    // ------------------------------------------------------------------------------------------ sets

    /** Logs the rows of one tap together (the attempts of a top-hold round, UX 11.7.4) in one transaction. */
    suspend fun logSets(cmds: List<LogSetCommand>): List<String> = db.withTransaction { cmds.map { logSet(it) } }

    /** Logs one set (UX 11.8): the row, the exercise status, the `set_logged` event, all in one transaction. */
    suspend fun logSet(cmd: LogSetCommand): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        lateinit var id: String
        db.withTransaction {
            val s = requireNotNull(db.sessions().get(cmd.sessionId)) { "unknown session" }
            val e = requireNotNull(db.sessionExercises().get(cmd.sessionExerciseId)) { "unknown session exercise" }
            id = ctx.ids.next()
            val p = cmd.load
            val bw = s.bodyweight_kg
            val row = SetLogEntity(
                id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, session_id = cmd.sessionId, session_exercise_id = cmd.sessionExerciseId,
                exercise_id = cmd.exerciseId, set_index = cmd.setIndex, side = cmd.side, set_kind = cmd.setKind,
                round_index = cmd.roundIndex, attempt_index = cmd.attemptIndex,
                planned_reps = cmd.planned?.reps,
                planned_rep_min = cmd.planned?.repMin, planned_rep_max = cmd.planned?.repMax,
                planned_rir = cmd.planned?.rir?.let { Scaled.fromDouble(it, 1) },
                planned_input_value = cmd.planned?.inputValue?.let { Scaled.fromDouble(it, 2) },
                planned_input_unit = cmd.planned?.inputValue?.let { cmd.planned.inputUnit },
                planned_added_kg = cmd.planned?.addedKg?.let { Scaled.fromDouble(it, 2) },
                planned_assist_native = cmd.planned?.assistNative?.let { Scaled.fromDouble(it, 2) },
                planned_effective_load_kg = cmd.planned?.effectiveKg?.let { Scaled.fromDouble(it, 2) },
                planned_duration_s = cmd.planned?.durationS?.let { Scaled.fromDouble(it, 1) },
                planned_ecc_s = cmd.planned?.eccS?.let { Scaled.fromDouble(it, 1) },
                planned_assist_ordinal = cmd.planned?.assistOrdinal,
                planned_source = cmd.planned?.source,
                load_mode = p?.mode?.code ?: e.load_mode,
                reps = cmd.reps, duration_s = cmd.durationS?.let { Scaled.fromDouble(it, 1) },
                hold_reserve_s = cmd.holdReserveS?.let { Scaled.fromDouble(it, 1) }, hold_reserve_is_lower_bound = cmd.holdReserveLowerBound,
                ecc_s_target = cmd.eccSTarget?.let { Scaled.fromDouble(it, 1) }, ecc_s_actual = cmd.eccSActual?.let { Scaled.fromDouble(it, 1) },
                calib_extra_reps = cmd.calibExtraReps,
                rir_reported = cmd.rir?.let { Scaled.fromDouble(it, 1) }, rir_is_lower_bound = cmd.rirLowerBound,
                rir_source = if (cmd.rir == null && cmd.holdReserveS == null) "missing" else cmd.rirSource ?: "chip",
                flags = cmd.flags.distinct().sorted(), deviation_reason_code = cmd.deviationReason,
                rest_timer_planned_s = cmd.rest?.plannedS, rest_adjust_s = cmd.rest?.adjustS,
                rest_timer_overridden = cmd.rest?.overridden ?: false,
                rest_before_s = cmd.rest?.beforeS?.let { Scaled.fromDouble(it, 1) }, rest_source = cmd.rest?.source,
                rest_end_reason = cmd.rest?.endReason,
                started_at = cmd.startedAtMs, completed_at = cmd.completedAtMs,
            ).withLoad(p, e.load_mode, bw).let { r -> if (cmd.assistOrdinal != null) r.copy(assist_ordinal = cmd.assistOrdinal) else r }
            val firstInBlock = db.setLogs().bySession(cmd.sessionId).none { prev ->
                db.sessionExercises().get(prev.session_exercise_id)?.block_id == e.block_id
            }
            db.setLogs().upsert(row)
            outbox.enqueue("set_logs", id, now)
            if (firstInBlock) blockEvent("block_started", cmd.sessionId, e.block_id, null)
            val working = db.setLogs().bySession(cmd.sessionId).filter { it.session_exercise_id == e.id && (cmd.countsAsWork || it.set_kind != "warmup") }
            // The attempts of a round share their set index: a round counts once per side.
            val units = working.distinctBy { it.set_index to it.side }.sumOf { if (it.side == "both") 2 else 1 } / 2
            val done = e.planned_sets != null && units >= e.planned_sets
            writeExercise(
                e.copy(
                    status = if (done) "done" else "active",
                    started_at = e.started_at ?: (cmd.startedAtMs ?: cmd.completedAtMs),
                    completed_at = if (done) cmd.completedAtMs else null,
                ),
            )
            telemetry.write(
                EventDraft(
                    type = "set_logged", sessionId = cmd.sessionId, entityType = "set", entityId = id,
                    occurredAtMs = cmd.completedAtMs,
                    payload = cmd.telemetry + mapOf(
                        "set_id" to JsonPrimitive(id), "session_exercise_id" to JsonPrimitive(cmd.sessionExerciseId),
                        "set_index" to JsonPrimitive(cmd.setIndex), "side" to JsonPrimitive(cmd.side),
                        "set_kind" to JsonPrimitive(cmd.setKind), "completed_at" to JsonPrimitive(cmd.completedAtMs),
                    ) + listOfNotNull(cmd.roundIndex?.let { "round_index" to JsonPrimitive(it) }, cmd.attemptIndex?.let { "attempt_index" to JsonPrimitive(it) }),
                ),
            )
        }
        scheduler.request()
        return id
    }

    /** Undo (UX 11.8): soft-deletes the set and reopens the exercise. */
    suspend fun undoSet(setId: String) {
        val now = ctx.nowMs()
        db.withTransaction {
            val row = db.setLogs().byIds(listOf(setId)).firstOrNull() ?: return@withTransaction
            db.setLogs().upsert(row.copy(deleted_at = now, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId()))
            outbox.enqueue("set_logs", setId, now)
            db.sessionExercises().get(row.session_exercise_id)?.let { e ->
                if (e.status == "done") writeExercise(e.copy(status = "active", completed_at = null))
            }
            telemetry.write(
                EventDraft(
                    type = "set_undone", sessionId = row.session_id, entityType = "set", entityId = setId,
                    payload = mapOf("set_id" to JsonPrimitive(setId), "ms_since_logged" to JsonPrimitive(now - row.completed_at)),
                ),
            )
        }
        scheduler.request()
    }

    /** Records a reason chosen after logging (post-log RIR deviation chips in the rest card, C69). */
    suspend fun setDeviationReason(setId: String, reason: String, context: String, chipPosition: Int, viaMore: Boolean) {
        val now = ctx.nowMs()
        db.withTransaction {
            val row = db.setLogs().byIds(listOf(setId)).firstOrNull() ?: return@withTransaction
            val implied = if (reason == "torso_rotation" || reason == "technique_breakdown") (row.flags + reason).distinct().sorted() else row.flags
            db.setLogs().upsert(row.copy(deviation_reason_code = reason, flags = implied, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId()))
            outbox.enqueue("set_logs", setId, now)
            telemetry.write(
                EventDraft(
                    type = "reason_selected", sessionId = row.session_id, entityType = "set", entityId = setId,
                    payload = mapOf(
                        "reason_code" to JsonPrimitive(reason), "context" to JsonPrimitive(context),
                        "chip_position" to JsonPrimitive(chipPosition), "via_more" to JsonPrimitive(viaMore),
                    ),
                ),
            )
        }
        scheduler.request()
    }

    /** Writes a UI-only telemetry event (no domain change) in its own transaction. */
    suspend fun event(draft: EventDraft) {
        db.withTransaction { telemetry.write(draft) }
        scheduler.request()
    }

    /** Events of [types], oldest first (UI-10's dismissal store reads `warning_dismissed` and `warning_use_mine_toggled`). */
    suspend fun eventsOfTypes(types: List<String>): List<SessionEventEntity> = db.events().ofTypes(types)

    // ------------------------------------------------------------------------------------------ helpers

    /** `block_started` / `block_finished` (UX 20.2), inside the caller's transaction. */
    private suspend fun blockEvent(type: String, sessionId: String, blockId: String, status: String?) {
        val b = db.blocks().bySession(sessionId).firstOrNull { it.id == blockId } ?: return
        telemetry.write(
            EventDraft(
                type = type, sessionId = sessionId, entityType = "block", entityId = blockId,
                payload = buildMap {
                    put("block_id", JsonPrimitive(blockId))
                    put("block_kind", JsonPrimitive(b.block_kind))
                    put("origin", JsonPrimitive(b.origin))
                    status?.let { put("status", JsonPrimitive(it)) }
                },
            ),
        )
    }

    private suspend fun exerciseEvent(sessionExerciseId: String, change: suspend (SessionExerciseEntity, Int) -> EventDraft) {
        db.withTransaction {
            val e = db.sessionExercises().get(sessionExerciseId) ?: return@withTransaction
            val sets = db.setLogs().bySession(e.session_id).count { it.session_exercise_id == e.id && it.set_kind != "warmup" }
            val draft = change(e, sets)
            telemetry.write(draft.copy(sessionId = e.session_id, entityType = "session_exercise", entityId = e.id))
        }
        scheduler.request()
    }

    private suspend fun writeExercise(e: SessionExerciseEntity) {
        val row = e.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
        db.sessionExercises().upsert(row)
        outbox.enqueue("session_exercises", row.id, ctx.nowMs())
    }

    private suspend fun writeBlock(b: SessionBlockEntity) {
        val row = b.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
        db.blocks().upsert(row)
        outbox.enqueue("session_blocks", row.id, ctx.nowMs())
    }

    /** The item keys of a challenge session's warm-up items (CDS role `warmup`); empty for other sessions. */
    internal suspend fun warmupItemKeys(sessionId: String): Set<String> {
        val live = db.challengeLive().get(sessionId) ?: return emptySet()
        return PlanJson.decode(Json.parseToJsonElement(live.plan_json).jsonObject).items
            .filter { it.role == "warmup" }.mapTo(HashSet()) { it.key }
    }

    private suspend fun writeSession(s: WorkoutSessionEntity) {
        val row = s.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
        db.sessions().upsert(row)
        outbox.enqueue("workout_sessions", row.id, ctx.nowMs())
    }

    private fun wellness(sessionId: String?, kind: String, phase: String): WellnessLogEntity {
        val now = ctx.nowMs()
        return WellnessLogEntity(
            id = ctx.ids.next(), user_id = ctx.requireUser(), client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
            app_version = ctx.appVersion, kind = kind, phase = phase, observed_at = now,
            local_date = Instant.ofEpochMilli(now).atZone(ctx.zone()).toLocalDate().toString(), session_id = sessionId,
        )
    }

    companion object {
        /** `session_exercises.position` is numeric(9,4): whole positions are multiples of 10^4. */
        const val POSITION_STEP = 10_000L
        const val DEFAULT_SETS = 3
    }
}
