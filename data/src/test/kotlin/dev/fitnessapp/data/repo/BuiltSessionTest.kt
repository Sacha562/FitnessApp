package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** D62: a session built for a type, the add-on block (insert, rebuild, skip) and the in-session trim, with their events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltSessionTest {
    private lateinit var db: FitnessDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var prompts: PromptRepository
    private val catalog = CatalogRepository.bundled()
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.7.json")).readText(Charsets.UTF_8),
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
        val equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        val bodyweight = BodyweightRepository(db, outbox, ctx, scheduler)
        val profiles = ProfileRepository(db, outbox, telemetry, ctx, scheduler)
        sessions = SessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles)
        prompts = PromptRepository(db, outbox, telemetry, ctx, scheduler)
    }

    @After
    fun tearDown() = db.close()

    private fun item(slug: String, sets: Int) = BuiltItem(requireNotNull(catalog.bySlug(slug)) { slug }, sets)

    @Test
    fun `a built session stores its type's exercises in order with the chip's budget`() = runBlocking<Unit> {
        val (id, blockId) = sessions.startBuilt(params, listOf(item("leg_press_45", 4), item("leg_extension", 2)), listOf("LEGS"), 60, "chip")
        val s = db.sessions().get(id)!!
        s.template_source shouldBe "family_combo"
        s.time_budget_min shouldBe 60
        s.time_budget_source shouldBe "chip"
        s.families shouldBe listOf("legs") // the server CHECK's lower-case codes (D80)
        db.blocks().bySession(id).single().let { it.id shouldBe blockId; it.origin shouldBe "standalone"; it.budget_min shouldBe 60 }
        val rows = db.sessionExercises().bySession(id).sortedBy { it.position }
        rows.map { catalog.byId(it.exercise_id)!!.slug to it.planned_sets } shouldBe listOf("leg_press_45" to 4, "leg_extension" to 2)
        rows.all { it.origin == "plan" && it.block_id == blockId } shouldBe true
        db.events().bySession(id).single { it.event_type == "session_started" }.payload.contains("\"legs\"") shouldBe true
    }

    @Test
    fun `the sheet's elbow answer, written before the session, is tied to it (D80)`() = runBlocking<Unit> {
        prompts.elbowOnly(0, emptyList())
        now += 2_000
        val (id, _) = sessions.startBuilt(params, listOf(item("leg_press_45", 3)), listOf("LEGS"), 60, "chip")
        prompts.recordPre(id, listOf(PromptShown("readiness"), PromptShown("elbow_check")), "normal", emptyMap(), true, emptyList(), 2_000)
        val logs = db.wellness().bySession(id)
        logs.map { it.kind }.sorted() shouldBe listOf("elbow_check", "readiness")
        logs.single { it.kind == "elbow_check" }.elbow_score shouldBe 0
        db.events().bySession(id).none { it.event_type == "prompt_skipped" } shouldBe true
    }

    @Test
    fun `an add-on block follows the session, rebuilds until it starts, and skips with its event`() = runBlocking<Unit> {
        val (id, _) = sessions.startBuilt(params, listOf(item("leg_press_45", 3)), listOf("LEGS"), 75, "default")
        val addOn = sessions.addAddOnBlock(id, listOf(item("leg_extension", 2), item("seated_leg_curl", 2)), 24)
        val blocks = db.blocks().bySession(id).sortedBy { it.position }
        blocks.map { it.origin to it.status } shouldBe listOf("standalone" to "active", "addon" to "planned")
        val first = db.sessionExercises().bySession(id).sortedBy { it.position }
        first.last().block_id shouldBe addOn
        first.first { it.block_id == addOn }.position shouldBe first.filter { it.block_id != addOn }.maxOf { it.position } + SessionRepository.POSITION_STEP

        sessions.rebuildBlock(addOn, id, listOf(item("hip_abduction_machine", 3)), 20, "other", "active") shouldBe true
        val after = db.sessionExercises().bySession(id).filter { it.block_id == addOn }
        after.filter { it.status == "removed" }.map { it.status_reason_code }.distinct() shouldBe listOf("other")
        after.filter { it.status != "removed" }.map { catalog.byId(it.exercise_id)!!.slug } shouldBe listOf("hip_abduction_machine")
        db.blocks().bySession(id).first { it.id == addOn }.let { it.status shouldBe "active"; it.budget_min shouldBe 20 }

        sessions.skipBlock(addOn, id, "no_time")
        db.sessionExercises().bySession(id).filter { it.block_id == addOn && it.status != "removed" }.all { it.status == "skipped" && it.status_reason_code == "no_time" } shouldBe true
        db.blocks().bySession(id).first { it.id == addOn }.status shouldBe "skipped"
        db.events().bySession(id).count { it.event_type == "block_addon_skipped" } shouldBe 1
    }

    @Test
    fun `the in-session trim lowers sets, leaves out what does not fit and logs session_trimmed`() = runBlocking<Unit> {
        val (id, _) = sessions.startBuilt(params, listOf(item("leg_press_45", 4), item("leg_extension", 3)), listOf("LEGS"), 75, "default")
        val rows = db.sessionExercises().bySession(id).sortedBy { it.position }
        sessions.setTimeBudget(id, 45, "chip")
        sessions.trimPlanned(id, mapOf(rows[0].id to 3, rows[1].id to null), 45, false)
        db.sessions().get(id)!!.time_budget_min shouldBe 45
        db.sessionExercises().get(rows[0].id)!!.planned_sets shouldBe 3
        db.sessionExercises().get(rows[1].id)!!.let { it.status shouldBe "skipped"; it.status_reason_code shouldBe "no_time" }
        db.events().bySession(id).count { it.event_type == "session_trimmed" } shouldBe 1
    }
}
