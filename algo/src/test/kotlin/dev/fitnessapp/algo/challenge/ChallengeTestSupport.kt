package dev.fitnessapp.algo.challenge

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.LoadResult
import dev.fitnessapp.algo.challenge.cds.ValidationContext
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.testing.TestParams

/** Bundled reference data and the OAP definition, loaded once for the challenge tests. */
object ChallengeTestSupport {
    fun resource(path: String): String =
        requireNotNull(ChallengeTestSupport::class.java.getResource("/fitness/$path")) { "missing resource $path" }.readText(Charsets.UTF_8)

    val context: ValidationContext by lazy {
        val params = TestParams.defaults()
        ValidationContext.fromTexts(
            catalog = resource("catalog/catalog-1.0.5.json"),
            muscleGroups = resource("reference/muscle_groups.json"),
            muscleRegions = resource("reference/muscle_regions.json"),
            metricDefs = resource("reference/metric_defs.json"),
            reasonCodes = resource("reference/reason_codes.json"),
            equipmentTypes = resource("reference/equipment_types.json"),
            bodyRegions = resource("reference/body_regions.json"),
            symptomCodes = resource("reference/symptom_codes.json"),
            limits = FitExprLimits.from(params),
            changeoverMin = 5,
            engineVersion = EngineVersion.ENGINE_VERSION,
            maxConditionalNesting = params.int("challenge.eca.max_conditional_nesting"),
        )
    }

    val oapText: String by lazy { resource("challenges/oap.challenge.v1.json") }

    val oapResult: LoadResult by lazy { Cds.load(oapText, context) }

    val oap: CompiledDefinition by lazy { oapResult.orThrow() }
}
