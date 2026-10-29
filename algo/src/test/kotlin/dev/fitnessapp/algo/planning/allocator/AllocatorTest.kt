package dev.fitnessapp.algo.planning.allocator

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** SessionDoseAllocator (ALGORITHMS_PLANNING 7.9) on the W41 targets, and the worked allocations of 21.8 (prototype inputs). */
class AllocatorTest {
    private val params = TestParams.defaults()
    private val targets = W41Support.targets(params)
    private val all = Groups.CANONICAL.associateWith { true }
    private val one = Groups.CANONICAL.associateWith { 1 }

    private fun alloc(
        type: SessionType = SessionType.FULL,
        vBefore: Map<String, Double> = W41Support.vBefore,
        readiness: Map<String, Double> = emptyMap(),
        budget: Double = 999.0,
        opp: Map<String, Int> = one,
        available: Map<String, Boolean> = all,
        tags: Set<String> = emptySet(),
        addOn: AddOnSlot? = null,
    ) = Allocator.allocate(AllocInput(type, W41Support.friStart, budget, vBefore, opp, readiness, targets, available, forbiddenSessionTags = tags, tagRules = W41Support.tagRules, addOn = addOn), params)

    @Test
    fun `recovering group excluded`() {
        alloc(readiness = mapOf("quads" to 0.45)).dropped shouldContain Dropped("quads", "RECOVERING")
    }

    @Test
    fun `partial readiness scales the sets`() {
        // front delts (maintain, T 4): x = 4.0; R 0.7 -> round_half(2.8) = 3.0
        alloc(readiness = mapOf("front_delts" to 0.7)).alloc["front_delts"] shouldBe 3.0
    }

    @Test
    fun `remaining volume split across opportunities`() {
        // chest: rem 12 Vhyp = 12.63 FS, opp 2, capS 10 -> ceil_half(6.32) = 6.5
        alloc(opp = one + ("chest" to 2)).alloc["chest"] shouldBe 6.5
    }

    @Test
    fun `indirect credit first`() {
        // chest 6 and triceps 6 allocated; front delts met: triceps normal = ceil_half(6 - 0.5 * 6) = 3
        val a = alloc(SessionType.PUSH, vBefore = W41Support.vBefore + mapOf("chest" to 6.3, "triceps" to 6.3, "front_delts" to 4.0, "side_delts" to 12.0))
        a.alloc["chest"] shouldBe 6.0
        a.alloc["triceps"] shouldBe 6.0
        a.normal["chest"] shouldBe 6.0
        a.normal["triceps"] shouldBe 3.0
    }

    @Test
    fun `pull-heavy cap on a gap day`() {
        // A light-pull exercise that trains upper back directly: alloc 6 FS, capped so lats + upper back stay below 4 hard sets.
        val a = alloc(SessionType.PULL, vBefore = W41Support.vBefore + mapOf("upper_back" to 6.3, "rear_delts" to 12.0), tags = W41Support.gapDayTags)
        a.alloc["upper_back"] shouldBe 6.0
        ((a.credit["upper_back"] ?: 0.0) + (a.credit["lats"] ?: 0.0)) shouldBeLessThanOrEqual 3.0
        a.predictedTags shouldNotContain "pull_heavy"
    }

    @Test
    fun `gap day leaves upper back without a direct exercise`() {
        val ctx = EligContext(gapDay = true, forbiddenSessionTags = W41Support.gapDayTags.toList(), lightPull = LightPullRule(1, 0.5))
        val avail = Elig.groupAvailability(TestCatalog.catalog, ctx, params)
        avail["upper_back"] shouldBe false
        avail["upper_traps"] shouldBe false
        avail["rear_delts"] shouldBe true
        alloc(available = avail).dropped shouldContain Dropped("upper_back", "NO_ELIGIBLE_EXERCISE")
    }

    @Test
    fun `nothing to train below three sets`() {
        val a = alloc(SessionType.CORE, vBefore = mapOf("abs" to 3.05, "lower_back" to 4.0))
        a.feasible shouldBe false
        a.infeasibleReason shouldBe "NOTHING_TO_TRAIN"
    }

    @Test
    fun `Thursday legs add-on of 21_8`() {
        val a = alloc(SessionType.LEGS, budget = 34.0, addOn = W41Support.legsAddOnSlot())
        a.normal shouldBe mapOf("abductors" to 1.0, "calves" to 1.0, "hamstrings" to 5.5, "quads" to 4.0)
        a.normalMinutes shouldBe 32.5
        a.direct shouldBe mapOf("hamstrings" to 2.0, "quads" to 2.0) // deload 0.5, then the 4-set cap
        a.credit shouldBe mapOf("adductors" to 0.5, "glutes" to 0.83, "hamstrings" to 2.0, "quads" to 2.0)
        a.estMinutes shouldBe 13.8
        a.rirTarget shouldBe mapOf("hamstrings" to 4.0, "quads" to 4.0)
        a.label shouldBe "Legs add-on"
    }

    @Test
    fun `Friday FULL of 21_8 after the add-on`() {
        val thu = alloc(SessionType.LEGS, budget = 34.0, addOn = W41Support.legsAddOnSlot())
        val vb = W41Support.vBefore.toMutableMap()
        thu.vhyp.forEach { (g, v) -> vb[g] = (vb[g] ?: 0.0) + v }
        val a = alloc(vBefore = vb, readiness = W41Support.friReadiness, budget = 75.0, available = W41Support.gapDayAvailable, tags = W41Support.gapDayTags)
        a.alloc shouldBe mapOf(
            "abductors" to 4.0, "adductors" to 4.0, "calves" to 4.0, "chest" to 10.0, "front_delts" to 4.0, "glutes" to 10.0, "hamstrings" to 10.0,
            "lower_back" to 4.0, "quads" to 10.0, "rear_delts" to 10.0, "side_delts" to 10.0, "triceps" to 10.0,
        )
        a.normal shouldBe mapOf("abductors" to 1.0, "calves" to 1.0, "chest" to 8.5, "hamstrings" to 3.0, "lower_back" to 1.0, "quads" to 2.5, "rear_delts" to 5.0, "side_delts" to 3.5)
        a.normalMinutes shouldBe 74.8
        // deload + re-entry: x 0.5, at least one set (deloadMinOneSet: calves 1 stays 1)
        a.direct shouldBe mapOf("abductors" to 1.0, "calves" to 1.0, "chest" to 4.5, "hamstrings" to 1.5, "lower_back" to 1.0, "quads" to 1.5, "rear_delts" to 2.5, "side_delts" to 2.0)
        a.estMinutes shouldBe 49.6
        a.credit["front_delts"] shouldBe 2.25
        a.credit["triceps"] shouldBe 2.25
        a.credit["upper_back"] shouldBe 1.25
        a.vhyp["chest"] shouldBe 3.6
        a.predictedTags shouldBe emptyList()
        a.label shouldBe "Legs + push + rear delts"
        a.dropped shouldContain Dropped("upper_back", "NO_ELIGIBLE_EXERCISE")
        a.dropped shouldContain Dropped("abs", "TARGETS_MET")
    }

    @Test
    fun `Wednesday train-anyway FULL of 21_8`() {
        val wedR = mapOf("lats" to 0.73, "biceps" to 0.88, "forearms" to 0.63, "rear_delts" to 0.89, "upper_back" to 0.87, "abs" to 0.86)
        val a = alloc(readiness = wedR, budget = 75.0, available = W41Support.gapDayAvailable, tags = W41Support.gapDayTags)
        a.normal shouldBe mapOf("abductors" to 1.0, "calves" to 1.0, "chest" to 8.0, "hamstrings" to 4.0, "lower_back" to 1.0, "quads" to 3.5, "rear_delts" to 3.0, "side_delts" to 3.5)
        a.normalMinutes shouldBe 74.6
        // deloadReentryNoStack: quads 3.5 -> round_half(1.75) = 2.0
        a.direct shouldBe mapOf("abductors" to 1.0, "calves" to 1.0, "chest" to 4.0, "hamstrings" to 2.0, "lower_back" to 1.0, "quads" to 2.0, "rear_delts" to 1.5, "side_delts" to 2.0)
        a.estMinutes shouldBe 48.6
    }

    @Test
    fun `labels name the families that get direct sets`() {
        Allocator.label(mapOf("rear_delts" to 2.0, "lower_back" to 1.0), false) shouldBe "Rear delts"
        Allocator.label(mapOf("rear_delts" to 2.0, "upper_back" to 1.0, "chest" to 3.0), false) shouldBe "Push + upper back"
        Allocator.label(mapOf("lats" to 2.0, "quads" to 2.0), false) shouldBe "Legs + pull"
        Allocator.label(mapOf("abs" to 2.0), false) shouldBe "Core"
        Allocator.label(mapOf("chest" to 2.0), true) shouldBe "Push add-on"
    }
}
