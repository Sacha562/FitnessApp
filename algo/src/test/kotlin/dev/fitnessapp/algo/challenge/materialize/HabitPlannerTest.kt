package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Home habits (2.4.8, 6.4): daily hang skip, deload scale, injury mute, plank days and the measure day (P26). */
class HabitPlannerTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.4.json")) }

    /** W42: C Wednesday, A Friday, B Sunday. */
    private val facts = OapFixture.facts.copy(
        weekPlanSlots = sortedMapOf(
            LocalDate.parse("2026-10-14") to listOf("C"), LocalDate.parse("2026-10-16") to listOf("A"), LocalDate.parse("2026-10-18") to listOf("B"),
        ),
    )

    private fun normal(approve: Boolean = true) =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false), approve)

    private fun today(s: EnrollmentState, date: String, general: Set<String> = emptySet(), done: Set<String> = emptySet()) =
        HabitPlanner.today(def, params, HabitInput(LocalDate.parse(date), s, facts, general.map(LocalDate::parse).toSet(), done)).first

    @Test
    fun `the daily hang on a normal Monday is due and measured`() {
        val rows = today(normal(), "2026-10-12")
        rows.map { it.habitId } shouldBe listOf("daily_hang")
        val hang = rows.single()
        hang.state shouldBe "DUE"
        hang.measureToday shouldBe true
        hang.dose.sets shouldBe listOf(4, 5)
        hang.dose.totalS shouldBe listOf(BigDecimal(120), BigDecimal(180))
        // P26 declined: the user picks the day.
        today(normal(approve = false), "2026-10-12").single().measureToday shouldBe false
        today(normal(), "2026-10-12", done = setOf("daily_hang")).single().state shouldBe "DONE"
    }

    @Test
    fun `the hang is skipped on an A day and the plank lands on Tuesday and Thursday`() {
        today(normal(), "2026-10-16").single { it.habitId == "daily_hang" }.let {
            it.state shouldBe "SKIPPED"
            it.reason shouldBe "skip"
        }
        val tue = today(normal(), "2026-10-13").single { it.habitId == "home_plank" }
        tue.state shouldBe "DUE"
        tue.dose.sets shouldBe listOf(3)
        tue.dose.holdS shouldBe BigDecimal(20)
        tue.dose.addedKg shouldBe BigDecimal(0)
        tue.dose.perSide shouldBe true
        today(normal(), "2026-10-15").any { it.habitId == "home_plank" } shouldBe true
        // A general gym session on Thursday moves that plank to the nearest free day (Monday: Friday, Wednesday,
        // Saturday before B and Sunday are taken; Tuesday already has one).
        val week = HabitPlanner.scheduledDays(def.doc.habits.single { it.id == "home_plank" }, HabitInput(LocalDate.parse("2026-10-12"), normal(), facts, setOf(LocalDate.parse("2026-10-15"))))
        week shouldBe listOf("2026-10-12", "2026-10-13").map(LocalDate::parse)
    }

    @Test
    fun `the hang is skipped on every gym day, test day included, and due on the days between (D51)`() {
        val withTest = facts.copy(weekPlanSlots = facts.weekPlanSlots.toSortedMap().apply { put(LocalDate.parse("2026-10-17"), listOf("TEST")) })
        fun hang(date: String) = HabitPlanner.today(def, params, HabitInput(LocalDate.parse(date), normal(), withTest)).first.single { it.habitId == "daily_hang" }.state
        listOf("2026-10-14", "2026-10-16", "2026-10-17", "2026-10-18").map(::hang) shouldBe List(4) { "SKIPPED" }
        listOf("2026-10-13", "2026-10-15").map(::hang) shouldBe List(2) { "DUE" }
    }

    @Test
    fun `deload halves the hang, injury and pause mute it`() {
        val deload = today(normal().copy(mode = "deload"), "2026-10-12").single()
        deload.state shouldBe "SCALED"
        deload.reason shouldBe "mode:deload"
        deload.dose.sets shouldBe listOf(2, 3)
        deload.dose.totalS shouldBe listOf(BigDecimal(60), BigDecimal(90))
        today(normal().copy(mode = "injury"), "2026-10-12").single().let {
            it.state shouldBe "OFF"
            it.reason shouldBe "mode:injury"
        }
        today(normal().copy(status = EnrollmentState.PAUSED), "2026-10-12").single().reason shouldBe "status:paused"
    }

    private fun plan(s: EnrollmentState, date: String, habit: String): SessionPlan? {
        val input = HabitInput(LocalDate.parse(date), s, facts)
        val row = HabitPlanner.today(def, params, input).first.single { it.habitId == habit }
        return HabitPlanner.plan(def, params, input, row, catalog)
    }

    @Test
    fun `the plank mini-session logs per-set holds under the prescription key`() {
        val p = plan(normal(), "2026-10-13", "home_plank")!!
        p.kind shouldBe "home"
        p.slot shouldBe HabitPlanner.HOME_SLOT
        p.templateId shouldBe "habit:home_plank"
        val item = p.items.single()
        item.key shouldBe "plank"
        item.exerciseKey shouldBe "one_arm_plank"
        item.rows.map { it.side to it.setIndex } shouldBe listOf("L" to 1, "R" to 1, "L" to 2, "R" to 2, "L" to 3, "R" to 3)
        item.rows.all { it.targetHoldS == BigDecimal(20) && it.logMode == LogMode.HOLD_CHIPS && it.restS == 60 } shouldBe true
        item.rows.all { it.addedKg == null } shouldBe true // no plate yet
        item.stopSetOn shouldBe listOf("hip_rotation")
        item.inSessionRuleIds shouldBe listOf("ins.plank.hipStop")
    }

    @Test
    fun `the hang mini-session prescribes the lower end of its ranges and no per-set target`() {
        val item = plan(normal(), "2026-10-12", "daily_hang")!!.items.single()
        item.key shouldBe "dhang"
        item.rows.map { it.side } shouldBe listOf("both", "both", "both", "both")
        item.rows.all { it.targetHoldS == null } shouldBe true
        item.notes.size shouldBe 4
        // A skipped habit has no session to start.
        plan(normal(), "2026-10-16", "daily_hang") shouldBe null
    }
}
