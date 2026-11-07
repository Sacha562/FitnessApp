package dev.fitnessapp.algo.testing

import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The bundled catalog 1.0.2, taxonomy and body-region joint keys, shared by the planning tests. */
object TestCatalog {
    fun resource(path: String): String =
        requireNotNull(TestCatalog::class.java.getResource("/fitness/$path")) { "missing resource $path" }.readText(Charsets.UTF_8)

    val taxonomy: Taxonomy by lazy { Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json")) }
    val doc: CatalogDoc by lazy { CatalogDoc.parse(resource("catalog/catalog-1.0.5.json")) }
    val catalog: Catalog by lazy { Catalog(doc, taxonomy) }

    /** `body_regions.joint_keys` (code -> joint keys). */
    val regionJoints: Map<String, List<String>> by lazy {
        Json.parseToJsonElement(resource("reference/body_regions.json")).jsonObject.getValue("rows").jsonArray.associate { row ->
            val o = row.jsonObject
            o.getValue("code").jsonPrimitive.content to o.getValue("joint_keys").jsonArray.map { it.jsonPrimitive.content }
        }
    }

    fun ex(slug: String): CatalogExercise = requireNotNull(catalog.exercise(slug)) { "no catalog row $slug" }
}
