package dev.fitnessapp.app.platform

import android.app.AlarmManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** Device facts the timer and the reliability checks need (ANDROID.md 12, 15). */
class DeviceState(private val context: Context) {
    fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    } catch (e: Exception) {
        0
    }

    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ignoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun activityRecognitionGranted(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun screenInteractive(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive
    fun deviceIdle(): Boolean = context.getSystemService(PowerManager::class.java).isDeviceIdleMode
    fun powerSave(): Boolean = context.getSystemService(PowerManager::class.java).isPowerSaveMode

    fun standbyBucket(): Int? = if (Build.VERSION.SDK_INT >= 28) {
        context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket
    } else {
        null
    }

    /** Free-form system strings sanitised to the payload code pattern (ANDROID.md 12.7). */
    fun sanitize(s: String?): String = (s ?: "").replace(Regex("[^A-Za-z0-9_.:/-]"), "_").take(64)
}

/** The rest-end waveform with alarm usage, always on (F20, ANDROID.md 12.4). */
class Haptics(private val context: Context) {
    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun alarm(waveformMs: List<Long>) = vibrate(VibrationEffect.createWaveform(waveformMs.toLongArray(), -1))

    fun buzz() = vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator()
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
