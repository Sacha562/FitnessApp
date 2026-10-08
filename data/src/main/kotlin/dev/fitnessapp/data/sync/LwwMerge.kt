package dev.fitnessapp.data.sync

/**
 * The last-writer-wins tuple of DATA_MODEL 7.1 / 11.5: `(client_updated_at ms, device_id)`, compared
 * lexicographically; the device id compares as lower-case canonical text, which orders like PostgreSQL's
 * `uuid` comparison. The server trigger applies the identical rule.
 */
data class LwwTuple(val clientUpdatedAtMs: Long, val deviceId: String) : Comparable<LwwTuple> {
    override fun compareTo(other: LwwTuple): Int {
        val c = clientUpdatedAtMs.compareTo(other.clientUpdatedAtMs)
        return if (c != 0) c else deviceId.lowercase().compareTo(other.deviceId.lowercase())
    }
}

enum class MergeAction { INSERT, OVERWRITE, MARK_SYNCED, KEEP_LOCAL }

object LwwMerge {
    /** What to do with a pulled mutable row given the local copy (DATA_MODEL 11.5 `applyPulled`). */
    fun decide(remote: LwwTuple, local: LwwTuple?): MergeAction = when {
        local == null -> MergeAction.INSERT
        remote > local -> MergeAction.OVERWRITE
        remote == local -> MergeAction.MARK_SYNCED
        else -> MergeAction.KEEP_LOCAL
    }
}
