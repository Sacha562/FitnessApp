package dev.fitnessapp.app

import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.data.sync.SyncConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Platform constants (ANDROID.md 25, C54): outside the engine params and `params_hash`; the full set is logged
 * as `platform_config_logged` whenever its hash changes, and the main platform events carry the hash.
 */
data class PlatformConfig(
    val timerAdjustStepS: Int = 15,
    val timerLongPressStepS: Int = 60,
    val timerMinLeftAfterAdjustS: Int = 5,
    val timerMinTimerS: Int = 10,
    val timerPrewarnS: Int = 10,
    val timerOvertimeReminderS: Int = 60,
    val timerCatchUpAlertMaxS: Int = 60,
    val timerVibrationWaveformMs: List<Long> = listOf(0, 300, 150, 300, 150, 600),
    val timerExactIdleMinSamples: Int = 30,
    val timerExactIdleP95MaxMs: Long = 2000,
    val timerExactIdleRevertWindow: Int = 20,
    val timerSwitchHintMs: Long = 1500,
    /** Live Update chip refresh while the screen is on (OxygenOS shows `shortCriticalText`, not the chronometer). */
    val timerChipTickMs: Long = 1000,
    val reliabilityLatencyAmberMs: Long = 2000,
    val reliabilityLatencyRedMs: Long = 5000,
    val sessionIdlePromptMin: Int = 60,
    val sessionIdleStopFgsMin: Int = 240,
    val sessionOverrunLineMin: Int = 10,
    val uiDoubleTapDebounceMs: Long = 300,
    val holdLeadInS: Int = 5,
    val holdBeepEveryS: Int = 5,
    val holdMaxHoldS: Int = 300,
    val holdStopGuardMs: Long = 500,
    /** F4: the monthly export reminder (ANDROID.md 25.3); the switch itself is a local setting. */
    val exportReminderDays: Int = 30,
    /** UX 18 N-SYNC: changes waiting for upload this long post the notice. */
    val syncStaleNoticeDays: Int = 7,
    /** ANDROID.md 17: habit reminders scheduled ahead, and the N-HABIT snooze. */
    val remindersHorizonH: Int = 48,
    val remindersSnoozeMin: Int = 30,
    /** How long before a reminder its pre-alarm wakes the app to arm the alarm clock (ANDROID.md 17, two stages). */
    val remindersPreLeadMin: Int = 15,
    /** D108: a wall-clock move larger than this during a session is a jump; the session clock stays anchored. */
    val clockJumpToleranceS: Int = 120,
    val sync: SyncConfig = SyncConfig(),
) {
    /** Every key of the register with its value, in key order (JCS sorts again). */
    fun values(): JsonObject = JsonObject(
        sortedMapOf(
            "clock.jumpToleranceS" to JsonPrimitive(clockJumpToleranceS),
            "timer.adjustStepS" to JsonPrimitive(timerAdjustStepS),
            "timer.longPressStepS" to JsonPrimitive(timerLongPressStepS),
            "timer.minLeftAfterAdjustS" to JsonPrimitive(timerMinLeftAfterAdjustS),
            "timer.minTimerS" to JsonPrimitive(timerMinTimerS),
            "timer.prewarnS" to JsonPrimitive(timerPrewarnS),
            "timer.overtimeReminderS" to JsonPrimitive(timerOvertimeReminderS),
            "timer.catchUpAlertMaxS" to JsonPrimitive(timerCatchUpAlertMaxS),
            "timer.vibrationWaveformMs" to JsonArray(timerVibrationWaveformMs.map(::JsonPrimitive)),
            "timer.exactIdleMinSamples" to JsonPrimitive(timerExactIdleMinSamples),
            "timer.exactIdleP95MaxMs" to JsonPrimitive(timerExactIdleP95MaxMs),
            "timer.exactIdleRevertWindow" to JsonPrimitive(timerExactIdleRevertWindow),
            "timer.switchHintMs" to JsonPrimitive(timerSwitchHintMs),
            "timer.chipTickMs" to JsonPrimitive(timerChipTickMs),
            "reliability.latencyAmberMs" to JsonPrimitive(reliabilityLatencyAmberMs),
            "reliability.latencyRedMs" to JsonPrimitive(reliabilityLatencyRedMs),
            "session.idlePromptMin" to JsonPrimitive(sessionIdlePromptMin),
            "session.idleStopFgsMin" to JsonPrimitive(sessionIdleStopFgsMin),
            "session.overrunLineMin" to JsonPrimitive(sessionOverrunLineMin),
            "ui.doubleTapDebounceMs" to JsonPrimitive(uiDoubleTapDebounceMs),
            "hold.leadInS" to JsonPrimitive(holdLeadInS),
            "hold.beepEveryS" to JsonPrimitive(holdBeepEveryS),
            "hold.maxHoldS" to JsonPrimitive(holdMaxHoldS),
            "hold.stopGuardMs" to JsonPrimitive(holdStopGuardMs),
            "export.reminderDays" to JsonPrimitive(exportReminderDays),
            "sync.staleNoticeDays" to JsonPrimitive(syncStaleNoticeDays),
            "reminders.horizonH" to JsonPrimitive(remindersHorizonH),
            "reminders.snoozeMin" to JsonPrimitive(remindersSnoozeMin),
            "reminders.preLeadMin" to JsonPrimitive(remindersPreLeadMin),
            "sync.pushBatchRows" to JsonPrimitive(sync.pushBatchRows),
            "sync.pullPageRows" to JsonPrimitive(sync.pullPageRows),
            "sync.overlapS" to JsonPrimitive(sync.overlapS),
            "sync.debounceS" to JsonPrimitive(sync.debounceS),
            "sync.backoffInitialS" to JsonPrimitive(sync.backoffInitialS),
            "sync.periodicH" to JsonPrimitive(sync.periodicH),
            "sync.fkRetryAttempts" to JsonPrimitive(sync.fkRetryAttempts),
            "sync.schemaDriftRetryMin" to JsonPrimitive(sync.schemaDriftRetryMin),
            "sync.unreachableBannerH" to JsonPrimitive(sync.unreachableBannerH),
            "sync.pendingLongH" to JsonPrimitive(sync.pendingLongH),
        ),
    )

    val hash: String by lazy { Jcs.sha256Hex(values()) }

    companion object {
        val DEFAULT = PlatformConfig()
    }
}
