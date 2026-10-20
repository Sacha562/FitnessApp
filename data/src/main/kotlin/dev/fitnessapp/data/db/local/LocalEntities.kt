@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Pending push of one row (ANDROID.md 8.3, C40). No payload copy: the push reads the entity, which coalesces
 * edits. `gen` increments on every enqueue of an already queued row; a successful push deletes the entry only
 * if `(seq, gen)` is unchanged.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["table_name", "row_id"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val table_name: String,
    val row_id: String,
    /** `upsert` or `insert_ignore`. */
    val op: String,
    val gen: Int = 0,
    val enqueued_at_ms: Long,
    val attempts: Int = 0,
    val next_attempt_at_ms: Long,
    val last_error: String? = null,
)

/** Pull cursor per table (ANDROID.md 9.6, DATA_MODEL 11.5). */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val table_name: String,
    val server_updated_at: String?,
    val last_id: String?,
    val updated_at_ms: Long,
)

/** Rows the server rejected permanently (ANDROID.md 9.5); never deleted automatically. */
@Serializable
@Entity(tableName = "dead_letter")
data class DeadLetterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val table_name: String,
    val row_id: String,
    val http_status: Int?,
    val error_code: String?,
    val message: String?,
    val payload_json: String?,
    val first_failed_at_ms: Long,
    val attempts: Int,
)

/** The active and last rest timer per session (ANDROID.md 12.2). Timestamps only, never a counter. */
@Serializable
@Entity(tableName = "rest_timer", indices = [Index("session_id"), Index("state")])
data class RestTimerEntity(
    @PrimaryKey val id: String,
    val session_id: String,
    val set_log_id: String,
    val exercise_id: String,
    val side: String,
    /** `rest` or `between_sides`. */
    val purpose: String,
    /** `rest_module`, `challenge` or `protocol`. */
    val duration_source: String,
    val prescribed_s: Int,
    val hard_max_s: Int,
    val start_wall_ms: Long,
    val start_elapsed_ms: Long,
    val boot_count: Int,
    val end_wall_ms: Long,
    /** `[{"at_wall_ms":…,"delta_s":±15|±60,"via":"button"}]` */
    val adjustments_json: String = "[]",
    /** `running`, `overtime`, `ended`. */
    val state: String,
    val ended_by: String? = null,
    /** `alarm_clock`, `exact_idle` or `inexact`. */
    val alarm_mode: String,
    val fired_wall_ms: Long? = null,
    val latency_ms: Long? = null,
    val overtime_reminder_sent: Boolean = false,
    val skip_at_ms: Long? = null,
)

/** Every alarm the app scheduled, for rescheduling after boot, force-stop or update (ANDROID.md 8.3). */
@Serializable
@Entity(tableName = "scheduled_alarm")
data class ScheduledAlarmEntity(
    @PrimaryKey val request_code: Int,
    val kind: String,
    val ref_id: String,
    val trigger_at_wall_ms: Long,
    val mode: String,
    val created_at_ms: Long,
)

/** Local crash record, summarised into an `app_crash` event at the next start (ANDROID.md 7.7). */
@Serializable
@Entity(tableName = "crash_log")
data class CrashLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at_ms: Long,
    val exception_class: String,
    val stack_sha256: String,
    val top_frame: String,
    val app_version: String,
    val reported: Boolean = false,
)

/**
 * The live plan of a challenge session (CHALLENGE_ENGINE 6.1, 6.10): the materialized plan at start and, per item
 * key, the rows as in-session rules changed them, the rules applied and the rules undone. Never synced: the plan is
 * reproducible from the enrollment and the logged sets, this copy only survives process death mid-session.
 */
@Serializable
@Entity(tableName = "challenge_live_session")
data class ChallengeLiveSessionEntity(
    @PrimaryKey val session_id: String,
    val enrollment_id: String,
    val plan_json: String,
    val live_json: String,
    val updated_at_ms: Long,
)
