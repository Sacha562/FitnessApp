package dev.fitnessapp.data.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.BodyweightRepository
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentDraft
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.GymDraft
import dev.fitnessapp.data.repo.LogSetCommand
import dev.fitnessapp.data.repo.PlannedValues
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SYNC_TABLE_ORDER
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** M3.4: the prescription runner stores and pins `rx.prescription`, follows up a probe, previews next time; Repeat copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrescriptionRunnerTest {
    private lateinit var db: FitnessDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var equipment: EquipmentRepository
    private lateinit var bodyweight: BodyweightRepository
    private lateinit var runner: PrescriptionRunner
    private lateinit var estimation: EstimationRunner
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val catalog = CatalogRepository.bundled()
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))
    private val wpu = catalog.bySlug("chinup_weighted")!!
    private var now = Instant.parse("2026-10-27T17:00:00Z").toEpochMilli()

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing $path" }.readText(Charsets.UTF_8)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val ctx = WriteContext(DeviceIdentity(app), "0.4.0-test", Clock { Instant.ofEpochMilli(now) }, { zone }, UuidV7Generator({ now }), { USER })
        val outbox = OutboxWriter(db)
        val telemetry = TelemetryWriter(db, outbox, ctx, EventRegistry.bundled())
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        val model = Catalog(catalog.doc, Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json")))
        equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        bodyweight = BodyweightRepository(db, outbox, ctx, scheduler)
        sessions = SessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, ProfileRepository(db, outbox, telemetry, ctx, scheduler))
        runner = PrescriptionRunner(db, outbox, ctx, scheduler, catalog, { model }, equipment, params, "1.0.2")
        estimation = EstimationRunner(db, outbox, ctx, scheduler, catalog, { model }, params, "1.0.2")
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private suspend fun gym() {
        equipment.saveChecklist(
            GymDraft("Basic-Fit", listOf("basic-fit"), "{}"),
            listOf(
                EquipmentDraft(
                    "dip_belt", "Dip belt", true,
                    Json.parseToJsonElement("""{"type":"belt","unit":"kg","plates":[{"kg":20,"count":2},{"kg":10,"count":2},{"kg":5,"count":2},{"kg":2.5,"count":2},{"kg":1.25,"count":2}],"max_kg":60,"max_items":4,"dumbbells_on_belt":false}""").jsonObject,
                ),
            ),
            confirmed = true,
        )
        bodyweight.logWeighIn(70.7)
    }

    private suspend fun log(sid: String, seId: String, index: Int, added: Double, reps: Int, rir: Double, planned: PlannedValues? = null): String {
        now += 180_000
        val p = equipment.loadables(wpu, LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 70.7, params).second.first { it.nativeValue == added }
        return sessions.logSet(
            LogSetCommand(
                sid, seId, catalog.idOf(wpu), index, "both", "working", p, reps = reps, durationS = null, rir = rir,
                rirLowerBound = false, holdReserveS = null, holdReserveLowerBound = false, flags = emptyList(), deviationReason = null,
                planned = planned, rest = null, startedAtMs = null, completedAtMs = now, telemetry = emptyMap(),
            ),
        )
    }

    @Test
    fun `recommendations push before the session exercises that point at them`() {
        SYNC_TABLE_ORDER.indexOf("recommendations") shouldBeLessThan SYNC_TABLE_ORDER.indexOf("session_exercises")
        SYNC_TABLE_ORDER.indexOf("params_snapshots") shouldBeLessThan SYNC_TABLE_ORDER.indexOf("recommendations")
    }

    @Test
    fun `a new exercise gets a probe, pinned once, and the probe plans the rest`() = blocking {
        gym()
        now += 60_000
        val sid = sessions.start(params)
        val seId = sessions.addExercise(sid, wpu, LoadMode.BODYWEIGHT_PLUS_EXTERNAL)
        val id = runner.ensure(RxSlot(seId)).shouldNotBeNull()
        runner.ensure(RxSlot(seId)) shouldBe id
        db.recommendations().exportAll().size shouldBe 1
        val se = db.sessionExercises().get(seId)!!
        se.recommendation_id shouldBe id
        val rx = runner.get(id).shouldNotBeNull()
        rx.probe shouldBe true
        rx.sets.first().wSys.shouldBeNull()
        rx.sessionExerciseId shouldBe seId
        db.paramsSnapshots().exportAll().single().params_hash shouldBe params.hash
        db.outbox().batch("recommendations", Long.MAX_VALUE, 10).size shouldBe 1

        log(sid, seId, 1, 20.0, rx.sets.first().targetReps!!, 3.0, PlannedValues(reps = rx.sets.first().targetReps, rir = rx.sets.first().rir, source = "engine"))
        val next = runner.followUp(RxSlot(seId)).shouldNotBeNull()
        next shouldNotBe id
        db.sessionExercises().get(seId)!!.recommendation_id shouldBe next
        val row = db.recommendations().byIds(listOf(next)).single()
        row.supersedes_id shouldBe id
        val follow = runner.get(next)!!
        follow.steps shouldContain "rx.probe.followup"
        follow.sets.first { it.setIndex == 2 }.wSys.shouldNotBeNull()
        follow.sets.size shouldBe (se.planned_sets ?: 3)
        // A second follow-up does nothing.
        runner.followUp(RxSlot(seId)).shouldBeNull()

        // The set row keeps the planned RIR for RIRCAL-1 and the review: the probe's q* + rx.probe_rir_add (B9.8).
        db.setLogs().bySession(sid).single().planned_rir shouldBe Math.round(rx.sets.first().rir!! * 10)
        rx.sets.first().rir shouldBe rx.qStar + params.double("rx.probe_rir_add")
    }

    @Test
    fun `next time previews from the finished session and Repeat copies its exercises`() = blocking {
        gym()
        now += 60_000
        val sid = sessions.start(params)
        val seId = sessions.addExercise(sid, wpu, LoadMode.BODYWEIGHT_PLUS_EXTERNAL)
        runner.ensure(RxSlot(seId))
        log(sid, seId, 1, 20.0, 12, 3.0)
        runner.followUp(RxSlot(seId))
        log(sid, seId, 2, 20.0, 10, 2.0)
        log(sid, seId, 3, 20.0, 9, 2.0)
        sessions.finish(sid, null)
        estimation.run()
        val (today, next) = runner.preview(seId).shouldNotBeNull()
        today.recommendationId shouldBe db.sessionExercises().get(seId)!!.recommendation_id
        next.probe shouldBe false
        next.sets.first().wSys.shouldNotBeNull()

        now += 86_400_000L
        val rid = sessions.start(params, repeatOf = sid)
        val s = db.sessions().get(rid)!!
        s.template_source shouldBe "repeat"
        val copied = db.sessionExercises().bySession(rid)
        copied.map { it.exercise_id } shouldBe listOf(catalog.idOf(wpu))
        copied.single().planned_sets shouldBe db.sessionExercises().get(seId)!!.planned_sets
        copied.single().recommendation_id.shouldBeNull()
        val started = db.events().exportAll().last { it.event_type == "session_started" }
        Json.parseToJsonElement(started.payload).jsonObject["source_session_id"]?.jsonPrimitive?.content shouldBe sid

        // The repeat's prescription starts from the model now, not a probe.
        val rx = runner.get(runner.ensure(RxSlot(copied.single().id))!!)!!
        rx.probe shouldBe false
        rx.sets.all { it.wSys != null } shouldBe true
    }

    private companion object {
        const val USER = "11111111-1111-4111-8111-111111111111"
    }
}
