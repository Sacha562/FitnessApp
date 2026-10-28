package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scale2
import dev.fitnessapp.data.db.Scale4
import kotlinx.serialization.Serializable

/**
 * The user's own rows of `fitness.exercises` (9.2.1, MIX class, D27, D53): custom exercises copied from a pattern
 * prototype (CAT-1 10.9). Global rows stay in the bundled catalog and are never stored here. Ids are UUIDv7.
 */
@Serializable
@Entity(tableName = "exercises", indices = [Index("slug", unique = true)])
data class CustomExerciseEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val slug: String,
    val name_en: String,
    val name_nl: String? = null,
    val aliases: List<String> = emptyList(),
    val pattern: String,
    val family: String,
    val equipment: List<String>,
    val requires_station: List<String> = emptyList(),
    val load_mode: String,
    val allowed_load_modes: List<String>,
    @Serializable(with = Scale4::class) val bw_fraction: Long = 0,
    @Serializable(with = Scale2::class) val implement_base_kg: Long? = null,
    val laterality: String,
    val measure: String,
    val contraction: String = "dynamic",
    val hold_class: String? = null,
    val ecc_emphasis: Boolean = false,
    val mechanics: String,
    val curve_class: String? = null,
    val rest_class: String,
    val safety_class: String,
    val grip: String = "none",
    val stretch_emphasis: Int = 0,
    val stability: Int = 1,
    val skill: Int = 1,
    val axial: Int = 0,
    val systemic: Int = 1,
    val sfr: Int = 3,
    val setup_s: Int = 30,
    val grip_demand: Int = 0,
    val js_elbow_medial: Int = 0,
    val js_elbow_lateral: Int = 0,
    val js_shoulder_ant: Int = 0,
    val js_shoulder_overhead: Int = 0,
    val js_wrist: Int = 0,
    val js_lumbar: Int = 0,
    val js_knee: Int = 0,
    val js_hip: Int = 0,
    val tags: List<String> = emptyList(),
    val flags_offered: List<String> = emptyList(),
    @Serializable(with = JsonText::class) val external_refs: String = JsonText.EMPTY_OBJECT,
    val evidence_note: String? = null,
    val prototype_exercise_id: String? = null,
    val catalog_version: String,
    @Serializable(with = EpochMsIso::class) val retired_at: Long? = null,
) : MutableRow

/** The region coefficients of a custom exercise (`fitness.exercise_stimulus`, user rows; absent row = 0). */
@Serializable
@Entity(tableName = "exercise_stimulus", indices = [Index("exercise_id")])
data class ExerciseStimulusEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val exercise_id: String,
    val region_id: String,
    @Serializable(with = Scale2::class) val coeff: Long,
    val evidence: String? = null,
    val note: String? = null,
) : MutableRow
