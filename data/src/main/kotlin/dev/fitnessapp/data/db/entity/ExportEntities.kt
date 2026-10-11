@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.fitnessapp.data.db.EpochMsIso
import dev.fitnessapp.data.db.JsonText
import kotlinx.serialization.Serializable

/**
 * `fitness.exports` (DATA_MODEL 9.8.3, APPEND): one row per written export file. The file never leaves the phone
 * through sync; `period_end` is the export instant of a full export (15.5), which the "last export" line reads.
 */
@Serializable
@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey override val id: String,
    override val user_id: String,
    override val server_updated_at: String? = null,
    override val device_id: String,
    override val app_version: String,
    val export_kind: String,
    val format: String = "json_gz",
    val schema_version: String,
    @Serializable(with = JsonText::class) val table_row_counts: String,
    val bytes: Long,
    val sha256: String,
    val destination: String,
    @Serializable(with = EpochMsIso::class) val period_start: Long? = null,
    @Serializable(with = EpochMsIso::class) val period_end: Long? = null,
    val includes_local_only: Boolean = true,
    val engine_version: String,
    val catalog_version: String,
) : SyncedRow
