package dev.fitnessapp.data.challenge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventCodec
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.PastTest
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.challenge.materialize.MigrationPreviews
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
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))

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

    private suspend fun start(version: String? = null, pastTests: List<PastTest> = emptyList()): FoldedEnrollment {
        repo.ensureBundled()
        val defId = db.challengeDefinitions().all().let { all -> version?.let { v -> all.single { it.version == v } } ?: all.maxBy { it.version } }.id
        val def = repo.definition(defId)
        return repo.create(
            defId, sortedMapOf(), onboard,
            ImportBlock(phase = 1, blockIndex = 1, blockWeek = 2, mode = "normal", weekOrdinal = 7, pastTests = pastTests),
            def.doc.proposals.associate { it.id to "approved" },
        )
    }

    private fun session(slot: String, date: LocalDate) = EventPayload.Session(
        SessionLog(UUID.randomUUID(), slot, "${slot}1", "gym", date, BigDecimal.ONE, false, null, sortedMapOf()),
    )

    @Test
    fun `create folds the import and every decision and queues the rows`() = blocking {
        val f = start()
        db.challengeDefinitions().all().map { it.version to it.source } shouldBe listOf("1.0.0" to "bundled", "1.0.1" to "bundled", "1.1.0" to "bundled", "1.2.0" to "bundled", "1.3.0" to "bundled", "1.4.0" to "bundled", "1.5.0" to "bundled", "1.6.0" to "bundled", "1.7.0" to "bundled")
        f.state.status shouldBe EnrollmentState.ACTIVE
        f.state.block.week shouldBe 2
        f.state.proposalDecisions.size shouldBe 29
        // D37: before the enrollment exists the facts resolve the grip-dependent weighted pull-up with the param defaults,
        // so its k is there and the inits that read userK('weighted_pullup') succeed.
        (f.state.vars["anchorFraction"] as? QV)?.v?.toPlainString() shouldBe "0.8239"
        (f.state.vars["e1rmAdded"] as? QV)?.v?.stripTrailingZeros()?.toPlainString() shouldBe "39.38"
        // created + 27 decisions + today's day tick, which sorts first (00:00.002) and is folded after the creation
        val events = db.challengeEvents().forEnrollment(f.enrollment.id)
        events.size shouldBe 31
        events.first().dedupe_key shouldBe "day.tick:2026-10-20"
        events[1].event_type shouldBe EventTypes.CREATED
        val row = db.enrollments().get(f.enrollment.id)!!
        row.status shouldBe "active"
        row.state_hash shouldBe StateJson.hash(f.state)
        row.last_folded_event_id shouldBe events.last().id
        row.fold_generation shouldBe 0
        Json.parseToJsonElement(row.rule_decisions).jsonObject.size shouldBe 29
        // The stored payloads fold back to the same state.
        val def = f.def
        val decoded = events.map {
            EventCodec.decode(def, UUID.fromString(it.id), it.event_type, Instant.ofEpochMilli(it.occurred_at), LocalDate.parse(it.local_date), Json.parseToJsonElement(it.payload).jsonObject)
        }
        StateJson.hash(EnrollmentFold.fold(def, params, decoded).state) shouldBe row.state_hash
        db.outbox().pendingCount("challenge_enrollments", row.id) shouldBe 1
        db.outbox().batch("challenge_events", Long.MAX_VALUE, 100).size shouldBe 31
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
        db.challengeEvents().count(f.enrollment.id) shouldBe 33
        stored.state_rev shouldBe 32
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
        g.state.stateRev shouldBe 31
    }

    @Test
    fun `a correction of the earlier tests refolds and survives a reload (D48)`() = blocking {
        val d = LocalDate.parse("2026-09-29")
        val f = start(pastTests = listOf(PastTest(d, TreeMap(), validForGate = true)))
        f.state.testBaseline.containsKey("oa_hang_s") shouldBe false
        now += 60_000
        val hang = SideV(Values.duration("15"), Values.duration("20"))
        val out = repo.amendImportedTests(f.enrollment.id, listOf(PastTest(d, TreeMap(mapOf("oa_hang_s" to hang)), validForGate = true)))
        out.warnings.filter { it.startsWith("creation_amendment") } shouldBe emptyList()
        out.folded.state.testBaseline.getValue("oa_hang_s").value shouldBe hang
        out.folded.state.vars shouldBe f.state.vars
        out.folded.enrollment.fold_generation shouldBe 1
        val rows = db.challengeEvents().forEnrollment(f.enrollment.id).filter { it.event_type == EventTypes.CREATED }
        rows.size shouldBe 2
        rows.last().supersedes_event_id shouldBe rows.first().id
        // The stored rows fold back to the same state in a fresh process, with no further generation.
        val again = make().folded(f.enrollment.id)
        StateJson.hash(again.state) shouldBe StateJson.hash(out.folded.state)
        again.enrollment.fold_generation shouldBe 1
    }

    @Test
    fun `a definition update moves the enrollment to the newest version and refolds across it (D44, D45, D47)`() = blocking {
        val f = start("1.4.0")
        val to = requireNotNull(repo.updateFor(f.enrollment.id))
        to.version shouldBe "1.7.0"
        val facts = ChallengeFactsProvider(db, EquipmentRepository(db, OutboxWriter(db), TelemetryWriter(db, OutboxWriter(db), ctx, EventRegistry.bundled()), ctx, SyncScheduler({ error("off") }, SyncConfig(), enabled = false)), { CatalogRepository.bundled().doc }, params, { Instant.ofEpochMilli(now) })
        val today = LocalDate.parse("2026-10-20")
        val preview = MigrationPreviews.preview(
            f.def, to, params, f.state, facts.facts(f.def, f.state), CatalogRepository.bundled().doc, listOf("A" to today.plusDays(3)), today, 5,
        )
        preview.sessions.single().changes.map { it.kind to it.key } shouldBe listOf("removed" to "wu_row")
        // D45: the elbow-trace rule widens; this gym has no cable, so the per-side Pallof press is not planned on either side.
        preview.rules.map { it.kind to it.id } shouldBe listOf("changed" to "safety.elbow.traceFromPain")
        now += 60_000
        val out = repo.migrate(f.enrollment.id, to, preview)
        out.folded.def.version shouldBe "1.7.0"
        val row = db.enrollments().get(f.enrollment.id)!!
        row.definition_version shouldBe "1.7.0"
        row.definition_id shouldBe ChallengeLibrary.idOf("oap", "1.7.0").toString()
        db.challengeEvents().forEnrollment(f.enrollment.id).last().let { it.event_type to it.definition_version } shouldBe (EventTypes.MIGRATED to "1.7.0")
        repo.updateFor(f.enrollment.id) shouldBe null
        // A fresh repository folds the stored events across both versions to the same state.
        val g = make().folded(f.enrollment.id)
        g.def.version shouldBe "1.7.0"
        g.enrollment.fold_generation shouldBe 0
        StateJson.hash(g.state) shouldBe row.state_hash
    }

    @Test
    fun `an update made on a stale preview is refused`() = blocking {
        val f = start("1.4.0")
        val to = requireNotNull(repo.updateFor(f.enrollment.id))
        val preview = MigrationPreviews.preview(f.def, to, params, f.state, f.state.lastFacts!!, null, emptyList(), LocalDate.parse("2026-10-20"), 5)
        now += 86_400_000
        repo.catchUp()
        runCatching { repo.migrate(f.enrollment.id, to, preview) }.exceptionOrNull()?.message shouldBe "the enrollment changed after the preview"
    }
}
