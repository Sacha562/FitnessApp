package dev.fitnessapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.fitnessapp.data.db.entity.ExerciseModelStateEntity
import dev.fitnessapp.data.db.entity.ParamsSnapshotEntity
import dev.fitnessapp.data.db.entity.RecommendationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParamsSnapshotDao : SyncDao<ParamsSnapshotEntity> {
    @Query("SELECT * FROM params_snapshots WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ParamsSnapshotEntity>

    @Query("SELECT id FROM params_snapshots")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM params_snapshots ORDER BY id")
    override suspend fun exportAll(): List<ParamsSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: ParamsSnapshotEntity): Long
}

@Dao
interface RecommendationDao : SyncDao<RecommendationEntity> {
    @Query("SELECT * FROM recommendations WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<RecommendationEntity>

    @Query("SELECT id FROM recommendations")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM recommendations ORDER BY id")
    override suspend fun exportAll(): List<RecommendationEntity>

    /** Row ids of the given kinds (the estimation layer's dedupe set). */
    @Query("SELECT id FROM recommendations WHERE kind IN (:kinds)")
    suspend fun idsOfKinds(kinds: List<String>): List<String>

    @Query("SELECT * FROM recommendations WHERE kind = :kind AND scope_key = :scope ORDER BY id")
    suspend fun byScope(kind: String, scope: String): List<RecommendationEntity>

    @Query("SELECT * FROM recommendations WHERE kind = :kind AND session_id = :sessionId ORDER BY id")
    suspend fun bySession(kind: String, sessionId: String): List<RecommendationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<RecommendationEntity>): List<Long>

    /** The chain columns of the estimation snapshots: which row supersedes which (the runner's `supersedes_id`). */
    @Query("SELECT id, kind, scope_key, session_id, supersedes_id FROM recommendations WHERE kind IN (:kinds)")
    suspend fun chain(kinds: List<String>): List<SnapshotLink>

    /** SSE-1 snapshots of the finished sessions, oldest session first (the exercise detail's e1RM history). */
    @Query(
        "SELECT r.id, r.supersedes_id, r.outputs, r.session_id, s.started_at, s.bodyweight_kg FROM recommendations r " +
            "JOIN workout_sessions s ON s.id = r.session_id " +
            "WHERE r.kind = 'estimation.sse' AND s.status = 'finished' AND s.deleted_at IS NULL ORDER BY s.started_at, r.id",
    )
    fun observeSseSnapshots(): Flow<List<SseSnapshotRow>>

    /** The snapshots of [kind] tied to finished sessions, oldest session first (`estimation.sse`, `estimation.hold`). */
    @Query(
        "SELECT r.id, r.supersedes_id, r.outputs, r.session_id, s.started_at, s.bodyweight_kg FROM recommendations r " +
            "JOIN workout_sessions s ON s.id = r.session_id " +
            "WHERE r.kind = :kind AND s.status = 'finished' AND s.deleted_at IS NULL ORDER BY s.started_at, r.id",
    )
    suspend fun sessionSnapshots(kind: String): List<SseSnapshotRow>

    @Query("SELECT * FROM recommendations WHERE kind = :kind ORDER BY id")
    suspend fun ofKind(kind: String): List<RecommendationEntity>
}

/** One link of a snapshot chain (`recommendations` columns). */
data class SnapshotLink(val id: String, val kind: String, val scope_key: String, val session_id: String?, val supersedes_id: String?)

/** An SSE-1 snapshot with the session facts the e1RM history needs. */
data class SseSnapshotRow(
    val id: String,
    val supersedes_id: String?,
    val outputs: String,
    val session_id: String,
    val started_at: Long,
    val bodyweight_kg: Long?,
)

@Dao
interface ExerciseModelStateDao : SyncDao<ExerciseModelStateEntity> {
    @Query("SELECT * FROM exercise_model_state WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ExerciseModelStateEntity>

    @Query("SELECT id FROM exercise_model_state")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM exercise_model_state ORDER BY id")
    override suspend fun exportAll(): List<ExerciseModelStateEntity>

    @Query("SELECT * FROM exercise_model_state WHERE deleted_at IS NULL ORDER BY id")
    suspend fun live(): List<ExerciseModelStateEntity>

    @Query("SELECT * FROM exercise_model_state WHERE model = :model AND exercise_id = :exerciseId AND deleted_at IS NULL ORDER BY side")
    suspend fun forExercise(model: String, exerciseId: String): List<ExerciseModelStateEntity>

    /**
     * A key that changes whenever a fact the estimation layer reads changes: the finished sessions, their sets and
     * the challenge events (completions release a held-back session). Sets of the session in progress do not move it.
     */
    @Query(
        "SELECT (SELECT COUNT(*) || '/' || IFNULL(MAX(client_updated_at), 0) FROM workout_sessions WHERE status = 'finished') || ':' || " +
            "(SELECT COUNT(*) || '/' || IFNULL(MAX(l.client_updated_at), 0) FROM set_logs l JOIN workout_sessions s ON s.id = l.session_id " +
            "WHERE s.status = 'finished') || ':' || (SELECT COUNT(*) FROM challenge_events)",
    )
    fun observeFactsKey(): Flow<String>
}
