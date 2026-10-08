package dev.fitnessapp.algo.core.load

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B2.2 identity and the C2 numbers of B2.3 (BW 70.7, offset 6.5 kg, ratio 1.0). */
class LoadModelTest {
    private val params = TestParams.defaults()
    private val machine = LoadScheme.PinAssist(unit = "lb", step = 5.0, min = 0.0, max = 100.0, offsetKg = 6.5, ratio = 1.0)

    @Test
    fun `assist kg and one-arm load at BW 70_7`() {
        val expected = listOf(
            50.0 to (29.18 to 41.52), 45.0 to (26.91 to 43.79), 40.0 to (24.64 to 46.06),
            35.0 to (22.38 to 48.32), 30.0 to (20.11 to 50.59), 0.0 to (6.50 to 64.20),
        )
        for ((pin, pair) in expected) {
            LoadModel.assistKg(pin, machine, params) shouldBe pair.first
            LoadModel.oneArmLoad(1.0, 70.7, pin, machine, params) shouldBe pair.second
        }
    }

    @Test
    fun `system load identity and undefined cases`() {
        LoadModel.systemLoad(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 1.0, 70.7, 0.0, false, 27.5, 0.0) shouldBe 98.2
        LoadModel.systemLoad(LoadMode.EXTERNAL, 0.64, 70.7, 0.0, false, 0.0, 0.0) shouldBe 45.25 // 45.248
        LoadModel.systemLoad(LoadMode.BODYWEIGHT, 1.0, null, 0.0, false, 0.0, 0.0) shouldBe null
        LoadModel.systemLoad(LoadMode.EXTERNAL, 0.0, null, null, true, 40.0, 0.0) shouldBe null
        LoadModel.systemLoad(LoadMode.EXTERNAL, 0.0, null, null, false, 40.0, 0.0) shouldBe 40.0
        LoadModel.systemLoad(LoadMode.ASSISTED_ORDINAL, 1.0, 70.7, 0.0, false, 0.0, 0.0) shouldBe null
    }

    @Test
    fun `one scale across modes (B2_7)`() {
        LoadModel.oneArmLoad(1.0, 70.7, 20.0, machine, params) shouldBe 55.13
        LoadModel.oneArmLoad(1.0, 70.7, 0.0, machine, params) shouldBe 64.2
    }
}
