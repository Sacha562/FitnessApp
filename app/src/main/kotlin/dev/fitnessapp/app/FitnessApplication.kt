package dev.fitnessapp.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.fitnessapp.app.notify.Channels
import dev.fitnessapp.data.db.local.CrashLogEntity
import dev.fitnessapp.data.sync.SyncWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

class FitnessApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    @Volatile
    private var foreground = false

    @Volatile
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        container = AppContainer(this)
        startup()
    }

    /** `app_state` of `rest_alarm_fired` (ANDROID.md 12.7). */
    fun appState(): String = when {
        foreground -> "foreground_ui"
        System.currentTimeMillis() - startedAt < 2_000 -> "cold_start"
        else -> "fgs_background"
    }

    /** ANDROID.md 7.7, each step guarded. */
    private fun startup() {
        installCrashHandler()
        runCatching { Channels.create(this) }
        container.appScope.launch {
            runCatching { container.restTimer.reconcile() }
            runCatching { reportCrashes() }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                container.syncScheduler.request(trigger = "app_start", immediate = true)
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        })
        runCatching { container.syncScheduler.ensurePeriodic() }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val stack = e.stackTraceToString()
                val sha = MessageDigest.getInstance("SHA-256").digest(stack.toByteArray()).joinToString("") { "%02x".format(it) }
                runBlocking {
                    container.db.crashes().insert(
                        CrashLogEntity(
                            at_ms = System.currentTimeMillis(), exception_class = e::class.java.name.take(120), stack_sha256 = sha,
                            top_frame = e.stackTrace.firstOrNull()?.toString()?.take(200) ?: "", app_version = BuildConfig.VERSION_NAME,
                        ),
                    )
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    private suspend fun reportCrashes() {
        for (c in container.db.crashes().unreported()) {
            container.fgsEvent(
                "app_crash",
                mapOf(
                    "exception_class" to container.device.sanitize(c.exception_class), "stack_sha256" to c.stack_sha256,
                    "top_frame" to container.device.sanitize(c.top_frame), "crashed_at" to c.at_ms, "crash_app_version" to c.app_version,
                ),
            )
            container.db.crashes().markReported(c.id)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, container.syncEngine) else null
        }).build()
}
