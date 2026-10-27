package dev.fitnessapp.algo.prescription.rx

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.estimation.stall.TierAction
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.TreeMap

/** B9 examples: Part C C3.1-C3.5 and the GF-RX fixtures of D2. */
class RxTest {
    private val params = TestParams.defaults()
    private val bw = 70.7

    /** GF-LOAD-03: the fixture belt (0 to 37.5 in 1.25 steps, then 40 ... 55) on top of BW 70.7. */
    private val belt: List<Double> = (0..30).map { it * 1.25 } + listOf(40.0, 41.25, 42.5, 43.75, 45.0, 46.25, 47.5, 50.0, 51.25, 52.5, 55.0)
    private val beltW = belt.map { bw + it }

    private fun scheme(kind: SchemeKind, lo: Int, hi: Int, q: Double, strength: Boolean = kind == SchemeKind.FIXED) =
        Scheme(kind, lo, hi, q, null, null, strength, TreeMap(), emptyList(), emptyList())

    private fun input(
        scheme: Scheme,
        sets: Int,
        rest: Double,
        loadables: List<Double>,
        m: Double?,
        k: Double,
        pLL: Double = 0.0,
        last: RxLast? = null,
        flags: RxFlags = RxFlags(),
        isolation: Boolean = false,
        highRisk: Boolean = false,
        bodyweightOnly: Boolean = false,
        rule: RuleItem? = null,
    ) = RxInput(
        "ex", "both", isolation, highRisk, bodyweightOnly, scheme, sets, listOf(rest), loadables, k,
        m?.let { RxEstimate(Det.ln(it), pLL) }, last = last, flags = flags, rule = rule,
    )

    @Test
    fun `GF-RX-01 six by two picks +27,5 kg with the posterior mean`() {
        val out = Rx.prescribe(input(scheme(SchemeKind.FIXED, 2, 2, 2.0), 6, 180.0, beltW, 109.94, 30.63, highRisk = true), params)
        out.mode shouldBe RxMode.MODEL
        out.sets.first().wSys shouldBe 98.2
        val pred = out.sets.map { it.rirPred!! }
        // C3.1 prints the values truncated (set 6 is 1.985).
        listOf(2.66, 2.46, 2.30, 2.17, 2.07, 1.98).forEachIndexed { i, x -> pred[i] shouldBe (x plusOrMinus 0.0105) }
        out.candidates.single { kotlin.math.abs(it.w - 99.45) < 0.001 }.feasible shouldBe false
    }

    @Test
    fun `GF-RX-02 with sd 3 percent and z_c 0,5 the model is one belt step lighter`() {
        val out = Rx.prescribe(input(scheme(SchemeKind.FIXED, 2, 2, 2.0), 6, 180.0, beltW, 109.94, 30.63, pLL = 0.0009, highRisk = true), params)
        out.mUse!! shouldBe (108.30 plusOrMinus 0.005)
        out.sets.first().wSys shouldBe 96.95
        out.candidates.single { kotlin.math.abs(it.w - 96.95) < 0.001 }.rir.min() shouldBe (1.90 plusOrMinus 0.006)
        out.sets.first().rirPred!! shouldBe (2.59 plusOrMinus 0.006)
        out.sets.last().rirPred!! shouldBe (1.90 plusOrMinus 0.006)
    }

    private val stack = (1..60).map { it * 5.0 }
    private val legPress = scheme(SchemeKind.RANGE, 8, 10, 2.0, strength = false)
    private val lastLp = RxLast(120.0, listOf(12), listOf(3.5))

    /** C3.3's `M` unrounded: 120 * (1 + 14.5 / 45); with 158.67 set 1's ideal at 120 kg crosses a rounding edge. */
    private val mLp = 120.0 * (1.0 + 14.5 / 45.0)

    @Test
    fun `GF-RX-03 leg press 3 x 8-10 in MODEL`() {
        val out = Rx.modelMode(input(legPress, 3, 150.0, stack, mLp, 45.0, last = lastLp), params)
        out.sets.map { it.targetReps } shouldBe listOf(10, 9, 8)
        out.sets.first().wSys shouldBe 125.0
        out.sets.map { it.rirPred!! }.zip(listOf(3.12, 2.12, 1.71)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.006) }
        out.candidates.single { it.w == 125.0 }.j!! shouldBe (3.35 plusOrMinus 0.006)
        out.candidates.single { it.w == 120.0 }.j!! shouldBe (41.62 plusOrMinus 0.03)
        // 130 kg is above the +5 % cap and has a negative RIR on set 3.
        (out.candidates.single { it.w == 130.0 }.j!! > 100.0) shouldBe true
    }

    @Test
    fun `GF-RX-04 the same as a cold start chooses 120 kg`() {
        val out = Rx.modelMode(input(legPress, 3, 150.0, stack, mLp, 45.0, pLL = 0.0064, last = lastLp), params)
        out.mUse!! shouldBe (152.45 plusOrMinus 0.005)
        out.sets.first().wSys shouldBe 120.0
        out.sets.map { it.targetReps } shouldBe listOf(10, 9, 8)
        out.sets.map { it.rirPred!! }.zip(listOf(3.17, 2.17, 1.77)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.0105) }
        out.candidates.single { it.w == 120.0 }.j!! shouldBe (3.44 plusOrMinus 0.01)
    }

    @Test
    fun `GF-RX-05 the deload keeps the load, halves the sets and adds 2 RIR`() {
        val last = RxLast(125.0, listOf(10, 9, 8), listOf(3.0, 2.0, 2.0), targets = listOf(10, 9, 8))
        val out = Rx.prescribe(input(legPress, 2, 150.0, stack, mLp, 45.0, last = last, flags = RxFlags(deload = true)), params)
        out.sets.map { it.wSys } shouldBe listOf(125.0, 125.0)
        out.sets.map { it.targetReps } shouldBe listOf(9, 7)
        out.sets.map { it.rirPred!! }.zip(listOf(4.12, 4.50)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.006) }
        out.sets.first().rir shouldBe 4.0
    }

    @Test
    fun `GF-RX-06 one pin step is a coarse step`() {
        listOf(41.52 to 43.79, 46.06 to 48.32, 48.32 to 50.59).map { (a, b) -> Rx.repsPerStep(a, b, 30.6, 5.0) }
            .zip(listOf(1.89, 1.70, 1.63)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.006) }
        // The leg press's 5 kg step at 125 kg is coarse too (2.2 reps): a general prescription runs in DOUBLE.
        val out = Rx.prescribe(input(legPress, 3, 150.0, stack, mLp, 45.0, last = lastLp), params)
        out.mode shouldBe RxMode.DOUBLE
    }

    @Test
    fun `GF-RX-07 the Gravl ladder 8-7-6, 8-8-6, 8-8-7, 8-8-8, then the next load`() {
        val s = scheme(SchemeKind.RANGE, 6, 8, 2.0, strength = false)
        fun step(reps: List<Int>, targets: List<Int>): RxOutput = Rx.prescribe(
            input(s, 3, 120.0, listOf(50.0, 55.0), 70.0, 30.0, last = RxLast(50.0, reps, reps.map { 2.0 }, targets = targets),
                flags = RxFlags(usedDoubleThisMeso = true)),
            params,
        )
        val a = step(listOf(8, 7, 6), listOf(8, 7, 6))
        a.double!!.targets shouldBe listOf(8, 8, 6)
        step(listOf(8, 8, 6), listOf(8, 8, 6)).double!!.targets shouldBe listOf(8, 8, 7)
        step(listOf(8, 8, 7), listOf(8, 8, 7)).double!!.targets shouldBe listOf(8, 8, 8)
        val adv = step(listOf(8, 8, 8), listOf(8, 8, 8))
        adv.sets.first().wSys shouldBe 55.0
        adv.trace shouldBe listOf("rx.double.advance")
    }

    @Test
    fun `GF-RX-08 lateral raise extends the range, then advances one dumbbell step`() {
        val s = scheme(SchemeKind.RANGE, 12, 15, 1.0, strength = false)
        val pairs = listOf(12.0, 16.0, 20.0, 24.0)
        val first = Rx.prescribe(
            input(s, 3, 90.0, pairs, 25.54, 26.0, isolation = true,
                last = RxLast(16.0, listOf(15, 15, 15), listOf(1.0, 1.0, 1.0), targets = listOf(15, 15, 15))),
            params,
        )
        first.mode shouldBe RxMode.DOUBLE
        first.repsPerStep!! shouldBe (10.4 plusOrMinus 0.05)
        first.double shouldBe DoubleState(listOf(16, 15, 15), 2)
        first.sets.first().wSys shouldBe 16.0
        val second = Rx.prescribe(
            input(s, 3, 90.0, pairs, 26.77, 26.0, isolation = true,
                last = RxLast(16.0, listOf(17, 17, 17), listOf(1.0, 1.0, 1.0), targets = listOf(17, 17, 17), ext = 2)),
            params,
        )
        second.sets.first().wSys shouldBe 20.0
        second.double shouldBe DoubleState(listOf(9, 6, 5), 0)
        second.capsApplied.single().type shouldBe "double_one_step"
    }

    @Test
    fun `GF-RX-09 a RULE item is copied, with predicted RIR and the model's shadow`() {
        val item = RuleItem(listOf(4, 4, 4, 4), wSys = 93.2, rirTarget = null, restS = 180.0)
        val s = scheme(SchemeKind.FIXED, 4, 4, 2.0)
        val out = Rx.prescribe(input(s, 4, 180.0, beltW, 109.94, 30.63, highRisk = true, rule = item), params)
        out.mode shouldBe RxMode.RULE
        out.sets.map { it.wSys } shouldBe List(4) { 93.2 }
        out.sets.map { it.rirPred!! }.zip(listOf(2.50, 1.92, 1.43, 1.01)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.006) }
        out.shadow!!.sets.first().wSys shouldBe 90.7
        val sd = Rx.prescribe(input(s, 4, 180.0, beltW, 109.94, 30.63, pLL = 0.0009, highRisk = true, rule = item), params)
        sd.shadow!!.sets.first().wSys shouldBe 89.45
        val two = Rx.prescribe(input(s, 2, 180.0, beltW, 109.94, 30.63, highRisk = true, rule = item.copy(reps = listOf(4, 4))), params)
        two.sets.map { it.rirPred!! }.zip(listOf(2.50, 1.92)).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.006) }
    }

    @Test
    fun `GF-WE-05 a RULE pin step above the cap is reported, not clamped`() {
        val item = RuleItem(listOf(5, 5), wSys = 43.79, rirTarget = 1.0, restS = 150.0, wLastItem = 41.52)
        val out = Rx.prescribe(input(scheme(SchemeKind.FIXED, 5, 5, 1.0), 2, 150.0, emptyList(), 60.0, 30.6, highRisk = true, rule = item), params)
        out.sets.first().wSys shouldBe 43.79
        out.capsApplied.single() shouldBe CapApplied("rule_excess", 43.6, 43.79)
    }

    @Test
    fun `GF-RX-10 a high-risk lift never gets a target RIR below 1`() {
        val s = Schemes.resolve(listOf(RepPref("exercise", rirTarget = 0.0, repMin = 3, repMax = 5)), SchemeContext(highRisk = true), params)
        s.qStar shouldBe 1.0
        s.clamps shouldBe listOf("rx.safety.min_q_high_risk")
        Schemes.resolve(listOf(RepPref("exercise", rirTarget = 0.0)), SchemeContext(), params).qStar shouldBe 0.0
    }

    @Test
    fun `GF-RX-11 two failed exposures cut the next load by 7,5 percent and restart the ladder`() {
        val barbell = (8..80).map { it * 2.5 }
        val s = scheme(SchemeKind.RANGE, 6, 8, 2.0, strength = false)
        val out = Rx.prescribe(
            input(s, 3, 150.0, barbell, 80.0, 30.0, last = RxLast(60.0, listOf(5, 4, 4), listOf(0.0, 0.0, 0.0), targets = listOf(8, 7, 6), failed = true),
                flags = RxFlags(failCounter = 2)),
            params,
        )
        out.sets.first().wSys shouldBe 55.0
        out.capsApplied.single().type shouldBe "fail"
        out.trace.contains("stall.fast_path") shouldBe true
    }

    @Test
    fun `scheme defaults, sources and transforms`() {
        val hyp = Schemes.resolve(emptyList(), SchemeContext(), params)
        (hyp.kind to listOf(hyp.rLo, hyp.rHi)) shouldBe (SchemeKind.RANGE to listOf(8, 12))
        hyp.qStar shouldBe 2.0
        hyp.sources["rep_min"] shouldBe "engine_default"
        Schemes.resolve(emptyList(), SchemeContext(isolation = true), params).qStar shouldBe 1.0
        val user = Schemes.resolve(listOf(RepPref("global", repMin = 6, repMax = 10), RepPref("exercise", repMin = 12, repMax = 12)), SchemeContext(), params)
        (user.kind to user.rLo) shouldBe (SchemeKind.FIXED to 12)
        user.sources["rep_min"] shouldBe "user:exercise"
        user.sources["rep_max"] shouldBe "user:exercise"
        // Rest is one field in two forms (D50): the most specific scope with either wins.
        val rest = Schemes.resolve(listOf(RepPref("global", restPrefS = 150.0), RepPref("mechanics", restPrefMult = 1.2)), SchemeContext(), params)
        (rest.restPrefS to rest.restPrefMult) shouldBe (null to 1.2)
        rest.sources["rest_pref_mult"] shouldBe "user:mechanics"
        rest.sources.containsKey("rest_pref_s") shouldBe false
        val exRest = Schemes.resolve(listOf(RepPref("mechanics", restPrefMult = 1.2), RepPref("exercise", restPrefS = 90.0)), SchemeContext(), params)
        (exRest.restPrefS to exRest.restPrefMult) shouldBe (90.0 to null)
        // A user-set RIR keeps its value under warn-only; the tier-1 action becomes a suggestion (D10, C57).
        val tier = Schemes.resolve(listOf(RepPref("global", rirTarget = 2.0)), SchemeContext(tier = TierAction.TIER1), params)
        tier.qStar shouldBe 2.0
        tier.suggestions shouldBe listOf("stall.tier1.q_add")
        Schemes.resolve(emptyList(), SchemeContext(tier = TierAction.TIER1), params).qStar shouldBe 3.0
        Schemes.resolve(emptyList(), SchemeContext(painFollowUp = true), params).qStar shouldBe 3.0
        Schemes.resolve(emptyList(), SchemeContext(reentryRirAdd = 1.0), params).qStar shouldBe 3.0
        Schemes.resolve(emptyList(), SchemeContext(reentryRirAdd = 1.0, deload = true), params).qStar shouldBe 2.0
        Schemes.resolve(emptyList(), SchemeContext(bodyweightOnly = true), params).rHi shouldBe 12
        Schemes.resolve(listOf(RepPref("exercise", repMin = 15, repMax = 25)), SchemeContext(bodyweightOnly = true), params).rHi shouldBe 20
    }

    @Test
    fun `a new exercise starts with a probe set`() {
        val out = Rx.prescribe(input(legPress, 3, 150.0, stack, null, 45.0), params)
        out.probe shouldBe true
        out.sets.first().targetReps shouldBe 10
        out.sets.first().rir shouldBe 4.0
        out.sets.first().wSys.shouldBeNull()
        out.sets.drop(1).all { it.targetReps == null } shouldBe true
        // A transfer prior is no probe (B9.4 step 4, SUB-1): the prescription starts from ratio * M_related.
        val withTransfer = Rx.prescribe(input(legPress, 3, 150.0, stack, null, 45.0).copy(transferM = mLp), params)
        withTransfer.probe shouldBe false
        withTransfer.sets.first().wSys shouldBe 125.0
    }

    @Test
    fun `match weaker gives both sides the weaker side's load and caps the stronger side's reps`() {
        val s = scheme(SchemeKind.RANGE, 8, 12, 2.0, strength = false)
        val l = Rx.modelMode(input(s, 2, 90.0, (1..40).map { it * 2.0 }, 40.0, 30.0).copy(side = "L"), params)
        val r = Rx.modelMode(input(s, 2, 90.0, (1..40).map { it * 2.0 }, 44.0, 30.0).copy(side = "R"), params)
        val (weak, strong) = Rx.combineSides(l, r, matchWeaker = true, oneSetting = false)
        weak.sets.first().side shouldBe "L"
        strong.sets.first().wSys shouldBe weak.sets.first().wSys
        strong.sets.zip(weak.sets).all { (a, b) -> a.targetReps!! <= b.targetReps!! } shouldBe true
    }
}
