package dev.fitnessapp.algo.core.clock

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/**
 * CLOCK-1 (ALGORITHMS.md B1, C31): local days, ISO weeks and elapsed time. Elapsed durations use
 * Instants; "which day / which week" uses local dates in the user's zone (F18).
 * The mesocycle function needs the challenge contract and the `clock.meso_weeks` param; it lands with
 * the params resolver (ALGORITHMS.md build order, M2/M3).
 */
object Week {
    private const val MS_PER_HOUR: Double = 3_600_000.0

    fun localDate(i: Instant, zone: ZoneId): LocalDate = i.atZone(zone).toLocalDate()

    fun localTime(i: Instant, zone: ZoneId): LocalTime = i.atZone(zone).toLocalTime()

    fun offsetMinutes(i: Instant, zone: ZoneId): Int = zone.rules.getOffset(i).totalSeconds / 60

    /** `YYYY-Www` (E52), zero-padded with integer arithmetic, never a locale-dependent formatter (P8). */
    fun isoWeek(d: LocalDate): String {
        val year = d.get(IsoFields.WEEK_BASED_YEAR)
        val week = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return pad(year, 4) + "-W" + pad(week, 2)
    }

    /** The Monday on or before [d]. */
    fun isoWeekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    fun elapsedHours(a: Instant, b: Instant): Double = (b.toEpochMilli() - a.toEpochMilli()) / MS_PER_HOUR

    /** Elapsed days (hours / 24); used by decay models. Never calendar days. */
    fun elapsedDays(a: Instant, b: Instant): Double = elapsedHours(a, b) / 24.0

    /** Calendar days between local dates; used for "the day before", staleness and cooldowns. */
    fun calendarDays(d1: LocalDate, d2: LocalDate): Long = ChronoUnit.DAYS.between(d1, d2)

    /**
     * A local date and time in [zone]. Inside the spring gap the time moves forward by the gap; inside
     * the autumn overlap the earlier offset wins (java.time behaviour, B1).
     */
    fun atLocal(d: LocalDate, t: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.ofLocal(LocalDateTime.of(d, t), zone, null).toInstant()

    private fun pad(value: Int, width: Int): String {
        val digits = value.toString()
        return if (digits.length >= width) digits else "0".repeat(width - digits.length) + digits
    }
}
