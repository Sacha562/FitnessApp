package dev.fitnessapp.app.reminders

import dev.fitnessapp.algo.challenge.materialize.HabitToday
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ANDROID.md 17: the 48 h horizon, due times in the profile zone across DST (G8) and request codes (13.2). */
class HabitRemindersTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun day(date: String, vararg rows: Pair<String, String>) = HabitDayRows(
        LocalDate.parse(date),
        rows.map { (id, state) -> HabitDayRows.Row(HabitToday(id, LocalTime.of(17, if (id == "daily_hang") 15 else 30), state), id, null) },
    )

    @Test
    fun `due and scaled habits within the horizon, at their local time across the DST change`() {
        // Saturday 24 Oct 2026 12:00 CEST; clocks go back on Sunday 25 Oct.
        val now = Instant.parse("2026-10-24T10:00:00Z")
        val r = HabitReminders.plan(
            listOf(
                day("2026-10-24", "daily_hang" to "DUE", "home_plank" to "DONE"),
                day("2026-10-25", "daily_hang" to "SCALED"),
                day("2026-10-26", "daily_hang" to "DUE", "home_plank" to "SKIPPED"),
            ),
            now, 48, zone,
        )
        r.map { it.id } shouldBe listOf("habit:daily_hang:2026-10-24", "habit:daily_hang:2026-10-25")
        r[0].dueMs shouldBe Instant.parse("2026-10-24T15:15:00Z").toEpochMilli() // 17:15 CEST
        r[1].dueMs shouldBe Instant.parse("2026-10-25T16:15:00Z").toEpochMilli() // 17:15 CET
        // A time already past gets none.
        HabitReminders.plan(listOf(day("2026-10-24", "daily_hang" to "DUE")), Instant.parse("2026-10-24T15:15:00Z"), 48, zone) shouldBe emptyList()
    }

    @Test
    fun `request codes stay in 2000-2999 and probe past codes in use`() {
        val id = HabitReminders.id("daily_hang", LocalDate.parse("2026-10-24"))
        val first = HabitReminders.requestCode(id, emptySet())!!
        (first in 2000..2999) shouldBe true
        val second = HabitReminders.requestCode(id, setOf(first))!!
        second shouldBe if (first == 2999) 2000 else first + 1
        HabitReminders.requestCode(id, (2000..2999).toSet()) shouldBe null
    }
}
