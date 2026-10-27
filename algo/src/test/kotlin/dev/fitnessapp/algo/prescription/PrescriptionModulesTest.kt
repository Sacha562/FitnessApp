package dev.fitnessapp.algo.prescription

import dev.fitnessapp.algo.core.det.Erfc
import dev.fitnessapp.algo.estimation.stall.FailExposure
import dev.fitnessapp.algo.estimation.stall.Stall
import dev.fitnessapp.algo.estimation.stall.StallExposure
import dev.fitnessapp.algo.estimation.stall.StallStatus
import dev.fitnessapp.algo.estimation.stall.TierAction
import dev.fitnessapp.algo.prescription.reason.JointLoad
import dev.fitnessapp.algo.prescription.reason.Reason
import dev.fitnessapp.algo.prescription.reason.SessionEffect
import dev.fitnessapp.algo.prescription.rest.AdaptSet
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.rest.SideMode
import dev.fitnessapp.algo.prescription.warmup.Warmup
import dev.fitnessapp.algo.prescription.warmup.WarmupInput
import dev.fitnessapp.algo.prescription.warmup.WarmupTier
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** WARMUP-1 (GF-WU-01), REST-1 1.1 (GF-REST-01/02, B11.4), REASON-1 (GF-REASON-01, D49) and STALL-1 (GF-STALL-01/02). */
class PrescriptionModulesTest {
    private val params = TestParams.defaults()
    private val belt = ((0..30).map { it * 1.25 } + listOf(40.0, 41.25, 42.5, 43.75, 45.0, 46.25, 47.5, 50.0, 51.25, 52.5, 55.0)).map { 70.7 + it }

    @Test
    fun `GF-WU-01 warm-up before +27,5 kg is BW x 6 then +12,5 x 1`() {
        val plan = Warmup.plan(WarmupInput(98.2, 109.94, 30.63, false, belt, bodyweightW = 70.7), params)
        plan.tier shouldBe WarmupTier.HIGH
        plan.pW shouldBe 0.8932
        plan.sets.map { Triple(it.wSys, it.reps, it.restS) } shouldBe listOf(Triple(70.7, 6, 45.0), Triple(83.2, 1, 90.0))
        plan.sets.first().bodyweight shouldBe true
    }

    @Test
    fun `warm-up tiers for isolation, already warm, light work and the singles of a test day`() {
        Warmup.plan(WarmupInput(30.0, 50.0, 26.0, true, (1..20).map { it * 2.5 }), params).tier shouldBe WarmupTier.LOW
        Warmup.plan(WarmupInput(30.0, 50.0, 26.0, true, (1..20).map { it * 2.5 }, firstForGroup = false), params).sets shouldBe emptyList()
        Warmup.plan(WarmupInput(60.0, 100.0, 30.0, false, (1..40).map { it * 2.5 }, warm = true), params).tier shouldBe WarmupTier.NONE
        Warmup.plan(WarmupInput(70.0, 100.0, 30.0, false, (1..40).map { it * 2.5 }), params).tier shouldBe WarmupTier.MID
        val test = Warmup.plan(WarmupInput(90.0, 100.0, 30.0, false, (1..40).map { it * 2.5 }, testSession = true), params)
        test.sets.map { it.wSys } shouldBe listOf(40.0, 57.5, 75.0, 82.5)
    }

    @Test
    fun `GF-REST-01 challenge value, strength heavy, and the leg press modifiers`() {
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, challengeRestS = 150.0), params).timerS shouldBe 150.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 2), params).timerS shouldBe 180.0
        val lp = RestInput(RestClass.COMPOUND_LOWER, targetReps = 9, qStar = 2.0)
        RestPolicy.decide(lp.copy(lastQTrue = 3.5, lastReps = 10, lastTarget = 10, fNext = 0.15), params).timerS shouldBe 150.0
        val hard = RestPolicy.decide(lp.copy(lastQTrue = 0.5, lastReps = 9, lastTarget = 9, fNext = 0.26), params)
        hard.timerS shouldBe 195.0
        hard.steps shouldBe listOf("rest.class", "rest.mod.hard", "rest.mod.fatigue")
        RestPolicy.decide(lp.copy(lastQTrue = 4.5), params).timerS shouldBe 135.0
        // The base policy is unchanged without last-set inputs (rest@1.0.0, D21).
        RestPolicy.decide(RestInput(RestClass.ISOLATION), params).timerS shouldBe 90.0
    }

    @Test
    fun `GF-REST-02 alternating pair and supersets`() {
        val d = RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, userRestPrefS = 120.0, sideMode = SideMode.ALTERNATING), params)
        d.betweenSidesS shouldBe 0.0
        RestPolicy.pairTimer(d.timerS, 5 * 3.0 + 5.0, params) shouldBe 90.0
        RestPolicy.supersetAfterPair(150.0, 90.0, 40.0, 30.0, params) shouldBe 60.0
        RestPolicy.readout(7.4975, 0.0, 5.0, 7.4975, 150.0, 2.0, params) shouldBe 4.62
    }

    @Test
    fun `rest adaptation needs ten sets, shortens when rests end early without loss, and respects a user preference`() {
        val early = List(10) { AdaptSet(90.0, 120.0, 0.0) }
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 120.0, early.take(9), null, false, params).shouldBeNull()
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 120.0, early, null, false, params)!!.baseNewS shouldBe 105.0
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 90.0, early, null, false, params)!!.baseNewS shouldBe 90.0
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 120.0, List(10) { AdaptSet(130.0, 120.0, -1.5) }, null, false, params)!!.baseNewS shouldBe 135.0
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 120.0, early, 3, false, params).shouldBeNull()
        RestPolicy.adapt(RestClass.COMPOUND_UPPER, 120.0, early, null, true, params).shouldBeNull()
    }

    @Test
    fun `GF-REASON-01 the table row of every code`() {
        Reason.row("feeling_bad").let { (it.session to it.countsForRecovery) shouldBe (SessionEffect.OFFER_LIGHTER to true) }
        Reason.row("no_time").let { (it.invalidatesExposure to it.excludedFromFailCounter) shouldBe (true to true) }
        Reason.row("grip_failure").let { (it.invalidatesExposure to it.excludedFromStall) shouldBe (true to false) }
        Reason.row("pain").session shouldBe SessionEffect.END_EXERCISE
        Reason.row(null).session shouldBe SessionEffect.NONE
        Reason.row("made_up").session shouldBe SessionEffect.NONE
        listOf("too_easy", "too_hard", "feeling_good", "feeling_bad", "too_tired", "no_time", "occupied", "equipment_missing",
            "pain", "grip_failure", "torso_rotation", "technique_breakdown", "other").forEach { Reason.row(it).code shouldBe it }
    }

    @Test
    fun `the pain stop removes only exercises with clear stress on the joint (D49)`() {
        val ex = listOf(JointLoad("curl", mapOf("elbow_medial" to 2)), JointLoad("row", mapOf("elbow_medial" to 1)), JointLoad("squat", emptyMap()))
        Reason.painStop(5.0, listOf("elbow_medial", "elbow_lateral"), ex, params) shouldBe listOf("curl")
        Reason.painStop(2.0, listOf("elbow_medial"), ex, params) shouldBe emptyList()
    }

    @Test
    fun `future effects from the reason history`() {
        Reason.tooHardBoostRemaining(listOf(true, true, false), params) shouldBe 2
        Reason.tooHardBoostRemaining(listOf(false, true, true), params) shouldBe 1
        Reason.tooHardBoostRemaining(listOf(true, false, true), params) shouldBe 0
        Reason.painFollowUp(1, params) shouldBe true
        Reason.painFollowUp(2, params) shouldBe false
        val today = LocalDate.parse("2026-10-27")
        Reason.recoveryCheck(listOf(today, today.minusDays(5), today.minusDays(13)), today, params) shouldBe true
        Reason.recoveryCheck(listOf(today, today.minusDays(5), today.minusDays(14)), today, params) shouldBe false
        Reason.busy(listOf(true, false, true, false), params) shouldBe true
        Reason.busy(listOf(true, false, false, false), params) shouldBe false
    }

    @Test
    fun `GF-STALL-01 a slow trend over five exposures is a stall with a tier-1 action`() {
        val today = LocalDate.parse("2026-10-27")
        val e = (0 until 5).map { StallExposure(today.minusDays(7L * it), true, 0.01) }
        val r = Stall.evaluate(0.0005, 0.002 * 0.002, e, today, StallStatus.PROGRESSING, emptyList(), null, params)
        r.pProg shouldBe (0.106 plusOrMinus 0.0006)
        r.status shouldBe StallStatus.STALL
        r.event shouldBe true
        r.tierAction shouldBe TierAction.TIER1
        Stall.evaluate(0.0005, 0.002 * 0.002, e, today, StallStatus.PROGRESSING, listOf(today.minusWeeks(3)), null, params).tierAction shouldBe TierAction.TIER2
        Stall.evaluate(0.0005, 0.002 * 0.002, e.take(3), today, null, emptyList(), null, params).status shouldBe StallStatus.INSUFFICIENT_DATA
    }

    @Test
    fun `GF-STALL-02 the global trigger needs five active exercises`() {
        Stall.global(listOf(true, true, false, false), emptyList(), 0, params).triggers shouldBe emptyList()
        Stall.global(listOf(true, true, false, false, false), emptyList(), 0, params).triggers shouldBe listOf("T1")
        Stall.global(emptyList(), listOf(-0.05, -0.05, -0.05, 0.0), 3, params).triggers shouldBe listOf("T2", "T3")
    }

    @Test
    fun `fail counter and erfc`() {
        Stall.failCounter(listOf(FailExposure(true, true), FailExposure(false, false), FailExposure(true, true), FailExposure(true, false))) shouldBe 2
        Erfc.erfc(0.0) shouldBe (1.0 plusOrMinus 1e-7)
        Erfc.phi(1.25) shouldBe (0.89435 plusOrMinus 1e-5)
        Erfc.phi(-1.25) shouldBe (0.10565 plusOrMinus 1e-5)
    }
}
