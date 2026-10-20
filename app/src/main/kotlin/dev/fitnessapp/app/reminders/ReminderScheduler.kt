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
 * Home-habit reminders (ANDROID.md 17, UX 18 N-HABIT): the next `reminders.horizonH` hours of due habits, re-armed on
 * app start, boot, update, time and zone changes, daily maintenance and every challenge state change; obsolete alarms
 * are cancelled. Nothing is scheduled until habit reminders are switched on (after the D4 cutover).
 *
 * Two stages, because OxygenOS turned `setExactAndAllowWhileIdle` into an inexact alarm that fired at the end of a
 * window of 0.75 x the lead time, up to 1 h late (measured 2026-10-21): more than `reminders.preLeadMin` ahead, a
 * pre-alarm with a 10 min window wakes the app 15-5 min before the time and arms an alarm-clock alarm for the time
 * itself (never deferred; the status-bar alarm icon shows only for those minutes); closer, the alarm clock directly.
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
                am.cancel(fireIntent(row.request_code, null, null, 0))
                am.cancel(preIntent(row.request_code, null, null, 0, snooze = false))
                c.db.alarms().delete(row.request_code)
                changed = true
            }
        }
        val kept = c.db.alarms().all()
        val used = kept.map { it.request_code }.toMutableSet()
        for (r in wanted) {
            // Every recompute arms again: a reboot, an update or a force stop drops the alarms but keeps these rows.
            val row = kept.firstOrNull { it.kind == KIND && it.ref_id == r.id && it.trigger_at_wall_ms == r.dueMs }
            val rc = row?.request_code ?: HabitReminders.requestCode(r.id, used) ?: continue
            used += rc
            val mode = arm(rc, r.dueMs, r.habitId, r.date, snooze = false)
            c.db.alarms().upsert(ScheduledAlarmEntity(rc, KIND, r.id, r.dueMs, mode, now.toEpochMilli()))
            if (row == null) changed = true
        }
        // A snooze survives a reboot: its alarm is armed again while it lies ahead.
        rows.filter { it.kind == KIND_SNOOZE && it.trigger_at_wall_ms > now.toEpochMilli() }.forEach { s ->
            parse(s.ref_id)?.let { (habitId, date) -> arm(s.request_code - SNOOZE_OFFSET, s.trigger_at_wall_ms, habitId, date, snooze = true) }
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
        android.util.Log.i(TAG, "fired $habitId $date late_ms=${System.currentTimeMillis() - dueMs}")
        if (habitId == PROBE) return
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
        val mode = arm(requestCode, at, habitId, date, snooze = true)
        c.db.alarms().upsert(ScheduledAlarmEntity(requestCode + SNOOZE_OFFSET, KIND_SNOOZE, HabitReminders.id(habitId, date), at, mode, System.currentTimeMillis()))
        action("snooze")
    }

    /** Skip today: no more reminders for this habit today (completion is never logged from a notification). */
    suspend fun skipToday(requestCode: Int, habitId: String, date: LocalDate) {
        c.notifications.cancelHabit(requestCode)
        c.settings.markHabitSkipped(habitId, date)
        am.cancel(fireIntent(requestCode, habitId, date, 0, snooze = true))
        am.cancel(preIntent(requestCode, habitId, date, 0, snooze = true))
        c.db.alarms().delete(requestCode + SNOOZE_OFFSET)
        action("skip_today")
    }

    suspend fun action(name: String) =
        c.fgsEvent("notification_action", mapOf("notification_id" to "N-HABIT", "channel" to Channels.HABIT_REMINDERS, "action" to name))

    /** Debug builds only (DebugActivity `reminder_probe`): one reminder through [arm], to measure the OEM's delivery. */
    internal fun probe(inS: Int): String {
        val at = System.currentTimeMillis() + inS * 1000L
        return arm(PROBE_RC, at, PROBE, Week.localDate(Instant.ofEpochMilli(at), ChallengeWeek.ZONE), snooze = false)
    }

    /** Stage 1 or 2 for the reminder at [dueMs] (see the class comment); returns the `scheduled_alarm.mode`. */
    private fun arm(rc: Int, dueMs: Long, habitId: String, date: LocalDate, snooze: Boolean): String {
        val preLead = c.platformConfig.remindersPreLeadMin * 60_000L
        if (dueMs - System.currentTimeMillis() <= preLead) return armClock(rc, dueMs, habitId, date, snooze)
        am.setWindow(AlarmManager.RTC_WAKEUP, dueMs - preLead, WINDOW_MS, preIntent(rc, habitId, date, dueMs, snooze))
        return "pre_window"
    }

    private fun armClock(rc: Int, dueMs: Long, habitId: String, date: LocalDate, snooze: Boolean): String {
        val fire = fireIntent(rc, habitId, date, dueMs, snooze)
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(dueMs, showIntent(rc, habitId)), fire)
            "alarm_clock"
        } catch (_: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, dueMs, WINDOW_MS, fire)
            "window"
        }
    }

    /** Stage 1 woke the app 15-5 min before a reminder: arm the alarm clock for its time if it is still wanted. */
    fun onPre(requestCode: Int, habitId: String, date: LocalDate, dueMs: Long, snooze: Boolean) {
        android.util.Log.i(TAG, "pre $habitId $date lead_ms=${dueMs - System.currentTimeMillis()}")
        if (habitId != PROBE && (!c.settings.habitRemindersEnabled || c.settings.habitSkipped(habitId, date))) return
        armClock(requestCode, dueMs, habitId, date, snooze)
    }

    private fun extras(i: Intent, rc: Int, habitId: String?, date: LocalDate?, dueMs: Long): Intent = i.apply {
        if (habitId != null && date != null) {
            putExtra(ReminderReceiver.EXTRA_RC, rc).putExtra(ReminderReceiver.EXTRA_HABIT, habitId)
                .putExtra(ReminderReceiver.EXTRA_DATE, date.toString()).putExtra(ReminderReceiver.EXTRA_DUE, dueMs)
        }
    }

    /** The alarm of the reminder itself; a snooze has its own action, so both can exist at the same code. */
    private fun fireIntent(rc: Int, habitId: String?, date: LocalDate?, dueMs: Long, snooze: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context, rc,
        extras(Intent(context, ReminderReceiver::class.java).setAction(if (snooze) ReminderReceiver.ACTION_SNOOZE_FIRE else ReminderReceiver.ACTION_FIRE), rc, habitId, date, dueMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun preIntent(rc: Int, habitId: String?, date: LocalDate?, dueMs: Long, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, rc,
        extras(Intent(context, ReminderReceiver::class.java).setAction(if (snooze) ReminderReceiver.ACTION_SNOOZE_PRE else ReminderReceiver.ACTION_PRE), rc, habitId, date, dueMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What tapping the status bar's next-alarm opens: the habit's mini-session. */
    private fun showIntent(rc: Int, habitId: String): PendingIntent = PendingIntent.getActivity(
        context, rc + SHOW_OFFSET,
        Intent(context, dev.fitnessapp.app.MainActivity::class.java).putExtra("route", "fitapp://habit/$habitId")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun parse(refId: String): Pair<String, LocalDate>? {
        val parts = refId.split(':')
        return if (parts.size == 3 && parts[0] == "habit") parts[1] to LocalDate.parse(parts[2]) else null
    }

    companion object {
        const val KIND = "habit"
        private const val TAG = "Reminders"
        private const val PROBE = "probe"
        private const val PROBE_RC = 1999
        const val KIND_SNOOZE = "habit_snooze"

        /** A snooze row's key in `scheduled_alarm` (its alarm keeps the reminder's request code, 13.2). */
        const val SNOOZE_OFFSET = 10_000

        /** Request codes of the next-alarm tap (activity PendingIntents, apart from the broadcasts). */
        private const val SHOW_OFFSET = 20_000

        /** The pre-alarm's window (the platform's minimum since Android 12), and the fallback without exact alarms. */
        private const val WINDOW_MS = 10 * 60_000L
    }
}
