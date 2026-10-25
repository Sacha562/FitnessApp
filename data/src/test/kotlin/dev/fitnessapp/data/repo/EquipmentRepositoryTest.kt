package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.load.LoadScheme
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

/** "Stack setup" (D43): one machine's stack changes, versioned and marked `measured`; the other machines keep theirs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EquipmentRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var equipment: EquipmentRepository
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
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun scheme(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val lbStack = """{"type":"stack","unit":"lb","start":10,"step":10,"max":220,"ratio":1,"add_on_steps":[5]}"""

    @Test
    fun `stack setup changes only that machine`() = blocking {
        equipment.saveChecklist(
            GymDraft("Basic-Fit", listOf("basic-fit"), "{}"),
            listOf(
                EquipmentDraft("cable_station", "Cable station", true, scheme(lbStack)),
                EquipmentDraft("leg_press", "Leg press", true, scheme(lbStack)),
            ),
            confirmed = false,
        )
        val cable = equipment.equipment().first { it.equipment_type == "cable_station" }
        now += 60_000

        equipment.saveStack(cable.id, "kg", 2.5, 2.5, 100.0)

        val rows = equipment.equipment().associateBy { it.equipment_type }
        val edited = rows.getValue("cable_station")
        edited.calibration_source shouldBe "measured"
        edited.calibration_rev shouldBe 2
        edited.confirmed_at shouldBe now
        val s = EquipmentRepository.parseScheme(edited.load_scheme) as LoadScheme.Stack
        s shouldBe LoadScheme.Stack("kg", 2.5, 2.5, 100.0, listOf(5.0), 1.0, null) // ratio and add-on steps kept
        rows.getValue("leg_press").calibration_rev shouldBe 1
        rows.getValue("leg_press").calibration_source shouldBe "default"
        db.events().exportAll().count { it.event_type == "equipment_calibration_changed" } shouldBe 1

        // Saving the same values again confirms the machine without a new calibration revision.
        equipment.saveStack(cable.id, "kg", 2.5, 2.5, 100.0)
        equipment.equipment().first { it.id == cable.id }.calibration_rev shouldBe 2
    }
}
