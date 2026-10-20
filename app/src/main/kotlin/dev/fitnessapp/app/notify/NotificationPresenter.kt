package dev.fitnessapp.app.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.fitnessapp.app.MainActivity
import dev.fitnessapp.app.R
import dev.fitnessapp.app.timer.RestActionReceiver
import dev.fitnessapp.app.timer.RestTimerReducer

/**
 * What the session notification says (UX 11.13.3 wording). The live session screen keeps it current; it is
 * also persisted, so an alarm receiver in a cold process can still post meaningful text.
 */
data class SessionNotificationText(
    val sessionTitle: String = "Session",
    val currentLine: String = "Tap to open",
    val nextTitle: String = "Next set",
    val nextLoad: String = "",
    val sessionStartWall: Long = 0L,
)

/** Builds and posts the notifications of ANDROID.md 13.3 (structure) with the UX wording. */
class NotificationPresenter(private val context: Context) {
    private val prefs = context.getSharedPreferences("session_notification", Context.MODE_PRIVATE)

    var text: SessionNotificationText
        get() = SessionNotificationText(
            prefs.getString("title", null) ?: "Session", prefs.getString("current", null) ?: "Tap to open",
            prefs.getString("next_title", null) ?: "Next set", prefs.getString("next_load", null) ?: "",
            prefs.getLong("start", 0L),
        )
        set(v) {
            prefs.edit().putString("title", v.sessionTitle).putString("current", v.currentLine)
                .putString("next_title", v.nextTitle).putString("next_load", v.nextLoad).putLong("start", v.sessionStartWall).apply()
        }

    /** A tap that opens [route] and reports `notification_opened` for [notificationId] (UX 18; one request code per notification). */
    private fun openIntent(route: String, notificationId: String = "N-SESSION", channel: String = Channels.SESSION_ONGOING, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).putExtra("route", route)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId).putExtra(MainActivity.EXTRA_CHANNEL, channel)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** `notification_dismissed` for the dismissible FGS notification (Android 14+, ANDROID.md 13.3). */
    private fun deleteIntent(notificationId: String, channel: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, NotificationEventReceiver::class.java).setAction(NotificationEventReceiver.ACTION_DISMISSED)
            .putExtra(NotificationEventReceiver.EXTRA_ID, notificationId).putExtra(NotificationEventReceiver.EXTRA_CHANNEL, channel),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcastAction(requestCode: Int, action: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, NotificationEventReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun action(requestCode: Int, act: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, RestActionReceiver::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(context, Channels.SESSION_ONGOING)
        .setSmallIcon(R.drawable.ic_stat_session)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openIntent("fitapp://session/current"))
        .setDeleteIntent(deleteIntent("N-SESSION", Channels.SESSION_ONGOING, Channels.RC_SESSION_DISMISSED))
        .setRequestPromotedOngoing(true) // Android 16 Live Update (ignored below API 36)

    /*
     * Titles stay short and every state carries `shortCriticalText` (the remaining or elapsed time): the status-bar
     * chip of a Live Update is at most 96 dp, and OxygenOS draws it from the title or that text rather than from
     * the chronometer. While the screen is on, SessionService reposts once per `timer.chipTickMs`.
     */

    /** Between sets: count-up from the session start. */
    fun session(t: SessionNotificationText = text, nowWall: Long = System.currentTimeMillis()): Notification {
        val start = if (t.sessionStartWall > 0) t.sessionStartWall else nowWall
        return base().setContentTitle(t.sessionTitle).setContentText(t.currentLine)
            .setUsesChronometer(true).setWhen(start).setShowWhen(true)
            .setShortCriticalText(RestTimerReducer.mmss(maxOf(0L, nowWall - start) / 1000))
            .build()
    }

    /**
     * Resting: countdown to the rest end with -15 s, Skip, +15 s. An interval (the warm-up row, D33) shows the item
     * ([interval] = its name) and Done instead of Skip: "Rowing machine · 1:12 left".
     */
    fun resting(endWall: Long, t: SessionNotificationText = text, nowWall: Long = System.currentTimeMillis(), interval: String? = null): Notification {
        val left = RestTimerReducer.mmss((maxOf(0L, endWall - nowWall) + 999) / 1000)
        return base().setContentTitle(interval?.let { "$it · $left left" } ?: "Rest")
            .setContentText(if (interval != null) "Logged as done at zero" else nextLine(t))
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(endWall).setShowWhen(true)
            .setShortCriticalText(left)
            .addAction(action(Channels.RC_MINUS_15, RestActionReceiver.MINUS_15, "-15 s"))
            .addAction(action(Channels.RC_SKIP, RestActionReceiver.SKIP, if (interval != null) "Done" else "Skip"))
            .addAction(action(Channels.RC_PLUS_15, RestActionReceiver.PLUS_15, "+15 s"))
            .build()
    }

    /** Overtime: count-up from the rest end, Skip only. */
    fun overtime(endWall: Long, t: SessionNotificationText = text, nowWall: Long = System.currentTimeMillis()): Notification {
        return base().setContentTitle("Rest over").setContentText(nextLine(t))
            .setUsesChronometer(true).setWhen(endWall).setShowWhen(true)
            .setShortCriticalText("+" + RestTimerReducer.mmss(maxOf(0L, nowWall - endWall) / 1000))
            .addAction(action(Channels.RC_SKIP, RestActionReceiver.SKIP, "Skip"))
            .build()
    }

    private fun nextLine(t: SessionNotificationText): String =
        "Next: " + listOf(t.nextTitle, t.nextLoad).filter { it.isNotBlank() }.joinToString(" · ")

    fun postSession(n: Notification) {
        notify(Channels.ID_SESSION, n)
    }

    /** Heads-up at rest end (`N-REST-DONE`), cancelled when the next set is logged; an interval says "<item> done". */
    fun postRestDone(t: SessionNotificationText = text, interval: String? = null): Boolean {
        val n = NotificationCompat.Builder(context, Channels.REST_DONE)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(interval?.let { "$it done" } ?: "Rest over")
            .setContentText(listOf(t.nextTitle, t.nextLoad).filter { it.isNotBlank() }.joinToString(": "))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent("fitapp://session/current/rest", "N-REST-DONE", Channels.REST_DONE, Channels.ID_REST_DONE))
            .build()
        return notify(Channels.ID_REST_DONE, n)
    }

    /**
     * N-HABIT (ANDROID.md 17, UX 18): "Two-arm hang" · "4-5 sets, 2-3 min total" with Start (the home mini-session),
     * Snooze 30 min and Skip today; never Done. [id] is the reminder's code (13.2), shared by its actions.
     */
    fun postHabit(id: Int, habitId: String, date: String, title: String, text: String?): Boolean {
        fun broadcast(action: String) = PendingIntent.getBroadcast(
            context, id,
            Intent(context, dev.fitnessapp.app.reminders.ReminderReceiver::class.java).setAction(action)
                .putExtra(dev.fitnessapp.app.reminders.ReminderReceiver.EXTRA_RC, id)
                .putExtra(dev.fitnessapp.app.reminders.ReminderReceiver.EXTRA_HABIT, habitId)
                .putExtra(dev.fitnessapp.app.reminders.ReminderReceiver.EXTRA_DATE, date),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val start = openIntent("fitapp://habit/$habitId", "N-HABIT", Channels.HABIT_REMINDERS, id)
        val n = NotificationCompat.Builder(context, Channels.HABIT_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) } }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(start)
            .addAction(NotificationCompat.Action.Builder(0, "Start", start).build())
            .addAction(NotificationCompat.Action.Builder(0, "Snooze 30 min", broadcast(dev.fitnessapp.app.reminders.ReminderReceiver.ACTION_SNOOZE)).build())
            .addAction(NotificationCompat.Action.Builder(0, "Skip today", broadcast(dev.fitnessapp.app.reminders.ReminderReceiver.ACTION_SKIP)).build())
            .build()
        return notify(id, n)
    }

    fun cancelHabit(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** N-IDLE (ANDROID.md 11.5, UX 18): "Session still running. Finish it?" with Finish / Keep open. */
    fun postIdle(): Boolean = notify(
        Channels.ID_IDLE,
        NotificationCompat.Builder(context, Channels.APP_STATUS)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Session still running")
            .setContentText("Finish it?")
            .setAutoCancel(true)
            .setContentIntent(openIntent("fitapp://session/current", "N-IDLE", Channels.APP_STATUS, Channels.ID_IDLE))
            .addAction(broadcastAction(Channels.RC_IDLE_FINISH, NotificationEventReceiver.ACTION_IDLE_FINISH, "Finish"))
            .addAction(broadcastAction(Channels.RC_IDLE_KEEP, NotificationEventReceiver.ACTION_IDLE_KEEP, "Keep open"))
            .build(),
    )

    fun cancelIdle() = NotificationManagerCompat.from(context).cancel(Channels.ID_IDLE)

    /** N-EXPORT (F4, UX 18): the monthly backup reminder; "Export" opens Data and sync and starts the export. */
    fun postExportReminder(): Boolean = notify(
        Channels.ID_EXPORT,
        NotificationCompat.Builder(context, Channels.APP_STATUS)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Monthly backup")
            .setContentText("Export your data")
            .setAutoCancel(true)
            .setContentIntent(openIntent("fitapp://you/data", "N-EXPORT", Channels.APP_STATUS, Channels.ID_EXPORT))
            .addAction(
                NotificationCompat.Action.Builder(0, "Export", openIntent("fitapp://you/data/export", "N-EXPORT", Channels.APP_STATUS, Channels.RC_EXPORT_ACTION)).build(),
            )
            .build(),
    )

    /** N-SYNC (UX 18): rows the server rejected, or changes not uploaded for 7 days. */
    fun postSyncNotice(text: String): Boolean = notify(
        Channels.ID_SYNC,
        NotificationCompat.Builder(context, Channels.APP_STATUS)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Upload problem")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent("fitapp://you/data", "N-SYNC", Channels.APP_STATUS, Channels.ID_SYNC))
            .build(),
    )

    fun cancelRestDone() = NotificationManagerCompat.from(context).cancel(Channels.ID_REST_DONE)

    fun cancelSession() = NotificationManagerCompat.from(context).cancel(Channels.ID_SESSION)

    /** Posts unless notifications are denied; returns whether it was posted. */
    private fun notify(id: Int, n: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        NotificationManagerCompat.from(context).notify(id, n)
        return true
    }

    /** `notification_promotion_state` (ANDROID.md 13.4): Live Updates allowed, and whether the posted one is promoted. */
    fun promotionState(): Pair<Boolean, Boolean>? {
        if (Build.VERSION.SDK_INT < 36) return null
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val can = nm.canPostPromotedNotifications()
        val flagged = nm.activeNotifications.firstOrNull { it.id == Channels.ID_SESSION }
            ?.let { (it.notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0 } ?: false
        return can to flagged
    }
}
