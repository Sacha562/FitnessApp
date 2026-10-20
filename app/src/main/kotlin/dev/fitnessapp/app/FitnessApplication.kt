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
import android.os.Build
import dev.fitnessapp.app.maintenance.MaintenanceWorker
import dev.fitnessapp.app.notify.Channels
import kotlinx.serialization.json.JsonPrimitive
import dev.fitnessapp.app.ui.equipment.EquipmentUpgrade
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

    /** ANDROID.md 7.7, each step guarded; a failed step is recorded as `app_startup_failed { step, error_class }`. */
    private fun startup() {
        installCrashHandler()
        val failed = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
        fun step(name: String, block: () -> Unit) = runCatching(block).onFailure { failed += name to it }
        step("channels") { Channels.create(this) }
        step("periodic_sync") { container.syncScheduler.ensurePeriodic() }
        step("maintenance") { MaintenanceWorker.ensureScheduled(this) }
        container.appScope.launch {
            suspend fun stepS(name: String, block: suspend () -> Unit) = runCatching { block() }.onFailure { failed += name to it }
            stepS("rest_timer_recovery") { container.restTimer.reconcile() }
            stepS("crash_report") { reportCrashes() }
            stepS("equipment_upgrade") { EquipmentUpgrade.addMissingTypes(container.equipment) }
            stepS("idle_watch") { container.idle.start(container.appScope) }
            stepS("platform_snapshots") { platformSnapshots() }
            // CHALLENGE_ENGINE 4.6: ticks are synthesized at app start; an absence prompt waits for the user (Challenges tab).
            stepS("challenge_catchup") {
                container.challenges.ensureBundled()
                if (container.writeContext.userId() != null) container.challenges.catchUp()
            }
            for ((name, e) in failed.toList()) {
                runCatching { container.fgsEvent("app_startup_failed", mapOf("step" to name, "error_class" to (e::class.simpleName ?: "unknown"))) }
            }
        }
        var coldStart = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                container.syncScheduler.request(trigger = "app_start", immediate = true)
                val cold = coldStart
                coldStart = false
                container.appScope.launch { runCatching { container.fgsEvent("app_opened", mapOf("cold_start" to cold)) } }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        })
    }

    /** ANDROID.md 7.7 step 8: permission and alarm state at each cold start, the platform config when it changed. */
    private suspend fun platformSnapshots() {
        val d = container.device
        if (Build.VERSION.SDK_INT >= 33) container.fgsEvent("permission_state", mapOf("permission" to "post_notifications", "granted" to d.notificationsAllowed()))
        if (Build.VERSION.SDK_INT >= 29) container.fgsEvent("permission_state", mapOf("permission" to "activity_recognition", "granted" to d.activityRecognitionGranted()))
        container.fgsEvent("exact_alarm_status", mapOf("allowed" to d.canScheduleExactAlarms()))
        container.fgsEvent("battery_optimization_status", mapOf("ignoring" to d.ignoringBatteryOptimizations()))
        val cfg = container.platformConfig
        if (container.writeContext.userId() != null && container.settings.lastPlatformConfigHash != cfg.hash) {
            container.fgsEventJson("platform_config_logged", mapOf("platform_config_hash" to JsonPrimitive(cfg.hash), "values" to cfg.values()))
            container.settings.lastPlatformConfigHash = cfg.hash
        }
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
                when (workerClassName) {
                    SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, container.syncEngine)
                    MaintenanceWorker::class.java.name -> MaintenanceWorker(appContext, workerParameters, container)
                    else -> null
                }
        }).build()
}
