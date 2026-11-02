package dev.fitnessapp.algo.core.det

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `Grid.round`'s double fast path returns exactly the exact BigDecimal path's result (ALGORITHMS.md A3.3). */
class GridFastPathTest {
    private val kinds = listOf(GridKind.KG, GridKind.S, GridKind.RATIO, GridKind.REP, GridKind.STAT)

    private fun check(x: Double) {
        for (k in kinds) {
            val exact = Grid.decimal(x, k).toDouble()
            val got = Grid.round(x, k)
            withClue(x, k) { got.toRawBits() shouldBe exact.toRawBits() }
        }
    }

    private fun withClue(x: Double, k: GridKind, f: () -> Unit) = io.kotest.assertions.withClue("x=$x kind=$k") { f() }

    @Test
    fun `random values at every magnitude`() {
        val r = java.util.Random(20261102)
        repeat(200_000) {
            val mag = r.nextInt(14) - 7
            val x = (r.nextDouble() * 2.0 - 1.0) * StrictMath.pow(10.0, mag.toDouble())
            check(x)
        }
    }

    @Test
    fun `halves, grid points and their neighbours`() {
        val r = java.util.Random(7)
        repeat(50_000) {
            val k = r.nextInt(2_000_000) - 1_000_000
            for (scale in listOf(1e1, 1e2, 1e4)) {
                for (base in listOf(k / scale, (k + 0.5) / scale)) {
                    check(base)
                    check(Math.nextUp(base))
                    check(Math.nextDown(base))
                }
            }
        }
        listOf(0.0, -0.0, 0.00005, -0.00005, 0.25, 2.5, 1e6, -1e6, 999999.99995, 4.2049999999999).forEach { check(it) }
    }
}
