package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.ValidationContext
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.PastTest
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.UserKFact
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.UUID

/**
 * GF-UX-10 (UX 25.5): the phase-1 gate on Wednesday 7 October from the imported 30 Aug test (class prior k 30,
 * C59), with the forward threshold at 70.7 kg and the newer non-valid observations on muted lines. The SSE-1
 * training line arrives with SSE-1 (M3); the texts follow the generic row format of [UiGateRows].
 */
class GateRowsGoldenTest {
    private fun resource(path: String) = requireNotNull(javaClass.getResource("/fitness/$path")).readText(Charsets.UTF_8)
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))
    private val def by lazy {
        Cds.load(
            resource("challenges/oap.challenge.v1.json"),
            ValidationContext.fromTexts(
                resource("catalog/catalog-1.0.2.json"), resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json"),
                resource("reference/metric_defs.json"), resource("reference/reason_codes.json"), resource("reference/equipment_types.json"),
                resource("reference/body_regions.json"), resource("reference/symptom_codes.json"), FitExprLimits.from(params), 5,
                EngineVersion.ENGINE_VERSION, params.int("challenge.eca.max_conditional_nesting"),
            ),
        ).orThrow()
    }
    private val facts = EngineFacts(
        bwKg = BigDecimal("70.7"),
        userK = sortedMapOf("weighted_pullup" to UserKFact("chinup_weighted", BigDecimal(30), BigDecimal("0.3"), null, 0)),
    )

    @Test
    fun `GF-UX-10 gate to phase 2 on 7 October`() {
        val metrics = TreeMap<String, Value>(
            mapOf(
                "bw" to Values.mass("71.0"), "wpu_3rm_added" to Values.mass("26"), "wpu_e1rm_added" to Values.mass("32.47"),
                "wpu_e1rm_added_m110" to Values.mass("35.70"), "wpu_e1rm_added_lo" to Values.mass("30.9"), "wpu_e1rm_added_hi" to Values.mass("33.8"),
                "oa_hang_s" to SideV(Values.duration("3"), Values.duration("8")),
                "oa_top_hold_s" to SideV(Values.duration("0.5"), Values.duration("1")),
            ),
        )
        val import = ImportBlock(
            phase = 1, blockIndex = 1, blockWeek = 5, mode = "test_pending", weekOrdinal = 6,
            pastTests = listOf(PastTest(LocalDate.parse("2026-08-30"), metrics, validForGate = true, notComparable = setOf("oa_hang_s"))),
        )
        val created = ChallengeEvent(
            UUID(0, 1), EventTypes.CREATED, Instant.parse("2026-10-07T10:00:00Z"), LocalDate.parse("2026-10-07"),
            EventPayload.Created(UUID(0, 2), sortedMapOf(), TreeMap(mapOf("bw" to Values.mass("70.7"), "anchorLoad" to Values.mass("20"), "wpu3rmAdded" to Values.mass("32.5"), "bwMaxReps" to Values.int(18), "pinA" to Values.pin("40"), "pinC" to Values.pin("50"), "pinHold90" to Values.pin("35"))), import, def.version, def.jcsSha256),
            facts,
        )
        val state = EnrollmentFold.create(def, params, created).state
        val nonValid = listOf(
            NonValidObservation("wpu_e1rm_added", LocalDate.parse("2026-09-19"), Values.mass("39.38"), "3RM only", mapOf("wpu_e1rm_added_m110" to Values.mass("42.82"))),
            NonValidObservation("oa_hang_s", LocalDate.parse("2026-09-29"), SideV(Values.duration("15"), Values.duration("20")), "not a test day"),
        )
        val g = UiGateRows.build(def, params, state, facts, BigDecimal("70.7"), nonValid)
        g.header shouldBe "Goals for phase 2 · 0 of 4 met"
        val (e1rm, hang, neg, top) = g.rows
        e1rm.met shouldBe false
        e1rm.target shouldBe "Target ≥ +43.96 kg at 70.7 kg bodyweight"
        e1rm.detail shouldBe "Tested 30 Aug"
        e1rm.more shouldBe "×1.10 estimate +35.7 kg · 80 % range +30.9 kg to +33.8 kg · 19 Sep: ×1.10 estimate +42.8 kg"
        e1rm.sides.single().fraction shouldBe 0.7386
        e1rm.muted shouldBe "Newer result, not counted (19 Sep, 3RM only): +39.4 kg"
        hang.sides.map { it.text to it.fraction } shouldBe listOf("L no result yet" to 0.0, "R no result yet" to 0.0)
        hang.muted shouldBe "Newer result, not counted (29 Sep, not a test day): L 15 s · R 20 s"
        neg.sides.map { it.text } shouldBe listOf("L not tested", "R not tested")
        top.sides.map { it.text to it.fraction } shouldBe listOf("L 0.5 s of 8 s" to 0.0625, "R 1 s of 8 s" to 0.125)
    }
}
