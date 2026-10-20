package dev.fitnessapp.data.challenge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventCodec
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import java.util.UUID

/** M2b.1: challenge storage folds, persists, refolds and catches up as CHALLENGE_ENGINE 4 and DATA_MODEL 9.7 say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var repo: ChallengeRepository
    private lateinit var ctx: WriteContext
    private lateinit var make: () -> ChallengeRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.1.json"))

    /** Tuesday 2026-10-20 12:00 in Amsterdam. */
    private var now = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing $path" }.readText(Charsets.UTF_8)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        ctx = WriteContext(
            DeviceIdentity(app), "0.2.0-test", Clock { Instant.ofEpochMilli(now) }, { zone },
            UuidV7Generator({ now }), { "11111111-1111-4111-8111-111111111111" },
        )
        val outbox = OutboxWriter(db)
        val telemetry = TelemetryWriter(db, outbox, ctx, EventRegistry.bundled())
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        val equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        val catalog = CatalogRepository.bundled()
        val library = ChallengeLibrary(params, 5, ::resource)
        make = {
            ChallengeRepository(
                db, outbox, telemetry, ctx, scheduler, params, library,
                ChallengeFactsProvider(db, equipment, { catalog.doc }, params, { Instant.ofEpochMilli(now) }),
            )
        }
        repo = make()
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private val onboard: TreeMap<String, Value> = TreeMap(
        mapOf(
            "bw" to Values.mass("70.7"), "wpu3rmAdded" to Values.mass("32.5"), "bwMaxReps" to Values.int(18), "anchorLoad" to Values.mass("20"),
            "pinA" to Values.pin("40"), "pinC" to Values.pin("50"), "pinHold90" to Values.pin("35"),
        ),
    )

    private suspend fun start(): FoldedEnrollment {
        repo.ensureBundled()
        val defId = db.challengeDefinitions().all().single().id
        val def = repo.definition(defId)
        return repo.create(
            defId, sortedMapOf(), onboard,
            ImportBlock(phase = 1, blockIndex = 1, blockWeek = 2, mode = "normal", weekOrdinal = 7),
            def.doc.proposals.associate { it.id to "approved" },
        )
    }

    private fun session(slot: String, date: LocalDate) = EventPayload.Session(
        SessionLog(UUID.randomUUID(), slot, "${slot}1", "gym", date, BigDecimal.ONE, false, null, sortedMapOf()),
    )

    @Test
    fun `create folds the import and every decision and queues the rows`() = blocking {
        val f = start()
        db.challengeDefinitions().all().single().source shouldBe "bundled"
        f.state.status shouldBe EnrollmentState.ACTIVE
        f.state.block.week shouldBe 2
        f.state.proposalDecisions.size shouldBe 27
        // created + 27 decisions + today's day tick, which sorts first (00:00.002) and is folded after the creation
        val events = db.challengeEvents().forEnrollment(f.enrollment.id)
        events.size shouldBe 29
        events.first().dedupe_key shouldBe "day.tick:2026-10-20"
        events[1].event_type shouldBe EventTypes.CREATED
        val row = db.enrollments().get(f.enrollment.id)!!
        row.status shouldBe "active"
        row.state_hash shouldBe StateJson.hash(f.state)
        row.last_folded_event_id shouldBe events.last().id
        row.fold_generation shouldBe 0
        Json.parseToJsonElement(row.rule_decisions).jsonObject.size shouldBe 27
        // The stored payloads fold back to the same state.
        val def = f.def
        val decoded = events.map {
            EventCodec.decode(def, UUID.fromString(it.id), it.event_type, Instant.ofEpochMilli(it.occurred_at), LocalDate.parse(it.local_date), Json.parseToJsonElement(it.payload).jsonObject)
        }
        StateJson.hash(EnrollmentFold.fold(def, params, decoded).state) shouldBe row.state_hash
        db.outbox().pendingCount("challenge_enrollments", row.id) shouldBe 1
        db.outbox().batch("challenge_events", Long.MAX_VALUE, 100).size shouldBe 29
    }

    @Test
    fun `a session folds incrementally and a late one refolds`() = blocking {
        val f = start()
        val tue = LocalDate.parse("2026-10-20")
        now += 3 * 3_600_000L // 15:00, after the creation
        val a = repo.record(f.enrollment.id, ChallengeEventDraft(EventTypes.SESSION_COMPLETED, session("C", tue), Instant.ofEpochMilli(now), tue))
        a.folded.enrollment.fold_generation shouldBe 0
        a.folded.state.lastChallengeSessionDate shouldBe tue
        // A session from an hour earlier (still after the creation) arrives after it: refold, generation 1.
        val earlier = Instant.ofEpochMilli(now).minusSeconds(3600)
        val b = repo.record(f.enrollment.id, ChallengeEventDraft(EventTypes.SESSION_COMPLETED, session("A", tue), earlier, tue))
        b.folded.enrollment.fold_generation shouldBe 1
        val stored = db.enrollments().get(f.enrollment.id)!!
        stored.state_hash shouldBe StateJson.hash(b.folded.state)
        db.challengeEvents().count(f.enrollment.id) shouldBe 31
        stored.state_rev shouldBe 30
    }

    @Test
    fun `catch-up synthesizes each tick once and reports the gap`() = blocking {
        val f = start()
        now = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli() // Friday
        val r = repo.catchUp()
        r.ticks shouldBe 3
        repo.catchUp().ticks shouldBe 0
        val ticks = db.challengeEvents().forEnrollment(f.enrollment.id).mapNotNull { it.dedupe_key }
        ticks shouldBe listOf("day.tick:2026-10-20", "day.tick:2026-10-21", "day.tick:2026-10-22", "day.tick:2026-10-23")
        db.events().exportAll().count { it.event_type == "tick_catchup" } shouldBe 1
        // Monday 05:00: the week boundary events arrive with the day tick.
        now = Instant.parse("2026-10-26T04:00:00Z").toEpochMilli()
        repo.catchUp()
        db.challengeEvents().forEnrollment(f.enrollment.id).mapNotNull { it.dedupe_key }.takeLast(3) shouldBe
            listOf("day.tick:2026-10-26", "week.closed:2026-W43", "week.started:2026-W44")
    }

    @Test
    fun `a fresh process reloads the stored fold and refolds after a foreign event`() = blocking {
        val f = start()
        val other = make()
        val again = other.folded(f.enrollment.id)
        StateJson.hash(again.state) shouldBe StateJson.hash(f.state)
        again.enrollment.fold_generation shouldBe 0
        // Another device's readiness check, earlier today, arrives through sync.
        val foreign = db.challengeEvents().forEnrollment(f.enrollment.id).last().copy(
            id = "00000000-0000-7000-8000-000000000001", event_type = EventTypes.READINESS, dedupe_key = null,
            occurred_at = Instant.parse("2026-10-20T06:00:00Z").toEpochMilli(),
            payload = """{"elbow_score":0,"symptom_codes":[]}""", device_id = "22222222-2222-4222-8222-222222222222",
        )
        db.challengeEvents().insertIgnore(foreign)
        other.reconcile()
        val g = other.folded(f.enrollment.id)
        g.enrollment.fold_generation shouldBe 1
        g.state.stateRev shouldBe 29
    }
}
