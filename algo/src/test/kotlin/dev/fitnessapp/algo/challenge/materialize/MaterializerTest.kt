package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** GF-CE-21, 22, 23, 34, 35, 38 and the basic shape of the phase-1 sessions. */
class MaterializerTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val catalog: CatalogDoc by lazy { CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.1.json")) }
    private val facts: EngineFacts = OapFixture.facts.copy(
        has = OapFixture.facts.has + listOf("adjustable_bench", "barbell", "seated_cable_row", "chest_supported_row"),
    )

    private fun plan(
        state: EnrollmentState,
        slot: String,
        f: EngineFacts = facts,
        prefs: Map<String, String> = emptyMap(),
        booked: Int? = null,
        est: String? = null,
        last: Map<String, BigDecimal> = emptyMap(),
        reasons: Map<String, String> = emptyMap(),
    ): SessionPlan = Materializer.materialize(
        def, params,
        MaterializeInput(slot, LocalDate.parse("2026-10-14"), state, f, catalog, prefs, reasons, booked, est?.let(::BigDecimal), 5, null, last),
    )

    private fun normal(approve: Boolean = true, except: Map<String, Boolean> = emptyMap()) =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false), approve, except)

    private fun SessionPlan.item(key: String) = items.first { it.key == key }
    private fun PlannedItem.pairs() = rows.mapNotNull { it.pairIndex }.distinct().size

    @Test
    fun `a rep range prescribes its lower end as the target`() {
        // B1 dips: "repsRange": [8, 10] (seen on the phone as "Dip 3x" with no target).
        val dip = plan(normal(), "B").item("dip")
        dip.rows.map { it.targetReps to it.targetRepsMax }.distinct() shouldBe listOf(8 to 10)
    }

    @Test
    fun `phase-1 session A in a normal week`() {
        val p = plan(normal(), "A")
        p.templateId shouldBe "A1"
        p.items.map { it.key } shouldBe listOf("wu_face", "wu_scap", "wu_pull", "oaa", "cal", "wpu", "top", "h90", "sth", "pallof", "pre_rwc", "pre_ps", "pre_fp")
        val oaa = p.item("oaa")
        oaa.pairs() shouldBe 5
        oaa.rows.first().pin shouldBe BigDecimal(40)
        oaa.rows.first().assistKg shouldBe BigDecimal("24.64")
        oaa.rows.first().oneArmLoadKg shouldBe BigDecimal("46.06")
        oaa.rows.first().logMode shouldBe LogMode.RIR_CHIPS
        oaa.inSessionRuleIds shouldBe listOf("ins.A.oaa.calibrate", "ins.A.oaa.fail", "ins.rotationStop")
        p.item("wpu").rows.map { it.addedKg } shouldBe List(4) { BigDecimal("22.5") }
        p.item("wpu").rows.first().systemLoadKg shouldBe BigDecimal("93.20")
        p.item("cal").rows.map { it.logMode } shouldBe listOf(LogMode.ECC_SECONDS, LogMode.ECC_SECONDS)
        p.item("top").rows.size shouldBe 6
        p.item("h90").rows.first().targetHoldS shouldBe BigDecimal(6)
        p.item("h90").rows.first().targetHoldMaxS shouldBe BigDecimal(8)
        p.item("sth").rows.first().targetHoldS shouldBe BigDecimal(6)
        p.item("wu_pull").noteCodes shouldContain "ramp"
    }

    @Test
    fun `no warm-up holds a cardio exercise (D44)`() {
        val families = catalog.exercises.associate { it.slug to it.family }
        for (slot in listOf("A", "B", "C", "TEST")) {
            val warmup = plan(normal(), slot).items.filter { it.role == "warmup" }
            warmup.map { it.key } shouldBe listOf("wu_face", "wu_scap", "wu_pull")
            warmup.filter { families[it.catalogSlug] == "cardio" } shouldBe emptyList()
        }
    }

    @Test
    fun `GF-CE-21 test-pending week in deload form`() {
        val s = OapFixture.start()
        val p = plan(s, "A")
        p.item("oaa").pairs() shouldBe 3
        p.item("wpu").rows.size shouldBe 2
        p.item("wpu").rows.first().addedKg shouldBe BigDecimal("22.5")
        p.item("top").rows.mapNotNull { it.pairIndex }.distinct().size shouldBe 2
        p.item("h90").pairs() shouldBe 2
        p.item("sth").pairs() shouldBe 2
        p.item("wu_pull").rows.size shouldBe 2
        p.item("oaa").rows.first().pin shouldBe BigDecimal(40)
        // P19 declined: the waiting week keeps the full sessions.
        val full = plan(OapFixture.start(except = mapOf("P19" to false)), "A")
        full.item("oaa").pairs() shouldBe 5
    }

    @Test
    fun `GF-CE-22 typewriters need a wide bar`() {
        val s = normal().copy(phase = 2)
        val p = plan(s, "C")
        p.templateId shouldBe "C2"
        p.item("tw").status shouldBe "excluded"
        plan(s, "C", f = facts.copy(has = facts.has + "wide_bar")).item("tw").status shouldBe "planned"
    }

    @Test
    fun `GF-CE-23 the row follows the preferences`() {
        val withWide = facts.copy(has = facts.has + "wide_bar")
        val prefs = mapOf("barbell_row_pronated" to "never", "seated_cable_row_wide_pronated" to "like", "chest_supported_row_pronated" to "neutral")
        plan(normal(), "B", f = withWide, prefs = prefs).item("row").exerciseKey shouldBe "seated_cable_row_wide_pronated"
        val allNever = prefs.mapValues { "never" }
        val p = plan(normal(), "B", f = withWide, prefs = allNever)
        p.item("row").exerciseKey shouldBe "barbell_row_pronated"
        p.item("row").warnings.map { it.code } shouldContain "challenge_never_conflict"
        p.item("row").rows.first().addedKg shouldBe null
        p.item("row").progressionMode shouldBe "delegate:rx"
    }

    @Test
    fun `GF-CE-34 a rule load above the generic cap warns and is not clamped`() {
        val s = normal().let { it.copy(vars = java.util.TreeMap(it.vars).also { v -> v["pinC"] = OapFixture.pin("45") }) }
        val p = plan(s, "C", last = mapOf("oaa" to BigDecimal("41.52")))
        p.item("oaa").rows.first().pin shouldBe BigDecimal(45)
        p.item("oaa").warnings.map { it.code to it.severity } shouldContain ("WE-S2" to "WARN")
    }

    @Test
    fun `GF-CE-35 add-on capacity uses one changeover`() {
        val c = plan(normal(), "C", booked = 90, est = "60.8")
        c.budgetMin shouldBe 85
        c.addOnCapacityMin shouldBe 24
        c.addOnOffered shouldBe true
        val a = plan(normal(), "A", booked = 90, est = "79.7")
        a.addOnCapacityMin shouldBe 5
        a.addOnOffered shouldBe false
        plan(normal(), "B", booked = 90, est = "60").addOnCapacityMin shouldBe null
    }

    @Test
    fun `GF-CE-38 test-day order`() {
        val declined = plan(OapFixture.start(except = mapOf("P08" to false, "P20" to false)), "TEST")
        declined.items.map { it.key } shouldBe listOf("bw", "wu_face", "wu_scap", "wu_pull", "str", "max", "hang", "top", "assist", "neg")
        val approved = plan(OapFixture.start(), "TEST")
        approved.items.map { it.key } shouldBe listOf("bw", "wu_face", "wu_scap", "wu_pull", "str", "hang", "top", "assist", "neg", "max")
        approved.item("assist").notes.last()["en"]!!.startsWith("Start at the A pin + 10 lb") shouldBe true
        approved.item("hang").rows.map { it.side } shouldBe listOf("L", "R")
        approved.item("hang").rows.first().logMode shouldBe LogMode.TEST_RECORD
    }

    @Test
    fun `D36 the test protocol steps follow P21`() {
        val approved = plan(OapFixture.start(), "TEST")
        val str = approved.item("str").steps!!
        // Expected 3RM total = e1RM total / (1 + 2 / k): (70.70 + 39.38) / (32 / 30) = 103.20 kg, so +32.5 kg.
        str.start shouldBe BigDecimal("32.5")
        str.step shouldBe BigDecimal("2.5")
        str.maxAttempts shouldBe 3
        str.ramp.first().addedKg shouldBe BigDecimal.ZERO
        str.ramp.map { it.addedKg } shouldBe str.ramp.map { it.addedKg }.sorted()
        str.ramp.all { it.addedKg < str.start!! && it.repsMin == 1 && it.repsMax == 3 } shouldBe true
        val assist = approved.item("assist").steps!!
        assist.start shouldBe BigDecimal("50")
        assist.step shouldBe BigDecimal("-5")
        assist.maxAttempts shouldBe 5
        approved.item("hang").steps shouldBe null
        val declined = plan(OapFixture.start(except = mapOf("P21" to false)), "TEST")
        declined.item("str").steps shouldBe null
        declined.item("assist").steps shouldBe null
        PlanJson.decode(PlanJson.encode(approved)) shouldBe approved
    }

    @Test
    fun `injury mode drops unilateral and eccentric work and doubles prehab`() {
        val s = normal().copy(mode = "injury")
        val a = plan(s, "A")
        a.item("oaa").status shouldBe "excluded"
        a.item("cal").status shouldBe "excluded"
        a.item("wpu").rows.first().addedKg shouldBe BigDecimal.ZERO
        a.item("wpu").rows.size shouldBe 2
        a.item("wpu").rirTarget shouldBe 3
        a.item("pre_rwc").rows.size shouldBe 4
    }

    @Test
    fun `the busy machine becomes finger assistance without progression data`() {
        val p = plan(normal(), "A", reasons = mapOf("oaa" to "occupied"))
        val oaa = p.item("oaa")
        oaa.exerciseKey shouldBe "oap_finger_assisted"
        oaa.substitutedFrom shouldBe "oap_assisted_machine"
        oaa.progressionExcluded shouldBe true
        oaa.rows.first().fingers shouldBe 4
        oaa.rows.first().pin shouldBe null
    }

    @Test
    fun `B in the ladder variant`() {
        val s = normal().let { it.copy(vars = java.util.TreeMap(it.vars).also { v -> v["bVariant"] = dev.fitnessapp.algo.challenge.fitexpr.Values.str("ladder123") }) }
        val wpu = plan(s, "B").item("wpu")
        wpu.rows.map { it.targetReps } shouldBe listOf(1, 2, 3, 1, 2, 3, 1, 2, 3)
        wpu.rows.map { it.restS } shouldBe listOf(60, 60, 180, 60, 60, 180, 60, 60, 180)
    }
}
