package dev.fitnessapp.app.timer

import android.content.Context
import dev.fitnessapp.app.notify.NotificationPresenter
import dev.fitnessapp.app.notify.SessionNotificationText
import dev.fitnessapp.app.platform.DeviceState
import dev.fitnessapp.app.platform.Haptics

/**
 * The 20-second rest-alarm self-test (UX 8.6 step 7, ANDROID.md 15.4): the production alarm path (AlarmGateway,
 * RestAlarmReceiver, alarm vibration, rest-done heads-up) without a session row. Results live in local prefs and
 * the `timer_selftest` event.
 */
object TimerSelfTest {
    const val TIMER_ID = "selftest"
    const val DURATION_S = 20

    data class Result(val scheduledEnd: Long, val firedAt: Long?, val screenOffAtFire: Boolean?, val alarmMode: String)

    private fun prefs(c: Context) = c.getSharedPreferences("timer_selftest", Context.MODE_PRIVATE)

    fun start(context: Context, alarms: AlarmGateway, device: DeviceState, nowWall: Long, preferredMode: String): Result {
        val end = nowWall + DURATION_S * 1000L
        val mode = alarms.scheduleEnd(TIMER_ID, end, preferredMode, device.canScheduleExactAlarms())
        prefs(context).edit().putLong("end", end).remove("fired").remove("screen_off").putString("mode", mode).commit()
        return Result(end, null, null, mode)
    }

    fun result(context: Context): Result? {
        val p = prefs(context)
        val end = p.getLong("end", 0L).takeIf { it > 0 } ?: return null
        return Result(
            end, p.getLong("fired", 0L).takeIf { it > 0 }, if (p.contains("screen_off")) p.getBoolean("screen_off", false) else null,
            p.getString("mode", "alarm_clock") ?: "alarm_clock",
        )
    }

    /** Called by [RestAlarmReceiver] for [TIMER_ID]. */
    fun onFired(context: Context, firedWall: Long, haptics: Haptics, device: DeviceState, notifications: NotificationPresenter, waveform: List<Long>) {
        prefs(context).edit().putLong("fired", firedWall).putBoolean("screen_off", !device.screenInteractive()).commit()
        haptics.alarm(waveform)
        val previous = notifications.text
        notifications.text = SessionNotificationText(nextTitle = "Test alarm", nextLoad = "The rest alarm works")
        notifications.postRestDone()
        notifications.text = previous
    }
}
