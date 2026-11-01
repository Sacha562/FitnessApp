package dev.fitnessapp.algo.selection

import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.EligReason
import dev.fitnessapp.algo.selection.elig.HardWindow
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.algo.selection.injury.Injury
import dev.fitnessapp.algo.selection.pref.Pref
import dev.fitnessapp.algo.selection.pref.PrefEvent
import dev.fitnessapp.algo.selection.pref.PrefEventKind
import dev.fitnessapp.algo.selection.pref.PrefRow
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.substitute.SubInput
import dev.fitnessapp.algo.selection.substitute.SubSessionItem
import dev.fitnessapp.algo.selection.substitute.Substitute
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** PREF-1, INJ-1, the ELIG-1 hard filters and SUB-1 on the bundled seed (ALGORITHMS_PLANNING 11.4, 12.5, 15.2, 18.3, 22.3). */
class SelectionTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing resource $path" }.readText(Charsets.UTF_8)

    private val taxonomy = Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json"))
    private val catalog = Catalog(CatalogDoc.parse(resource("catalog/catalog-1.0.4.json")), taxonomy)
    private val params = TestParams.defaults()
    private val today = LocalDate.of(2026, 10, 28)
    private fun ex(slug: String) = requireNotNull(catalog.exercise(slug)) { slug }

    // ------------------------------------------------------------------------------------------------ PREF-1

    @Test
    fun `never is zero`() {
        Pref.evaluate(PrefRow(PrefTier.NEVER), emptyList(), today, params).m shouldBe 0.0
    }

    @Test
    fun `implicit signal only without an explicit row`() {
        val swaps = List(3) { PrefEvent(PrefEventKind.SWAPPED_AWAY, "other", today.minusDays(it.toLong())) }
        Pref.evaluate(PrefRow(PrefTier.NEUTRAL), swaps, today, params).m shouldBe 1.0
        Pref.evaluate(null, swaps, today, params).m shouldNotBe 1.0
    }

    @Test
    fun `external reason is ignored`() {
        val v = Pref.evaluate(null, listOf(PrefEvent(PrefEventKind.SWAPPED_AWAY, "occupied", today)), today, params)
        v.z shouldBe 0.0
        v.m shouldBe 1.0
    }

    @Test
    fun `ten manual adds clamp at 1_3`() {
        Pref.evaluate(null, List(10) { PrefEvent(PrefEventKind.ADDED, null, today) }, today, params).m shouldBe 1.3
    }

    @Test
    fun `prompt after three swaps`() {
        val two = List(2) { PrefEvent(PrefEventKind.SWAPPED_AWAY, "other", today.minusDays(it * 7L)) }
        Pref.prompt(two, today, params) shouldBe false
        Pref.prompt(two + PrefEvent(PrefEventKind.SWAPPED_AWAY, "other", today.minusDays(41)), today, params) shouldBe true
        Pref.prompt(two + PrefEvent(PrefEventKind.SWAPPED_AWAY, "other", today.minusDays(42)), today, params) shouldBe false
        Pref.prompt(two + PrefEvent(PrefEventKind.SWAPPED_AWAY, "occupied", today), today, params) shouldBe false
    }

    @Test
    fun `an unavailable date hides without changing the tier`() {
        val v = Pref.evaluate(PrefRow(null, today.plusDays(3)), emptyList(), today, params)
        v.hidden shouldBe true
        v.tier shouldBe PrefTier.NEUTRAL
        Pref.evaluate(PrefRow(null, today), emptyList(), today, params).hidden shouldBe false
    }

    @Test
    fun `apply to similar keeps the pattern`() {
        val sim = Pref.similar(ex("leg_press_45"), catalog, params)
        sim.all { it.pattern == "squat_machine" } shouldBe true
        sim.map { it.slug } shouldNotContain "leg_press_45"
    }

    // ------------------------------------------------------------------------------------------------ INJ-1

    @Test
    fun `elbow severity 2 excludes stress 2 and keeps stress 1`() {
        val flags = mapOf("elbow_medial" to 2)
        val stressed = catalog.exercises.filter { it.js("elbow_medial") >= 2 }
        val light = catalog.exercises.filter { it.js("elbow_medial") == 1 }
        stressed.isNotEmpty() shouldBe true
        stressed.all { Injury.excludes(it, flags, params) != null } shouldBe true
        light.all { Injury.excludes(it, flags, params) == null } shouldBe true
    }

    @Test
    fun `lumbar severity 3 excludes the back squat and keeps the leg press`() {
        val flags = mapOf("lumbar" to 3)
        Injury.excludes(ex("back_squat_barbell"), flags, params) shouldNotBe null
        Injury.excludes(ex("leg_press_45"), flags, params) shouldBe null
    }

    @Test
    fun `severity 1 only penalises`() {
        val e = ex("back_squat_barbell")
        val flags = mapOf("lumbar" to 1)
        Injury.excludes(e, flags, params) shouldBe null
        Injury.jointPenaltyPerSet(e, flags, params) shouldBe 0.3 * e.js("lumbar")
    }

    // ------------------------------------------------------------------------------------------------ ELIG-1

    @Test
    fun `never is never in the pool`() {
        val ctx = EligContext(prefs = mapOf("leg_press_45" to Pref.evaluate(PrefRow(PrefTier.NEVER), emptyList(), today, params)))
        Elig.reason(ex("leg_press_45"), catalog, ctx, params) shouldBe EligReason.NEVER
    }

    @Test
    fun `a missing station removes the exercise`() {
        val ctx = EligContext(availableStations = setOf("barbell", "dumbbells", "adjustable_bench"))
        Elig.reason(ex("leg_press_45"), catalog, ctx, params) shouldBe EligReason.STATION
        Elig.reason(ex("db_bench_press"), catalog, ctx, params) shouldBe null
    }

    @Test
    fun `skip goal removes a glute 0_5 exercise and keeps the leg extension`() {
        val ctx = EligContext(skipGroups = setOf("glutes"))
        (catalog.groupCoeff(ex("leg_press_45"), "glutes") >= 0.5) shouldBe true
        Elig.reason(ex("leg_press_45"), catalog, ctx, params) shouldBe EligReason.SKIP_GOAL
        Elig.reason(ex("leg_extension"), catalog, ctx, params) shouldBe null
    }

    @Test
    fun `gap day removes grip heavy and heavy pull, keeps the reverse pec deck`() {
        val ctx = EligContext(gapDay = true, forbiddenSessionTags = listOf("grip_heavy", "elbow_flexor_heavy"), lightPull = LightPullRule(1, 0.5))
        Elig.reason(ex("rdl_barbell"), catalog, ctx, params) shouldBe EligReason.GAP_DAY_TAG
        Elig.reason(ex("reverse_pec_deck"), catalog, ctx, params) shouldBe null
        val rows = catalog.exercises.filter { it.pattern == "horiz_pull" || it.pattern == "vert_pull" }
        rows.all { Elig.reason(it, catalog, ctx, params) != null } shouldBe true
    }

    @Test
    fun `a hard window removes a brachialis hit`() {
        val ctx = EligContext(hardWindows = listOf(HardWindow("w", groups = listOf("biceps"), regions = emptyList())))
        Elig.reason(ex("hammer_curl_db"), catalog, ctx, params) shouldBe EligReason.HARD_WINDOW
        Elig.reason(ex("leg_extension"), catalog, ctx, params) shouldBe null
    }

    @Test
    fun `add-on patterns remove rows and keep the leg press`() {
        val ctx = EligContext(addOn = AddOnFilter(listOf("squat", "squat_machine", "lunge", "knee_ext", "knee_flex", "calf"), 1, listOf("lats"), emptyList()))
        Elig.reason(ex("leg_press_45"), catalog, ctx, params) shouldBe null
        catalog.exercises.filter { it.pattern == "horiz_pull" }.all { Elig.reason(it, catalog, ctx, params) != null } shouldBe true
    }

    @Test
    fun `challenge-local rows stay out of general blocks`() {
        catalog.exercises.filter { "challenge" in it.tags }.all { Elig.reason(it, catalog, EligContext(), params) == EligReason.CHALLENGE_ONLY } shouldBe true
    }

    // ------------------------------------------------------------------------------------------------ SUB-1

    private fun rank(from: String, session: Set<String>, prefs: Map<String, PrefTier> = emptyMap()) = Substitute.rank(
        SubInput(
            ex(from), Elig.pool(catalog, EligContext(), params).pool, session + from,
            occupiedStations = ex(from).requiresStation.toSet(),
            prefs = prefs.mapValues { Pref.evaluate(PrefRow(it.value), emptyList(), today, params) },
        ),
        catalog, params,
    )

    @Test
    fun `leg press occupied offers the hack squat and the seated leg press first (D64)`() {
        val r = rank("leg_press_45", setOf("rdl_barbell", "hip_thrust_barbell", "seated_leg_curl", "leg_extension", "hip_abduction_machine", "leg_press_calf_raise"))
        // 22.3 regenerated with the seed's full region vectors (the prototype listed hack squat, BSS, back squat).
        // D64: the seated leg press is its own station, so the occupied 45° sled no longer hides it.
        r.map { it.slug to it.score } shouldBe listOf("hack_squat_machine" to 0.9339, "leg_press_seated" to 0.9339, "goblet_squat_db" to 0.8565)
    }

    @Test
    fun `chest press occupied offers the dumbbell bench first`() {
        val r = rank("machine_chest_press", setOf("incline_db_press", "db_lateral_raise", "overhead_db_triceps_ext"))
        r.map { it.slug to it.score } shouldBe listOf("db_bench_press" to 0.9339, "push_up" to 0.9204, "decline_db_press" to 0.9186)
    }

    @Test
    fun `disliked only when no other candidate`() {
        val r = rank("leg_press_45", emptySet(), mapOf("hack_squat_machine" to PrefTier.DISLIKE))
        r.map { it.slug } shouldNotContain "hack_squat_machine"
        r.all { it.tier != PrefTier.DISLIKE } shouldBe true
    }

    @Test
    fun `never is never offered`() {
        val prefs = mapOf("hack_squat_machine" to PrefTier.NEVER)
        val pool = Elig.pool(catalog, EligContext(prefs = prefs.mapValues { Pref.evaluate(PrefRow(it.value), emptyList(), today, params) }), params).pool
        Substitute.scored(SubInput(ex("leg_press_45"), pool), catalog, params).map { it.slug } shouldNotContain "hack_squat_machine"
        rank("leg_press_45", emptySet(), prefs).map { it.slug } shouldNotContain "hack_squat_machine"
    }

    @Test
    fun `reorder first skips done, pinned and same-station exercises`() {
        val order = listOf(
            SubSessionItem("a", "leg_press_45", notDone = true, pinned = false, stations = listOf("leg_press")),
            SubSessionItem("b", "leg_press_calf_raise", notDone = true, pinned = false, stations = listOf("leg_press")),
            SubSessionItem("c", "leg_extension", notDone = false, pinned = false, stations = listOf("leg_extension")),
            SubSessionItem("d", "pallof", notDone = true, pinned = true, stations = listOf("cable_station")),
            SubSessionItem("e", "seated_leg_curl", notDone = true, pinned = false, stations = listOf("leg_curl_seated")),
        )
        Substitute.doNow(order, "a", emptySet(), null, catalog, params)?.id shouldBe "e"
        // Every remaining item counts, not only the later ones (D62): from e, a is still open on another station.
        Substitute.doNow(order, "e", emptySet(), null, catalog, params)?.id shouldBe "a"
        Substitute.doNow(order.map { if (it.id in setOf("a", "b")) it.copy(notDone = false) else it }, "e", emptySet(), null, catalog, params) shouldBe null
    }

    @Test
    fun `do k now follows ORD-1 order, not the session's (D62)`() {
        fun item(id: String, slug: String) = SubSessionItem(id, slug, notDone = true, pinned = false, stations = ex(slug).requiresStation, exercise = ex(slug))
        val order = listOf(item("a", "leg_press_45"), item("b", "leg_extension"), item("c", "hack_squat_machine"))
        Substitute.doNow(order, "a", setOf("quads"), null, catalog, params)?.id shouldBe "c"
    }

    @Test
    fun `candidates on a busy station rank after the others (D62)`() {
        val free = rank("leg_press_45", emptySet())
        val top = ex(free.first().slug)
        val busy = Substitute.rank(
            SubInput(
                ex("leg_press_45"), Elig.pool(catalog, EligContext(), params).pool, setOf("leg_press_45"),
                occupiedStations = ex("leg_press_45").requiresStation.toSet(), busyStations = top.requiresStation.toSet(),
            ),
            catalog, params,
        )
        busy.first().slug shouldNotBe top.slug
        val onBusy = busy.map { b -> ex(b.slug).requiresStation.any { it in top.requiresStation } }
        onBusy shouldBe onBusy.sorted()
    }

    @Test
    fun `ranking is deterministic`() {
        rank("leg_press_45", emptySet()) shouldBe rank("leg_press_45", emptySet())
    }
}

