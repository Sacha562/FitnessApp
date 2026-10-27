package dev.fitnessapp.data.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** M3.2: the L8 runner replays finished sessions, stores only new rows, holds back challenge sessions, supersedes on edits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EstimationRunnerTest {
    private lateinit var db: FitnessDatabase
    private lateinit var runner: EstimationRunner
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val user = "11111111-1111-4111-8111-111111111111"
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))
    private val catalog = CatalogRepository.bundled()
    private val wpu = catalog.idOf(catalog.bySlug("chinup_weighted")!!)
    private var now = Instant.parse("2026-10-20T20:00:00Z").toEpochMilli()

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing $path" }.readText(Charsets.UTF_8)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val ctx = WriteContext(DeviceIdentity(app), "0.3.0-test", Clock { Instant.ofEpochMilli(now) }, { zone }, UuidV7Generator({ now }), { user })
        val model = Catalog(catalog.doc, Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json")))
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        runner = EstimationRunner(db, OutboxWriter(db), ctx, scheduler, catalog, { model }, params, "1.0.2")
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private suspend fun session(id: String, startIso: String, slot: String? = null) {
        val start = Instant.parse(startIso)
        val date = Week.localDate(start, zone)
        db.sessions().upsert(
            WorkoutSessionEntity(
                id = id, user_id = user, client_updated_at = start.toEpochMilli(), device_id = "d", app_version = "t",
                status = "finished", template_source = if (slot == null) "empty" else "challenge_slot",
                enrollment_id = slot?.let { "e1" }, challenge_slot = slot,
                local_date = date.toString(), timezone = zone.id, iso_week = Week.isoWeek(date),
                started_at = start.toEpochMilli(), finished_at = start.toEpochMilli() + 3_600_000L,
                bodyweight_kg = Scaled.fromDouble(70.7, 2), engine_version = "1.7.0", params_hash = params.hash, catalog_version = catalog.version,
            ),
        )
    }

    private suspend fun set(id: String, sessionId: String, atIso: String, w: Double, reps: Int, rir: Double, index: Int, kind: String = "working") {
        db.setLogs().upsert(
            SetLogEntity(
                id = id, user_id = user, client_updated_at = Instant.parse(atIso).toEpochMilli(), device_id = "d", app_version = "t",
                session_id = sessionId, session_exercise_id = "$sessionId-se", exercise_id = wpu, set_index = index, set_kind = kind,
                load_mode = "bodyweight_plus_external", effective_load_kg = Scaled.fromDouble(w, 2), added_kg = Scaled.fromDouble(w - 70.7, 2),
                reps = reps, rir_reported = Scaled.fromDouble(rir, 1), rest_before_s = if (index == 1) null else Scaled.fromDouble(150.0, 1),
                completed_at = Instant.parse(atIso).toEpochMilli(),
            ),
        )
    }

    private suspend fun twoSessions() {
        session("s1", "2026-10-08T17:45:00Z")
        set("a1", "s1", "2026-10-08T17:50:00Z", 93.2, 5, 2.0, 1)
        set("a2", "s1", "2026-10-08T17:53:00Z", 93.2, 5, 1.0, 2)
        session("s2", "2026-10-15T17:45:00Z")
        set("b1", "s2", "2026-10-15T17:50:00Z", 93.2, 5, 2.0, 1)
        set("b2", "s2", "2026-10-15T18:05:00Z", 70.7, 16, 0.0, 2, kind = "test")
    }

    @Test
    fun `a run stores snapshots, states and the params snapshot once`() = blocking {
        twoSessions()
        val first = runner.run().shouldNotBeNull()
        first.sessions shouldBe 2
        first.snapshotsAdded shouldBe 4 // rcm s1, sse s1, rcm s2, sse s2
        db.recommendations().exportAll().map { it.kind to it.scope_key }.shouldContainExactlyInAnyOrder(
            "estimation.rcm" to "exercise:$wpu", "estimation.sse" to "session:s1",
            "estimation.rcm" to "exercise:$wpu", "estimation.sse" to "session:s2",
        )
        db.paramsSnapshots().exportAll().single().params_hash shouldBe params.hash
        db.modelStates().live().map { it.model } shouldContainExactlyInAnyOrder listOf("sse", "rcm")
        db.outbox().batch("params_snapshots", Long.MAX_VALUE, 10).size shouldBe 1
        db.outbox().batch("recommendations", Long.MAX_VALUE, 10).size shouldBe 4
        db.outbox().batch("exercise_model_state", Long.MAX_VALUE, 10).size shouldBe 2

        // Nothing changed: nothing is written.
        val second = runner.run().shouldNotBeNull()
        second.snapshotsAdded shouldBe 0
        second.statesWritten shouldBe 0
        db.recommendations().exportAll().size shouldBe 4
    }

    @Test
    fun `an edited past set adds the snapshots it changes, superseding the old ones`() = blocking {
        twoSessions()
        runner.run()
        val oldSse = db.recommendations().exportAll().single { it.scope_key == "session:s1" }
        now += 60_000
        set("a2", "s1", "2026-10-08T17:53:00Z", 93.2, 5, 0.0, 2)
        val r = runner.run().shouldNotBeNull()
        (r.snapshotsAdded > 0) shouldBe true
        val s1 = db.recommendations().exportAll().filter { it.scope_key == "session:s1" }
        s1.size shouldBe 2
        s1.single { it.id != oldSse.id }.supersedes_id shouldBe oldSse.id
        // The e1RM history reads the newest snapshot per session only.
        val points = EstimateRepository(db).observeE1rm(wpu).first()
        points.map { it.sessionId } shouldBe listOf("s1", "s2")
    }

    @Test
    fun `a challenge session waits for its completion event, then runs`() = blocking {
        session("c1", "2026-10-20T17:45:00Z", slot = "A")
        set("c1a", "c1", "2026-10-20T17:50:00Z", 93.2, 5, 2.0, 1)
        val held = runner.run().shouldNotBeNull()
        held.deferredSessionId shouldBe "c1"
        held.sessions shouldBe 0
        db.recommendations().exportAll() shouldBe emptyList()

        db.challengeEvents().insertIgnore(
            ChallengeEventEntity(
                id = "ev1", user_id = user, device_id = "d", app_version = "t", enrollment_id = "e1", event_type = EventTypes.SESSION_COMPLETED,
                occurred_at = now, local_date = "2026-10-20", source_session_id = "c1", definition_version = "1.7.0",
                definition_jcs_sha256 = "0".repeat(64), engine_version = "1.7.0",
            ),
        )
        val ran = runner.run().shouldNotBeNull()
        ran.deferredSessionId.shouldBeNull()
        ran.sessions shouldBe 1
    }

    @Test
    fun `a missing completion stops holding the session back after the wait`() = blocking {
        session("c1", "2026-10-20T10:00:00Z", slot = "A")
        set("c1a", "c1", "2026-10-20T10:05:00Z", 93.2, 5, 2.0, 1)
        now = Instant.parse("2026-10-20T18:00:00Z").toEpochMilli() // finished 11:00Z, 7 h ago
        runner.run().shouldNotBeNull().sessions shouldBe 1
    }
}
