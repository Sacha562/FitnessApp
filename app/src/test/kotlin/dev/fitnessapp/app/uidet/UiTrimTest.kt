package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.pipeline.TrimLine
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.SessionType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UX 9.4's time chips and trim line (D62). */
class UiTrimTest {
    @Test
    fun `an 85-minute calendar budget is an extra chip shown first`() {
        UiTrim.chips(85, listOf(45, 60, 75), 180).map { it.label to it.source } shouldBe
            listOf("85 cal" to "calendar", "45" to "chip", "60" to "chip", "75" to "chip", "90+" to "chip")
    }

    @Test
    fun `a calendar budget equal to a standard chip relabels it`() {
        UiTrim.chips(75, listOf(45, 60, 75), 180).map { it.label } shouldBe listOf("45", "60", "75 cal", "90+")
        UiTrim.chips(null, listOf(45, 60, 75), 180).last().minutes shouldBe 180
    }

    private fun result(trimmed: List<TrimLine>): BuildResult {
        val a = Allocation(
            SessionType.LEGS, false, "Legs", true, null, emptyMap(), emptyMap(), emptyMap(), mapOf("quads" to 4.0), emptyMap(), emptyMap(),
            emptyMap(), 40.0, 40.0, emptyList(), emptyList(), emptyMap(), null,
        )
        return BuildResult(SessionType.LEGS, "Legs", a, null, emptyList(), 58.0, 60.0, trimmed, false, null)
    }

    @Test
    fun `the trim line names sets taken off and exercises left out`() {
        val names = mapOf("lateral_raise" to "Lateral raise", "calf_raise" to "Calf raise")
        UiTrim.trimLine(result(listOf(TrimLine("lateral_raise", 1, false), TrimLine("calf_raise", 3, true))), 60, 180) { names.getValue(it) } shouldBe
            "To fit 60 min: 1 set fewer on lateral raise; calf raise left out."
    }

    @Test
    fun `no trim line at 90+ or when nothing was cut`() {
        UiTrim.trimLine(result(listOf(TrimLine("calf_raise", 1, false))), 180, 180) { it }.shouldBeNull()
        UiTrim.trimLine(result(emptyList()), 45, 180) { it }.shouldBeNull()
    }
}
