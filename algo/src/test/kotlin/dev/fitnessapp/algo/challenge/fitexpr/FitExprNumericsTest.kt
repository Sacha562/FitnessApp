package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.eval
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.evalError
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** GF-CE-01 .. 06, 06b, 09 and the section 3.13 worked examples. */
class FitExprNumericsTest {
    private fun mass(src: String): BigDecimal {
        val v = eval(src) as QV
        v.dim shouldBe Dim.MASS
        return v.v
    }

    private fun kg(x: BigDecimal): String = x.setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    @Test
    fun `GF-CE-01 e1rmTotal is exact decimal arithmetic`() {
        val v = mass("e1rmTotal(70.7kg + 32.5kg, 3, 30) - 70.7kg")
        v.compareTo(BigDecimal("39.38")) shouldBe 0
        mass("e1rmTotal(103.2kg, 3, 30)").compareTo(BigDecimal("110.08")) shouldBe 0
    }

    @Test
    fun `GF-CE-02 userKQuantile and the 80 percent interval`() {
        eval("userKQuantile('weighted_pullup', 0.1)") shouldBe QV(Dim.NUM, BigDecimal("20.4244"))
        eval("userKQuantile('weighted_pullup', 0.9)") shouldBe QV(Dim.NUM, BigDecimal("44.0649"))
        kg(mass("e1rmTotal(t.bw + t.wpu_3rm_added, 3, userKQuantile('weighted_pullup', 0.1)) - t.bw")) shouldBe "42.61"
        kg(mass("e1rmTotal(t.bw + t.wpu_3rm_added, 3, userKQuantile('weighted_pullup', 0.9)) - t.bw")) shouldBe "37.18"
    }

    @Test
    fun `GF-CE-03 and 04 display value and gate threshold`() {
        kg(mass("(70.7kg + 32.5kg) * 1.10 - 70.7kg")) shouldBe "42.82"
        mass("0.65 * 70.7kg - 2.0kg").compareTo(BigDecimal("43.955")) shouldBe 0
        kg(mass("0.65 * 70.7kg - 2.0kg")) shouldBe "43.96"
        mass("p.thresholdRatioByGrip[p.grip] * t.bw - p.gate1GapKg").compareTo(BigDecimal("43.955")) shouldBe 0
    }

    @Test
    fun `GF-CE-05 assistance and one-arm load`() {
        mass("assistKg(40lb)").compareTo(BigDecimal("24.64")) shouldBe 0
        mass("oneArmLoad(40lb)").compareTo(BigDecimal("46.06")) shouldBe 0
        mass("assistKg(35lb)").compareTo(BigDecimal("22.38")) shouldBe 0
        mass("assistKg(50lb)").compareTo(BigDecimal("29.18")) shouldBe 0
    }

    @Test
    fun `GF-CE-06 and 06b rounding delegates to LOAD-1`() {
        mass("roundLoad(22.1667kg, 'belt', 'nearest')").compareTo(BigDecimal("22.5")) shouldBe 0
        mass("roundLoad(26.5kg, 'belt', 'nearest')").compareTo(BigDecimal("27.5")) shouldBe 0
        mass("roundLoad(48.5kg, 'belt', 'down')").compareTo(BigDecimal("47.5")) shouldBe 0
        mass("roundLoad(44.75kg, 'belt', 'down')").compareTo(BigDecimal("42.5")) shouldBe 0
        eval("roundPin(37.5, 'nearest')") shouldBe QV(Dim.PIN, BigDecimal("40"))
        mass("roundLoad(23.75kg, 'belt', 'nearest')").compareTo(BigDecimal("22.5")) shouldBe 0
        mass("roundLoad(0kg - 1kg, 'belt', 'down')").compareTo(BigDecimal.ZERO) shouldBe 0
        eval("roundPin(35.27, 'up')") shouldBe QV(Dim.PIN, BigDecimal("40"))
        eval("roundPin(35.27, 'down')") shouldBe QV(Dim.PIN, BigDecimal("35"))
        mass("roundLoad(22.17kg, 'belt', 'nearest')").compareTo(BigDecimal("22.5")) shouldBe 0
    }

    @Test
    fun `GF-CE-09 date arithmetic`() {
        eval("today - p.injuryDays + p.injuryExtendDays") shouldBe DateV(LocalDate.parse("2026-10-14"))
        eval("today - 14d + 7d") shouldBe DateV(LocalDate.parse("2026-10-14"))
        eval("today - (today - 3d)") shouldBe QV(Dim.DAYS, BigDecimal(3))
    }

    @Test
    fun `worked examples of section 3_13`() {
        eval("min(t.oa_hang_s) >= p.gate1HangS") shouldBe BoolV.FALSE
        eval("all(ex.wpu.reps >= ex.wpu.targetReps)") shouldBe BoolV.TRUE
        eval("s.pinA - 5lb") shouldBe QV(Dim.PIN, BigDecimal("35"))
        mass("kgPerRepAt(90.7kg, 5, 30)").setScale(2, RoundingMode.HALF_EVEN).toPlainString() shouldBe "2.67"
        mass("loadForReps(110.08kg, 3, 30)").compareTo(BigDecimal("103.2")) shouldBe 0
    }

    @Test
    fun `units and percent literals`() {
        eval("65%") shouldBe QV(Dim.NUM, BigDecimal("0.65"))
        eval("2min") shouldBe QV(Dim.DURATION, BigDecimal(120))
        eval("1h") shouldBe QV(Dim.DURATION, BigDecimal(3600))
        eval("2.50kg == 2.5kg") shouldBe BoolV.TRUE
        eval("7 / 2") shouldBe QV(Dim.NUM, BigDecimal("3.5"))
        eval("7 * 2") shouldBe QV(Dim.INT, BigDecimal(14))
    }

    @Test
    fun `rounding functions and modulo`() {
        eval("floor(7.9)") shouldBe QV(Dim.NUM, BigDecimal(7))
        eval("ceil(5 / 2)") shouldBe QV(Dim.NUM, BigDecimal(3))
        eval("round(2.5)") shouldBe QV(Dim.NUM, BigDecimal(2))
        eval("round(22.17kg, 2.5kg)") shouldBe QV(Dim.MASS, BigDecimal("22.5"))
        eval("mod(-7, 3)") shouldBe QV(Dim.INT, BigDecimal(2))
        evalError("mod(7, 0)").code shouldBe "div_zero"
        evalError("1 / 0").code shouldBe "div_zero"
        eval("clamp(12kg, 5kg, 10kg)") shouldBe QV(Dim.MASS, BigDecimal(10))
        eval("max(1, ceil(5 / 2))") shouldBe QV(Dim.NUM, BigDecimal(3))
    }

    @Test
    fun `domain errors of the curve functions`() {
        evalError("e1rmTotal(100kg, 3, 0)").code shouldBe "domain"
        evalError("e1rmTotal(100kg, 0, 30)").code shouldBe "domain"
    }
}
