package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.LoadResult
import dev.fitnessapp.algo.challenge.cds.ValidationContext
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogDoc

/** The bundled `fitness/` resources of `:algo` (catalog, reference data, params, definitions) for the CLI and tests. */
object SimResources {
    const val PARAMS_FILE: String = "params/default-params-1.0.1.json"
    const val CATALOG_FILE: String = "catalog/catalog-1.0.1.json"

    /** C49: the profile default changeover. */
    const val CHANGEOVER_MIN: Int = 5

    fun text(path: String): String =
        requireNotNull(SimResources::class.java.getResource("/fitness/$path")) { "missing resource fitness/$path" }.readText(Charsets.UTF_8)

    fun params(): ParamView = ParamView.fromParamsFile(text(PARAMS_FILE))

    val catalog: CatalogDoc by lazy { CatalogDoc.parse(text(CATALOG_FILE)) }

    val context: ValidationContext by lazy {
        val p = params()
        ValidationContext.fromTexts(
            catalog = text(CATALOG_FILE),
            muscleGroups = text("reference/muscle_groups.json"),
            muscleRegions = text("reference/muscle_regions.json"),
            metricDefs = text("reference/metric_defs.json"),
            reasonCodes = text("reference/reason_codes.json"),
            equipmentTypes = text("reference/equipment_types.json"),
            bodyRegions = text("reference/body_regions.json"),
            symptomCodes = text("reference/symptom_codes.json"),
            limits = FitExprLimits.from(p),
            changeoverMin = CHANGEOVER_MIN,
            engineVersion = EngineVersion.ENGINE_VERSION,
            maxConditionalNesting = p.int("challenge.eca.max_conditional_nesting"),
        )
    }

    fun load(text: String): LoadResult = Cds.load(text, context)

    val oap: CompiledDefinition by lazy { load(text("challenges/oap.challenge.v1.json")).orThrow() }
}
