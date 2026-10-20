package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.compile
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.compileError
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.eval
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.evalError
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport.limits
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Grammar, static checking (GF-CE-07, 08) and the null / missing semantics of section 3.5. */
class FitExprParserTypeTest {
    @Test
    fun `GF-CE-07 a List of Bool in a boolean position needs all or any`() {
        compileError("ex.wpu.reps >= 2 && true").code shouldBe "type"
        compileError("!ex.wpu.jerky").code shouldBe "type"
        compileError("ex.wpu.jerky ? 1 : 2").code shouldBe "type"
        compile("all(ex.wpu.reps >= 2) && true").type shouldBe Type.BoolT
    }

    @Test
    fun `GF-CE-08 dimensions do not mix`() {
        compileError("s.pinA - 2.5kg").code shouldBe "type"
        compileError("s.wpuC + 5s").code shouldBe "type"
        compileError("s.pinA * 1.5").code shouldBe "type"
        compile("s.pinA * 2").type shouldBe Type.PIN
        compile("-p.pinDropLb").type shouldBe Type.PIN
        compile("s.wpuC / s.wpuC").type shouldBe Type.NUM
    }

    @Test
    fun `precedence and associativity`() {
        Printer.print(FitExpr.parse("!a == b", limits)) shouldBe "!((a) == (b))"
        Printer.print(FitExpr.parse("a ?? b || c", limits)) shouldBe "(a) ?? ((b) || (c))"
        Printer.print(FitExpr.parse("a ? b : c ? d : e", limits)) shouldBe "(a) ? (b) : ((c) ? (d) : (e))"
        Printer.print(FitExpr.parse("1 - 2 - 3", limits)) shouldBe "((1) - (2)) - (3)"
        Printer.print(FitExpr.parse("-x.y[2]", limits)) shouldBe "-(((x).y)[2])"
    }

    @Test
    fun `parse errors`() {
        compileError("1 < 2 < 3").code shouldBe "parse"
        compileError("(min)(1)").code shouldBe "parse"
        compileError("1 +").code shouldBe "parse"
        compileError("'open").code shouldBe "parse"
        compileError("3x").code shouldBe "parse"
        compileError("2.5d").code shouldBe "parse"
        compileError("a # b").code shouldBe "parse"
        // A non-ASCII letter outside quotes is a parse error, not an endless empty identifier.
        compileError("café > 1").code shouldBe "parse"
        compileError("é").code shouldBe "parse"
        compileError("2é").code shouldBe "parse"
    }

    @Test
    fun `identifiers functions and arity`() {
        compileError("s.nope").code shouldBe "unknown_identifier"
        compileError("q.x").code shouldBe "unknown_identifier"
        compileError("frobnicate(1)").code shouldBe "unknown_function"
        compileError("clamp(1, 2)").code shouldBe "arity"
        compileError("roundLoad(20kg, 'belt', 'sideways')").code shouldBe "type"
        compileError("mode == 'holiday'").code shouldBe "type"
        compileError("mode in ['normal', 'holiday']").code shouldBe "type"
        compile("mode in ['deload', 'test_pending']").type shouldBe Type.BoolT
        compile("s.bVariant == '6x2'").type shouldBe Type.BoolT
    }

    @Test
    fun `limits on nodes, depth, strings and arguments`() {
        compileError((1..300).joinToString(" + ") { "1" }).code shouldBe "limit"
        compileError("(".repeat(40) + "1" + ")".repeat(40)).code shouldBe "limit"
        compileError("'" + "x".repeat(300) + "'").code shouldBe "parse"
        compileError("min(1,2,3,4,5,6,7,8,9)").code shouldBe "limit"
    }

    @Test
    fun `null and missing semantics`() {
        evalError("t.oa_top_hold_s").code shouldBe "missing:t.oa_top_hold_s"
        eval("defined(t.oa_top_hold_s)") shouldBe BoolV.FALSE
        eval("defined(t.oa_hang_s)") shouldBe BoolV.TRUE
        eval("defined(s.injuryStart)") shouldBe BoolV.FALSE
        evalError("daysSince(s.injuryStart)").code shouldStartWith "null:"
        eval("s.injuryStart == null") shouldBe BoolV.TRUE
        eval("onboard.sthS ?? 6s") shouldBe QV(Dim.DURATION, BigDecimal(6))
        eval("onboard.bw ?? 1kg") shouldBe QV(Dim.MASS, BigDecimal("70.7"))
        eval("false && t.oa_top_hold_s.L > 1s") shouldBe BoolV.FALSE
        eval("true || t.oa_top_hold_s.L > 1s") shouldBe BoolV.TRUE
        evalError("min(ex.wpu.pin)").code shouldBe "empty_aggregate"
        eval("count(ex.wpu.pin)") shouldBe Values.int(0)
        eval("sum(ex.wpu.hold_s)") shouldBe QV(Dim.DURATION, BigDecimal.ZERO)
        eval("all(ex.wpu.ecc_s >= 5s)") shouldBe BoolV.TRUE
        eval("any(ex.wpu.ecc_s >= 5s)") shouldBe BoolV.FALSE
        eval("any(ex.wpu.grip_slip_before_last)") shouldBe BoolV.TRUE
        eval("count(ex.wpu.reps) >= ex.wpu.plannedRows && min(ex.wpu.rir) >= 2") shouldBe BoolV.TRUE
    }

    @Test
    fun `broadcasting and membership`() {
        eval("ex.wpu.reps + 1") shouldBe ListV(List(6) { Values.int(3) })
        eval("['a', 'b'] in ['b', 'c']") shouldBe ListV(listOf(BoolV.FALSE, BoolV.TRUE))
        eval("any(['a', 'b'] in ['b', 'c'])") shouldBe BoolV.TRUE
        evalError("ex.wpu.reps + [1, 2]").code shouldBe "list_length_mismatch"
        eval("t.oa_hang_s.L") shouldBe QV(Dim.DURATION, BigDecimal(15))
        eval("max(t.oa_hang_s)") shouldBe QV(Dim.DURATION, BigDecimal(20))
    }

    @Test
    fun `print and parse round trip`() {
        val samples = listOf(
            "p.approved_P10 && defined(t.wpu_3rm_added) ? roundLoad(clamp(s.a * T1 - bw, s.c - j, s.c + j), 'belt', 'nearest') : null",
            "!s.needsCleanSession && mode in ['deload', 'test_pending'] && defined(t.bw)",
            "any(event.painKinds in ['medial_elbow_ache_after', 'ulnar_tingling'])",
            "a.b[c.d] - -1.5kg * 2 / 3 + 10% ?? 'x\\'y'",
            "x == null || f(1, [2, 3], -4lb) <= 5min",
        )
        for (s in samples) {
            val once = Printer.print(FitExpr.parse(s, limits))
            Printer.print(FitExpr.parse(once, limits)) shouldBe once
        }
    }

    @Test
    fun `evaluation is repeatable`() {
        val c = compile("e1rmTotal(t.bw + t.wpu_3rm_added, 3, userKQuantile('weighted_pullup', 0.1)) - t.bw")
        val a = c.evaluate(FitExprTestSupport.roots(), FitExprTestSupport.host())
        val b = c.evaluate(FitExprTestSupport.roots(), FitExprTestSupport.host())
        (a as QV).v.toPlainString() shouldBe (b as QV).v.toPlainString()
    }
}
