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
 * The bundled catalog (`fitness/catalog/catalog-1.0.1.json`; migration 009 seeded 1.0.0, migration 020 the
 * 1.0.1 delta). Global exercise ids are UUIDv5 `exercise:<slug>` (C38), so the phone and the server agree
 * without a lookup.
 */
class CatalogRepository(text: () -> String) {
    val doc: CatalogDoc by lazy { CatalogDoc.parse(text()) }
    val version: String get() = doc.catalogVersion

    val exercises: List<CatalogExercise> by lazy { doc.exercises.sortedBy { it.nameEn.lowercase(Locale.ROOT) } }
    private val byId: Map<String, CatalogExercise> by lazy { doc.exercises.associateBy { idOf(it) } }
    private val bySlug: Map<String, CatalogExercise> by lazy { doc.exercises.associateBy { it.slug } }

    fun idOf(e: CatalogExercise): String = Ids.exercise(e.slug).toString()
    fun byId(id: String): CatalogExercise? = byId[id]
    fun bySlug(slug: String): CatalogExercise? = bySlug[slug]

    /** Search by English and Dutch names and aliases (D12), accent- and case-insensitive; empty query = all. */
    fun search(query: String): List<CatalogExercise> {
        val q = fold(query)
        if (q.isBlank()) return exercises
        val words = q.split(' ').filter { it.isNotBlank() }
        return exercises.filter { e ->
            val hay = fold(listOfNotNull(e.nameEn, e.nameNl).plus(e.aliases).joinToString(" "))
            words.all { hay.contains(it) }
        }
    }

    companion object {
        fun bundled(): CatalogRepository = CatalogRepository {
            requireNotNull(CatalogRepository::class.java.getResource("/fitness/catalog/catalog-1.0.1.json")) {
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
