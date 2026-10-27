package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.estimation.EstimationFixtures.FACTS
import dev.fitnessapp.algo.estimation.EstimationFixtures.LEG_PRESS
import dev.fitnessapp.algo.estimation.EstimationFixtures.WPU
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.hold.HoldFacts
import dev.fitnessapp.algo.estimation.hold.HoldSet
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The L8 replay: order of modules, snapshot kinds, cached states and determinism. */
class EstimationReplayTest {
    private val params = TestParams.defaults()
    private val hang = HoldFacts("one_arm_hang", "grip", "hang", 1.0)

    private fun session(id: String, date: String, vararg sets: dev.fitnessapp.algo.estimation.LoggedSet, holds: List<HoldSet> = emptyList()) =
        FinishedSession(id, Instant.parse("${date}T17:45:00Z"), LocalDate.parse(date), 70.7, sets.map { it.copy(sessionId = id) }, holds)

    private val week1 = session(
        "s1", "2026-10-01",
        set("a1", "2026-10-01T17:50:00Z", 93.2, 5, 2.0),
        set("a2", "2026-10-01T17:53:00Z", 93.2, 5, 1.0, restBeforeS = 150.0),
        set("lp1", "2026-10-01T18:10:00Z", 120.0, 10, 2.0, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL),
        holds = listOf(HoldSet("h1", "one_arm_hang", "R", Instant.parse("2026-10-01T18:20:00Z"), "working", null, 0.0, 10.0, 5.0)),
    )
    private val week2 = session(
        "s2", "2026-10-08",
        set("b1", "2026-10-08T17:50:00Z", 93.2, 5, 2.0),
        set("b2", "2026-10-08T17:53:00Z", 93.2, 5, 1.0, restBeforeS = 150.0),
        set("bw", "2026-10-08T18:05:00Z", 70.7, 16, 0.0, setKind = "test"),
    )

    private fun run(sessions: List<FinishedSession>) =
        EstimationReplay.run(sessions, FACTS, mapOf(hang.exerciseId to hang), emptyList(), params)

    @Test
    fun `snapshots per session in pipeline order`() {
        val r = run(listOf(week1, week2))
        r.snapshots.map { it.kind to it.scopeKey } shouldContainExactly listOf(
            "estimation.rcm" to "exercise:leg_press_45",
            "estimation.rcm" to "exercise:weighted_pullup",
            "estimation.sse" to "session:s1",
            "estimation.hold" to "session:s1",
            "estimation.rcm" to "exercise:weighted_pullup",
            "estimation.sse" to "session:s2",
        )
        r.states.map { Triple(it.model, it.exerciseId, it.side) } shouldContainExactly listOf(
            Triple("sse", "leg_press_45", "both"),
            Triple("sse", "weighted_pullup", "both"),
            Triple("rcm", "leg_press_45", "both"),
            Triple("rcm", "weighted_pullup", "both"),
            Triple("hold", "one_arm_hang", "R"),
        )
    }

    @Test
    fun `a max-reps set next to heavy fives is an informative window that moves k within the rate limit`() {
        val r = run(listOf(week1, week2))
        val wpu = r.states.single { it.model == "rcm" && it.exerciseId == WPU.exerciseId }
        val k = wpu.state.getValue("k_used").jsonPrimitive.content.toDouble()
        wpu.state.getValue("m").jsonPrimitive.content shouldBe "1"
        (k in 27.0..33.0) shouldBe true
        // SSE-1 of session 2 used that k.
        val sse2 = r.snapshots.single { it.scopeKey == "session:s2" }
        sse2.inputs.getValue("k_by_exercise").let { it as kotlinx.serialization.json.JsonObject }
            .getValue(WPU.exerciseId).jsonPrimitive.content.toDouble() shouldBe (k plusOrMinus 1e-9)
    }

    @Test
    fun `same facts give the same bytes, whatever the input order`() {
        val a = run(listOf(week1, week2))
        val shuffled = listOf(week1.copy(sets = week1.sets.reversed()), week2.copy(sets = week2.sets.reversed()))
        val b = run(shuffled)
        a.snapshots.map { Jcs.canonicalize(it.outputs) + it.inputsHash } shouldBe b.snapshots.map { Jcs.canonicalize(it.outputs) + it.inputsHash }
        a.states.map { it.stateHash } shouldBe b.states.map { it.stateHash }
    }

    @Test
    fun `an explicit RIR check is snapshotted and cached for the user and its class`() {
        val check = session(
            "s3", "2026-10-10",
            set("c1", "2026-10-10T10:00:00Z", 120.0, 12, 2.0, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL, setKind = "calibration")
                .copy(calibExtraReps = 4),
        )
        val r = run(listOf(check))
        val snap = r.snapshots.single { it.kind == "estimation.rircal" }
        snap.setLogId shouldBe "c1"
        snap.outputs.getValue("b_after").jsonPrimitive.content shouldBe "0.8"
        r.states.filter { it.model == "rircal" }.map { it.classKey } shouldContainExactly listOf("user", "lower_machine_compound")
    }
}
