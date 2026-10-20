package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.ExerciseSpec
import dev.fitnessapp.algo.challenge.cds.I18n
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** What the materializer needs to know about a definition exercise key (2.4.2): local entry or catalog row. */
data class ResolvedExercise(
    val key: String,
    val catalogSlug: String?,
    val name: I18n,
    val tags: List<String>,
    val stations: List<String>,
    val flagsOffered: List<String>,
    val loadMode: String,
    val fBw: BigDecimal,
    /** `reps`, `eccentric`, `hold` or `attempt`. */
    val logAs: String,
    val compound: Boolean,
    val highRisk: Boolean,
)

/** Resolves definition exercise keys through `catalogRef` (including `byParam`) or the local entry. */
class ExerciseResolver(private val def: CompiledDefinition, private val catalog: CatalogDoc?, private val params: Map<String, Value>) {
    private val bySlug: Map<String, CatalogExercise> = catalog?.exercises?.associateBy { it.slug } ?: emptyMap()

    fun spec(key: String): ExerciseSpec? = def.doc.exercises.firstOrNull { it.key == key }

    fun slugOf(spec: ExerciseSpec): String? = when (val ref = spec.catalogRef) {
        is JsonPrimitive -> ref.content
        is JsonObject -> {
            val param = (ref["byParam"] as? JsonPrimitive)?.content
            val value = (params[param] as? StrV)?.s
            ((ref["map"] as? JsonObject)?.get(value) as? JsonPrimitive)?.content
        }
        else -> null
    }

    fun resolve(key: String): ResolvedExercise? {
        val spec = spec(key) ?: return null
        if (spec.local) {
            val logAs = spec.logAs ?: "reps"
            val heavyTags = setOf("heavy_unilateral", "elbow_stress_high", "unilateral_pull")
            return ResolvedExercise(
                key = key, catalogSlug = null, name = spec.name ?: emptyMap(), tags = spec.tags, stations = spec.requiresStation,
                flagsOffered = spec.flagsOffered, loadMode = spec.loadMode ?: "bodyweight", fBw = Dec.ofParam(spec.fBw ?: 0.0),
                logAs = logAs, compound = logAs != "hold",
                highRisk = spec.loadMode in setOf("bodyweight_plus_external", "assisted_machine", "assisted_ordinal") || spec.tags.any { it in heavyTags },
            )
        }
        val slug = slugOf(spec) ?: return null
        val c = bySlug[slug]
        val overlayTags = spec.overlay?.tags.orEmpty()
        val overlayFlags = spec.overlay?.flagsOffered.orEmpty()
        return ResolvedExercise(
            key = key,
            catalogSlug = slug,
            name = c?.let { e -> listOfNotNull("en" to e.nameEn, e.nameNl?.let { "nl" to it }).toMap() } ?: emptyMap(),
            tags = (c?.tags.orEmpty() + overlayTags).distinct(),
            stations = c?.requiresStation.orEmpty(),
            flagsOffered = (c?.flagsOffered.orEmpty() + overlayFlags).distinct(),
            loadMode = c?.loadMode ?: "external",
            fBw = Dec.ofParam(c?.bwFraction ?: 0.0),
            logAs = when {
                c == null -> "reps"
                c.contraction == "eccentric_only" -> "eccentric"
                c.measure == "duration" -> "hold"
                c.measure == "attempt" -> "attempt"
                else -> "reps"
            },
            compound = c?.mechanics == "compound",
            highRisk = c?.safetyClass == "high_risk",
        )
    }
}
