package dev.fitnessapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.fitnessapp.data.db.local.ChallengeLiveSessionEntity
import dev.fitnessapp.data.db.local.CrashLogEntity
import dev.fitnessapp.data.db.local.DeadLetterEntity
import dev.fitnessapp.data.db.local.OutboxEntity
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.db.local.ScheduledAlarmEntity
import dev.fitnessapp.data.db.local.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: OutboxEntity): Long

    /** The second half of an enqueue (ANDROID.md 8.3): plain SQL, no UPSERT (SQLite 3.24 is newer than API 26). */
    @Query(
        """UPDATE outbox SET gen = gen + 1,
             next_attempt_at_ms = CASE WHEN next_attempt_at_ms < :now THEN next_attempt_at_ms ELSE :now END
           WHERE table_name = :table AND row_id = :rowId""",
    )
    suspend fun bump(table: String, rowId: String, now: Long)

    @Query("SELECT * FROM outbox WHERE table_name = :table AND next_attempt_at_ms <= :now ORDER BY seq LIMIT :limit")
    suspend fun batch(table: String, now: Long, limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE seq = :seq AND gen = :gen")
    suspend fun deleteIfUnchanged(seq: Long, gen: Int): Int

    @Query("DELETE FROM outbox WHERE table_name = :table AND row_id = :rowId")
    suspend fun deleteRow(table: String, rowId: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE table_name = :table AND row_id = :rowId")
    suspend fun pendingCount(table: String, rowId: String): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, next_attempt_at_ms = :nextAt, last_error = :error WHERE seq = :seq")
    suspend fun deferRow(seq: Long, nextAt: Long, error: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeDepth(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun depth(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE next_attempt_at_ms <= :now")
    suspend fun readyCount(now: Long): Int

    @Query("SELECT MIN(enqueued_at_ms) FROM outbox")
    suspend fun oldestEnqueuedAt(): Long?

    @Query("SELECT MIN(enqueued_at_ms) FROM outbox")
    fun observeOldestEnqueuedAt(): Flow<Long?>

    @Query("SELECT MIN(next_attempt_at_ms) FROM outbox")
    suspend fun nextAttemptAt(): Long?
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursor WHERE table_name = :table")
    suspend fun get(table: String): SyncCursorEntity?

    @Upsert
    suspend fun put(row: SyncCursorEntity)

    @Query("DELETE FROM sync_cursor")
    suspend fun clear()
}

@Dao
interface DeadLetterDao {
    @Insert
    suspend fun insert(row: DeadLetterEntity)

    @Query("SELECT COUNT(*) FROM dead_letter")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM dead_letter ORDER BY id")
    suspend fun all(): List<DeadLetterEntity>

    @Query("DELETE FROM dead_letter WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RestTimerDao {
    @Upsert
    suspend fun upsert(row: RestTimerEntity)

    @Query("SELECT * FROM rest_timer WHERE id = :id")
    suspend fun get(id: String): RestTimerEntity?

    /** The active timer: running or overtime, newest first. */
    @Query("SELECT * FROM rest_timer WHERE state IN ('running', 'overtime') ORDER BY start_wall_ms DESC, id DESC LIMIT 1")
    suspend fun active(): RestTimerEntity?

    @Query("SELECT * FROM rest_timer WHERE session_id = :sessionId ORDER BY start_wall_ms DESC, id DESC LIMIT 1")
    fun observeLatest(sessionId: String): Flow<RestTimerEntity?>

    @Query("SELECT * FROM rest_timer WHERE session_id = :sessionId ORDER BY start_wall_ms DESC, id DESC LIMIT 1")
    suspend fun latest(sessionId: String): RestTimerEntity?

    @Query("SELECT * FROM rest_timer ORDER BY id")
    suspend fun all(): List<RestTimerEntity>

    /** Timers recovered after the process was dead at the end (ANDROID.md 12.6), for the Reliability screen (15.5). */
    @Query("SELECT COUNT(*) FROM rest_timer WHERE ended_by = 'elapsed_while_dead' AND start_wall_ms >= :sinceMs")
    fun observeRecoveredDeadSince(sinceMs: Long): Flow<Int>
}

@Dao
interface ScheduledAlarmDao {
    @Upsert
    suspend fun upsert(row: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarm WHERE request_code = :requestCode")
    suspend fun delete(requestCode: Int)

    @Query("SELECT * FROM scheduled_alarm ORDER BY trigger_at_wall_ms")
    suspend fun all(): List<ScheduledAlarmEntity>
}

@Dao
interface CrashLogDao {
    @Insert
    suspend fun insert(row: CrashLogEntity)

    @Query("SELECT * FROM crash_log WHERE reported = 0 ORDER BY id")
    suspend fun unreported(): List<CrashLogEntity>

    @Query("UPDATE crash_log SET reported = 1 WHERE id = :id")
    suspend fun markReported(id: Long)

    @Query("SELECT * FROM crash_log ORDER BY id")
    suspend fun all(): List<CrashLogEntity>
}

@Dao
interface ChallengeLiveDao {
    @Upsert
    suspend fun upsert(row: ChallengeLiveSessionEntity)

    @Query("SELECT * FROM challenge_live_session WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): ChallengeLiveSessionEntity?

    @Query("SELECT * FROM challenge_live_session WHERE session_id = :sessionId")
    fun observe(sessionId: String): Flow<ChallengeLiveSessionEntity?>
}
