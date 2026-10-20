package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.fitexpr.FitExprLimits
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One `fitness.metric_defs` row as the validator needs it (DATA_MODEL 9.1.4). */
data class MetricDef(val code: String, val valueType: String, val unit: String, val sideMode: String)

/**
 * The reference data of the target `catalog_version` that stage 2 checks a definition against (2.6): the
 * catalog, the muscle taxonomy and the DATA_MODEL code tables. `:algo` does no IO; callers pass file texts.
 */
class ValidationContext(
    val catalog: CatalogDoc,
    val taxonomy: Taxonomy,
    val metricDefs: Map<String, MetricDef>,
    val reasonCodes: Set<String>,
    val equipmentTypes: Set<String>,
    val bodyRegions: Set<String>,
    val symptomCodes: Set<String>,
    val limits: FitExprLimits,
    /** C49 changeover (the profile default, 5 min) that add-on `bufferMin` must equal (V13). */
    val changeoverMin: Int,
    val engineVersion: String,
    /** `challenge.eca.max_conditional_nesting` (4). */
    val maxConditionalNesting: Int,
) {
    val catalogSlugs: Set<String> = catalog.exercises.map { it.slug }.toSortedSet()
    val patterns: Set<String> = CatalogDomains.PATTERNS.toSortedSet()
    val reservedTags: Set<String> = CatalogDomains.RESERVED_TAGS.toSortedSet()
    val groupIds: Set<String> = taxonomy.activeGroups.map { it.id }.toSortedSet()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun rows(text: String): List<JsonObject> = json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.map { it.jsonObject }

        private fun codes(text: String, key: String): Set<String> =
            rows(text).mapNotNull { it[key]?.jsonPrimitive?.contentOrNull }.toSortedSet()

        /** Builds the context from the bundled `fitness/` resource texts. */
        fun fromTexts(
            catalog: String,
            muscleGroups: String,
            muscleRegions: String,
            metricDefs: String,
            reasonCodes: String,
            equipmentTypes: String,
            bodyRegions: String,
            symptomCodes: String,
            limits: FitExprLimits,
            changeoverMin: Int,
            engineVersion: String,
            maxConditionalNesting: Int,
        ): ValidationContext {
            val metrics = rows(metricDefs).associate { r ->
                val code = r.getValue("code").jsonPrimitive.content
                code to MetricDef(
                    code, r.getValue("value_type").jsonPrimitive.content, r.getValue("unit").jsonPrimitive.content,
                    r.getValue("side_mode").jsonPrimitive.content,
                )
            }.toSortedMap()
            return ValidationContext(
                CatalogDoc.parse(catalog), Taxonomy.parse(muscleGroups, muscleRegions), metrics,
                codes(reasonCodes, "code"), codes(equipmentTypes, "key"), codes(bodyRegions, "code"), codes(symptomCodes, "code"),
                limits, changeoverMin, engineVersion, maxConditionalNesting,
            )
        }
    }
}
