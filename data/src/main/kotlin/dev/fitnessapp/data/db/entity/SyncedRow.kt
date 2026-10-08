@file:Suppress("PropertyName")

package dev.fitnessapp.data.db.entity

/**
 * A row of a synced `fitness` table. Kotlin property names are the server column names (snake_case), so the
 * Room column, the JSON key and the PostgREST column are the same string (ANDROID.md 8.1, DATA_MODEL 11).
 * Mutable rows carry the LWW tuple `(client_updated_at, device_id)`; append-only rows only `device_id`.
 */
interface SyncedRow {
    val id: String
    val user_id: String
    val device_id: String
    val app_version: String
    /** Exactly as returned by PostgREST (microseconds); null until the row was pulled or echoed back. */
    val server_updated_at: String?
}

interface MutableRow : SyncedRow {
    val client_updated_at: Long
    val deleted_at: Long?
}
