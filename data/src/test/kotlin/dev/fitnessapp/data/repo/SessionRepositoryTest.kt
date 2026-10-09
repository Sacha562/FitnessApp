package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Logging through the repository writes rows that pass the `set_logs` CHECK constraints of migration 013
 * (mirrored below), with the LOAD-1 identity on the session's BW snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var equipment: EquipmentRepository
    private lateinit var bodyweight: BodyweightRepository
    private val catalog = CatalogRepository.bundled()
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.1.json")).readText(Charsets.UTF_8),
    )
    private var now = 1_791_482_280_000L

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val ctx = WriteContext(
            DeviceIdentity(app), "0.1.0-test", Clock { Instant.ofEpochMilli(now) }, { ZoneId.of("Europe/Amsterdam") },
            UuidV7Generator({ now }), { "11111111-1111-4111-8111-111111111111" },
        )
        val outbox = OutboxWriter(db)
        val telemetry = TelemetryWriter(db, outbox, ctx, EventRegistry.bundled())
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        bodyweight = BodyweightRepository(db, outbox, ctx, scheduler)
        val profiles = ProfileRepository(db, outbox, telemetry, ctx, scheduler)
        sessions = SessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles)
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun scheme(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun setUpGym() {
        equipment.saveChecklist(
            GymDraft("Basic-Fit", listOf("basic-fit"), "{}"),
            listOf(
                EquipmentDraft("assisted_pullup_machine", "Assisted machine", true, scheme("""{"type":"pin_assist","unit":"lb","step":5,"min":0,"max":100,"offset_kg":6.5,"ratio":1.0}""")),
                EquipmentDraft("dip_belt", "Dip belt", true, scheme("""{"type":"belt","unit":"kg","plates":[{"kg":20,"count":2},{"kg":10,"count":2},{"kg":5,"count":2},{"kg":2.5,"count":2}],"max_kg":60,"max_items":4,"dumbbells_on_belt":false}""")),
                EquipmentDraft("dumbbells", "Dumbbells", true, scheme("""{"type":"dumbbells","unit":"kg","values":[10,12.5,15],"per":"hand"}""")),
            ),
            confirmed = true,
        )
    }

    @Test
    fun `sets pass the server checks for every load type`() = blocking {
        setUpGym()
        bodyweight.logWeighIn(70.7)
        now += 60_000
        val sid = sessions.start(params)
        sessions.session(sid)!!.bodyweight_kg shouldBe 7070L

        val cases = listOf(
            Triple("pullup_weighted", LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 22.5),
            Triple("assisted_pullup_machine", LoadMode.ASSISTED_MACHINE, 40.0),
            Triple("db_bench_press", LoadMode.EXTERNAL, 12.5),
        )
        for ((slug, mode, native) in cases) {
            val ex = requireNotNull(catalog.bySlug(slug) ?: catalog.exercises.firstOrNull { it.slug.startsWith(slug.substringBefore('_')) && mode.code in it.allowedLoadModes }) { slug }
            val seId = sessions.addExercise(sid, ex, mode)
            val (_, points) = equipment.loadables(ex, mode, 70.7, params)
            val p = points.first { (it.assistPinNative ?: it.nativeValue) == native }
            now += 90_000
            sessions.logSet(
                LogSetCommand(
                    sid, seId, catalog.idOf(ex), 1, "both", "working", p, reps = 5, durationS = null, rir = 2.0,
                    rirLowerBound = false, holdReserveS = null, holdReserveLowerBound = false, flags = emptyList(),
                    deviationReason = null, planned = null, rest = null, startedAtMs = null, completedAtMs = now, telemetry = emptyMap(),
                ),
            )
        }
        val sets = sessions.sets(sid)
        sets.size shouldBe 3
        sets.forEach { serverChecks(it) shouldBe emptyList() }
        sets.first { it.load_mode == "bodyweight_plus_external" }.effective_load_kg shouldBe 9320L
        sets.first { it.load_mode == "assisted_machine" }.let {
            it.assist_kg shouldBe 2464L
            it.effective_load_kg shouldBe 4606L
            it.assist_pin_native shouldBe 4000L
            it.assist_unit shouldBe "lb"
        }
        sets.first { it.load_mode == "external" }.let {
            it.implement_count shouldBe 2
            it.added_kg shouldBe 2500L
        }
    }

    @Test
    fun `finish marks pending exercises and computes completion`() = blocking {
        setUpGym()
        val sid = sessions.start(params)
        val ex = catalog.exercises.first { it.loadMode == "bodyweight" && it.measure == "reps" && it.laterality == "bilateral" }
        val seId = sessions.addExercise(sid, ex)
        val other = sessions.addExercise(sid, catalog.exercises.first { it.slug != ex.slug && it.loadMode == "bodyweight" && it.measure == "reps" })
        val (_, points) = equipment.loadables(ex, LoadMode.BODYWEIGHT, null, params)
        sessions.logSet(
            LogSetCommand(sid, seId, catalog.idOf(ex), 1, "both", "working", points.single(), 10, null, 1.0, false, null, false, emptyList(), null, null, null, null, now, emptyMap()),
        )
        sessions.finish(sid, "no_time")
        val s = sessions.session(sid)!!
        s.status shouldBe "finished"
        s.end_reason_code shouldBe "no_time"
        s.completion_ratio shouldBe Scaled.fromDouble(1.0 / 6.0, 4)
        sessions.exercises(sid).associate { it.id to it.status } shouldBe mapOf(seId to "done", other to "not_done")
        sessions.sets(sid).forEach { serverChecks(it) shouldBe emptyList() }
    }

    /** Mirrors of the `set_logs` CHECK constraints of migration 013 that the client can violate. */
    private fun serverChecks(r: SetLogEntity): List<String> {
        val out = mutableListOf<String>()
        if ((r.input_value == null) != (r.input_unit == null)) out += "input_pair"
        if ((r.implement_count == null) != (r.load_per_implement_kg == null)) out += "implement_pair"
        if (r.implement_count != null && r.added_kg != r.implement_count * r.load_per_implement_kg!!) out += "implement_sum"
        if ((r.rest_source == null) != (r.rest_before_s == null)) out += "rest_source"
        if (!r.rest_timer_overridden && ((r.rest_adjust_s ?: 0) != 0 || r.rest_end_reason == "skipped")) out += "rest_overridden"
        val fbw = r.f_bw ?: 0
        val modeOk = when (r.load_mode) {
            "external" -> r.assist_kg == null && r.assist_ordinal == null
            "bodyweight" -> r.added_kg == 0L && r.assist_kg == null && r.assist_ordinal == null && fbw > 0
            "bodyweight_plus_external" -> r.assist_kg == null && r.assist_ordinal == null && fbw > 0
            "assisted_machine" -> r.assist_pin_native != null && r.assist_unit != null && r.assist_kg != null && r.equipment_id != null && r.assist_ordinal == null
            "assisted_ordinal" -> r.assist_ordinal != null && r.assist_kg == null
            else -> false
        }
        if (!modeOk) out += "load_mode"
        r.effective_load_kg?.let { eff ->
            val sys = (r.bodyweight_kg ?: 0) / 100.0 * (fbw / 10000.0) + r.implement_base_kg / 100.0 + r.added_kg / 100.0 - (r.assist_kg ?: 0) / 100.0
            if (abs(eff / 100.0 - sys) > 0.01 + 1e-9) out += "effective_load"
        }
        if (r.reps == null && r.duration_s == null) out += "performed"
        if (r.rir_reported != null && r.hold_reserve_s != null) out += "one_effort"
        if ((r.rir_reported == null && r.hold_reserve_s == null) != (r.rir_source == "missing")) out += "effort_source"
        r.rir_reported?.let { if (it % 5 != 0L) out += "rir_half_steps" }
        return out
    }
}
