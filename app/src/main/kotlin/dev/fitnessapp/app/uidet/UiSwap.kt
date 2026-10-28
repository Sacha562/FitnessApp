package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.substitute.SubCandidate

/**
 * The tags of a SUB-1 candidate on the Swap sheet and the exercise detail's Alternatives (UX 13.3, 16.5): how close
 * the muscles are, the preference, and whether it needs no station. Display only; the ranking is SUB-1's.
 */
object UiSwap {
    /** Similarity from which the muscles count as the same. */
    const val SAME_MUSCLES_SIM: Double = 0.95

    fun tags(c: SubCandidate, ex: CatalogExercise, onlyDisliked: Boolean): List<String> = buildList {
        add(if (c.similarity >= SAME_MUSCLES_SIM) "same muscles" else "similar muscles")
        when (c.tier) {
            PrefTier.FAVORITE -> add("favorite")
            PrefTier.LIKE -> add("liked")
            PrefTier.DISLIKE -> add(if (onlyDisliked) "disliked: closest option" else "disliked")
            else -> {}
        }
        if (ex.requiresStation.isEmpty()) add("no station")
    }

    /**
     * The start line (16.5): the last working set when there is one, else how the first set is found: from the
     * replaced exercise through a transfer ratio, or a calibration set aimed at the target RIR + 2.
     */
    fun startLine(last: String?, transferFrom: String?): String = when {
        last != null -> "last time $last"
        transferFrom != null -> "first time: start load estimated from $transferFrom"
        else -> "first time: an easy calibration set first"
    }
}
