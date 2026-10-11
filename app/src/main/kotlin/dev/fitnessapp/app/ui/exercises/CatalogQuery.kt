package dev.fitnessapp.app.ui.exercises

import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import java.text.Normalizer
import java.util.Locale

/** Equipment filter of UX 13.2: catalog classes plus the assisted machine and the dip belt. */
enum class EquipmentFilter(val code: String, val label: String) {
    BARBELL("barbell", "Barbell"),
    DUMBBELL("dumbbell", "Dumbbell"),
    MACHINE("machine", "Machine"),
    CABLE("cable", "Cable"),
    BODYWEIGHT("bodyweight", "Bodyweight"),
    SMITH("smith", "Smith machine"),
    ASSISTED("assisted_machine", "Assisted machine"),
    BELT("belt", "Dip belt"),
    OTHER("other", "Other");

    fun matches(e: CatalogExercise): Boolean = when (this) {
        ASSISTED -> "assisted_machine" in e.allowedLoadModes
        BELT -> "bodyweight_plus_external" in e.allowedLoadModes
        OTHER -> e.equipment.any { it in setOf("band", "kettlebell", "other") }
        else -> code in e.equipment
    }
}

enum class CatalogSort(val code: String, val label: String) { NAME("a_z", "A-Z"), MOST_USED("most_used", "Most used"), PREFERENCE("preference", "Preference") }

data class CatalogFilter(
    val muscle: String? = null,
    val equipment: EquipmentFilter? = null,
    val pattern: String? = null,
    val preference: String? = null,
    val availableOnly: Boolean = true,
)

/**
 * The deterministic catalog list of UX 13.2: search over English names and aliases, plus Dutch names when [dutch]
 * (D12: the phone's language is Dutch), case- and diacritic-insensitive, ranked exact name, name prefix, word prefix, alias, substring, all words; filters; and the
 * three sorts. Ties always break by English name, then slug.
 */
object CatalogQuery {
    /** Group coefficient from which a muscle filter lists an exercise ("trains it well", C26). */
    const val MUSCLE_FILTER_MIN = 0.75

    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    /** 0 = best; null = no match. */
    fun rank(e: CatalogExercise, query: String, dutch: Boolean): Int? {
        val q = fold(query)
        if (q.isEmpty()) return 0
        val names = listOfNotNull(e.nameEn, e.nameNl?.takeIf { dutch }).map(::fold)
        val aliases = e.aliases.map(::fold)
        fun wordPrefix(h: String) = (" $h").contains(" $q")
        return when {
            names.any { it == q } -> 0
            names.any { it.startsWith(q) } -> 1
            names.any(::wordPrefix) -> 2
            aliases.any { it == q || it.startsWith(q) || wordPrefix(it) } -> 3
            (names + aliases).any { it.contains(q) } -> 4
            q.split(' ').all { w -> (names + aliases).any { it.contains(w) } } -> 5
            else -> null
        }
    }

    private val byName = compareBy<CatalogExercise>({ it.nameEn.lowercase(Locale.ROOT) }, { it.slug })

    fun apply(
        all: List<CatalogExercise>,
        query: String,
        filter: CatalogFilter,
        sort: CatalogSort,
        catalog: Catalog,
        prefs: Map<String, String>,
        usage: Map<String, Int>,
        available: (CatalogExercise) -> Boolean,
        idOf: (CatalogExercise) -> String,
        dutch: Boolean,
    ): List<CatalogExercise> {
        val ranked = all.mapNotNull { e -> rank(e, query, dutch)?.let { e to it } }
            .filter { (e, _) ->
                (filter.muscle == null || catalog.groupCoeff(e, filter.muscle) >= MUSCLE_FILTER_MIN) &&
                    (filter.equipment == null || filter.equipment.matches(e)) &&
                    (filter.pattern == null || e.pattern == filter.pattern) &&
                    (filter.preference == null || (prefs[idOf(e)] ?: "neutral") == filter.preference) &&
                    (!filter.availableOnly || available(e))
            }
        val searching = fold(query).isNotEmpty()
        val cmp: Comparator<Pair<CatalogExercise, Int>> = when (sort) {
            CatalogSort.NAME -> compareBy<Pair<CatalogExercise, Int>> { if (searching) it.second else 0 }
            CatalogSort.MOST_USED -> compareBy { -(usage[idOf(it.first)] ?: 0) }
            CatalogSort.PREFERENCE -> compareBy { PREF_ORDER.indexOf(prefs[idOf(it.first)] ?: "neutral") }
        }
        return ranked.sortedWith(cmp.thenBy(byName) { it.first }).map { it.first }
    }

    /** Favorite first, never last (UX 13.2 "preference" sort). */
    val PREF_ORDER = listOf("favorite", "like", "neutral", "dislike", "never")

    /** Up to two primary groups (group coefficient 1.0), in taxonomy order. */
    fun primaryGroups(e: CatalogExercise, catalog: Catalog): List<String> =
        catalog.taxonomy.activeGroups.filter { catalog.groupCoeff(e, it.id) >= 1.0 }.take(2).map { it.labelEn.substringBefore(" (") }

    fun equipmentAbbrev(e: CatalogExercise): String = when {
        "assisted_machine" in e.allowedLoadModes && e.loadMode == "assisted_machine" -> "AM"
        "machine" in e.equipment -> "M"
        "barbell" in e.equipment -> "B"
        "dumbbell" in e.equipment -> "DB"
        "cable" in e.equipment -> "C"
        "smith" in e.equipment -> "S"
        "kettlebell" in e.equipment -> "KB"
        "band" in e.equipment -> "Band"
        "bodyweight" in e.equipment -> "BW"
        else -> ""
    }

    /** Plain pattern names of UX 13.5, grouped by family in the filter menu. */
    val PATTERN_LABELS: Map<String, String> = linkedMapOf(
        "squat" to "Squat", "squat_machine" to "Squat (machine)", "lunge" to "Lunge", "hinge" to "Hip hinge",
        "hip_ext" to "Hip extension", "knee_ext" to "Knee extension", "knee_flex" to "Knee flexion", "hip_abd" to "Hip abduction",
        "hip_add" to "Hip adduction", "calf" to "Calf raise", "horiz_push" to "Horizontal push", "incline_push" to "Incline push",
        "vert_push" to "Vertical push", "dip" to "Dip", "chest_fly" to "Chest fly", "lateral_raise" to "Lateral raise",
        "rear_delt_fly" to "Rear delt fly", "horiz_pull" to "Horizontal pull", "vert_pull" to "Vertical pull",
        "straight_arm_pull" to "Straight-arm pull", "shrug" to "Shrug", "elbow_flex" to "Elbow flexion", "elbow_ext" to "Elbow extension",
        "elbow_ext_overhead" to "Overhead elbow extension", "wrist_flex" to "Wrist flexion", "wrist_ext" to "Wrist extension",
        "forearm_rot" to "Forearm rotation", "grip_hold" to "Grip hold", "ext_rotation" to "External rotation",
        "core_flex" to "Core flexion", "core_anti_ext" to "Core anti-extension", "core_anti_rot" to "Core anti-rotation",
        "hang" to "Hang", "skill_static" to "Static skill", "carry" to "Carry", "conditioning" to "Conditioning",
    )

    fun patternLabel(p: String): String = PATTERN_LABELS[p] ?: p.replace('_', ' ')
}
