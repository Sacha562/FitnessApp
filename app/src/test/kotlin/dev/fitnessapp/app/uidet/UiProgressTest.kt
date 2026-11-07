package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * UI-11 (ALGORITHMS_PLANNING 19.6, UX 19.7): assistedNegativeAxis, prThreshold, neverRecomputes, bandIsSse1,
 * addedKgUsesSetSnapshot, gateSideBySide, gateTwoThresholds, repPr, holdPrMinGain, noPrFirstExposure, noPrWithPainReason.
 */
class UiProgressTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.8.json")).readText(Charsets.UTF_8),
    )
    private val day0 = LocalDate.parse("2026-10-05")
    private val bodyweight = ProgressExercise(fBw = 1.0, implementBaseKg = 0.0)

    private fun set(i: Int, setId: String = "x$i", side: String = "both", e1rm: Double? = null, added: Double? = null, load: Double? = null, reps: Int? = null,
                    hold: Double? = null, assist: Double? = null, pin: Double? = null, bw: Double? = 70.7, pain: Boolean = false, failed: Boolean = false, test: Boolean = false) =
        ProgressSet(
            setId, "s$i", day0.plusWeeks(i.toLong()).toEpochDay() * 86_400_000L, day0.plusWeeks(i.toLong()), side, test, e1rm, added, "normal",
            load, load, reps, hold, assist, pin, bw, failed, pain,
        )

    @Test
    fun `assistedNegativeAxis - assisted sets sit below zero on the added axis, with weighted sets on the same scale`() {
        // C2 fixture: assisted A pin 40 lb -> 46.06 kg per arm at BW 70.7 (added -24.64); weighted C +20 -> 90.70 system.
        val sets = listOf(set(0, e1rm = 46.06, added = -24.64), set(1, e1rm = 90.70, added = 20.0))
        val s = UiProgress.series(ProgressMetric.STRENGTH, sets, emptyList(), ProgressAxis.ADDED, bodyweight, params)
        s.points.map { it.y } shouldBe listOf(-24.64, 20.0)
        s.signed shouldBe true
        UiProgress.series(ProgressMetric.STRENGTH, sets, emptyList(), ProgressAxis.SYSTEM, bodyweight, params).points.map { it.y } shouldBe listOf(46.06, 90.7)
    }

    @Test
    fun `prThreshold - an e1RM best needs the minimum relative gain`() {
        val base = set(0, e1rm = 100.0)
        UiProgress.bests(listOf(base, set(1, e1rm = 100.4)), params).shouldBeEmpty()
        val b = UiProgress.bests(listOf(base, set(1, e1rm = 100.6)), params).single()
        b.kind shouldBe BestKind.E1RM
        b.previous shouldBe 100.0
        b.gain shouldBe 0.006
    }

    @Test
    fun `neverRecomputes - points are the engine's per-set values, whatever the load and reps say`() {
        // 60 kg x 10 would give ~80 kg by any formula; the engine logged 77.25 and that is what is drawn.
        val s = UiProgress.series(ProgressMetric.STRENGTH, listOf(set(0, e1rm = 77.25, added = 77.25, load = 60.0, reps = 10)), emptyList(), ProgressAxis.SYSTEM, ProgressExercise(0.0, 0.0), params)
        s.points.single().y shouldBe 77.25
    }

    @Test
    fun `bandIsSse1 - the band is the logged level and 80 % interval, unsmoothed`() {
        val levels = listOf(ProgressLevel("s0", 0, "both", 90.0, 85.0, 95.0, 70.7), ProgressLevel("s1", 1, "both", 92.5, 88.0, 97.0, 70.7))
        val s = UiProgress.series(ProgressMetric.STRENGTH, listOf(set(0, e1rm = 90.0)), levels, ProgressAxis.SYSTEM, bodyweight, params)
        s.band.map { Triple(it.m, it.lo, it.hi) } shouldBe listOf(Triple(90.0, 85.0, 95.0), Triple(92.5, 88.0, 97.0))
        val added = UiProgress.series(ProgressMetric.STRENGTH, listOf(set(0, e1rm = 90.0)), levels, ProgressAxis.ADDED, bodyweight, params)
        added.band.first().m shouldBe 19.3
    }

    @Test
    fun `addedKgUsesSetSnapshot - a later bodyweight change does not move old points`() {
        val old = set(0, e1rm = 90.7, added = 20.0, bw = 70.7)
        val later = set(1, e1rm = 92.0, added = 20.0, bw = 72.0)
        UiProgress.series(ProgressMetric.STRENGTH, listOf(old, later), emptyList(), ProgressAxis.ADDED, bodyweight, params).points.map { it.y } shouldBe listOf(20.0, 20.0)
    }

    @Test
    fun `gateSideBySide - the gate value and the x1_10 value are labelled next to the SSE-1 estimate`() {
        val g = UiProgress.Gate("Weighted pull-up e1RM", 39.38, LocalDate.parse("2026-10-11"), 42.82, 43.96, 70.7, null, null, null)
        val lines = UiProgress.gateLines(g, ProgressBand(0, "both", 40.8, 37.3, 44.4)) { "+%.1f kg".format(java.util.Locale.ROOT, it) }
        lines[0] shouldContain "Training estimate (SSE-1) +40.8 kg"
        lines[1] shouldContain "gate value +39.4 kg"
        lines[1] shouldContain "×1.10 rule +42.8 kg"
    }

    @Test
    fun `gateTwoThresholds - the test-day threshold and the forward threshold are both shown when they differ`() {
        val g = UiProgress.Gate("e1RM", 39.38, null, null, 43.96, 70.7, 43.63, 70.2, null)
        UiProgress.gateLines(g, null) { "+%.2f".format(java.util.Locale.ROOT, it) }.last() shouldBe "Gate target +43.96 at today's 70.7 kg · +43.63 at the test day's 70.2 kg"
        UiProgress.gateLines(g.copy(thresholdTest = 43.96, bwTest = 70.7), null) { "+%.2f".format(java.util.Locale.ROOT, it) }.last() shouldBe "Gate target +43.96 at today's 70.7 kg"
    }

    @Test
    fun `repPr - 8 reps at a load whose best was 7 is a rep best`() {
        val b = UiProgress.bests(listOf(set(0, load = 60.0, reps = 7), set(1, load = 60.0, reps = 8)), params).single()
        b.kind shouldBe BestKind.REPS
        b.value shouldBe 8.0
        b.previous shouldBe 7.0
        b.loadKg shouldBe 60.0
        // A load never used before is not a rep best.
        UiProgress.bests(listOf(set(0, load = 60.0, reps = 7), set(1, load = 62.5, reps = 8)), params).shouldBeEmpty()
    }

    @Test
    fun `holdPrMinGain - 1_0 to 1_5 s per side is a best, 1_0 to 1_4 s is not`() {
        UiProgress.bests(listOf(set(0, side = "L", hold = 1.0), set(1, side = "L", hold = 1.5)), params).single().kind shouldBe BestKind.HOLD
        UiProgress.bests(listOf(set(0, side = "L", hold = 1.0), set(1, side = "L", hold = 1.4)), params).shouldBeEmpty()
    }

    @Test
    fun `noPrFirstExposure - the first session of a metric never gives a best`() {
        UiProgress.bests(listOf(set(0, e1rm = 100.0, load = 80.0, reps = 5, hold = null)), params).shouldBeEmpty()
    }

    @Test
    fun `noPrWithPainReason - a set with pain never gives a best`() {
        UiProgress.bests(listOf(set(0, e1rm = 100.0), set(1, e1rm = 110.0, pain = true)), params).shouldBeEmpty()
    }

    @Test
    fun `an assistance best needs a lower pin with a clean rep`() {
        val a = UiProgress.bests(listOf(set(0, assist = 24.64, pin = 40.0, reps = 1), set(1, assist = 22.38, pin = 35.0, reps = 1)), params).single()
        a.kind shouldBe BestKind.ASSIST
        UiProgress.bests(listOf(set(0, assist = 24.64, pin = 40.0, reps = 1), set(1, assist = 22.38, pin = 35.0, reps = 1, failed = true)), params).shouldBeEmpty()
    }

    @Test
    fun `session bests are ordered by relative gain and capped`() {
        val bests = mapOf(
            "a" to listOf(PersonalBest(BestKind.REPS, "both", "s1", "x", day0, 8.0, 7.0, 60.0, 0.1429)),
            "b" to listOf(PersonalBest(BestKind.E1RM, "both", "s1", "y", day0, 101.0, 100.0, null, 0.01)),
            "c" to listOf(PersonalBest(BestKind.HOLD, "L", "s1", "z", day0, 12.0, 10.0, null, 0.2), PersonalBest(BestKind.HOLD, "L", "s0", "w", day0, 10.0, 9.0, null, 0.1111)),
        )
        UiProgress.sessionBests(bests, "s1", 2).map { it.first } shouldBe listOf("c", "a")
        UiProgress.sessionBests(bests, "s1", null).size shouldBe 3
    }
}
