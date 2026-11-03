package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanDay
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.Visits
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** D83's Week grid (`ui.weekgrid`): the three looks, overlap lanes and the visible hours. */
class UiWeekGridTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val monday = LocalDate.parse("2026-11-02")
    private val today = LocalDate.parse("2026-11-03")
    private val now = Week.atLocal(today, LocalTime.of(12, 0), zone)

    private fun at(d: String, t: String): Instant = Week.atLocal(LocalDate.parse(d), LocalTime.parse(t), zone)

    private fun item(kind: String, d: String, from: String, to: String, label: String, slot: String? = null, type: String? = null, est: Double = 90.0) =
        PlanItem("$kind:$d", kind, slot, type, label, at(d, from), at(d, to), est, writePolicy = "never", source = "weekplan")

    private fun plan(vararg days: Pair<String, List<PlanItem>>) = WeekPlan(
        "2026-W45", "id", false, days.map { (d, items) -> PlanDay(LocalDate.parse(d), if (items.isEmpty()) "free" else "fixed", items, null) },
        Visits(0, 0, 0, 4), 0, emptyMap(), 0.0, emptyMap(), Band(0.0, 0.05, 1, "only"), emptyList(), emptyList(), 1, 1, false, emptyList(),
    )

    private fun busy(key: String, d: String, from: String, to: String, kind: AnchorKind = AnchorKind.BUSY, slot: String? = null) = Anchor(
        key, key, 1, 1, at(d, from), at(d, to), LocalDate.parse(d), LocalTime.parse(from), LocalTime.parse(to), kind, slot = slot,
        method = MatchMethod.SCORE, classifierVersion = "1.0.0",
    )

    private fun inst(a: Anchor, title: String) = a.instanceKey to CalendarInstance(a.instanceKey, a.instanceKey, 1, 1, "p", true, a.beginUtc, a.endUtc, false, title, null, null, "confirmed", false, false)

    @Test
    fun `gym sessions are gym blocks, suggestions are suggested, other events are other`() {
        val p = plan(
            "2026-11-04" to listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-04", "19:45", "21:15", "Session C", slot = "C"), item(WeeklyPlanner.KIND_ADDON, "2026-11-04", "20:45", "21:00", "Push add-on", type = "PUSH", est = 15.0)),
            "2026-11-05" to listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-05", "19:45", "21:15", "Legs + push", type = "PUSH+LEGS", est = 50.0)),
        )
        val chess = busy("chess", "2026-11-05", "20:00", "22:30")
        val g = UiWeekGrid.grid(p, monday, listOf(chess), mapOf(inst(chess, "Schaken")), emptyList(), today, now, zone)
        val wed = g.days[2].blocks.single()
        wed.look shouldBe BlockLook.GYM
        wed.short shouldBe "C"
        wed.sub shouldBe "+ push 15 min"
        val thu = g.days[3].blocks.associateBy { it.key }
        val sug = thu.getValue("recommended_general:2026-11-05")
        sug.look shouldBe BlockLook.SUGGESTED
        sug.endMin shouldBe 19 * 60 + 45 + 50
        sug.short shouldBe "L+P"
        sug.planIt.shouldNotBeNull()
        thu.getValue("chess").look shouldBe BlockLook.OTHER
        thu.getValue("chess").title shouldBe "Schaken"
        // Overlapping: two lanes.
        thu.values.map { it.lanes }.toSet() shouldBe setOf(2)
        thu.values.map { it.lane }.sorted() shouldBe listOf(0, 1)
    }

    @Test
    fun `a suggestion that has started cannot be moved, a later one can, with or without a calendar`() {
        val p = plan("2026-11-03" to listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-03", "09:00", "10:00", "Push", type = "PUSH", est = 45.0)))
        UiWeekGrid.grid(p, monday, emptyList(), emptyMap(), emptyList(), today, now, zone).days[1].blocks.single().let { it.planIt.shouldBeNull(); it.movable shouldBe false }
        val later = plan("2026-11-05" to listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-05", "19:45", "21:00", "Push", type = "PUSH", est = 45.0)))
        UiWeekGrid.grid(later, monday, emptyList(), emptyMap(), emptyList(), today, now, zone).days[3].blocks.single().movable shouldBe true
    }

    @Test
    fun `a moved suggestion shows on its new day and its old day reads as moved`() {
        val p = plan(
            "2026-11-05" to listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-05", "19:45", "21:00", "Push", type = "PUSH", est = 45.0)),
            "2026-11-07" to emptyList(),
        )
        val moved = UiWeekRows.withMoved(p, mapOf("recommended_general:2026-11-05" to at("2026-11-07", "10:00")), zone)
        moved.days.first { it.date == LocalDate.parse("2026-11-05") }.let { it.items.isEmpty() shouldBe true; it.restReason?.code shouldBe "MOVED" }
        val sat = moved.days.first { it.date == LocalDate.parse("2026-11-07") }.items.single()
        sat.start shouldBe at("2026-11-07", "10:00")
        sat.end shouldBe at("2026-11-07", "11:15")
        val g = UiWeekGrid.grid(moved, monday, emptyList(), emptyMap(), emptyList(), today, now, zone)
        g.days[5].blocks.single().let { it.look shouldBe BlockLook.SUGGESTED; it.startMin shouldBe 600 }
        UiWeekRows.withMoved(p, emptyMap(), zone) shouldBe p
    }

    @Test
    fun `a gym event the plan does not show is still gym, and a past week shows its logged sessions`() {
        val ev = busy("evC", "2026-11-04", "19:45", "21:15", AnchorKind.CHALLENGE_SESSION, slot = "C")
        val g = UiWeekGrid.grid(null, monday, listOf(ev), mapOf(inst(ev, "Gym — sessie C")), listOf(LoggedSession(LocalDate.parse("2026-11-02"), "Legs + push", at("2026-11-02", "18:00"), at("2026-11-02", "19:10"))), today, now, zone)
        g.days[2].blocks.single().look shouldBe BlockLook.GYM
        val mon = g.days[0].blocks.single()
        mon.done shouldBe true
        mon.short shouldBe "L+P"
        mon.endMin shouldBe 19 * 60 + 10
    }

    @Test
    fun `the hours cover the blocks, at least 08 to 21, and a block past midnight stops at 24`() {
        val early = busy("run", "2026-11-03", "06:30", "07:15", AnchorKind.RUN)
        val late = busy("out", "2026-11-06", "21:00", "23:00")
        val night = Anchor(
            "night", "night", 1, 1, at("2026-11-07", "22:00"), at("2026-11-08", "02:00"), LocalDate.parse("2026-11-07"), LocalTime.of(22, 0), LocalTime.of(2, 0),
            AnchorKind.BUSY, method = MatchMethod.SCORE, classifierVersion = "1.0.0",
        )
        val g = UiWeekGrid.grid(null, monday, listOf(early, late, night), emptyMap(), emptyList(), today, now, zone)
        g.startHour shouldBe 6
        g.endHour shouldBe 24
        g.days[5].blocks.single().endMin shouldBe 24 * 60
        UiWeekGrid.grid(null, monday, emptyList(), emptyMap(), emptyList(), today, now, zone).let { (it.startHour to it.endHour) shouldBe (8 to 21) }
    }

    @Test
    fun `only future app gym events and plannable suggestions can be dragged`() {
        val marker = dev.fitnessapp.algo.core.calendar.Marker("0190a1f0-0000-7000-8000-00000000e001", "oap/C/2026-W45")
        val p = plan(
            "2026-11-02" to listOf(item(FixedItem.KIND_DONE, "2026-11-02", "19:45", "21:15", "Session B", slot = "B")),
            "2026-11-04" to listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-04", "19:45", "21:15", "Session C", slot = "C")),
            "2026-11-05" to listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-05", "19:45", "21:15", "Push", type = "PUSH", est = 50.0)),
            "2026-11-06" to listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-06", "19:45", "21:15", "Session A", slot = "A")),
        )
        val c = busy("evC", "2026-11-04", "19:45", "21:15", AnchorKind.CHALLENGE_SESSION, slot = "C").copy(marker = marker)
        val a = busy("evA", "2026-11-06", "19:45", "21:15", AnchorKind.CHALLENGE_SESSION, slot = "A") // the user's own, unmarked
        val g = UiWeekGrid.grid(p, monday, listOf(c, a), emptyMap(), emptyList(), today, now, zone)
        g.days[0].blocks.single().movable shouldBe false // done
        g.days[2].blocks.single().let { it.movable shouldBe true; it.marker shouldBe marker; it.start shouldBe at("2026-11-04", "19:45") }
        g.days[3].blocks.single().movable shouldBe true // a future suggestion
        g.days[4].blocks.single().movable shouldBe false // unmarked: never moved by the app (D11)
    }

    @Test
    fun `lanes split only overlapping clusters`() {
        val a = GridBlock("a", monday, 600, 660, BlockLook.OTHER, "a", "a", "10:00", false, null, null)
        val b = a.copy(key = "b", startMin = 630, endMin = 700)
        val c = a.copy(key = "c", startMin = 720, endMin = 780)
        val out = UiWeekGrid.lanes(listOf(c, b, a)).associateBy { it.key }
        out.getValue("a").lanes shouldBe 2
        out.getValue("b").lane shouldBe 1
        out.getValue("c").lanes shouldBe 1
        out.getValue("c").lane shouldBe 0
        UiWeekGrid.lanes(emptyList()) shouldHaveSize 0
    }
}
