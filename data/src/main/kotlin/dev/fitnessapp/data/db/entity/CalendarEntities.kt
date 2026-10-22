@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.Scale3
import kotlinx.serialization.Serializable

/**
 * `fitness.calendar_events` (DATA_MODEL 9.7.3, migration 014): classified calendar instances only. Busy events are
 * never stored here (they stay in [CalendarInstanceEntity]); an `ignored` row exists only as a user override and has
 * no title or location. Id = UUIDv5 `calendar_events:<uid>:<instance_key>`.
 */
@Serializable
@Entity(tableName = "calendar_events", indices = [Index(value = ["instance_key"], unique = true), Index("local_date"), Index("enrollment_id")])
data class CalendarEventEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val provider: String = "android_cp",
    val instance_key: String,
    val series_key: String? = null,
    val source_device_id: String,
    val classified_kind: String,
    val title: String? = null,
    val location: String? = null,
    @Serializable(with = EpochMsIso::class) val starts_at: Long,
    @Serializable(with = EpochMsIso::class) val ends_at: Long,
    val all_day: Boolean = false,
    val local_date: String,
    val timezone: String,
    val iso_week: String,
    val provider_status: String,
    val challenge_slot: String? = null,
    val enrollment_id: String? = null,
    val planned_families: List<String> = emptyList(),
    val run_intensity: String? = null,
    val run_minutes: Int? = null,
    val classifier_version: String,
    val match_method: String,
    @Serializable(with = Scale3::class) val score: Long? = null,
    val features: List<String> = emptyList(),
    val override_kind: String? = null,
    val override_slot: String? = null,
    @Serializable(with = EpochMsIso::class) val override_at: Long? = null,
    val app_written: Boolean = false,
    val marker: String? = null,
    @Serializable(with = EpochMsIso::class) val adopted_at: Long? = null,
    val write_state: String = "none",
    val attention: String? = null,
    val linked_session_id: String? = null,
    val link_method: String? = null,
    val recommendation_id: String? = null,
    @Serializable(with = EpochMsIso::class) val last_seen_at: Long,
    @Serializable(with = EpochMsIso::class) val missing_since: Long? = null,
) : MutableRow

/**
 * The local, never-synced cache of every instance of the read window (ANDROID 8.3, 16.4), busy and ignored events
 * included, with the latest classification. Rows older than 60 days are purged. [description] is kept for the
 * marker scan after a reinstall and the event sheet; it never leaves the phone.
 */
@Entity(tableName = "calendar_instance", indices = [Index("begin_ms"), Index("series_key")])
data class CalendarInstanceEntity(
    @PrimaryKey val instance_key: String,
    val series_key: String,
    val calendar_identity_sha256: String,
    val calendar_id: Long,
    val event_id: Long,
    val begin_ms: Long,
    val end_ms: Long,
    val title: String?,
    val description: String?,
    val location: String?,
    val all_day: Boolean,
    val status: String,
    val declined: Boolean,
    val classified_kind: String,
    val slot: String? = null,
    val method: String? = null,
    /** The feature score (planned gym) or the match weight x 1000 (challenge sessions). */
    val score: Int = 0,
    val match_milli: Int? = null,
    val gym_type: String? = null,
    val attention: String? = null,
    val needs_confirm: Boolean = false,
    val features_json: String = "[]",
    val last_seen_ms: Long,
)

/**
 * `calendar_link` (ANDROID 16.6, local only): the app's own events by marker (uuid, slot key) and what the app wrote
 * last. Rebuilt from the markers after a reinstall.
 */
@Entity(tableName = "calendar_link", indices = [Index(value = ["marker_uuid", "slot_key"], unique = true)])
data class CalendarLinkEntity(
    @PrimaryKey val id: String,
    val marker_uuid: String,
    val slot_key: String,
    val kind: String,
    val calendar_identity_sha256: String,
    val event_id: Long?,
    val last_written_title: String,
    val last_written_desc_sha256: String,
    val last_written_dtstart_ms: Long,
    val last_written_dtend_ms: Long,
    val state: String,
    val created_at_ms: Long,
    val updated_at_ms: Long,
)
