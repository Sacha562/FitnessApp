package dev.fitnessapp.algo.pipeline

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.allocator.W41Support
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** The L6 build (D62): allocator, ELIG-1 with the gate, SEL-1 with ORD-1, UI-02 trimming, on the W41 inputs. */
class BuildingTest {
    private val params = TestParams.defaults()
    private val catalog = TestCatalog.catalog
    private val targets = W41Support.targets(params)

    private fun request(type: SessionType, budget: Double, elig: EligContext = EligContext(), warmup: WarmupKind = WarmupKind.GENERAL) = BuildRequest(
        type = type, start = W41Support.friStart, budgetMin = budget, warmup = warmup, targets = targets, vBefore = W41Support.vBefore,
        opp = Groups.CANONICAL.associateWith { 1 }, readiness = W41Support.friReadiness, elig = elig,
        forbiddenGroups = W41Support.owned.toSet(), tagRules = W41Support.tagRules,
    )

    @Test
    fun `a legs session builds legs exercises within its budget`() {
        val r = Building.build(request(SessionType.LEGS, 75.0), catalog, params)
        r.empty.shouldBeNull()
        r.exercises.shouldNotBeEmpty()
        r.estMin shouldBeLessThanOrEqual 75.0
        val legs = Groups.FAMILIES.getValue("LEGS") + Groups.FAMILIES.getValue("CORE")
        r.exercises.all { x ->
            val e = TestCatalog.ex(x.slug)
            legs.any { g -> catalog.groupCoeff(e, g) >= 0.5 }
        } shouldBe true
    }

    @Test
    fun `a shorter budget trims or rebuilds to fit and says what it cut`() {
        val long = Building.build(request(SessionType.PUSH_LEGS, 180.0), catalog, params)
        val short = Building.build(request(SessionType.PUSH_LEGS, 45.0), catalog, params)
        short.estMin shouldBeLessThanOrEqual 45.0
        short.exercises.sumOf { it.sets } shouldNotBe long.exercises.sumOf { it.sets }
    }

    @Test
    fun `a type whose groups are all covered by the challenge builds nothing`() {
        val r = Building.build(
            request(SessionType.PULL, 75.0).copy(forbiddenGroups = Groups.FAMILIES.getValue("PULL").toSet()),
            catalog, params,
        )
        r.empty.shouldNotBeNull()
        r.exercises.shouldBeEmpty()
    }

    @Test
    fun `an add-on keeps to the slot's set cap and allowed patterns`() {
        val slot = W41Support.legsAddOnSlot()
        val elig = EligContext(addOn = AddOnFilter(listOf("squat", "squat_machine", "lunge", "hip_ext", "knee_ext", "knee_flex", "calf"), 1, W41Support.owned, Groups.FAMILIES.getValue("LEGS")))
        val r = Building.build(request(SessionType.LEGS, 24.0, elig, WarmupKind.ADDON).copy(addOn = slot), catalog, params)
        r.empty.shouldBeNull()
        r.exercises.sumOf { it.sets }.toDouble() shouldBeLessThanOrEqual slot.maxSets!!
        r.estMin shouldBeLessThanOrEqual 24.0
    }

    @Test
    fun `a mesocycle's anchor enters a full build when it trains a group with a target (D86)`() {
        val req = request(SessionType.LEGS, 75.0)
        val plain = Building.build(req, catalog, params).exercises.map { it.slug }.toSet()
        val input = Building.selectInput(req, catalog, params).second.getOrThrow()
        // A pool exercise the build leaves out on its own that directly trains a group this session targets.
        val anchor = input.pool.map { it.slug }.sorted().first { s ->
            s !in plain && catalog.taxonomy.activeGroups.any { g -> catalog.groupCoeff(TestCatalog.ex(s), g.id) >= 0.5 && (input.target[g.id] ?: 0.0) > 0.0 }
        }
        Building.build(req.copy(anchors = listOf(anchor)), catalog, params).exercises.map { it.slug } shouldContain anchor
    }

    @Test
    fun `a rotated-out exercise never enters the build (D86)`() {
        val req = request(SessionType.LEGS, 75.0)
        val first = Building.build(req, catalog, params).exercises.first().slug
        Building.build(req.copy(rotatedOut = setOf(first)), catalog, params).exercises.map { it.slug } shouldNotContain first
    }

    @Test
    fun `the build is deterministic`() {
        Building.build(request(SessionType.FULL, 60.0), catalog, params) shouldBe Building.build(request(SessionType.FULL, 60.0), catalog, params)
    }
}
