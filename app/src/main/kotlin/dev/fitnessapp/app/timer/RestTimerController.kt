package dev.fitnessapp.app.timer

import android.os.Build
import androidx.room.withTransaction
import dev.fitnessapp.app.PlatformConfig
import dev.fitnessapp.app.notify.NotificationPresenter
import dev.fitnessapp.app.platform.DeviceState
import dev.fitnessapp.app.platform.Haptics
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.RestColumns
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * Applies [RestTimerReducer] transitions (ANDROID.md 12): Room first, then alarms, notifications, alerts and
 * events. One mutex serialises the UI, the notification actions and the alarm receivers.
 */
class RestTimerController(
    private val db: FitnessDatabase,
    private val alarms: AlarmGateway,
    private val notifications: NotificationPresenter,
    private val haptics: Haptics,
    private val device: DeviceState,
    private val telemetry: TelemetryWriter,
    private val profiles: ProfileRepository,
    private val ids: UuidV7Generator,
    private val nowWall: () -> Long,
    private val config: PlatformConfig,
    private val scheduler: SyncScheduler,
) {
    private val mutex = Mutex()
    val timerConfig = TimerConfig(
        adjustStepS = config.timerAdjustStepS, longPressStepS = config.timerLongPressStepS,
        minLeftAfterAdjustS = config.timerMinLeftAfterAdjustS, minTimerS = config.timerMinTimerS,
        overtimeReminderS = config.timerOvertimeReminderS, catchUpAlertMaxS = config.timerCatchUpAlertMaxS,
    )

    fun observe(sessionId: String): Flow<RestTimerEntity?> = db.restTimers().observeLatest(sessionId)

    suspend fun active(): RestTimerEntity? = db.restTimers().active()

    /** Starts the rest after a logged set; returns the timer id, or null when the rest is too short for a timer. */
    suspend fun startAfter(
        sessionId: String, setLogId: String, exerciseId: String, side: String, setLoggedAtWall: Long,
        durationS: Int, hardMaxS: Int, purpose: String, durationSource: String,
    ): String? = mutex.withLock {
        db.restTimers().active()?.let { old -> apply(RestTimerReducer.skip(old, nowWall(), "next_set")) }
        val mode = profiles.get()?.rest_alarm_mode ?: "alarm_clock"
        val tr = RestTimerReducer.start(
            RestTimerReducer.Start(
                ids.next(), sessionId, setLogId, exerciseId, side, setLoggedAtWall, device.elapsedRealtime(), device.bootCount(),
                durationS, hardMaxS, purpose, durationSource, mode,
            ),
            timerConfig,
        )
        apply(tr)
        tr.timer?.id
    }

    suspend fun adjust(deltaS: Int, via: String) = mutex.withLock {
        db.restTimers().active()?.let { apply(RestTimerReducer.adjust(it, deltaS, via, nowWall(), timerConfig)) }
    }

    suspend fun skip(via: String) = mutex.withLock {
        db.restTimers().active()?.let { apply(RestTimerReducer.skip(it, nowWall(), via)) }
    }

    /**
     * Ends the rest before the next set is logged and returns that set's rest columns. A timer belongs to the
     * next set only when it was started by the set logged just before it ([previousSetCompletedAt]); otherwise
     * (first set, or the timer was already used) there is no preceding rest and the columns are null.
     */
    suspend fun onNextSetLogged(
        sessionId: String, previousSetCompletedAt: Long?, completedAtWall: Long, firstTouchWall: Long?, holdStartWall: Long?, estSetDurationS: Double,
    ): RestColumns? = mutex.withLock {
        val t = db.restTimers().latest(sessionId) ?: return@withLock null
        if (previousSetCompletedAt == null || t.start_wall_ms != previousSetCompletedAt) return@withLock null
        val (tr, cols) = RestTimerReducer.onNextSetLogged(t, RestTimerReducer.NextSet(completedAtWall, firstTouchWall, holdStartWall, estSetDurationS, nowWall()))
        apply(tr)
        notifications.cancelRestDone()
        cols
    }

    /** Undo of the set that started the rest (UX 11.13.6): the timer is cancelled and not logged as a rest. */
    suspend fun cancelForUndo(sessionId: String, setLoggedAt: Long) = mutex.withLock {
        val t = db.restTimers().latest(sessionId) ?: return@withLock
        if (t.start_wall_ms != setLoggedAt || t.state == RestTimerReducer.ENDED) return@withLock
        db.restTimers().upsert(t.copy(state = RestTimerReducer.ENDED, ended_by = null))
        alarms.cancel(t.id)
        notifications.cancelRestDone()
        notifications.postSession(notifications.session())
    }

    suspend fun sessionFinished(sessionId: String) = mutex.withLock {
        db.restTimers().latest(sessionId)?.let { apply(RestTimerReducer.sessionFinished(it, nowWall())) }
        notifications.cancelRestDone()
    }

    suspend fun onEndAlarm(timerId: String, firedWall: Long, appState: String) = mutex.withLock {
        val t = db.restTimers().active()
        val tr = RestTimerReducer.onEndAlarm(t, timerId, firedWall, timerConfig)
        apply(tr)
        val fired = tr.timer
        if (fired != null && fired.id == timerId && fired.fired_wall_ms == firedWall) {
            event(fired, "rest_alarm_fired", latencyPayload(fired, appState))
        }
    }

    suspend fun onOvertimeAlarm(timerId: String) = mutex.withLock {
        apply(RestTimerReducer.onOvertimeAlarm(db.restTimers().active(), timerId, nowWall()))
    }

    /** Process start, service start, boot and update (ANDROID.md 12.6). */
    suspend fun reconcile() = mutex.withLock {
        db.restTimers().active()?.let { apply(RestTimerReducer.reconcile(it, nowWall(), device.elapsedRealtime(), device.bootCount(), timerConfig)) }
    }

    private suspend fun apply(tr: Transition) {
        var timer = tr.timer
        for (e in tr.effects) {
            when (e) {
                is RestEffect.Persist -> db.restTimers().upsert(e.timer).also { timer = e.timer }
                is RestEffect.ScheduleEnd -> {
                    val t = timer ?: continue
                    val used = alarms.scheduleEnd(e.timerId, e.atWallMs, t.alarm_mode, device.canScheduleExactAlarms())
                    if (used != t.alarm_mode) {
                        val next = t.copy(alarm_mode = used)
                        db.restTimers().upsert(next)
                        timer = next
                        if (t.alarm_mode != "inexact") event(t, "rest_alarm_failed", mapOf("timer_id" to JsonPrimitive(t.id), "error_class" to JsonPrimitive("exact_not_allowed")))
                    }
                }
                is RestEffect.ScheduleOvertime -> timer?.let { alarms.scheduleOvertime(e.timerId, e.atWallMs, it.alarm_mode, device.canScheduleExactAlarms()) }
                is RestEffect.CancelAlarms -> alarms.cancel(e.timerId)
                RestEffect.NotifyResting -> timer?.let { notifications.postSession(notifications.resting(it.end_wall_ms)) }
                RestEffect.NotifyOvertime -> timer?.let { notifications.postSession(notifications.overtime(it.end_wall_ms)) }
                RestEffect.NotifySession -> notifications.postSession(notifications.session())
                RestEffect.Alert -> {
                    haptics.alarm(config.timerVibrationWaveformMs)
                    notifications.postRestDone()
                }
                RestEffect.ReminderBuzz -> haptics.buzz()
                is RestEffect.Event -> timer?.let { event(it, e.type, e.payload) } ?: eventNoTimer(e)
            }
        }
    }

    private suspend fun event(t: RestTimerEntity, type: String, payload: Map<String, kotlinx.serialization.json.JsonElement>) {
        db.withTransaction {
            telemetry.write(EventDraft(type = type, sessionId = t.session_id, entityType = "set", entityId = t.set_log_id, payload = payload))
        }
        scheduler.request()
    }

    private suspend fun eventNoTimer(e: RestEffect.Event) {
        db.withTransaction { telemetry.write(EventDraft(type = e.type, entityType = "none", payload = e.payload)) }
    }

    private fun latencyPayload(t: RestTimerEntity, appState: String) = mapOf(
        "timer_id" to JsonPrimitive(t.id),
        "scheduled_end_wall_ms" to JsonPrimitive(t.end_wall_ms),
        "fired_wall_ms" to JsonPrimitive(t.fired_wall_ms),
        "latency_ms" to JsonPrimitive(t.latency_ms),
        "alarm_mode" to JsonPrimitive(t.alarm_mode),
        "app_state" to JsonPrimitive(appState),
        "screen_interactive" to JsonPrimitive(device.screenInteractive()),
        "device_idle" to JsonPrimitive(device.deviceIdle()),
        "power_save" to JsonPrimitive(device.powerSave()),
        "battery_opt_exempt" to JsonPrimitive(device.ignoringBatteryOptimizations()),
        "standby_bucket" to JsonPrimitive(device.standbyBucket()),
        "fgs_type" to JsonPrimitive(dev.fitnessapp.app.session.FgsType.code(Build.VERSION.SDK_INT, device.activityRecognitionGranted())),
        "sdk_int" to JsonPrimitive(Build.VERSION.SDK_INT),
        "manufacturer" to JsonPrimitive(device.sanitize(Build.MANUFACTURER)),
        "build_display" to JsonPrimitive(device.sanitize(Build.DISPLAY)),
        "platform_config_hash" to JsonPrimitive(config.hash),
    )
}
