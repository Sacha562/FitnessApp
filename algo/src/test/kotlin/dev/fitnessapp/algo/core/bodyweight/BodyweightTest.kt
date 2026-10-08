package dev.fitnessapp.algo.core.bodyweight

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** B3 examples: EWMA, day merge, staleness at 14 and 15 days, the test-day raw value, no data. */
class BodyweightTest {
    private val params = TestParams.defaults()
    private val oct4 = BwObservation("o1", Instant.parse("2026-10-04T07:00:00Z"), LocalDate.parse("2026-10-04"), 70.7)

    private fun session(date: String, kind: String = "gym", id: String = "s-$date", firstSet: Instant? = null) =
        BwSession(id, kind, Instant.parse("${date}T17:45:00Z"), LocalDate.parse(date), firstSet)

    @Test
    fun `one weigh-in carries until stale after 14 days`() {
        Bodyweight.sessionSnapshot(session("2026-10-18"), listOf(oct4), null, params) shouldBe
            BwSnapshot(70.7, BwSource.EWMA, false, LocalDate.parse("2026-10-04"))
        Bodyweight.sessionSnapshot(session("2026-10-19"), listOf(oct4), null, params).stale shouldBe true
    }

    @Test
    fun `test-day weigh-in is raw for the session and enters the EWMA afterwards`() {
        val test = session("2026-10-11", kind = "test", id = "test1", firstSet = Instant.parse("2026-10-11T11:20:00Z"))
        val weighIn = BwObservation("o2", Instant.parse("2026-10-11T11:05:00Z"), LocalDate.parse("2026-10-11"), 70.9, "test1")
        val snap = Bodyweight.sessionSnapshot(test.copy(start = Instant.parse("2026-10-11T11:00:00Z")), listOf(oct4, weighIn), null, params)
        snap.valueKg shouldBe 70.9
        snap.source shouldBe BwSource.TEST_WEIGHIN
        Bodyweight.gate(test.copy(start = Instant.parse("2026-10-11T11:00:00Z")), listOf(oct4, weighIn), params) shouldBe 70.9
        Bodyweight.ewma(listOf(oct4, weighIn), Instant.parse("2026-10-12T00:00:00Z"), params) shouldBe 70.76
    }

    @Test
    fun `a weigh-in after the first set is not the test value`() {
        val test = session("2026-10-11", kind = "test", id = "t", firstSet = Instant.parse("2026-10-11T11:20:00Z"))
            .copy(start = Instant.parse("2026-10-11T11:00:00Z"))
        val late = BwObservation("o3", Instant.parse("2026-10-11T12:00:00Z"), LocalDate.parse("2026-10-11"), 71.5, "t")
        val snap = Bodyweight.sessionSnapshot(test, listOf(oct4, late), null, params)
        snap.source shouldBe BwSource.EWMA
        snap.valueKg shouldBe 70.7
    }

    @Test
    fun `two weigh-ins on one day count once`() {
        val a = BwObservation("a", Instant.parse("2026-10-05T06:00:00Z"), LocalDate.parse("2026-10-05"), 70.0)
        val b = BwObservation("b", Instant.parse("2026-10-05T20:00:00Z"), LocalDate.parse("2026-10-05"), 71.0)
        // day means 70.7 then 70.5: 70.7 + 0.3 * (70.5 - 70.7) = 70.64
        Bodyweight.ewma(listOf(b, oct4, a), Instant.parse("2026-10-06T00:00:00Z"), params) shouldBe 70.64
    }

    @Test
    fun `out-of-range values are ignored, fallback and none paths`() {
        val bad = BwObservation("x", Instant.parse("2026-10-03T07:00:00Z"), LocalDate.parse("2026-10-03"), 7.07)
        Bodyweight.ewma(listOf(bad, oct4), Instant.parse("2026-10-05T00:00:00Z"), params) shouldBe 70.7
        Bodyweight.sessionSnapshot(session("2026-10-01"), listOf(oct4), 72.0, params) shouldBe
            BwSnapshot(72.0, BwSource.FALLBACK, true, null)
        Bodyweight.sessionSnapshot(session("2026-10-01"), emptyList(), null, params) shouldBe
            BwSnapshot(null, BwSource.NONE, true, null)
    }
}
