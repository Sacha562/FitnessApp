package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.OapFixture.bWpu
import dev.fitnessapp.algo.challenge.OapFixture.oaa
import dev.fitnessapp.algo.challenge.OapFixture.session
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Values
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** Creation and import (D4), GF-CE-14, 15, the test-day flow and P07 block-relative test dates. */
class EnrollmentFoldTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun q(s: EnrollmentState, v: String): BigDecimal = (s.vars.getValue(v) as QV).v

    @Test
    fun `creation applies the import and the init expressions`() {
        val s = OapFixture.start()
        s.mode shouldBe "test_pending"
        s.block shouldBe BlockPos(1, 5, LocalDate.parse("2026-09-07"))
        q(s, "anchorFraction").toPlainString() shouldBe "0.8239"
        q(s, "kgPerRep").toPlainString() shouldBe "2.17"
        q(s, "wpuB").compareTo(BigDecimal("27.5")) shouldBe 0
        s.vars.getValue("bVariant") shouldBe Values.str("6x2")
        s.vars.getValue("needsCleanSession") shouldBe BoolV.TRUE
        s.snapshots.keys.toList() shouldBe listOf(0, 1)
        s.plannedTest shouldBe PlannedTest(LocalDate.parse("2026-10-11"), "postponed")
        s.proposalDecisions.size shouldBe 27
    }

    @Test
    fun `GF-CE-14 an extra deload week does not use a build week`() {
        val s = OapFixture.start(OapFixture.import(mode = "extra_deload", blockWeek = 1, plannedTest = null, needsCleanSession = false))
        val (events, steps) = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-12T05:00:00Z"), OapFixture.zone, OapFixture.facts)
        events.last().type shouldBe EventTypes.WEEK_STARTED
        val end = steps.last().state
        end.block.week shouldBe 1
        end.mode shouldBe "normal"
        end.weekOrdinal shouldBe 7
    }

    @Test
    fun `GF-CE-15 a late session folds to the same state`() {
        val created = OapFixture.created(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        val decisions = OapFixture.decisions(true)
        val c = session("C", "2026-10-13", mapOf("oaa" to oaa("50", List(3) { 5 to 5 }, targetReps = 5)), time = "21:15")
        val a = session("A", "2026-10-15", mapOf("oaa" to oaa("40", List(5) { 3 to 3 }, targetReps = 3)), time = "21:15")
        val inOrder = EnrollmentFold.fold(def, params, listOf(created) + decisions + listOf(c, a))
        val late = EnrollmentFold.fold(def, params, listOf(created) + decisions + listOf(a, c))
        StateJson.hash(late.state) shouldBe StateJson.hash(inOrder.state)
    }

    @Test
    fun `GF-CE-28 test due without a clean session postpones the test`() {
        val s = OapFixture.start()
        val (_, steps) = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-11T08:00:00Z"), OapFixture.zone, OapFixture.facts)
        val st = steps.last().state
        st.mode shouldBe "test_pending"
        st.plannedTest.basis shouldBe "postponed"
        st.plannedTest.date shouldBe null
        steps.flatMap { it.notifications }.map { it.msgKey } shouldBe listOf("test_postponed")
    }

    private fun testDay(date: String, hangL: String, hangR: String, top: String, assist: String, neg: Boolean, rm3: String, bw: String = "70.7") =
        OapFixture.event(
            EventTypes.TEST_COMPLETED, date, "13:00",
            EventPayload.TestCompleted(
                OapFixture.id("test:$date"), "T1",
                TreeMap(
                    mapOf(
                        "bw" to OapFixture.mass(bw), "wpu_3rm_added" to OapFixture.mass(rm3), "bw_max_reps" to Values.int(18),
                        "oa_hang_s" to SideV(OapFixture.dur(hangL), OapFixture.dur(hangR)),
                        "oa_top_hold_s" to SideV(OapFixture.dur(top), OapFixture.dur(top)),
                        "oa_least_assist_pin" to SideV(OapFixture.pin(assist), OapFixture.pin(assist)),
                        "oa_neg5_unassisted" to SideV(BoolV.of(neg), BoolV.of(neg)),
                    ),
                ),
            ),
        )

    @Test
    fun `a valid test recalculates the loads and starts a block on Monday`() {
        // A clean A session on Thursday makes the Sunday test valid.
        val s0 = OapFixture.start()
        val a = session("A", "2026-10-08", mapOf("oaa" to oaa("40", List(3) { 3 to 3 }, targetReps = 3)), time = "21:15")
        var s = EnrollmentFold.apply(def, params, s0, a).state
        s.vars.getValue("needsCleanSession") shouldBe BoolV.FALSE
        s = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-11T08:00:00Z"), OapFixture.zone, OapFixture.facts).second.last().state
        s.mode shouldBe "test_pending"
        val step = EnrollmentFold.apply(def, params, s, testDay("2026-10-11", "15", "17", "6", "35", neg = false, rm3 = "32.5"))
        s = step.state
        val audits = step.audits.associateBy { it.ruleId }
        audits.getValue("data.test.e1rm").outcome.name shouldBe "NO_EFFECT" // the import already holds 39.38
        q(s, "e1rmAdded").toPlainString() shouldBe "39.38"
        // P10 reproduces the anchor: 0.8239 x 110.08 - 70.7 = 20.0 -> 20 kg; A 22.5, B 27.5.
        q(s, "wpuC").compareTo(BigDecimal(20)) shouldBe 0
        q(s, "wpuA").compareTo(BigDecimal("22.5")) shouldBe 0
        q(s, "wpuB").compareTo(BigDecimal("27.5")) shouldBe 0
        // P11: weaker arm 35 lb -> A 45, C 55, hold 40.
        q(s, "pinA").compareTo(BigDecimal(45)) shouldBe 0
        q(s, "pinC").compareTo(BigDecimal(55)) shouldBe 0
        q(s, "pinHold90").compareTo(BigDecimal(40)) shouldBe 0
        // P06: floor(0.67 x 15) = 10 s.
        q(s, "oaHangS").compareTo(BigDecimal(10)) shouldBe 0
        s.vars.getValue("blockEndPending") shouldBe BoolV.TRUE
        s.lastValidTest?.date shouldBe LocalDate.parse("2026-10-11")
        s = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-12T05:00:00Z"), OapFixture.zone, OapFixture.facts).second.last().state
        s.block shouldBe BlockPos(2, 1, LocalDate.parse("2026-10-12"))
        s.mode shouldBe "normal"
        s.snapshots.getValue(2).getValue("pinA") shouldBe OapFixture.pin("45")
        s.plannedTest shouldBe PlannedTest.NONE
    }

    @Test
    fun `P07 schedules the test in week 5 of the block`() {
        val s0 = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 4, plannedTest = null, needsCleanSession = false))
        val c = session("C", "2026-10-07", mapOf("oaa" to oaa("50", List(3) { 5 to 5 }, targetReps = 5)), time = "21:15")
        val a = session("A", "2026-10-09", mapOf("oaa" to oaa("40", List(5) { 3 to 3 }, targetReps = 3)), time = "21:15")
        var s = EnrollmentFold.apply(def, params, EnrollmentFold.apply(def, params, s0, c).state, a).state
        s = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-12T05:00:00Z"), OapFixture.zone, OapFixture.facts).second.last().state
        s.block.week shouldBe 5
        s.mode shouldBe "deload"
        s.plannedTest shouldBe PlannedTest(LocalDate.parse("2026-10-18"), "blockRelative")
    }

    @Test
    fun `a missed week repeats with the same loads`() {
        val s0 = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        val b = session("B", "2026-10-11", mapOf("wpu" to bWpu("27.5", listOf(2, 2, 2, 2, 2, 2))), time = "12:30")
        var s = EnrollmentFold.apply(def, params, s0, b).state
        q(s, "wpuB").compareTo(BigDecimal(30)) shouldBe 0
        s = EnrollmentFold.catchUp(def, params, s, Instant.parse("2026-10-12T05:00:00Z"), OapFixture.zone, OapFixture.facts).second.last().state
        // One of three sessions (P02: at least 2 must count): the week repeats and the next test needs a clean session.
        s.block.week shouldBe 2
        s.vars.getValue("needsCleanSession") shouldBe BoolV.TRUE
    }
}
