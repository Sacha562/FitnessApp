package dev.fitnessapp.algo.selection.rotate

import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.select.SelectFixtures
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** ALGORITHMS_PLANNING 16.2 and the readings of D86. */
class RotationTest {
    private val params = TestParams.defaults()
    private val catalog = TestCatalog.catalog
    private val prefs = SelectFixtures.prefs(params, SelectFixtures.w41Prefs)
    private val ctx = EligContext(prefs = prefs)
    private val pool = Elig.pool(catalog, ctx, params).pool
    private val runs = mapOf("LEGS" to SelectFixtures.legs(params), "PUSH" to SelectFixtures.push(params))
    private val w45 = LocalDate.parse("2026-11-02")

    private fun clock(n: Int = 1, start: LocalDate = w45) = MesocycleClock(n, start, 1, 5, Rotation.SOURCE_CHALLENGE, 2, isNew = true)

    private fun input(
        used: Set<String> = emptySet(),
        history: Map<String, ExerciseHistory> = emptyMap(),
        previous: Mesocycle? = null,
        clock: MesocycleClock = clock(),
        tiers: Map<String, PrefTier> = SelectFixtures.w41Prefs,
        pool: List<dev.fitnessapp.algo.selection.catalog.CatalogExercise> = this.pool,
    ) = RotateInput(
        clock = clock, previous = previous, familyRuns = runs, pool = pool, prefs = SelectFixtures.prefs(params, tiers),
        used = used, history = history, previousFrom = clock.start.minusWeeks(5),
    )

    /** [n] weekly exposures before [end] with the same best e1RM and reps: no best after the first. */
    private fun flat(slug: String, n: Int, end: LocalDate = w45, e1rm: Double = 60.0, g: Double? = 0.0) = ExerciseHistory(
        slug,
        (1..n).map { i -> Exposure(end.minusWeeks(i.toLong()), "s$i", e1rm, listOf(LoadReps(45.0, 10))) },
        g,
    )

    @Test
    fun `challengeBlockAligned - block 2 week 1 starts a new mesocycle`() {
        val first = Rotation.clock(LocalDate.parse("2026-10-07"), ChallengeBlock(1, 1, 5), null, params)
        first.start shouldBe LocalDate.parse("2026-10-05")
        first.n shouldBe 1
        val m1 = Rotation.start(input(clock = first), catalog, params)
        val mid = Rotation.clock(LocalDate.parse("2026-10-21"), ChallengeBlock(1, 3, 5), m1, params)
        mid.isNew shouldBe false
        mid.week shouldBe 3
        val next = Rotation.clock(LocalDate.parse("2026-11-04"), ChallengeBlock(2, 1, 5), m1, params)
        next.isNew shouldBe true
        next.n shouldBe 2
        next.start shouldBe w45
        next.blockIndex shouldBe 2
    }

    @Test
    fun `without a challenge the mesocycle runs on in calendar weeks`() {
        val m1 = Rotation.start(input(clock = MesocycleClock(1, w45, 1, 5, Rotation.SOURCE_CALENDAR, null, true)), catalog, params)
        val week5 = Rotation.clock(LocalDate.parse("2026-12-03"), null, m1, params)
        week5.isNew shouldBe false
        week5.week shouldBe 5
        val next = Rotation.clock(LocalDate.parse("2026-12-08"), null, m1, params)
        next.isNew shouldBe true
        next.start shouldBe LocalDate.parse("2026-12-07")
        next.n shouldBe 2
    }

    @Test
    fun `anchorsLockedInMesocycle - two compound main lifts per family, kept at the next start`() {
        val m1 = Rotation.start(input(), catalog, params)
        val legs = m1.anchors.filter { it.family == "LEGS" }
        val push = m1.anchors.filter { it.family == "PUSH" }
        legs.size shouldBe 2
        push.size shouldBe 2
        (legs + push).forEach { catalog.exercise(it.slug)!!.mechanics shouldBe "compound"; it.reason shouldBe "sel1" }
        m1.anchors.filter { it.family == "PULL" || it.family == "CORE" } shouldBe emptyList()
        // During the mesocycle every standalone build pins the same anchors; an add-on pins none.
        Rotation.selection(m1, emptySet(), addOn = false).anchors shouldBe m1.anchors.map { it.slug }.sorted()
        Rotation.selection(m1, emptySet(), addOn = true).anchors shouldBe emptyList()
        // The next start keeps them even when the user now likes another compound more.
        val m2 = Rotation.start(
            input(previous = m1, clock = clock(2, w45.plusWeeks(5)), tiers = SelectFixtures.w41Prefs + ("hack_squat_machine" to PrefTier.FAVORITE)),
            catalog, params,
        )
        m2.anchors.map { it.slug } shouldBe m1.anchors.map { it.slug }
        m2.anchors.forEach { it.reason shouldBe "kept" }
    }

    @Test
    fun `an anchor stalled in two consecutive mesocycles or marked never is replaced`() {
        val m1 = Rotation.start(input(), catalog, params)
        val a = m1.anchors.first { it.family == "LEGS" }.slug
        val stalledOnce = Rotation.start(input(previous = m1, history = mapOf(a to flat(a, 5)), clock = clock(2, w45.plusWeeks(5))), catalog, params)
        stalledOnce.anchors.first { it.slug == a }.stalledMesocycles shouldBe 1
        val stalledTwice = Rotation.start(
            input(previous = stalledOnce, history = mapOf(a to flat(a, 10, w45.plusWeeks(10))), clock = clock(3, w45.plusWeeks(10))), catalog, params,
        )
        stalledTwice.anchors.map { it.slug } shouldNotContain a
        stalledTwice.dropped shouldContainExactly listOf(DroppedAnchor("LEGS", a, "stalled_mesocycles"))
        stalledTwice.anchors.count { it.family == "LEGS" } shouldBe 2

        val never = Rotation.start(
            input(previous = m1, clock = clock(2, w45.plusWeeks(5)), tiers = SelectFixtures.w41Prefs + (a to PrefTier.NEVER),
                pool = Elig.pool(catalog, EligContext(prefs = SelectFixtures.prefs(params, SelectFixtures.w41Prefs + (a to PrefTier.NEVER))), params).pool),
            catalog, params,
        )
        never.dropped.single().reason shouldBe "never"
    }

    @Test
    fun `stalledAccessoryRotated - a stalled accessory rotates to a SUB-1 replacement, a progressing one stays`() {
        val used = setOf("leg_extension", "lying_leg_curl", "seated_calf_raise")
        val history = mapOf(
            "lying_leg_curl" to flat("lying_leg_curl", 4),
            "leg_extension" to flat("leg_extension", 4, g = 0.01).copy(exposures = flat("leg_extension", 4).exposures.mapIndexed { i, x -> x.copy(bestE1rmKg = 80.0 - i) }),
        )
        val m = Rotation.start(input(used = used, history = history), catalog, params)
        m.keepScores["lying_leg_curl"] shouldBe -1.0
        m.keepScores["leg_extension"] shouldBe 1.5
        val r = m.rotated.single()
        r.from shouldBe "lying_leg_curl"
        r.reason shouldBe "stalled"
        r.family shouldBe "LEGS"
        r.to shouldNotBe null
        r.to shouldNotBe "leg_extension"
        m.kept shouldContainExactly listOf("leg_extension", "seated_calf_raise")
        val sel = Rotation.selection(m, setOf("lying_leg_curl"), addOn = false)
        sel.rotatedOut shouldBe setOf("lying_leg_curl")
        sel.consistency shouldNotContain "lying_leg_curl"
        (r.to in sel.consistency) shouldBe true
    }

    @Test
    fun `rotateAtMostThird - six disliked accessories rotate two`() {
        val used = listOf("leg_extension", "lying_leg_curl", "seated_leg_curl", "seated_calf_raise", "hip_abduction_machine", "hip_adduction_machine")
        val m = Rotation.start(input(used = used.toSet(), tiers = SelectFixtures.w41Prefs + used.associateWith { PrefTier.DISLIKE }), catalog, params)
        m.rotated.size shouldBe 2
        m.rotated.forEach { it.reason shouldBe "disliked" }
        m.rotated.map { it.from } shouldBe used.sorted().take(2)
    }

    @Test
    fun `fewer negative keep scores than a third rotate only those`() {
        val used = setOf("leg_extension", "lying_leg_curl", "seated_calf_raise")
        val m = Rotation.start(input(used = used), catalog, params)
        m.rotated shouldBe emptyList()
        m.kept shouldContainExactly used.sorted()
    }

    @Test
    fun `deterministicHashOrder - the result does not depend on input order`() {
        val used = listOf("leg_extension", "lying_leg_curl", "seated_calf_raise", "pec_deck", "db_lateral_raise")
        val history = used.associateWith { flat(it, 4) }
        val a = Rotation.start(input(used = used.toSet(), history = history), catalog, params)
        val b = Rotation.start(
            input(used = used.reversed().toCollection(LinkedHashSet()), history = history.entries.reversed().associate { it.key to it.value }, pool = pool.reversed()),
            catalog, params,
        )
        a shouldBe b
        Rotation.json(a) shouldBe Rotation.json(b)
    }

    @Test
    fun `the snapshot outputs read back to the same state, and an override bumps the revision`() {
        val used = setOf("leg_extension", "lying_leg_curl", "seated_calf_raise")
        val m = Rotation.start(input(used = used, history = mapOf("lying_leg_curl" to flat("lying_leg_curl", 4))), catalog, params)
        Rotation.parse(Rotation.json(m)) shouldBe m
        val from = m.anchors.first { it.family == "LEGS" }.slug
        val o = Rotation.override(m, "LEGS", from, "hack_squat_machine")
        o.rev shouldBe 1
        o.anchors.first { it.family == "LEGS" && it.reason == "user" }.slug shouldBe "hack_squat_machine"
        o.anchors.size shouldBe m.anchors.size
        Rotation.parse(Rotation.json(o)) shouldBe o
    }

    @Test
    fun `stall needs more exposures than the window, and a rep best breaks it`() {
        Rotation.stalled(flat("x", 3).exposures, 3) shouldBe false
        Rotation.stalled(flat("x", 4).exposures, 3) shouldBe true
        val xs = flat("x", 4).exposures.sortedBy { it.date }
        Rotation.stalled(xs.dropLast(1) + xs.last().copy(repsAtLoad = listOf(LoadReps(45.0, 11))), 3) shouldBe false
    }
}
