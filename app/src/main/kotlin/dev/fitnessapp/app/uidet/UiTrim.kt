package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.planning.allocator.Allocation

/** One time chip (UX 9.4): minutes, its label, its source (`calendar` or `chip`); "90+" carries `today.max_budget_min`. */
data class TimeChip(val minutes: Int, val label: String, val source: String)

/**
 * The time chips and the build preview's texts (UX 9.4, D62), pure so the spec examples are unit tests: the calendar
 * chip, the trim line "To fit 60 min: 1 set fewer on lateral raise; calf raise left out." and the empty-build texts.
 */
object UiTrim {
    /**
     * A calendar budget equal to a standard chip relabels that chip ("75 cal"); any other is an extra chip shown first
     * ("85 cal"). Then the [standard] chips and "90+" ([maxMin]).
     */
    fun chips(calendarMin: Int?, standard: List<Int>, maxMin: Int): List<TimeChip> {
        val out = ArrayList<TimeChip>()
        if (calendarMin != null && calendarMin !in standard) out += TimeChip(calendarMin, "$calendarMin cal", "calendar")
        standard.forEach { m -> out += if (m == calendarMin) TimeChip(m, "$m cal", "calendar") else TimeChip(m, "$m", "chip") }
        out += TimeChip(maxMin, "90+", "chip")
        return out
    }

    /** A type the allocator can give at least one set today. */
    fun usable(a: Allocation?): Boolean = a != null && a.feasible && a.credit.values.any { it > 0.0 }

    fun minutes(m: Double): Int = kotlin.math.round(m).toInt()

    /** Why a build came out empty, in the user's words. */
    fun emptyText(b: BuildResult?): String = when (b?.empty) {
        null -> "Couldn't build this session."
        "NOTHING_NEEDED" -> "This week's targets for these muscles are already met."
        "NO_ELIGIBLE" -> "No exercise fits today's limits and your equipment."
        else -> "Not today: these muscles are covered or need rest."
    }

    /**
     * "To fit 60 min: 1 set fewer on lateral raise; calf raise left out." (UX 9.4) when UI-02 cut anything at a chip
     * shorter than "90+" ([max]); null otherwise. [name] gives an exercise's display name.
     */
    fun trimLine(b: BuildResult, chipMin: Int, max: Int, name: (String) -> String): String? {
        if (chipMin >= max || b.trimmed.isEmpty()) return null
        val parts = b.trimmed.map { t ->
            if (t.removed) "${name(t.slug).lowercase()} left out"
            else "${t.setsRemoved} set${if (t.setsRemoved == 1) "" else "s"} fewer on ${name(t.slug).lowercase()}"
        }
        return "To fit $chipMin min: " + parts.joinToString("; ") + "."
    }
}
