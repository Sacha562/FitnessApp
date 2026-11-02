package dev.fitnessapp.app

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.data.account.AccountStore
import dev.fitnessapp.app.notify.NotificationPresenter
import dev.fitnessapp.app.platform.DeviceState
import dev.fitnessapp.app.platform.Haptics
import dev.fitnessapp.app.timer.AlarmGateway
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.timer.RestTimerController
import dev.fitnessapp.app.timer.SelfTestStore
import dev.fitnessapp.app.uidet.ReasonVocabulary
import dev.fitnessapp.data.challenge.ChallengeFactsProvider
import dev.fitnessapp.data.challenge.ChallengeLibrary
import dev.fitnessapp.data.challenge.ChallengeRepository
import dev.fitnessapp.data.challenge.ChallengeSessionRepository
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.remote.AccessCheck
import dev.fitnessapp.data.remote.SupabaseFactory
import dev.fitnessapp.data.repo.BodyweightRepository
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.HistoryRepository
import dev.fitnessapp.data.repo.PreferenceRepository
import dev.fitnessapp.data.export.ExportRepository
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
    val account: AccountStore = AccountStore(context)
    val ids = UuidV7Generator({ clock.now().toEpochMilli() })
    private val zone: () -> ZoneId = { ZoneId.of("Europe/Amsterdam") }

    val db: FitnessDatabase by lazy { FitnessDatabase.open(context, clock.now().toEpochMilli()) }
    val outbox: OutboxWriter by lazy { OutboxWriter(db) }
    val writeContext: WriteContext by lazy {
        WriteContext(deviceIdentity, BuildConfig.VERSION_NAME.take(40), clock, zone, ids) { supabase.auth.currentUserOrNull()?.id ?: account.userId() }
    }
    val telemetry: TelemetryWriter by lazy { TelemetryWriter(db, outbox, writeContext, EventRegistry.bundled()) }

    /** The bundled params 1.0.2 (A8.4); server params and user overrides arrive with later milestones. */
    val params: ParamView by lazy {
        ParamView.fromParamsFile(resource("/fitness/params/default-params-$PARAMS_VERSION.json"))
    }
    val reasons: ReasonVocabulary by lazy { ReasonVocabulary.parse(resource("/fitness/reference/reason_codes.json")) }

    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler({ WorkManager.getInstance(context) }, platformConfig.sync, enabled = BuildConfig.SYNC_ENABLED)
    }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            db, PostgrestSyncRemote(supabase), syncTables(db), outbox, telemetry, { deviceIdentity.deviceId() },
            platformConfig.sync, { clock.now().toEpochMilli() }, platformConfig.hash,
        ) { pulled ->
            // Events or a newer fold from another device: refold the open enrollments (CHALLENGE_ENGINE 4.11).
            if (pulled.keys.any { it.startsWith("challenge_") }) challenges.reconcile()
        }
    }

    val catalog: CatalogRepository by lazy { CatalogRepository.bundled() }

    val taxonomy: Taxonomy by lazy { Taxonomy.parse(resource("/fitness/reference/muscle_groups.json"), resource("/fitness/reference/muscle_regions.json")) }
    @Volatile private var catalogModelCache: Pair<Int, Catalog>? = null

    /**
     * CAT-1 read model over the bundled catalog, the custom exercises and the taxonomy; rebuilt when the custom rows
     * change. SUB-1's vector weight is the params value.
     */
    val catalogModel: Catalog
        get() {
            val v = catalog.customVersion
            catalogModelCache?.takeIf { it.first == v }?.let { return it.second }
            return Catalog(catalog.merged, taxonomy, regionWeight = params.fork().double("substitute.region_weight")).also { catalogModelCache = v to it }
        }
    /** Custom exercises (M3.6): storage, sync and the merge into [catalog]. */
    val customExercises: dev.fitnessapp.data.repo.CustomExerciseRepository by lazy {
        dev.fitnessapp.data.repo.CustomExerciseRepository(db, outbox, telemetry, writeContext, syncScheduler, catalog)
    }
    val profiles: ProfileRepository by lazy { ProfileRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    /** The per-group muscle goals and the bundled goal presets (D6, M4.2). */
    val goals: dev.fitnessapp.data.repo.GoalRepository by lazy { dev.fitnessapp.data.repo.GoalRepository(db, outbox, telemetry, writeContext, syncScheduler, profiles) }
    val goalPresets: dev.fitnessapp.algo.targets.GoalPresets by lazy { dev.fitnessapp.algo.targets.GoalPresets.parse(resource("/fitness/presets/goal-presets-1.0.0.json")) }
    /** The F15 feedback prompts' answers and events (M4.2). */
    val prompts: dev.fitnessapp.data.repo.PromptRepository by lazy { dev.fitnessapp.data.repo.PromptRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    val equipment: EquipmentRepository by lazy { EquipmentRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    val bodyweight: BodyweightRepository by lazy { BodyweightRepository(db, outbox, writeContext, syncScheduler) }
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(db, outbox, telemetry, writeContext, syncScheduler, catalog, equipment, bodyweight, profiles)
    }
    val sync: SyncRepository by lazy { SyncRepository(db, outbox, syncEngine, syncScheduler, writeContext) }
    val preferences: PreferenceRepository by lazy { PreferenceRepository(db, outbox, telemetry, writeContext, syncScheduler) }
    val repPreferences: dev.fitnessapp.data.repo.RepPreferenceRepository by lazy {
        dev.fitnessapp.data.repo.RepPreferenceRepository(db, outbox, telemetry, writeContext, syncScheduler)
    }
    /** Challenge definitions (bundled and pulled), validated against the bundled catalog and reference data. */
    val challengeLibrary: ChallengeLibrary by lazy { ChallengeLibrary(params, CHANGEOVER_MIN) { resource("/fitness/$it") } }
    val challengeFacts: ChallengeFactsProvider by lazy { ChallengeFactsProvider(db, equipment, { catalog.doc }, params, clock::now) }
    val challenges: ChallengeRepository by lazy {
        ChallengeRepository(db, outbox, telemetry, writeContext, syncScheduler, params, challengeLibrary, challengeFacts)
    }
    /** Home-habit reminders (ANDROID.md 17). */
    val reminders: dev.fitnessapp.app.reminders.ReminderScheduler by lazy { dev.fitnessapp.app.reminders.ReminderScheduler(this, context) }
    val challengeSessions: ChallengeSessionRepository by lazy {
        ChallengeSessionRepository(
            db, outbox, telemetry, writeContext, syncScheduler, catalog, equipment, bodyweight, profiles, challenges, challengeFacts, params, CHANGEOVER_MIN,
            { catalogModel },
        )
    }
    /** The `session.plan` snapshots of built general blocks (D62). */
    val sessionPlans: dev.fitnessapp.data.engine.SessionPlanRecorder by lazy {
        dev.fitnessapp.data.engine.SessionPlanRecorder(db, outbox, writeContext, syncScheduler, catalog, params, PARAMS_VERSION)
    }
    /** The planner's `weekplan.plan` and `today.options` snapshots (M4.5, D70). */
    val planSnapshots: dev.fitnessapp.data.engine.PlanSnapshotRecorder by lazy {
        dev.fitnessapp.data.engine.PlanSnapshotRecorder(db, outbox, writeContext, syncScheduler, catalog, params, PARAMS_VERSION)
    }
    /** The test-day wizard's storage (UX 14.9). */
    val testDays: dev.fitnessapp.data.challenge.TestDayRepository by lazy {
        dev.fitnessapp.data.challenge.TestDayRepository(
            db, outbox, writeContext, syncScheduler, catalog, challenges, challengeSessions, sessionRepository, challengeFacts, params, CHANGEOVER_MIN,
        )
    }
    /** The L8 estimation layer (ALGORITHMS.md A12, M3.2): estimator caches and snapshots, replayed from the facts. */
    val estimation: dev.fitnessapp.data.engine.EstimationRunner by lazy {
        dev.fitnessapp.data.engine.EstimationRunner(db, outbox, writeContext, syncScheduler, catalog, { catalogModel }, params, PARAMS_VERSION)
    }
    /** L6 prescriptions for the exercises the user picks (D49, M3.4): targets, probe follow-up, warm-up sets. */
    val prescriptions: dev.fitnessapp.data.engine.PrescriptionRunner by lazy {
        dev.fitnessapp.data.engine.PrescriptionRunner(db, outbox, writeContext, syncScheduler, catalog, { catalogModel }, equipment, params, PARAMS_VERSION, repPreferences)
    }
    /** WE-1 for a live session (M3.5): evidence and safety warnings, stored as `warnings.evaluation`. */
    val warnings: dev.fitnessapp.data.engine.WarningRunner by lazy {
        dev.fitnessapp.data.engine.WarningRunner(db, outbox, writeContext, syncScheduler, catalog, prescriptions, params, PARAMS_VERSION)
    }
    /** PREF-1's rows and events and the available stations, for ELIG-1 and SUB-1 (M3.6). */
    val selection: dev.fitnessapp.data.engine.SelectionFacts by lazy { dev.fitnessapp.data.engine.SelectionFacts(db, { catalog }, params) }
    /** The engine-logged estimates for display (the exercise detail's e1RM history). */
    val estimates: dev.fitnessapp.data.engine.EstimateRepository by lazy { dev.fitnessapp.data.engine.EstimateRepository(db) }
    val history: HistoryRepository by lazy { HistoryRepository(db, outbox, telemetry, writeContext, syncScheduler, sessionRepository) }
    val exports: ExportRepository by lazy {
        ExportRepository(db, outbox, telemetry, writeContext, syncScheduler, syncTables(db), { catalog.version }, { listOf(PARAMS_VERSION) })
    }
    val settings: LocalSettings by lazy { LocalSettings(context) }
    /** The calendar (ANDROID 16): reading, classification and the write-back to the test calendar (D39). */
    val calendar: dev.fitnessapp.app.calendar.CalendarSync by lazy { dev.fitnessapp.app.calendar.CalendarSync(this) }
    val idle: dev.fitnessapp.app.session.IdleWatcher by lazy { dev.fitnessapp.app.session.IdleWatcher(this) }

    val notifications: NotificationPresenter by lazy { NotificationPresenter(context) }
    val haptics: Haptics by lazy { Haptics(context) }
    val selfTests: SelfTestStore by lazy { SelfTestStore(context) }
    val restTimer: RestTimerController by lazy {
        RestTimerController(
            db, AlarmGateway(context), notifications, haptics, device, telemetry, profiles, ids,
            { clock.now().toEpochMilli() }, platformConfig, syncScheduler, selfTests,
            { done -> dev.fitnessapp.app.timer.IntervalLog.log(db, sessionRepository, done) },
        ) {
            if (sessionRepository.observeActiveOnce() == null) {
                SessionService.stop(context)
                notifications.cancelSession()
            }
        }
    }

    /** Platform events without a domain write (`fgs_*`, permission and reliability events). */
    suspend fun fgsEvent(type: String, payload: Map<String, Any?>) {
        if (writeContext.userId() == null) return
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

    /** A platform event whose payload holds JSON values (lists, objects), e.g. `platform_config_logged`. */
    suspend fun fgsEventJson(type: String, payload: Map<String, kotlinx.serialization.json.JsonElement>) {
        if (writeContext.userId() == null) return
        db.withTransaction { telemetry.write(EventDraft(type = type, entityType = "none", payload = payload)) }
        syncScheduler.request()
    }

    /** `screen_viewed` (UX 20.9) with the UX screen id in the `screen` envelope column. */
    suspend fun screenViewed(screen: String, payload: Map<String, Any?>) {
        if (writeContext.userId() == null) return
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = "screen_viewed", entityType = "none", screen = screen.take(40),
                    payload = payload.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to JsonPrimitive(it) } ?: (v as? Number)?.let { k to JsonPrimitive(it) } }.toMap(),
                ),
            )
        }
        syncScheduler.request()
    }

    /** A platform event that belongs to a session (idle prompt, restore). */
    suspend fun sessionEvent(sessionId: String, type: String, payload: Map<String, Any?>, screen: String? = null) {
        if (writeContext.userId() == null) return
        db.withTransaction {
            telemetry.write(
                EventDraft(
                    type = type, sessionId = sessionId, entityType = "session", entityId = sessionId, screen = screen,
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

    companion object {
        /** The bundled params version (ALGORITHMS A8.1); the export lists it in `params_versions`. */
        const val PARAMS_VERSION = "1.0.3"

        /** C49: the profile default changeover (minutes) that add-on capacity and validation V13 use. */
        const val CHANGEOVER_MIN = 5
    }
}
