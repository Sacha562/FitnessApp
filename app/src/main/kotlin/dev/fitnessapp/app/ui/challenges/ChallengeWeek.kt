package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan
import dev.fitnessapp.algo.challenge.plan.DoneSession
import dev.fitnessapp.algo.challenge.plan.GymAnchor
import dev.fitnessapp.algo.challenge.plan.Interval
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.algo.challenge.plan.SlotAnchor
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.data.challenge.FoldedEnrollment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The planner input of CHALLENGE_ENGINE 7 as the phone knows it: this ISO week's finished challenge sessions (done
 * slots), its general gym sessions, the previous week's last gym visit, and from the calendar (M2b.5) the events
 * matched to this enrollment's slots (fixed), busy blocks, runs and planned general gym sessions.
 */
object ChallengeWeek {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    suspend fun input(c: AppContainer, f: FoldedEnrollment, facts: EngineFacts, now: Instant = Instant.now(), weekOffset: Int = 0): PlanInput {
        val today = Week.localDate(now, ZONE)
        val monday = Week.isoWeekStart(today).plusWeeks(weekOffset.toLong())
        val sessions = if (weekOffset == 0) c.db.sessions().finishedInIsoWeek(Week.isoWeek(today)) else emptyList()
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
        val week = Week.isoWeek(monday)
        val anchors = c.calendar.snapshot.value.anchors.filter { Week.isoWeek(it.localDate) == week }
        val matched = anchors.filter { it.kind == AnchorKind.CHALLENGE_SESSION && it.enrollmentId == f.enrollment.id && it.slot != null }.map {
            SlotAnchor(it.slot!!, Interval(it.beginUtc, it.endUtc), if (it.marker != null) "calendar_marked" else "calendar_unmarked", it.instanceKey, it.attention != null)
        }
        val busy = anchors.filter { it.kind == AnchorKind.BUSY || (it.kind == AnchorKind.CHALLENGE_SESSION && it.enrollmentId != f.enrollment.id) }
            .map { Interval(it.beginUtc, it.endUtc) }
        val runs = anchors.filter { it.kind == AnchorKind.RUN }.map { Interval(it.beginUtc, it.endUtc) }
        val planned = anchors.filter { it.kind == AnchorKind.PLANNED_GYM }.map { GymAnchor(it.localDate, Interval(it.beginUtc, it.endUtc), emptyList()) }
            .filter { g -> general.none { it.date == g.date } }
        return PlanInput(
            now, ZONE, f.state, facts, monday, done = done, matched = matched, previousLastGym = if (weekOffset == 0) prev else null,
            general = general + planned, runs = runs, busy = busy, projected = weekOffset > 0,
        )
    }

    suspend fun plan(c: AppContainer, f: FoldedEnrollment, facts: EngineFacts, now: Instant = Instant.now(), weekOffset: Int = 0): Pair<PlanInput, ChallengeWeekPlan> {
        val input = input(c, f, facts, now, weekOffset)
        return input to WeekPlanner.plan(f.def, c.params, input)
    }

    /**
     * "Next test" for the library card and the dashboard: the test day as this week's plan places it (calendar event or
     * suggestion, with its time), else the program's planned test date.
     */
    suspend fun nextTestText(c: AppContainer, f: FoldedEnrollment, facts: EngineFacts): String? {
        val s = f.state
        if (s.status == dev.fitnessapp.algo.challenge.enrollment.EnrollmentState.ACTIVE) {
            val testSlot = f.def.doc.structure.block.testSlot
            val placed = runCatching { plan(c, f, facts).second.sessions.firstOrNull { it.slot == testSlot && it.source != "done" } }.getOrNull()
            if (placed != null) return "Next test: ${ChallengeTexts.date(placed.date)} ${ChallengeTexts.time(Week.localTime(placed.interval.start, ZONE))}"
        }
        return s.plannedTest.date?.let { "Next test: ${ChallengeTexts.date(it)}" } ?: if (s.plannedTest.basis == "postponed") "Test postponed" else null
    }

    private fun interval(start: Long?, end: Long?): Interval? =
        if (start != null && end != null && end > start) Interval(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)) else null
}
