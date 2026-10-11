package dev.fitnessapp.app.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.notify.Channels
import dev.fitnessapp.app.notify.NotificationEventReceiver
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.db.local.ScheduledAlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Idle protection (ANDROID.md 11.5, D29): when no set, rest or exercise event happened for `session.idlePromptMin`
 * (60 min), an inexact alarm posts N-IDLE "Session still running. Finish it?" with Finish / Keep open; after
 * `session.idleStopFgsMin` (240 min) the foreground service stops itself and the session stays in progress. The
 * last activity is the newest `session_events` row of the session other than the idle prompt itself; "Keep open"
 * counts as activity. Alarms are recomputed from that state whenever it changes, so no counter survives in memory.
 */
class IdleWatcher(private val c: AppContainer) {
    private val prefs = c.appContext.getSharedPreferences("idle_watch", Context.MODE_PRIVATE)
    private val am get() = c.appContext.getSystemService(AlarmManager::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) = scope.launch {
        c.sessionRepository.observeActive().flatMapLatest { s ->
            if (s == null) flowOf(null to null) else c.db.events().observeLastActivity(s.id, IGNORED).map { last -> s to last }
        }.collect { (s, last) ->
            if (s == null) cancel() else schedule(s, lastActivity(s, last))
        }
    }

    private fun lastActivity(s: WorkoutSessionEntity, lastEvent: Long?): Long {
        val keep = if (prefs.getString(KEY_SESSION, null) == s.id) prefs.getLong(KEY_KEEP_AT, 0L) else 0L
        return maxOf(lastEvent ?: 0L, s.started_at ?: 0L, keep)
    }

    private suspend fun schedule(s: WorkoutSessionEntity, last: Long) {
        val now = System.currentTimeMillis()
        val prompt = last + c.platformConfig.sessionIdlePromptMin * 60_000L
        val stop = last + c.platformConfig.sessionIdleStopFgsMin * 60_000L
        val at = when {
            now < prompt -> prompt
            now < stop -> if (prefs.getLong(KEY_PROMPTED_FOR, 0L) == last) stop else now + 1_000
            prefs.getLong(KEY_STOPPED_FOR, 0L) == last -> {
                // Already stopped for this idle period: nothing more to do until the next activity.
                am.cancel(alarmIntent())
                c.db.alarms().delete(Channels.RC_IDLE_ALARM)
                return
            }
            else -> now + 1_000
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        c.db.alarms().upsert(ScheduledAlarmEntity(Channels.RC_IDLE_ALARM, "idle", s.id, at, "inexact", now))
    }

    private suspend fun cancel() {
        am.cancel(alarmIntent())
        c.db.alarms().delete(Channels.RC_IDLE_ALARM)
        c.notifications.cancelIdle()
    }

    /** The alarm fired (possibly in a fresh process): prompt once per idle period, or stop the service. */
    suspend fun onAlarm() {
        val s = c.sessionRepository.observeActiveOnce() ?: return cancel()
        val last = lastActivity(s, c.db.events().lastActivity(s.id, IGNORED))
        val idleMin = ((System.currentTimeMillis() - last) / 60_000L).toInt()
        when {
            idleMin >= c.platformConfig.sessionIdleStopFgsMin -> if (prefs.getLong(KEY_STOPPED_FOR, 0L) != last) {
                prefs.edit().putLong(KEY_STOPPED_FOR, last).apply()
                SessionService.stop(c.appContext)
                c.notifications.cancelSession()
                c.fgsEvent("fgs_stopped", mapOf("reason" to "idle_timeout"))
            }
            idleMin >= c.platformConfig.sessionIdlePromptMin -> if (prefs.getLong(KEY_PROMPTED_FOR, 0L) != last) {
                prefs.edit().putLong(KEY_PROMPTED_FOR, last).putString(KEY_SESSION, s.id).apply()
                if (c.notifications.postIdle()) c.fgsEvent("notification_posted", mapOf("notification_id" to "N-IDLE", "channel" to Channels.APP_STATUS))
                c.sessionEvent(s.id, "session_idle_prompted", mapOf("idle_min" to idleMin, "action" to "posted"))
            }
        }
        schedule(s, last)
    }

    /** Finish / Keep open from N-IDLE. */
    suspend fun onAnswer(finish: Boolean) {
        c.notifications.cancelIdle()
        val s = c.sessionRepository.observeActiveOnce() ?: return
        val last = lastActivity(s, c.db.events().lastActivity(s.id, IGNORED))
        val idleMin = ((System.currentTimeMillis() - last) / 60_000L).toInt()
        c.fgsEvent("notification_action", mapOf("notification_id" to "N-IDLE", "channel" to Channels.APP_STATUS, "action" to if (finish) "finish" else "keep_open"))
        c.sessionEvent(s.id, "session_idle_prompted", mapOf("idle_min" to idleMin, "action" to if (finish) "finish" else "keep_open"))
        if (finish) {
            c.restTimer.sessionFinished(s.id)
            c.sessionRepository.finish(s.id, null)
            c.notifications.cancelRestDone()
            SessionService.stop(c.appContext)
            c.notifications.cancelSession()
            c.fgsEvent("fgs_stopped", mapOf("reason" to "finished"))
        } else {
            prefs.edit().putString(KEY_SESSION, s.id).putLong(KEY_KEEP_AT, System.currentTimeMillis()).apply()
            schedule(s, lastActivity(s, c.db.events().lastActivity(s.id, IGNORED)))
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        c.appContext, Channels.RC_IDLE_ALARM,
        Intent(c.appContext, NotificationEventReceiver::class.java).setAction(NotificationEventReceiver.ACTION_IDLE_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** The prompt itself is not activity; its answer "Keep open" is (via [KEY_KEEP_AT]). */
        val IGNORED = listOf("session_idle_prompted")
        const val KEY_SESSION = "session_id"
        const val KEY_KEEP_AT = "keep_open_at"
        const val KEY_PROMPTED_FOR = "prompted_for_last"
        const val KEY_STOPPED_FOR = "stopped_for_last"
    }
}
