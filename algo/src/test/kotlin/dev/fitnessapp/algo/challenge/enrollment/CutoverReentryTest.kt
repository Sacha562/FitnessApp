package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.OapFixture.zone
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * D40: the real cutover. The elbow protocol started 2026-10-08 and the user has been pain-free since 2026-10-12, so the
 * protocol ends on the first tick after the import; with P29 a lighter week runs to the end of the new block's first
 * week, then the restored pre-injury loads apply.
 */
class CutoverReentryTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val closeMin = params.int("challenge.enrollment.week_close_local_time")
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.2.json")) }

    private fun cutoverImport(): ImportBlock {
        val base = OapFixture.import(mode = "injury", blockWeek = 5, plannedTest = null, needsCleanSession = true)
        val vars = java.util.TreeMap(base.vars)
        vars["injuryStart"] = DateV(LocalDate.parse("2026-10-08"))
        return base.copy(weekOrdinal = 8, vars = vars)
    }

    /** Creation on Thu 2026-10-22 12:00, the decisions, then every synthesized tick up to [until]. */
    private fun foldUntil(until: String, approve: Boolean = true, except: Map<String, Boolean> = emptyMap()): EnrollmentState {
        val events = ArrayList<ChallengeEvent>()
        events += OapFixture.created(cutoverImport(), date = "2026-10-22")
        events += OapFixture.decisions(approve, date = "2026-10-22", except = except)
        var state = EnrollmentFold.fold(def, params, events).state
        val out = TickSynthesizer.synthesize(state, Instant.parse(until), zone, closeMin, OapFixture.facts)
        events += out.events
        state = EnrollmentFold.fold(def, params, events).state
        return state
    }

    private fun plan(state: EnrollmentState, slot: String, date: String): SessionPlan = Materializer.materialize(
        def, params,
        MaterializeInput(slot, LocalDate.parse(date), state, OapFixture.facts, catalog, emptyMap(), emptyMap(), null, null, 5, null, emptyMap()),
    )

    private fun q(s: EnrollmentState, name: String): BigDecimal = (s.vars.getValue(name) as QV).v

    @Test
    fun `the import starts in the elbow protocol with the pre-injury loads`() {
        val created = OapFixture.created(cutoverImport(), date = "2026-10-22")
        val s = EnrollmentFold.fold(def, params, listOf(created) + OapFixture.decisions(true, date = "2026-10-22")).state
        s.mode shouldBe "injury"
        q(s, "pinA").compareTo(BigDecimal(40)) shouldBe 0
        q(s, "wpuA").compareTo(BigDecimal("22.5")) shouldBe 0
        q(s, "pallofKg").compareTo(BigDecimal("11.34")) shouldBe 0
    }

    @Test
    fun `with P29 the protocol ends in a lighter week`() {
        // Day 14 is the setup day itself: its tick (folded after the setup's decisions, 4.3) ends the protocol.
        foldUntil("2026-10-22T12:30:00Z").mode shouldBe "extra_deload"
        val s = foldUntil("2026-10-23T10:00:00Z")
        s.mode shouldBe "extra_deload"
        s.vars.getValue("reentry") shouldBe BoolV.TRUE
        // The snapshot is not lowered; the lighter week comes from the templates.
        q(s, "pinA").compareTo(BigDecimal(40)) shouldBe 0
        val a = plan(s, "A", "2026-10-23")
        val keys = a.items.filter { it.status == "planned" }.map { it.key }
        keys shouldNotContain "cal"
        keys shouldContain "pallof"
        a.items.first { it.key == "oaa" }.rows.first().pin!!.compareTo(BigDecimal(50)) shouldBe 0
        a.items.first { it.key == "wpu" }.rows.first().addedKg!!.compareTo(BigDecimal("17.5")) shouldBe 0
        val b = plan(s, "B", "2026-10-25")
        b.items.filter { it.status == "planned" }.map { it.key } shouldNotContain "neg"
        b.items.map { it.key } shouldNotContain "plank"
    }

    @Test
    fun `the lighter week covers the new block's first week, then the restored loads apply`() {
        val week1 = foldUntil("2026-10-28T10:00:00Z")
        week1.block.index shouldBe 2
        week1.mode shouldBe "extra_deload"
        week1.vars.getValue("reentry") shouldBe BoolV.TRUE
        val after = foldUntil("2026-11-03T10:00:00Z")
        after.mode shouldBe "normal"
        after.vars.getValue("reentry") shouldBe BoolV.FALSE
        val a = plan(after, "A", "2026-11-06")
        a.items.first { it.key == "oaa" }.rows.first().pin!!.compareTo(BigDecimal(40)) shouldBe 0
        a.items.first { it.key == "wpu" }.rows.first().addedKg!!.compareTo(BigDecimal("22.5")) shouldBe 0
    }

    @Test
    fun `without P29 the protocol ends straight at the restored loads`() {
        val s = foldUntil("2026-10-23T10:00:00Z", except = mapOf("P29" to false))
        s.mode shouldBe "normal"
        s.vars.getValue("reentry") shouldBe BoolV.FALSE
        plan(s, "A", "2026-10-23").items.first { it.key == "oaa" }.rows.first().pin!!.compareTo(BigDecimal(40)) shouldBe 0
    }
}
