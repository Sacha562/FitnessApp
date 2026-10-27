package dev.fitnessapp.algo.prescription.reason

import dev.fitnessapp.algo.estimation.EstimationFixtures.set
import dev.fitnessapp.algo.estimation.EstimatorClass
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The estimator column of B12.3 and the imputation of B12.4 (the rest of `ReasonSemanticsTest` comes with M3.3). */
class ReasonSemanticsTest {
    private val params = TestParams.defaults()
    private fun s(code: String?, lowerBound: Boolean = false) =
        set("x", "2026-10-08T18:00:00Z", 93.2, 5, 2.0, reasonCode = code, lowerBound = lowerBound)

    @Test
    fun `estimator class per reason code`() {
        fun cls(code: String?) = ReasonSemantics.forSet(s(code), params).estimatorClass
        listOf(null, "none", "too_easy", "too_hard", "feeling_good", "no_time").forEach { cls(it) shouldBe EstimatorClass.NORMAL }
        cls("feeling_bad") shouldBe EstimatorClass.downweight(4.0)
        cls("too_tired") shouldBe EstimatorClass.downweight(4.0)
        cls("other") shouldBe EstimatorClass.downweight(2.25)
        cls("grip_failure") shouldBe EstimatorClass.LOWER_BOUND
        listOf("pain", "occupied", "equipment_missing", "made_up").forEach { cls(it) shouldBe EstimatorClass.EXCLUDED }
        ReasonSemantics.forSet(s("technique_breakdown"), params) shouldBe SetSemantics(EstimatorClass.NORMAL, techniqueLimited = true)
        ReasonSemantics.forSet(s("feeling_bad", lowerBound = true), params).estimatorClass shouldBe EstimatorClass.LOWER_BOUND
        ReasonSemantics.forSet(s("pain", lowerBound = true), params).estimatorClass shouldBe EstimatorClass.EXCLUDED
    }

    @Test
    fun `imputation of a missing RIR`() {
        fun imp(code: String?, reps: Int, target: Int?, pred: Double?, qStar: Double? = 2.0) = ReasonSemantics.imputeQ(
            set("x", "2026-10-08T18:00:00Z", 93.2, reps, null, reasonCode = code, targetReps = target).copy(rirPred = pred, qStar = qStar),
            params,
        )
        imp(null, 8, 8, 6.2) shouldBe 5.0
        imp(null, 7, 8, 2.4) shouldBe 1.0
        imp(null, 7, 8, 0.4) shouldBe 0.4
        imp(null, 6, 8, 2.4) shouldBe 0.0
        imp("too_easy", 8, 8, 1.5) shouldBe 3.5
        imp("too_hard", 8, 8, 1.5, qStar = 3.0) shouldBe 1.0
        imp("too_hard", 7, 8, 1.5) shouldBe 0.0
        imp(null, 8, null, 2.0).shouldBeNull()
        imp(null, 8, 8, null).shouldBeNull()
    }
}
