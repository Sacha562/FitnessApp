package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 4.6 absence prompt and the 10.5 pause preview (dry run of the resume rules for 1, 7 and 28 days). */
class PauseToolsTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun normal(approve: Boolean) =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 3, plannedTest = null, needsCleanSession = false), approve)

    @Test
    fun `the absence prompt appears after 14 days without a challenge session`() {
        val s = normal(true).copy(lastChallengeSessionDate = LocalDate.parse("2026-10-01"))
        PauseTools.absencePromptDue(s, LocalDate.parse("2026-10-14"), params) shouldBe false
        PauseTools.absencePromptDue(s, LocalDate.parse("2026-10-15"), params) shouldBe true
        PauseTools.absencePromptDue(s.copy(status = EnrollmentState.PAUSED), LocalDate.parse("2026-10-20"), params) shouldBe false
    }

    @Test
    fun `the pause preview follows P15`() {
        val p = PauseTools.previewResume(def, params, normal(true), LocalDate.parse("2026-10-14"), OapFixture.zone, OapFixture.facts, listOf(1, 7, 28))
        p.map { it.notifications.map { n -> n.msgKey } } shouldBe listOf(emptyList(), listOf("resume_repeat"), listOf("resume_restart"))
        p[1].state.vars.getValue("needsCleanSession") shouldBe BoolV.TRUE
        p[2].state.block.week shouldBe 1
        p.all { it.state.status == EnrollmentState.ACTIVE } shouldBe true
        // P15 declined: any pause resumes the same week; the test waits for a clean session.
        val d = PauseTools.previewResume(def, params, normal(false), LocalDate.parse("2026-10-14"), OapFixture.zone, OapFixture.facts, listOf(1, 28))
        d.map { it.notifications.map { n -> n.msgKey } } shouldBe listOf(listOf("resume_same_week"), listOf("resume_same_week"))
        d[1].state.block.week shouldBe 3
    }
}
