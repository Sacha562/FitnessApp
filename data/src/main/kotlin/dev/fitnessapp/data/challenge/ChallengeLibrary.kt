package dev.fitnessapp.data.challenge

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.LoadResult
import dev.fitnessapp.algo.challenge.cds.ValidationContext
import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.entity.ChallengeDefinitionEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The challenge definitions the phone can run: the bundled files (`fitness/challenges/`, DATA_MODEL 11.10) and
 * rows pulled from `fitness.challenge_definitions`. Every definition is validated (CDS stages 1 and 2) with the
 * bundled catalog and reference data before use, and a stored row must reproduce its `jcs_sha256` (11.10:
 * a definition whose recomputed hash differs is rejected).
 */
class ChallengeLibrary(private val params: ParamView, private val changeoverMin: Int, private val resource: (String) -> String) {
    val context: ValidationContext by lazy {
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
            changeoverMin = changeoverMin,
            engineVersion = EngineVersion.ENGINE_VERSION,
            maxConditionalNesting = params.int("challenge.eca.max_conditional_nesting"),
        )
    }

    private val compiled = ConcurrentHashMap<String, CompiledDefinition>()

    /** The definitions shipped with this app version, with their file text. */
    val bundled: List<Pair<String, CompiledDefinition>> by lazy {
        BUNDLED.map { name -> resource("challenges/$name").let { it to load(it).orThrow() } }
    }

    fun load(text: String): LoadResult = Cds.load(text, context)

    /** The row's compiled definition; throws when it does not validate or its hash differs from the row's. */
    fun compile(row: ChallengeDefinitionEntity): CompiledDefinition = compiled.getOrPut(row.id) {
        val def = load(row.definition).orThrow()
        require(def.jcsSha256 == row.jcs_sha256) { "definition ${row.slug}@${row.version}: jcs_sha256 differs from the stored row" }
        def
    }

    fun row(text: String, def: CompiledDefinition, source: String): ChallengeDefinitionEntity = ChallengeDefinitionEntity(
        id = idOf(def).toString(), slug = def.slug, version = def.version, status = "published", schema_version = def.doc.schema,
        engine_range = def.doc.engine, title_en = def.doc.title.getValue("en"), title_nl = def.doc.title["nl"],
        definition = text, jcs_sha256 = def.jcsSha256, source = source,
    )

    companion object {
        /** File names under `fitness/challenges/`. */
        val BUNDLED: List<String> = listOf("oap.challenge.v1.0.0.json", "oap.challenge.v1.0.1.json", "oap.challenge.v1.1.0.json", "oap.challenge.v1.2.0.json", "oap.challenge.v1.3.0.json", "oap.challenge.v1.4.0.json", "oap.challenge.v1.5.0.json", "oap.challenge.v1.6.0.json", "oap.challenge.v1.7.0.json", "oap.challenge.v1.8.0.json", "oap.challenge.v1.9.0.json", "oap.challenge.v1.10.0.json", "oap.challenge.v1.json")

        /** DATA_MODEL 6.4: `challenge_definition:<slug>@<version>`. */
        fun idOf(def: CompiledDefinition) = idOf(def.slug, def.version)

        fun idOf(slug: String, version: String) = Ids.fitness("challenge_definition:$slug@$version")
    }
}
