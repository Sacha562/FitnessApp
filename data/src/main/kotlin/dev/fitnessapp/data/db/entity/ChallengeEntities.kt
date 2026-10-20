@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.JsonText
import kotlinx.serialization.Serializable

/**
 * `fitness.challenge_definitions` (DATA_MODEL 9.4.1): immutable published definitions. Global rows are pulled
 * (DEF-PULL) or come with the app (`source = bundled`, DATA_MODEL 11.10); the phone never pushes them, so this is
 * not a [SyncedRow]. [server_checksum] is null until the row was seen on the server.
 */
@Serializable
@Entity(tableName = "challenge_definitions", indices = [Index(value = ["slug", "version"])])
data class ChallengeDefinitionEntity(
    @PrimaryKey val id: String,
    val user_id: String? = null,
    val slug: String,
    val version: String,
    val status: String,
    val schema_version: String,
    val engine_range: String,
    val title_en: String,
    val title_nl: String? = null,
    @Serializable(with = JsonText::class) val definition: String,
    val jcs_sha256: String,
    val server_checksum: String? = null,
    val supersedes_id: String? = null,
    @Serializable(with = EpochMsIso::class) val published_at: Long? = null,
    @Serializable(with = EpochMsIso::class) val retired_at: Long? = null,
    val server_updated_at: String? = null,
    /** Local only: `bundled` or `server`. */
    val source: String = "server",
)

/**
 * `fitness.challenge_enrollments` (9.4.2, LWW-REV): the cached fold of the enrollment's events. The phone refolds
 * from `challenge_events` whenever the cache is stale (CHALLENGE_ENGINE 4.11); [state] is `StateJson`.
 */
@Serializable
@Entity(tableName = "challenge_enrollments", indices = [Index("definition_slug"), Index("status")])
data class ChallengeEnrollmentEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    @Serializable(with = EpochMsIso::class) override val client_updated_at: Long,
    override val server_updated_at: String? = null,
    @Serializable(with = EpochMsIso::class) override val deleted_at: Long? = null,
    override val device_id: String,
    override val app_version: String,
    val definition_id: String,
    val definition_slug: String,
    val definition_version: String,
    val definition_jcs_sha256: String,
    val status: String = "onboarding",
    val mode: String? = null,
    val phase: Int? = null,
    val block_index: Int? = null,
    val block_week: Int? = null,
    val week_ordinal: Int? = null,
    @Serializable(with = JsonText::class) val params: String = JsonText.EMPTY_OBJECT,
    @Serializable(with = JsonText::class) val rule_decisions: String = JsonText.EMPTY_OBJECT,
    @Serializable(with = JsonText::class) val state: String = JsonText.EMPTY_OBJECT,
    val state_rev: Int = 0,
    val state_hash: String? = null,
    val fold_generation: Int = 0,
    val last_folded_event_id: String? = null,
    val engine_version: String,
    val started_on: String? = null,
    @Serializable(with = EpochMsIso::class) val paused_at: Long? = null,
    @Serializable(with = EpochMsIso::class) val completed_at: Long? = null,
    @Serializable(with = EpochMsIso::class) val abandoned_at: Long? = null,
) : MutableRow

/** `fitness.challenge_events` (9.7.1, APPEND): the enrollment's input events; payload per CHALLENGE_ENGINE 4.2. */
@Serializable
@Entity(
    tableName = "challenge_events",
    indices = [
        Index(value = ["enrollment_id", "occurred_at", "id"]),
        Index(value = ["enrollment_id", "dedupe_key"], unique = true),
        Index("source_session_id"),
    ],
)
data class ChallengeEventEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    override val server_updated_at: String? = null,
    override val device_id: String,
    override val app_version: String,
    val enrollment_id: String,
    val event_type: String,
    @Serializable(with = EpochMsIso::class) val occurred_at: Long,
    val local_date: String,
    val dedupe_key: String? = null,
    @Serializable(with = JsonText::class) val payload: String = JsonText.EMPTY_OBJECT,
    val source_session_id: String? = null,
    val source_wellness_log_id: String? = null,
    val supersedes_event_id: String? = null,
    val definition_version: String,
    val definition_jcs_sha256: String,
    val engine_version: String,
) : SyncedRow

/** `fitness.challenge_rule_events` (9.7.2, APPEND): one audit row per rule whose condition was true or errored. */
@Serializable
@Entity(
    tableName = "challenge_rule_events",
    indices = [Index(value = ["enrollment_id", "fold_generation"]), Index("challenge_event_id")],
)
data class ChallengeRuleEventEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    override val server_updated_at: String? = null,
    override val device_id: String,
    override val app_version: String,
    val enrollment_id: String,
    val challenge_event_id: String,
    val evaluation_id: String,
    val fold_generation: Int = 0,
    val rule_id: String,
    val rule_tag: String,
    val proposal_id: String? = null,
    val rule_decision: String? = null,
    val stage: String,
    val prio: Int,
    val outcome: String,
    val cond_value: Boolean? = null,
    val eval_error: String? = null,
    @Serializable(with = JsonText::class) val bindings: String = JsonText.EMPTY_OBJECT,
    @Serializable(with = JsonText::class) val proposals: String = "[]",
    @Serializable(with = JsonText::class) val applied: String = JsonText.EMPTY_OBJECT,
    val suppressed_by: String? = null,
    val state_rev_before: Int,
    val state_rev_after: Int,
    val state_hash_before: String? = null,
    val state_hash_after: String? = null,
    val cascade_depth: Int = 0,
    val derived_event_type: String? = null,
    val definition_slug: String,
    val definition_version: String,
    val definition_jcs_sha256: String,
    val module_version: String,
    val engine_version: String,
) : SyncedRow
