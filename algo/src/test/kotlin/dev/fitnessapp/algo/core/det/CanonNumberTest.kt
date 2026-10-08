package dev.fitnessapp.algo.core.det

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** ALGORITHMS.md A3.4 and the RFC 8785 Appendix B vectors that are representable on the grids. */
class CanonNumberTest {
    private fun es(text: String) = Canon.esDecimal(BigDecimal(text))

    @Test
    fun `A3_4 examples`() {
        Canon.esNumber(45.24, GridKind.KG) shouldBe "45.24"
        Canon.esNumber(100.0, GridKind.KG) shouldBe "100"
        Canon.esNumber(2.5e-5, GridKind.STAT) shouldBe "0.000025"
        Canon.esNumber(2.5e-7, GridKind.STAT) shouldBe "2.5e-7"
        Canon.esNumber(1.2e21, GridKind.STAT) shouldBe "1.2e+21"
    }

    @Test
    fun `RFC 8785 appendix B vectors on the grids`() {
        Canon.esNumber(0.0, GridKind.KG) shouldBe "0"
        Canon.esNumber(-0.0, GridKind.KG) shouldBe "0"
        es("1e23") shouldBe "1e+23"
        es("1e21") shouldBe "1e+21"
        es("1e20") shouldBe "100000000000000000000"
        es("0.000001") shouldBe "0.000001"
        es("1e-7") shouldBe "1e-7"
        es("-1e-7") shouldBe "-1e-7"
        es("4.50") shouldBe "4.5"
        es("2e-3") shouldBe "0.002"
        es("1E30") shouldBe "1e+30"
        es("0.000000000000000000000000001") shouldBe "1e-27"
    }

    @Test
    fun `integers and negatives`() {
        Canon.esInt(1791151200000) shouldBe "1791151200000"
        Canon.esInt(-120) shouldBe "-120"
        Canon.esNumber(-27.5, GridKind.KG) shouldBe "-27.5"
        Canon.esNumber(65.25, GridKind.RATIO) shouldBe "65.25"
    }

    @Test
    fun `more than 15 significant digits is rejected`() {
        assertThrows<IllegalArgumentException> { es("9007199254740993") }
    }
}
