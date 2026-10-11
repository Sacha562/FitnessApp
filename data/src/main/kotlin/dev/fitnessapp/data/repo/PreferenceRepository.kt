package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.UserExercisePrefEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive

/**
 * The five-state exercise preference (UX 13.4, C26), stored now and read by the selection modules in M3. One row per
 * exercise with a UUIDv5 id (DATA_MODEL 9.3.6), so a change on another device converges on the same row. A change
 * applies immediately; there is no Undo snackbar, the control itself is the way back (D30).
 */
class PreferenceRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    /** exercise id -> preference code; exercises without a row are neutral. */
    fun observe(): Flow<Map<String, String>> = db.exercisePrefs().observeAll().map { rows -> rows.associate { it.exercise_id to it.preference } }

    /** Sets the preference; returns the `preference_changed` event id, or null when nothing changed. */
    suspend fun set(exerciseId: String, to: String, surface: String): String? {
        require(to in STATES) { "unknown preference $to" }
        var eventId: String? = null
        db.withTransaction {
            val from = write(exerciseId, to) ?: return@withTransaction
            eventId = telemetry.write(
                EventDraft(
                    type = "preference_changed", entityType = "exercise", entityId = exerciseId,
                    payload = mapOf(
                        "exercise_id" to JsonPrimitive(exerciseId), "from" to JsonPrimitive(from), "to" to JsonPrimitive(to),
                        "surface" to JsonPrimitive(surface),
                    ),
                ),
            )
        }
        scheduler.request()
        return eventId
    }

    /** Writes the row; returns the previous preference, or null when it already had [to]. */
    private suspend fun write(exerciseId: String, to: String): String? {
        val uid = ctx.requireUser()
        val id = idOf(uid, exerciseId)
        val old = db.exercisePrefs().get(id)
        val from = old?.takeIf { it.deleted_at == null }?.preference ?: NEUTRAL
        if (from == to) return null
        val row = (old ?: UserExercisePrefEntity(id = id, user_id = uid, client_updated_at = 0, device_id = "", app_version = "", exercise_id = exerciseId))
            .copy(
                preference = to, deleted_at = null, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                app_version = ctx.appVersion,
            )
        db.exercisePrefs().upsert(row)
        outbox.enqueue("user_exercise_prefs", id, ctx.nowMs())
        return from
    }

    companion object {
        const val NEUTRAL = "neutral"

        /** C26 order, as the segmented control shows it. */
        val STATES = listOf("favorite", "like", NEUTRAL, "dislike", "never")

        fun idOf(userId: String, exerciseId: String): String = Ids.fitness("user_exercise_prefs:$userId:$exerciseId").toString()
    }
}
