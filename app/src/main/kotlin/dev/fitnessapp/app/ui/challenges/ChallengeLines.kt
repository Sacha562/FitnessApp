package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.SessionPlan

/**
 * Plain-text lines of a materialized challenge session: the setup review (UX 14.8 step 3) and the English description
 * of the app's calendar events (CHALLENGE_ENGINE 9.4, F25) use the same wording.
 */
object ChallengeLines {
    /** "5×3 · pin 40 lb", "3 rounds × 4 tries", "3×6-8 s · pin 35 lb", "1×1 · 5 s down" for one planned item. */
    fun detailOf(item: PlannedItem): String {
        val r = item.rows.firstOrNull() ?: return ""
        fun n(x: java.math.BigDecimal) = x.stripTrailingZeros().toPlainString()
        val sets = item.rows.count { it.side == "L" || it.side == "both" || it.side == "none" }.coerceAtLeast(1)
        val attempts = r.attempts
        val volume = when {
            // Top-hold rounds (UX 11.7.4): rounds of short attempts, no per-attempt target.
            attempts != null && attempts > 1 && r.targetReps == null && r.targetHoldS == null -> "$sets rounds × $attempts tries"
            r.targetReps != null && r.targetRepsMax != null && r.targetRepsMax != r.targetReps -> "$sets×${r.targetReps}-${r.targetRepsMax}"
            r.targetReps != null -> "$sets×${r.targetReps}"
            r.targetHoldS != null -> "$sets×" + n(r.targetHoldS!!) + (r.targetHoldMaxS?.let { "-" + n(it) } ?: "") + " s"
            r.durationS != null -> n(r.durationS!!) + " s"
            else -> "$sets sets"
        }
        val pin = r.pin
        val added = r.addedKg
        val load = when {
            pin != null -> "pin ${n(pin)} lb"
            // "+" only on top of bodyweight (the belt); a cable or machine load is the load itself (Pallof, D40).
            added != null && added.signum() > 0 -> (if (r.systemLoadKg != null) "+" else "") + "${n(added)} kg"
            r.targetEccS != null -> n(r.targetEccS!!) + " s down"
            else -> null
        }
        return volume + (load?.let { " · $it" } ?: "")
    }

    /**
     * The planned items as name and detail rows for the setup review and Today's preview: "Warm-up" first and "Prehab"
     * last as one row each; a test item reads as what it measures ("Max strict pull-ups"), without a set count.
     */
    fun exercises(sp: SessionPlan): List<ReviewExercise> {
        val planned = sp.items.filter { it.status == "planned" }
        val main = planned.filter { it.role != "warmup" && it.role != "prehab" }.map { item ->
            val metric = item.metric
            if (item.role == "test" && metric != null) {
                ReviewExercise(if (metric == "bw") "Weigh-in" else ChallengeTexts.metricLabel(metric), if (item.rows.any { it.side == "L" || it.side == "R" }) "each arm" else "")
            } else {
                ReviewExercise(item.name["en"] ?: item.key, detailOf(item))
            }
        }
        return listOfNotNull(ReviewExercise("Warm-up", "").takeIf { planned.any { it.role == "warmup" } }) + main +
            listOfNotNull(ReviewExercise("Prehab", "").takeIf { planned.any { it.role == "prehab" } })
    }

    /** One line per planned item, warm-up first and prehab last (one line each), for an event description. */
    fun descriptionLines(sp: SessionPlan): List<String> {
        val planned = sp.items.filter { it.status == "planned" }
        val main = planned.filter { it.role != "warmup" && it.role != "prehab" }.map { item ->
            val metric = item.metric
            if (item.role == "test" && metric != null) {
                (if (metric == "bw") "Weigh-in" else ChallengeTexts.metricLabel(metric)) +
                    (if (item.rows.any { it.side == "L" || it.side == "R" }) ", each arm" else "")
            } else {
                val d = detailOf(item)
                (item.name["en"] ?: item.key) + (if (d.isNotEmpty()) " · $d" else "")
            }
        }
        return listOfNotNull("Warm-up".takeIf { planned.any { it.role == "warmup" } }) + main +
            listOfNotNull("Prehab".takeIf { planned.any { it.role == "prehab" } })
    }
}
