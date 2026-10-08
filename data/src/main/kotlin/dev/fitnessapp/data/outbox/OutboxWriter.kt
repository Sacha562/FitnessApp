package dev.fitnessapp.data.outbox

import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.local.OutboxEntity
import dev.fitnessapp.data.sync.isAppendTable

/**
 * Enqueues a row for push (ANDROID.md 8.3). Must be called inside the Room transaction that wrote the row:
 * `INSERT OR IGNORE` on `(table_name, row_id)` followed by `gen + 1`, so one entry per row and an edit made
 * while a push is in flight is pushed again.
 */
class OutboxWriter(private val db: FitnessDatabase) {
    suspend fun enqueue(table: String, rowId: String, nowMs: Long) {
        val op = if (isAppendTable(table)) "insert_ignore" else "upsert"
        db.outbox().insertIgnore(OutboxEntity(table_name = table, row_id = rowId, op = op, enqueued_at_ms = nowMs, next_attempt_at_ms = nowMs))
        db.outbox().bump(table, rowId, nowMs)
    }
}
