package dev.fitnessapp.app.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.MainActivity
import dev.fitnessapp.app.notify.Channels

/**
 * Schedules the rest end and overtime alarms (ANDROID.md 12.4): `setAlarmClock` by default (never deferred
 * in Doze), `setExactAndAllowWhileIdle` when the user chose it, and the inexact fallback when exact alarms
 * are not allowed. Returns the mode actually used.
 */
class AlarmGateway(
    private val context: Context,
    /** App time to device wall time (D108: a guarded clock jump); identity otherwise. */
    private val toDeviceMs: (Long) -> Long = { it },
) {
    private val am = context.getSystemService(AlarmManager::class.java)

    private fun pi(requestCode: Int, action: String, timerId: String): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, RestAlarmReceiver::class.java).setAction(action).putExtra(RestAlarmReceiver.EXTRA_TIMER_ID, timerId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val showIntent: PendingIntent
        get() = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra("route", "fitapp://session/current/rest"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun scheduleEnd(timerId: String, atWall: Long, preferred: String, exactAllowed: Boolean): String =
        schedule(Channels.RC_REST_END, RestAlarmReceiver.ACTION_END, timerId, atWall, preferred, exactAllowed)

    fun scheduleOvertime(timerId: String, atWall: Long, preferred: String, exactAllowed: Boolean): String =
        schedule(Channels.RC_OVERTIME, RestAlarmReceiver.ACTION_OVERTIME, timerId, atWall, preferred, exactAllowed)

    fun cancel(timerId: String) {
        am.cancel(pi(Channels.RC_REST_END, RestAlarmReceiver.ACTION_END, timerId))
        am.cancel(pi(Channels.RC_OVERTIME, RestAlarmReceiver.ACTION_OVERTIME, timerId))
    }

    private fun schedule(rc: Int, action: String, timerId: String, atApp: Long, preferred: String, exactAllowed: Boolean): String {
        val atWall = toDeviceMs(atApp)
        val p = pi(rc, action, timerId)
        if (!exactAllowed) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, p)
            return "inexact"
        }
        return try {
            if (preferred == "exact_idle") {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, p)
                "exact_idle"
            } else {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atWall, showIntent), p)
                "alarm_clock"
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, p)
            "inexact"
        }
    }
}
