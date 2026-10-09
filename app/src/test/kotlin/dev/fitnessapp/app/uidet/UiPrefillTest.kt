package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.load.PlateCount
import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/** ALGORITHMS_PLANNING 19.7 tests and UX GF-UX-06, GF-UX-07, GF-UX-15. */
class UiPrefillTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.1.json")).readText(Charsets.UTF_8),
    )
    private val pull = ExerciseLoadAttrs(1.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.BODYWEIGHT_PLUS_EXTERNAL))
    private val beltScheme = LoadScheme.Belt(
        listOf(PlateCount(20.0, 2), PlateCount(10.0, 2), PlateCount(5.0, 1), PlateCount(2.5, 1)), 60.0, 6, false,
    )
    private val belt = Loadables.enumerate(beltScheme, Loadables.Context(pull, 70.7), params)
    private val machine = LoadScheme.PinAssist("lb", 5.0, 0.0, 100.0, 6.5, 1.0)
    private val oneArm = ExerciseLoadAttrs(1.0, 0.0, Laterality.UNILATERAL, listOf(LoadMode.ASSISTED_MACHINE))
    private val pins = Loadables.enumerate(machine, Loadables.Context(oneArm, 70.7), params)

    private fun beltAt(kg: Double): LoadPoint = belt.first { it.addedKg == kg }
    private fun pinAt(lb: Double): LoadPoint = pins.first { it.assistPinNative == lb }

    @Test
    fun prefillOrder() {
        val slot = SetSlot(2, "both", LoadMode.BODYWEIGHT_PLUS_EXTERNAL)
        val default = SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, native = 0.0, reps = 8)
        val hist = listOf(
            HistorySet("s1", Instant.parse("2026-10-01T18:00:00Z"), 1, "both", SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 20.0, 5)),
            HistorySet("s1", Instant.parse("2026-10-01T18:00:00Z"), 2, "both", SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 20.0, 4)),
            HistorySet("s0", Instant.parse("2026-09-28T18:00:00Z"), 2, "both", SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 17.5, 5)),
        )
        val adj = SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 22.5, 4)
        val target = SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 25.0, 3)
        UiPrefill.prefill(slot, PrefillContext(adj, target, hist, default = default)).value.source shouldBe PrefillSource.ACCEPTED_ADJUSTMENT
        UiPrefill.prefill(slot, PrefillContext(null, target, hist, default = default)).value.values.native shouldBe 25.0
        val last = UiPrefill.prefill(slot, PrefillContext(null, null, hist, default = default)).value
        last.source shouldBe PrefillSource.LAST_SAME_INDEX
        last.values shouldBe SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 20.0, 4)
        val prev = UiPrefill.prefill(SetSlot(3, "both", LoadMode.BODYWEIGHT_PLUS_EXTERNAL), PrefillContext(history = hist, default = default)).value
        prev.source shouldBe PrefillSource.LAST_PREV_INDEX
        prev.values.reps shouldBe 4
        UiPrefill.prefill(slot, PrefillContext(default = default)).value.source shouldBe PrefillSource.DEFAULT
    }

    @Test
    fun todayChangeCarriesForward() {
        val m = LoadMode.EXTERNAL
        val hist = listOf(
            HistorySet("s1", Instant.parse("2026-10-01T18:00:00Z"), 1, "both", SetValues(m, 55.0, 8)),
            HistorySet("s1", Instant.parse("2026-10-01T18:00:00Z"), 2, "both", SetValues(m, 55.0, 8)),
        )
        val slot = SetSlot(2, "both", m)
        val changed = UiPrefill.prefill(slot, PrefillContext(history = hist, todayPrevious = SetValues(m, 60.0, 8), default = SetValues(m, 20.0, 8))).value
        changed.values shouldBe SetValues(m, 60.0, 8)
        changed.source shouldBe PrefillSource.LAST_PREV_INDEX
        val same = UiPrefill.prefill(slot, PrefillContext(history = hist, todayPrevious = SetValues(m, 55.0, 8), default = SetValues(m, 20.0, 8))).value
        same.source shouldBe PrefillSource.LAST_SAME_INDEX
        // no history at all: today's previous set is the best guess
        UiPrefill.prefill(slot, PrefillContext(todayPrevious = SetValues(m, 40.0, 10), default = SetValues(m, 20.0, 8))).value.values.native shouldBe 40.0
    }

    @Test
    fun noHistoryAcrossLoadModes() {
        val bwSets = listOf(HistorySet("s1", Instant.parse("2026-10-01T18:00:00Z"), 1, "both", SetValues(LoadMode.BODYWEIGHT, null, 12)))
        val slot = SetSlot(1, "both", LoadMode.BODYWEIGHT_PLUS_EXTERNAL)
        val target = SetValues(LoadMode.BODYWEIGHT_PLUS_EXTERNAL, 5.0, 6)
        val p = UiPrefill.prefill(slot, PrefillContext(target = target, history = bwSets, default = SetValues(slot.loadMode, 0.0, 8))).value
        p.source shouldBe PrefillSource.ENGINE_TARGET
        p.values.reps shouldBe 6
        val noTarget = UiPrefill.prefill(slot, PrefillContext(history = bwSets, default = SetValues(slot.loadMode, 0.0, 8))).value
        noTarget.source shouldBe PrefillSource.DEFAULT
        noTarget.values.reps shouldBe 8
    }

    @Test
    fun noRirPrefill() {
        // SetValues has no RIR field: RIR chips are the log action and are never prefilled (D9).
        SetValues::class.java.declaredFields.map { it.name }.any { it.contains("rir", ignoreCase = true) } shouldBe false
    }

    @Test
    fun rightCappedAtLeft() {
        val slot = SetSlot(1, "R", LoadMode.ASSISTED_MACHINE)
        val ctx = PrefillContext(
            target = SetValues(LoadMode.ASSISTED_MACHINE, 40.0, 8), default = SetValues(LoadMode.ASSISTED_MACHINE, 40.0, 3),
            sideRule = true, leftLoggedReps = 6,
        )
        val p = UiPrefill.prefill(slot, ctx).value
        p.values.reps shouldBe 6
        p.sideCapApplied shouldBe true
        // GF-UX-07: target 3/arm, left logged 2 -> right prefill 2.
        val gf = UiPrefill.prefill(slot, ctx.copy(target = SetValues(LoadMode.ASSISTED_MACHINE, 40.0, 3), leftLoggedReps = 2)).value
        gf.values.reps shouldBe 2
        UiPrefill.prefill(SetSlot(1, "L", LoadMode.ASSISTED_MACHINE), ctx).value.sideCapApplied shouldBe false
    }

    @Test
    fun beltSteps() {
        UiPrefill.stepLoad(belt, beltAt(22.5), true, params).addedKg shouldBe 25.0
        UiPrefill.stepLoad(belt, beltAt(22.5), false, params).addedKg shouldBe 20.0
        UiPrefill.stepLoad(belt, beltAt(27.5), true, params).addedKg shouldBe 30.0
        UiPrefill.stepLoad(belt, beltAt(47.5), false, params).addedKg shouldBe 45.0
        beltAt(47.5).plateBreakdown shouldBe listOf(20.0, 20.0, 5.0, 2.5)
        UiPrefill.stepLoad(belt, belt.last(), true, params) shouldBe belt.last()
    }

    @Test
    fun beltStep() {
        val owned = LoadScheme.Belt(listOf(1.25, 2.5, 5.0, 10.0, 20.0).map { PlateCount(it, 1) }, 60.0, 4, false)
        val w = Loadables.enumerate(owned, Loadables.Context(pull, 70.7), params)
        UiPrefill.stepLoad(w, w.first { it.addedKg == 22.5 }, true, params).addedKg shouldBe 23.75
    }

    @Test
    fun keypadSnapping() {
        (UiPrefill.snap(23.0, beltScheme, 1, belt, params) as SnapResult.Snapped).let {
            it.point.addedKg shouldBe 22.5
            it.changed shouldBe true
        }
        (UiPrefill.snap(23.75, beltScheme, 1, belt, params) as SnapResult.Snapped).point.addedKg shouldBe 22.5
        (UiPrefill.snap(22.5, beltScheme, 1, belt, params) as SnapResult.Snapped).changed shouldBe false
        (UiPrefill.snap(61.0, beltScheme, 1, belt, params) as SnapResult.Snapped).point.addedKg shouldBe 60.0
        UiPrefill.snap(63.0, beltScheme, 1, belt, params).shouldBeInstanceOf<SnapResult.OutOfRange>()
        val (lo, hi) = UiPrefill.keypadChips(23.0, beltScheme, 1, belt, params)
        lo!!.addedKg shouldBe 22.5
        hi!!.addedKg shouldBe 25.0
    }

    @Test
    fun assistedTieMoreAssist() {
        (UiPrefill.snap(37.5, machine, 1, pins, params) as SnapResult.Snapped).point.assistPinNative shouldBe 40.0
        (UiPrefill.snap(42.0, machine, 1, pins, params) as SnapResult.Snapped).point.assistPinNative shouldBe 40.0
        (UiPrefill.snap(42.5, machine, 1, pins, params) as SnapResult.Snapped).point.assistPinNative shouldBe 45.0
        UiPrefill.stepLoad(pins, pinAt(40.0), harder = true, params = params).assistPinNative shouldBe 35.0
        UiPrefill.stepLoad(pins, pinAt(40.0), harder = false, params = params).assistPinNative shouldBe 45.0
        pinAt(40.0).assistKg shouldBe 24.64
        pinAt(40.0).wSys shouldBe 46.06
    }

    @Test
    fun deviations() {
        val target = SetTarget(load = beltAt(22.5), repsLo = 4, repsHi = 4)
        UiPrefill.deviation(SetDraft(beltAt(25.0), 4), target, 2.5, params) shouldBe
            Deviation(DeviationDirection.UP, listOf(FieldDeviation("load", DeviationDirection.UP)))
        UiPrefill.deviation(SetDraft(beltAt(22.5), 3), target, 2.5, params) shouldBe
            Deviation(DeviationDirection.DOWN, listOf(FieldDeviation("reps", DeviationDirection.DOWN)))
        UiPrefill.deviation(SetDraft(beltAt(25.0), 3), target, 2.5, params).direction shouldBe DeviationDirection.DOWN
        UiPrefill.deviation(SetDraft(beltAt(22.5), 4), target, 2.5, params) shouldBe Deviation.NONE
    }

    @Test
    fun postRirDelta() {
        UiPrefill.postRirDeviation(4.0, 2.0, params).direction shouldBe DeviationDirection.UP
        UiPrefill.postRirDeviation(3.0, 2.0, params) shouldBe Deviation.NONE
        UiPrefill.postRirDeviation(1.0, 2.0, params) shouldBe Deviation.NONE
        UiPrefill.postRirDeviation(0.0, 2.0, params).direction shouldBe DeviationDirection.DOWN
        UiPrefill.postRirDeviation(0.0, null, params) shouldBe Deviation.NONE
    }

    @Test
    fun holdRangeLowerBound() {
        UiPrefill.deviation(SetDraft(holdS = 9.0), SetTarget(holdLoS = 10.0), null, params) shouldBe Deviation.NONE
        UiPrefill.deviation(SetDraft(holdS = 4.5), SetTarget(holdLoS = 6.0), null, params).direction shouldBe DeviationDirection.DOWN
        UiPrefill.deviation(SetDraft(holdS = 5.0), SetTarget(holdLoS = 6.0), null, params) shouldBe Deviation.NONE
    }

    @Test
    fun sideRuleAdvisory() {
        UiPrefill.deviation(SetDraft(reps = 3), SetTarget(repsLo = 3, repsHi = 3), null, params, sideRuleLeftReps = 2) shouldBe
            Deviation(DeviationDirection.UP, listOf(FieldDeviation("side_rule", DeviationDirection.UP)))
    }

    @Test
    fun holdStepBelow10() {
        UiPrefill.holdStep(8.0, params) shouldBe 0.5
        UiPrefill.holdStep(12.0, params) shouldBe 1.0
        UiPrefill.stepHold(6.0, true, 8.0, params) shouldBe 6.5
        UiPrefill.stepHold(0.0, false, 8.0, params) shouldBe 0.0
    }

    @Test
    fun holdResultPrefill() {
        UiPrefill.holdResult(7.4, 6.0, params) shouldBe 6.0
        UiPrefill.holdResult(4.73, 6.0, params) shouldBe 4.5
        UiPrefill.holdResult(16.73, null, params, stepBasisS = 15.0) shouldBe 16.0
    }

    @Test
    fun rirLayout() {
        UiPrefill.rirChipLayout(393.0, 1.0) shouldBe ChipLayout(false, 56.5)
        UiPrefill.rirChipLayout(393.0, 1.3).grid shouldBe false
        UiPrefill.rirChipLayout(393.0, 1.5).grid shouldBe true
        UiPrefill.rirChipLayout(360.0, 1.0) shouldBe ChipLayout(true, 51.0)
    }

    @Test
    fun longPress() {
        UiPrefill.longPressMultiplier(1, params) shouldBe 1
        UiPrefill.longPressMultiplier(8, params) shouldBe 1
        UiPrefill.longPressMultiplier(9, params) shouldBe 5
    }
}
