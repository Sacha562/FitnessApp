package dev.fitnessapp.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Runs one sync (ANDROID.md 7.8, 9.4): transient failures return `retry` (exponential backoff), permanent ones
 * are dead-lettered by the engine, so the periodic chain never fails.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: "worker"
        return when (engine.run(trigger)) {
            RunOutcome.SUCCESS, RunOutcome.STOPPED -> Result.success()
            RunOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}

/** Enqueues the sync work of ANDROID.md 7.8 / 9.3; never syncs in the sandbox build (F27). */
class SyncScheduler(
    /** Resolved on first use, so a disabled scheduler (sandbox, tests) never touches WorkManager. */
    private val workManagerProvider: () -> WorkManager,
    private val config: SyncConfig,
    private val enabled: Boolean,
) {
    private val workManager: WorkManager by lazy(workManagerProvider)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** After a committed local write (debounced) or with zero delay for foreground / "Sync now". */
    fun request(trigger: String = "write", immediate: Boolean = false) {
        if (!enabled) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(if (immediate) 0L else config.debounceS.toLong(), TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.backoffInitialS.toLong(), TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger))
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC, if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Session finished: get it to the server quickly. */
    fun expedited() {
        if (!enabled) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.backoffInitialS.toLong(), TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to "session_finished"))
            .build()
        workManager.enqueueUniqueWork(WORK_EXPEDITED, ExistingWorkPolicy.REPLACE, req)
    }

    fun ensurePeriodic() {
        if (!enabled) return
        val req = PeriodicWorkRequestBuilder<SyncWorker>(config.periodicH.toLong(), TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.backoffInitialS.toLong(), TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to "periodic"))
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val WORK_SYNC = "sync"
        const val WORK_EXPEDITED = "sync-expedited"
        const val WORK_PERIODIC = "sync-periodic"
    }
}
