package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.export.ExportRepository
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.sync.syncTables
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/** M1b History edits (UX 15.3, D28), preferences (UX 13.4) and the full export (DATA_MODEL 15.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var history: HistoryRepository
    private lateinit var prefs: PreferenceRepository
    private lateinit var export: ExportRepository
    private lateinit var equipment: EquipmentRepository
    private val catalog = CatalogRepository.bundled()
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.1.json")).readText(Charsets.UTF_8),
    )
    private val uid = "11111111-1111-4111-8111-111111111111"
    private var now = 1_791_482_280_000L

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val ctx = WriteContext(
            DeviceIdentity(app), "0.1.0-test", Clock { Instant.ofEpochMilli(now) }, { ZoneId.of("Europe/Amsterdam") },
            UuidV7Generator({ now }), { uid },
        )
        val outbox = OutboxWriter(db)
        val telemetry = TelemetryWriter(db, outbox, ctx, EventRegistry.bundled())
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        val bodyweight = BodyweightRepository(db, outbox, ctx, scheduler)
        val profiles = ProfileRepository(db, outbox, telemetry, ctx, scheduler)
        sessions = SessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles)
        history = HistoryRepository(db, outbox, telemetry, ctx, scheduler, sessions)
        prefs = PreferenceRepository(db, outbox, telemetry, ctx, scheduler)
        export = ExportRepository(db, outbox, telemetry, ctx, scheduler, syncTables(db), { catalog.version }, { listOf("1.0.1") })
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private val bwEx get() = catalog.exercises.first { it.loadMode == "bodyweight" && it.measure == "reps" && it.laterality == "bilateral" }

    /** A finished session with one bodyweight exercise and two working sets. */
    private suspend fun finishedSession(): Triple<String, String, List<String>> {
        val sid = sessions.start(params)
        val ex = bwEx
        val seId = sessions.addExercise(sid, ex)
        val (_, points) = equipment.loadables(ex, LoadMode.BODYWEIGHT, null, params)
        val ids = (1..2).map { i ->
            now += 120_000
            sessions.logSet(
                LogSetCommand(sid, seId, catalog.idOf(ex), i, "both", "working", points.single(), 10, null, 2.0, false, null, false, emptyList(), null, null, null, null, now, emptyMap()),
            )
        }
        now += 60_000
        sessions.finish(sid, null)
        return Triple(sid, seId, ids)
    }

    private suspend fun events(type: String) = db.events().latest(type, 50)

    @Test
    fun `editing a set writes one event per field and keeps the server checks`() = blocking {
        val (sid, _, ids) = finishedSession()
        now += 3_600_000
        val changed = history.editSet(ids[0], SetEdit(null, 8, null, 1.0, false, null, false, listOf("failed_rep"), "technique_breakdown"), "history")
        changed shouldBe 4
        val row = db.setLogs().get(ids[0])!!
        row.reps shouldBe 8
        row.rir_reported shouldBe 10L
        row.rir_source shouldBe "edited"
        row.flags shouldContainExactly listOf("failed_rep", "technique_breakdown")
        SetChecks.of(row) shouldBe emptyList()
        events("set_edited").map { it.payload }.map { Json.parseToJsonElement(it).jsonObject["field"]!!.jsonPrimitive.content }.sorted() shouldBe
            listOf("flags", "reason", "reps", "rir")
        events("set_edited").first().session_id shouldBe sid
        // Nothing changed: no event.
        history.editSet(ids[0], SetEdit(null, 8, null, 1.0, false, null, false, listOf("failed_rep"), "technique_breakdown"), "history") shouldBe 0
    }

    @Test
    fun `adding and deleting sets updates exercise status and completion`() = blocking {
        val (sid, seId, ids) = finishedSession()
        val otherEx = catalog.exercises.first { it.slug != bwEx.slug && it.loadMode == "bodyweight" && it.measure == "reps" && it.laterality == "bilateral" }
        val other = history.addExercise(sid, otherEx)
        db.sessionExercises().get(other)!!.status shouldBe "not_done"
        val point = equipment.loadables(otherEx, LoadMode.BODYWEIGHT, null, params).second.single()
        val newSet = history.addSetLater(other, "both", false, SetEdit(point, 12, null, 3.0, false, null, false, emptyList(), null))!!
        db.sessionExercises().get(other)!!.status shouldBe "done"
        SetChecks.of(db.setLogs().get(newSet)!!) shouldBe emptyList()
        events("set_added_later").size shouldBe 1

        val ev = history.deleteSet(ids[0], "history")!!
        db.setLogs().get(ids[0])!!.deleted_at shouldNotBe null
        val ev2 = history.deleteSet(ids[1], "history")!!
        db.sessionExercises().get(seId)!!.status shouldBe "not_done"
        history.restoreSet(ids[1], ev2, 2_000)
        db.sessionExercises().get(seId)!!.status shouldBe "done"
        events("action_undone").single().payload shouldContain "set_deleted"
        ev shouldNotBe ev2
        // Planned 3 + 3, done 1 + 1 after the delete and the restore.
        db.sessions().get(sid)!!.completion_ratio shouldBe 3333L
    }

    @Test
    fun `deleting a session hides it from History until undone`() = blocking {
        val (sid, _, _) = finishedSession()
        history.observeHistory().first().map { it.session.id } shouldBe listOf(sid)
        history.observeHistory().first().single().workingSets shouldBe 2
        val ev = history.deleteSession(sid)!!
        history.observeHistory().first() shouldBe emptyList()
        // Prefill history ignores deleted sessions too.
        sessions.history(catalog.idOf(bwEx), "none") shouldBe emptyList()
        history.restoreSession(sid, ev, 5_000)
        history.observeHistory().first().size shouldBe 1
        events("session_deleted").single().payload shouldContain "\"working_sets\":2"
    }

    @Test
    fun `reopen only an abandoned session of today without another open one`() = blocking {
        val (finished, _, _) = finishedSession()
        history.reopen(finished) shouldBe ReopenResult.NOT_ABANDONED
        val sid = sessions.start(params)
        now += 60_000
        sessions.abandon(sid)
        val other = sessions.start(params)
        history.reopen(sid) shouldBe ReopenResult.ANOTHER_ACTIVE
        sessions.abandon(other)
        history.reopen(sid) shouldBe ReopenResult.REOPENED
        db.sessions().get(sid)!!.status shouldBe "in_progress"
        events("session_reopened").size shouldBe 1
    }

    @Test
    fun `quick check edit updates the sRPE row`() = blocking {
        val (sid, _, _) = finishedSession()
        sessions.saveFeedback(sid, 6, null)
        history.editSrpe(sid, 8)
        db.wellness().bySession(sid).filter { it.kind == "srpe" }.map { it.srpe } shouldBe listOf(8)
    }

    @Test
    fun `preference change converges on one row and is reverted by changing it back`() = blocking {
        val ex = catalog.idOf(bwEx)
        prefs.set(ex, "dislike", "detail") shouldNotBe null
        prefs.set(ex, "dislike", "detail") shouldBe null
        prefs.observe().first() shouldBe mapOf(ex to "dislike")
        db.exercisePrefs().exportAll().single().id shouldBe PreferenceRepository.idOf(uid, ex)
        prefs.set(ex, "neutral", "detail") shouldNotBe null // back by tapping the previous state (D30)
        prefs.observe().first() shouldBe mapOf(ex to "neutral")
        db.outbox().pendingCount("user_exercise_prefs", PreferenceRepository.idOf(uid, ex)) shouldBe 1
        events("preference_changed").map { it.payload }.let { p ->
            p.size shouldBe 2
            p.count { "\"to\":\"dislike\"" in it } shouldBe 1
            p.count { "\"to\":\"neutral\"" in it } shouldBe 1
        }
        events("action_undone") shouldBe emptyList()
    }

    @Test
    fun `full export is canonical, deterministic and complete`() = blocking {
        val (sid, _, _) = finishedSession()
        sessions.saveFeedback(sid, 7, "felt strong")
        prefs.set(catalog.idOf(bwEx), "like", "list")
        val a = export.build("manual_full")
        val b = export.build("manual_full")
        a.bytes.toList() shouldBe b.bytes.toList()
        a.sha256 shouldBe ExportRepository.sha256Hex(a.bytes)
        val text = GZIPInputStream(ByteArrayInputStream(a.bytes)).readBytes().toString(Charsets.UTF_8)
        val doc = Json.parseToJsonElement(text).jsonObject
        doc["schema_version"]!!.jsonPrimitive.content shouldBe "fitness-export/1"
        doc["room_schema_version"]!!.jsonPrimitive.int shouldBe FitnessDatabase.VERSION
        val tables = doc["tables"] as JsonObject
        tables.keys.toList() shouldBe tables.keys.sorted()
        tables["workout_sessions"]!!.jsonArray.size shouldBe 1
        tables["set_logs"]!!.jsonArray.size shouldBe 2
        tables["user_exercise_prefs"]!!.jsonArray.size shouldBe 1
        (doc["local_only"] as JsonObject).keys shouldBe setOf("crash_log", "dead_letter", "device", "rest_timer", "scheduled_alarm")
        text shouldContain "felt strong"
        text shouldNotContain "\"outbox\""
        a.rowCounts["set_logs"] shouldBe 2

        val id = export.record(a, "device_storage")
        val row = export.lastFull()!!
        row.id shouldBe id
        row.sha256 shouldBe a.sha256
        row.bytes shouldBe a.bytes.size.toLong()
        Json.parseToJsonElement(row.table_row_counts).jsonObject["set_logs"]!!.jsonPrimitive.int shouldBe 2
        events("export_completed").single().payload shouldContain "\"kind\":\"manual_full\""
        db.outbox().pendingCount("exports", id) shouldBe 1
    }
}
