package dev.fitnessapp.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.FitnessApplication
import kotlinx.coroutines.launch

/**
 * Notification broadcasts that are not rest-timer actions (exported = false): the idle alarm and the N-IDLE
 * answers (ANDROID.md 11.5), and `notification_dismissed` of the dismissible session notification (13.3).
 */
class NotificationEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as FitnessApplication).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_IDLE_ALARM -> c.idle.onAlarm()
                    ACTION_IDLE_FINISH -> c.idle.onAnswer(finish = true)
                    ACTION_IDLE_KEEP -> c.idle.onAnswer(finish = false)
                    ACTION_DISMISSED -> c.fgsEvent(
                        "notification_dismissed",
                        mapOf("notification_id" to (intent.getStringExtra(EXTRA_ID) ?: "N-SESSION"), "channel" to (intent.getStringExtra(EXTRA_CHANNEL) ?: Channels.SESSION_ONGOING)),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_IDLE_ALARM = "dev.fitnessapp.IDLE_ALARM"
        const val ACTION_IDLE_FINISH = "dev.fitnessapp.IDLE_FINISH"
        const val ACTION_IDLE_KEEP = "dev.fitnessapp.IDLE_KEEP"
        const val ACTION_DISMISSED = "dev.fitnessapp.NOTIFICATION_DISMISSED"
        const val EXTRA_ID = "notification_id"
        const val EXTRA_CHANNEL = "channel"
    }
}
