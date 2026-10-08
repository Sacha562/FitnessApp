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
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as FitnessApplication).container
        val notification = c.notifications.session()
        val type = FgsType.forSdk(Build.VERSION.SDK_INT, c.device.activityRecognitionGranted())
        try {
            if (type == null) startForeground(Channels.ID_SESSION, notification)
            else ServiceCompat.startForeground(this, Channels.ID_SESSION, notification, type)
            c.appScope.launch {
                c.fgsEvent("fgs_started", mapOf("fgs_type" to FgsType.code(Build.VERSION.SDK_INT, c.device.activityRecognitionGranted()), "sdk_int" to Build.VERSION.SDK_INT))
                if (c.sessionRepository.observeActiveOnce() == null) stopSelf() else c.restTimer.reconcile()
            }
        } catch (e: Exception) {
            c.appScope.launch { c.fgsEvent("fgs_restart_failed", mapOf("error_class" to (e::class.simpleName ?: "unknown"))) }
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
