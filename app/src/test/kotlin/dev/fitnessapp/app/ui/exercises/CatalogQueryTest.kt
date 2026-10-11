package dev.fitnessapp.app.ui.exercises

import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import dev.fitnessapp.app.ui.common.isDutch
import dev.fitnessapp.data.repo.CatalogRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** UX 13.2 catalog list: ranking, Dutch names and diacritics, filters and sorts. */
class CatalogQueryTest {
    private val repo = CatalogRepository.bundled()
    private val catalog = Catalog(
        repo.doc,
        Taxonomy.parse(resource("/fitness/reference/muscle_groups.json"), resource("/fitness/reference/muscle_regions.json")),
    )

    private fun resource(p: String) = requireNotNull(javaClass.getResource(p)).readText(Charsets.UTF_8)

    private fun ex(slug: String, nameEn: String, nameNl: String? = null, aliases: List<String> = emptyList()) = CatalogExercise(
        slug = slug, nameEn = nameEn, nameNl = nameNl, aliases = aliases, pattern = "squat_machine", family = "squat",
        equipment = listOf("machine"), loadMode = "external", allowedLoadModes = listOf("external"), bwFraction = 0.0,
        laterality = "bilateral", measure = "reps", mechanics = "compound", restClass = "compound_lower", safetyClass = "machine",
    )

    private fun search(list: List<CatalogExercise>, q: String, dutch: Boolean = false) =
        CatalogQuery.apply(list, q, CatalogFilter(availableOnly = false), CatalogSort.NAME, catalog, emptyMap(), emptyMap(), { true }, { it.slug }, dutch).map { it.slug }

    @Test
    fun `ranking is exact, prefix, word prefix, alias, substring, then all words`() {
        val list = listOf(
            ex("a", "Seated leg press"),
            ex("b", "Leg press"),
            ex("c", "Leg press calf raise"),
            ex("d", "Hack squat", aliases = listOf("leg press alternative")),
            ex("e", "Single-leg pressdown"),
            ex("f", "Press with legs", nameNl = null),
        )
        search(list, "leg press") shouldBe listOf("b", "c", "a", "e", "d", "f")
    }

    @Test
    fun `Dutch names and diacritics match on a Dutch phone`() {
        val list = listOf(ex("x", "Leg press", nameNl = "Beenpers"), ex("y", "One arm hang", nameNl = "Eén arm hangen"))
        search(list, "beenpers", dutch = true) shouldBe listOf("x")
        search(list, "een arm", dutch = true) shouldBe listOf("y")
        // "een" is also a substring of "beenpers": ranked after the name prefix.
        search(list, "ÉÉN", dutch = true) shouldBe listOf("y", "x")
    }

    @Test
    fun `Dutch names are not searched when the phone language is not Dutch (D12)`() {
        val list = listOf(ex("x", "Leg press", nameNl = "Beenpers"), ex("y", "One arm hang", nameNl = "Eén arm hangen"))
        search(list, "beenpers") shouldBe emptyList()
        search(list, "een arm") shouldBe emptyList()
        search(list, "leg press") shouldBe listOf("x")
        repo.search("beenpers", dutch = false) shouldBe emptyList()
        repo.search("beenpers", dutch = true).map { it.slug } shouldContain "leg_press_45"
        isDutch(java.util.Locale.forLanguageTag("en-NL")) shouldBe false
        isDutch(java.util.Locale.forLanguageTag("nl-NL")) shouldBe true
        isDutch(java.util.Locale.forLanguageTag("nl-BE")) shouldBe true
    }

    @Test
    fun `empty query lists everything A-Z`() {
        val all = repo.exercises
        val out = search(all, "")
        out.size shouldBe all.size
        out.first() shouldBe all.minWith(compareBy({ it.nameEn.lowercase() }, { it.slug })).slug
    }

    @Test
    fun `muscle filter uses group coefficients and equipment filter the load modes`() {
        val legs = CatalogQuery.apply(repo.exercises, "", CatalogFilter(muscle = "quads", availableOnly = false), CatalogSort.NAME, catalog, emptyMap(), emptyMap(), { true }, repo::idOf, dutch = false)
        legs.forEach { (catalog.groupCoeff(it, "quads") >= CatalogQuery.MUSCLE_FILTER_MIN) shouldBe true }
        legs.map { it.slug } shouldNotContain "pullup_weighted"
        val assisted = CatalogQuery.apply(repo.exercises, "", CatalogFilter(equipment = EquipmentFilter.ASSISTED, availableOnly = false), CatalogSort.NAME, catalog, emptyMap(), emptyMap(), { true }, repo::idOf, dutch = false)
        assisted.forEach { ("assisted_machine" in it.allowedLoadModes) shouldBe true }
        assisted.size shouldNotBe 0
    }

    @Test
    fun `preference sort puts favorites first and never last`() {
        val list = listOf(ex("a", "A"), ex("b", "B"), ex("c", "C"))
        val prefs = mapOf("a" to "never", "c" to "favorite")
        CatalogQuery.apply(list, "", CatalogFilter(availableOnly = false), CatalogSort.PREFERENCE, catalog, prefs, emptyMap(), { true }, { it.slug }, dutch = false).map { it.slug } shouldBe
            listOf("c", "b", "a")
        CatalogQuery.apply(list, "", CatalogFilter(preference = "neutral", availableOnly = false), CatalogSort.NAME, catalog, prefs, emptyMap(), { true }, { it.slug }, dutch = false).map { it.slug } shouldBe
            listOf("b")
    }

    @Test
    fun `most used sort and the available filter`() {
        val list = listOf(ex("a", "A"), ex("b", "B"), ex("c", "C"))
        CatalogQuery.apply(list, "", CatalogFilter(availableOnly = false), CatalogSort.MOST_USED, catalog, emptyMap(), mapOf("c" to 3, "a" to 1), { true }, { it.slug }, dutch = false).map { it.slug } shouldBe
            listOf("c", "a", "b")
        CatalogQuery.apply(list, "", CatalogFilter(availableOnly = true), CatalogSort.NAME, catalog, emptyMap(), emptyMap(), { it.slug != "b" }, { it.slug }, dutch = false).map { it.slug } shouldBe
            listOf("a", "c")
    }

    @Test
    fun `primary groups are the groups at coefficient 1`() {
        val legPress = requireNotNull(repo.bySlug("leg_press_45"))
        CatalogQuery.primaryGroups(legPress, catalog) shouldContain "Quads"
    }
}
