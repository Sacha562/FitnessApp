package dev.fitnessapp.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Notification channels and ids of ANDROID.md 13.1-13.2 (final, C54). Channels are never renamed or deleted. */
object Channels {
    const val SESSION_ONGOING = "session_ongoing"
    // `rest_done` (the rest-end heads-up) and notification id 1002 are retired by D45 and stay reserved:
    // new installs no longer create the channel, existing installs keep it (channels are never deleted).
    const val HABIT_REMINDERS = "habit_reminders"
    const val CHALLENGE = "challenge"
    const val SAFETY = "safety"
    const val APP_STATUS = "app_status"

    const val ID_SESSION = 1001
    const val RC_MINUS_15 = 1101
    const val RC_SKIP = 1102
    const val RC_PLUS_15 = 1103
    const val RC_REST_END = 1201
    const val RC_OVERTIME = 1202
    const val ID_SYNC = 3001
    const val ID_EXPORT = 3002
    const val ID_IDLE = 3003

    /** Request codes of PendingIntents that are not notification ids (added to ANDROID.md 13.2 in M1b). */
    const val RC_SESSION_DISMISSED = 1003
    const val RC_EXPORT_ACTION = 3102
    const val RC_IDLE_ALARM = 3103
    const val RC_IDLE_FINISH = 3113
    const val RC_IDLE_KEEP = 3123

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(SESSION_ONGOING, "Active session", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(HABIT_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHALLENGE, "Challenge updates", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SAFETY, "Safety alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(APP_STATUS, "App status", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
        )
        nm.createNotificationChannels(channels)
    }
}
