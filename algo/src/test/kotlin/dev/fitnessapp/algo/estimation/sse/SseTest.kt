package dev.fitnessapp.algo.estimation.sse

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.estimation.EstimationFixtures.FACTS
import dev.fitnessapp.algo.estimation.EstimationFixtures.WPU
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** B6: the worked session (GF-SSE-01), detraining (GF-SSE-02), eligibility (GF-SSE-03), lower bounds (GF-SSE-04). */
class SseTest {
    private val params = TestParams.defaults()
    private val key = SideKey(WPU.exerciseId, "both")
    private val t0 = Instant.parse("2026-10-01T17:45:00Z")
    private val prior = SseState(TrendState(Det.ln(109.94), 0.003, 0.0009, 0.0, 0.005 * 0.005, t0, 10), 30.63)

    /** Session A one week later: 4 x 5 at +22.5 kg (`w_sys` 93.20), RIR 2/1/1/0, 150 s rests. */
    private fun sessionA(): List<LoggedSet> {
        val start = Instant.parse("2026-10-08T17:45:00Z")
        return listOf(2.0, 1.0, 1.0, 0.0).mapIndexed { i, rir ->
            set("a$i", start.plusSeconds(300L + 180L * i).toString(), 93.20, 5, rir, restBeforeS = if (i == 0) null else 150.0)
        }
    }

    private fun run(sets: List<LoggedSet>, priors: Map<SideKey, SseState> = mapOf(key to prior), k: Double = 30.63): SseResult {
        val prepared = Sse.prepare(sets, FACTS, RirCal.initial(params), params)
        return Sse.update(prepared, priors, mapOf(WPU.exerciseId to k), emptyMap(), FACTS, Instant.parse("2026-10-08T17:45:00Z"), 70.7, params)
    }

    @Test
    fun `GF-SSE-01 the worked weighted pull-up session`() {
        val r = run(sessionA())
        r.sets.map { it.fatigueF } shouldBe listOf(0.0, 0.1166, 0.2246, 0.2889)
        r.sets.map { it.nFresh } shouldBe listOf(7.5, 7.36, 8.38, 7.73)
        listOf(0.0372, 0.0352, 0.0413, 0.0417).forEachIndexed { i, s -> r.sets[i].s shouldBe (s plusOrMinus 0.00005) }
        listOf(112.98, 112.55, 115.66, 113.69).forEachIndexed { i, e -> r.sets[i].e1rmKg shouldBe (e plusOrMinus 0.011) }
        val st = r.states.single()
        Det.exp(st.lPrior!!) shouldBe (110.27 plusOrMinus 0.005)
        Det.sqrt(st.pLLPrior!!) shouldBe (0.0320 plusOrMinus 0.00005)
        st.k0!! shouldBe (0.391 plusOrMinus 0.0005)
        st.k1!! shouldBe (0.0081 plusOrMinus 0.00005)
        st.huberApplied shouldBe false
        st.mKg shouldBe 111.55
        (st.mKg - 70.7) shouldBe (40.85 plusOrMinus 1e-9)
        (st.ci80LoKg - 70.7) shouldBe (37.34 plusOrMinus 0.005)
        (st.ci80HiKg - 70.7) shouldBe (44.48 plusOrMinus 0.005)
        st.gPerWeek shouldBe (0.0028 plusOrMinus 0.00005)
        r.next.getValue(key).trend.nObs shouldBe 14
        r.next.getValue(key).trend.tLast shouldBe Instant.parse("2026-10-08T17:45:00Z")
    }

    @Test
    fun `GF-SSE-02 detraining after 49 days`() {
        val grace = Trend.predict(prior.trend, t0.plusSeconds(21L * 86400), params)
        Det.exp(grace.l) shouldBe (110.93 plusOrMinus 0.005)
        val p = Trend.predict(prior.trend, t0.plusSeconds(49L * 86400), params)
        // 109.94 exp(0.009 - 0.03) = 107.6553; the spec prints it truncated.
        Det.exp(p.l) shouldBe (107.655 plusOrMinus 0.0005)
        Det.sqrt(p.pLL) shouldBe (0.0585 plusOrMinus 0.00005)
        p.g shouldBe 0.0
    }

    @Test
    fun `detraining is continuous at 21 days and capped`() {
        val at = t0.plusSeconds(21L * 86400)
        val before = Trend.predict(prior.trend, at.minusSeconds(1), params)
        val after = Trend.predict(prior.trend, at.plusSeconds(1), params)
        after.l shouldBe (before.l plusOrMinus 1e-6)
        val long = Trend.predict(prior.trend, t0.plusSeconds(400L * 86400), params)
        val atGrace = Trend.predict(prior.trend, at, params)
        (atGrace.l - long.l) shouldBe (0.1625 plusOrMinus 1e-12)
    }

    @Test
    fun `prediction across the 25-hour DST Sunday uses elapsed time`() {
        val sat = Instant.parse("2026-10-24T17:45:00Z")
        val sun = Instant.parse("2026-10-25T18:45:00Z")
        val s = prior.trend.copy(tLast = sat)
        Trend.predict(s, sun, params).l shouldBe (s.l + s.g * (25.0 / 24.0 / 7.0) plusOrMinus 1e-15)
    }

    @Test
    fun `GF-SSE-03 eligibility excludes negatives, hangs, finger assistance and n_eff above 25`() {
        val sets = listOf(
            set("neg", "2026-10-08T18:10:00Z", 118.2, 2, null, contraction = "eccentric_only"),
            set("hang", "2026-10-08T18:20:00Z", 70.7, 1, null, contraction = "hang", exercise = "one_arm_hang"),
            set("finger", "2026-10-08T18:30:00Z", 70.7, 3, 1.0, fingerAssisted = true, exercise = "finger_pullup"),
            set("many", "2026-10-08T18:40:00Z", 50.0, 26, 0.0, loadMode = LoadMode.ASSISTED_MACHINE, exercise = "assisted_pullup"),
            set("warm", "2026-10-08T17:46:00Z", 70.7, 6, null, setKind = "warmup"),
        )
        val skips = Sse.prepare(sets, FACTS, RirCal.initial(params), params).associate { it.set.id to it.skipCode }
        skips shouldBe mapOf(
            "warm" to "sse.skip.set_kind",
            "neg" to "sse.skip.contraction",
            "hang" to "sse.skip.contraction",
            "finger" to "sse.skip.finger_assist",
            "many" to "sse.skip.n_eff",
        )
        val r = run(sets)
        r.sets shouldBe emptyList()
        r.next shouldBe mapOf(key to prior)
    }

    @Test
    fun `GF-SSE-04 a lower bound above the gate raises L, one below has no effect`() {
        val low = set("lb1", "2026-10-08T18:00:00Z", 80.0, 5, 5.0, lowerBound = true)
        val high = set("lb2", "2026-10-08T18:00:00Z", 100.0, 5, 5.0, lowerBound = true)
        val predicted = Trend.predict(prior.trend, Instant.parse("2026-10-08T17:45:00Z"), params)
        val rLow = run(listOf(low))
        rLow.states shouldBe emptyList()
        rLow.next.getValue(key) shouldBe prior
        rLow.trace.map { it.code } shouldBe listOf("sse.no_update")
        val rHigh = run(listOf(high))
        rHigh.states.single().lbApplied shouldBe true
        (rHigh.next.getValue(key).trend.l > predicted.l) shouldBe true
    }

    @Test
    fun `downweighted sets count less, excluded sets not at all`() {
        val normal = run(listOf(set("n", "2026-10-08T18:00:00Z", 93.2, 5, 0.0)))
        val tired = run(listOf(set("t", "2026-10-08T18:00:00Z", 93.2, 5, 0.0, reasonCode = "feeling_bad")))
        val pain = run(listOf(set("p", "2026-10-08T18:00:00Z", 93.2, 5, 0.0, reasonCode = "pain")))
        val moveN = normal.next.getValue(key).trend.l - prior.trend.l
        val moveT = tired.next.getValue(key).trend.l - prior.trend.l
        (kotlin.math.abs(moveT) < kotlin.math.abs(moveN)) shouldBe true
        tired.sets.single().estimatorClass shouldBe "downweight"
        pain.sets shouldBe emptyList()
        pain.trace.single().code shouldBe "sse.skip.excluded"
    }

    @Test
    fun `cold start from the first normal set, then the rest update it`() {
        val r = run(sessionA(), priors = emptyMap())
        val st = r.next.getValue(key)
        st.trend.nObs shouldBe 4
        r.sets.first().residual.shouldBeNull()
        r.states.single().lPrior.shouldBeNull()
        r.trace.map { it.code } shouldBe listOf("sse.cold_start.first_set")
        (Det.exp(st.trend.l) in 112.0..115.0) shouldBe true
    }

    @Test
    fun `transfer prior starts the state with its own variance`() {
        val prepared = Sse.prepare(sessionA(), FACTS, RirCal.initial(params), params)
        val r = Sse.update(prepared, emptyMap(), mapOf(WPU.exerciseId to 30.0), mapOf(WPU.exerciseId to 105.0), FACTS,
            Instant.parse("2026-10-08T17:45:00Z"), 70.7, params)
        r.states.single().pLLPrior shouldBe (0.0225 plusOrMinus 1e-12)
        r.trace.map { it.code } shouldBe listOf("sse.cold_start.transfer")
    }
}
