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
import dev.fitnessapp.data.repo.HistoryRepository
import dev.fitnessapp.data.repo.LogSetCommand
import dev.fitnessapp.data.repo.ProfileRepository
import dev.fitnessapp.data.repo.SetEdit
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
    private lateinit var history: HistoryRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))

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
        history = HistoryRepository(db, outbox, telemetry, ctx, scheduler, sessions)
        live = ChallengeSessionRepository(db, outbox, telemetry, ctx, scheduler, catalog, equipment, bodyweight, profiles, challenges, facts, { params }, 5)
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
                EquipmentDraft("cable_station", "Cable station", true, scheme("""{"type":"stack","unit":"lb","start":5,"step":5,"max":220,"ratio":1,"add_on_steps":[]}""")),
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
        exercises.any { it.challenge_item_key == "wu_face" } shouldBe true
        exercises.none { it.challenge_item_key == "wu_row" } shouldBe true

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
    fun `D111 an exercise added to a challenge session joins a user-built general block`() = blocking {
        val enrollmentId = enroll(except = mapOf("P13" to "declined"))
        val sessionId = live.start(enrollmentId, "A")
        val bench = CatalogRepository.bundled().bySlug("db_bench_press")!!
        val first = sessions.addExercise(sessionId, bench, dev.fitnessapp.algo.core.load.LoadMode.EXTERNAL)
        val blocks = db.blocks().bySession(sessionId)
        blocks.map { it.block_kind } shouldBe listOf("challenge", "general")
        val general = blocks.last()
        general.origin shouldBe "user_built"
        db.sessionExercises().get(first)!!.block_id shouldBe general.id
        // A second add joins the same block; the event names the block it went to.
        val second = sessions.addExercise(sessionId, bench, dev.fitnessapp.algo.core.load.LoadMode.EXTERNAL)
        db.sessionExercises().get(second)!!.block_id shouldBe general.id
        db.blocks().bySession(sessionId).size shouldBe 2
        db.events().exportAll().filter { it.event_type == "exercise_added" }
            .all { it.payload.contains("\"block_kind\":\"general\"") } shouldBe true
    }

    @Test
    fun `a session with every planned set logged is complete, warm-up items aside, and its block is done`() = blocking {
        val enrollmentId = enroll()
        val sessionId = live.start(enrollmentId, "C")
        val plan = live.live(sessionId)!!.plan.items.associate { it.key to it.role }
        val exercises = db.sessionExercises().bySession(sessionId)
        exercises.any { plan[it.challenge_item_key] == "warmup" } shouldBe true
        for (e in exercises) for (i in 1..(e.planned_sets ?: 0)) {
            now += 30_000
            val kind = if (plan[e.challenge_item_key] == "warmup") "warmup" else "working"
            sessions.logSet(
                LogSetCommand(sessionId, e.id, e.exercise_id, i, "both", kind, null, 5, null, 2.0, false, null, false, emptyList(), null, null, null, null, now, emptyMap()),
            )
        }
        now += 60_000
        sessions.finish(sessionId, null)
        db.sessions().get(sessionId)!!.completion_ratio shouldBe 10000L
        db.blocks().bySession(sessionId).single().status shouldBe "done"
        // A History edit afterwards recomputes the ratio the same way.
        val set = db.setLogs().bySession(sessionId).first { it.set_kind == "working" }
        history.editSet(set.id, SetEdit(null, 6, null, 2.0, false, null, false, emptyList(), null), "history") shouldBe 1
        db.sessions().get(sessionId)!!.completion_ratio shouldBe 10000L
        exercises.filter { plan[it.challenge_item_key] == "warmup" }.map { db.sessionExercises().get(it.id)!!.status }.distinct() shouldBe listOf("done")
        val finished = db.events().exportAll().single { it.event_type == "session_finished" }.payload
        finished.contains("\"working_sets_planned\":${exercises.filter { plan[it.challenge_item_key] != "warmup" }.sumOf { it.planned_sets ?: 0 }}") shouldBe true
    }

    @Test
    fun `added kg is a belt load only on a belt lift, the Pallof press stays an external cable load`() = blocking {
        val enrollmentId = enroll()
        val sessionId = live.start(enrollmentId, "B")
        val exercises = db.sessionExercises().bySession(sessionId)
        val wpu = exercises.single { it.challenge_item_key == "wpu" }
        wpu.load_mode shouldBe "bodyweight_plus_external"
        val pallof = exercises.single { it.challenge_item_key == "pallof" }
        pallof.load_mode shouldBe "external"
        pallof.planned_load_input shouldBe 1134L
        equipment.resolve(CatalogRepository.bundled().bySlug("pallof_press")!!, dev.fitnessapp.algo.core.load.LoadMode.EXTERNAL).row!!.equipment_type shouldBe "cable_station"
    }

    @Test
    fun `amend reports the corrected rows as session_amended superseding the latest report`() = blocking {
        val enrollmentId = enroll()
        val sessionId = live.start(enrollmentId, "B")
        val pallof = db.sessionExercises().bySession(sessionId).single { it.challenge_item_key == "pallof" }
        log(sessionId, pallof.id, pallof.exercise_id, 1, "both", 10)
        now += 600_000
        sessions.finish(sessionId, "no_time")
        val completed = live.complete(sessionId)!!
        val set = db.setLogs().bySession(sessionId).single()
        db.setLogs().upsert(set.copy(side = "L"))
        db.setLogs().upsert(set.copy(id = "01a16a69-0000-7000-8000-000000000001", side = "R", completed_at = set.completed_at + 1))

        live.amend(sessionId)!!
        live.amend(sessionId)!!
        val reports = db.challengeEvents().forEnrollment(enrollmentId).filter { it.source_session_id == sessionId }
        reports.map { it.event_type } shouldBe listOf(EventTypes.SESSION_COMPLETED, EventTypes.SESSION_AMENDED, EventTypes.SESSION_AMENDED)
        reports[1].supersedes_event_id shouldBe reports[0].id
        reports[2].supersedes_event_id shouldBe reports[1].id
        reports[2].payload.contains("\"side\":\"R\"") shouldBe true
        completed.folded.state.lastChallengeSessionDate shouldBe LocalDate.parse("2026-10-22")
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

    @Test
    fun `the habit's hang logged in a free gym session completes the daily hang once (D59)`() = blocking {
        val enrollmentId = enroll()
        val today = LocalDate.parse("2026-10-22")
        val catalog = CatalogRepository.bundled()
        val hang = catalog.bySlug("two_arm_dead_hang")!!
        val bench = catalog.bySlug("db_bench_press")!!
        val sid = sessions.start(params)
        val hangSe = sessions.addExercise(sid, hang, dev.fitnessapp.algo.core.load.LoadMode.BODYWEIGHT)
        val benchSe = sessions.addExercise(sid, bench, dev.fitnessapp.algo.core.load.LoadMode.EXTERNAL)
        log(sid, benchSe, catalog.idOf(bench), 1, "both", 10)
        for ((i, s) in listOf(60.0, 45.0, 43.0).withIndex()) {
            now += 30_000
            sessions.logSet(LogSetCommand(sid, hangSe, catalog.idOf(hang), i + 1, "both", "working", null, null, s, null, false, null, false, emptyList(), null, null, null, null, now, emptyMap()))
        }
        val due = live.habitDay(enrollmentId, today, sortedMapOf(), emptySet())
        // Both habits are due on this Thursday; only the hang's exercise is in the session.
        live.habitExercises(due).associate { it.habitId to it.exerciseId }["daily_hang"] shouldBe catalog.idOf(hang)
        live.habitExercises(due).single { it.habitId == "daily_hang" }.maxRows shouldBe 5
        // Not before the session is finished.
        live.creditHabits(sid, due) shouldBe emptyList()

        now += 60_000
        sessions.finish(sid, null)
        live.creditHabits(sid, due).size shouldBe 1
        val e = db.challengeEvents().forEnrollment(enrollmentId).last()
        e.event_type shouldBe EventTypes.HABIT_COMPLETED
        e.source_session_id shouldBe sid
        val items = (kotlinx.serialization.json.Json.parseToJsonElement(e.payload) as kotlinx.serialization.json.JsonObject).getValue("items") as kotlinx.serialization.json.JsonObject
        val dhang = items.getValue("dhang").toString()
        dhang.contains("\"status\":\"partial\"") shouldBe true
        Regex("\"duration_s\"").findAll(dhang).count() shouldBe 3
        live.habitDay(enrollmentId, today, sortedMapOf(), emptySet()).rows.associate { it.habitId to it.state }["daily_hang"] shouldBe "DONE"
        // A second call (the same stale day) reports nothing new.
        live.creditHabits(sid, due) shouldBe emptyList()
    }
}
