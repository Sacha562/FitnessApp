package dev.fitnessapp.algo.estimation.rcm

import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.EstimationFixtures.FACTS
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.PreparedSet
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** B5: the curve functions, the two-point closed form (GF-RCM-01) and the windowed fit (GF-RCM-02 to 05). */
class RcmTest {
    private val params = TestParams.defaults()
    private val z80 = params.double("sse.ci_z")

    /** Each maximal set in its own session, so no within-session fatigue enters. */
    private fun prepared(vararg sets: LoggedSet): List<PreparedSet> =
        sets.flatMap { Sse.prepare(listOf(it), FACTS, RirCal.initial(params), params) }

    private fun maxSet(id: String, date: String, w: Double, reps: Int) =
        set(id, "${date}T10:00:00Z", w, reps, 0.0, setKind = "test", session = id)

    @Test
    fun `curve functions are inverse and reproduce the RPE chart`() {
        RepCurve.cap(RepCurve.load(7.0, 110.0, 30.0), 110.0, 30.0) shouldBe (7.0 plusOrMinus 1e-12)
        RepCurve.pctPerRep(3.0, 30.0) shouldBe (0.03125 plusOrMinus 1e-12)
        RepCurve.pctPerRep(10.0, 30.0) shouldBe (0.0256 plusOrMinus 0.0001)
    }

    @Test
    fun `GF-RCM-01 two-point closed form`() {
        val k = RepCurve.twoPointK(70.7, 18.0, 103.2, 3.0)
        k shouldBe (30.63 plusOrMinus 0.005)
        val m = RepCurve.e1rm(103.2, 3.0, 0.0, k)
        m shouldBe (109.94 plusOrMinus 0.005)
        (m - 70.7) shouldBe (39.24 plusOrMinus 0.005)
        RepCurve.twoPointK(70.7, 18.0, 103.2 / 1.02, 3.0) shouldBe (32.80 plusOrMinus 0.005)
    }

    @Test
    fun `GF-RCM-02 the user's pair forced into one window`() {
        val sets = prepared(maxSet("bw18", "2026-09-19", 70.7, 18), maxSet("rm3", "2026-09-20", 103.2, 3))
        val fit = Rcm.fit(sets, LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, null, null, params)
        fit.m shouldBe 1
        fit.kHat!! shouldBe (30.06 plusOrMinus 0.005)
        fit.state.sdLnk shouldBe (0.198 plusOrMinus 0.0005)
        fit.state.kUsed shouldBe (30.02 plusOrMinus 0.005)
        fit.rateLimited shouldBe false
        val (lo, hi) = RepCurve.e1rmInterval(103.2, 3.0, 0.0, fit.state.kUsed, fit.state.sdLnk, z80)
        (RepCurve.e1rm(103.2, 3.0, 0.0, fit.state.kUsed) - 70.7) shouldBe (39.38 plusOrMinus 0.005)
        (lo - 70.7) shouldBe (37.84 plusOrMinus 0.005)
        (hi - 70.7) shouldBe (41.36 plusOrMinus 0.005)
    }

    @Test
    fun `GF-RCM-03 the 30 August test window alone`() {
        RepCurve.twoPointK(71.0, 18.0, 97.0, 3.0) shouldBe (38.96 plusOrMinus 0.005)
        val sets = prepared(maxSet("bw18", "2026-08-30", 71.0, 18), maxSet("rm3", "2026-08-30", 97.0, 3))
        val fit = Rcm.fit(sets, LocalDate.parse("2026-08-30"), CurveClass.UPPER_COMPOUND, null, null, params)
        fit.kHat!! shouldBe (34.14 plusOrMinus 0.005)
        fit.state.sdLnk shouldBe (0.202 plusOrMinus 0.0005)
        fit.state.kUsed shouldBe (31.32 plusOrMinus 0.005)
        val (lo, hi) = RepCurve.e1rmInterval(103.2, 3.0, 0.0, fit.state.kUsed, fit.state.sdLnk, z80)
        (RepCurve.e1rm(103.2, 3.0, 0.0, fit.state.kUsed) - 70.7) shouldBe (39.09 plusOrMinus 0.005)
        (lo - 70.7) shouldBe (37.58 plusOrMinus 0.005)
        (hi - 70.7) shouldBe (41.04 plusOrMinus 0.005)
    }

    @Test
    fun `GF-RCM-04 sets 20 days apart sit in different windows`() {
        val sets = prepared(maxSet("bw18", "2026-08-31", 70.7, 18), maxSet("rm3", "2026-09-20", 103.2, 3))
        val fit = Rcm.fit(sets, LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, null, null, params)
        fit.m shouldBe 0
        fit.kHat.shouldBeNull()
        fit.state.kUsed shouldBe 30.0
        fit.sets.map { it.window } shouldBe listOf(1, 0)
        Rcm.fit(sets, LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, null, 31.5, params).state.kUsed shouldBe 31.5
    }

    @Test
    fun `GF-RCM-05 cutover state is the class prior`() {
        val s = Rcm.initial(CurveClass.UPPER_COMPOUND, params)
        s shouldBe RcmState(30.0, 0.30)
        val (lo, hi) = RepCurve.e1rmInterval(103.2, 3.0, 0.0, s.kUsed, s.sdLnk, z80)
        (RepCurve.e1rm(103.2, 3.0, 0.0, s.kUsed) - 70.7) shouldBe (39.38 plusOrMinus 0.005)
        (lo - 70.7) shouldBe (37.18 plusOrMinus 0.005)
        (hi - 70.7) shouldBe (42.61 plusOrMinus 0.005)
    }

    @Test
    fun `rate limit holds a big jump to 10 percent`() {
        val sets = prepared(maxSet("a", "2026-09-19", 70.7, 18), maxSet("b", "2026-09-20", 103.2, 3))
        val fit = Rcm.fit(sets, LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, RcmState(40.0, 0.2), null, params)
        fit.kPrev shouldBe 40.0
        fit.state.kUsed shouldBe 36.0
        fit.rateLimited shouldBe true
    }

    @Test
    fun `sets beyond the lookback, above 20 reps or far from failure are not data`() {
        val sets = prepared(
            maxSet("old", "2026-07-01", 70.7, 18),
            set("far", "2026-09-19T10:00:00Z", 80.0, 5, 5.0, session = "far"),
            maxSet("many", "2026-09-19", 60.0, 21),
            maxSet("ok", "2026-09-20", 103.2, 3),
        )
        Rcm.fit(sets, LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, null, null, params).sets.map { it.setId } shouldBe listOf("ok")
    }

    @Test
    fun `pooled class value comes from the other exercises' windows`() {
        val other = prepared(
            set("p1", "2026-09-19T10:00:00Z", 70.7, 18, 0.0, setKind = "test", session = "p1", exercise = "leg_press_45"),
            set("p2", "2026-09-20T10:00:00Z", 103.2, 3, 0.0, setKind = "test", session = "p2", exercise = "leg_press_45"),
        )
        val pooled = Rcm.pooledClassK(mapOf("leg_press_45" to other), LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, params)
        pooled!! shouldBe (30.02 plusOrMinus 0.005)
        Rcm.pooledClassK(emptyMap(), LocalDate.parse("2026-09-20"), CurveClass.UPPER_COMPOUND, params).shouldBeNull()
    }
}
