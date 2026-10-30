package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.selection.time.WarmupKind
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * TIME-1's estimate of a materialized challenge block (CHALLENGE_ENGINE 6.9, ALGORITHMS_PLANNING 17.6): the planned
 * items, the warm-up item included, as TIME-1 items. A row index is one set; an L and R row of the same index are one
 * unilateral pair (C7). Reps, holds, eccentric seconds, attempts and rests come from the first working row of the
 * item, the setup from the catalog row (0 for an exercise outside the catalog).
 */
object ChallengeTime {
    fun items(plan: SessionPlan, catalog: Catalog?): List<TimeItem> = plan.items.filter { it.status == "planned" }.mapNotNull { item ->
        val rows = item.rows.filter { it.setKind != "warmup" || item.role == "warmup" }
        if (rows.isEmpty()) return@mapNotNull null
        val first = rows.first()
        val pair = rows.any { it.side == "L" } && rows.any { it.side == "R" }
        val sets = rows.map { it.setIndex }.distinct().size
        val hold = first.targetHoldS ?: first.durationS
        TimeItem(
            key = item.key,
            sets = sets,
            restS = (first.restS ?: 0).toDouble(),
            setupS = item.catalogSlug?.let { catalog?.exercise(it)?.setupS?.toDouble() } ?: 0.0,
            reps = if (hold == null && first.attempts == null) (first.targetReps ?: first.targetRepsMax ?: 1).toDouble() else null,
            eccS = first.targetEccS?.toDouble(),
            holdS = hold?.toDouble(),
            attempts = first.attempts,
            unilateralPair = pair,
        )
    }

    /** The block's minutes on the 0.1 grid. */
    fun estMin(plan: SessionPlan, catalog: Catalog?, params: ParamView): BigDecimal =
        BigDecimal(TimeModel.minutes(TimeModel.blockSeconds(items(plan, catalog), WarmupKind.CHALLENGE, params))).setScale(1, RoundingMode.HALF_UP)
}
