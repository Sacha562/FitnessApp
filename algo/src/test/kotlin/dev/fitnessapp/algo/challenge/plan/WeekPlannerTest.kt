package dev.fitnessapp.algo.challenge.plan

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.BlockPos
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.PlannedTest
import dev.fitnessapp.algo.core.clock.Week
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** GF-CE-29, 30, test weeks, postponed tests, relaxations and today advice. */
class WeekPlannerTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val zone = OapFixture.zone

    private fun normalBlock2(): EnrollmentState =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 1, plannedTest = null, needsCleanSession = false))
            .copy(block = BlockPos(2, 1, LocalDate.parse("2026-10-12")))

    /** Chess on Monday evenings until 2026-11-30 (the 14.1 calendar). */
    private fun chess(weekStart: LocalDate) = listOf(
        Interval(Week.atLocal(weekStart, LocalTime.of(19, 30), zone), Week.atLocal(weekStart, LocalTime.of(22, 30), zone)),
    )

    private val lastTest = GymAnchor(
        LocalDate.parse("2026-10-11"),
        Interval(OapFixture.at("2026-10-11", "13:00"), OapFixture.at("2026-10-11", "15:00")),
        listOf("pull_heavy", "grip_heavy", "elbow_flexor_heavy", "heavy_unilateral"),
    )

    private fun input(state: EnrollmentState, weekStart: String, now: String = "${weekStart}T10:00:00Z", prev: GymAnchor? = lastTest, general: List<GymAnchor> = emptyList()) =
        PlanInput(
            now = Instant.parse(now), zone = zone, state = state, facts = OapFixture.facts, weekStart = LocalDate.parse(weekStart),
            previousLastGym = prev, busy = chess(LocalDate.parse(weekStart)), general = general,
        )

    private fun ChallengeWeekPlan.slots() = sessions.map { "${it.slot} ${it.date} ${Week.localTime(it.interval.start, zone)}" }

    @Test
    fun `GF-CE-29 the W42 plan, blocked days and protected windows`() {
        val inp = input(normalBlock2(), "2026-10-12")
        val plan = WeekPlanner.plan(def, params, inp)
        plan.isoWeek shouldBe "2026-W42"
        plan.slots() shouldBe listOf("C 2026-10-14 19:45", "A 2026-10-16 19:45", "B 2026-10-18 11:00")
        plan.blockedDays.map { "${it.date} ${it.scope}" } shouldBe listOf(
            "2026-10-12 tags", "2026-10-13 tags", "2026-10-15 tags", "2026-10-17 all_gym",
        )
        val contract = ContractBuilder.build(def, params, inp.state, plan, 5)
        val w = contract.protectedWindows.filter { it.mode == "hard" }.map { "${it.id} [${it.from}, ${it.to})" }
        w shouldBe listOf(
            "pw.beforeA [2026-10-15T17:45:00Z, 2026-10-16T17:45:00Z)",
            "pw.beforeBT [2026-10-17T09:00:00Z, 2026-10-18T09:00:00Z)",
            "pw.afterB [2026-10-18T10:30:00Z, 2026-10-19T10:30:00Z)",
        )
        contract.gapRule shouldBe dev.fitnessapp.algo.challenge.contract.GapRule(1, listOf("pull_heavy", "grip_heavy", "elbow_flexor_heavy"))
        contract.reservedSessions.map { it.budgetMin } shouldBe listOf(85, 85, 85)
        contract.reservedSessions.first().addOnSlot?.forbidGroups shouldBe listOf("lats", "biceps", "forearms")
        contract.deloadWeek shouldBe false
    }

    @Test
    fun `GF-CE-30 a 24-hour window across the DST change is 24 real hours`() {
        val s = normalBlock2()
        val inp = input(s, "2026-10-19", prev = null)
        val plan = WeekPlanner.plan(def, params, inp)
        val b = plan.sessions.first { it.slot == "B" }
        b.interval.start shouldBe Instant.parse("2026-10-25T10:00:00Z")
        val win = ContractBuilder.build(def, params, s, plan, 5).protectedWindows.first { it.id == "pw.beforeBT" }
        win.from shouldBe Instant.parse("2026-10-24T10:00:00Z")
        win.to shouldBe Instant.parse("2026-10-25T10:00:00Z")
    }

    @Test
    fun `a deload week places the test on Sunday`() {
        val s = normalBlock2().copy(mode = "deload", plannedTest = PlannedTest(LocalDate.parse("2026-10-18"), "blockRelative"))
        val plan = WeekPlanner.plan(def, params, input(s, "2026-10-12"))
        plan.slots() shouldBe listOf("C 2026-10-14 19:45", "A 2026-10-16 19:45", "TEST 2026-10-18 11:00")
        ContractBuilder.build(def, params, s, plan, 5).deloadWeek shouldBe true
    }

    @Test
    fun `a postponed test without a clean session becomes session B`() {
        val s = OapFixture.start().copy(plannedTest = PlannedTest(null, "postponed", LocalDate.parse("2026-10-11")))
        WeekPlanner.requiredSlots(def, params, s, OapFixture.facts, LocalDate.parse("2026-10-12")) shouldBe listOf("C", "A", "B")
        val clean = s.copy(vars = java.util.TreeMap(s.vars).also { it["needsCleanSession"] = dev.fitnessapp.algo.challenge.fitexpr.BoolV.FALSE })
        WeekPlanner.requiredSlots(def, params, clean, OapFixture.facts, LocalDate.parse("2026-10-12")) shouldBe listOf("C", "A", "TEST")
    }

    @Test
    fun `done sessions and matched events stay where they are`() {
        val s = normalBlock2()
        val inp = input(s, "2026-10-12", now = "2026-10-15T08:00:00Z").copy(
            done = listOf(DoneSession("C", LocalDate.parse("2026-10-13"), Interval(OapFixture.at("2026-10-13", "19:45"), OapFixture.at("2026-10-13", "21:15")))),
            matched = listOf(SlotAnchor("A", Interval(OapFixture.at("2026-10-15", "19:45"), OapFixture.at("2026-10-15", "21:15")), "calendar_unmarked", "evt-a")),
        )
        val plan = WeekPlanner.plan(def, params, inp)
        plan.sessions.map { it.slot to it.source } shouldBe listOf("C" to "done", "A" to "calendar_unmarked", "B" to "suggested")
        plan.sessions.last().date shouldBe LocalDate.parse("2026-10-18")
    }

    @Test
    fun `a full week drops C first`() {
        val s = normalBlock2()
        // Only Saturday and Sunday left: B on Sunday needs Saturday free, so A and C cannot both fit.
        val inp = input(s, "2026-10-12", now = "2026-10-16T21:00:00Z")
        val plan = WeekPlanner.plan(def, params, inp)
        plan.partial shouldBe true
        plan.sessions.map { it.slot } shouldBe listOf("B")
        plan.droppedSlots shouldBe listOf("C", "A")
    }

    @Test
    fun `today advice`() {
        val s = normalBlock2()
        val inp = input(s, "2026-10-12")
        val plan = WeekPlanner.plan(def, params, inp)
        TodayAdvisor.advise(def, params, inp, plan).let {
            it.kind shouldBe "general_allowed"
            it.forbiddenSessionTags shouldBe listOf("pull_heavy", "grip_heavy", "elbow_flexor_heavy")
        }
        val wed = inp.copy(now = Instant.parse("2026-10-14T08:00:00Z"))
        TodayAdvisor.advise(def, params, wed, WeekPlanner.plan(def, params, wed)).let {
            it.kind shouldBe "challenge"
            it.slot shouldBe "C"
        }
        val sat = inp.copy(now = Instant.parse("2026-10-17T08:00:00Z"))
        TodayAdvisor.advise(def, params, sat, WeekPlanner.plan(def, params, sat)).kind shouldBe "rest"
    }

    @Test
    fun `a general session inside the window before A moves A`() {
        val s = normalBlock2()
        val pullDay = GymAnchor(
            LocalDate.parse("2026-10-15"),
            Interval(OapFixture.at("2026-10-15", "19:00"), OapFixture.at("2026-10-15", "20:00")),
            listOf("grip_heavy"),
        )
        val plan = WeekPlanner.plan(def, params, input(s, "2026-10-12", general = listOf(pullDay)))
        // The grip-heavy Thursday session blocks Wednesday and Friday (gap rule) and Thursday itself (one visit a day).
        plan.sessions.none { it.date in listOf("2026-10-14", "2026-10-15", "2026-10-16").map(LocalDate::parse) } shouldBe true
        // C has the lowest slot priority, so C is dropped and A takes Tuesday.
        plan.sessions.map { "${it.slot} ${it.date}" } shouldBe listOf("A 2026-10-13", "B 2026-10-18")
        plan.droppedSlots shouldBe listOf("C")
    }
}
