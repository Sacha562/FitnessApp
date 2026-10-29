package dev.fitnessapp.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.algo.targets.GoalPresets
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncConfig
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

/** Muscle goals (D6, UX 8.9 and 15.4, M4.2): presets, single changes, `goal_changed`, the profile's preset code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoalRepositoryTest {
    private lateinit var db: FitnessDatabase
    private lateinit var goals: GoalRepository
    private lateinit var profiles: ProfileRepository
    private val presets = GoalPresets.parse(requireNotNull(javaClass.getResource("/fitness/presets/goal-presets-1.0.0.json")).readText(Charsets.UTF_8))
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
        profiles = ProfileRepository(db, outbox, telemetry, ctx, scheduler)
        goals = GoalRepository(db, outbox, telemetry, ctx, scheduler, profiles)
    }

    @After
    fun tearDown() = db.close()

    private fun events() = runBlocking { db.events().ofTypes(listOf("goal_changed")) }.map { Json.parseToJsonElement(it.payload).jsonObject }

    @Test
    fun presetWritesAllGroupsOnce(): Unit = runBlocking {
        goals.current() shouldBe emptyMap()
        goals.applyPreset(presets, "balanced")
        val g = goals.current()
        g.size shouldBe 18
        g["quads"] shouldBe GroupGoal("improve")
        g["calves"] shouldBe GroupGoal("maintain")
        profiles.get()!!.muscle_goal_preset shouldBe "balanced"
        db.muscleGoals().live().map { it.id }.toSet() shouldBe g.keys.map { GoalRepository.idOf(uid, it) }.toSet()
        events().size shouldBe 18
        events().first()["via"]!!.jsonPrimitive.content shouldBe "preset"
        // Applying it again changes nothing and logs nothing.
        goals.applyPreset(presets, "balanced")
        events().size shouldBe 18
        // No legs: only the six leg groups change goal.
        goals.applyPreset(presets, "no_legs")
        events().size shouldBe 24
    }

    @Test
    fun singleChangeMarksCustomAndSavesTheBaseline(): Unit = runBlocking {
        val baseline = presets.goals("balanced")
        goals.set("chest", GroupGoal("improve", focus = true), baseline)
        val g = goals.current()
        g.size shouldBe 18
        g["chest"] shouldBe GroupGoal("improve", true)
        g["quads"] shouldBe GroupGoal("improve")
        profiles.get()!!.muscle_goal_preset shouldBe "custom"
        val chest = events().single { it["group"]!!.jsonPrimitive.content == "chest" }
        chest["focus_to"]!!.jsonPrimitive.content shouldBe "true"
        chest["via"]!!.jsonPrimitive.content shouldBe "manual"
        shouldThrow<IllegalArgumentException> { goals.set("chest", GroupGoal("maintain", focus = true), baseline) }
    }

    @Test
    fun settingLogsChangesOnly(): Unit = runBlocking {
        profiles.set(Setting.MAX_VISITS, 3)
        profiles.set(Setting.MAX_VISITS, 3)
        profiles.get()!!.max_gym_visits_per_week shouldBe 3
        val ev = db.events().ofTypes(listOf("settings_changed")).map { Json.parseToJsonElement(it.payload).jsonObject }
        ev.size shouldBe 1
        ev.single()["from"]!!.jsonPrimitive.content shouldBe "4"
        ev.single()["to"]!!.jsonPrimitive.content shouldBe "3"
    }
}
