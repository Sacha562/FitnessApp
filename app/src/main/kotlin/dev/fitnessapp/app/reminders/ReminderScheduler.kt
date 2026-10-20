package dev.fitnessapp.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.notify.Channels
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.challenges.HabitDays
import dev.fitnessapp.data.db.local.ScheduledAlarmEntity
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

/**
 * Home-habit reminders (ANDROID.md 17, UX 18 N-HABIT): the next `reminders.horizonH` hours of due habits as exact
 * while-idle alarms (a 10 min window when exact alarms are not allowed), recomputed on app start, boot, time and zone
 * changes, daily maintenance and every challenge state change; obsolete alarms are cancelled. Nothing is scheduled
 * until habit reminders are switched on (after the D4 cutover).
 */
class ReminderScheduler(private val c: AppContainer, private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    @Volatile private var again = false

    /** Asks for a recompute; one requested while another runs runs once more after it (no overlap, no loss). */
    fun request() {
        c.appScope.launch { recompute() }
    }

    suspend fun recompute() {
        if (!mutex.tryLock()) {
            again = true
            return
        }
        try {
            do {
                again = false
                runCatching { reschedule() }
            } while (again)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun reschedule() {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val wanted = if (c.settings.habitRemindersEnabled && c.writeContext.userId() != null) plan(now) else emptyList()
        val rows = c.db.alarms().all()
        val existing = rows.filter { it.kind == KIND }
        var changed = false
        for (row in existing) {
            if (wanted.none { it.id == row.ref_id && it.dueMs == row.trigger_at_wall_ms }) {
                am.cancel(fireIntent(row.request_code, null))
                c.db.alarms().delete(row.request_code)
                changed = true
            }
        }
        val used = c.db.alarms().all().map { it.request_code }.toMutableSet()
        for (r in wanted) {
            if (existing.any { it.ref_id == r.id && it.trigger_at_wall_ms == r.dueMs }) continue
            val rc = HabitReminders.requestCode(r.id, used) ?: continue
            used += rc
            val mode = arm(rc, r.dueMs, fireIntent(rc, r))
            c.db.alarms().upsert(ScheduledAlarmEntity(rc, KIND, r.id, r.dueMs, mode, now.toEpochMilli()))
            changed = true
        }
        // A snooze survives a reboot: its alarm is armed again while it lies ahead.
        rows.filter { it.kind == KIND_SNOOZE && it.trigger_at_wall_ms > now.toEpochMilli() }.forEach { s ->
            parse(s.ref_id)?.let { (habitId, date) -> arm(s.request_code - SNOOZE_OFFSET, s.trigger_at_wall_ms, snoozeFireIntent(s.request_code - SNOOZE_OFFSET, habitId, date)) }
        }
        if (changed) {
            c.db.withTransaction {
                c.telemetry.write(
                    EventDraft(
                        type = "reminder_scheduled", entityType = "none",
                        payload = mapOf("kind" to JsonPrimitive("habit"), "count" to JsonPrimitive(wanted.size), "horizon_h" to JsonPrimitive(c.platformConfig.remindersHorizonH)),
                    ),
                )
            }
        }
    }

    /** Today and the following days the horizon reaches, each with the week plan as of that day. */
    private suspend fun plan(now: Instant): List<HabitReminder> {
        val zone = ChallengeWeek.ZONE
        val today = Week.localDate(now, zone)
        val last = Week.localDate(now.plusSeconds(c.platformConfig.remindersHorizonH * 3600L), zone)
        val days = ArrayList<HabitDayRows>()
        var d = today
        while (!d.isAfter(last)) {
            val at = if (d == today) now else Week.atLocal(d, java.time.LocalTime.MIDNIGHT, zone)
            HabitDays.of(c, d, at)?.let { days += HabitDays.rows(it) }
            d = d.plusDays(1)
        }
        return HabitReminders.plan(days, now, c.platformConfig.remindersHorizonH, zone)
    }

    /** The alarm fired (N-HABIT): posted only if the habit is still due today and was not skipped. */
    suspend fun onFire(requestCode: Int, habitId: String, date: LocalDate, dueMs: Long) {
        c.db.alarms().delete(requestCode)
        c.db.alarms().delete(requestCode + SNOOZE_OFFSET)
        if (!c.settings.habitRemindersEnabled || c.settings.habitSkipped(habitId, date)) return
        val now = System.currentTimeMillis()
        if (Week.localDate(Instant.ofEpochMilli(now), ChallengeWeek.ZONE) != date) return
        val day = HabitDays.of(c, date, Instant.ofEpochMilli(now)) ?: return
        val row = HabitDays.rows(day).rows.firstOrNull { it.habit.habitId == habitId } ?: return
        if (row.habit.state != "DUE" && row.habit.state != "SCALED") return
        if (c.notifications.postHabit(requestCode, habitId, date.toString(), row.title, row.text)) {
            c.fgsEvent(
                "notification_posted",
                mapOf("notification_id" to "N-HABIT", "channel" to Channels.HABIT_REMINDERS, "latency_ms" to now - dueMs, "platform_config_hash" to c.platformConfig.hash),
            )
        }
    }

    /** Snooze 30 min: the notification goes and comes back once at the same id. */
    suspend fun snooze(requestCode: Int, habitId: String, date: LocalDate) {
        c.notifications.cancelHabit(requestCode)
        val at = System.currentTimeMillis() + c.platformConfig.remindersSnoozeMin * 60_000L
        val mode = arm(requestCode, at, snoozeFireIntent(requestCode, habitId, date))
        c.db.alarms().upsert(ScheduledAlarmEntity(requestCode + SNOOZE_OFFSET, KIND_SNOOZE, HabitReminders.id(habitId, date), at, mode, System.currentTimeMillis()))
        action("snooze")
    }

    /** Skip today: no more reminders for this habit today (completion is never logged from a notification). */
    suspend fun skipToday(requestCode: Int, habitId: String, date: LocalDate) {
        c.notifications.cancelHabit(requestCode)
        c.settings.markHabitSkipped(habitId, date)
        am.cancel(snoozeFireIntent(requestCode, habitId, date))
        c.db.alarms().delete(requestCode + SNOOZE_OFFSET)
        action("skip_today")
    }

    suspend fun action(name: String) =
        c.fgsEvent("notification_action", mapOf("notification_id" to "N-HABIT", "channel" to Channels.HABIT_REMINDERS, "action" to name))

    private fun arm(rc: Int, atMs: Long, pi: PendingIntent): String =
        if (c.device.canScheduleExactAlarms()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                "exact_idle"
            } catch (_: SecurityException) {
                am.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, pi)
                "window"
            }
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, pi)
            "window"
        }

    private fun fireIntent(rc: Int, r: HabitReminder?): PendingIntent = PendingIntent.getBroadcast(
        context, rc,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).apply {
            if (r != null) putExtra(ReminderReceiver.EXTRA_RC, rc).putExtra(ReminderReceiver.EXTRA_HABIT, r.habitId).putExtra(ReminderReceiver.EXTRA_DATE, r.date.toString()).putExtra(ReminderReceiver.EXTRA_DUE, r.dueMs)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun snoozeFireIntent(rc: Int, habitId: String, date: LocalDate): PendingIntent = PendingIntent.getBroadcast(
        context, rc,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SNOOZE_FIRE)
            .putExtra(ReminderReceiver.EXTRA_RC, rc).putExtra(ReminderReceiver.EXTRA_HABIT, habitId).putExtra(ReminderReceiver.EXTRA_DATE, date.toString()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun parse(refId: String): Pair<String, LocalDate>? {
        val parts = refId.split(':')
        return if (parts.size == 3 && parts[0] == "habit") parts[1] to LocalDate.parse(parts[2]) else null
    }

    companion object {
        const val KIND = "habit"
        const val KIND_SNOOZE = "habit_snooze"

        /** A snooze row's key in `scheduled_alarm` (its alarm keeps the reminder's request code, 13.2). */
        const val SNOOZE_OFFSET = 10_000

        /** ANDROID.md 17: `setWindow(dueUtcMs, 10 min)` when exact alarms are unavailable. */
        private const val WINDOW_MS = 10 * 60_000L
    }
}
