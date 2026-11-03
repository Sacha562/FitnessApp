package dev.fitnessapp.algo.selection.catalog

import dev.fitnessapp.algo.common.Ids
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

/** CAT-1 validator on the bundled seed (CI gate before migration 009, D19) and the 10.11 test cases. */
class CatalogValidatorTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/fitness/$path")) { "missing resource $path" }.readText(Charsets.UTF_8)

    private val taxonomy = Taxonomy.parse(resource("reference/muscle_groups.json"), resource("reference/muscle_regions.json"))
    private val doc = CatalogDoc.parse(resource("catalog/catalog-1.0.4.json"))
    private val catalog = Catalog(doc, taxonomy)
    private val stations: Set<String> = Json.parseToJsonElement(resource("reference/equipment_types.json"))
        .jsonObject.getValue("rows").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()

    private val repoRoot = File(System.getProperty("fitness.repoRoot") ?: ".")
    private val oap = Json.parseToJsonElement(File(repoRoot, "docs/challenges/oap.challenge.v1.json").readText(Charsets.UTF_8)).jsonObject

    /** The C38 union: catalog slugs reached through catalogRef (directly or by grip map) plus challenge-local keys. */
    private fun definitionSlugs(def: JsonObject): Pair<Set<String>, Set<String>> {
        val local = sortedSetOf<String>()
        val refs = sortedSetOf<String>()
        for (e in def.getValue("exercises").jsonArray.map { it.jsonObject }) {
            val ref = e["catalogRef"]
            when {
                ref is JsonPrimitive -> refs += ref.content
                ref is JsonObject -> refs += ref.getValue("map").jsonObject.values.map { it.jsonPrimitive.content }
                e["local"]?.jsonPrimitive?.content == "true" -> local += e.getValue("key").jsonPrimitive.content
            }
        }
        return local to refs
    }

    @Test
    fun `bundled seed passes CAT-1`() {
        val (local, refs) = definitionSlugs(oap)
        local.size shouldBe 14
        refs.size shouldBe 15
        val report = CatalogValidator.validate(catalog, stations, challengeLocalSlugs = local, requiredSlugs = local + refs)
        report.issues.map { it.toString() }.shouldBeEmpty()
        doc.catalogVersion shouldBe "1.0.4"
    }

    @Test
    fun `every externally loaded machine exercise names a loadable station`() {
        // Catalog 1.0.1: without one, LOAD-1 finds no stack and the set card has nothing to offer.
        val loadable = setOf("stack", "plates", "pin_assist")
        val schemes = Json.parseToJsonElement(resource("reference/equipment_types.json")).jsonObject.getValue("rows").jsonArray
            .map { it.jsonObject }
            .associate { it.getValue("key").jsonPrimitive.content to (it["default_scheme_type"] as? JsonPrimitive)?.content }
        val missing = doc.exercises
            .filter { "machine" in it.equipment && it.loadMode == "external" && it.measure == "reps" }
            .filter { e -> e.requiresStation.none { schemes[it] in loadable } }
            .map { it.slug }
        missing.shouldBeEmpty()
    }

    @Test
    fun `validatorRejectsUnknownRegion`() {
        val bad = doc.exercises.first { it.slug == "wrist_curl_db" }.copy(stimulus = mapOf("forearm_flexor" to 1.0))
        CatalogValidator.validateRow(catalog, bad, stations, seed = true).map { it.code } shouldContain "V2"
    }

    @Test
    fun `aliasRewritten`() {
        taxonomy.canonicalRegion("mid_traps") shouldBe "traps_mid"
        taxonomy.canonicalRegion("traps_mid") shouldBe "traps_mid"
        taxonomy.canonicalRegion("made_up") shouldBe null
    }

    @Test
    fun `groupMaxNotSum`() {
        catalog.groupCoeff(catalog.exercise("pullup_bw")!!, "biceps") shouldBe 0.5   // max(0.5, 0.5, 0.5), not the sum 1.5
    }

    @Test
    fun `seedCoverage and substituteExists`() {
        val report = CatalogValidator.validate(catalog, stations)
        report.coverage.size shouldBe 18
        report.coverage.forEach { c ->
            require(c.exercises >= 3 && c.direct >= 1 && c.equipmentClasses.size >= 2) { "V9 $c" }
        }
        report.bestSubstitutes.forEach { (slug, sub, sim) -> require(sim >= 0.6) { "V10 $slug -> $sub $sim" } }
    }

    @Test
    fun `derivedTags`() {
        catalog.derivedTags(catalog.exercise("db_shrug")!!) shouldContain "grip_heavy"
        catalog.derivedTags(catalog.exercise("barbell_curl")!!) shouldContainAll listOf("elbow_flexor_heavy", "supinated_curl")
        catalog.derivedTags(catalog.exercise("lat_pulldown_wide")!!) shouldContain "pull_heavy"
        catalog.derivedTags(catalog.exercise("ohp_barbell")!!) shouldContainAll listOf("axial_heavy", "overhead_press_heavy")
        catalog.derivedTags(catalog.exercise("leg_extension")!!) shouldNotContain "pull_heavy"
    }

    @Test
    fun `nlNamesPresent`() {
        doc.exercises.forEach { require(!it.nameNl.isNullOrBlank()) { "${it.slug} has no Dutch name" } }
    }

    @Test
    fun `v4 needs evidence for 0_75 and v5 checks bodyweight fraction`() {
        val e = catalog.exercise("db_bench_press")!!
        CatalogValidator.validateRow(catalog, e.copy(evidenceNote = null), stations, seed = true).map { it.code } shouldContain "V4"
        CatalogValidator.validateRow(catalog, e.copy(bwFraction = 0.5), stations, seed = true).map { it.code } shouldContain "V5"
    }

    @Test
    fun `fixture ids of DATA_MODEL 19_6`() {
        Ids.exercise("chinup_weighted").toString() shouldBe "1bf2755f-6a6e-5998-9327-79648dda4205"
        Ids.exercise("oap_assisted_machine").toString() shouldBe "bb7197a7-c5d4-57f7-b2b6-80c6e3f28947"
        Ids.exercise("two_arm_dead_hang").toString() shouldBe "637a638c-b2cb-5f42-a874-c7f0066cfbb1"
        Ids.fitness("fixture-user:owner").toString() shouldBe "63750a4b-f9b8-5269-a927-91957167fe62"
    }

    @Test
    fun `a dip is high-risk only when loaded, weighted pulls always (D80)`() {
        fun ex(slug: String) = doc.exercises.single { it.slug == slug }
        ex("dip_chest").highRiskIn("bodyweight") shouldBe false
        ex("dip_chest").highRiskIn("bodyweight_plus_external") shouldBe true
        ex("dip_neutral").highRiskIn("bodyweight") shouldBe false
        ex("pullup_weighted").highRiskIn("bodyweight_plus_external") shouldBe true
        ex("oap_attempt").highRiskIn("bodyweight") shouldBe true
        ex("dip_machine").highRiskIn("external") shouldBe false
    }
}
