package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.selection.catalog.CustomDraft
import dev.fitnessapp.algo.selection.catalog.Prototypes
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.sync.syncTables
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** Custom exercises (M3.6): stored, merged into the catalog under their own id, and pushed in the server's shape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomExerciseRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var repo: CustomExerciseRepository
    private val catalog = CatalogRepository.bundled()
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
        repo = CustomExerciseRepository(db, outbox, telemetry, ctx, scheduler, catalog)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a saved copy joins the catalog and pushes in the server shape`(): Unit = runBlocking {
        val proto = Prototypes.table(catalog.doc).getValue("squat_machine" to "machine")
        val slug = repo.slugFor("Pendulum squat")
        slug shouldBe "custom_pendulum_squat"
        repo.nameTaken("Leg Press (45°)") shouldBe catalog.exercises.any { it.nameEn == "Leg press (45°)" }
        val e = Prototypes.copy(proto, CustomDraft(slug, "Pendulum squat", "Pendelsquat", "squat_machine", "machine", listOf("hack_squat"), proto.loadMode, false, false, emptyList()), catalog.version)
        val id = repo.create(e, proto.slug, coefficientsEdited = false)

        catalog.byId(id)?.slug shouldBe slug
        catalog.idOf(catalog.bySlug(slug)!!) shouldBe id
        catalog.isCustom(catalog.bySlug(slug)!!) shouldBe true
        catalog.isCustom(proto) shouldBe false
        catalog.customVersion shouldNotBe 0
        catalog.bySlug(slug)!!.stimulus shouldBe proto.stimulus
        repo.slugFor("Pendulum squat") shouldBe "custom_pendulum_squat_2"
        repo.nameTaken("pendulum  SQUAT") shouldBe true

        val row = db.customExercises().byIds(listOf(id)).single()
        row.prototype_exercise_id shouldBe catalog.idOf(proto)
        val push = syncTables(db).first { it.name == "exercises" }.let { t -> @Suppress("UNCHECKED_CAST") (t as dev.fitnessapp.data.sync.SyncTable<dev.fitnessapp.data.db.entity.CustomExerciseEntity>).toPush(row) }
        push.getValue("requires_station").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("hack_squat")
        push.getValue("bw_fraction").jsonPrimitive.content.toDouble() shouldBe proto.bwFraction
        db.exerciseStimulus().live().size shouldBe proto.stimulus.size
        db.outbox().pendingCount("exercises", id) shouldBe 1
        db.outbox().batch("exercise_stimulus", Long.MAX_VALUE, 100).size shouldBe proto.stimulus.size
        db.events().exportAll().single { it.event_type == "exercise_custom_created" }.entity_id shouldBe id
    }
}
