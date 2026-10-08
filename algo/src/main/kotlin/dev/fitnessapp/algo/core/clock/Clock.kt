package dev.fitnessapp.algo.core.clock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The injected clock (C31, P2). Only adapters implement it (`:app` AndroidClock, `:sim`, tests); no code
 * in `:algo` reads a system clock. Modules receive a [ClockSnapshot] in their input instead.
 */
fun interface Clock {
    fun now(): Instant
}

/** ALGORITHMS.md A6. On replay, localDate/isoWeek/tzOffsetMin come from storage (A5), not from tzdb. */
data class ClockSnapshot(
    val now: Instant,
    val zone: ZoneId,
    val localDate: LocalDate,
    val isoWeek: String,
    val tzOffsetMin: Int,
) {
    companion object {
        fun at(now: Instant, zone: ZoneId): ClockSnapshot {
            val date = Week.localDate(now, zone)
            return ClockSnapshot(now, zone, date, Week.isoWeek(date), Week.offsetMinutes(now, zone))
        }
    }
}
