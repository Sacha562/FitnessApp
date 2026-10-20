package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Tick synthesis of CHALLENGE_ENGINE 4.6: `day.tick` per local date (DST-safe date iteration), `test.due`
 * on the planned test date, and `week.closed` / `week.started` at the Monday boundary time. Idempotent:
 * ids are UUIDv5 of `challenge_events:<enrollment_id>:<dedupe_key>`, and no event lies after [now].
 */
object TickSynthesizer {
    /** Fixed local times of 4.3 for the boundary events (milliseconds after local midnight). */
    private const val DAY_TICK_MS = 2L
    private const val TEST_DUE_MS = 3L
    private const val WEEK_STARTED_AFTER_CLOSE_MS = 1L

    data class Output(val events: List<ChallengeEvent>, val catchupTicks: Int)

    fun eventId(enrollmentId: UUID, dedupeKey: String): UUID = Ids.fitness("challenge_events:$enrollmentId:$dedupeKey")

    private fun at(d: LocalDate, zone: ZoneId, millis: Long): Instant =
        Week.atLocal(d, LocalTime.MIDNIGHT, zone).plusMillis(millis)

    /**
     * Events due between the state's cursors and [now]. [weekCloseLocalMin] is
     * `challenge.enrollment.week_close_local_time` (minutes after local midnight).
     */
    fun synthesize(state: EnrollmentState, now: Instant, zone: ZoneId, weekCloseLocalMin: Int, facts: EngineFacts? = null): Output {
        if (state.status == EnrollmentState.COMPLETED || state.status == EnrollmentState.ABANDONED) return Output(emptyList(), 0)
        val today = Week.localDate(now, zone)
        val out = ArrayList<ChallengeEvent>()
        var d = state.lastTickDate.plusDays(1)
        var ticks = 0
        while (!d.isAfter(today)) {
            val tickAt = at(d, zone, DAY_TICK_MS)
            if (tickAt.isAfter(now)) break
            out += ChallengeEvent(eventId(state.enrollmentId, "day.tick:$d"), EventTypes.DAY_TICK, tickAt, d, EventPayload.DayTick(d), facts)
            ticks++
            if (state.plannedTest.date == d) {
                out += ChallengeEvent(
                    eventId(state.enrollmentId, "test.due:$d"), EventTypes.TEST_DUE, at(d, zone, TEST_DUE_MS), d, EventPayload.TestDue(d), facts,
                )
            }
            d = d.plusDays(1)
        }
        var m = state.lastWeekBoundary.plusDays(7)
        while (!m.isAfter(today)) {
            val closeAt = Week.atLocal(m, LocalTime.MIDNIGHT.plusMinutes(weekCloseLocalMin.toLong()), zone)
            if (now.isBefore(closeAt)) break
            val closed = Week.isoWeek(m.minusDays(1))
            val started = Week.isoWeek(m)
            out += ChallengeEvent(
                eventId(state.enrollmentId, "week.closed:$closed"), EventTypes.WEEK_CLOSED, closeAt, m, EventPayload.WeekBoundary(closed), facts,
            )
            out += ChallengeEvent(
                eventId(state.enrollmentId, "week.started:$started"), EventTypes.WEEK_STARTED, closeAt.plusMillis(WEEK_STARTED_AFTER_CLOSE_MS), m,
                EventPayload.WeekBoundary(started), facts,
            )
            m = m.plusDays(7)
        }
        return Output(out.sortedWith(ChallengeEvent.ORDER), ticks)
    }
}
