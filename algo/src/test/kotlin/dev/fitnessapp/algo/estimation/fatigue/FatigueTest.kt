package dev.fitnessapp.algo.estimation.fatigue

import dev.fitnessapp.algo.estimation.EstimationFixtures.LEG_PRESS
import dev.fitnessapp.algo.estimation.EstimationFixtures.WPU
import dev.fitnessapp.algo.estimation.rcm.RepCurve
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B7 examples: the `c` table and Nuzzo check (GF-FAT-01), 4 x 5 at +20 (GF-FAT-02), carry-over, the nightly fit. */
class FatigueTest {
    private val params = TestParams.defaults()
    private val k = Fatigue.constants(params)

    @Test
    fun `c of rest reproduces the B7_1 table`() {
        val rests = listOf(60.0, 90.0, 120.0, 150.0, 180.0, 240.0, 300.0)
        val table = listOf(0.455, 0.357, 0.300, 0.262, 0.235, 0.198, 0.173)
        rests.zip(table).forEach { (r, x) -> Fatigue.c(r, k) shouldBe (x plusOrMinus 0.0005) }
    }

    @Test
    fun `to-failure sets at 120 s follow Nuzzo's decline`() {
        val caps = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        val fs = Fatigue.observed(caps, caps, List(5) { 120.0 }, 0.0, k)
        val rel = fs.map { 1.0 - it }
        listOf(1.00, 0.70, 0.565, 0.504, 0.477, 0.465).forEachIndexed { i, x -> rel[i] shouldBe (x plusOrMinus 0.0006) }
    }

    @Test
    fun `GF-FAT-02 four by five at plus 20 kg`() {
        val capFresh = RepCurve.cap(90.70, 109.94, 30.63)
        capFresh shouldBe (7.50 plusOrMinus 0.005)
        val reps = List(4) { 5.0 }
        val fs = Fatigue.planned(capFresh, reps, List(3) { 150.0 }, 0.0, k)
        listOf(0.0, 0.117, 0.220, 0.314).forEachIndexed { i, x -> fs[i] shouldBe (x plusOrMinus 0.0006) }
        val rir = fs.map { capFresh * (1.0 - it) - 5.0 }
        listOf(2.50, 1.62, 0.85, 0.14).forEachIndexed { i, x -> rir[i] shouldBe (x plusOrMinus 0.006) }
        // Live readout after set 1: reps at RIR 2 after 60 / 120 / 150 / 180 s.
        listOf(60.0 to 3.98, 120.0 to 4.50, 150.0 to 4.62, 180.0 to 4.71).forEach { (t, x) ->
            (Fatigue.predictNext(capFresh, 0.0, 5.0, capFresh, t, k) - 2.0) shouldBe (x plusOrMinus 0.006)
        }
    }

    @Test
    fun `carry-over counts overlapping work, halves side rows and is capped`() {
        // Leg press shares no group with the pull-up at the secondary level: no carry-over.
        Fatigue.carryOver(WPU, listOf(CarrySet(LEG_PRESS, false, 10.0, 10.0)), params) shouldBe 0.0
        val pulls = List(4) { CarrySet(WPU, false, 5.0, 5.0) }
        Fatigue.carryOver(WPU, pulls, params) shouldBe (0.16 plusOrMinus 1e-12)
        Fatigue.carryOver(WPU, pulls.map { it.copy(sideRow = true) }, params) shouldBe (0.08 plusOrMinus 1e-12)
        Fatigue.carryOver(WPU, List(20) { CarrySet(WPU, false, 5.0, 5.0) }, params) shouldBe 0.25
    }

    @Test
    fun `nightly fit shrinks toward the default and fits d only from 100 sets`() {
        val k = Fatigue.constants(params, cRef = 0.40)
        // Self-consistent data under c_ref 0.40: n_eff_j = n_fresh_1 (1 - F_j) with F from the observed n_eff.
        val inst = (0 until 10).map {
            val nEff = arrayListOf(10.0)
            var f = 0.0
            repeat(2) { j ->
                f = Fatigue.step(f, 8.0, nEff[j], 120.0, k)
                nEff += 10.0 * (1.0 - f)
            }
            FitInstance(0.0, nEff.map { FitSet(8.0, it, 2.0, 120.0) })
        }
        val fit = Fatigue.fit(inst, params)
        fit.m shouldBe 20
        fit.cHat!! shouldBe (0.40 plusOrMinus 0.021)
        fit.cUsed shouldBe ((20 * fit.cHat!! + 30 * 0.30) / 50 plusOrMinus 0.0001)
        fit.dHat.shouldBeNull()
        fit.dUsed shouldBe 0.75
        fit.residBySetIndex.keys.toList() shouldBe listOf("s2", "s3")
    }
}
