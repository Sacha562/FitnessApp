package dev.fitnessapp.app.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.fitnessapp.app.FitnessApplication
import dev.fitnessapp.app.notify.Channels
import dev.fitnessapp.app.timer.TimerSelfTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** C28 type selection (ANDROID.md 11.2). */
object FgsType {
    fun forSdk(sdk: Int, activityRecognitionGranted: Boolean): Int? = when {
        sdk < 34 -> null
        activityRecognitionGranted -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    }

    /** The `fgs_type` event code. */
    fun code(sdk: Int, activityRecognitionGranted: Boolean): String = when (forSdk(sdk, activityRecognitionGranted)) {
        null -> "none"
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH -> "health"
        else -> "special_use"
    }
}

/**
 * The live-session foreground service (ANDROID.md 11): no loop, no wake lock; it keeps the process at
 * foreground priority, owns the ongoing notification and gives the app while-in-use capability. Started only
 * from a user action; on a sticky restart it checks Room for an active session first.
 */
class SessionService : Service() {
    private var chipTick: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as FitnessApplication).container
        chipTick = c.appScope.launch {
            while (isActive) {
                delay(c.platformConfig.timerChipTickMs)
                if (c.device.screenInteractive()) runCatching { c.restTimer.tickNotification() }
            }
        }
    }

    override fun onDestroy() {
        chipTick?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // The cancel right after stopService() is refused while the service is still in the foreground; this one is not.
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            if (c.sessionRepository.observeActiveOnce() == null && !c.restTimer.selfTestRunning()) c.notifications.cancelSession()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as FitnessApplication).container
        val testEnd = intent?.getLongExtra(EXTRA_SELF_TEST_END, 0L) ?: 0L
        val notification = if (testEnd > 0) c.notifications.resting(testEnd, TimerSelfTest.TEXT) else c.notifications.session()
        val type = FgsType.forSdk(Build.VERSION.SDK_INT, c.device.activityRecognitionGranted())
        try {
            if (type == null) startForeground(Channels.ID_SESSION, notification)
            else ServiceCompat.startForeground(this, Channels.ID_SESSION, notification, type)
            c.appScope.launch {
                c.fgsEvent("fgs_started", mapOf("fgs_type" to FgsType.code(Build.VERSION.SDK_INT, c.device.activityRecognitionGranted()), "sdk_int" to Build.VERSION.SDK_INT))
                val active = c.sessionRepository.observeActiveOnce() != null || c.restTimer.selfTestRunning()
                if (active) c.restTimer.reconcile() else stopSelf()
                if (active && testEnd == 0L) {
                    c.fgsEvent("notification_posted", mapOf("notification_id" to "N-SESSION", "channel" to Channels.SESSION_ONGOING))
                    // ANDROID.md 13.4: whether the posted notification became a Live Update, once per service start.
                    delay(PROMOTION_CHECK_DELAY_MS)
                    c.notifications.promotionState()?.let { (can, flagged) ->
                        c.fgsEvent("notification_promotion_state", mapOf("can_post_promoted" to can, "flag_promoted" to flagged))
                    }
                }
            }
        } catch (e: Exception) {
            c.appScope.launch { c.fgsEvent("fgs_restart_failed", mapOf("error_class" to (e::class.simpleName ?: "unknown"))) }
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val EXTRA_SELF_TEST_END = "self_test_end"
        private const val PROMOTION_CHECK_DELAY_MS = 1_500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }

        /** Test mode for the rest-alarm self-test (UX 8.6 step 7): runs while the self-test timer does. */
        fun startSelfTest(context: Context, endWall: Long) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).putExtra(EXTRA_SELF_TEST_END, endWall))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
