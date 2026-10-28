package dev.fitnessapp.algo.common

/**
 * The planning vocabulary of ALGORITHMS_PLANNING 0.3 (C9): the 18 muscle groups in their canonical order, the
 * session families, and the tissue channels of SetCredit and readiness. Every per-group or per-tissue loop of the
 * planning modules walks these lists, so results never depend on map order.
 */
object Groups {
    val CANONICAL: List<String> = listOf(
        "chest", "front_delts", "side_delts", "rear_delts", "lats", "upper_back", "upper_traps", "biceps", "triceps",
        "forearms", "abs", "lower_back", "glutes", "abductors", "adductors", "quads", "hamstrings", "calves",
    )

    val FAMILIES: Map<String, List<String>> = linkedMapOf(
        "PUSH" to listOf("chest", "front_delts", "side_delts", "triceps"),
        "PULL" to listOf("lats", "upper_back", "rear_delts", "biceps", "forearms", "upper_traps"),
        "LEGS" to listOf("quads", "hamstrings", "glutes", "abductors", "adductors", "calves"),
        "CORE" to listOf("abs", "lower_back"),
    )

    /** SetCredit's tissue channels (3.4): the eight CAT-1 joint keys and grip. */
    val TISSUES: List<String> = listOf(
        "elbow_medial", "elbow_lateral", "shoulder_ant", "shoulder_overhead", "wrist", "lumbar", "knee", "hip", "grip",
    )

    fun familyOf(group: String): String = FAMILIES.entries.first { group in it.value }.key

    /** `ceil_half(x) = ceil(2x - 1e-9) / 2` (0.2): planning quantities move in half sets. */
    fun ceilHalf(x: Double): Double = kotlin.math.ceil(2.0 * x - 1e-9) / 2.0

    /** `round_half(x) = floor(2x + 0.5) / 2` (0.2). */
    fun roundHalf(x: Double): Double = kotlin.math.floor(2.0 * x + 0.5) / 2.0

    /** Round half up to an integer (targets and ramp scores, 5.4). */
    fun roundInt(x: Double): Int = kotlin.math.floor(x + 0.5).toInt()
}
