package dev.fitnessapp.algo.core.load

/** DATA_MODEL E3. [code] is the stored spelling (C38). */
enum class LoadMode(val code: String) {
    EXTERNAL("external"),
    BODYWEIGHT("bodyweight"),
    BODYWEIGHT_PLUS_EXTERNAL("bodyweight_plus_external"),
    ASSISTED_MACHINE("assisted_machine"),
    ASSISTED_ORDINAL("assisted_ordinal");

    companion object {
        fun of(code: String): LoadMode = requireNotNull(entries.firstOrNull { it.code == code }) { "unknown load_mode $code" }
    }
}

/** DATA_MODEL E37. */
enum class Laterality(val code: String) {
    BILATERAL("bilateral"), UNILATERAL("unilateral"), ALTERNATING("alternating");

    companion object {
        fun of(code: String): Laterality = requireNotNull(entries.firstOrNull { it.code == code }) { "unknown laterality $code" }
    }
}

/** The exercise attributes LOAD-1 reads (B2.1, DATA_MODEL 9.2.1). */
data class ExerciseLoadAttrs(
    val fBw: Double,
    /** Bar, sled or carriage mass not included in the entered load; null = unknown (F7 asks). */
    val implementBaseKg: Double?,
    val laterality: Laterality,
    val allowedLoadModes: List<LoadMode>,
)

/**
 * One exactly loadable option on the system-load scale (B2.8). Masses are on the KG grid.
 * [scaleKg] = `addedKg - assistKg`: it orders options exactly like `wSys` (which adds a per-exercise
 * constant) and stays defined when `wSys` is not (no bodyweight yet, unknown implement base).
 */
data class LoadPoint(
    val mode: LoadMode,
    val nativeValue: Double?,
    val nativeUnit: String?,
    val implementCount: Int?,
    val perImplementKg: Double?,
    val addedKg: Double,
    val implementBaseKg: Double,
    val assistPinNative: Double?,
    val assistKg: Double?,
    val assistOrdinal: Int?,
    val bwKg: Double?,
    val fBw: Double,
    val wSys: Double?,
    val plateBreakdown: List<Double>,
    val equipmentId: String?,
    val calibrationRev: Int?,
    /** Level stacks without `level_kg` (C43) are ordinal: no kg scale, ordered by [nativeValue]. */
    val ordinal: Boolean = false,
) {
    val scaleKg: Double get() = if (ordinal) 0.0 else addedKg - (assistKg ?: 0.0)
}
