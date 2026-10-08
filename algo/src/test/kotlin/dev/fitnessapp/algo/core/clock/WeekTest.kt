package dev.fitnessapp.algo.core.clock

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ClockDstTest / IsoWeekTest essentials (ALGORITHMS.md B1); the full GF-CLK cases are golden fixtures. */
class WeekTest {
    private val ams = ZoneId.of("Europe/Amsterdam")

    @Test
    fun `snapshot of the fixture now`() {
        val s = ClockSnapshot.at(Instant.parse("2026-10-07T08:00:00Z"), ams)
        s.localDate shouldBe LocalDate.of(2026, 10, 7)
        s.isoWeek shouldBe "2026-W41"
        s.tzOffsetMin shouldBe 120
    }

    @Test
    fun `week starts of ANDROID 24_1`() {
        Week.atLocal(LocalDate.of(2026, 10, 5), java.time.LocalTime.MIDNIGHT, ams).toEpochMilli() shouldBe 1791151200000
        Week.atLocal(LocalDate.of(2026, 10, 26), java.time.LocalTime.MIDNIGHT, ams).toEpochMilli() shouldBe 1792969200000
        Week.elapsedHours(Instant.ofEpochMilli(1792360800000), Instant.ofEpochMilli(1792969200000)) shouldBe 169.0
    }

    @Test
    fun `iso week of the year boundary`() {
        Week.isoWeek(LocalDate.of(2027, 1, 3)) shouldBe "2026-W53"
        Week.isoWeek(LocalDate.of(2027, 1, 4)) shouldBe "2027-W01"
        Week.isoWeekStart(LocalDate.of(2027, 1, 3)) shouldBe LocalDate.of(2026, 12, 28)
    }
}
