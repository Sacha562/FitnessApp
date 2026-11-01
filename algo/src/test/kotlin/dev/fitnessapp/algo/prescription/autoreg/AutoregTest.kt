package dev.fitnessapp.algo.prescription.autoreg

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.estimation.EstimationFixtures.FACTS
import dev.fitnessapp.algo.estimation.EstimationFixtures.LEG_PRESS
import dev.fitnessapp.algo.estimation.EstimationFixtures.WPU
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.rx.RxEstimate
import dev.fitnessapp.algo.prescription.rx.RxInput
import dev.fitnessapp.algo.prescription.rx.RxLast
import dev.fitnessapp.algo.prescription.rx.RxMode
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.algo.prescription.rx.Scheme
import dev.fitnessapp.algo.prescription.rx.SchemeKind
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.TreeMap

/** AUTO-1 (ALGORITHMS.md B13): the worked example, GF-AUTO-01 and 02, triggers, limit, RULE shadow, bounds, transfer. */
class AutoregTest {
    private val params = TestParams.defaults()
    private val bw = 70.7
    private val belt = (0..48).map { bw + it * 1.25 }
    private val stack = (1..60).map { it * 5.0 }

    private fun scheme(kind: SchemeKind, lo: Int, hi: Int, q: Double) =
        Scheme(kind, lo, hi, q, null, null, kind == SchemeKind.FIXED, TreeMap(), emptyList(), emptyList())

    /** 6 x 2 at +27.5 kg (C3.1, B13.6): M 109.94, k 30.63, q* 2, rest 180. */
    private val wpuInput = RxInput(
        exerciseId = WPU.exerciseId, side = "both", isolation = false, highRisk = true, bodyweightOnly = false,
        scheme = scheme(SchemeKind.FIXED, 2, 2, 2.0), sets = 6, restsS = listOf(180.0), loadables = belt, k = 30.63,
        estimate = RxEstimate(Det.ln(109.94), 0.0),
    )

    /** Leg press 3 x 8-10 (C3.3, GF-RX-03): M 158.67 unrounded, k 45, w_last 120. */
    private val legInput = RxInput(
        exerciseId = LEG_PRESS.exerciseId, side = "both", isolation = false, highRisk = false, bodyweightOnly = false,
        scheme = scheme(SchemeKind.RANGE, 8, 10, 2.0), sets = 3, restsS = listOf(150.0), loadables = stack, k = 45.0,
        estimate = RxEstimate(Det.ln(120.0 * (1.0 + 14.5 / 45.0)), 0.0), last = RxLast(120.0, listOf(12), listOf(3.5)),
    )

    private fun run(input: RxInput, plan: RxOutput, sets: List<LoggedSet>, pLL: Double = 0.0016, cards: Int = 0, rule: Boolean = false, technique: Int = 0) =
        Autoreg.evaluate(
            AutoregInput(
                rx = input.copy(estimate = RxEstimate(input.estimate!!.lPred, pLL)), plan = plan,
                logged = Sse.prepare(sets, FACTS, RirCal.initial(params), params).filter { it.set.setKind != "warmup" },
                cardsShown = cards, ruleItem = rule, techniqueEvents = technique,
            ),
            params.fork(),
        )

    private fun wpuSet(n: Int, reps: Int, rir: Double?, w: Double = bw + 27.5, reason: String? = null) =
        set("w$n", "2026-10-27T17:0$n:00Z", w, reps, rir, reasonCode = reason, restBeforeS = if (n > 1) 180.0 else null)

    private fun legSet(n: Int, reps: Int, rir: Double?, w: Double = 125.0, lowerBound: Boolean = false, reason: String? = null) =
        set("l$n", "2026-10-27T17:0$n:00Z", w, reps, rir, exercise = LEG_PRESS.exerciseId, loadMode = LoadMode.EXTERNAL,
            lowerBound = lowerBound, reasonCode = reason, restBeforeS = if (n > 1) 150.0 else null)

    @Test
    fun `GF-AUTO-01 the worked example raises delta_ex but shows no card`() {
        val plan = Rx.modelMode(wpuInput, params.fork())
        plan.sets.first().wSys shouldBe 98.2
        val r = run(wpuInput, plan, listOf(wpuSet(1, 2, 4.0)))
        r.deltaEx shouldBe (0.0173 plusOrMinus 0.0002)
        r.deltas.single()!! shouldBe (1.84 plusOrMinus 0.01)
        r.trigger.shouldBeNull()
        r.rirNextCur!! shouldBe (3.14 plusOrMinus 0.01)
        r.card shouldBe false
    }

    @Test
    fun `GF-AUTO-02 a 5+ chip on the leg press proposes reps 10 and 8`() {
        val plan = Rx.modelMode(legInput, params.fork())
        plan.sets.map { it.targetReps } shouldBe listOf(10, 9, 8)
        val r = run(legInput, plan, listOf(legSet(1, 10, 5.0, lowerBound = true)))
        r.trigger shouldBe "first_set"
        r.deltas.single()!! shouldBe (2.38 plusOrMinus 0.01)
        r.deltaEx shouldBe (0.0093 plusOrMinus 0.0002)
        r.rirNextCur!! shouldBe (3.16 plusOrMinus 0.01)
        r.hysteresis!!.loadChangeAllowed shouldBe false
        r.kind shouldBe SuggestionKind.REPS
        r.proposed.map { it.targetReps } shouldBe listOf(10, 8)
        r.proposed.map { it.rirPred } shouldBe listOf(2.16, 2.38)
        r.card shouldBe true
    }

    @Test
    fun `a RULE item updates delta_ex in shadow and never shows a card`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 10, 5.0, lowerBound = true)), rule = true)
        r.kind shouldBe SuggestionKind.REPS
        r.card shouldBe false
    }

    @Test
    fun `one card per exercise and session`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 10, 5.0, lowerBound = true)), cards = 1)
        r.kind.shouldBeNull()
        r.card shouldBe false
    }

    @Test
    fun `reps short by two fires and a much harder set drops the load`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 7, 0.0)))
        r.trigger shouldBe "first_set"
        r.kind shouldBe SuggestionKind.LOAD
        (r.wNew!! < 125.0) shouldBe true
        // Safety drop: the next set at 125 kg would leave less than q* - 1 in reserve, so the -8 % floor does not bind.
        r.hysteresis!!.safetyDrop shouldBe true
    }

    @Test
    fun `a raise stays within +6 % of the planned load and +5 % per suggestion`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 10, 8.0), legSet(2, 10, 8.0)), pLL = 0.01)
        r.kind shouldBe SuggestionKind.LOAD
        (r.wNew!! <= 125.0 * 1.05 + 1e-9) shouldBe true
    }

    @Test
    fun `two consecutive short sets at RIR 0 advise a stop`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 7, 0.0), legSet(2, 6, 0.0)), cards = 0)
        r.kind shouldBe SuggestionKind.STOP
    }

    @Test
    fun `technique events advise a stop`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 10, 2.0, reason = "technique_breakdown")), technique = 2)
        r.kind shouldBe SuggestionKind.STOP
        r.stopReason shouldBe "technique"
    }

    @Test
    fun `a pain reason never produces a suggestion`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 7, 0.0, reason = "pain")))
        r.kind.shouldBeNull()
        r.card shouldBe false
    }

    @Test
    fun `the last set leaves nothing to suggest`() {
        val plan = Rx.modelMode(legInput, params.fork())
        val r = run(legInput, plan, listOf(legSet(1, 10, 2.0), legSet(2, 9, 2.0), legSet(3, 8, 5.0)))
        r.kind.shouldBeNull()
        plan.mode shouldBe RxMode.MODEL
    }

    @Test
    fun `delta_today transfers half the mean when two finished exercises agree`() {
        Autoreg.deltaToday(listOf(0.02, 0.04), feelingGood = false, params) shouldBe 0.015
        Autoreg.deltaToday(listOf(0.02, -0.04), feelingGood = false, params) shouldBe 0.0
        Autoreg.deltaToday(listOf(0.02), feelingGood = false, params) shouldBe 0.0
        Autoreg.deltaToday(listOf(0.02), feelingGood = true, params) shouldBe 0.01
    }

    private fun prepared(sets: List<LoggedSet>) = Sse.prepare(sets, FACTS, RirCal.initial(params), params).filter { it.set.setKind != "warmup" }

    private fun restInput(vararg sets: LoggedSet, rule: Boolean = false) = AutoregInput(
        legInput.copy(estimate = RxEstimate(legInput.estimate!!.lPred, 0.0016)), Rx.modelMode(legInput, params.fork()), prepared(sets.toList()),
        ruleItem = rule,
    )

    @Test
    fun `a stronger set than planned lifts the next target after the planned rest (D69)`() {
        // Plan 10 / 9 / 8 at 125 kg; set 1 shows 12 reps with 3 in reserve, more than the model's 13.1 capacity.
        val next = Autoreg.restTarget(restInput(legSet(1, 12, 3.0)), 150.0, params.fork())!!
        next.setIndex shouldBe 2
        next.targetReps shouldBe 10 // the top of the 8-10 range
        next.rirPred shouldBe 3.06
        Autoreg.restTarget(restInput(legSet(1, 12, 3.0)), 60.0, params.fork()).shouldBeNull() // a short rest keeps 9
    }

    @Test
    fun `a short rest lowers the next target and the planned rest keeps it (D69)`() {
        val logged = legSet(1, 10, 2.0)
        Autoreg.restTarget(restInput(logged), 60.0, params.fork())!!.let { it.targetReps shouldBe 8; it.rirPred shouldBe 0.86 }
        Autoreg.restTarget(restInput(logged), 150.0, params.fork()).shouldBeNull()
    }

    @Test
    fun `the third set follows the second set and the actual rest, a RULE item never (D69)`() {
        Autoreg.restTarget(restInput(legSet(1, 10, 2.0), legSet(2, 10, 3.0)), 300.0, params.fork())!!.let {
            it.setIndex shouldBe 3
            it.targetReps shouldBe 10
            it.rirPred shouldBe 2.9
        }
        Autoreg.restTarget(restInput(legSet(1, 12, 3.0), rule = true), 150.0, params.fork()).shouldBeNull()
    }
}
