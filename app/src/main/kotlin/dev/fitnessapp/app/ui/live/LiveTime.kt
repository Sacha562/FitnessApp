package dev.fitnessapp.app.ui.live

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.time.TimeItem
import dev.fitnessapp.algo.selection.time.TimeModel
import dev.fitnessapp.algo.selection.time.WarmupKind

/**
 * TIME-1 for the rest of a live session (C63, D62): the working sets still to do of the open items, each with its
 * planned rest, the setup only before its first set, and reps from the prescription (else the planned range's low end,
 * else 10) or the planned hold. Used by the overrun line, the add-on header and the in-session time budget.
 */
object LiveTime {
    fun item(it: ExerciseItem, catalog: Catalog): TimeItem? {
        if (!it.open) return null
        val left = maxOf(0, it.planned - it.workingDone)
        if (left == 0) return null
        val hold = it.se.planned_duration_s?.let { d -> d / 10.0 }
        val reps = it.rx?.sets?.firstOrNull()?.targetReps?.toDouble() ?: it.se.planned_rep_min?.toDouble() ?: 10.0
        return TimeItem(
            key = it.se.id, sets = left, restS = (it.se.planned_rest_s ?: 90).toDouble(),
            setupS = if (it.workingDone == 0) catalog.exercise(it.ex.slug)?.setupS?.toDouble() ?: 0.0 else 0.0,
            reps = if (hold == null) reps else null, eccS = it.se.planned_ecc_s?.let { e -> e / 10.0 }, holdS = hold,
            unilateralPair = it.unilateral,
        )
    }

    /** Minutes of [items]' remaining work. */
    fun remainingMin(items: List<ExerciseItem>, catalog: Catalog, params: ParamView): Double =
        TimeModel.minutes(TimeModel.blockSeconds(items.mapNotNull { item(it, catalog) }, WarmupKind.CHALLENGE, params))
}
