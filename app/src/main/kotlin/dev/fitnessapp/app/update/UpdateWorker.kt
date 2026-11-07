package dev.fitnessapp.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.notify.Channels
import java.util.concurrent.TimeUnit

/**
 * `update-check` (D98 (2)): once a day with a network, posts N-UPDATE when a newer release is out and that version
 * was neither notified nor shown in the app before. Release build only; a failed check waits for the next day.
 */
class UpdateWorker(context: Context, params: WorkerParameters, private val c: AppContainer) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!c.updates.automatic) return Result.success()
        val r = runCatching { c.updates.newerRelease() }.getOrNull() ?: return Result.success()
        if (c.settings.lastNotifiedUpdateCode >= r.versionCode) return Result.success()
        if (c.notifications.postUpdate(r.versionName)) {
            c.settings.lastNotifiedUpdateCode = r.versionCode
            runCatching { c.fgsEvent("notification_posted", mapOf("notification_id" to "N-UPDATE", "channel" to Channels.APP_STATUS)) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun ensureScheduled(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
