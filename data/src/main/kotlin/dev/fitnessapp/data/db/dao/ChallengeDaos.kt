package dev.fitnessapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.fitnessapp.data.db.entity.ChallengeDefinitionEntity
import dev.fitnessapp.data.db.entity.ChallengeEnrollmentEntity
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.ChallengeRuleEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeDefinitionDao {
    @Upsert
    suspend fun upsert(row: ChallengeDefinitionEntity)

    @Query("SELECT * FROM challenge_definitions WHERE id = :id")
    suspend fun get(id: String): ChallengeDefinitionEntity?

    @Query("SELECT * FROM challenge_definitions WHERE status <> 'draft' ORDER BY slug, version")
    suspend fun all(): List<ChallengeDefinitionEntity>

    @Query("SELECT * FROM challenge_definitions WHERE status <> 'draft' ORDER BY slug, version")
    fun observeAll(): Flow<List<ChallengeDefinitionEntity>>
}

@Dao
interface ChallengeEnrollmentDao : SyncDao<ChallengeEnrollmentEntity> {
    @Query("SELECT * FROM challenge_enrollments WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ChallengeEnrollmentEntity>

    @Query("SELECT id FROM challenge_enrollments")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM challenge_enrollments ORDER BY id")
    override suspend fun exportAll(): List<ChallengeEnrollmentEntity>

    @Query("SELECT * FROM challenge_enrollments WHERE id = :id")
    suspend fun get(id: String): ChallengeEnrollmentEntity?

    /** Enrollments that are not finished: `onboarding`, `active` or `paused` (one per slug, 4.5). */
    @Query("SELECT * FROM challenge_enrollments WHERE status IN ('onboarding', 'active', 'paused') AND deleted_at IS NULL ORDER BY id")
    suspend fun open(): List<ChallengeEnrollmentEntity>

    @Query("SELECT * FROM challenge_enrollments WHERE status IN ('onboarding', 'active', 'paused') AND deleted_at IS NULL ORDER BY id")
    fun observeOpen(): Flow<List<ChallengeEnrollmentEntity>>

    @Query("SELECT * FROM challenge_enrollments WHERE deleted_at IS NULL ORDER BY id")
    fun observeAll(): Flow<List<ChallengeEnrollmentEntity>>
}

@Dao
interface ChallengeEventDao : SyncDao<ChallengeEventEntity> {
    @Query("SELECT * FROM challenge_events WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ChallengeEventEntity>

    @Query("SELECT id FROM challenge_events")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM challenge_events ORDER BY id")
    override suspend fun exportAll(): List<ChallengeEventEntity>

    /** Returns -1 when the id or the `(enrollment_id, dedupe_key)` pair already exists (idempotent ticks, 4.6). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: ChallengeEventEntity): Long

    /** The fold order of 4.3. */
    @Query("SELECT * FROM challenge_events WHERE enrollment_id = :enrollmentId ORDER BY occurred_at, id")
    suspend fun forEnrollment(enrollmentId: String): List<ChallengeEventEntity>

    @Query("SELECT COUNT(*) FROM challenge_events WHERE enrollment_id = :enrollmentId")
    suspend fun count(enrollmentId: String): Int

    /** The challenge events a session caused (`session.completed`, pain and readiness reports during it). */
    @Query("SELECT * FROM challenge_events WHERE source_session_id = :sessionId ORDER BY occurred_at, id")
    suspend fun bySourceSession(sessionId: String): List<ChallengeEventEntity>

    /** Every input event except ticks, newest first (History, UX 14.5). */
    @Query("SELECT * FROM challenge_events WHERE enrollment_id = :enrollmentId AND event_type NOT IN ('day.tick', 'week.started') ORDER BY occurred_at DESC, id DESC")
    fun observeHistory(enrollmentId: String): Flow<List<ChallengeEventEntity>>
}

@Dao
interface ChallengeRuleEventDao : SyncDao<ChallengeRuleEventEntity> {
    @Query("SELECT * FROM challenge_rule_events WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<ChallengeRuleEventEntity>

    @Query("SELECT id FROM challenge_rule_events")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM challenge_rule_events ORDER BY id")
    override suspend fun exportAll(): List<ChallengeRuleEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<ChallengeRuleEventEntity>): List<Long>

    @Query("SELECT * FROM challenge_rule_events WHERE challenge_event_id = :eventId AND fold_generation = :generation ORDER BY cascade_depth, prio DESC, rule_id")
    suspend fun forEvent(eventId: String, generation: Int): List<ChallengeRuleEventEntity>

    /** The current generation's audit (History, UX 14.5; reviews use only the current generation, 9.7.2). */
    @Query("SELECT * FROM challenge_rule_events WHERE enrollment_id = :enrollmentId AND fold_generation = :generation ORDER BY id")
    fun observeGeneration(enrollmentId: String, generation: Int): Flow<List<ChallengeRuleEventEntity>>
}
