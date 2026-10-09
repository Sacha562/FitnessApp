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

    private fun openIntent(route: String): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).putExtra("route", route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        .setRequestPromotedOngoing(true) // Android 16 Live Update (ignored below API 36)

    /** Between sets: count-up from the session start. */
    fun session(t: SessionNotificationText = text): Notification {
        return base().setContentTitle(t.sessionTitle).setContentText(t.currentLine)
            .setUsesChronometer(true).setWhen(if (t.sessionStartWall > 0) t.sessionStartWall else System.currentTimeMillis())
            .setShowWhen(true).build()
    }

    /** Resting: countdown to the rest end with -15 s, Skip, +15 s. */
    fun resting(endWall: Long, t: SessionNotificationText = text): Notification {
        return base().setContentTitle("Rest · next: " + t.nextTitle).setContentText(t.nextLoad)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(endWall).setShowWhen(true)
            .addAction(action(Channels.RC_MINUS_15, RestActionReceiver.MINUS_15, "-15 s"))
            .addAction(action(Channels.RC_SKIP, RestActionReceiver.SKIP, "Skip"))
            .addAction(action(Channels.RC_PLUS_15, RestActionReceiver.PLUS_15, "+15 s"))
            .build()
    }

    /** Overtime: count-up from the rest end, Skip only. */
    fun overtime(endWall: Long, t: SessionNotificationText = text): Notification {
        return base().setContentTitle("Rest over · " + t.nextTitle).setContentText(t.nextLoad)
            .setUsesChronometer(true).setWhen(endWall).setShowWhen(true)
            .addAction(action(Channels.RC_SKIP, RestActionReceiver.SKIP, "Skip"))
            .build()
    }

    fun postSession(n: Notification) = notify(Channels.ID_SESSION, n)

    /** Heads-up at rest end (`N-REST-DONE`), cancelled when the next set is logged. */
    fun postRestDone(t: SessionNotificationText = text) {
        val n = NotificationCompat.Builder(context, Channels.REST_DONE)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Rest over")
            .setContentText(listOf(t.nextTitle, t.nextLoad).filter { it.isNotBlank() }.joinToString(": "))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent("fitapp://session/current/rest"))
            .build()
        notify(Channels.ID_REST_DONE, n)
    }

    fun cancelRestDone() = NotificationManagerCompat.from(context).cancel(Channels.ID_REST_DONE)

    fun cancelSession() = NotificationManagerCompat.from(context).cancel(Channels.ID_SESSION)

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
