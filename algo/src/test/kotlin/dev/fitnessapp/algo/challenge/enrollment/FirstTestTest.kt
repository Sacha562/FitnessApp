package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.eca.Outcome
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.TreeMap

/** D37: the setup's two starts leave no empty program value once the first valid test is in. */
class FirstTestTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun start(onboard: Map<String, Value>, approve: Boolean = true): EnrollmentState {
        val created = OapFixture.event(
            EventTypes.CREATED, "2026-10-21", "12:00",
            EventPayload.Created(
                OapFixture.enrollmentId, sortedMapOf(), TreeMap(onboard),
                ImportBlock(mode = "test_pending", blockWeek = 1, plannedTestDate = java.time.LocalDate.parse("2026-10-25")),
                def.version, def.jcsSha256,
            ),
            name = "created",
        )
        return EnrollmentFold.fold(def, params, listOf(created) + OapFixture.decisions(approve, date = "2026-10-21")).state
    }

    private fun test(date: String = "2026-10-25") = OapFixture.event(
        EventTypes.TEST_COMPLETED, date, "13:00",
        EventPayload.TestCompleted(
            OapFixture.id("test:$date"), "T1",
            TreeMap(
                mapOf(
                    "bw" to OapFixture.mass("70.7"), "wpu_3rm_added" to OapFixture.mass("32.5"), "bw_max_reps" to Values.int(18),
                    "oa_hang_s" to SideV(OapFixture.dur("16"), OapFixture.dur("21")),
                    "oa_top_hold_s" to SideV(OapFixture.dur("1.5"), OapFixture.dur("2")),
                    "oa_least_assist_pin" to SideV(OapFixture.pin("45"), OapFixture.pin("40")),
                    "oa_neg5_unassisted" to SideV(BoolV.TRUE, BoolV.FALSE),
                ),
            ),
        ),
    )

    private fun EnrollmentState.kg(name: String): String? = (vars[name] as? QV)?.v?.stripTrailingZeros()?.toPlainString()

    @Test
    fun `entered numbers give the gate e1RM on the personal curve`() {
        val s = start(OapFixture.onboard)
        // (70.7 + 32.5) x (1 + 2 / 30) - 70.7 = 39.38 kg (D7, k 30 class prior).
        s.kg("e1rmAdded") shouldBe "39.38"
        s.kg("e1rmAddedM110") shouldBe "42.82"
        s.kg("negB") shouldBe "42.5"
        (s.vars.getValue("anchorFraction") is QV) shouldBe true
    }

    @Test
    fun `a test-week start gets every load from its first valid test`() {
        val s0 = start(emptyMap())
        for (name in def.snapshotVars.filter { it.startsWith("wpu") || it.startsWith("neg") || it.startsWith("pin") || it == "kgPerRep" }) {
            (s0.vars[name] ?: NullV) shouldBe NullV
        }
        val step = EnrollmentFold.apply(def, params, s0, test())
        val s = step.state
        s.validTestCount shouldBe 1
        // T1 = 70.7 + 39.38 = 110.08 kg; C = load for 5 + 2 reps on the curve: 110.08 / 1.2 - 70.7 = 21.03 -> +20 kg (belt, down).
        s.kg("wpuC") shouldBe "20"
        // P10's slope at C: (70.7 + 20) / (30 + 5 - 1) = 2.6676 kg per rep; A = C + 1 rep, B = C + 3 reps (nearest).
        s.kg("kgPerRep") shouldBe "2.67"
        s.kg("wpuA") shouldBe "22.5"
        s.kg("wpuB") shouldBe "27.5"
        s.kg("negB") shouldBe "42.5"
        s.kg("negReset") shouldBe "40"
        s.kg("pinA") shouldBe "55"
        (s.vars.getValue("anchorFraction") as QV).v.toDouble() shouldBe (90.7 / 110.08 * 10_000).let { Math.round(it) / 10_000.0 }
        val fired = step.audits.filter { it.outcome == Outcome.APPLIED }.map { it.ruleId }
        fired.contains("data.test.first") shouldBe true
        fired.contains("data.test.first.neg") shouldBe true
        step.audits.none { it.outcome == Outcome.ERROR } shouldBe true
        for (name in def.snapshotVars) (s.vars[name] is QV || s.vars[name] is BoolV || s.vars[name] is dev.fitnessapp.algo.challenge.fitexpr.StrV) shouldBe true
    }

    @Test
    fun `with P10 and P11 declined the first test uses the artifact slope and the pin formula`() {
        val step = EnrollmentFold.apply(def, params, start(emptyMap(), approve = false), test())
        val s = step.state
        // Artifact: 32.5 / (18 - 3) = 2.1667 kg per rep.
        s.kg("kgPerRep") shouldBe "2.17"
        s.kg("wpuA") shouldBe "22.5"
        s.kg("pinA") shouldBe "55"
        step.audits.filter { it.outcome == Outcome.APPLIED }.map { it.ruleId }.contains("data.test.first.pins") shouldBe true
        step.audits.none { it.outcome == Outcome.ERROR } shouldBe true
    }
}
