package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.local.DeadLetterEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SYNC_TABLE_ORDER
import dev.fitnessapp.data.sync.SyncEngine
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.sync.SyncState
import dev.fitnessapp.data.sync.syncTables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** Sync status for the UI (ANDROID.md 9.7) and the user actions of You > Data and sync. */
class SyncRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val ctx: WriteContext,
) {
    val state: StateFlow<SyncState> get() = engine.state
    fun observePending(): Flow<Int> = db.outbox().observeDepth()

    /** Outbox depth and oldest entry, for [dev.fitnessapp.data.sync.PendingStatus]. */
    fun observeOutbox(): Flow<Pair<Int, Long?>> = combine(db.outbox().observeDepth(), db.outbox().observeOldestEnqueuedAt()) { n, oldest -> n to oldest }
    fun observeDeadLetters(): Flow<Int> = db.deadLetters().observeCount()

    fun syncNow() = scheduler.request(trigger = "manual", immediate = true)

    suspend fun deadLetters(): List<DeadLetterEntity> = db.deadLetters().all()

    /** D17 "Re-push all data": re-queues every local row of every pushed table in dependency order. */
    suspend fun rePushAll(): Int {
        var n = 0
        val now = ctx.nowMs()
        db.withTransaction {
            val tables = syncTables(db).associateBy { it.name }
            for (name in SYNC_TABLE_ORDER) {
                for (id in tables.getValue(name).dao.allIds()) {
                    outbox.enqueue(name, id, now)
                    n++
                }
            }
        }
        scheduler.request(trigger = "repush_all", immediate = true)
        return n
    }

    /** Moves dead letters back to the outbox ("Retry" in Sync status, ANDROID.md 9.5). */
    suspend fun retryDeadLetters() {
        val now = ctx.nowMs()
        db.withTransaction {
            for (d in db.deadLetters().all()) {
                outbox.enqueue(d.table_name, d.row_id, now)
                db.deadLetters().delete(d.id)
            }
        }
        scheduler.request(trigger = "retry_dead_letters", immediate = true)
    }
}
