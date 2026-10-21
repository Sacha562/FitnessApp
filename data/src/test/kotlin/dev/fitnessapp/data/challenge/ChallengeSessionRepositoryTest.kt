package dev.fitnessapp.data.challenge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.core.params.ParamView
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
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.WriteContext
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** M2b.3: a challenge session starts from the materialized plan, runs its in-session rules and completes the slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeSessionRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var challenges: ChallengeRepository
    private lateinit var sessions: SessionRepository
    private lateinit var live: ChallengeSessionRepository
    private lateinit var equipment: EquipmentRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.1.json"))

    /** Thursday 2026-10-22 19:45 in Amsterdam. */
    private var now = Instant.parse("2026-10-22T17:45:00Z").toEpochMilli()

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing $path" }.readText(Charsets.UTF_8)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val ctx = WriteContext(
            DeviceIdentity(app), "0.2.0-test", Clock { Instant.ofEpochMilli(now) }, { zone },
            UuidV7Generator({ now }), { "11111111-1111-4111-8111-111111111111" },
        )
        val outbox = OutboxWriter(db)
        val telemetry = TelemetryWriter(db, outbox, ctx, EventRegistry.bundled())
        val scheduler = SyncScheduler({ error("sync disabled in this test") }, SyncConfig(), enabled = false)
        equipment = EquipmentRepository(db, outbox, telemetry, ctx, scheduler)
        val bodyweight = BodyweightRepository(db, outbox, ctx, scheduler)
        val profiles = ProfileRepository(db, outbox, telemetry, ctx, scheduler)
        val catalog = CatalogRepository.bundled()
        val facts = ChallengeFactsProvider(db, equipment, { catalog.doc }, params, { Instant.ofEpochMilli(now) })
        challenges = ChallengeRepository(db, outbox, telemetry, ctx, scheduler, params, ChallengeLibrary(params, 5, ::resource), facts)
        sessions = SessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles)
        live = ChallengeSessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles, challenges, facts, params, 5)
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun scheme(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s).let { it as kotlinx.serialization.json.JsonObject }

    /** The Basic-Fit checklist of the M1 onboarding, with the OAP stations. */
    private suspend fun gym() {
        val bw = scheme("""{"type":"bodyweight"}""")
        equipment.saveChecklist(
            GymDraft("Basic-Fit", listOf("basic-fit"), "{}"),
            listOf(
                EquipmentDraft("assisted_pullup_machine", "Assisted machine", true, scheme("""{"type":"pin_assist","unit":"lb","step":5,"min":0,"max":100,"offset_kg":6.5,"ratio":1.0}""")),
                EquipmentDraft("dip_belt", "Dip belt", true, scheme("""{"type":"belt","unit":"kg","plates":[{"kg":20,"count":2},{"kg":10,"count":2},{"kg":5,"count":2},{"kg":2.5,"count":2}],"max_kg":60,"max_items":4,"dumbbells_on_belt":false}""")),
                EquipmentDraft("pullup_bar", "Pull-up bar", true, bw),
                EquipmentDraft("fixed_neutral_handle", "Neutral handle", true, bw),
                EquipmentDraft("dip_station", "Dip station", true, bw),
                EquipmentDraft("rower_erg", "Rower", true, bw),
            ),
            confirmed = true,
        )
    }

    private suspend fun enroll(except: Map<String, String> = emptyMap()): String {
        gym()
        challenges.ensureBundled()
        val defId = db.challengeDefinitions().all().maxBy { it.version }.id
        val def = challenges.definition(defId)
        val onboard = TreeMap<String, Value>(
            mapOf(
                "bw" to Values.mass("70.7"), "wpu3rmAdded" to Values.mass("32.5"), "bwMaxReps" to Values.int(18), "anchorLoad" to Values.mass("20"),
                "pinA" to Values.pin("40"), "pinC" to Values.pin("50"), "pinHold90" to Values.pin("35"),
            ),
        )
        val f = challenges.create(
            defId, sortedMapOf(), onboard, ImportBlock(blockWeek = 2, mode = "normal", weekOrdinal = 7),
            def.doc.proposals.associate { it.id to (except[it.id] ?: "approved") },
        )
        now += 60_000
        return f.enrollment.id
    }

    private suspend fun log(sessionId: String, seId: String, exerciseId: String, setIndex: Int, side: String, reps: Int) {
        now += 30_000
        sessions.logSet(
            LogSetCommand(
                sessionId, seId, exerciseId, setIndex, side, "working", null, reps, null, 1.0, false, null, false, emptyList(), null, null, null, null, now, emptyMap(),
            ),
        )
    }

    @Test
    fun `session A starts from the plan, calibrates the pin, undoes it and completes the slot`() = blocking {
        val enrollmentId = enroll(except = mapOf("P13" to "declined"))
        val sessionId = live.start(enrollmentId, "A")
        val s = db.sessions().get(sessionId)!!
        s.template_source shouldBe "challenge_slot"
        s.challenge_slot shouldBe "A"
        s.challenge_template_id shouldBe "A1"
        db.blocks().bySession(sessionId).single().block_kind shouldBe "challenge"
        val exercises = db.sessionExercises().bySession(sessionId)
        val oaa = exercises.single { it.challenge_item_key == "oaa" }
        oaa.load_mode shouldBe "assisted_machine"
        oaa.planned_sets shouldBe 5
        oaa.side_mode shouldBe "unilateral_sequential"
        exercises.any { it.challenge_item_key == "wu_row" } shouldBe true

        // Calibration pair: L 2, R 4 -> +5 lb on the remaining sets (ins.A.oaa.calibrate, D14).
        log(sessionId, oaa.id, oaa.exercise_id, 1, "L", 2)
        live.onSetLogged(sessionId, oaa.id) shouldBe emptyList()
        log(sessionId, oaa.id, oaa.exercise_id, 1, "R", 4)
        val apps = live.onSetLogged(sessionId, oaa.id)
        val calib = apps.single { it.kind == "suggest" }
        calib.ruleId shouldBe "ins.A.oaa.calibrate"
        live.live(sessionId)!!.items.getValue("oaa").rows.drop(2).map { it.pin } shouldBe List(8) { BigDecimal(45) }

        live.undo(sessionId, oaa.id, calib, "other", 1_500)
        live.live(sessionId)!!.items.getValue("oaa").rows.drop(2).map { it.pin } shouldBe List(8) { BigDecimal(40) }
        db.sessionExercises().get(oaa.id)!!.user_override shouldBe true

        now += 3_600_000
        sessions.finish(sessionId, "no_time")
        val outcome = live.complete(sessionId)!!
        outcome.folded.state.lastChallengeSessionDate shouldBe LocalDate.parse("2026-10-22")
        val events = db.challengeEvents().forEnrollment(enrollmentId)
        events.last().event_type shouldBe EventTypes.SESSION_COMPLETED
        events.last().source_session_id shouldBe sessionId
    }

    @Test
    fun `the plank mini-session reports its rows as habit_completed and the hang is quick-done`() = blocking {
        val enrollmentId = enroll()
        val today = LocalDate.parse("2026-10-22")
        val day = live.habitDay(enrollmentId, today, sortedMapOf(), emptySet())
        day.rows.map { it.habitId to it.state } shouldBe listOf("daily_hang" to "DUE", "home_plank" to "DUE")

        val sessionId = live.startHabit(day, "home_plank", "today")
        val s = db.sessions().get(sessionId)!!
        s.kind shouldBe "home"
        s.template_source shouldBe "habit"
        s.habit_id shouldBe "home_plank"
        s.challenge_slot shouldBe null
        s.challenge_template_id shouldBe "habit:home_plank"
        s.gym_id shouldBe null
        s.time_budget_min shouldBe null
        val plank = db.sessionExercises().bySession(sessionId).single()
        plank.challenge_item_key shouldBe "plank"
        plank.planned_sets shouldBe 3
        for (i in 1..3) for (side in listOf("L", "R")) {
            now += 30_000
            sessions.logSet(LogSetCommand(sessionId, plank.id, plank.exercise_id, i, side, "working", null, null, 20.0, null, false, 2.0, false, emptyList(), null, null, null, null, now, emptyMap()))
        }
        now += 60_000
        sessions.finish(sessionId, null)
        live.complete(sessionId)
        val e = db.challengeEvents().forEnrollment(enrollmentId).last()
        e.event_type shouldBe EventTypes.HABIT_COMPLETED
        e.source_session_id shouldBe sessionId
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(e.payload) as kotlinx.serialization.json.JsonObject
        (payload.getValue("items") as kotlinx.serialization.json.JsonObject).keys shouldBe setOf("plank")

        now += 60_000
        live.quickDone(enrollmentId, "daily_hang")
        live.habitDay(enrollmentId, today, sortedMapOf(), emptySet()).rows.associate { it.habitId to it.state } shouldBe
            mapOf("daily_hang" to "DONE", "home_plank" to "DONE")
    }
}
