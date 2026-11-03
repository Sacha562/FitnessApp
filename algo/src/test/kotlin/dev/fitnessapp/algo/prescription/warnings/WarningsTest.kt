package dev.fitnessapp.algo.prescription.warnings

import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** WE-1 (ALGORITHMS.md B15): rule thresholds and severities, SAFETY enforcement, GF-WE fixtures, ranking and de-dup. */
class WarningsTest {
    private val params = TestParams.defaults()

    @Test
    fun `GF-WE-01 two sets of 12 twice a week on an improve group is WE-V1 WARN`() {
        val w = Warnings.improveWeeklyLow("lats", 4.0, 2, false, params).shouldNotBeNull()
        w.severity shouldBe Severity.WARN
        w.magnitude shouldBe 0.6
        w.numbers["gain"] shouldBe 0.2247
        Warnings.improveWeeklyLow("lats", 2.0, 2, false, params)!!.severity shouldBe Severity.STRONG
        Warnings.improveWeeklyLow("lats", 8.0, 2, false, params)!!.severity shouldBe Severity.INFO
        Warnings.improveWeeklyLow("lats", 10.0, 2, false, params).shouldBeNull()
    }

    @Test
    fun `GF-WE-08 WE-V1 is not raised in a deload week (D61)`() {
        Warnings.improveWeeklyLow("rear_delts", 4.2, 2, true, params).shouldBeNull()
        Warnings.improveWeeklyLow("rear_delts", 0.0, 0, true, params).shouldBeNull()
        Warnings.improveWeeklyLow("rear_delts", 4.2, 2, false, params)!!.severity shouldBe Severity.WARN
    }

    @Test
    fun `WE-P12 suggests the belt once capacity at q star passes the ladder's top (D80)`() {
        // dip_chest 3 Nov: 10 reps at RIR 5 with q* 1 -> 14 = r_hi 10 + rx.double_max_extension 4.
        Warnings.bodyweightAddLoad("se:dips", 14.0, 10, true, params)!!.let {
            it.ruleId shouldBe "WE-P12"
            it.severity shouldBe Severity.INFO
            it.suggestion shouldBe "add_load"
            it.enforced shouldBe false
        }
        Warnings.bodyweightAddLoad("se:dips", 13.0, 10, true, params).shouldBeNull()
        Warnings.bodyweightAddLoad("se:dips", 20.0, 10, false, params).shouldBeNull()
        Warnings.bodyweightAddLoad("se:dips", null, 10, true, params).shouldBeNull()
    }

    @Test
    fun `GF-WE-02 strength reps of 12 are WE-P5 STRONG`() {
        val ws = Warnings.scheme("ex:wpu", SchemeFacts(12, 12, 2.0, strength = true, highRisk = true), params)
        ws.single { it.ruleId == "WE-P5" }.severity shouldBe Severity.STRONG
        Warnings.scheme("ex:wpu", SchemeFacts(8, 8, 2.0, strength = true), params).single { it.ruleId == "WE-P5" }.severity shouldBe Severity.WARN
        Warnings.scheme("ex:wpu", SchemeFacts(5, 5, 2.0, strength = true), params).none { it.ruleId == "WE-P5" } shouldBe true
    }

    @Test
    fun `GF-WE-03 twelve chest sets in one session is WE-V3 INFO`() {
        Warnings.sessionVolumeHigh("chest", 12.0, params)!!.severity shouldBe Severity.INFO
        Warnings.sessionVolumeHigh("chest", 16.0, params)!!.severity shouldBe Severity.WARN
        Warnings.sessionVolumeHigh("chest", 11.0, params).shouldBeNull()
    }

    @Test
    fun `GF-WE-04 a gym session the day before TEST is WE-S4 SAFETY`() {
        val w = Warnings.constraint("session:x", ConstraintFacts(restDayBefore = true)).single()
        w.ruleId shouldBe "WE-S4"
        w.severity shouldBe Severity.SAFETY
        w.enforced shouldBe false
    }

    @Test
    fun `GF-WE-05 a RULE pin jump above the generic cap is WE-S2 WARN, not enforced`() {
        val w = Warnings.loadAboveCap("ex:oaa", 43.79, 41.52 * 1.05, ruleItem = true, hold = false).shouldNotBeNull()
        w.severity shouldBe Severity.WARN
        w.enforced shouldBe false
        w.magnitude shouldBe 0.0044
        Warnings.loadAboveCap("ex:lp", 140.0, 130.0, ruleItem = false, hold = false)!!.severity shouldBe Severity.SAFETY
    }

    @Test
    fun `GF-WE-06 a recorded 3RM at RIR 0 gives no WE-S1, a working set does`() {
        Warnings.loggedSet("ex:wpu", highRisk = true, setKind = "test", contraction = "dynamic", rir = 0.0, failedRep = false).shouldBeNull()
        Warnings.loggedSet("ex:wpu", highRisk = true, setKind = "working", contraction = "dynamic", rir = 0.0, failedRep = false)!!.severity shouldBe Severity.SAFETY
        Warnings.loggedSet("ex:wpu", highRisk = true, setKind = "working", contraction = "eccentric", rir = null, failedRep = true).shouldBeNull()
        Warnings.loggedSet("ex:lp", highRisk = false, setKind = "working", contraction = "dynamic", rir = 0.0, failedRep = false).shouldBeNull()
    }

    @Test
    fun `GF-WE-07 a HOLD-1 load jump is not evaluated`() {
        Warnings.loadAboveCap("ex:plank", 20.0, 10.5, ruleItem = false, hold = true).shouldBeNull()
    }

    @Test
    fun `GF-RX-10 q-star 0 on a high-risk lift is clamped and enforced`() {
        val w = Warnings.scheme("ex:dip", SchemeFacts(5, 8, 1.0, qStarAsked = 0.0, highRisk = true), params).single { it.ruleId == "WE-S1" }
        w.severity shouldBe Severity.SAFETY
        w.enforced shouldBe true
        w.suggestion shouldBe "q_star:1"
        // q* = 0 on a safe lift is only WE-P9 INFO.
        Warnings.scheme("ex:lp", SchemeFacts(8, 10, 0.0), params).map { it.ruleId } shouldBe listOf("WE-P9")
    }

    @Test
    fun `scheme rules fire at their thresholds`() {
        val high = Warnings.scheme("p", SchemeFacts(25, 35, 2.0, compound = false), params).associateBy { it.ruleId }
        high.getValue("WE-P1").severity shouldBe Severity.WARN
        high.getValue("WE-P4").severity shouldBe Severity.INFO
        Warnings.scheme("p", SchemeFacts(8, 12, 5.0), params).single { it.ruleId == "WE-P2" }.severity shouldBe Severity.STRONG
        Warnings.scheme("p", SchemeFacts(8, 12, 4.0), params).single { it.ruleId == "WE-P2" }.severity shouldBe Severity.WARN
        Warnings.scheme("p", SchemeFacts(8, 12, 2.0, setsPref = 1), params).single().ruleId shouldBe "WE-P3"
        Warnings.scheme("p", SchemeFacts(8, 12, 2.0, restS = 45.0), params).single().ruleId shouldBe "WE-P6"
        Warnings.scheme("p", SchemeFacts(3, 5, 2.0, restS = 75.0, strength = true), params).single().ruleId shouldBe "WE-P6"
        Warnings.scheme("p", SchemeFacts(8, 8, 2.0, repsPerStep = 2.2), params).single().ruleId shouldBe "WE-P7"
        Warnings.scheme("p", SchemeFacts(8, 12, 2.0, bwOverExposures = 2), params).single().ruleId shouldBe "WE-P11"
        Warnings.scheme("p", SchemeFacts(8, 12, 2.0, restS = 120.0), params).shouldBeEmpty()
    }

    @Test
    fun `predicted failure on a high-risk lift is WE-S5`() {
        Warnings.predictedFailure("ex:wpu", true, 0.4, params)!!.magnitude shouldBe 0.6
        Warnings.predictedFailure("ex:wpu", true, 1.2, params).shouldBeNull()
        Warnings.predictedFailure("ex:lp", false, 0.0, params).shouldBeNull()
    }

    @Test
    fun `hard windows and gap days decide WE-S4`() {
        val window = WindowHit("pw.beforeA", listOf("lats", "biceps", "forearms"), listOf("brachialis"), listOf("grip_heavy"), allGym = false)
        Warnings.constraint("ex:curl", ConstraintFacts(hardWindows = listOf(window), groups = mapOf("biceps" to 1.0))).single().suggestion shouldBe "rule_override:pw.beforeA"
        Warnings.constraint("ex:bench", ConstraintFacts(hardWindows = listOf(window), groups = mapOf("chest" to 1.0))).shouldBeEmpty()
        val gap = ConstraintFacts(gapDay = true, gapForbiddenTags = listOf("pull_heavy", "grip_heavy"), sessionTags = setOf("pull_heavy"), pullFamily = true, lightPull = false)
        Warnings.constraint("ex:row", gap).single().severity shouldBe Severity.SAFETY
        Warnings.constraint("ex:row", gap.copy(sessionTags = emptySet())).single().severity shouldBe Severity.WARN
        Warnings.constraint("ex:rear_delt", gap.copy(sessionTags = emptySet(), lightPull = true)).shouldBeEmpty()
    }

    @Test
    fun `ranking puts SAFETY first, then magnitude, and drops duplicates`() {
        val a = Warning("WE-P2", Severity.WARN, "e1", 0.5, "moderate", false)
        val b = Warning("WE-P1", Severity.WARN, "e1", 0.7, "moderate", false)
        val c = Warning("WE-S1", Severity.SAFETY, "e2", 1.0, "expert-opinion", false)
        Warnings.rank(listOf(a, b, c, a.copy(magnitude = 0.1))).map { it.ruleId } shouldBe listOf("WE-S1", "WE-P1", "WE-P2")
    }

    @Test
    fun `history rules`() {
        Warnings.gripLimited("ex:wpu", 3)!!.severity shouldBe Severity.INFO
        Warnings.gripLimited("ex:wpu", 2).shouldBeNull()
        Warnings.recoveryCheck(3, emptyList(), params)!!.ruleId shouldBe "WE-T2"
        Warnings.recoveryCheck(0, listOf("T2"), params).shouldNotBeNull()
        Warnings.recoveryCheck(2, emptyList(), params).shouldBeNull()
        Warnings.painRepeat("elbow", 2)!!.severity shouldBe Severity.SAFETY
    }
}
