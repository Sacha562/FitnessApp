package dev.fitnessapp.app.ui.testday

import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.materialize.LogMode
import dev.fitnessapp.algo.challenge.materialize.PlanSnapshot
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.PlannedRow
import dev.fitnessapp.algo.challenge.materialize.RampSet
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.challenge.materialize.TestSteps
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** UX 14.9 / D36: the wizard's steps, sides, next loads and Submit values, rebuilt from logged rows alone. */
class TestDayModelTest {
    private fun row(side: String, kind: String = "test", reps: Int? = null, attempts: Int? = null, duration: String? = null, rest: Int? = 180) =
        PlannedRow(side, kind, 1, targetReps = reps, attempts = attempts, durationS = duration?.let(::BigDecimal), restS = rest, logMode = LogMode.TEST_RECORD)

    private fun item(key: String, metric: String?, record: String?, role: String = "test", rows: List<PlannedRow>, exercise: String? = key, status: String = "planned", steps: TestSteps? = null, reason: String? = null) =
        PlannedItem(key, exercise, exercise, mapOf("en" to key), role, status, reason, rows, progressionMode = "none", metric = metric, record = record, steps = steps)

    private val lr = listOf("L", "R")

    // The OAP T1 order with P08, P20 and P21 approved (GF-CE-38).
    private val plan = SessionPlan(
        "TEST", "T1", 1, "test_pending", LocalDate.parse("2026-10-25"), "test", 120, 120,
        listOf(
            item("bw", "bw", null, rows = listOf(row("both")), exercise = null),
            item("wu_row", null, null, "warmup", listOf(row("both", "warmup", duration = "180"))),
            item("wu_face", null, null, "warmup", listOf(row("both", "warmup", reps = 20))),
            item(
                "str", "wpu_3rm_added", "heaviestClean", rows = listOf(row("both")),
                steps = TestSteps(listOf(RampSet(BigDecimal.ZERO, 1, 3), RampSet(BigDecimal("17.5"), 1, 3)), BigDecimal("32.5"), BigDecimal("2.5"), 3),
            ),
            item("hang", "oa_hang_s", "max", rows = lr.map { row(it, attempts = 1) }),
            item("top", "oa_top_hold_s", "max", rows = lr.map { row(it, attempts = 3) }),
            item("assist", "oa_least_assist_pin", "lowestClean", rows = lr.map { row(it) }, steps = TestSteps(start = BigDecimal("50"), step = BigDecimal("-5"), maxAttempts = 5)),
            item("neg", "oa_neg5_unassisted", "bool", rows = lr.map { row(it, attempts = 1) }, status = "skipped", reason = "skipIf: s.elbowTrace"),
            item("max", "bw_max_reps", "max", rows = listOf(row("both"))),
            item("rep", "oa_rep", "bool", rows = lr.map { row(it, attempts = 2) }, status = "excluded"),
        ),
        null, null, false, emptyList(), PlanSnapshot("sha", "1.1.0", "p", null, 1),
    )

    private val types = mapOf(
        "bw" to "mass", "wpu_3rm_added" to "mass", "oa_hang_s" to "duration", "oa_top_hold_s" to "duration",
        "oa_least_assist_pin" to "pin", "oa_neg5_unassisted" to "bool", "bw_max_reps" to "int", "oa_rep" to "bool",
    )

    private val steps = TestDayModel.steps(plan) { types[it] }
    private fun step(key: String) = steps.single { it.key == key }

    private var t = 0L
    private fun logged(key: String, side: String, kind: String = "test", reps: Int? = null, kg: String? = null, pin: String? = null, s: String? = null, clean: Boolean = true) =
        LoggedRow("id${++t}", key, side, kind, reps, kg?.let(::BigDecimal), pin?.let(::BigDecimal), s?.let(::BigDecimal), if (clean) emptyList() else listOf(LoggedRow.FAILED_FLAG), t)

    @Test
    fun `steps follow the plan with the warm-up as one step and excluded items left out`() {
        steps.map { it.key } shouldBe listOf("bw", "warmup", "str", "hang", "top", "assist", "neg", "max")
        steps.map { it.kind } shouldBe listOf(
            StepKind.WEIGH, StepKind.WARMUP, StepKind.ATTEMPTS, StepKind.HOLD, StepKind.HOLD, StepKind.LADDER, StepKind.BOOL, StepKind.REPS,
        )
        steps.map { it.number } shouldBe (1..8).toList()
        step("warmup").items.map { it.key } shouldBe listOf("wu_row", "wu_face")
        step("neg").skipped shouldBe "skipIf: s.elbowTrace"
        TestDayModel.requiredReps(step("str").item) shouldBe 3
    }

    @Test
    fun `the strength attempts start at the protocol load and add its step after a clean triple`() {
        val s = step("str")
        TestDayModel.nextLoad(s, "both", emptyList(), BigDecimal("30")) shouldBe NextLoad(BigDecimal("32.5"))
        val rows = listOf(logged("str", "both", reps = 3, kg = "32.5"))
        TestDayModel.nextLoad(s, "both", rows, null) shouldBe NextLoad(BigDecimal("35.0"))
        val missed = rows + logged("str", "both", reps = 2, kg = "35", clean = false)
        TestDayModel.nextLoad(s, "both", missed, null) shouldBe NextLoad(BigDecimal("35"))
        TestDayModel.currentSide(s, missed) shouldBe "both"
        val third = missed + logged("str", "both", reps = 3, kg = "35")
        TestDayModel.currentSide(s, third) shouldBe null
        TestDayModel.value(s, third) shouldBe QV(Dim.MASS, BigDecimal("35"))
    }

    @Test
    fun `without protocol steps the previous test is the start and the stepper moves one point`() {
        val plain = step("str").let { it.copy(items = listOf(it.item.copy(steps = null))) }
        TestDayModel.nextLoad(plain, "both", emptyList(), BigDecimal("30")) shouldBe NextLoad(BigDecimal("30"))
        TestDayModel.nextLoad(plain, "both", listOf(logged("str", "both", reps = 3, kg = "30")), null) shouldBe NextLoad(BigDecimal("30"), 1)
        TestDayModel.maxAttempts(plain) shouldBe null
    }

    @Test
    fun `the ladder runs left then right and ends a side at the first failure`() {
        val s = step("assist")
        TestDayModel.nextLoad(s, "L", emptyList(), null) shouldBe NextLoad(BigDecimal("50"))
        var rows = listOf(logged("assist", "L", reps = 1, pin = "50"))
        TestDayModel.nextLoad(s, "L", rows, null) shouldBe NextLoad(BigDecimal("45"))
        rows = rows + logged("assist", "L", reps = 1, pin = "45") + logged("assist", "L", reps = 0, pin = "40", clean = false)
        TestDayModel.currentSide(s, rows) shouldBe "R"
        TestDayModel.nextLoad(s, "R", rows, null) shouldBe NextLoad(BigDecimal("50"))
        rows = rows + logged("assist", "R", reps = 1, pin = "50") + logged("assist", "R", reps = 1, pin = "45") + logged("assist", "R", reps = 1, pin = "40")
        // The machine's lowest pin ends the side as well.
        TestDayModel.currentSide(s, rows, minPin = BigDecimal("40")) shouldBe null
        TestDayModel.value(s, rows) shouldBe SideV(QV(Dim.PIN, BigDecimal("45")), QV(Dim.PIN, BigDecimal("40")))
        TestDayModel.attemptCounts(steps, rows) shouldBe mapOf("oa_least_assist_pin|L" to 3, "oa_least_assist_pin|R" to 3)
    }

    @Test
    fun `holds take the best attempt per side and yes or no attempts stop at the first success`() {
        val top = step("top")
        val rows = listOf(
            logged("top", "L", s = "1.0"), logged("top", "L", s = "1.5"), logged("top", "L", s = "0.5"),
            logged("top", "R", s = "2.0"),
        )
        TestDayModel.currentSide(top, rows) shouldBe "R"
        TestDayModel.value(top, rows) shouldBe SideV(QV(Dim.DURATION, BigDecimal("1.5")), QV(Dim.DURATION, BigDecimal("2.0")))
        val neg = step("neg")
        val tries = listOf(logged("neg", "L", reps = 0, clean = false))
        TestDayModel.currentSide(neg, tries) shouldBe "R"
        TestDayModel.value(neg, tries) shouldBe SideV(BoolV.FALSE, null)
    }

    @Test
    fun `submit sends the weigh-in and every step with a result`() {
        val rows = listOf(
            logged("str", "both", reps = 3, kg = "32.5"), logged("hang", "L", s = "16"), logged("hang", "R", s = "21"),
            logged("max", "both", reps = 19), logged("str", "both", "warmup", reps = 3, kg = "17.5"),
        )
        val m = TestDayModel.metrics(steps, rows, BigDecimal("70.4"))
        m.keys.toList() shouldBe listOf("bw", "bw_max_reps", "oa_hang_s", "wpu_3rm_added")
        m["bw"] shouldBe QV(Dim.MASS, BigDecimal("70.4"))
        m["bw_max_reps"] shouldBe QV(Dim.INT, BigDecimal(19))
        TestDayModel.rampDone(rows, step("str")) shouldBe listOf(BigDecimal("17.5"))
    }
}
