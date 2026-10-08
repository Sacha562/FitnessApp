package dev.fitnessapp.algo.selection.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The bundled catalog file `fitness/catalog/catalog-<version>.json` (ALGORITHMS_PLANNING 10.8). The same
 * file is bundled in the APK and turned into migration 009 by `:sim seedgen`, so server and phone agree.
 * Field names are the DATA_MODEL 9.2 column names; `joint_stress` holds the `js_*` columns and `stimulus`
 * the `exercise_stimulus` rows (region -> coefficient, absent = 0).
 */
@Serializable
data class CatalogDoc(
    @SerialName("catalog_version") val catalogVersion: String,
    val exercises: List<CatalogExercise>,
    @SerialName("transfer_ratios") val transferRatios: List<TransferRatio>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(text: String): CatalogDoc = json.decodeFromString(serializer(), text)
        fun parseElement(text: String): JsonElement = Json.parseToJsonElement(text)
    }
}

@Serializable
data class CatalogExercise(
    val slug: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("name_nl") val nameNl: String? = null,
    val aliases: List<String> = emptyList(),
    val pattern: String,
    val family: String,
    val equipment: List<String>,
    @SerialName("requires_station") val requiresStation: List<String> = emptyList(),
    @SerialName("load_mode") val loadMode: String,
    @SerialName("allowed_load_modes") val allowedLoadModes: List<String>,
    @SerialName("bw_fraction") val bwFraction: Double,
    @SerialName("implement_base_kg") val implementBaseKg: Double? = null,
    val laterality: String,
    val measure: String,
    val contraction: String = "dynamic",
    @SerialName("hold_class") val holdClass: String? = null,
    @SerialName("ecc_emphasis") val eccEmphasis: Boolean = false,
    val mechanics: String,
    @SerialName("curve_class") val curveClass: String? = null,
    @SerialName("rest_class") val restClass: String,
    @SerialName("safety_class") val safetyClass: String,
    val grip: String = "none",
    @SerialName("stretch_emphasis") val stretchEmphasis: Int = 0,
    val stability: Int = 1,
    val skill: Int = 1,
    val axial: Int = 0,
    val systemic: Int = 1,
    val sfr: Int = 3,
    @SerialName("setup_s") val setupS: Int = 30,
    @SerialName("grip_demand") val gripDemand: Int = 0,
    @SerialName("joint_stress") val jointStress: Map<String, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
    @SerialName("flags_offered") val flagsOffered: List<String> = emptyList(),
    @SerialName("external_refs") val externalRefs: JsonObject = JsonObject(emptyMap()),
    @SerialName("evidence_note") val evidenceNote: String? = null,
    val stimulus: Map<String, Double> = emptyMap(),
) {
    fun js(joint: String): Int = jointStress[joint] ?: 0
}

@Serializable
data class TransferRatio(
    @SerialName("from_slug") val fromSlug: String,
    @SerialName("to_slug") val toSlug: String,
    val ratio: Double,
    val evidence: String = "expert-opinion",
    val note: String? = null,
)

/** Value domains of DATA_MODEL 8 used by the catalog (C38: the only spellings). */
object CatalogDomains {
    val PATTERNS: List<String> = listOf(
        "squat", "squat_machine", "lunge", "hinge", "hip_ext", "knee_ext", "knee_flex", "hip_abd", "hip_add", "calf",
        "horiz_push", "incline_push", "vert_push", "dip", "chest_fly", "lateral_raise", "rear_delt_fly", "horiz_pull",
        "vert_pull", "straight_arm_pull", "shrug", "elbow_flex", "elbow_ext", "elbow_ext_overhead", "wrist_flex",
        "wrist_ext", "forearm_rot", "grip_hold", "ext_rotation", "core_flex", "core_anti_ext", "core_anti_rot", "hang",
        "skill_static", "carry", "conditioning",
    )

    /**
     * Substitution families of ALGORITHMS_PLANNING 10.6. That table has no entry for `ext_rotation` (rotator-cuff
     * prehab); it gets its own family `prehab`.
     */
    val FAMILY_OF_PATTERN: Map<String, String> = linkedMapOf(
        "horiz_push" to "press", "incline_push" to "press", "dip" to "press",
        "squat" to "squat", "squat_machine" to "squat", "lunge" to "squat",
        "hinge" to "hinge", "hip_ext" to "hinge",
        "vert_pull" to "vpull", "straight_arm_pull" to "vpull",
        "horiz_pull" to "hpull", "rear_delt_fly" to "hpull",
        "lateral_raise" to "raise", "chest_fly" to "fly", "vert_push" to "vpush", "elbow_flex" to "curl",
        "elbow_ext" to "ext", "elbow_ext_overhead" to "ext", "knee_ext" to "legiso", "knee_flex" to "legiso",
        "hip_abd" to "hip", "hip_add" to "hip", "calf" to "calf", "shrug" to "trap",
        "wrist_flex" to "forearm", "wrist_ext" to "forearm", "forearm_rot" to "forearm", "grip_hold" to "forearm",
        "carry" to "forearm", "core_flex" to "core", "core_anti_ext" to "core", "core_anti_rot" to "core",
        "hang" to "skill", "skill_static" to "skill", "conditioning" to "cardio", "ext_rotation" to "prehab",
    )

    val EQUIPMENT_CLASSES = listOf("barbell", "dumbbell", "machine", "cable", "bodyweight", "smith", "band", "kettlebell", "other")
    val LOAD_MODES = listOf("external", "bodyweight", "bodyweight_plus_external", "assisted_machine", "assisted_ordinal")
    val BODYWEIGHT_MODES = setOf("bodyweight", "bodyweight_plus_external", "assisted_machine", "assisted_ordinal")
    val LATERALITY = listOf("bilateral", "unilateral", "alternating")
    val MEASURES = listOf("reps", "duration", "attempt", "checklist")
    val CONTRACTIONS = listOf("dynamic", "eccentric_only", "isometric", "hang")
    val HOLD_CONTRACTIONS = setOf("isometric", "hang")
    val HOLD_CLASSES = listOf("grip", "elbow", "shoulder", "trunk", "knee", "ankle")
    val MECHANICS = listOf("compound", "isolation", "skill", "conditioning")
    val CURVE_CLASSES = listOf("upper_compound", "lower_machine_compound", "lower_free_compound", "upper_isolation", "lower_isolation")
    val REST_CLASSES = listOf("strength_heavy", "compound_lower", "compound_upper", "isolation", "hold", "core")
    val SAFETY_CLASSES = listOf("machine", "cable", "isolation", "free_compound", "bodyweight", "high_risk")
    val GRIPS = listOf("supinated", "neutral", "pronated", "mixed", "none")
    val JOINTS = listOf("elbow_medial", "elbow_lateral", "shoulder_ant", "shoulder_overhead", "wrist", "lumbar", "knee", "hip")
    val SET_FLAGS = listOf(
        "grip_slip_before_last", "jerky", "torso_rotation", "hip_rotation", "technique_breakdown", "failed_rep",
        "hand_shift", "used_straps",
    )

    /** Reserved exercise tags of DATA_MODEL 9.2.1 (C38). */
    val RESERVED_TAGS = listOf(
        "challenge", "prehab", "skill", "core", "hang", "home", "pull_heavy", "grip_heavy", "elbow_flexor_heavy",
        "supinated_curl", "unilateral", "unilateral_pull", "heavy_unilateral", "eccentric", "elbow_stress_high",
        "axial_heavy", "overhead_press_heavy", "unstrapped_deadlift", "no_grip",
    )
    val COEFF_LEVELS = listOf(0.25, 0.5, 0.75, 1.0)
    val SLUG = Regex("^[a-z][a-z0-9_]{0,63}$")
}
