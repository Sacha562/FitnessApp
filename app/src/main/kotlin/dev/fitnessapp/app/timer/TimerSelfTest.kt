package dev.fitnessapp.app.timer

import android.content.Context
import dev.fitnessapp.app.notify.SessionNotificationText

/**
 * The 20-second rest-alarm self-test (UX 8.6 step 7, ANDROID.md 15.4): a real rest timer through
 * [RestTimerController] and the session service in test mode (countdown notification, alarm, vibration,
 * rest-done heads-up), without a session row. The timer row carries [SESSION_ID]; it emits no rest events.
 * Results live in local prefs and the `timer_selftest` event.
 */
object TimerSelfTest {
    const val SESSION_ID = "selftest"
    const val DURATION_S = 20

    val TEXT = SessionNotificationText(sessionTitle = "Rest alarm test", nextTitle = "Test alarm", nextLoad = "The rest alarm works")

    data class Result(val scheduledEnd: Long, val firedAt: Long?, val screenOffAtFire: Boolean?, val alarmMode: String)
}

/** Local record of the last self-test run. */
class SelfTestStore(context: Context) {
    private val prefs = context.getSharedPreferences("timer_selftest", Context.MODE_PRIVATE)

    fun started(end: Long, alarmMode: String) {
        prefs.edit().putLong("end", end).remove("fired").remove("screen_off").putString("mode", alarmMode).commit()
    }

    fun fired(firedWall: Long, screenOff: Boolean) {
        prefs.edit().putLong("fired", firedWall).putBoolean("screen_off", screenOff).commit()
    }

    fun result(): TimerSelfTest.Result? {
        val end = prefs.getLong("end", 0L).takeIf { it > 0 } ?: return null
        return TimerSelfTest.Result(
            end, prefs.getLong("fired", 0L).takeIf { it > 0 }, if (prefs.contains("screen_off")) prefs.getBoolean("screen_off", false) else null,
            prefs.getString("mode", "alarm_clock") ?: "alarm_clock",
        )
    }
}
