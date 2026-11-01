package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.ValidationContext
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.params.ParamView
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** D40: the cutover draft's earlier tests reach the import with every value, side and validity flag. */
class SetupImportTest {
    private fun resource(path: String) = requireNotNull(javaClass.getResource("/fitness/$path")).readText(Charsets.UTF_8)
    private val params = ParamView.fromParamsFile(resource("params/default-params-1.0.2.json"))
    private val def by lazy {
        Cds.load(
            resource("challenges/oap.challenge.v1.json"),
            ValidationContext.fromTexts(
                resource("catalog/catalog-1.0.4.json"), resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json"),
                resource("reference/metric_defs.json"), resource("reference/reason_codes.json"), resource("reference/equipment_types.json"),
                resource("reference/body_regions.json"), resource("reference/symptom_codes.json"), FitExprLimits.from(params), 5,
                EngineVersion.ENGINE_VERSION, params.int("challenge.eca.max_conditional_nesting"),
            ),
        ).orThrow()
    }

    // The shape the debug `setup_draft` action stores (the cutover draft of 2026-10-22).
    private val tests = Json.decodeFromString<List<DraftTest>>(
        """
        [
          { "date": "2026-08-30", "validForGate": true,
            "metrics": { "bw": "71.0", "wpu_3rm_added": "26", "oa_hang_s.L": "3", "oa_hang_s.R": "8", "oa_least_assist_pin.L": "35", "oa_least_assist_pin.R": "35" },
            "notComparable": ["oa_hang_s"], "protocolCodes": { "oa_hang_s": "oa_hang_bar_supinated_release_v0" } },
          { "date": "2026-09-19", "validForGate": false, "metrics": { "bw": "70.7", "wpu_3rm_added": "32.5" } },
          { "date": "2026-09-29", "validForGate": false, "metrics": { "oa_hang_s.L": "15", "oa_hang_s.R": "20" } }
        ]
        """,
    )

    @Test
    fun `sided values reach the import as side pairs`() {
        val baseline = SetupImport.pastTest(def, tests[0])!!
        baseline.metrics["oa_hang_s"] shouldBe SideV(Values.duration("3"), Values.duration("8"))
        baseline.metrics["oa_least_assist_pin"] shouldBe SideV(Values.pin("35"), Values.pin("35"))
        baseline.metrics["wpu_3rm_added"] shouldBe Values.mass("26")
        baseline.notComparable shouldBe setOf("oa_hang_s")
        SetupImport.pastTest(def, tests[2])!!.metrics["oa_hang_s"] shouldBe SideV(Values.duration("15"), Values.duration("20"))
    }

    @Test
    fun `validity flags are kept as entered`() {
        tests.map { SetupImport.pastTest(def, it)!!.let { t -> t.date to t.validForGate } } shouldBe listOf(
            LocalDate.parse("2026-08-30") to true, LocalDate.parse("2026-09-19") to false, LocalDate.parse("2026-09-29") to false,
        )
    }
}
