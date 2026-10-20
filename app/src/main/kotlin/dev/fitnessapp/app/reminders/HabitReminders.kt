package dev.fitnessapp.app.reminders

import dev.fitnessapp.algo.challenge.materialize.HabitToday
import dev.fitnessapp.algo.core.clock.Week
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One habit reminder (N-HABIT) of the horizon: due at the habit's time on [date] in the profile zone. */
data class HabitReminder(val id: String, val habitId: String, val date: LocalDate, val dueMs: Long, val title: String, val text: String?)

/** A day's habit rows with the wording the notification shows. */
data class HabitDayRows(val date: LocalDate, val rows: List<Row>) {
    data class Row(val habit: HabitToday, val title: String, val text: String?)
}

/**
 * The reminders of the next `reminders.horizonH` hours (ANDROID.md 17): one per due or scaled habit, at its
 * `LocalDate + LocalTime` in the profile zone (17:15 stays 17:15 across the DST change, G8). Done, skipped and muted
 * habits get none; neither do times already past.
 */
object HabitReminders {
    fun plan(days: List<HabitDayRows>, now: Instant, horizonH: Int, zone: ZoneId): List<HabitReminder> {
        val end = now.plusSeconds(horizonH * 3600L)
        return days.flatMap { d ->
            d.rows.filter { it.habit.state == "DUE" || it.habit.state == "SCALED" }.mapNotNull { r ->
                val due = Week.atLocal(d.date, r.habit.time, zone)
                if (!due.isAfter(now) || due.isAfter(end)) null
                else HabitReminder(id(r.habit.habitId, d.date), r.habit.habitId, d.date, due.toEpochMilli(), r.title, r.text)
            }
        }.sortedWith(compareBy({ it.dueMs }, { it.id }))
    }

    fun id(habitId: String, date: LocalDate): String = "habit:$habitId:$date"

    /**
     * ANDROID.md 13.2: 2000 + (hash(reminderId) mod 1000), probing upwards past codes already in [used]; the
     * notification and its alarm share the code. Null when the range is full.
     */
    fun requestCode(reminderId: String, used: Set<Int>): Int? {
        val start = Math.floorMod(reminderId.hashCode(), RANGE)
        return (0 until RANGE).map { BASE + (start + it) % RANGE }.firstOrNull { it !in used }
    }

    const val BASE = 2000
    const val RANGE = 1000
}
