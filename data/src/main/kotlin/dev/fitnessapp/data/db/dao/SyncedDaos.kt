package dev.fitnessapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

/** The operations the sync engine needs on every synced table (ANDROID.md 9). */
interface SyncDao<E> {
    @Upsert
    suspend fun upsert(row: E)

    @Upsert
    suspend fun upsertAll(rows: List<E>)

    suspend fun byIds(ids: List<String>): List<E>

    suspend fun allIds(): List<String>
}

@Dao
interface ProfileDao : SyncDao<ProfileEntity> {
    @Query("SELECT * FROM profiles WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ProfileEntity>

    @Query("SELECT id FROM profiles")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM profiles WHERE id = :userId")
    fun observe(userId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :userId")
    suspend fun get(userId: String): ProfileEntity?
}

@Dao
interface GymDao : SyncDao<GymEntity> {
    @Query("SELECT * FROM gyms WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<GymEntity>

    @Query("SELECT id FROM gyms")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM gyms WHERE deleted_at IS NULL ORDER BY is_default DESC, name, id")
    fun observeAll(): Flow<List<GymEntity>>

    @Query("SELECT * FROM gyms WHERE deleted_at IS NULL ORDER BY is_default DESC, name, id")
    suspend fun all(): List<GymEntity>
}

@Dao
interface GymEquipmentDao : SyncDao<GymEquipmentEntity> {
    @Query("SELECT * FROM gym_equipment WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<GymEquipmentEntity>

    @Query("SELECT id FROM gym_equipment")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM gym_equipment WHERE deleted_at IS NULL ORDER BY equipment_type, label, id")
    fun observeAll(): Flow<List<GymEquipmentEntity>>

    @Query("SELECT * FROM gym_equipment WHERE deleted_at IS NULL ORDER BY equipment_type, label, id")
    suspend fun all(): List<GymEquipmentEntity>
}

@Dao
interface WorkoutSessionDao : SyncDao<WorkoutSessionEntity> {
    @Query("SELECT * FROM workout_sessions WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<WorkoutSessionEntity>

    @Query("SELECT id FROM workout_sessions")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun get(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    fun observe(id: String): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status = 'in_progress' AND deleted_at IS NULL ORDER BY started_at DESC, id DESC LIMIT 1")
    fun observeActive(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status = 'in_progress' AND deleted_at IS NULL ORDER BY started_at DESC, id DESC LIMIT 1")
    suspend fun active(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE status IN ('finished', 'abandoned') AND deleted_at IS NULL ORDER BY started_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WorkoutSessionEntity>>
}

@Dao
interface SessionBlockDao : SyncDao<SessionBlockEntity> {
    @Query("SELECT * FROM session_blocks WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionBlockEntity>

    @Query("SELECT id FROM session_blocks")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM session_blocks WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    suspend fun bySession(sessionId: String): List<SessionBlockEntity>
}

@Dao
interface SessionExerciseDao : SyncDao<SessionExerciseEntity> {
    @Query("SELECT * FROM session_exercises WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionExerciseEntity>

    @Query("SELECT id FROM session_exercises")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM session_exercises WHERE id = :id")
    suspend fun get(id: String): SessionExerciseEntity?

    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    fun observeBySession(sessionId: String): Flow<List<SessionExerciseEntity>>

    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    suspend fun bySession(sessionId: String): List<SessionExerciseEntity>
}

/** One logged set joined with its session start (for "last time" history, UI-04). */
data class SetWithSessionStart(
    val session_started_at: Long?,
    val session_local_date: String,
    @androidx.room.Embedded val set: SetLogEntity,
)

@Dao
interface SetLogDao : SyncDao<SetLogEntity> {
    @Query("SELECT * FROM set_logs WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SetLogEntity>

    @Query("SELECT id FROM set_logs")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY completed_at, id")
    fun observeBySession(sessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY completed_at, id")
    suspend fun bySession(sessionId: String): List<SetLogEntity>

    @Query(
        """SELECT s.started_at AS session_started_at, s.local_date AS session_local_date, l.*
           FROM set_logs l JOIN workout_sessions s ON s.id = l.session_id
           WHERE l.exercise_id = :exerciseId AND l.deleted_at IS NULL AND s.deleted_at IS NULL
             AND s.id != :excludeSessionId AND s.status IN ('finished', 'abandoned', 'in_progress')
           ORDER BY s.started_at DESC, s.id DESC, l.set_index, l.side, l.id
           LIMIT :limit""",
    )
    suspend fun historyForExercise(exerciseId: String, excludeSessionId: String, limit: Int): List<SetWithSessionStart>
}

@Dao
interface WellnessLogDao : SyncDao<WellnessLogEntity> {
    @Query("SELECT * FROM wellness_logs WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<WellnessLogEntity>

    @Query("SELECT id FROM wellness_logs")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM wellness_logs WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY observed_at, id")
    suspend fun bySession(sessionId: String): List<WellnessLogEntity>
}

@Dao
interface MetricObservationDao : SyncDao<MetricObservationEntity> {
    @Query("SELECT * FROM metric_observations WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<MetricObservationEntity>

    @Query("SELECT id FROM metric_observations")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM metric_observations WHERE metric_code = :code AND deleted_at IS NULL ORDER BY observed_at, id")
    suspend fun byMetric(code: String): List<MetricObservationEntity>

    @Query("SELECT * FROM metric_observations WHERE metric_code = :code AND deleted_at IS NULL ORDER BY observed_at DESC, id DESC LIMIT 1")
    fun observeLatest(code: String): Flow<MetricObservationEntity?>
}

@Dao
interface SessionEventDao : SyncDao<SessionEventEntity> {
    @Query("SELECT * FROM session_events WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionEventEntity>

    @Query("SELECT id FROM session_events")
    override suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SessionEventEntity)

    @Query("SELECT * FROM session_events WHERE session_id = :sessionId ORDER BY occurred_at, id")
    suspend fun bySession(sessionId: String): List<SessionEventEntity>
}
