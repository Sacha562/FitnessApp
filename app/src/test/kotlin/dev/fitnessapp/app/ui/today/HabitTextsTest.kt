package dev.fitnessapp.app.ui.today

import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.materialize.HabitDose
import dev.fitnessapp.algo.challenge.materialize.HabitToday
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/** UX 9.6 row wording: the dose, and why a habit is skipped or off today. */
class HabitTextsTest {
    @Test
    fun `doses read as the program writes them`() {
        HabitTexts.dose(HabitDose(sets = listOf(4, 5), totalS = listOf(BigDecimal(120), BigDecimal(180)))) shouldBe "4-5 sets, 2-3 min total"
        HabitTexts.dose(HabitDose(sets = listOf(2, 3), totalS = listOf(BigDecimal(60), BigDecimal(90)))) shouldBe "2-3 sets, 1-1.5 min total"
        HabitTexts.dose(HabitDose(sets = listOf(3), holdS = BigDecimal(20), addedKg = BigDecimal.ZERO, perSide = true)) shouldBe "3 sets × 20 s/side"
        HabitTexts.dose(HabitDose(sets = listOf(3), holdS = BigDecimal(30), addedKg = BigDecimal(5), perSide = true)) shouldBe "3 sets × 30 s/side × + 5 kg"
        HabitTexts.dose(HabitDose()) shouldBe null
    }

    private val hang = HabitToday("daily_hang", LocalTime.of(17, 15), "SKIPPED", "skip", "todayHas('A') || s.elbowTrace")

    @Test
    fun `a skipped habit names the part of its condition that holds`() {
        HabitTexts.status(hang, listOf("A"), emptyMap()) shouldBe "skipped: A day"
        HabitTexts.status(hang, emptyList(), mapOf("elbowTrace" to BoolV.of(true))) shouldBe "skipped: elbow trace"
        HabitTexts.status(hang, emptyList(), mapOf("elbowTrace" to BoolV.of(false))) shouldBe "skipped: today"
        HabitTexts.status(hang.copy(reason = "error:missing"), emptyList(), emptyMap()) shouldBe "skipped: could not check the rule"
        HabitTexts.status(hang.copy(state = "OFF", reason = "status:paused"), emptyList(), emptyMap()) shouldBe "off: paused"
        HabitTexts.status(hang.copy(state = "DONE", reason = null), emptyList(), emptyMap()) shouldBe "done"
        HabitTexts.status(hang.copy(state = "DUE", reason = null), emptyList(), emptyMap()) shouldBe null
    }
}
