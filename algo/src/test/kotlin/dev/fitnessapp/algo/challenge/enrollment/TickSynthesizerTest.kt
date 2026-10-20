package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.OapFixture.zone
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** GF-CE-10 .. 13b. */
class TickSynthesizerTest {
    private val closeMin = OapFixture.params().int("challenge.enrollment.week_close_local_time")

    private fun state(lastTick: String, boundary: String, planned: String? = null): EnrollmentState =
        OapFixture.start().copy(
            lastTickDate = LocalDate.parse(lastTick),
            lastWeekBoundary = LocalDate.parse(boundary),
            plannedTest = planned?.let { PlannedTest(LocalDate.parse(it), "blockRelative") } ?: PlannedTest.NONE,
        )

    private fun keys(out: TickSynthesizer.Output) = out.events.map { e ->
        val p = e.payload
        val key = when (p) {
            is EventPayload.DayTick -> "day.tick:${p.date}"
            is EventPayload.TestDue -> "test.due:${p.date}"
            is EventPayload.WeekBoundary -> "${e.type}:${p.isoWeek}"
            else -> e.type
        }
        key to e.occurredAt.toString()
    }

    @Test
    fun `GF-CE-10 one tick for one new day`() {
        val out = TickSynthesizer.synthesize(state("2026-10-06", "2026-10-05"), Instant.parse("2026-10-07T10:00:00Z"), zone, closeMin)
        keys(out) shouldBe listOf("day.tick:2026-10-07" to "2026-10-06T22:00:00.002Z")
    }

    @Test
    fun `GF-CE-11 ticks, test due and the week boundary`() {
        val out = TickSynthesizer.synthesize(
            state("2026-10-07", "2026-10-05", planned = "2026-10-11"), Instant.parse("2026-10-12T10:00:00Z"), zone, closeMin,
        )
        keys(out) shouldBe listOf(
            "day.tick:2026-10-08" to "2026-10-07T22:00:00.002Z",
            "day.tick:2026-10-09" to "2026-10-08T22:00:00.002Z",
            "day.tick:2026-10-10" to "2026-10-09T22:00:00.002Z",
            "day.tick:2026-10-11" to "2026-10-10T22:00:00.002Z",
            "test.due:2026-10-11" to "2026-10-10T22:00:00.003Z",
            "day.tick:2026-10-12" to "2026-10-11T22:00:00.002Z",
            "week.closed:2026-W41" to "2026-10-12T02:00:00Z",
            "week.started:2026-W42" to "2026-10-12T02:00:00.001Z",
        )
        out.catchupTicks shouldBe 5
    }

    @Test
    fun `GF-CE-12 the 25-hour day yields one tick`() {
        val out = TickSynthesizer.synthesize(state("2026-10-23", "2026-10-19"), Instant.parse("2026-10-26T12:00:00Z"), zone, closeMin)
        keys(out) shouldBe listOf(
            "day.tick:2026-10-24" to "2026-10-23T22:00:00.002Z",
            "day.tick:2026-10-25" to "2026-10-24T22:00:00.002Z",
            "day.tick:2026-10-26" to "2026-10-25T23:00:00.002Z",
            "week.closed:2026-W43" to "2026-10-26T03:00:00Z",
            "week.started:2026-W44" to "2026-10-26T03:00:00.001Z",
        )
    }

    @Test
    fun `GF-CE-13 two devices synthesize identical ids`() {
        val s = state("2026-10-24", "2026-10-19")
        val a = TickSynthesizer.synthesize(s, Instant.parse("2026-10-25T08:00:00Z"), zone, closeMin)
        val b = TickSynthesizer.synthesize(s, Instant.parse("2026-10-25T20:00:00Z"), zone, closeMin)
        a.events.map { it.id } shouldBe b.events.map { it.id }
        a.events.single().id shouldBe TickSynthesizer.eventId(OapFixture.enrollmentId, "day.tick:2026-10-25")
    }

    @Test
    fun `GF-CE-13b no future boundary events`() {
        val s0 = state("2026-10-11", "2026-10-05")
        val first = TickSynthesizer.synthesize(s0, Instant.parse("2026-10-11T23:00:00Z"), zone, closeMin)
        keys(first).map { it.first } shouldBe listOf("day.tick:2026-10-12")
        val s1 = s0.copy(lastTickDate = LocalDate.parse("2026-10-12"))
        val second = TickSynthesizer.synthesize(s1, Instant.parse("2026-10-12T03:00:00Z"), zone, closeMin)
        keys(second).map { it.first } shouldBe listOf("week.closed:2026-W41", "week.started:2026-W42")
    }
}
