package dev.fitnessapp.algo.selection.catalog

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** CAT-1 10.9: custom copies of every prototype pass the validator, in every laterality and timing variant. */
class PrototypesTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing resource $path" }.readText(Charsets.UTF_8)

    private val taxonomy = Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json"))
    private val doc = CatalogDoc.parse(resource("catalog/catalog-1.0.4.json"))
    private val catalog = Catalog(doc, taxonomy)
    private val stations: Set<String> = Json.parseToJsonElement(resource("reference/equipment_types.json"))
        .jsonObject.getValue("rows").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()
    private val table = Prototypes.table(doc)

    private fun draft(p: CatalogExercise, eq: String, unilateral: Boolean, timed: Boolean) = CustomDraft(
        "custom_test", "Test exercise", null, p.pattern, eq, p.requiresStation, p.loadMode, unilateral, timed, p.flagsOffered,
    )

    @Test
    fun `the lowest slug per pattern and equipment class, never a challenge row`() {
        table.values.forEach { ("challenge" in it.tags) shouldBe false }
        table.forEach { (key, p) ->
            doc.exercises.filter { it.pattern == key.first && key.second in it.equipment && "challenge" !in it.tags }.minOf { it.slug } shouldBe p.slug
        }
    }

    @Test
    fun `every copy validates in every variant`() {
        val problems = ArrayList<String>()
        for ((key, p) in table) {
            for (unilateral in listOf(false, true)) for (timed in listOf(false, true)) {
                val e = Prototypes.copy(p, draft(p, key.second, unilateral, timed), doc.catalogVersion)
                CatalogValidator.validateRow(catalog, e, stations, seed = false).forEach { problems += "${key.first}/${key.second} u=$unilateral t=$timed: $it" }
                e.equipment shouldBe listOf(key.second)
                e.tags shouldNotContain "challenge"
                (e.laterality == "bilateral") shouldBe !unilateral
                (e.measure == "duration") shouldBe timed
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `edited coefficients replace the copy and zero means absent`() {
        val p = table.getValue("squat_machine" to "machine")
        val e = Prototypes.copy(p, draft(p, "machine", false, false).copy(stimulus = mapOf("vasti" to 1.0, "glute_max" to 0.0)), doc.catalogVersion)
        e.stimulus shouldBe mapOf("vasti" to 1.0)
    }
}
