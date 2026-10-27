package dev.fitnessapp.algo.prescription.reason

import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.estimation.EstimatorClass
import dev.fitnessapp.algo.estimation.EstimatorKind
import dev.fitnessapp.algo.estimation.LoggedSet

/** The estimator view of one set's reason: its class and whether it ended technique-limited (`q_true = 0`). */
data class SetSemantics(val estimatorClass: EstimatorClass, val techniqueLimited: Boolean = false)

/**
 * The estimator column of REASON-1 (ALGORITHMS.md B12.2-B12.4): the estimator class of a set from its reason
 * code and chip, and the imputation of a missing RIR. Built with the estimators (M3.1, D49); the session and
 * future effects of B12.3 follow with the rest of REASON-1 in M3.3.
 */
object ReasonSemantics : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("reason")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("reason.feeling_bad_m", "variance multiplier", 1.0, 16.0),
        ParamSpec.number("reason.impute_offset", "RIR", 0.0, 4.0),
        ParamSpec.number("reason.impute_sigma_q", "reps", 1.0, 4.0),
        ParamSpec.number("reason.other_m", "variance multiplier", 1.0, 9.0),
        ParamSpec.number("reason.too_tired_m", "variance multiplier", 1.0, 16.0),
    )

    /** B12.3 "Set context" column; the "5+" chip makes a usable set a lower bound (B6.3). */
    fun forSet(set: LoggedSet, params: ParamView): SetSemantics {
        val base = when (set.reasonCode) {
            null, "none", "too_easy", "too_hard", "feeling_good", "no_time" -> SetSemantics(EstimatorClass.NORMAL)
            "feeling_bad" -> SetSemantics(EstimatorClass.downweight(params.double("reason.feeling_bad_m")))
            "too_tired" -> SetSemantics(EstimatorClass.downweight(params.double("reason.too_tired_m")))
            "other" -> SetSemantics(EstimatorClass.downweight(params.double("reason.other_m")))
            "grip_failure" -> SetSemantics(EstimatorClass.LOWER_BOUND)
            "torso_rotation", "technique_breakdown" -> SetSemantics(EstimatorClass.NORMAL, techniqueLimited = true)
            // pain, occupied, equipment_missing, and any code this version does not know: never an observation.
            else -> SetSemantics(EstimatorClass.EXCLUDED)
        }
        return if (set.rirLowerBound && base.estimatorClass.kind != EstimatorKind.EXCLUDED) {
            base.copy(estimatorClass = EstimatorClass.LOWER_BOUND)
        } else {
            base
        }
    }

    /**
     * The imputed `q` of a set without a chip RIR (B12.3 `too_easy` / `too_hard`, else B12.4), or null when the
     * prescription values it needs are missing (the set then gives no observation).
     */
    fun imputeQ(set: LoggedSet, params: ParamView): Double? {
        val target = set.targetReps
        return when (set.reasonCode) {
            "too_easy" -> set.rirPred?.let { it + params.double("reason.impute_offset") }
            "too_hard" -> when {
                target == null -> null
                set.reps < target -> 0.0
                else -> set.qStar?.let { maxOf(0.0, it - 2.0) }
            }
            else -> {
                val pred = set.rirPred
                when {
                    target == null || pred == null -> null
                    set.reps >= target -> pred.coerceIn(0.0, 5.0)
                    set.reps == target - 1 -> minOf(1.0, pred).coerceIn(0.0, 5.0)
                    else -> 0.0
                }
            }
        }
    }

    /** The floor on `sigma_q` of an imputed RIR (B12.4). */
    fun imputeSigmaQ(params: ParamView): Double = params.double("reason.impute_sigma_q")
}
