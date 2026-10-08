package dev.fitnessapp.algo.core.det

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** ALGORITHMS.md A3.3 and A11. */
class GridRoundingTest {
    @Test
    fun `45_245 rounds down because its double is below the tie`() {
        Grid.kg(45.245) shouldBe 45.24
    }

    @Test
    fun `half even on exact ties`() {
        Grid.kg(0.125) shouldBe 0.12
        Grid.kg(0.375) shouldBe 0.38
        Grid.s(2.25) shouldBe 2.2
    }

    @Test
    fun `stat grid keeps small values`() {
        Grid.stat(2.5e-5) shouldBe 2.5e-5
        Grid.stat(2.5e-7) shouldBe 2.5e-7
        Grid.ratio(2.5e-5) shouldBe 0.0
        Grid.stat(1.0 / 3.0) shouldBe 0.333333333333
    }

    @Test
    fun `other grids`() {
        Grid.ratio(30.63333) shouldBe 30.6333
        Grid.rep(4.567) shouldBe 4.57
        Grid.s(179.96) shouldBe 180.0
    }

    @Test
    fun `non-finite values are rejected`() {
        assertThrows<IllegalArgumentException> { Grid.kg(Double.NaN) }
        assertThrows<IllegalArgumentException> { Grid.stat(Double.POSITIVE_INFINITY) }
    }
}
