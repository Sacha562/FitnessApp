package dev.fitnessapp.algo.selection.order

import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.select.SelectFixtures
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** ORD-1 (ALGORITHMS_PLANNING 14.3) on the seed. */
class OrderTest {
    private val params = TestParams.defaults()
    private val catalog = TestCatalog.catalog
    private val stations = SelectFixtures.stations

    private fun input(vararg items: Pair<String, Int>) = items.map { (slug, s) -> OrderInput(TestCatalog.ex(slug), s) }

    @Test
    fun `legs order of 22_1 with the calf press chained to the leg press`() {
        val ordered = Order.order(
            input("hip_abduction_machine" to 2, "leg_press_calf_raise" to 3, "seated_leg_curl" to 4, "hip_thrust_smith" to 2, "leg_extension" to 2,
                "rdl_barbell" to 2, "leg_press_45" to 4),
            setOf("quads", "glutes", "hamstrings"), stations, catalog, params,
        )
        ordered.map { it.slug } shouldContainExactly listOf(
            "leg_press_45", "leg_press_calf_raise", "rdl_barbell", "hip_thrust_smith", "seated_leg_curl", "leg_extension", "hip_abduction_machine",
        )
        ordered[1].chainedAfter shouldBe "leg_press_45"
        ordered[1].setupS shouldBe 15.0
    }

    @Test
    fun `pre-fatigue wins over chaining`() {
        // A synthetic quad isolation on the leg press station: it may not move in front of the hack squat.
        val legExtOnPress = TestCatalog.ex("leg_extension").copy(slug = "zz_leg_ext_on_press", requiresStation = listOf("leg_press"))
        val cat = Catalog(CatalogDoc("test", TestCatalog.doc.exercises + legExtOnPress, emptyList()), TestCatalog.taxonomy)
        val ordered = Order.order(
            listOf(OrderInput(TestCatalog.ex("leg_press_45"), 3), OrderInput(TestCatalog.ex("hack_squat_machine"), 2), OrderInput(legExtOnPress, 2)),
            setOf("quads"), stations, cat, params,
        )
        ordered.map { it.slug } shouldContainExactly listOf("leg_press_45", "hack_squat_machine", "zz_leg_ext_on_press")
        ordered.last().chainedAfter shouldBe null
    }

    @Test
    fun `grip-heavy work goes after a grip-heavy anchor`() {
        val ordered = Order.order(
            listOf(OrderInput(TestCatalog.ex("barbell_row_pronated"), 3), OrderInput(TestCatalog.ex("rdl_barbell"), 3, anchor = true)),
            setOf("upper_back"), stations, catalog, params,
        )
        ordered.map { it.slug } shouldContainExactly listOf("rdl_barbell", "barbell_row_pronated")
    }

    @Test
    fun `dumbbells are not a station to chain on`() {
        val ordered = Order.order(input("db_lateral_raise" to 2, "incline_db_press" to 3, "machine_chest_press" to 3), setOf("chest", "side_delts"), stations, catalog, params)
        ordered.map { it.slug } shouldContainExactly listOf("incline_db_press", "machine_chest_press", "db_lateral_raise")
        ordered.all { it.chainedAfter == null } shouldBe true
    }

    private fun time(slug: String, sets: Int, reps: Double): TimeItem {
        val e = TestCatalog.ex(slug)
        return TimeItem(slug, sets, params.table("rest.base").getValue(e.restClass), e.setupS.toDouble(), reps = reps, unilateralPair = e.laterality != "bilateral")
    }

    @Test
    fun `supersets pair dissimilar exercises only`() {
        val raise = time("db_lateral_raise", 3, 15.0)
        val curl = time("seated_leg_curl", 3, 10.0)
        val press = time("machine_chest_press", 3, 10.0)
        val dip = time("dip_chest", 3, 10.0)
        val base = mapOf("db_lateral_raise" to 90.0, "seated_leg_curl" to 90.0, "machine_chest_press" to 120.0, "dip_chest" to 120.0)
        val ok = Order.supersets(
            listOf(Ordered("db_lateral_raise", 3, 99), Ordered("seated_leg_curl", 3, 99)), mapOf(raise.key to raise, curl.key to curl), base,
            0.0, 0.0, true, emptySet(), stations, catalog, params,
        )
        ok.map { it.a to it.b } shouldContainExactly listOf("db_lateral_raise" to "seated_leg_curl")
        Order.supersets(
            listOf(Ordered("machine_chest_press", 3, 99), Ordered("dip_chest", 3, 99)), mapOf(press.key to press, dip.key to dip), base,
            0.0, 0.0, true, emptySet(), stations, catalog, params,
        ).shouldBeEmpty()
    }

    @Test
    fun `superset rests come from REST-1`() {
        // Lateral raise 3 x 15 (work 50 s, setup 20 s) + seated leg curl 3 x 10 (work 35 s, setup 40 s): 60 s between the members,
        // after = max(rest.min_s 60, 90 - 60 - 35, 90 - 60 - 50) = 60; t_pair = 20 + 40 + 3 (50 + 60 + 35 + 60) - 60 + 60 = 675 s.
        val s = Order.pair(time("db_lateral_raise", 3, 15.0), time("seated_leg_curl", 3, 10.0), 90.0, 90.0, params)
        s.betweenS shouldBe 60.0
        s.afterS shouldBe 60.0
        s.pairS shouldBe 675.0
        s.savedS shouldBe 120.0 // t(raise) 410 + t(curl) 385 - 675
    }
}
