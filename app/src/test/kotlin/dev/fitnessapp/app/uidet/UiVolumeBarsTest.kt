package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UI-09 (ALGORITHMS_PLANNING 19.4, UX 25.6): GF-UX-13, coveredRowNoBar, deloadUsesEffectiveTarget, sortOrder. */
class UiVolumeBarsTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.2.json")).readText(Charsets.UTF_8),
    )

    private fun g(group: String, status: String, target: Double, mrv: Double, done: Double, planned: Double, deficit: Boolean = false) =
        VolumeGroup(group, status, false, done, planned, target, target, mrv, deficit)

    /** Wednesday 7 October, deload week, Balanced, lats covered; deficits from GF-UX-12 (chest STRONG, quads and hamstrings WARN). */
    private val wed = listOf(
        g("chest", "improve", 6.0, 12.0, 2.0, 2.0, deficit = true),
        g("side_delts", "improve", 6.0, 12.0, 1.0, 2.0),
        g("quads", "improve", 6.0, 12.0, 0.0, 3.0, deficit = true),
        g("hamstrings", "improve", 6.0, 10.0, 0.0, 0.0, deficit = true),
        g("calves", "maintain", 2.0, 8.0, 0.0, 2.0),
        g("abs", "maintain", 2.0, 2.5, 3.0, 0.0),
        g("lats", "covered", 0.0, 0.0, 9.5, 0.0),
        g("abductors", "skip", 0.0, 0.0, 0.0, 0.0),
    )

    @Test
    fun `GF-UX-13 volume bars`() {
        val b = UiVolumeBars.bars(wed, params)
        b.rows.map { it.group } shouldBe listOf("hamstrings", "side_delts", "quads", "chest", "calves", "abs")
        b.rows.map { it.text } shouldBe listOf("0 / 6", "1 (+2) / 6", "0 (+3) / 6", "2 (+2) / 6", "0 (+2) / 2", "3 / 2")
        b.rows.map { it.fillDone to it.fillPlanned } shouldBe listOf(
            0.0 to 0.0, 0.1667 to 0.3333, 0.0 to 0.5, 0.3333 to 0.3334, 0.0 to 1.0, 1.25 to 0.0,
        )
        b.rows.map { it.state } shouldBe listOf(
            VolumeState.DEFICIT, VolumeState.OK, VolumeState.DEFICIT, VolumeState.DEFICIT, VolumeState.OK, VolumeState.OVER,
        )
        b.rows.last().mrvMark shouldBe 1.25
        b.covered.map { it.group } shouldBe listOf("lats")
        b.skipped shouldBe listOf("abductors")
    }

    @Test
    fun coveredRowNoBar() {
        // A covered group above its MRV gets no row and no OVER: challenge volume is exempt (D6).
        val b = UiVolumeBars.bars(listOf(g("lats", "covered", 0.0, 22.0, 30.0, 4.0)), params)
        b.rows shouldBe emptyList()
        b.covered.single().group shouldBe "lats"
    }

    @Test
    fun deloadUsesEffectiveTarget() {
        val r = UiVolumeBars.bars(listOf(VolumeGroup("chest", "improve", false, 3.0, 0.0, 6.0, 12.0, 20.0, false)), params).rows.single()
        r.text shouldBe "3 / 6"
        r.fillDone shouldBe 0.5
        // The normal target is the faint tick, capped like the fills.
        r.normalMark shouldBe 1.25
    }

    @Test
    fun sortOrder() {
        val b = UiVolumeBars.bars(
            listOf(
                g("calves", "maintain", 4.0, 8.0, 0.0, 0.0),
                g("quads", "improve", 12.0, 20.0, 6.0, 0.0),
                g("chest", "improve", 12.0, 20.0, 6.0, 0.0),
                g("biceps", "improve", 12.0, 20.0, 2.0, 0.0),
            ),
            params,
        )
        // Improve by share (biceps 0.17, then chest and quads at 0.5 in canonical order), then maintain.
        b.rows.map { it.group } shouldBe listOf("biceps", "chest", "quads", "calves")
    }
}
