package dev.fitnessapp.algo.prescription.rest

import dev.fitnessapp.algo.prescription.warmup.WarmupRest
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** B11.2 base policy (D21) and the B11.5 examples it covers. */
class RestPolicyTest {
    private val params = TestParams.defaults()

    @Test
    fun `class table defaults`() {
        mapOf(
            RestClass.STRENGTH_HEAVY to 180.0, RestClass.COMPOUND_LOWER to 150.0, RestClass.COMPOUND_UPPER to 120.0,
            RestClass.ISOLATION to 90.0, RestClass.HOLD to 90.0, RestClass.CORE to 60.0,
        ).forEach { (cls, s) -> RestPolicy.decide(RestInput(cls), params).timerS shouldBe s }
    }

    @Test
    fun `heavy sets use strength_heavy`() {
        // B11.5: weighted pull-up 6 x 2 outside a challenge -> FIXED r* = 2 <= 5 -> 180 s.
        val d = RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 2), params)
        d.timerS shouldBe 180.0
        d.steps shouldBe listOf("rest.class.strength_heavy")
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 5), params).timerS shouldBe 180.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 8, p = 0.86), params).timerS shouldBe 180.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 6), params).timerS shouldBe 120.0
        // B11.5: leg press 3 x 8-10 -> 150 s (no modifiers in the base policy).
        RestPolicy.decide(RestInput(RestClass.COMPOUND_LOWER, targetReps = 10), params).timerS shouldBe 150.0
    }

    @Test
    fun `challenge rest is exact and capped only by the hard maximum`() {
        // B11.5: OAP session C weighted pull-up 4 x 5, item rest 150 s.
        val d = RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, challengeRestS = 150.0, targetReps = 5), params)
        d.timerS shouldBe 150.0
        d.source shouldBe RestSource.CHALLENGE
        RestPolicy.decide(RestInput(RestClass.HOLD, challengeRestS = 1200.0), params).timerS shouldBe 900.0
        RestPolicy.decide(RestInput(RestClass.HOLD, challengeRestS = 20.0), params).timerS shouldBe 20.0
        RestPolicy.decide(
            RestInput(RestClass.COMPOUND_UPPER, challengeRestS = 120.0, challengeBetweenSidesS = 30.0, sideMode = SideMode.ALTERNATING),
            params,
        ).betweenSidesS shouldBe 30.0
    }

    @Test
    fun `the user base is kept within the wide clamps`() {
        val user = RestPolicy.decide(RestInput(RestClass.CORE, userRestPrefS = 400.0, targetReps = 3), params)
        user.timerS shouldBe 400.0
        user.source shouldBe RestSource.USER
        RestPolicy.decide(RestInput(RestClass.CORE, userRestPrefS = 20.0), params).timerS shouldBe 30.0
        RestPolicy.decide(RestInput(RestClass.CORE, userRestPrefS = 700.0), params).timerS shouldBe 600.0
    }

    @Test
    fun `a multiplier scales the class base and is the user's base (D50)`() {
        val upper = RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 10, userRestMult = 1.2), params)
        (upper.timerS to upper.source) shouldBe (144.0 to RestSource.USER)
        upper.steps shouldBe listOf("rest.class", "rest.user_mult")
        RestPolicy.decide(RestInput(RestClass.ISOLATION, userRestMult = 0.5), params).timerS shouldBe 45.0
        RestPolicy.decide(RestInput(RestClass.CORE, userRestMult = 0.5), params).timerS shouldBe 30.0
        // The strength-heavy class is scaled too, and the user clamps (not 60-300 s) apply.
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, targetReps = 5, userRestMult = 1.5), params).timerS shouldBe 270.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_LOWER, userRestMult = 2.0, lastQTrue = 0.0, qStar = 2.0), params).timerS shouldBe 330.0
        // An adapted base is scaled; seconds win over a multiplier.
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, adaptedBaseS = 105.0, userRestMult = 1.2), params).timerS shouldBe 126.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, userRestPrefS = 90.0, userRestMult = 1.5), params).timerS shouldBe 90.0
    }

    @Test
    fun `alternating sides switch immediately by default`() {
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, sideMode = SideMode.ALTERNATING), params).betweenSidesS shouldBe 0.0
        RestPolicy.decide(RestInput(RestClass.COMPOUND_UPPER, sideMode = SideMode.SEQUENTIAL), params).betweenSidesS shouldBe null
    }

    @Test
    fun `warm-up gaps use the WARMUP-1 rest`() {
        WarmupRest.afterWarmup(0.5, false, params) shouldBe 45.0
        WarmupRest.afterWarmup(0.75, false, params) shouldBe 60.0
        WarmupRest.afterWarmup(0.85, true, params) shouldBe 90.0
        WarmupRest.afterWarmup(null, false, params) shouldBe 45.0
        val d = RestPolicy.decide(RestInput(RestClass.COMPOUND_LOWER, prevWasWarmup = true, warmupRestS = 90.0), params)
        d.timerS shouldBe 90.0
        d.source shouldBe RestSource.WARMUP
    }
}
