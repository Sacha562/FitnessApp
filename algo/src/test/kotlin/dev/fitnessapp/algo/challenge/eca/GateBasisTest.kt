package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.enrollment.TestRecord
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.TreeMap

/** GF-CE-37: a criterion is met on the test-day bodyweight and stays met; the forward threshold follows BW(t). */
class GateBasisTest {
    @Test
    fun `GF-CE-37 test-day basis and forward threshold`() {
        val metrics = TreeMap(
            mapOf(
                "bw" to OapFixture.mass("70.7"), "wpu_e1rm_added" to OapFixture.mass("44.00"),
                "oa_hang_s" to SideV(OapFixture.dur("15"), OapFixture.dur("20")),
                "oa_top_hold_s" to SideV(OapFixture.dur("8"), OapFixture.dur("9")),
                "oa_neg5_unassisted" to SideV(BoolV.TRUE, BoolV.FALSE),
            ),
        )
        val s = OapFixture.start().copy(lastValidTest = TestRecord(LocalDate.parse("2026-10-11"), null, metrics, sortedMapOf()))
        val later = OapFixture.facts.copy(bwKg = BigDecimal("71.5"))
        val c = GateCriteria.criteria(OapFixture.def, OapFixture.params(), s, later)
        c.map { it.met } shouldBe listOf(true, false, false, true)
        GateCriteria.thresholdAt(OapFixture.def, OapFixture.params(), s, later, 1, 0, BigDecimal("71.5")) shouldBe BigDecimal("44.48")
        GateCriteria.thresholdAt(OapFixture.def, OapFixture.params(), s, later, 1, 0, BigDecimal("70.7")) shouldBe BigDecimal("43.96")
        GateCriteria.thresholdAt(OapFixture.def, OapFixture.params(), s, later, 1, 1, BigDecimal("71.5")) shouldBe null
    }
}
