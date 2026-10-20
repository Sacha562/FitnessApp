package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan
import dev.fitnessapp.algo.challenge.plan.DoneSession
import dev.fitnessapp.algo.challenge.plan.GymAnchor
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.data.challenge.FoldedEnrollment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The planner input of CHALLENGE_ENGINE 7 as the phone knows it before the calendar arrives (M2b.5): this ISO week's
 * finished challenge sessions (done slots), its general gym sessions, and the previous week's last gym visit.
 */
object ChallengeWeek {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    suspend fun input(c: AppContainer, f: FoldedEnrollment, facts: EngineFacts, now: Instant = Instant.now()): PlanInput {
        val today = Week.localDate(now, ZONE)
        val monday = Week.isoWeekStart(today)
        val sessions = c.db.sessions().finishedInIsoWeek(Week.isoWeek(today))
        val done = sessions.filter { it.enrollment_id == f.enrollment.id && it.challenge_slot != null }.map {
            DoneSession(it.challenge_slot!!, LocalDate.parse(it.local_date), interval(it.started_at, it.finished_at))
        }
        val general = sessions.filter { it.enrollment_id == null && it.kind == "gym" }.map {
            GymAnchor(LocalDate.parse(it.local_date), interval(it.started_at, it.finished_at), emptyList())
        }
        val prev = c.db.sessions().lastGymBefore(Week.atLocal(monday, LocalTime.MIDNIGHT, ZONE).toEpochMilli())?.let { p ->
            val tags = p.challenge_slot?.let { slot -> Materializer.templateFor(f.def, f.state, slot)?.tags }.orEmpty()
            GymAnchor(LocalDate.parse(p.local_date), interval(p.started_at, p.finished_at), tags)
        }
        return PlanInput(now, ZONE, f.state, facts, monday, done = done, previousLastGym = prev, general = general)
    }

    suspend fun plan(c: AppContainer, f: FoldedEnrollment, facts: EngineFacts, now: Instant = Instant.now()): Pair<PlanInput, ChallengeWeekPlan> {
        val input = input(c, f, facts, now)
        return input to WeekPlanner.plan(f.def, c.params, input)
    }

    private fun interval(start: Long?, end: Long?): Interval? =
        if (start != null && end != null && end > start) Interval(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)) else null
}
