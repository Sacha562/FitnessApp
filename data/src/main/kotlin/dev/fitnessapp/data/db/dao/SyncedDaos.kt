package dev.fitnessapp.data.db.dao

import dev.fitnessapp.data.db.entity.CustomExerciseEntity
import dev.fitnessapp.data.db.entity.ExerciseStimulusEntity
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
import dev.fitnessapp.data.db.entity.RepPreferenceEntity
import dev.fitnessapp.data.db.entity.UserExercisePrefEntity
import dev.fitnessapp.data.db.entity.ExportEntity
import kotlinx.coroutines.flow.Flow

/** The operations the sync engine needs on every synced table (ANDROID.md 9). */
interface SyncDao<E> {
    @Upsert
    suspend fun upsert(row: E)

    @Upsert
    suspend fun upsertAll(rows: List<E>)

    suspend fun byIds(ids: List<String>): List<E>

    suspend fun allIds(): List<String>

    /** Every row including tombstones, ordered by id (the full export, DATA_MODEL 15.5). */
    suspend fun exportAll(): List<E>
}

@Dao
interface ProfileDao : SyncDao<ProfileEntity> {
    @Query("SELECT * FROM profiles WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ProfileEntity>

    @Query("SELECT id FROM profiles")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM profiles ORDER BY id")
    override suspend fun exportAll(): List<ProfileEntity>

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

    @Query("SELECT * FROM gyms ORDER BY id")
    override suspend fun exportAll(): List<GymEntity>

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

    @Query("SELECT * FROM gym_equipment ORDER BY id")
    override suspend fun exportAll(): List<GymEquipmentEntity>

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

    @Query("SELECT * FROM workout_sessions ORDER BY id")
    override suspend fun exportAll(): List<WorkoutSessionEntity>

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

    /** History (UX 15.3): every finished or abandoned session, newest first. */
    @Query("SELECT * FROM workout_sessions WHERE status IN ('finished', 'abandoned') AND deleted_at IS NULL ORDER BY started_at DESC, id DESC")
    fun observeHistory(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT MIN(started_at) FROM workout_sessions WHERE deleted_at IS NULL")
    suspend fun firstStartedAt(): Long?

    /** Finished sessions of one ISO week (the challenge week plan's done slots and gym anchors, CHALLENGE_ENGINE 7). */
    @Query("SELECT * FROM workout_sessions WHERE iso_week = :isoWeek AND status = 'finished' AND deleted_at IS NULL ORDER BY started_at, id")
    suspend fun finishedInIsoWeek(isoWeek: String): List<WorkoutSessionEntity>

    /** Every finished session in the order the estimation layer replays them (L8). */
    @Query("SELECT * FROM workout_sessions WHERE status = 'finished' AND deleted_at IS NULL AND started_at IS NOT NULL ORDER BY started_at, id")
    suspend fun finishedChronological(): List<WorkoutSessionEntity>

    /** The last finished gym session before [beforeMs] (the previous week's last gym visit for the gap rule). */
    @Query("SELECT * FROM workout_sessions WHERE kind <> 'home' AND status = 'finished' AND deleted_at IS NULL AND started_at < :beforeMs ORDER BY started_at DESC, id DESC LIMIT 1")
    suspend fun lastGymBefore(beforeMs: Long): WorkoutSessionEntity?
}

@Dao
interface SessionBlockDao : SyncDao<SessionBlockEntity> {
    @Query("SELECT * FROM session_blocks WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionBlockEntity>

    @Query("SELECT id FROM session_blocks")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM session_blocks ORDER BY id")
    override suspend fun exportAll(): List<SessionBlockEntity>

    @Query("SELECT * FROM session_blocks WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    suspend fun bySession(sessionId: String): List<SessionBlockEntity>
}

@Dao
interface SessionExerciseDao : SyncDao<SessionExerciseEntity> {
    @Query("SELECT * FROM session_exercises WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionExerciseEntity>

    @Query("SELECT id FROM session_exercises")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM session_exercises ORDER BY id")
    override suspend fun exportAll(): List<SessionExerciseEntity>

    @Query("SELECT * FROM session_exercises WHERE id = :id")
    suspend fun get(id: String): SessionExerciseEntity?

    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    fun observeBySession(sessionId: String): Flow<List<SessionExerciseEntity>>

    @Query("SELECT * FROM session_exercises WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY position, id")
    suspend fun bySession(sessionId: String): List<SessionExerciseEntity>

    /** Exercise ids per session in list order, for the History rows. */
    @Query("SELECT session_id, exercise_id, status FROM session_exercises WHERE deleted_at IS NULL AND status != 'removed' ORDER BY session_id, position, id")
    fun observeNames(): Flow<List<SessionExerciseName>>
}

/** One exercise of a session, for History rows. */
data class SessionExerciseName(val session_id: String, val exercise_id: String, val status: String)

/** Working sets of a session; an L+R pair counts as one (C7): `units / 2`. */
data class SessionWorkUnits(val session_id: String, val units: Int)

/** Sessions in which an exercise was trained since a date (the "most used" sort, UX 13.2). */
data class ExerciseUsage(val exercise_id: String, val sessions: Int)

/** One deviation reason of a working set since a date (WE-P10, WE-T2). */
data class ReasonRow(val exercise_id: String, val deviation_reason_code: String, val local_date: String)

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

    @Query("SELECT * FROM set_logs ORDER BY id")
    override suspend fun exportAll(): List<SetLogEntity>

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

    @Query(
        """SELECT s.started_at AS session_started_at, s.local_date AS session_local_date, l.*
           FROM set_logs l JOIN workout_sessions s ON s.id = l.session_id
           WHERE l.exercise_id = :exerciseId AND l.deleted_at IS NULL AND s.deleted_at IS NULL
             AND s.status IN ('finished', 'abandoned', 'in_progress')
           ORDER BY s.started_at DESC, s.id DESC, l.set_index, l.side, l.id
           LIMIT :limit""",
    )
    fun observeHistoryForExercise(exerciseId: String, limit: Int): Flow<List<SetWithSessionStart>>

    @Query(
        """SELECT l.session_id AS session_id, SUM(CASE WHEN l.side = 'both' THEN 2 ELSE 1 END) AS units
           FROM set_logs l WHERE l.deleted_at IS NULL AND l.set_kind != 'warmup' GROUP BY l.session_id""",
    )
    fun observeWorkUnits(): Flow<List<SessionWorkUnits>>

    @Query(
        """SELECT l.exercise_id AS exercise_id, COUNT(DISTINCT l.session_id) AS sessions
           FROM set_logs l JOIN workout_sessions s ON s.id = l.session_id
           WHERE l.deleted_at IS NULL AND s.deleted_at IS NULL AND s.local_date >= :sinceDate
           GROUP BY l.exercise_id""",
    )
    fun observeUsageSince(sinceDate: String): Flow<List<ExerciseUsage>>

    @Query(
        """SELECT l.exercise_id AS exercise_id, l.deviation_reason_code AS deviation_reason_code, s.local_date AS local_date
           FROM set_logs l JOIN workout_sessions s ON s.id = l.session_id
           WHERE l.deleted_at IS NULL AND s.deleted_at IS NULL AND s.local_date >= :sinceDate
             AND l.deviation_reason_code IN (:codes)
           ORDER BY s.local_date, l.id""",
    )
    suspend fun reasonsSince(sinceDate: String, codes: List<String>): List<ReasonRow>

    @Query("SELECT * FROM set_logs WHERE id = :id")
    suspend fun get(id: String): SetLogEntity?

    /** Every set of a session including tombstones (new set indexes never collide with a set that is restored). */
    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId ORDER BY completed_at, id")
    suspend fun bySessionAll(sessionId: String): List<SetLogEntity>
}

@Dao
interface WellnessLogDao : SyncDao<WellnessLogEntity> {
    @Query("SELECT * FROM wellness_logs WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<WellnessLogEntity>

    @Query("SELECT id FROM wellness_logs")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM wellness_logs ORDER BY id")
    override suspend fun exportAll(): List<WellnessLogEntity>

    @Query("SELECT * FROM wellness_logs WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY observed_at, id")
    suspend fun bySession(sessionId: String): List<WellnessLogEntity>

    @Query("SELECT * FROM wellness_logs WHERE session_id = :sessionId AND deleted_at IS NULL ORDER BY observed_at, id")
    fun observeBySession(sessionId: String): Flow<List<WellnessLogEntity>>

    /** Readiness answers of one kind on a local date (the elbow check is asked once a day, UX 19.4). */
    @Query("SELECT COUNT(*) FROM wellness_logs WHERE kind = :kind AND local_date = :date AND deleted_at IS NULL")
    suspend fun countKindOn(kind: String, date: String): Int

    /** Every pain report (the challenge facts' last pain date per body region, CHALLENGE_ENGINE 3.9). */
    @Query("SELECT * FROM wellness_logs WHERE kind = 'pain' AND deleted_at IS NULL ORDER BY observed_at, id")
    suspend fun painLogs(): List<WellnessLogEntity>

    /** Sessions with a pain report (the History "pain" badge). */
    @Query("SELECT DISTINCT session_id FROM wellness_logs WHERE kind = 'pain' AND deleted_at IS NULL AND session_id IS NOT NULL")
    fun observePainSessions(): Flow<List<String>>
}

@Dao
interface MetricObservationDao : SyncDao<MetricObservationEntity> {
    @Query("SELECT * FROM metric_observations WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<MetricObservationEntity>

    @Query("SELECT id FROM metric_observations")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM metric_observations ORDER BY id")
    override suspend fun exportAll(): List<MetricObservationEntity>

    @Query("SELECT * FROM metric_observations WHERE metric_code = :code AND deleted_at IS NULL ORDER BY observed_at, id")
    suspend fun byMetric(code: String): List<MetricObservationEntity>

    @Query("SELECT * FROM metric_observations WHERE metric_code = :code AND deleted_at IS NULL ORDER BY observed_at DESC, id DESC LIMIT 1")
    fun observeLatest(code: String): Flow<MetricObservationEntity?>

    /** The test-day results of one test session (UX 14.9). */
    @Query("SELECT * FROM metric_observations WHERE test_session_id = :sessionId AND deleted_at IS NULL ORDER BY observed_at, id")
    fun observeByTestSession(sessionId: String): Flow<List<MetricObservationEntity>>
}

@Dao
interface SessionEventDao : SyncDao<SessionEventEntity> {
    @Query("SELECT * FROM session_events WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<SessionEventEntity>

    @Query("SELECT id FROM session_events")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM session_events ORDER BY id")
    override suspend fun exportAll(): List<SessionEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SessionEventEntity)

    @Query("SELECT * FROM session_events WHERE event_type IN (:types) ORDER BY occurred_at, id")
    suspend fun ofTypes(types: List<String>): List<SessionEventEntity>

    @Query("SELECT * FROM session_events WHERE session_id = :sessionId ORDER BY occurred_at, id")
    suspend fun bySession(sessionId: String): List<SessionEventEntity>

    @Query("SELECT * FROM session_events WHERE event_type = :type ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeLatest(type: String, limit: Int): Flow<List<SessionEventEntity>>

    @Query("SELECT * FROM session_events WHERE session_id = :sessionId AND event_type = :type ORDER BY occurred_at, id")
    fun observeBySessionType(sessionId: String, type: String): Flow<List<SessionEventEntity>>

    @Query("SELECT * FROM session_events WHERE event_type = :type ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    suspend fun latest(type: String, limit: Int): List<SessionEventEntity>

    /** The last activity of a session (idle protection, ANDROID.md 11.5): any of its events except [ignored] types. */
    @Query("SELECT MAX(occurred_at) FROM session_events WHERE session_id = :sessionId AND event_type NOT IN (:ignored)")
    fun observeLastActivity(sessionId: String, ignored: List<String>): Flow<Long?>

    @Query("SELECT MAX(occurred_at) FROM session_events WHERE session_id = :sessionId AND event_type NOT IN (:ignored)")
    suspend fun lastActivity(sessionId: String, ignored: List<String>): Long?
}

@Dao
interface UserExercisePrefDao : SyncDao<UserExercisePrefEntity> {
    @Query("SELECT * FROM user_exercise_prefs WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<UserExercisePrefEntity>

    @Query("SELECT id FROM user_exercise_prefs")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM user_exercise_prefs ORDER BY id")
    override suspend fun exportAll(): List<UserExercisePrefEntity>

    @Query("SELECT * FROM user_exercise_prefs WHERE deleted_at IS NULL ORDER BY exercise_id, id")
    fun observeAll(): Flow<List<UserExercisePrefEntity>>

    @Query("SELECT * FROM user_exercise_prefs WHERE deleted_at IS NULL ORDER BY exercise_id, id")
    suspend fun live(): List<UserExercisePrefEntity>

    @Query("SELECT * FROM user_exercise_prefs WHERE id = :id")
    suspend fun get(id: String): UserExercisePrefEntity?
}

@Dao
interface RepPreferenceDao : SyncDao<RepPreferenceEntity> {
    @Query("SELECT * FROM rep_preferences WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<RepPreferenceEntity>

    @Query("SELECT id FROM rep_preferences")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM rep_preferences ORDER BY id")
    override suspend fun exportAll(): List<RepPreferenceEntity>

    @Query("SELECT * FROM rep_preferences WHERE deleted_at IS NULL ORDER BY scope_key, id")
    fun observeAll(): Flow<List<RepPreferenceEntity>>

    @Query("SELECT * FROM rep_preferences WHERE deleted_at IS NULL ORDER BY scope_key, id")
    suspend fun active(): List<RepPreferenceEntity>

    @Query("SELECT * FROM rep_preferences WHERE id = :id")
    suspend fun get(id: String): RepPreferenceEntity?
}

@Dao
interface ExportDao : SyncDao<ExportEntity> {
    @Query("SELECT * FROM exports WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ExportEntity>

    @Query("SELECT id FROM exports")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM exports ORDER BY id")
    override suspend fun exportAll(): List<ExportEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ExportEntity)

    /** The newest full export (monthly or manual): the "last export" line and the N-EXPORT reminder (F4). */
    @Query("SELECT * FROM exports WHERE export_kind IN ('monthly_full', 'manual_full') ORDER BY period_end DESC, id DESC LIMIT 1")
    fun observeLastFull(): Flow<ExportEntity?>

    @Query("SELECT * FROM exports WHERE export_kind IN ('monthly_full', 'manual_full') ORDER BY period_end DESC, id DESC LIMIT 1")
    suspend fun lastFull(): ExportEntity?
}

@Dao
interface CustomExerciseDao : SyncDao<CustomExerciseEntity> {
    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<CustomExerciseEntity>

    @Query("SELECT id FROM exercises")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM exercises ORDER BY id")
    override suspend fun exportAll(): List<CustomExerciseEntity>

    @Query("SELECT * FROM exercises WHERE deleted_at IS NULL ORDER BY slug")
    fun observeLive(): Flow<List<CustomExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE deleted_at IS NULL ORDER BY slug")
    suspend fun live(): List<CustomExerciseEntity>

    @Query("SELECT * FROM exercises WHERE slug = :slug")
    suspend fun bySlug(slug: String): CustomExerciseEntity?
}

@Dao
interface ExerciseStimulusDao : SyncDao<ExerciseStimulusEntity> {
    @Query("SELECT * FROM exercise_stimulus WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ExerciseStimulusEntity>

    @Query("SELECT id FROM exercise_stimulus")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM exercise_stimulus ORDER BY id")
    override suspend fun exportAll(): List<ExerciseStimulusEntity>

    @Query("SELECT * FROM exercise_stimulus WHERE deleted_at IS NULL ORDER BY exercise_id, region_id")
    fun observeLive(): Flow<List<ExerciseStimulusEntity>>

    @Query("SELECT * FROM exercise_stimulus WHERE deleted_at IS NULL ORDER BY exercise_id, region_id")
    suspend fun live(): List<ExerciseStimulusEntity>
}
