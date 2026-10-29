package dev.fitnessapp.algo.selection.select

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.planning.allocator.AllocInput
import dev.fitnessapp.algo.planning.allocator.Allocator
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.allocator.W41Support
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** SEL-1 (ALGORITHMS_PLANNING 13.6) on the seed; the full plans of 21.8 and 22 are frozen in the GF-SEL and GF-W41 goldens. */
class SelectTest {
    private val params = TestParams.defaults()
    private val catalog = SelectFixtures.catalog

    private fun plan(r: SelectResult) = r.exercises.map { it.slug to it.sets }

    @Test
    fun `push prototype case`() {
        plan(Select.select(SelectFixtures.push(params), catalog, params)) shouldContainExactly listOf(
            "incline_db_press" to 3, "machine_chest_press" to 3, "db_lateral_raise" to 4, "triceps_pushdown_rope" to 2,
        )
    }

    @Test
    fun `legs prototype case covers every target group without the disliked lunge`() {
        val r = Select.select(SelectFixtures.legs(params), catalog, params)
        r.exercises.map { it.slug } shouldNotContain "walking_lunge_db"
        r.shortfall shouldBe emptyMap()
        r.exercises.first { it.slug == "leg_press_calf_raise" }.chainedAfter shouldBe "leg_press_45"
        r.estMin shouldBeLessThanOrEqual 65.0
    }

    @Test
    fun `the result does not depend on the pool order`() {
        val input = SelectFixtures.legs(params)
        plan(Select.select(input.copy(pool = input.pool.reversed()), catalog, params)) shouldBe plan(Select.select(input, catalog, params))
    }

    @Test
    fun `the add-on keeps its set cap`() {
        val t = W41Support.targets(params)
        val thu = Allocator.allocate(
            AllocInput(SessionType.LEGS, W41Support.thuStart, 34.0, W41Support.vBefore, Groups.CANONICAL.associateWith { 1 }, emptyMap(), t,
                Groups.CANONICAL.associateWith { true }, tagRules = W41Support.tagRules, addOn = W41Support.legsAddOnSlot()), params,
        )
        val r = Select.select(SelectFixtures.thursday(params, t, thu), catalog, params)
        r.exercises.sumOf { it.sets } shouldBeLessThanOrEqual 4
        plan(r) shouldContainExactly listOf("leg_press_45" to 2, "seated_leg_curl" to 2)
    }

    @Test
    fun `a forbidden pull-heavy tag caps lats and upper back below four hard sets`() {
        val goals = Groups.CANONICAL.associateWith { if (it in setOf("upper_back", "rear_delts")) "improve" else "maintain" }
        val input = SelectFixtures.input(params, EligContext(), mapOf("upper_back" to 6.0, "rear_delts" to 2.0), goals, 60.0).copy(
            forbiddenSessionTags = setOf("pull_heavy"), tagRules = W41Support.tagRules,
        )
        val r = Select.select(input, catalog, params)
        ((r.coverage["lats"] ?: 0.0) + (r.coverage["upper_back"] ?: 0.0)) shouldBeLessThan 4.0
        val free = Select.select(input.copy(forbiddenSessionTags = emptySet()), catalog, params)
        ((free.coverage["lats"] ?: 0.0) + (free.coverage["upper_back"] ?: 0.0)) shouldBeGreaterThanOrEqual 4.0
    }

    @Test
    fun `beam equals exhaustive search on a small pool`() {
        val keep = setOf("incline_db_press", "machine_chest_press", "db_lateral_raise", "triceps_pushdown_rope", "pec_deck", "machine_shoulder_press")
        val full = SelectFixtures.push(params)
        val input = full.copy(pool = full.pool.filter { it.slug in keep })
        val search = Select.Search(input, catalog, params)
        fun finish(plan: List<Select.Pick>) = Select.timeFit(search, Select.tune(search, plan, params), input.budgetMin).map { it.e.slug to it.s }
        val (beam, _) = Select.beam(search)
        val best = exhaustive(search)
        beam.map { it.e.slug }.sorted() shouldBe best.map { it.e.slug }.sorted()
        finish(beam) shouldBe finish(best)
    }

    /** Every ordered sequence of the pool (the space the beam walks), sets by `alloc` on the residual, best by `J` then key. */
    private fun exhaustive(search: Select.Search): List<Select.Pick> {
        val overrun = params.double("select.overrun_cap") * search.input.budgetMin
        var best = emptyList<Select.Pick>()
        var bestJ = search.j(best)
        fun walk(plan: List<Select.Pick>) {
            if (plan.size >= params.int("select.max_ex")) return
            for (e in search.input.pool) {
                if (plan.any { it.e.slug == e.slug }) continue
                val same = plan.filter { it.e.pattern == e.pattern }
                if (same.size >= params.int("select.max_per_pattern") || same.any { catalog.similarity(it.e, e) >= params.double("select.dedup_cos") }) continue
                val next = plan + Select.Pick(e, search.alloc(e, search.residual(plan)))
                if (search.minutes(next) > overrun + 1e-9 || search.forbiddenTag(next)) continue
                val j = search.j(next)
                val key = next.map { it.e.slug }.sorted().joinToString(",")
                if (j > bestJ || (j == bestJ && key < best.map { it.e.slug }.sorted().joinToString(","))) { best = next; bestJ = j }
                walk(next)
            }
        }
        walk(emptyList())
        return best
    }

    @Test
    fun `set bounds follow the preference and the transform`() {
        val input = SelectFixtures.legs(params)
        val search = Select.Search(input.copy(mult = 0.5), catalog, params)
        search.bounds(catalog.exercise("leg_press_45")!!) shouldBe (1 to 2) // 21.8: sPref 3, mult 0.5
        Select.Search(input, catalog, params).bounds(catalog.exercise("leg_press_45")!!) shouldBe (2 to 4)
        Select.Search(input.copy(autoAdjust = true), catalog, params).bounds(catalog.exercise("leg_extension")!!) shouldBe (1 to 4) // SMAX isolation 4
    }

    @Test
    fun `a liked exercise outranks its neutral twin`() {
        val ctx = EligContext(prefs = SelectFixtures.prefs(params, mapOf("triceps_pushdown_bar" to PrefTier.LIKE)))
        val goals = Groups.CANONICAL.associateWith { if (it == "triceps") "improve" else "maintain" }
        val r = Select.select(SelectFixtures.input(params, ctx, mapOf("triceps" to 3.0), goals, 30.0), catalog, params)
        r.exercises.map { it.slug } shouldContainExactly listOf("triceps_pushdown_bar")
    }
}
