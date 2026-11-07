package dev.fitnessapp.algo.prescription.warmup

import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.load.PlateCount
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** D106: the warm-up grid and the warm-up count of WARMUP-1. */
class WarmupGridTest {
    private val params = TestParams.defaults()
    private val squat = ExerciseLoadAttrs(0.0, null, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
    private val backExt = ExerciseLoadAttrs(0.6, 0.0, Laterality.BILATERAL, listOf(LoadMode.BODYWEIGHT, LoadMode.BODYWEIGHT_PLUS_EXTERNAL))

    private fun barbell(): Pair<List<Double>, List<Double>> {
        val pts = Loadables.enumerate(LoadScheme.defaultBarbell(params), Loadables.Context(squat, null), params)
        return pts.mapNotNull { it.wSys } to WarmupGrid.options(LoadScheme.defaultBarbell(params), pts, params)!!
    }

    @Test
    fun `a barbell's warm-up grid is 10 kg totals from the empty bar`() {
        val (_, grid) = barbell()
        grid.take(5) shouldBe listOf(20.0, 30.0, 40.0, 50.0, 60.0)
    }

    @Test
    fun `bench press at 70 kg warms up at 30 and 50 instead of 35 and 52_5`() {
        val (all, grid) = barbell()
        Warmup.plan(WarmupInput(70.0, 90.0, 30.0, false, all), params).sets.map { it.wSys } shouldBe listOf(35.0, 52.5)
        Warmup.plan(WarmupInput(70.0, 90.0, 30.0, false, all, gridLoadables = grid), params).sets.map { it.wSys } shouldBe listOf(30.0, 50.0)
    }

    @Test
    fun `the leg press sled keeps its own mass outside the grid`() {
        val sled = LoadScheme.Plates(barKg = null, plates = listOf(PlateCount(20.0, 20), PlateCount(10.0, 20), PlateCount(5.0, 20), PlateCount(2.5, 20), PlateCount(1.25, 20)), baseKg = 75.7)
        val pts = Loadables.enumerate(sled, Loadables.Context(squat, null), params)
        val grid = WarmupGrid.options(sled, pts, params)!!
        val plan = Warmup.plan(WarmupInput(218.2, 268.5, 30.0, false, pts.mapNotNull { it.wSys }, gridLoadables = grid), params)
        plan.sets.map { it.wSys } shouldBe listOf(95.7, 135.7, 175.7)
    }

    @Test
    fun `held plates below the first 5 kg step warm up at bodyweight, never at +1_25`() {
        val held = LoadScheme.Belt(listOf(PlateCount(20.0, 2), PlateCount(10.0, 2), PlateCount(5.0, 2), PlateCount(2.5, 2), PlateCount(1.25, 2)), 40.0, 2, false)
        val pts = Loadables.enumerate(held, Loadables.Context(backExt, 70.0), params)
        val grid = WarmupGrid.options(held, pts, params)!!
        val bw = 42.0
        // Mid tier: 0.5 x 62 = 31 and 0.75 x 62 = 46.5, both below BW + 5 -> bodyweight (not BW + 2.5 or + 3.75).
        Warmup.plan(WarmupInput(62.0, 80.0, 30.0, false, pts.mapNotNull { it.wSys }, bodyweightW = bw), params).sets.map { it.wSys } shouldBe listOf(42.0, 45.75)
        val plan = Warmup.plan(WarmupInput(62.0, 80.0, 30.0, false, pts.mapNotNull { it.wSys }, bodyweightW = bw, gridLoadables = grid), params)
        plan.sets.map { it.wSys to it.bodyweight } shouldBe listOf(42.0 to true)
    }

    @Test
    fun `dumbbells and stacks have no grid`() {
        WarmupGrid.options(LoadScheme.Dumbbells("kg", listOf(10.0, 12.0)), emptyList(), params) shouldBe null
    }

    @Test
    fun `a warm-up count keeps the middle of the ramp`() {
        val loads = (8..80).map { it * 2.5 }
        fun fracs(max: Int?, test: Boolean = false) =
            Warmup.plan(WarmupInput(200.0, 230.0, 30.0, false, loads, maxSets = max, testSession = test), params).sets.map { it.frac }
        fracs(null) shouldBe listOf(0.45, 0.65, 0.85)
        fracs(1) shouldBe listOf(0.65)
        fracs(2) shouldBe listOf(0.65, 0.85)
        fracs(0) shouldBe emptyList()
        fracs(1, test = true).size shouldBe 4 // a test session keeps its whole ramp and the single
        // 2 -> 1 keeps the heavier set (mid tier)
        Warmup.plan(WarmupInput(140.0, 200.0, 30.0, false, loads, maxSets = 1), params).sets.map { it.frac } shouldBe listOf(0.75)
    }

    @Test
    fun `the last kept set rests toward the working set`() {
        val loads = (8..80).map { it * 2.5 }
        val sets = Warmup.plan(WarmupInput(200.0, 230.0, 30.0, false, loads, maxSets = 1), params).sets
        sets.single().restS shouldBe params.double("warmup.rest_to_work_s")
    }
}
