package dev.fitnessapp.algo.challenge.plan

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.enrollment.BlockPos
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.PlannedTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** D72: the expected test date while none is planned (CHALLENGE_ENGINE 7.7). */
class TestProjectionTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun block2(week: Int, mode: String = Modes.NORMAL, except: Map<String, Boolean> = emptyMap()): EnrollmentState =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 1, plannedTest = null, needsCleanSession = false), except = except)
            .copy(block = BlockPos(2, week, LocalDate.parse("2026-10-26")), mode = mode)

    private fun expected(state: EnrollmentState, today: String) =
        TestProjection.expected(def, params, state, OapFixture.facts, LocalDate.parse(today))

    @Test
    fun `block week 1 expects the Sunday of block week 5`() {
        expected(block2(1), "2026-11-02") shouldBe TestProjection.Expected(LocalDate.parse("2026-12-06"), "blockRelative")
    }

    @Test
    fun `later in the block the same Sunday`() {
        expected(block2(3), "2026-11-18") shouldBe TestProjection.Expected(LocalDate.parse("2026-12-06"), "blockRelative")
        expected(block2(4), "2026-11-29") shouldBe TestProjection.Expected(LocalDate.parse("2026-12-06"), "blockRelative")
    }

    @Test
    fun `an extra deload week does not count as a block week`() {
        expected(block2(1, Modes.EXTRA_DELOAD), "2026-10-28") shouldBe TestProjection.Expected(LocalDate.parse("2026-12-06"), "blockRelative")
    }

    @Test
    fun `declined P07 expects the next fixed date`() {
        expected(block2(1, except = mapOf("P07" to false)), "2026-11-02") shouldBe TestProjection.Expected(LocalDate.parse("2026-11-08"), "fixedList")
    }

    @Test
    fun `no expected date in injury, when planned or when postponed`() {
        expected(block2(1, Modes.INJURY), "2026-11-02") shouldBe null
        expected(block2(5, Modes.DELOAD).copy(plannedTest = PlannedTest(LocalDate.parse("2026-12-06"), "blockRelative")), "2026-11-30") shouldBe null
        expected(block2(5, Modes.TEST_PENDING).copy(plannedTest = PlannedTest(null, "postponed", LocalDate.parse("2026-12-06"))), "2026-12-07") shouldBe null
    }

    @Test
    fun `past the block's last week no date is guessed`() {
        expected(block2(6), "2026-12-07") shouldBe null
    }
}
