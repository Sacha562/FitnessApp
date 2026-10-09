package dev.fitnessapp.data.sync

/**
 * What the UI says about the outbox (UX 7.1). Every sync run queues its own `sync_run` event for the next run,
 * so a non-empty outbox while online is normal and not shown: only offline waits and entries older than
 * [SyncConfig.pendingLongH] are.
 */
sealed interface PendingStatus {
    data object None : PendingStatus
    data class Offline(val count: Int) : PendingStatus
    data class Long(val oldestAgeH: kotlin.Long) : PendingStatus

    companion object {
        fun of(state: SyncState, count: Int, oldestEnqueuedAtMs: kotlin.Long?, nowMs: kotlin.Long, config: SyncConfig): PendingStatus {
            if (count == 0 || oldestEnqueuedAtMs == null) return None
            val ageMs = nowMs - oldestEnqueuedAtMs
            return when {
                state is SyncState.Offline -> Offline(count)
                ageMs >= config.pendingLongH * 3_600_000L -> Long(ageMs / 3_600_000L)
                else -> None
            }
        }
    }
}
