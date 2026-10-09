package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B2.5: every direction on the system-load scale; ties go to the easier option (C2). */
class RoundingDirectionTest {
    private val params = TestParams.defaults()
    private val squat = ExerciseLoadAttrs(0.0, null, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
    private val bar = Loadables.enumerate(LoadScheme.defaultBarbell(params), Loadables.Context(squat, null), params)
    private val pull = ExerciseLoadAttrs(1.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.ASSISTED_MACHINE))
    private val machine = LoadScheme.PinAssist("lb", 5.0, 0.0, 100.0, 6.5, 1.0)
    private val pins = Loadables.enumerate(machine, Loadables.Context(pull, 70.7), params)

    @Test
    fun `down, up and nearest on a barbell`() {
        Rounding.easierOrEqual(bar, 61.0, params).addedKg shouldBe 60.0
        Rounding.harderOrEqual(bar, 61.0, params).addedKg shouldBe 62.5
        Rounding.nearest(bar, 61.25, params).addedKg shouldBe 60.0 // exact tie -> easier
        Rounding.nearest(bar, 61.3, params).addedKg shouldBe 62.5
        Rounding.easierOrEqual(bar, 5.0, params).addedKg shouldBe 20.0 // none below -> easiest
        Rounding.harderOrEqual(bar, 999.0, params).addedKg shouldBe 1000.0
        Rounding.harderOrEqual(bar, 9999.0, params).addedKg shouldBe 1595.0 // none above -> hardest
        Rounding.nextHarder(bar, 60.0, params)!!.addedKg shouldBe 62.5
        Rounding.nextEasier(bar, 60.0, params)!!.addedKg shouldBe 57.5
        Rounding.nextEasier(bar, 20.0, params) shouldBe null
    }

    @Test
    fun `a typed 37_5 lb pin snaps to 40 lb (more assistance)`() {
        val typed = LoadModel.scaleOfNative(machine, 37.5, 1, params)!!
        Rounding.nearest(pins, typed, params).assistPinNative shouldBe 40.0
        val (lo, hi) = Rounding.bracket(pins, typed, params)
        lo!!.assistPinNative shouldBe 40.0
        hi!!.assistPinNative shouldBe 35.0
    }

    @Test
    fun `w_sys targets work through the key selector`() {
        val p = Rounding.easierOrEqual(pins, 47.0, params) { it.wSys!! }
        p.assistPinNative shouldBe 40.0 // 46.06 <= 47 < 48.32
    }
}
