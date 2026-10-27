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
 * `daily-maintenance` (ANDROID.md 7.8) as far as M1b needs it: the monthly export reminder N-EXPORT when the last
 * full export is older than `export.reminderDays` (F4), and N-SYNC when rows were rejected or changes have waited
 * for upload longer than `sync.staleNoticeDays`. Each reminder is posted at most once per day; a late or skipped
 * run is harmless. Challenge tick synthesis runs first (7.8, CHALLENGE_ENGINE 4.6); the calendar and habit
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
        val decision = Maintenance.decide(
            nowMs = nowMs,
            reminderEnabled = c.settings.exportReminderEnabled,
            lastExportMs = c.exports.lastFull()?.period_end,
            firstSessionMs = c.db.sessions().firstStartedAt(),
            reminderDays = c.platformConfig.exportReminderDays,
            deadLetters = c.db.deadLetters().all().size,
            oldestOutboxMs = c.db.outbox().oldestEnqueuedAt(),
            staleDays = c.platformConfig.syncStaleNoticeDays,
        )
        if (decision.export && c.settings.lastExportReminderDate != today) {
            c.settings.lastExportReminderDate = today
            if (c.notifications.postExportReminder()) c.fgsEvent("notification_posted", mapOf("notification_id" to "N-EXPORT", "channel" to Channels.APP_STATUS))
        }
        decision.syncText?.let { text ->
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

/** The reminder rules of [MaintenanceWorker], pure for tests. */
object Maintenance {
    data class Decision(val export: Boolean, val syncText: String?)

    fun decide(
        nowMs: Long,
        reminderEnabled: Boolean,
        lastExportMs: Long?,
        firstSessionMs: Long?,
        reminderDays: Int,
        deadLetters: Int,
        oldestOutboxMs: Long?,
        staleDays: Int,
    ): Decision {
        val dayMs = 86_400_000L
        // Without any export, the reminder counts from the first logged session (nothing to back up before it).
        val since = lastExportMs ?: firstSessionMs
        val export = reminderEnabled && since != null && nowMs - since >= reminderDays * dayMs
        val syncText = when {
            deadLetters > 0 -> "$deadLetters change${if (deadLetters == 1) "" else "s"} could not be uploaded"
            oldestOutboxMs != null && nowMs - oldestOutboxMs >= staleDays * dayMs -> "Changes have not been uploaded for ${(nowMs - oldestOutboxMs) / dayMs} days"
            else -> null
        }
        return Decision(export, syncText)
    }
}
