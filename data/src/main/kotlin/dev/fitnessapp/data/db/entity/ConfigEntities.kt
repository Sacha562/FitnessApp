@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scale2
import kotlinx.serialization.Serializable

/** `fitness.profiles` (DATA_MODEL 9.3.1); `id = user_id`. */
@Serializable
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val display_name: String? = null,
    val timezone: String = "Europe/Amsterdam",
    val week_start_dow: Int = 1,
    val mass_unit: String = "kg",
    val ui_language: String = "en",
    val decimal_separator: String = ".",
    val sex: String? = null,
    val birth_year: Int? = null,
    val training_age: String? = null,
    @Serializable(with = Scale2::class) val bodyweight_fallback_kg: Long? = null,
    val max_gym_visits_per_week: Int = 4,
    val default_session_min: Int = 75,
    val changeover_min: Int = 5,
    @Serializable(with = JsonText::class) val availability: String = JsonText.EMPTY_OBJECT,
    val default_gym_id: String? = null,
    val calendar_read_enabled: Boolean = true,
    val calendar_scope: String = "primary_only",
    val calendar_selected_keys: List<String> = emptyList(),
    val calendar_ignore_all_day: Boolean = true,
    val calendar_ignore_name_patterns: List<String> = listOf("(?i)todoist"),
    val calendar_write_enabled: Boolean = true,
    val calendar_write_weeks: Int = 2,
    val evidence_mode: String = "warn_only",
    val running_priority: String = "low",
    val deload_sync_with_challenge: Boolean = true,
    val allow_supersets: Boolean = false,
    val rir_calibration_opt_in: Boolean = false,
    val prompt_readiness: Boolean = true,
    val prompt_elbow_check: Boolean = true,
    val prompt_srpe: Boolean = true,
    val prompt_soreness: Boolean = true,
    val prompt_pain_after: Boolean = true,
    val rest_alarm_mode: String = "alarm_clock",
    val rest_tone_enabled: Boolean = false,
    val keep_screen_on: Boolean = true,
    val theme: String = "system",
    val dynamic_color: Boolean = false,
    val reminders_enabled: Boolean = true,
    val muscle_goal_preset: String? = null,
    @Serializable(with = EpochMsIso::class) val disclaimer_accepted_at: Long? = null,
    @Serializable(with = EpochMsIso::class) val onboarding_completed_at: Long? = null,
) : MutableRow

/** `fitness.gyms` (9.3.2). */
@Serializable
@Entity(tableName = "gyms")
data class GymEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val name: String,
    val location_keywords: List<String> = emptyList(),
    val address_note: String? = null,
    @Serializable(with = JsonText::class) val opening_hours: String = JsonText.EMPTY_OBJECT,
    val is_default: Boolean = false,
) : MutableRow

/** `fitness.gym_equipment` (9.3.3); `gym_id` null = portable (dip belt, belt plates). */
@Serializable
@Entity(tableName = "gym_equipment", indices = [Index("gym_id")])
data class GymEquipmentEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val gym_id: String? = null,
    val equipment_type: String,
    val label: String,
    val available: Boolean = true,
    val scheme_type: String,
    @Serializable(with = JsonText::class) val load_scheme: String,
    val calibration_rev: Int = 1,
    val calibration_source: String = "default",
    @Serializable(with = EpochMsIso::class) val confirmed_at: Long? = null,
) : MutableRow
