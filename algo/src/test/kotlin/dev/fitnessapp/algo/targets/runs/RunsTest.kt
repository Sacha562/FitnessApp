package dev.fitnessapp.algo.targets.runs

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ALGORITHMS_PLANNING 6.7. */
class RunsTest {
    private val params = TestParams.defaults()
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun run(start: String, minutes: Long, intensity: String = "easy", long: Boolean = false): Run {
        val s = Instant.parse(start)
        return Run("r", s, s.plusSeconds(minutes * 60), minutes.toDouble(), intensity, long)
    }

    @Test
    fun `easy40`() {
        val d = Runs.dose(run("2026-10-05T11:00:00Z", 40), params)
        d.fat shouldBe mapOf("calves" to 2.4, "glutes" to 1.2, "hamstrings" to 1.2, "quads" to 2.0)
        d.sys shouldBe 0.5
        d.t shouldBe Instant.parse("2026-10-05T11:40:00Z")
    }

    @Test
    fun `longIntervals90`() {
        Runs.dose(run("2026-10-05T11:00:00Z", 90, "intervals"), params).fat.getValue("quads") shouldBe 10.53
        // The long flag alone applies the factor too.
        Runs.dose(run("2026-10-05T11:00:00Z", 40, long = true), params).fat.getValue("quads") shouldBe 2.6
    }

    @Test
    fun `legsAfterRun6h and upperNoPenalty`() {
        val r = run("2026-10-05T10:00:00Z", 60) // ends 11:00Z = 13:00 CEST
        val s = Instant.parse("2026-10-05T17:00:00Z")
        Runs.penalty(s, s.plusSeconds(3600), 4.0, listOf(r), "low", params) shouldBe RunPenalty(0.5, listOf("RUN_NEAR"))
        Runs.penalty(Instant.parse("2026-10-05T13:00:00Z"), Instant.parse("2026-10-05T14:00:00Z"), 0.0, listOf(r), "low", params).value shouldBe 0.0
    }

    @Test
    fun `hardRunPriorityLow`() {
        val legsEnd = Instant.parse("2026-10-05T18:00:00Z")
        val tempo = run("2026-10-07T00:00:00Z", 45, "tempo") // 30 h after the session
        fun pen(p: String) = Runs.penalty(legsEnd.minusSeconds(3600), legsEnd, 4.0, listOf(tempo), p, params).value
        pen("low") shouldBe 0.0
        pen("medium") shouldBe 0.4
        pen("high") shouldBe 0.8
    }

    @Test
    fun `runOnRestDay`() {
        val r = run("2026-10-10T08:00:00Z", 40)
        Runs.restDayWarnings(listOf(r), listOf(RunRestDay(LocalDate.parse("2026-10-10"), "TEST")), zone) shouldBe listOf(RunWarning("RUN_ON_REST_DAY", "r", "TEST"))
        Runs.restDayWarnings(listOf(r), listOf(RunRestDay(LocalDate.parse("2026-10-09"), "TEST")), zone) shouldBe emptyList()
    }
}
