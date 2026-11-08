package dev.fitnessapp.app.timer

import dev.fitnessapp.app.platform.AppClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.FitnessApplication
import kotlinx.coroutines.launch

/** Delivers the rest end and overtime alarms (exported = false, ANDROID.md 12.4). */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fired = AppClock.nowMs()
        val app = context.applicationContext as FitnessApplication
        val c = app.container
        val timerId = intent.getStringExtra(EXTRA_TIMER_ID) ?: return
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_END -> c.restTimer.onEndAlarm(timerId, fired, app.appState())
                    ACTION_OVERTIME -> c.restTimer.onOvertimeAlarm(timerId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_END = "dev.fitnessapp.REST_END"
        const val ACTION_OVERTIME = "dev.fitnessapp.REST_OVERTIME"
        const val EXTRA_TIMER_ID = "timer_id"
    }
}

/** The -15 s / Skip / +15 s notification actions (ANDROID.md 12.5); double deliveries within 300 ms are ignored. */
class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as FitnessApplication).container
        val now = AppClock.nowMs()
        synchronized(LOCK) {
            if (intent.action == lastAction && now - lastAt < c.platformConfig.uiDoubleTapDebounceMs) return
            lastAction = intent.action
            lastAt = now
        }
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    MINUS_15 -> c.restTimer.adjust(-c.platformConfig.timerAdjustStepS, "notification")
                    PLUS_15 -> c.restTimer.adjust(c.platformConfig.timerAdjustStepS, "notification")
                    SKIP -> c.restTimer.skip("notification")
                }
                c.notificationAction(intent.action ?: "")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val MINUS_15 = "dev.fitnessapp.REST_MINUS_15"
        const val PLUS_15 = "dev.fitnessapp.REST_PLUS_15"
        const val SKIP = "dev.fitnessapp.REST_SKIP"
        private val LOCK = Any()
        private var lastAction: String? = null
        private var lastAt = 0L
    }
}
