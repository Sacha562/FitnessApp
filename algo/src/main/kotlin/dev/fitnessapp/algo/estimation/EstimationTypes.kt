package dev.fitnessapp.algo.estimation

import dev.fitnessapp.algo.core.load.LoadMode
import java.time.Instant
import java.time.LocalDate

/** RCM-1 curve classes (B5, DATA_MODEL E40); the class priors live in `rcm.k0`. */
enum class CurveClass(val code: String) {
    UPPER_COMPOUND("upper_compound"),
    LOWER_MACHINE_COMPOUND("lower_machine_compound"),
    LOWER_FREE_COMPOUND("lower_free_compound"),
    UPPER_ISOLATION("upper_isolation"),
    LOWER_ISOLATION("lower_isolation");

    companion object {
        fun of(code: String): CurveClass = requireNotNull(entries.firstOrNull { it.code == code }) { "unknown curve_class $code" }
    }
}

/** REASON-1 estimator classes (B12.2). */
enum class EstimatorKind(val code: String) {
    NORMAL("normal"), DOWNWEIGHT("downweight"), LOWER_BOUND("lower_bound"), EXCLUDED("excluded")
}

/** An estimator class with its variance multiplier [m] (1 unless [kind] is DOWNWEIGHT). */
data class EstimatorClass(val kind: EstimatorKind, val m: Double = 1.0) {
    companion object {
        val NORMAL = EstimatorClass(EstimatorKind.NORMAL)
        val LOWER_BOUND = EstimatorClass(EstimatorKind.LOWER_BOUND)
        val EXCLUDED = EstimatorClass(EstimatorKind.EXCLUDED)
        fun downweight(m: Double) = EstimatorClass(EstimatorKind.DOWNWEIGHT, m)
    }
}

/**
 * One logged set as the estimators read it (`set_logs` joined with its exercise, DATA_MODEL 9.6). [wSys] comes
 * from LOAD-1 (B2), [side] is E1 (`both`, `L`, `R`), [setKind] E2, [contraction] E39. [rirReported] is the chip
 * value (null = missing); [rirLowerBound] marks the "5+" chip. [calibExtraReps] is set on an explicit RIR check
 * (B4.3). [rirPred], [targetReps] and [qStar] are the prescription's values for this set, used only by the
 * REASON-1 imputation of a missing RIR. [restBeforeS] is the measured rest before this set, [plannedRestBeforeS]
 * the planned one.
 */
data class LoggedSet(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val side: String,
    val performedAt: Instant,
    val localDate: LocalDate,
    val setKind: String,
    val contraction: String,
    val loadMode: LoadMode,
    val wSys: Double?,
    val reps: Int,
    val rirReported: Double? = null,
    val rirLowerBound: Boolean = false,
    val failedRep: Boolean = false,
    val fingerAssisted: Boolean = false,
    val reasonCode: String? = null,
    val calibExtraReps: Int? = null,
    val rirPred: Double? = null,
    val targetReps: Int? = null,
    val qStar: Double? = null,
    val restBeforeS: Double? = null,
    val plannedRestBeforeS: Double? = null,
)

/** The catalog facts of one exercise the estimators read. [groupCoeffs] maps muscle group id to `groupCoeff`. */
data class ExerciseFacts(
    val exerciseId: String,
    val curveClass: CurveClass?,
    val fBw: Double,
    val implementBaseKg: Double,
    val groupCoeffs: Map<String, Double>,
)
