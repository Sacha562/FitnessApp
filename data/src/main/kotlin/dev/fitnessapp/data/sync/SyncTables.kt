package dev.fitnessapp.data.sync

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.dao.SyncDao
import dev.fitnessapp.data.db.entity.ExportEntity
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.db.entity.MutableRow
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.SyncedRow
import dev.fitnessapp.data.db.entity.UserExercisePrefEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Push/pull JSON: every column explicit (nulls and defaults included), unknown server columns ignored. */
val SyncJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** One synced table: its name, sync mode and the mapping between Room rows and PostgREST JSON. */
class SyncTable<E : SyncedRow>(
    val name: String,
    /** Append-only (ignore-duplicates); otherwise LWW merge. */
    val append: Boolean,
    /** Whether the incremental pull includes this table in M1a. */
    val pull: Boolean,
    val dao: SyncDao<E>,
    private val serializer: KSerializer<E>,
) {
    /** The push body: every writable column; `server_updated_at` is never sent (DATA_MODEL 11.2). */
    fun toPush(row: E): JsonObject {
        val obj = SyncJson.encodeToJsonElement(serializer, row).jsonObject
        return JsonObject(obj.filterKeys { it != "server_updated_at" })
    }

    /** The full DTO including `server_updated_at`: one row of the export file (DATA_MODEL 15.5). */
    fun toExport(row: SyncedRow): JsonObject {
        @Suppress("UNCHECKED_CAST")
        return SyncJson.encodeToJsonElement(serializer, row as E).jsonObject
    }

    fun fromPulled(obj: JsonObject): E = SyncJson.decodeFromJsonElement(serializer, obj)

    fun tuple(row: E): LwwTuple? = (row as? MutableRow)?.let { LwwTuple(it.client_updated_at, it.device_id) }

    @Suppress("UNCHECKED_CAST")
    suspend fun upsertAny(row: SyncedRow) = dao.upsert(row as E)
}

/**
 * Push order of DATA_MODEL 11.3 restricted to the Room tables (parents before children). `session_events`
 * is pushed but not pulled yet: its pull scope (11.6) only rebuilds caches that arrive with later milestones.
 */
fun syncTables(db: FitnessDatabase): List<SyncTable<out SyncedRow>> = listOf(
    SyncTable("gyms", false, true, db.gyms(), GymEntity.serializer()),
    SyncTable("gym_equipment", false, true, db.gymEquipment(), GymEquipmentEntity.serializer()),
    SyncTable("profiles", false, true, db.profiles(), ProfileEntity.serializer()),
    SyncTable("user_exercise_prefs", false, true, db.exercisePrefs(), UserExercisePrefEntity.serializer()),
    SyncTable("workout_sessions", false, true, db.sessions(), WorkoutSessionEntity.serializer()),
    SyncTable("session_blocks", false, true, db.blocks(), SessionBlockEntity.serializer()),
    SyncTable("session_exercises", false, true, db.sessionExercises(), SessionExerciseEntity.serializer()),
    SyncTable("set_logs", false, true, db.setLogs(), SetLogEntity.serializer()),
    SyncTable("wellness_logs", false, true, db.wellness(), WellnessLogEntity.serializer()),
    SyncTable("metric_observations", false, true, db.metrics(), MetricObservationEntity.serializer()),
    SyncTable("session_events", true, false, db.events(), SessionEventEntity.serializer()),
    SyncTable("exports", true, true, db.exports(), ExportEntity.serializer()),
)

/** Table names in push order, for the outbox and tests. */
val SYNC_TABLE_ORDER: List<String> = listOf(
    "gyms", "gym_equipment", "profiles", "user_exercise_prefs", "workout_sessions", "session_blocks", "session_exercises",
    "set_logs", "wellness_logs", "metric_observations", "session_events", "exports",
)

fun isAppendTable(table: String): Boolean = table == "session_events" || table == "exports"
