package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.reminders.HabitDayRows
import dev.fitnessapp.app.ui.today.HabitTexts
import dev.fitnessapp.data.challenge.FoldedEnrollment
import dev.fitnessapp.data.challenge.HabitDay
import java.time.Instant
import java.time.LocalDate

/**
 * The home habits of a day for the running enrollment (UX 9.6, ANDROID.md 17): the week plan as the phone knows it
 * ([ChallengeWeek]) gives `todayHas` and `dayBefore(...)` their slots, general gym sessions make a `gymDay`.
 */
object HabitDays {
    /** [date]'s habits with the week plan as seen at [at]; null without an active or paused enrollment. */
    suspend fun of(c: AppContainer, date: LocalDate, at: Instant): HabitDay? {
        val e = c.challenges.open().firstOrNull { it.status == "active" || it.status == "paused" } ?: return null
        val f = c.challenges.folded(e.id)
        val (input, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), at)
        return of(c, f, date, input, plan)
    }

    suspend fun of(c: AppContainer, f: FoldedEnrollment, date: LocalDate, input: PlanInput, plan: ChallengeWeekPlan): HabitDay {
        val slots = plan.sessions.groupBy { it.date }.mapValues { (_, l) -> l.map { it.slot } }.toSortedMap()
        return c.challengeSessions.habitDay(f.enrollment.id, date, slots, input.general.map { it.date }.toSet())
    }

    /** The notification wording of a day's rows: the habit's name and its dose. */
    fun rows(day: HabitDay): HabitDayRows = HabitDayRows(
        day.input.date,
        day.rows.map { r -> HabitDayRows.Row(r, day.def.doc.habits.firstOrNull { it.id == r.habitId }?.name?.get("en") ?: r.habitId, HabitTexts.dose(r.dose)) },
    )
}
