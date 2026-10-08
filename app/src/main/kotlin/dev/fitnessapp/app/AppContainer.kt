package dev.fitnessapp.app

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.app.notify.NotificationPresenter
import dev.fitnessapp.app.platform.DeviceState
import dev.fitnessapp.app.platform.Haptics
import dev.fitnessapp.app.timer.AlarmGateway
import dev.fitnessapp.app.timer.RestTimerController
import dev.fitnessapp.app.uidet.ReasonVocabulary
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.remote.AccessCheck
import dev.fitnessapp.data.remote.SupabaseFactory
import dev.fitnessapp.data.repo.BodyweightRepository
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.SyncRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.PostgrestSyncRemote
import dev.fitnessapp.data.sync.SyncEngine
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.sync.syncTables
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/** Manual dependency injection (ANDROID.md 7.2). One instance per process, owned by the Application. */
class AppContainer(private val context: Context) {
    val appContext: Context get() = context
    val platformConfig = PlatformConfig.DEFAULT
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock { Instant.now() }

    val supabase: SupabaseClient by lazy {
        SupabaseFactory.create(context, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }
    val accessCheck: AccessCheck by lazy { AccessCheck(supabase) }

    val device: DeviceState = DeviceState(context)
    val deviceIdentity: DeviceIdentity = DeviceIdentity(context)
    val ids = UuidV7Generator({ clock.now().toEpochMilli() })
    private val zone: () -> ZoneId = { ZoneId.of("Europe/Amsterdam") }

    val db: FitnessDatabase by lazy { FitnessDatabase.open(context, clock.now().toEpochMilli()) }
    val outbox: OutboxWriter by lazy { OutboxWriter(db) }
    val writeContext: WriteContext by lazy {
        WriteContext(deviceIdentity, BuildConfig.VERSION_NAME.take(40), clock, zone, ids) { supabase.auth.currentUserOrNull()?.id }
    }
    val telemetry: TelemetryWriter by lazy { TelemetryWriter(db, outbox, writeContext, EventRegistry.bundled()) }

    /** The bundled params 1.0.0 (A8.4); server params and user overrides arrive with later milestones. */
    val params: ParamView by lazy {
        ParamView.fromParamsFile(resource("/fitness/params/default-params-1.0.0.json"))
    }
    val reasons: ReasonVocabulary by lazy { ReasonVocabulary.parse(resource("/fitness/reference/reason_codes.json")) }

    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler({ WorkManager.getInstance(context) }, platformConfig.sync, enabled = BuildConfig.SYNC_ENABLED)
    }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            db, PostgrestSyncRemote(supabase), syncTables(db), outbox, telemetry, { deviceIdentity.deviceId() },
            platformConfig.sync, { clock.now().toEpochMilli() }, platformConfig.hash,
        )
    }

    val catalog: CatalogRepository by lazy { CatalogRepository.bundled() }
    val profiles: ProfileRepository by lazy { ProfileRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    val equipment: EquipmentRepository by lazy { EquipmentRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    val bodyweight: BodyweightRepository by lazy { BodyweightRepository(db, outbox, writeContext, syncScheduler) }
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(db, outbox, telemetry, writeContext, syncScheduler, catalog, equipment, bodyweight, profiles)
    }
    val sync: SyncRepository by lazy { SyncRepository(db, outbox, syncEngine, syncScheduler, writeContext) }

    val notifications: NotificationPresenter by lazy { NotificationPresenter(context) }
    val haptics: Haptics by lazy { Haptics(context) }
    val restTimer: RestTimerController by lazy {
        RestTimerController(
            db, AlarmGateway(context), notifications, haptics, device, telemetry, profiles, ids,
            { clock.now().toEpochMilli() }, platformConfig, syncScheduler,
        )
    }

    /** Platform events without a domain write (`fgs_*`, permission and reliability events). */
    suspend fun fgsEvent(type: String, payload: Map<String, Any?>) {
        if (supabase.auth.currentUserOrNull() == null) return
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = type, entityType = "none",
                    payload = payload.mapNotNull { (k, v) ->
                        when (v) {
                            null -> null
                            is Boolean -> k to JsonPrimitive(v)
                            is Number -> k to JsonPrimitive(v)
                            else -> k to JsonPrimitive(v.toString())
                        }
                    }.toMap(),
                ),
            )
        }
        syncScheduler.request()
    }

    suspend fun notificationAction(action: String) {
        val code = when (action) {
            dev.fitnessapp.app.timer.RestActionReceiver.MINUS_15 -> "minus_15"
            dev.fitnessapp.app.timer.RestActionReceiver.PLUS_15 -> "plus_15"
            dev.fitnessapp.app.timer.RestActionReceiver.SKIP -> "skip"
            else -> return
        }
        fgsEvent("notification_action", mapOf("notification_id" to "N-SESSION", "channel" to "session_ongoing", "action" to code))
    }

    private fun resource(path: String): String =
        requireNotNull(AppContainer::class.java.getResource(path)) { "missing $path" }.readText(Charsets.UTF_8)
}
