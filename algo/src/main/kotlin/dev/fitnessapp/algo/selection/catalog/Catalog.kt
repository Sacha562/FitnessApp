package dev.fitnessapp.algo.selection.catalog

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid

/** Thresholds of the derived tags (params `catalog.derived_tags`, ALGORITHMS_PLANNING 10.4). */
data class DerivedTagParams(
    val gripHeavyMinGripDemand: Int = 2,
    val elbowFlexorHeavyMinBicepsCoeff: Double = 1.0,
    val elbowFlexorHeavyMinJsElbowMedial: Int = 2,
    val pullHeavyMinLatsOrUpperBackCoeff: Double = 0.75,
    val axialHeavyMinAxial: Int = 3,
    val overheadPressHeavyMinJsShoulderOverhead: Int = 2,
)

/**
 * CAT-1 read model (ALGORITHMS_PLANNING 10.4): group coefficients (max over the group's regions, never a sum),
 * derived tags, and the SUB-1 stimulus vector and similarity (15.1) that the validator's V10 uses.
 * Every iteration is in the taxonomy's canonical order, so results never depend on map order.
 */
class Catalog(
    val doc: CatalogDoc,
    val taxonomy: Taxonomy,
    private val tagParams: DerivedTagParams = DerivedTagParams(),
    /** `substitute.region_weight` (0.5): weight of the region part of the stimulus vector. */
    private val regionWeight: Double = 0.5,
) {
    val exercises: List<CatalogExercise> = doc.exercises.sortedBy { it.slug }
    private val bySlug: Map<String, CatalogExercise> = exercises.associateBy { it.slug }

    fun exercise(slug: String): CatalogExercise? = bySlug[slug]

    /** `groupCoeff(e, g) = max over regions r of g of coeff(e, r)`; 0 when no region of g is stimulated. */
    fun groupCoeff(e: CatalogExercise, groupId: String): Double =
        taxonomy.regionsOf(groupId).maxOfOrNull { e.stimulus[it.id] ?: 0.0 } ?: 0.0

    /** Derived tags (computed, never stored); the effective tag set is stored ∪ derived. */
    fun derivedTags(e: CatalogExercise): List<String> {
        val out = sortedSetOf<String>()
        if (e.gripDemand >= tagParams.gripHeavyMinGripDemand) out += "grip_heavy"
        if (groupCoeff(e, "biceps") >= tagParams.elbowFlexorHeavyMinBicepsCoeff ||
            e.js("elbow_medial") >= tagParams.elbowFlexorHeavyMinJsElbowMedial
        ) out += "elbow_flexor_heavy"
        if (maxOf(groupCoeff(e, "lats"), groupCoeff(e, "upper_back")) >= tagParams.pullHeavyMinLatsOrUpperBackCoeff &&
            e.contraction in setOf("dynamic", "eccentric_only")
        ) out += "pull_heavy"
        if (e.pattern == "elbow_flex" && e.grip == "supinated") out += "supinated_curl"
        if (e.axial >= tagParams.axialHeavyMinAxial) out += "axial_heavy"
        if (e.pattern == "vert_push" && e.mechanics == "compound" &&
            e.js("shoulder_overhead") >= tagParams.overheadPressHeavyMinJsShoulderOverhead
        ) out += "overhead_press_heavy"
        return out.toList()
    }

    fun effectiveTags(e: CatalogExercise): List<String> = (e.tags.toSortedSet() + derivedTags(e)).toSortedSet().toList()

    /** SUB-1 vector: the group coefficients ⊕ regionWeight × the region coefficients, in canonical order. */
    fun vector(e: CatalogExercise): DoubleArray {
        val groups = taxonomy.activeGroups.map { groupCoeff(e, it.id) }
        val regions = taxonomy.activeRegions.map { regionWeight * (e.stimulus[it.id] ?: 0.0) }
        return (groups + regions).toDoubleArray()
    }

    /** Cosine similarity of the two vectors, on the RATIO grid; 0 when either vector is zero. */
    fun similarity(a: CatalogExercise, b: CatalogExercise): Double {
        val va = vector(a)
        val vb = vector(b)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in va.indices) {
            dot += va[i] * vb[i]
            na += va[i] * va[i]
            nb += vb[i] * vb[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return Grid.ratio(dot / (Det.sqrt(na) * Det.sqrt(nb)))
    }
}
