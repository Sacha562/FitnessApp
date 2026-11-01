package dev.fitnessapp.data.repo

import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import java.text.Normalizer
import java.util.Locale

/**
 * The bundled catalog (`fitness/catalog/catalog-1.0.4.json`; migration 009 seeded 1.0.0, migrations 020,
 * 040, 041 and 045 the 1.0.1 to 1.0.4 deltas) plus the user's custom exercises (M3.6, D27), which [CustomExerciseRepository] keeps current through
 * [setCustom]. Global exercise ids are UUIDv5 `exercise:<slug>` (C38), so the phone and the server agree
 * without a lookup; custom rows keep their stored UUIDv7. [doc] stays the bundled file (challenge definitions
 * resolve against it); [merged] is the document the engine reads.
 */
class CatalogRepository(text: () -> String) {
    val doc: CatalogDoc by lazy { CatalogDoc.parse(text()) }
    val version: String get() = doc.catalogVersion

    private class Index(val doc: CatalogDoc, custom: List<Pair<String, CatalogExercise>>, val version: Int) {
        val customIds: Map<String, String> = custom.associate { it.second.slug to it.first }
        val all: List<CatalogExercise> = doc.exercises + custom.map { it.second }
        val sorted: List<CatalogExercise> = all.sortedWith(compareBy({ it.nameEn.lowercase(Locale.ROOT) }, { it.slug }))
        val bySlug: Map<String, CatalogExercise> = all.associateBy { it.slug }
        val byId: Map<String, CatalogExercise> = all.associateBy { customIds[it.slug] ?: Ids.exercise(it.slug).toString() }
        val merged: CatalogDoc = doc.copy(exercises = all)
    }

    @Volatile private var index: Index? = null
    private fun ix(): Index = index ?: synchronized(this) { index ?: Index(doc, emptyList(), 0).also { index = it } }

    /** Bumps with every change of the custom rows, so read models built from [merged] know when to rebuild. */
    val customVersion: Int get() = ix().version

    /** Replaces the custom exercises (id to row); custom slugs never collide with global ones (`custom_` prefix). */
    fun setCustom(rows: List<Pair<String, CatalogExercise>>) = synchronized(this) {
        val old = ix()
        if (old.customIds.size == rows.size && rows.all { (id, e) -> old.customIds[e.slug] == id && old.bySlug[e.slug] == e }) return@synchronized
        index = Index(doc, rows.sortedBy { it.second.slug }, old.version + 1)
    }

    /** Every exercise, global and custom, sorted by English name. */
    val exercises: List<CatalogExercise> get() = ix().sorted
    /** Global then custom rows, the engine's view (estimators, prescriptions, selection). */
    val allExercises: List<CatalogExercise> get() = ix().all
    val merged: CatalogDoc get() = ix().merged
    val custom: List<CatalogExercise> get() = ix().all.filter { isCustom(it) }

    fun isCustom(e: CatalogExercise): Boolean = e.slug in ix().customIds
    fun idOf(e: CatalogExercise): String = ix().customIds[e.slug] ?: Ids.exercise(e.slug).toString()
    fun byId(id: String): CatalogExercise? = ix().byId[id]
    fun bySlug(slug: String): CatalogExercise? = ix().bySlug[slug]

    /**
     * Search by English names and aliases, plus Dutch names when [dutch] (D12: the phone's language is Dutch), accent-
     * and case-insensitive; empty query = all.
     */
    fun search(query: String, dutch: Boolean): List<CatalogExercise> {
        val q = fold(query)
        if (q.isBlank()) return exercises
        val words = q.split(' ').filter { it.isNotBlank() }
        return exercises.filter { e ->
            val hay = fold(listOfNotNull(e.nameEn, e.nameNl?.takeIf { dutch }).plus(e.aliases).joinToString(" "))
            words.all { hay.contains(it) }
        }
    }

    companion object {
        fun bundled(): CatalogRepository = CatalogRepository {
            requireNotNull(CatalogRepository::class.java.getResource("/fitness/catalog/catalog-1.0.4.json")) {
                "bundled catalog missing"
            }.readText(Charsets.UTF_8)
        }

        fun loadAttrs(e: CatalogExercise): ExerciseLoadAttrs = ExerciseLoadAttrs(
            fBw = e.bwFraction,
            implementBaseKg = e.implementBaseKg,
            laterality = Laterality.of(e.laterality),
            allowedLoadModes = e.allowedLoadModes.map(LoadMode::of),
        )

        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
    }
}
