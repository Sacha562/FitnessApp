package dev.fitnessapp.algo.core.calendar

import dev.fitnessapp.algo.core.clock.Week
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Instances of fixture F-CAL-41 (ALGORITHMS_PLANNING 24) and helpers for the calendar tests. */
object CalendarFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    const val PRIMARY = 1L
    const val TODOIST = 2L
    const val ENROLLMENT = "0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b"
    const val RECOMMENDATION = "0199c3a0-0000-7000-8000-00000000abcd"

    val now: Instant = at("2026-10-07", "12:00")

    fun at(date: String, time: String): Instant = Week.atLocal(LocalDate.parse(date), LocalTime.parse(time), zone)

    fun inst(
        id: Long,
        title: String,
        date: String,
        start: String,
        end: String,
        calendarId: Long = PRIMARY,
        endDate: String = date,
        description: String? = null,
        location: String? = null,
        allDay: Boolean = false,
        status: String = "confirmed",
        declined: Boolean = false,
        free: Boolean = false,
    ): CalendarInstance {
        val b = at(date, start)
        return CalendarInstance(
            instanceKey = CalendarKeys.instanceKey("com.google", "me@example.com", "cal$calendarId", "ev$id", b.toEpochMilli()),
            seriesKey = CalendarKeys.seriesKey("com.google", "me@example.com", "cal$calendarId", "ev$id"),
            eventId = id, calendarId = calendarId, calendarName = if (calendarId == TODOIST) "Todoist" else "me@example.com",
            calendarWritable = calendarId == PRIMARY, beginUtc = b, endUtc = at(endDate, end), allDay = allDay, title = title,
            description = description, location = location, status = status, declined = declined, availabilityFree = free,
        )
    }

    /** F-CAL-41: the real week 2026-W41 with the calendar cases of the classifier tests. */
    val fCal41: List<CalendarInstance> = listOf(
        inst(10, "Gym — sessie C (volume en techniek)", "2026-10-06", "19:45", "21:15"),
        inst(11, "Gym — sessie A (één arm zwaar)", "2026-10-08", "19:45", "21:15"),
        inst(12, "Gym — testdag 1 (meten)", "2026-10-11", "13:00", "15:00"),
        inst(13, "Gym", "2026-10-07", "00:00", "00:00", calendarId = TODOIST, endDate = "2026-10-08", allDay = true),
        inst(14, "Schaken", "2026-10-05", "19:30", "22:30"),
        inst(15, "Hardlopen", "2026-10-09", "07:00", "07:45"),
        inst(16, "Intervaltraining hardlopen", "2026-10-10", "09:00", "09:50"),
        inst(17, "Borrel", "2026-10-09", "17:00", "19:00", declined = true),
        inst(18, "Gym — sessie B", "2026-10-10", "11:00", "12:30", status = "cancelled"),
        inst(19, "Dentist", "2026-10-09", "09:00", "09:30"),
    )

    val gymHours: List<OpeningHours> = (1..7).map { d ->
        if (d <= 5) OpeningHours(d, 7 * 60, 22 * 60 + 30) else OpeningHours(d, 9 * 60, 16 * 60)
    }
}
