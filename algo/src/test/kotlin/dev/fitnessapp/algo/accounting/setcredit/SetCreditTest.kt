package dev.fitnessapp.algo.accounting.setcredit

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ALGORITHMS_PLANNING 3.7, the week account and the contract coverage (CHALLENGE_ENGINE 8.2). */
class SetCreditTest {
    private val params = TestParams.defaults()
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val catalog = TestCatalog.catalog
    private val ts = Instant.parse("2026-10-06T18:00:00Z")
    private val recent = LocalDate.parse("2026-10-01")

    private fun set(
        slug: String,
        side: String = "both",
        rir: Double? = null,
        felt: String? = null,
        dur: Double? = null,
        kind: String = "working",
        completed: Boolean = true,
        id: String = "s",
        session: String = "x",
        at: Instant = ts,
    ) = CreditSet(id, session, slug, side, kind, completed, at, durationS = dur, felt = felt, rirReported = rir)

    private fun credit(s: CreditSet, last: LocalDate? = recent, ex: dev.fitnessapp.algo.selection.catalog.CatalogExercise = TestCatalog.ex(s.exerciseSlug)) =
        SetCredit.credit(s, ex, catalog, last, zone, params)

    @Test
    fun `weightedChinupRir2 with the spec's coefficients`() {
        // The spec row: lats 1.0, biceps 0.5, forearms 0.5, rear_delts 0.25, upper_back 0.25 (catalog 1.0.2 has biceps 0.75).
        val ex = TestCatalog.ex("chinup_weighted").copy(
            stimulus = mapOf("lats" to 1.0, "biceps_brachii" to 0.5, "wrist_flexors_grip" to 0.5, "delt_posterior" to 0.25, "traps_lower" to 0.25),
        )
        val c = credit(set("chinup_weighted", rir = 2.0), ex = ex)
        c.fs shouldBe mapOf("lats" to 1.0, "biceps" to 0.5, "forearms" to 0.5)
        c.vhyp shouldBe mapOf("lats" to 0.95, "biceps" to 0.475, "forearms" to 0.475)
        c.vstr shouldBe mapOf("lats" to 1.0)
        c.fat shouldBe mapOf("rear_delts" to 0.25, "lats" to 1.0, "upper_back" to 0.25, "biceps" to 0.5, "forearms" to 0.5)
        c.hard shouldBe true
        c.qSource shouldBe "reported"
    }

    @Test
    fun `assistedOapPair`() {
        val l = credit(set("oap_assisted_machine", "L", rir = 1.0, id = "l"))
        val r = credit(set("oap_assisted_machine", "R", rir = 1.0, id = "r"))
        l.fs shouldBe mapOf("lats" to 0.5, "biceps" to 0.25, "forearms" to 0.25, "abs" to 0.25)
        l.fat.getValue("lats") shouldBe 0.575
        (l.fs.getValue("lats") + r.fs.getValue("lats")) shouldBe 1.0
        (l.vstr.getValue("lats") + r.vstr.getValue("lats")) shouldBe 1.0
        l.sideFs.getValue("lats") shouldBe mapOf("L" to 1.0)
    }

    @Test
    fun `oneArmHang10sHard`() {
        val c = credit(set("oa_dead_hang", "L", felt = "hard", dur = 10.0))
        c.fs shouldBe mapOf("forearms" to 0.125)
        c.fat shouldBe mapOf("forearms" to 0.115)
        c.qSource shouldBe "felt"
    }

    @Test
    fun `topHoldShort and planks20sOk`() {
        credit(set("oa_top_hold", "L", felt = "max", dur = 8.0)).fs.getValue("lats") shouldBe 0.0625
        credit(set("one_arm_plank", "L", felt = "ok", dur = 20.0)).fs shouldBe mapOf("abs" to 0.125)
    }

    @Test
    fun `rir5Credit`() {
        val c = credit(set("leg_extension", rir = 5.0))
        c.vhyp shouldBe mapOf("quads" to 0.5)
        c.hard shouldBe false
        c.vstr shouldBe emptyMap()
        c.sys shouldBe 0.0
    }

    @Test
    fun `warmupZero and skippedZero`() {
        credit(set("leg_extension", rir = 2.0, kind = "warmup")) shouldBe SetCreditOut.zero("s")
        credit(set("leg_extension", rir = 2.0, completed = false)) shouldBe SetCreditOut.zero("s")
    }

    @Test
    fun `noveltyFirstExposure and the 14-21 day band`() {
        credit(set("leg_press_45", rir = 2.0), last = null).fat.getValue("quads") shouldBe 1.5
        credit(set("leg_press_45", rir = 2.0), last = LocalDate.parse("2026-09-20")).fat.getValue("quads") shouldBe 1.25
    }

    @Test
    fun `stabiliserNoCredit`() {
        val c = credit(set("back_squat_barbell", rir = 2.0))
        c.fs shouldNotContainKey "lower_back"
        c.fat.getValue("lower_back") shouldBe 0.25
    }

    @Test
    fun `q sources and the systemic load`() {
        credit(set("leg_press_45", kind = "test")).qUsed shouldBe 0.0
        credit(set("leg_press_45").copy(imputedQ = 3.4)).let {
            it.qSource shouldBe "imputed_reason"
            it.vhyp.getValue("quads") shouldBe 0.9 // STIM at the nearest integer, 3
        }
        credit(set("leg_press_45").copy(plannedRir = 2.0)).qSource shouldBe "planned"
        credit(set("leg_press_45")).let {
            it.qSource shouldBe "default"
            it.qUsed shouldBe 3.0
        }
        // Systemic 2 (0.7) over 15.
        credit(set("leg_press_45", rir = 2.0)).sys shouldBe 0.0467
    }

    @Test
    fun `the week account sums done sets and counts trained sessions`() {
        val monday = Instant.parse("2026-10-04T22:00:00Z")
        val now = Instant.parse("2026-10-07T10:00:00Z")
        val sets = listOf(
            set("leg_press_45", rir = 2.0, id = "a", session = "s1", at = Instant.parse("2026-10-05T18:00:00Z")),
            set("leg_press_45", rir = 2.0, id = "b", session = "s1", at = Instant.parse("2026-10-05T18:05:00Z")),
            set("leg_press_45", rir = 2.0, id = "old", session = "s0", at = Instant.parse("2026-09-28T18:00:00Z")),
            set("leg_press_45", rir = 2.0, id = "late", session = "s2", at = Instant.parse("2026-10-08T18:00:00Z")),
            set("leg_press_45", id = "m", session = "s1", at = Instant.parse("2026-10-05T18:10:00Z")),
        ).map { CreditedSet(it, credit(it)) }
        val acc = SetCredit.account(sets, listOf(mapOf("quads" to 0.5)), listOf(mapOf("quads" to 2.0)), monday, now, zone, params)
        acc.done.getValue("quads").vhyp shouldBe 2.8 // 0.95 + 0.95 + 0.90 (the default-RIR set)
        acc.committed shouldBe mapOf("quads" to 0.5)
        acc.plannedGeneral shouldBe mapOf("quads" to 2.0)
        acc.sessionsTraining.getValue("quads") shouldBe 2 // s1 (2.8) and the planned session (2.0); the committed 0.5 is below 1.0
        acc.daysSinceTrained.getValue("quads") shouldBe 2L
        acc.daysSinceTrained.getValue("chest") shouldBe null
        acc.missingRirShare shouldBe 0.3333
    }

    @Test
    fun `committedEqualsPlanned for the deload session A`() {
        val doc: CatalogDoc = CatalogDoc.parse(ChallengeTestSupport.resource("catalog/catalog-1.0.5.json"))
        val state = OapFixture.start()
        val date = LocalDate.parse("2026-10-08")
        val plan = Materializer.materialize(OapFixture.def, params, MaterializeInput("A", date, state, OapFixture.facts, doc, changeoverMin = 5))
        val start = Instant.parse("2026-10-08T17:45:00Z")
        val planned = Coverage.planned(OapFixture.def, listOf(plan to start), catalog, emptyMap(), zone, params)
        val sets = Coverage.plannedSets(OapFixture.def, plan, catalog, start, params)
        // Warm-up rows never count; every planned working row is credited.
        sets.none { it.setKind == "warmup" } shouldBe true
        planned.single().credits.size shouldBe sets.size
        // The assisted one-arm pairs at creditRir 1: 2 pairs (half of 4, D117), lats 0.5 per side row at STIM 1.0.
        sets.filter { it.exerciseSlug == "oap_assisted_machine" }.size shouldBe 4
        val json = Coverage.contractJson(planned, emptyList(), planned)
        json.getValue("committed") shouldBe json.getValue("planned")
        planned.single().vhyp.getValue("lats") shouldBe (planned.single().credits.sumOf { it.vhyp["lats"] ?: 0.0 } plusOrMinus 1e-9)
    }
}
