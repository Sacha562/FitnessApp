@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.JsonText
import kotlinx.serialization.Serializable

/**
 * `fitness.params_snapshots` (DATA_MODEL 9.5.1, APPEND): the effective params object behind a `params_hash`, so a
 * snapshot can be replayed. One row per user and hash; the id is UUIDv5 `params_snapshots:<uid>:<params_hash>`.
 */
@Serializable
@Entity(tableName = "params_snapshots", indices = [Index(value = ["params_hash"], unique = true)])
data class ParamsSnapshotEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    override val server_updated_at: String? = null,
    override val device_id: String,
    override val app_version: String,
    val params_hash: String,
    val params_version: String,
    @Serializable(with = JsonText::class) val params: String,
    val user_param_ids: List<String> = emptyList(),
) : SyncedRow

/**
 * `fitness.recommendations` (DATA_MODEL 9.5.2, APPEND): the C24 snapshot of one engine decision. The estimation
 * layer derives the id from its content (UUIDv5 of kind, scope, session, module version, params hash and inputs
 * hash), so a replay of the same facts never adds a row, here or on the server.
 */
@Serializable
@Entity(
    tableName = "recommendations",
    indices = [Index(value = ["kind", "scope_key"]), Index("session_id")],
)
data class RecommendationEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    override val server_updated_at: String? = null,
    override val device_id: String,
    override val app_version: String,
    val kind: String,
    val module_id: String,
    val module_version: String,
    val engine_version: String,
    val params_hash: String,
    val catalog_version: String,
    val challenge_def_sha256: List<String> = emptyList(),
    val inputs_hash: String,
    @Serializable(with = JsonText::class) val inputs: String?,
    val inputs_bytes: Int,
    @Serializable(with = JsonText::class) val outputs: String,
    @Serializable(with = JsonText::class) val trace: String = "[]",
    @Serializable(with = JsonText::class) val warnings: String = "[]",
    val scope_key: String,
    val scope_date: String? = null,
    val iso_week: String? = null,
    val session_id: String? = null,
    val session_exercise_id: String? = null,
    val set_log_id: String? = null,
    val enrollment_id: String? = null,
    val supersedes_id: String? = null,
    @Serializable(with = EpochMsIso::class) val inputs_pruned_at: Long? = null,
) : SyncedRow

/**
 * `fitness.exercise_model_state` (DATA_MODEL 9.8.2, CACHE, LWW): one estimator state per model, exercise, side and
 * class key. Never authoritative: the estimation layer rebuilds it from the facts. The id is UUIDv5
 * `exercise_model_state:<uid>:<model>:<exercise_id or ->:<side>:<class_key or ->`.
 */
@Serializable
@Entity(tableName = "exercise_model_state", indices = [Index(value = ["model", "exercise_id"])])
data class ExerciseModelStateEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val model: String,
    val exercise_id: String? = null,
    val side: String = "both",
    val class_key: String? = null,
    val module_version: String,
    val engine_version: String,
    val params_hash: String,
    @Serializable(with = JsonText::class) val state: String,
    val state_hash: String,
    val n_observations: Int = 0,
    @Serializable(with = EpochMsIso::class) val as_of: Long,
    val as_of_session_id: String? = null,
) : MutableRow
