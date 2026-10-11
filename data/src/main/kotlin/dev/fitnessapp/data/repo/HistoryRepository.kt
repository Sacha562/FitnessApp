package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** One History row (UX 15.3): the session, its exercises in order, working sets (L+R = 1, C7) and the pain badge. */
data class HistoryRow(val session: WorkoutSessionEntity, val exerciseIds: List<String>, val workingSets: Int, val pain: Boolean)

/**
 * The values of a set as the Set edit sheet (UX 16.14) saves them. [load] null = the logged load is kept (the
 * sheet only replaces it when the user changed it, so a load that is no longer loadable stays as logged).
 */
data class SetEdit(
    val load: LoadPoint?,
    val reps: Int?,
    val durationS: Double?,
    val rir: Double?,
    val rirLowerBound: Boolean,
    val holdReserveS: Double?,
    val holdReserveLowerBound: Boolean,
    val flags: List<String>,
    val reason: String?,
)

enum class ReopenResult { REOPENED, NOT_ABANDONED, OTHER_DAY, ANOTHER_ACTIVE }

/**
 * Editing past sessions (UX 15.3, D28): edit, add and delete sets, add a forgotten exercise, edit the quick check,
 * reopen an abandoned session and delete a session. Every action is one Room transaction with its telemetry and
 * outbox entries (ANDROID.md 8.4). Edits change facts only; engines rebuild from facts ("Edits update future
 * recommendations"). Deletes are soft (`deleted_at`, DATA_MODEL 11.7) and can be undone.
 */
class HistoryRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val sessions: SessionRepository,
) {
    fun observeHistory(): Flow<List<HistoryRow>> = combine(
        db.sessions().observeHistory(),
        db.sessionExercises().observeNames(),
        db.setLogs().observeWorkUnits(),
        db.wellness().observePainSessions(),
    ) { ss, names, units, pain ->
        val bySession = names.groupBy { it.session_id }
        val unitMap = units.associate { it.session_id to it.units }
        val painSet = pain.toSet()
        ss.map { s ->
            HistoryRow(s, bySession[s.id].orEmpty().map { it.exercise_id }, (unitMap[s.id] ?: 0) / 2, s.id in painSet)
        }
    }

    fun observeWellness(sessionId: String): Flow<List<WellnessLogEntity>> = db.wellness().observeBySession(sessionId)

    /** `history_session_opened {days_ago}`. */
    suspend fun opened(sessionId: String, daysAgo: Int) = event(
        EventDraft(type = "history_session_opened", sessionId = sessionId, entityType = "session", entityId = sessionId, payload = mapOf("days_ago" to JsonPrimitive(daysAgo))),
    )

    // ------------------------------------------------------------------------------------------------ sets

    /** Saves the sheet; one `set_edited` event per changed field. Returns the number of changed fields. */
    suspend fun editSet(setId: String, edit: SetEdit, surface: String): Int {
        val now = ctx.nowMs()
        var changed = 0
        db.withTransaction {
            val old = db.setLogs().get(setId) ?: return@withTransaction
            val session = db.sessions().get(old.session_id) ?: return@withTransaction
            var row = applyValues(old, edit, session)
            val events = diff(old, row)
            if (events.isEmpty()) return@withTransaction
            if (events.any { it.first == "rir" } && row.rir_source != "missing") row = row.copy(rir_source = "edited")
            row = row.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
            db.setLogs().upsert(row)
            outbox.enqueue("set_logs", setId, now)
            for ((field, fromTo) in events) {
                telemetry.write(
                    EventDraft(
                        type = "set_edited", sessionId = old.session_id, entityType = "set", entityId = setId,
                        payload = mapOf(
                            "set_id" to JsonPrimitive(setId), "field" to JsonPrimitive(field), "from" to fromTo.first, "to" to fromTo.second,
                            "surface" to JsonPrimitive(surface), "ms_since_logged" to JsonPrimitive(now - old.completed_at),
                        ),
                    ),
                )
            }
            changed = events.size
            refresh(old.session_id, old.session_exercise_id)
        }
        scheduler.request()
        return changed
    }

    /**
     * A forgotten set added afterwards (`set_added_later`); a right side fills the open pair of the last left side.
     * [edit] must carry the LOAD-1 point of the set (a new set has no logged load to keep).
     */
    suspend fun addSetLater(sessionExerciseId: String, side: String, warmup: Boolean, edit: SetEdit): String? {
        requireNotNull(edit.load) { "a new set needs its load point" }
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        var id: String? = null
        db.withTransaction {
            val e = db.sessionExercises().get(sessionExerciseId) ?: return@withTransaction
            val session = db.sessions().get(e.session_id) ?: return@withTransaction
            val all = db.setLogs().bySessionAll(e.session_id).filter { it.session_exercise_id == e.id }
            val live = all.filter { it.deleted_at == null }
            val openPair = if (side == "R") {
                live.filter { it.side == "L" }.map { it.set_index }.filter { i -> live.none { it.set_index == i && it.side == "R" } }.maxOrNull()
            } else {
                null
            }
            val index = openPair ?: ((all.maxOfOrNull { it.set_index } ?: 0) + 1)
            val sessionSets = db.setLogs().bySession(e.session_id)
            val completed = (sessionSets.maxOfOrNull { it.completed_at } ?: session.finished_at ?: session.started_at ?: now) + 1
            val newId = ctx.ids.next()
            val base = SetLogEntity(
                id = newId, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion, session_id = e.session_id, session_exercise_id = e.id, exercise_id = e.exercise_id,
                set_index = index, side = side, set_kind = if (warmup) "warmup" else "working", load_mode = e.load_mode,
                completed_at = completed,
            )
            val row = applyValues(base, edit, session).let { r ->
                r.copy(rir_source = if (r.rir_reported == null && r.hold_reserve_s == null) "missing" else "edited")
            }
            db.setLogs().upsert(row)
            outbox.enqueue("set_logs", newId, now)
            val hours = (now - (session.finished_at ?: session.started_at ?: now)) / 3_600_000.0
            telemetry.write(
                EventDraft(
                    type = "set_added_later", sessionId = e.session_id, entityType = "set", entityId = newId,
                    payload = mapOf("set_id" to JsonPrimitive(newId), "hours_after_session" to JsonPrimitive(Scaled.toDouble(Scaled.fromDouble(hours, 1), 1))),
                ),
            )
            refresh(e.session_id, e.id)
            id = newId
        }
        scheduler.request()
        return id
    }

    /** Soft delete; returns the `set_deleted` event id for Undo. */
    suspend fun deleteSet(setId: String, surface: String): String? {
        val now = ctx.nowMs()
        var eventId: String? = null
        db.withTransaction {
            val row = db.setLogs().get(setId)?.takeIf { it.deleted_at == null } ?: return@withTransaction
            writeSet(row.copy(deleted_at = now))
            eventId = telemetry.write(
                EventDraft(
                    type = "set_deleted", sessionId = row.session_id, entityType = "set", entityId = setId,
                    payload = mapOf("set_id" to JsonPrimitive(setId), "surface" to JsonPrimitive(surface)),
                ),
            )
            refresh(row.session_id, row.session_exercise_id)
        }
        scheduler.request()
        return eventId
    }

    suspend fun restoreSet(setId: String, eventId: String, msSince: Long) {
        db.withTransaction {
            val row = db.setLogs().get(setId)?.takeIf { it.deleted_at != null } ?: return@withTransaction
            writeSet(row.copy(deleted_at = null))
            undone(row.session_id, "set", setId, "set_deleted", eventId, msSince)
            refresh(row.session_id, row.session_exercise_id)
        }
        scheduler.request()
    }

    // ------------------------------------------------------------------------------------------------ sessions

    /** Soft delete of a whole session; its rows stay and every view filters on the session's tombstone. */
    suspend fun deleteSession(sessionId: String): String? {
        val now = ctx.nowMs()
        var eventId: String? = null
        db.withTransaction {
            val s = db.sessions().get(sessionId)?.takeIf { it.deleted_at == null && it.status != "in_progress" } ?: return@withTransaction
            val units = db.setLogs().bySession(sessionId).filter { it.set_kind != "warmup" }.sumOf { if (it.side == "both") 2 else 1 } / 2
            writeSession(s.copy(deleted_at = now))
            eventId = telemetry.write(
                EventDraft(
                    type = "session_deleted", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf("working_sets" to JsonPrimitive(units), "kind" to JsonPrimitive(s.kind)),
                ),
            )
        }
        scheduler.request()
        return eventId
    }

    suspend fun restoreSession(sessionId: String, eventId: String, msSince: Long) {
        db.withTransaction {
            val s = db.sessions().get(sessionId)?.takeIf { it.deleted_at != null } ?: return@withTransaction
            writeSession(s.copy(deleted_at = null))
            undone(sessionId, "session", sessionId, "session_deleted", eventId, msSince)
        }
        scheduler.request()
    }

    /** Reopen an abandoned session on the same local date (UX 15.3) when no other session is open. */
    suspend fun reopen(sessionId: String): ReopenResult {
        val now = ctx.nowMs()
        var result = ReopenResult.NOT_ABANDONED
        db.withTransaction {
            val s = db.sessions().get(sessionId) ?: return@withTransaction
            if (s.status != "abandoned" || s.deleted_at != null) return@withTransaction
            val today = Instant.ofEpochMilli(now).atZone(ctx.zone()).toLocalDate().toString()
            if (s.local_date != today) {
                result = ReopenResult.OTHER_DAY
                return@withTransaction
            }
            if (db.sessions().active() != null) {
                result = ReopenResult.ANOTHER_ACTIVE
                return@withTransaction
            }
            writeSession(s.copy(status = "in_progress", finished_at = null, end_reason_code = null))
            telemetry.write(
                EventDraft(
                    type = "session_reopened", sessionId = sessionId, entityType = "session", entityId = sessionId,
                    payload = mapOf("ms_since_abandon" to JsonPrimitive(now - (s.finished_at ?: now))),
                ),
            )
            result = ReopenResult.REOPENED
        }
        scheduler.request()
        return result
    }

    /** Quick-check edit (session effort, within 24 h of the session, UX 15.3): updates the session's sRPE row. */
    suspend fun editSrpe(sessionId: String, srpe: Int) {
        val now = ctx.nowMs()
        db.withTransaction {
            val existing = db.wellness().bySession(sessionId).firstOrNull { it.kind == "srpe" }
            if (existing?.srpe == srpe) return@withTransaction
            val row = (existing ?: WellnessLogEntity(
                id = ctx.ids.next(), user_id = ctx.requireUser(), client_updated_at = 0, device_id = "", app_version = "",
                kind = "srpe", phase = "post", observed_at = now,
                local_date = Instant.ofEpochMilli(now).atZone(ctx.zone()).toLocalDate().toString(), session_id = sessionId,
            )).copy(srpe = srpe, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion)
            db.wellness().upsert(row)
            outbox.enqueue("wellness_logs", row.id, now)
            telemetry.write(
                EventDraft(
                    type = "prompt_answered", sessionId = sessionId, entityType = "wellness_log", entityId = row.id,
                    payload = mapOf("prompt" to JsonPrimitive("srpe"), "value_code" to JsonPrimitive(srpe.toString()), "wellness_log_id" to JsonPrimitive(row.id)),
                ),
            )
        }
        scheduler.request()
    }

    /** A forgotten exercise (D28): added at the end, `not_done` until a set is added. */
    suspend fun addExercise(sessionId: String, e: CatalogExercise): String {
        val id = sessions.addExercise(sessionId, e)
        db.withTransaction {
            val row = db.sessionExercises().get(id) ?: return@withTransaction
            val s = db.sessions().get(sessionId)
            if (s != null && s.status != "in_progress") writeExercise(row.copy(status = "not_done"))
        }
        scheduler.request()
        return id
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun applyValues(old: SetLogEntity, edit: SetEdit, session: WorkoutSessionEntity): SetLogEntity {
        val withLoad = edit.load?.let { old.withLoad(it, old.load_mode, session.bodyweight_kg) } ?: old
        val flags = (edit.flags + listOfNotNull(edit.reason?.takeIf { it in FLAG_REASONS })).distinct().sorted()
        val effortMissing = edit.rir == null && edit.holdReserveS == null
        return withLoad.copy(
            reps = edit.reps,
            duration_s = edit.durationS?.let { Scaled.fromDouble(it, 1) },
            rir_reported = edit.rir?.let { Scaled.fromDouble(it, 1) },
            rir_is_lower_bound = edit.rir != null && edit.rirLowerBound,
            hold_reserve_s = edit.holdReserveS?.let { Scaled.fromDouble(it, 1) },
            hold_reserve_is_lower_bound = edit.holdReserveS != null && edit.holdReserveLowerBound,
            rir_source = if (effortMissing) "missing" else old.rir_source.takeIf { it != "missing" } ?: "edited",
            flags = flags,
            deviation_reason_code = edit.reason,
        )
    }

    /** Changed fields of UX 20.3 `set_edited` with their from / to values (numbers, codes or code lists). */
    private fun diff(a: SetLogEntity, b: SetLogEntity): List<Pair<String, Pair<JsonElement, JsonElement>>> {
        val out = ArrayList<Pair<String, Pair<JsonElement, JsonElement>>>()
        fun num(v: Long?, scale: Int): JsonElement = v?.let { JsonPrimitive(Scaled.toDecimal(it, scale)) } ?: JsonNull
        val loadA = a.input_value ?: a.assist_pin_native
        val loadB = b.input_value ?: b.assist_pin_native
        if (loadA != loadB || a.effective_load_kg != b.effective_load_kg) out += "load" to (num(loadA, 2) to num(loadB, 2))
        if (a.reps != b.reps) out += "reps" to ((a.reps?.let(::JsonPrimitive) ?: JsonNull) to (b.reps?.let(::JsonPrimitive) ?: JsonNull))
        if (a.duration_s != b.duration_s) out += "duration_s" to (num(a.duration_s, 1) to num(b.duration_s, 1))
        if (a.rir_reported != b.rir_reported || a.rir_is_lower_bound != b.rir_is_lower_bound ||
            a.hold_reserve_s != b.hold_reserve_s || a.hold_reserve_is_lower_bound != b.hold_reserve_is_lower_bound
        ) {
            out += "rir" to (num(a.rir_reported ?: a.hold_reserve_s, 1) to num(b.rir_reported ?: b.hold_reserve_s, 1))
        }
        if (a.flags != b.flags) out += "flags" to (JsonArray(a.flags.map(::JsonPrimitive)) to JsonArray(b.flags.map(::JsonPrimitive)))
        if (a.deviation_reason_code != b.deviation_reason_code) {
            out += "reason" to ((a.deviation_reason_code?.let(::JsonPrimitive) ?: JsonNull) to (b.deviation_reason_code?.let(::JsonPrimitive) ?: JsonNull))
        }
        return out
    }

    /**
     * After a change in a closed session: the exercise is done with at least one working set, otherwise not done
     * (a skip or removal keeps its reason); the session's completion ratio is recomputed as at finish (UX 11.14).
     */
    private suspend fun refresh(sessionId: String, sessionExerciseId: String) {
        val s = db.sessions().get(sessionId) ?: return
        if (s.status == "in_progress") return
        val sets = db.setLogs().bySession(sessionId).filter { it.set_kind != "warmup" }
        db.sessionExercises().get(sessionExerciseId)?.let { e ->
            val units = sets.filter { it.session_exercise_id == e.id }.sumOf { if (it.side == "both") 2 else 1 }
            val status = when {
                units > 0 -> "done"
                e.status in setOf("skipped", "removed") -> e.status
                else -> "not_done"
            }
            if (status != e.status) writeExercise(e.copy(status = status, completed_at = if (status == "done") e.completed_at ?: s.finished_at else e.completed_at))
        }
        if (s.status == "finished") {
            val planned = db.sessionExercises().bySession(sessionId).filter { it.status != "removed" }.sumOf { it.planned_sets ?: 0 }
            val done = sets.sumOf { if (it.side == "both") 2 else 1 } / 2.0
            val ratio = if (planned > 0) Scaled.fromDouble(minOf(1.0, done / planned), 4) else null
            if (ratio != s.completion_ratio) writeSession(s.copy(completion_ratio = ratio))
        }
    }

    private suspend fun undone(sessionId: String, entityType: String, entityId: String, type: String, eventId: String, msSince: Long) {
        telemetry.write(
            EventDraft(
                type = "action_undone", sessionId = sessionId, entityType = entityType, entityId = entityId,
                payload = mapOf("undone_event_type" to JsonPrimitive(type), "undone_event_id" to JsonPrimitive(eventId), "ms_since" to JsonPrimitive(msSince)),
            ),
        )
    }

    private suspend fun event(draft: EventDraft) {
        db.withTransaction { telemetry.write(draft) }
        scheduler.request()
    }

    private suspend fun writeSet(r: SetLogEntity) {
        db.setLogs().upsert(r.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion))
        outbox.enqueue("set_logs", r.id, ctx.nowMs())
    }

    private suspend fun writeExercise(e: SessionExerciseEntity) {
        db.sessionExercises().upsert(e.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion))
        outbox.enqueue("session_exercises", e.id, ctx.nowMs())
    }

    private suspend fun writeSession(s: WorkoutSessionEntity) {
        db.sessions().upsert(s.copy(client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion))
        outbox.enqueue("workout_sessions", s.id, ctx.nowMs())
    }

    private companion object {
        /** Reasons that imply their flag (`reason_codes.implies_flag`, CHECK `set_logs_reason_flag_ck`). */
        val FLAG_REASONS = setOf("torso_rotation", "technique_breakdown")
    }
}
