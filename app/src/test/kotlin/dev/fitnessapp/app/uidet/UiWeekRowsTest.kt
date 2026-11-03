package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanDay
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.ReasonCode
import dev.fitnessapp.algo.planning.weekplan.Visits
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.calendar.CalendarPrompt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** UX 10.3 day rows as D82 stacks them (`ui.weekrows`), on a hand-made W41 plan and calendar. */
class UiWeekRowsTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val monday = LocalDate.parse("2026-10-05")
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private val today = LocalDate.parse("2026-10-07")
    private val rec = "0190a1f0-4141-7000-8000-000000000041"
    private val enrollment = "0190a1f0-0000-7000-8000-00000000e001"

    private fun at(date: String, time: String) = Week.atLocal(LocalDate.parse(date), java.time.LocalTime.parse(time), zone)

    private fun item(kind: String, date: String, label: String, type: String? = null, slot: String? = null, key: String = "$kind:$date", direct: Map<String, Double> = emptyMap(), est: Double = 50.0) =
        PlanItem(key, kind, slot, type, label, at(date, "19:45"), at(date, "21:15"), est, direct, writePolicy = "never", source = "weekplan")

    private fun day(date: String, status: String, items: List<PlanItem> = emptyList(), reason: ReasonCode? = null, families: List<String>? = null) =
        PlanDay(LocalDate.parse(date), status, items, reason, families)

    private fun plan(days: List<PlanDay>) = WeekPlan(
        "2026-W41", "id", false, days, Visits(1, 2, 1, 4), 4, emptyMap(), 4.0, emptyMap(), Band(4.0, 0.05, 1, "only"),
        emptyList(), emptyList(), 1, 1, false, emptyList(),
    )

    private fun anchor(key: String, date: String, kind: AnchorKind, marker: Marker? = null, slot: String? = null) = Anchor(
        key, key, 1, 1, at(date, "19:45"), at(date, "21:15"), LocalDate.parse(date), java.time.LocalTime.of(19, 45), java.time.LocalTime.of(21, 15),
        kind, slot = slot, enrollmentId = if (slot != null) enrollment else null, marker = marker,
        method = if (marker != null) MatchMethod.MARKER else MatchMethod.SCORE, classifierVersion = "1.0.0",
    )

    private fun instance(key: String, date: String, title: String) =
        CalendarInstance(key, key, 1, 1, "primary", true, at(date, "19:45"), at(date, "21:15"), false, title, null, null, "confirmed", false, false)

    private val week = plan(
        listOf(
            day("2026-10-05", "past", reason = ReasonCode("PAST_DAY")),
            day("2026-10-06", "fixed", listOf(item(FixedItem.KIND_DONE, "2026-10-06", "Session C", slot = "C"))),
            day("2026-10-07", "free", reason = ReasonCode("MAX_VISITS", sortedMapOf("used" to "4", "max" to "4")), families = listOf("LEGS", "PUSH", "CORE")),
            day("2026-10-08", "fixed", listOf(item(FixedItem.KIND_CHALLENGE, "2026-10-08", "Session A", slot = "A"), item(WeeklyPlanner.KIND_ADDON, "2026-10-08", "Legs add-on", type = "LEGS", est = 14.0))),
            day("2026-10-09", "free", listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-10-09", "Legs + push + rear delts", type = "FULL"))),
            day("2026-10-10", "blocked", reason = ReasonCode("REST_BEFORE", sortedMapOf("slot" to "TEST", "when" to "2026-10-11T11:00:00Z"))),
            day("2026-10-11", "fixed", listOf(item(FixedItem.KIND_TEST, "2026-10-11", "Test day", slot = "TEST"))),
        ),
    )

    private val anchors = listOf(
        anchor("evA", "2026-10-08", AnchorKind.CHALLENGE_SESSION, slot = "A"),
        anchor("evT", "2026-10-11", AnchorKind.CHALLENGE_SESSION, slot = "TEST"),
    )
    private val instances = mapOf("evA" to instance("evA", "2026-10-08", "Gym — sessie A (één arm zwaar)"), "evT" to instance("evT", "2026-10-11", "Gym — testdag 1 (meten)"))
    private val adoptA = CalendarPrompt(enrollment, WritePrompt.Adopt("evA", 1, "2026-W41", "A", false))

    private fun rows(p: WeekPlan? = week, a: List<Anchor> = anchors, links: List<CalendarLink> = emptyList(), canPlan: Boolean = true, done: List<Pair<LocalDate, String>> = emptyList()) =
        UiWeekRows.rows(p, monday, a, instances, links, listOf(adoptA), done, today, now, zone, canPlan) { it }

    @Test
    fun `the W41 week reads as UX 10_2 sketches it`() {
        val r = rows()
        r[1].symbol shouldBe "v"
        r[1].title shouldBe "Session C (done)"
        r[2].symbol shouldBe "-"
        r[2].detail shouldBe "4 of 4 gym visits used; free for legs / push / core"
        r[3].symbol shouldBe "*"
        r[3].detail shouldBe "+ legs add-on ~14 min"
        r[3].events.single().app shouldBe false
        r[3].action shouldBe WeekAction.Adopt(adoptA)
        r[4].symbol shouldBe "o"
        r[4].title shouldBe "Legs + push + rear delts ~50 min"
        r[4].action.shouldBeInstanceOf<WeekAction.PlanIt>().item.type shouldBe "FULL"
        r[5].symbol shouldBe "x"
        r[5].detail shouldBe "Rest before test Sunday"
        r[6].symbol shouldBe "T"
    }

    @Test
    fun `Plan it is hidden without a calendar to write to, and past days offer nothing`() {
        rows(canPlan = false)[4].action.shouldBeNull()
        rows()[0].action.shouldBeNull()
    }

    @Test
    fun `a planned session the plan gives no sets reads as not recommended`() {
        val marker = Marker(rec, "gen/2026-W41/5")
        val planned = plan(week.days.map { d ->
            if (d.date != LocalDate.parse("2026-10-09")) d
            else d.copy(items = listOf(item(FixedItem.KIND_PLANNED_GYM, "2026-10-09", "Planned gym", type = "FULL", key = "evG")))
        })
        val a = anchors + anchor("evG", "2026-10-09", AnchorKind.PLANNED_GYM, marker = marker)
        val r = rows(planned, a)[4]
        r.title shouldBe "Planned, not recommended"
        r.notRecommended shouldBe true
        r.events.single().app shouldBe true
        r.events.single().note shouldBe "planned (not recommended)"
        r.action.shouldBeNull()
    }

    @Test
    fun `a planned session with sets shows its label and a moved app event says so`() {
        val marker = Marker(rec, "gen/2026-W41/5")
        val planned = plan(week.days.map { d ->
            if (d.date != LocalDate.parse("2026-10-09")) d
            else d.copy(items = listOf(item(FixedItem.KIND_PLANNED_GYM, "2026-10-09", "Legs + push", type = "PUSH+LEGS", key = "evG", direct = mapOf("quads" to 3.0))))
        })
        val a = anchors + anchor("evG", "2026-10-09", AnchorKind.PLANNED_GYM, marker = marker)
        val link = CalendarLink(rec, marker.slotKey, "general", 1, CalendarLink.USER_MOVED, "t", "h", at("2026-10-09", "19:00"), at("2026-10-09", "20:00"))
        val r = rows(planned, a, listOf(link))[4]
        r.symbol shouldBe "o"
        r.title shouldBe "Legs + push ~50 min"
        r.events.single().note shouldBe "Moved by you"
    }

    @Test
    fun `a past week lists its logged sessions`() {
        val done = listOf(LocalDate.parse("2026-10-06") to "Session C", LocalDate.parse("2026-10-09") to "Legs + push")
        val later = LocalDate.parse("2026-10-20")
        val r = UiWeekRows.rows(null, monday, emptyList(), emptyMap(), emptyList(), emptyList(), done, later, Week.atLocal(later, java.time.LocalTime.NOON, zone), zone, true) { it }
        r[1].title shouldBe "Session C (done)"
        r[4].title shouldBe "Legs + push (done)"
        r[2].title shouldBe "No session"
    }
}
