package dev.fitnessapp.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.fitnessapp.data.db.entity.CalendarEventEntity
import dev.fitnessapp.data.db.entity.CalendarInstanceEntity
import dev.fitnessapp.data.db.entity.CalendarLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao : SyncDao<CalendarEventEntity> {
    @Query("SELECT * FROM calendar_events WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<CalendarEventEntity>

    @Query("SELECT id FROM calendar_events")
    override suspend fun allIds(): List<String>

    @Query("SELECT * FROM calendar_events ORDER BY id")
    override suspend fun exportAll(): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE instance_key = :key")
    suspend fun byInstanceKey(key: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events WHERE deleted_at IS NULL AND local_date >= :from AND local_date < :to ORDER BY starts_at, instance_key")
    suspend fun between(from: String, to: String): List<CalendarEventEntity>

    /** The latest row of a vanished app event, by its last marker line. */
    @Query("SELECT * FROM calendar_events WHERE marker = :marker AND missing_since IS NOT NULL AND deleted_at IS NULL ORDER BY last_seen_at DESC LIMIT 1")
    suspend fun missingWithMarker(marker: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events WHERE linked_session_id = :sessionId AND deleted_at IS NULL LIMIT 1")
    suspend fun linkedTo(sessionId: String): CalendarEventEntity?

    /** Rows that carry a user override (C20), by instance or series. */
    @Query("SELECT * FROM calendar_events WHERE deleted_at IS NULL AND override_kind IS NOT NULL ORDER BY instance_key")
    suspend fun overrides(): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE deleted_at IS NULL AND override_kind IS NOT NULL ORDER BY starts_at DESC")
    fun observeOverrides(): Flow<List<CalendarEventEntity>>
}

@Dao
interface CalendarInstanceDao {
    @Upsert
    suspend fun upsertAll(rows: List<CalendarInstanceEntity>)

    @Query("SELECT * FROM calendar_instance WHERE begin_ms >= :from AND begin_ms < :to ORDER BY begin_ms, event_id, instance_key")
    suspend fun between(from: Long, to: Long): List<CalendarInstanceEntity>

    @Query("SELECT * FROM calendar_instance WHERE begin_ms >= :from AND begin_ms < :to ORDER BY begin_ms, event_id, instance_key")
    fun observeBetween(from: Long, to: Long): Flow<List<CalendarInstanceEntity>>

    @Query("SELECT * FROM calendar_instance WHERE instance_key = :key")
    suspend fun get(key: String): CalendarInstanceEntity?

    /** Instances of the window that the provider no longer returned. */
    @Query("DELETE FROM calendar_instance WHERE begin_ms >= :from AND begin_ms < :to AND last_seen_ms < :seenBefore")
    suspend fun deleteUnseen(from: Long, to: Long, seenBefore: Long)

    @Query("DELETE FROM calendar_instance WHERE end_ms < :before")
    suspend fun purgeBefore(before: Long)
}

@Dao
interface CalendarLinkDao {
    @Upsert
    suspend fun upsert(row: CalendarLinkEntity)

    @Query("SELECT * FROM calendar_link ORDER BY marker_uuid, slot_key")
    suspend fun all(): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_link ORDER BY last_written_dtstart_ms")
    fun observeAll(): Flow<List<CalendarLinkEntity>>

    @Query("SELECT * FROM calendar_link WHERE marker_uuid = :uuid AND slot_key = :slotKey")
    suspend fun byMarker(uuid: String, slotKey: String): CalendarLinkEntity?

    @Query("DELETE FROM calendar_link WHERE id = :id")
    suspend fun delete(id: String)
}
