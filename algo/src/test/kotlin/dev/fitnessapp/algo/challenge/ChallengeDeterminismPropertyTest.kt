package dev.fitnessapp.algo.challenge

import dev.fitnessapp.algo.challenge.OapFixture.oaa
import dev.fitnessapp.algo.challenge.OapFixture.session
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.CompileResult
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.FitExpr
import dev.fitnessapp.algo.challenge.fitexpr.FitExprTestSupport
import dev.fitnessapp.algo.challenge.fitexpr.Printer
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

/** CHALLENGE_ENGINE 14.3: print/parse round trips, repeatable evaluation, order-independent folds, stable hashes. */
@OptIn(ExperimentalKotest::class)
class ChallengeDeterminismPropertyTest {
    private val config = PropTestConfig(seed = 20261020L)

    /** Random well-formed expressions over the FitExpr test scope (types may not check; parsing must). */
    private fun expr(depth: Int): Arb<String> = arbitrary { rs ->
        val leaves = listOf("1", "2.5kg", "5lb", "6s", "14d", "65%", "true", "null", "'x'", "s.pinA", "p.gate1GapKg", "t.bw", "ex.wpu.reps", "today")
        fun gen(d: Int): String {
            val r = rs.random
            if (d <= 0 || r.nextInt(4) == 0) return leaves[r.nextInt(leaves.size)]
            return when (r.nextInt(7)) {
                0 -> "(" + gen(d - 1) + " " + listOf("+", "-", "*", "/").random(r) + " " + gen(d - 1) + ")"
                1 -> "(" + gen(d - 1) + " " + listOf("==", "<", ">=", "in").random(r) + " " + gen(d - 1) + ")"
                2 -> "(!(" + gen(d - 1) + "))"
                3 -> "(" + gen(d - 1) + " ? " + gen(d - 1) + " : " + gen(d - 1) + ")"
                4 -> listOf("min", "max", "count", "defined").random(r) + "(" + gen(d - 1) + ")"
                5 -> "[" + gen(d - 1) + ", " + gen(d - 1) + "]"
                else -> "(" + gen(d - 1) + " ?? " + gen(d - 1) + ")"
            }
        }
        gen(depth)
    }

    @Test
    fun `print then parse reproduces the tree`() {
        runBlocking {
            checkAll(config, expr(4)) { src ->
                val once = Printer.print(FitExpr.parse(src, FitExprTestSupport.limits))
                Printer.print(FitExpr.parse(once, FitExprTestSupport.limits)) shouldBe once
            }
        }
    }

    @Test
    fun `evaluating the same expression twice gives identical results`() {
        runBlocking {
            checkAll(config, expr(3)) { src ->
                val c = (FitExpr.compile(src, FitExprTestSupport.scope, FitExprTestSupport.limits) as? CompileResult.Ok)?.expr
                    ?: return@checkAll
                fun run(): String = try {
                    c.evaluate(FitExprTestSupport.roots(), FitExprTestSupport.host()).toString()
                } catch (e: EvalError) {
                    "error:" + e.code
                }
                run() shouldBe run()
            }
        }
    }

    @Test
    fun `a fold does not depend on the arrival order of its events`() {
        runBlocking {
            val created = OapFixture.created(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
            val decisions = OapFixture.decisions(true)
            val c = session("C", "2026-10-07", mapOf("oaa" to oaa("50", List(3) { 5 to 5 }, targetReps = 5)), time = "21:15")
            val a = session("A", "2026-10-09", mapOf("oaa" to oaa("40", List(5) { 3 to 3 }, targetReps = 3)), time = "21:15")
            val base = EnrollmentFold.fold(OapFixture.def, OapFixture.params(), listOf(created) + decisions + listOf(c, a)).state
            val (ticks, _) = EnrollmentFold.catchUp(OapFixture.def, OapFixture.params(), base, Instant.parse("2026-10-20T08:00:00Z"), OapFixture.zone, OapFixture.facts)
            val events = listOf(created) + decisions + listOf(c, a) + ticks
            val reference = StateJson.hash(EnrollmentFold.fold(OapFixture.def, OapFixture.params(), events).state)
            checkAll(config.copy(iterations = 25), Arb.long()) { seed ->
                val shuffled = events.shuffled(kotlin.random.Random(seed))
                StateJson.hash(EnrollmentFold.fold(OapFixture.def, OapFixture.params(), shuffled).state) shouldBe reference
            }
        }
    }

    @Test
    fun `the state hash is stable`() {
        runBlocking {
            checkAll(config.copy(iterations = 10), Arb.int(1..5), Arb.element(listOf("normal", "deload", "test_pending"))) { week, mode ->
                val s = OapFixture.start(OapFixture.import(mode = mode, blockWeek = week, plannedTest = null))
                StateJson.hash(s) shouldBe StateJson.hash(s.copy())
                StateJson.encode(s).toString() shouldBe StateJson.encode(s).toString()
            }
        }
    }
}
