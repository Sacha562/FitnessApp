package dev.fitnessapp.algo.estimation.hold

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.estimation.sse.SideKey
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** B8: the 90-degree hold step (GF-HOLD-01), the one-arm hang target (GF-HOLD-02), chips, segments, fallback class. */
class HoldTest {
    private val params = TestParams.defaults()
    private val start = Instant.parse("2026-10-08T17:45:00Z")
    private val hold90 = HoldFacts("hold_90", "elbow", "skill_static", 1.0)
    private val hang = HoldFacts("one_arm_hang", "grip", "hang", 1.0)
    private val plank = HoldFacts("plank", "trunk", "core_anti_extension", 0.0)
    private val facts = mapOf(hold90.exerciseId to hold90, hang.exerciseId to hang, plank.exerciseId to plank)

    private fun hold(id: String, ex: String, w: Double?, t: Double, chip: String?, side: String = "R", ext: Double = 0.0, at: Long = 0) =
        HoldSet(id, ex, side, start.plusSeconds(at), "working", w, ext, t, chip)

    @Test
    fun `GF-HOLD-01 the 90-degree hold at pin 35, then pin 30`() {
        val r = Hold.update(listOf(hold("h1", "hold_90", 48.32, 8.0, "hard")), facts, emptyMap(), start, params)
        val st = r.next.getValue(SideKey("hold_90", "R"))
        Det.exp(st.trend.l) shouldBe (10.0 plusOrMinus 1e-9)
        st.wRef shouldBe 48.32
        val beta = Hold.beta(hold90, params)
        Hold.tCap(st, 50.59, beta) shouldBe (9.04 plusOrMinus 0.005)
        Hold.tTarget(Hold.tCap(st, 50.59, beta), 0.0, params) shouldBe (7.04 plusOrMinus 0.005)
        val rx = Hold.prescribe(st, beta, listOf(48.32, 50.59), 6.0, 8.0, 0.0, params)
        rx.wSys shouldBe 50.59
        rx.tTargetS shouldBe 7.0
        rx.feasible shouldBe true
    }

    @Test
    fun `GF-HOLD-02 the one-arm dead hang`() {
        val r = Hold.update(listOf(hold("g1", "one_arm_hang", null, 10.0, "ok")), facts, emptyMap(), start, params)
        val st = r.next.getValue(SideKey("one_arm_hang", "R"))
        Det.exp(st.trend.l) shouldBe (15.0 plusOrMinus 1e-9)
        Hold.prescribe(st, Hold.beta(hang, params), emptyList(), 10.0, 15.0, 0.0, params).tTargetS shouldBe 12.0
    }

    @Test
    fun `chips, lower bounds and pain`() {
        val r = Hold.update(
            listOf(
                hold("e", "hold_90", 48.32, 8.0, "easy", at = 0),
                hold("p", "hold_90", 48.32, 3.0, "max", side = "L", at = 60),
            ).map { if (it.id == "p") it.copy(reasonCode = "pain") else it },
            facts, emptyMap(), start, params,
        )
        // A lone "easy" (lower bound) cannot start a state; the pain set is no observation.
        r.next shouldBe emptyMap()
        r.sets.map { it.setId to it.estimatorClass } shouldBe listOf("e" to "lower_bound")
        r.trace.map { it.code } shouldBe listOf("hold.skip.excluded")
    }

    @Test
    fun `a plate on a time-only plank starts a new segment`() {
        val first = Hold.update(listOf(hold("p1", "plank", null, 40.0, "ok", side = "both")), facts, emptyMap(), start, params)
        val key = SideKey("plank", "both")
        first.next.getValue(key).segmentKg shouldBe 0.0
        val second = Hold.update(
            listOf(hold("p2", "plank", null, 20.0, "ok", side = "both", ext = 5.0)),
            facts, first.next, start.plusSeconds(86400L * 2), params,
        )
        second.trace.map { it.code } shouldBe listOf("hold.segment.new")
        Det.exp(second.next.getValue(key).trend.l) shouldBe (25.0 plusOrMinus 1e-9)
    }

    @Test
    fun `missing hold class falls back on the pattern`() {
        Hold.holdClass(HoldFacts("x", null, "straight_arm_pull", 1.0)) shouldBe "shoulder"
        Hold.holdClass(HoldFacts("x", null, "core_plank", 0.0)) shouldBe "trunk"
        Hold.holdClass(HoldFacts("x", null, "row", 1.0)) shouldBe "general"
    }

    @Test
    fun `progression needs every set at the top with reserve`() {
        Hold.reachedTop(listOf(8.0 to 2.0, 8.0 to 2.0), 8.0, 7.0, params) shouldBe true
        Hold.reachedTop(listOf(8.0 to 2.0, 7.5 to 5.0), 8.0, 7.0, params) shouldBe false
    }
}
