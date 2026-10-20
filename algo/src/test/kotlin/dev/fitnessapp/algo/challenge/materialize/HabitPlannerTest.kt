package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Home habits (2.4.8, 6.4): daily hang skip, deload scale, injury mute, plank days and the measure day (P26). */
class HabitPlannerTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

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
}
