package dev.fitnessapp.algo.accounting.readiness

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** ALGORITHMS_PLANNING 4.7. */
class ReadinessTest {
    private val params = TestParams.defaults()
    private val t0 = Instant.parse("2026-10-05T18:00:00Z")

    private fun quads(f: Double, at: Instant = t0, id: String = "d") = Dose(id, at, mapOf("quads" to f))

    @Test
    fun `decay48hQuads`() {
        val r = Readiness.evaluate(listOf(quads(4.0)), t0.plus(Duration.ofHours(48)), null, null, params)
        r.f.getValue("quads") shouldBe 0.8076
        r.r.getValue("quads") shouldBe 0.8991
        ReadinessOut.label(r.r.getValue("quads")) shouldBe "fresh"
    }

    @Test
    fun `nextReadyQuads`() {
        val at = Readiness.nextReady("quads", 0.85, listOf(quads(8.0)), t0, null, params)
        Duration.between(t0, at).toMillis() / 3_600_000.0 shouldBe (56.91 plusOrMinus 0.01)
        Readiness.nextReady("quads", 0.85, emptyList(), t0, null, params) shouldBe t0
    }

    @Test
    fun `dstSpan`() {
        // Sat 2026-10-24 20:00 CEST to Sun 2026-10-25 20:00 CET is 25 hours.
        val r = Readiness.evaluate(listOf(quads(8.0, Instant.parse("2026-10-24T18:00:00Z"))), Instant.parse("2026-10-25T19:00:00Z"), null, null, params)
        r.f.getValue("quads") shouldBe 3.4768
        r.r.getValue("quads") shouldBe 0.5654
    }

    @Test
    fun `windowCut`() {
        Readiness.evaluate(listOf(quads(8.0)), t0.plus(Duration.ofHours(241)), null, null, params).f.getValue("quads") shouldBe 0.0
        // A dose after t is not counted either.
        Readiness.evaluate(listOf(quads(8.0)), t0.minusSeconds(1), null, null, params).r.getValue("quads") shouldBe 1.0
    }

    @Test
    fun `sorenessModifier and checkInExpires`() {
        // R 0.9 for quads: F 0.8.
        val doses = listOf(quads(0.8))
        val sore = CheckIn(t0.minus(Duration.ofHours(2)), soreness = mapOf("quads" to "still_sore"))
        Readiness.evaluate(doses, t0, null, sore, params).r.getValue("quads") shouldBe 0.576
        Readiness.evaluate(doses, t0, null, sore.copy(ts = t0.minus(Duration.ofHours(13))), params).r.getValue("quads") shouldBe 0.9
        Readiness.evaluate(doses, t0, null, sore, params).modifiersApplied shouldBe listOf("soreness:quads")
    }

    @Test
    fun `elbowComposite and the G modifiers`() {
        // Rt elbow_medial 0.84: tissue 0.96 now.
        val r = Readiness.evaluate(listOf(Dose("e", t0, emptyMap(), mapOf("elbow_medial" to 0.96))), t0, null, null, params)
        r.tissueR.getValue("elbow") shouldBe 0.84
        r.tissueR.getValue("elbow_lateral") shouldBe 1.0
        val c = CheckIn(t0, readiness = "worse", sleepHours = 5.0, elbowScore = 1)
        val m = Readiness.evaluate(emptyList(), t0, null, c, params)
        m.g shouldBe 0.81
        m.tissueR.getValue("elbow") shouldBe 0.9
    }

    @Test
    fun `kappaScales`() {
        val r = Readiness.evaluate(listOf(quads(4.0)), t0.plus(Duration.ofHours(48)), mapOf("t30" to 1.2), null, params)
        r.r.getValue("quads") shouldBe 0.8682
        Readiness.classOf("hamstrings", params) shouldBe "t34"
    }

    @Test
    fun `orderIndependent`() {
        val doses = listOf(
            quads(3.0, t0, "a"), quads(1.7, t0.plusSeconds(3600), "b"),
            Dose("c", t0.plusSeconds(7200), mapOf("chest" to 2.2, "quads" to 0.3), mapOf("knee" to 0.4), 0.2),
        )
        val t = t0.plus(Duration.ofHours(30))
        Readiness.evaluate(doses.reversed(), t, null, null, params) shouldBe Readiness.evaluate(doses, t, null, null, params)
    }
}
