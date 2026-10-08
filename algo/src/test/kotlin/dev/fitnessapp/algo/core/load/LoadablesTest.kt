package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B2.4 enumeration per scheme type. */
class LoadablesTest {
    private val params = TestParams.defaults()
    private val pull = ExerciseLoadAttrs(1.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.ASSISTED_MACHINE, LoadMode.BODYWEIGHT, LoadMode.BODYWEIGHT_PLUS_EXTERNAL))
    private val squat = ExerciseLoadAttrs(0.0, null, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
    private val ctx = Loadables.Context(pull, bwKg = 70.7)

    @Test
    fun `default barbell covers 20 to 335 kg in 2_5 kg steps`() {
        val w = Loadables.enumerate(LoadScheme.defaultBarbell(params), Loadables.Context(squat, null), params)
        w.size shouldBe 127
        w.first().wSys shouldBe 20.0
        w.last().wSys shouldBe 335.0
        w.first { it.addedKg == 60.0 }.plateBreakdown shouldBe listOf(20.0)
        w.first { it.addedKg == 62.5 }.plateBreakdown shouldBe listOf(20.0, 1.25)
        w.zipWithNext().all { (a, b) -> b.addedKg - a.addedKg == 2.5 } shouldBe true
    }

    @Test
    fun `smith machine needs its bar mass`() {
        val smith = LoadScheme.Plates(barKg = null, plates = listOf(PlateCount(10.0, 2)))
        val unknown = Loadables.enumerate(smith, Loadables.Context(squat, null), params)
        unknown.map { it.wSys } shouldBe listOf(null, null)
        val known = Loadables.enumerate(smith, Loadables.Context(squat.copy(implementBaseKg = 15.0), null), params)
        known.map { it.wSys } shouldBe listOf(15.0, 35.0)
    }

    @Test
    fun `belt multisets within max items and max kg, empty belt excluded`() {
        val belt = LoadScheme.Belt(
            listOf(PlateCount(20.0, 2), PlateCount(10.0, 1), PlateCount(5.0, 1), PlateCount(2.5, 1), PlateCount(1.25, 2)),
            maxKg = 60.0, maxItems = 4, dumbbellsOnBelt = false,
        )
        val w = Loadables.enumerate(belt, ctx, params)
        w.first().addedKg shouldBe 1.25
        w.last().addedKg shouldBe 55.0 // 20 + 20 + 10 + 5 with at most four items
        w.first { it.addedKg == 27.5 }.wSys shouldBe 98.2
        w.all { it.mode == LoadMode.BODYWEIGHT_PLUS_EXTERNAL } shouldBe true
    }

    @Test
    fun `belt with a dumbbell uses one item for the dumbbell`() {
        val belt = LoadScheme.Belt(listOf(PlateCount(20.0, 1)), maxKg = 60.0, maxItems = 2, dumbbellsOnBelt = true)
        val w = Loadables.enumerate(belt, ctx.copy(beltDumbbellsKg = listOf(12.5)), params)
        w.map { it.addedKg } shouldBe listOf(12.5, 20.0, 32.5)
        w.last().plateBreakdown shouldBe listOf(20.0, 12.5)
    }

    @Test
    fun `dumbbells are per hand and doubled for bilateral lifts`() {
        val db = LoadScheme.Dumbbells("kg", listOf(10.0, 12.5))
        val bench = ExerciseLoadAttrs(0.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
        Loadables.enumerate(db, Loadables.Context(bench, null), params).map { it.wSys } shouldBe listOf(20.0, 25.0)
        val row = bench.copy(laterality = Laterality.UNILATERAL)
        val w = Loadables.enumerate(db, Loadables.Context(row, null), params)
        w.map { it.wSys } shouldBe listOf(10.0, 12.5)
        w.map { it.implementCount } shouldBe listOf(1, 1)
        w.map { it.nativeValue } shouldBe listOf(10.0, 12.5)
    }

    @Test
    fun `lb dumbbells keep added equal to count x per-hand kg`() {
        val db = LoadScheme.Dumbbells("lb", listOf(25.0, 35.0))
        val bench = ExerciseLoadAttrs(0.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
        val w = Loadables.enumerate(db, Loadables.Context(bench, null), params)
        w.map { it.perImplementKg } shouldBe listOf(11.34, 15.88)
        w.map { it.addedKg } shouldBe listOf(22.68, 31.76) // not Grid.kg(2 x 35 x 0.4536) = 31.75
    }

    @Test
    fun `lb stack with one add-on per set`() {
        val stack = LoadScheme.Stack("lb", 10.0, 10.0, 100.0, listOf(5.0), 1.0, null)
        val w = Loadables.enumerate(stack, Loadables.Context(squat.copy(implementBaseKg = 0.0), null), params)
        w.size shouldBe 20
        w.first { it.nativeValue == 50.0 }.wSys shouldBe 22.68
        w.map { it.nativeValue } shouldBe (1..10).flatMap { listOf(it * 10.0, it * 10.0 + 5.0) }
    }

    @Test
    fun `level stack without level_kg is ordinal`() {
        val stack = LoadScheme.Stack("level", 1.0, 1.0, 12.0, emptyList(), 1.0, null)
        val w = Loadables.enumerate(stack, Loadables.Context(squat.copy(implementBaseKg = 0.0), null), params)
        w.size shouldBe 12
        w.all { it.ordinal && it.wSys == null } shouldBe true
        w.map { it.nativeValue } shouldBe (1..12).map { it.toDouble() }
    }

    @Test
    fun `pins are ordered easier first and unknown max uses the default`() {
        val machine = LoadScheme.PinAssist("lb", 5.0, 0.0, null, 6.5, 1.0)
        val w = Loadables.enumerate(machine, ctx, params)
        w.size shouldBe 21 // 0..100 lb
        w.first().assistPinNative shouldBe 100.0 // most assistance = easiest
        w.last().assistPinNative shouldBe 0.0
        w.last().wSys shouldBe 64.2
    }

    @Test
    fun `assisted, bodyweight and belt merge onto one scale`() {
        val pins = Loadables.enumerate(LoadScheme.PinAssist("lb", 5.0, 0.0, 20.0, 6.5, 1.0), ctx, params)
        val bw = Loadables.enumerate(LoadScheme.Bodyweight, ctx, params)
        val belt = Loadables.enumerate(LoadScheme.Belt(listOf(PlateCount(5.0, 1)), 60.0, 4, false), ctx, params)
        Loadables.merge(listOf(belt, bw, pins), params).map { it.wSys } shouldBe
            listOf(55.13, 57.4, 59.66, 61.93, 64.2, 70.7, 75.7)
    }
}
