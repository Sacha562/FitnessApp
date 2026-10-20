package dev.fitnessapp.app.timer

import dev.fitnessapp.app.notify.Channels

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
    private val selfTests: SelfTestStore,
    /** Logs the warm-up item of an interval that ended (D33); runs under the timer mutex, so it must not call back. */
    private val onIntervalDone: suspend (RestEffect.IntervalDone) -> Unit,
    /** Stops the test-mode session service and its notification when no real session is active. */
    private val onSelfTestEnded: suspend () -> Unit,
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

    /**
     * Starts the countdown of a timed warm-up item (ANDROID.md 12.3 `startInterval`, UX 11.7.6, D33) at the Start tap:
     * any active rest or interval ends first. Returns the timer id, or null when the duration is below the minimum.
     */
    suspend fun startInterval(
        sessionId: String, sessionExerciseId: String, exerciseId: String, setIndex: Int, side: String, label: String,
        durationS: Int, hardMaxS: Int, durationSource: String,
    ): String? = mutex.withLock {
        val now = nowWall()
        db.restTimers().active()?.let { old -> apply(RestTimerReducer.endForInterval(old, now)) }
        val mode = profiles.get()?.rest_alarm_mode ?: "alarm_clock"
        val tr = RestTimerReducer.start(
            RestTimerReducer.Start(
                ids.next(), sessionId, "", exerciseId, side, now, device.elapsedRealtime(), device.bootCount(),
                durationS, hardMaxS, RestTimerReducer.INTERVAL, durationSource, mode,
                sessionExerciseId = sessionExerciseId, setIndex = setIndex, label = label,
            ),
            timerConfig,
        )
        apply(tr)
        tr.timer?.id
    }

    /** The item's Done tap while its interval runs: logged with the measured seconds (ANDROID.md 12.3). */
    suspend fun intervalDone(via: String) = mutex.withLock {
        db.restTimers().active()?.let { apply(RestTimerReducer.intervalDone(it, nowWall(), via)) }
    }

    /**
     * Starts the 20 s self-test rest (UX 8.6 step 7) through the production timer path; returns its planned
     * end, or null while another rest is running. [hardMaxS] is the real rest cap (`rest.timer_hard_max_s`), so
     * the notification's +15 s behaves as in a session.
     */
    suspend fun startSelfTest(hardMaxS: Int): Long? = mutex.withLock {
        if (db.restTimers().active() != null) return@withLock null
        notifications.cancelRestDone()
        val now = nowWall()
        val mode = profiles.get()?.rest_alarm_mode ?: "alarm_clock"
        val tr = RestTimerReducer.start(
            RestTimerReducer.Start(
                ids.next(), TimerSelfTest.SESSION_ID, "", "", "none", now, device.elapsedRealtime(), device.bootCount(),
                TimerSelfTest.DURATION_S, hardMaxS, "rest", "self_test", mode,
            ),
            timerConfig,
        )
        apply(tr)
        val t = tr.timer?.let { db.restTimers().get(it.id) } ?: return@withLock null
        selfTests.started(t.end_wall_ms, t.alarm_mode)
        t.end_wall_ms
    }

    suspend fun selfTestRunning(): Boolean = db.restTimers().active()?.let(::isSelfTest) ?: false

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
        // A set logged while an interval runs ends it first (its item is logged with the measured seconds).
        db.restTimers().active()?.takeIf { it.purpose == RestTimerReducer.INTERVAL && it.session_id == sessionId }?.let {
            apply(RestTimerReducer.intervalDone(it, nowWall(), "next_set"))
        }
        val t = db.restTimers().latest(sessionId) ?: return@withLock null
        if (t.purpose == RestTimerReducer.INTERVAL) return@withLock null // the first set after a warm-up interval has no rest
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
        val own = db.restTimers().get(timerId)
        if (own != null && isSelfTest(own)) {
            if (own.state == RestTimerReducer.RUNNING) finishSelfTest(own, firedWall, alert = true)
            return@withLock
        }
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
        val t = db.restTimers().active() ?: return@withLock
        val now = nowWall()
        val remaining = RestTimerReducer.remainingMs(t, now, device.elapsedRealtime(), device.bootCount())
        if (isSelfTest(t) && remaining <= 0) {
            // The test is over: record the late delivery; alert only within the catch-up window, like a real rest.
            if (t.state == RestTimerReducer.RUNNING) finishSelfTest(t, now, alert = -remaining <= timerConfig.catchUpAlertMaxS * 1000L)
            else endSelfTest(t)
            return@withLock
        }
        apply(RestTimerReducer.reconcile(t, now, device.elapsedRealtime(), device.bootCount(), timerConfig))
    }

    /**
     * Reposts the session notification for [sessionId] in its current state (countdown, overtime or count-up).
     * Under the mutex, so it cannot overwrite a countdown that a concurrent [startAfter] just posted.
     */
    suspend fun refreshSessionNotification(sessionId: String) = mutex.withLock {
        val t = db.restTimers().latest(sessionId)
        val n = when (t?.state) {
            RestTimerReducer.RUNNING -> resting(t)
            RestTimerReducer.OVERTIME -> notifications.overtime(t.end_wall_ms)
            else -> notifications.session()
        }
        notifications.postSession(n)
    }

    /**
     * The chip tick (SessionService, screen on only): reposts the current state so `shortCriticalText` follows the
     * clock. Posts nothing once the session is finished or the self-test is over.
     */
    suspend fun tickNotification() = mutex.withLock {
        val t = db.restTimers().active()
        when {
            t?.state == RestTimerReducer.RUNNING -> notifications.postSession(resting(t))
            t?.state == RestTimerReducer.OVERTIME -> notifications.postSession(notifications.overtime(t.end_wall_ms, textFor(t)))
            db.sessions().active() != null -> notifications.postSession(notifications.session())
        }
    }

    private fun isSelfTest(t: RestTimerEntity) = t.session_id == TimerSelfTest.SESSION_ID

    private fun intervalLabel(t: RestTimerEntity) = if (t.purpose == RestTimerReducer.INTERVAL) t.label ?: "Interval" else null

    private fun resting(t: RestTimerEntity) = notifications.resting(t.end_wall_ms, textFor(t), interval = intervalLabel(t))

    private suspend fun finishSelfTest(t: RestTimerEntity, firedWall: Long, alert: Boolean) {
        db.restTimers().upsert(
            t.copy(state = RestTimerReducer.ENDED, ended_by = "elapsed", fired_wall_ms = firedWall, latency_ms = firedWall - t.end_wall_ms),
        )
        alarms.cancel(t.id)
        selfTests.fired(firedWall, !device.screenInteractive())
        if (alert) {
            haptics.alarm(config.timerVibrationWaveformMs)
            notifications.postRestDone(TimerSelfTest.TEXT)
        }
        onSelfTestEnded()
    }

    private suspend fun endSelfTest(t: RestTimerEntity) {
        if (t.state != RestTimerReducer.ENDED) db.restTimers().upsert(t.copy(state = RestTimerReducer.ENDED, ended_by = t.ended_by ?: "elapsed"))
        alarms.cancel(t.id)
        onSelfTestEnded()
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
                RestEffect.NotifyResting -> timer?.let { notifications.postSession(resting(it)) }
                RestEffect.NotifyOvertime -> timer?.let { notifications.postSession(notifications.overtime(it.end_wall_ms, textFor(it))) }
                // A skipped self-test has no session to return to.
                RestEffect.NotifySession -> if (timer?.let(::isSelfTest) == true) onSelfTestEnded() else notifications.postSession(notifications.session())
                RestEffect.Alert -> {
                    haptics.alarm(config.timerVibrationWaveformMs)
                    if (notifications.postRestDone(timer?.let(::textFor) ?: notifications.text, timer?.let(::intervalLabel))) {
                        timer?.let { event(it, "notification_posted", mapOf("notification_id" to JsonPrimitive("N-REST-DONE"), "channel" to JsonPrimitive(Channels.REST_DONE))) }
                    }
                }
                RestEffect.ReminderBuzz -> haptics.buzz()
                is RestEffect.IntervalDone -> onIntervalDone(e)
                is RestEffect.Event -> timer?.let { event(it, e.type, e.payload) } ?: eventNoTimer(e)
            }
        }
    }

    private fun textFor(t: RestTimerEntity) = if (isSelfTest(t)) TimerSelfTest.TEXT else notifications.text

    /** Self-test timers emit no rest events: their only record is `timer_selftest`. */
    private suspend fun event(t: RestTimerEntity, type: String, payload: Map<String, kotlinx.serialization.json.JsonElement>) {
        if (isSelfTest(t)) return
        db.withTransaction {
            // An interval follows no set: its events point at the warm-up item.
            val (entityType, entityId) = if (t.purpose == RestTimerReducer.INTERVAL) "session_exercise" to t.session_exercise_id else "set" to t.set_log_id
            telemetry.write(EventDraft(type = type, sessionId = t.session_id, entityType = entityType, entityId = entityId, payload = payload))
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
