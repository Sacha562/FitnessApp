package dev.fitnessapp.app.maintenance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.notify.Channels
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * `daily-maintenance` (ANDROID.md 7.8) as far as M1b needs it: N-SYNC when rows were rejected or changes have waited
 * for upload longer than `sync.staleNoticeDays`, at most once per day; a late or skipped run is harmless. The monthly
 * export reminder N-EXPORT was removed (D128). Challenge tick synthesis runs first (7.8, CHALLENGE_ENGINE 4.6); the calendar and habit
 * reminders join this job later in M2b.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters, private val c: AppContainer) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { run(System.currentTimeMillis()) }
        return Result.success()
    }

    suspend fun run(nowMs: Long) {
        if (c.writeContext.userId() == null) return
        runCatching { c.challenges.catchUp(java.time.Instant.ofEpochMilli(nowMs)) }
        // A challenge session held back for its completion event is estimated once the wait is over (M3.2).
        runCatching { c.estimation.run() }
        c.reminders.recompute()
        // ANDROID 16.4: the 05:00 run reads the calendar and writes the (possibly new) current and next week.
        runCatching { c.calendar.refresh("maintenance") }
        val today = LocalDate.now(ZONE).toString()
        val syncText = Maintenance.syncText(
            nowMs = nowMs,
            deadLetters = c.db.deadLetters().all().size,
            oldestOutboxMs = c.db.outbox().oldestEnqueuedAt(),
            staleDays = c.platformConfig.syncStaleNoticeDays,
        )
        syncText?.let { text ->
            if (c.settings.lastSyncNoticeDate != today) {
                c.settings.lastSyncNoticeDate = today
                if (c.notifications.postSyncNotice(text)) c.fgsEvent("notification_posted", mapOf("notification_id" to "N-SYNC", "channel" to Channels.APP_STATUS))
            }
        }
    }

    companion object {
        private const val NAME = "daily-maintenance"
        private val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

        /** Daily at about 05:00 in the profile zone (ANDROID.md 7.8). */
        fun ensureScheduled(context: Context) {
            val now = ZonedDateTime.now(ZONE)
            var next = now.withHour(5).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** The reminder rule of [MaintenanceWorker], pure for tests: the N-SYNC text, or null when nothing is wrong. */
object Maintenance {
    fun syncText(nowMs: Long, deadLetters: Int, oldestOutboxMs: Long?, staleDays: Int): String? {
        val dayMs = 86_400_000L
        return when {
            deadLetters > 0 -> "$deadLetters change${if (deadLetters == 1) "" else "s"} could not be uploaded"
            oldestOutboxMs != null && nowMs - oldestOutboxMs >= staleDays * dayMs -> "Changes have not been uploaded for ${(nowMs - oldestOutboxMs) / dayMs} days"
            else -> null
        }
    }
}
