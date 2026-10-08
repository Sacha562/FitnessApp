package dev.fitnessapp.algo.core.load

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B2.8 examples with the Part D fixture belt inventory (20 x 2, 10, 5, 2.5, 1.25 x 2). */
class PlateBreakdownTest {
    private val belt = listOf(PlateCount(20.0, 2), PlateCount(10.0, 1), PlateCount(5.0, 1), PlateCount(2.5, 1), PlateCount(1.25, 2))

    @Test
    fun `fixture belt examples`() {
        PlateBreakdown.solve(47.5, belt) shouldBe listOf(20.0, 20.0, 5.0, 2.5)
        PlateBreakdown.solve(27.5, belt) shouldBe listOf(20.0, 5.0, 2.5)
        PlateBreakdown.solve(22.5, belt) shouldBe listOf(20.0, 2.5)
        PlateBreakdown.solve(26.25, belt) shouldBe listOf(20.0, 5.0, 1.25)
    }

    @Test
    fun `fewest items, then big plates first`() {
        // 30 = 20 + 10 (2 items) beats 20 + 5 + 2.5 + 1.25 + 1.25 (5 items).
        PlateBreakdown.solve(30.0, belt) shouldBe listOf(20.0, 10.0)
        // 2.5 alone or 1.25 + 1.25: one item wins.
        PlateBreakdown.solve(2.5, belt) shouldBe listOf(2.5)
        // 3.75: 2.5 + 1.25 is the only two-item option.
        PlateBreakdown.solve(3.75, belt) shouldBe listOf(2.5, 1.25)
    }

    @Test
    fun `unloadable targets and item limits`() {
        PlateBreakdown.solve(0.5, belt) shouldBe null
        PlateBreakdown.solve(70.0, belt) shouldBe null // 20+20+10+5+2.5+1.25+1.25 = 60 max
        PlateBreakdown.solve(47.5, belt, maxItems = 3) shouldBe null
        PlateBreakdown.solve(0.0, belt) shouldBe emptyList()
    }
}
