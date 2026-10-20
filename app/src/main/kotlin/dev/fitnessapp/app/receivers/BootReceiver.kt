package dev.fitnessapp.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.FitnessApplication
import kotlinx.coroutines.launch

/** Rest-timer recovery and the reminder horizon after boot, app update, time and zone changes and exact-alarm permission changes (12.6, 17). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val c = (context.applicationContext as FitnessApplication).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                c.restTimer.reconcile()
                c.reminders.recompute() // ANDROID.md 17: boot, update, time and zone changes recompute the horizon
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
