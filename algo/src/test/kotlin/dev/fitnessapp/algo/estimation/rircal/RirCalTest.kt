package dev.fitnessapp.algo.estimation.rircal

import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.prescription.reason.ReasonSemantics
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.TreeMap

/** B4: `q_true`, the explicit check (GF-RIR-01), the class switch, implicit and hook updates, scheduling (GF-RIR-02). */
class RirCalTest {
    private val params = TestParams.defaults()
    private val initial = RirCal.initial(params)
    private val iso = CurveClass.LOWER_ISOLATION

    private fun q(s: dev.fitnessapp.algo.estimation.LoggedSet, state: RirCalState = initial) =
        RirCal.qTrue(s, ReasonSemantics.forSet(s, params), CurveClass.UPPER_COMPOUND, state, params)

    @Test
    fun `q_true adds the bias, failures and protocol 3RMs are zero`() {
        q(set("a", "2026-10-08T17:50:00Z", 93.2, 5, 2.0))!!.qTrue shouldBe 2.5
        q(set("b", "2026-10-08T17:50:00Z", 93.2, 5, 1.0, failedRep = true))!!.qTrue shouldBe 0.0
        q(set("c", "2026-10-11T11:30:00Z", 103.2, 3, 0.0, setKind = "test"))!!.qTrue shouldBe 0.0
        q(set("d", "2026-10-08T17:50:00Z", 93.2, 5, 0.0, reasonCode = "technique_breakdown"))!!.qTrue shouldBe 0.0
        // sigma_q = base(n) s_q + 0.15 q_rep: n = 7.5 -> base 1.0.
        q(set("e", "2026-10-08T17:50:00Z", 93.2, 5, 2.0))!!.sigmaQ shouldBe (1.3 plusOrMinus 1e-12)
    }

    @Test
    fun `a missing RIR is imputed with the sigma floor, or gives no observation`() {
        q(set("a", "2026-10-08T17:50:00Z", 93.2, 5, null)).shouldBeNull()
        val imputed = q(set("b", "2026-10-08T17:50:00Z", 93.2, 5, null, targetReps = 5).copy(rirPred = 1.6))!!
        imputed.qTrue shouldBe 1.6
        imputed.imputed shouldBe true
        imputed.sigmaQ shouldBe 2.0
    }

    @Test
    fun `GF-RIR-01 explicit check`() {
        val u = RirCal.explicit(initial, iso, qPred = 2.0, extra = 4, date = LocalDate.parse("2026-10-24"), params = params)
        u.bAfter shouldBe (0.80 plusOrMinus 1e-9)
        u.sQAfter shouldBe (1.118 plusOrMinus 0.0005)
        u.e shouldBe 2.0
        u.type shouldBe "explicit"
        u.nC shouldBe 1
        u.state.lastCheckWeek shouldBe "2026-W43"
    }

    @Test
    fun `the class value takes over after five checks`() {
        var s = initial
        repeat(4) { s = RirCal.explicit(s, iso, 2.0, 4, LocalDate.parse("2026-09-01").plusWeeks(it * 4L), params).state }
        RirCal.bEff(s, iso, params) shouldBe s.b
        s = RirCal.explicit(s, iso, 2.0, 4, LocalDate.parse("2026-12-01"), params).state
        RirCal.bEff(s, iso, params) shouldBe s.classes.getValue(iso.code).bC
        RirCal.bEff(s, CurveClass.UPPER_COMPOUND, params) shouldBe s.b
    }

    @Test
    fun `implicit update from an unintended failure`() {
        val sets = listOf(
            set("1", "2026-10-08T17:50:00Z", 93.2, 5, 2.0, targetReps = 5),
            set("2", "2026-10-08T17:53:00Z", 93.2, 5, 1.0, restBeforeS = 150.0, targetReps = 5),
            set("3", "2026-10-08T17:56:00Z", 93.2, 3, 0.0, restBeforeS = 150.0, targetReps = 5),
        ).map { it to ReasonSemantics.forSet(it, params) }
        val u = RirCal.implicit(initial, CurveClass.UPPER_COMPOUND, sets, 0.0, params).shouldNotBeNull()
        u.type shouldBe "implicit"
        // Set 3 failed at 3 reps where set 2 reported 1 in reserve after 5: the user overestimated, e < 0.
        (u.e < 0.0) shouldBe true
        u.bAfter shouldBe (0.5 + 0.1 * (u.e - 0.5) plusOrMinus 1e-9)
        u.sQAfter shouldBe initial.sQ
        // A grip failure blocks it.
        val grip = sets.toMutableList().also { it[2] = it[2].first.copy(reasonCode = "grip_failure") to it[2].second }
        RirCal.implicit(initial, CurveClass.UPPER_COMPOUND, grip, 0.0, params).shouldBeNull()
    }

    @Test
    fun `reason hook after two too-easy exposures`() {
        fun exposure(id: String) = listOf(set(id, "2026-10-08T17:50:00Z", 93.2, 5, 2.0, reasonCode = "too_easy").copy(qStar = 2.0))
        val u = RirCal.reasonHook(initial, null, listOf(exposure("a"), exposure("b")), params).shouldNotBeNull()
        u.bAfter shouldBe 0.75
        RirCal.reasonHook(initial, null, listOf(exposure("a")), params).shouldBeNull()
    }

    @Test
    fun `GF-RIR-02 one check per ISO week across the DST change`() {
        val cand = CheckCandidate("leg_extension", 3, iso, "machine", challenge = false, ruleMode = false, jointFlagged = false)
        val other = CheckCandidate("leg_curl", 2, CurveClass.LOWER_ISOLATION, "machine", challenge = false, ruleMode = false, jointFlagged = false)
        RirCal.chooseCheck(listOf(cand, other), true, false, LocalDate.parse("2026-10-24"), initial, params) shouldBe other
        val afterSat = RirCal.explicit(initial, iso, 2.0, 4, LocalDate.parse("2026-10-24"), params).state
        RirCal.chooseCheck(listOf(cand), true, false, LocalDate.parse("2026-10-25"), afterSat, params).shouldBeNull()
        // Monday is week 44, but the class cooldown (21 days) still blocks the same class.
        RirCal.chooseCheck(listOf(cand), true, false, LocalDate.parse("2026-10-26"), afterSat, params).shouldBeNull()
        val upper = CheckCandidate("cable_row", 1, CurveClass.UPPER_COMPOUND, "cable", challenge = false, ruleMode = false, jointFlagged = false)
        RirCal.chooseCheck(listOf(upper), true, false, LocalDate.parse("2026-10-26"), afterSat, params) shouldBe upper
    }

    @Test
    fun `check eligibility filters`() {
        val ok = CheckCandidate("x", 1, iso, "machine", challenge = false, ruleMode = false, jointFlagged = false)
        val day = LocalDate.parse("2026-10-24")
        RirCal.chooseCheck(listOf(ok), false, false, day, initial, params).shouldBeNull()
        RirCal.chooseCheck(listOf(ok), true, true, day, initial, params).shouldBeNull()
        listOf(ok.copy(safetyClass = "high_risk"), ok.copy(safetyClass = "free_compound"), ok.copy(challenge = true),
            ok.copy(ruleMode = true), ok.copy(jointFlagged = true)).forEach {
            RirCal.chooseCheck(listOf(it), true, false, day, initial, params).shouldBeNull()
        }
        // Oldest class check first; a never-checked class beats any checked one.
        val checked = initial.copy(classes = TreeMap(mapOf(iso.code to ClassCal(0.5, 1, LocalDate.parse("2026-09-01")))))
        val never = ok.copy(exerciseId = "y", planPosition = 9, curveClass = CurveClass.UPPER_ISOLATION)
        RirCal.chooseCheck(listOf(ok, never), true, false, day, checked, params) shouldBe never
    }
}
