package dev.fitnessapp.algo.planning.weekplan

import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.WriteOp
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.Marker
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "Plan it" (ALGORITHMS_PLANNING 8.9, D82): the event's content and the updates of an unedited planned event. */
class PlanItTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val human = "Planned by FitnessApp. If you move this event, the app keeps your new time."
    private val rec = "0190a1f0-4141-7000-8000-000000000041"
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private val windowStart = Instant.parse("2026-09-28T00:00:00Z")
    private val windowEnd = Instant.parse("2026-10-19T00:00:00Z")

    private val item = PlanItem(
        "gen:2026-10-09", WeeklyPlanner.KIND_RECOMMENDED, null, "FULL", "Legs + push + rear delts",
        Instant.parse("2026-10-09T17:45:00Z"), Instant.parse("2026-10-09T19:00:00Z"), 49.6, writePolicy = "on_plan_it", source = "weekplan",
    )
    private val event = PlanIt.event(rec, item, zone, listOf("Leg press · 3 sets", "Chest press · 2 sets"), 5.0, human)

    private fun instance(e: PlanIt.Event, title: String = e.title, description: String = e.description, start: Instant = e.start, end: Instant = e.end, id: Long = 7) =
        CalendarInstance("key$id", "series$id", id, 1, "primary", true, start, end, false, title, description, null, "confirmed", false, false)

    private fun input(instances: List<CalendarInstance>, links: List<CalendarLink>, desired: Map<String, PlanIt.Desired> = emptyMap()) =
        PlanIt.UpdateInput(now, windowStart, windowEnd, links, instances, desired, human)

    @Test
    fun `the event is the label, the planner's start and the estimate plus changeover on the 5-minute grid`() {
        event.title shouldBe "Gym — Full body" // D84: titled by the session type, which reads back as FULL
        event.start shouldBe Instant.parse("2026-10-09T17:45:00Z")
        event.end shouldBe Instant.parse("2026-10-09T18:40:00Z") // 49.6 + 5 = 54.6 -> 55 min, budget 50 (W41_planItFri in 21.10)
        event.marker shouldBe Marker(rec, "gen/2026-W41/5")
        event.description.lines().last() shouldBe "[fitapp:v1 $rec gen/2026-W41/5]"
        event.description.lines().first() shouldBe "Leg press · 3 sets"
        Marker.ofDescription(event.description) shouldBe event.marker
    }

    @Test
    fun `an estimate on the grid stays on it and the slot key names the day`() {
        PlanIt.minutes(45.0, 5.0) shouldBe 50
        PlanIt.minutes(45.01, 5.0) shouldBe 55
        PlanIt.minutes(0.0, 0.0) shouldBe 5
        PlanIt.slotKey(LocalDate.parse("2026-10-09")) shouldBe "gen/2026-W41/5"
        PlanIt.dateOf("gen/2026-W41/5") shouldBe LocalDate.parse("2026-10-09")
        PlanIt.dateOf("gen/2026-W53/7") shouldBe LocalDate.parse("2027-01-03")
        PlanIt.dateOf("oap/C/2026-W41").shouldBeNull()
        PlanIt.line("Plank", 1) shouldBe "Plank · 1 set"
        listOf("LEGS", "PUSH", "PULL", "PUSH+LEGS", "PULL+LEGS", "UPPER", "FULL", "CORE").map { PlanIt.typeName(it) } shouldBe
            listOf("Legs", "Push", "Pull", "Legs + push", "Pull + legs", "Upper", "Full body", "Core")
    }

    @Test
    fun `an unedited event follows the plan's title and lines but keeps its time`() {
        val link = PlanIt.link(event, 7)
        val want = PlanIt.Desired("Gym — Legs + push", "Leg press · 4 sets")
        val plan = PlanIt.updates(input(listOf(instance(event)), listOf(link), mapOf(event.marker.render() to want)))
        plan.ops shouldHaveSize 1
        val op = plan.ops.single().shouldBeInstanceOf<WriteOp.Update>()
        op.title shouldBe "Gym — Legs + push"
        op.description shouldBe "Leg press · 4 sets\n\n$human\n${event.marker.render()}"
        op.start.shouldBeNull()
        op.end.shouldBeNull()
        plan.linkChanges.shouldBeEmpty()

        // Applied, the same inputs give no operation (idempotent) and the link is not "edited by the user".
        val after = PlanIt.linksAfter(listOf(link), plan)
        val updated = instance(event, title = op.title!!, description = op.description!!)
        val again = PlanIt.updates(input(listOf(updated), after, mapOf(event.marker.render() to want)))
        again.ops.shouldBeEmpty()
        again.linkChanges.shouldBeEmpty()
    }

    @Test
    fun `a moved event is still updated, never moved back`() {
        val link = PlanIt.link(event, 7)
        val moved = instance(event, start = event.start.plusSeconds(3600), end = event.end.plusSeconds(3600))
        val plan = PlanIt.updates(input(listOf(moved), listOf(link), mapOf(event.marker.render() to PlanIt.Desired("Gym — Push", ""))))
        plan.linkChanges.single().link.state shouldBe CalendarLink.USER_MOVED
        val op = plan.ops.single().shouldBeInstanceOf<WriteOp.Update>()
        op.title shouldBe "Gym — Push"
        op.start.shouldBeNull()
    }

    @Test
    fun `an edited title stops the updates`() {
        val link = PlanIt.link(event, 7)
        val edited = instance(event, title = "Gym with Sam")
        val plan = PlanIt.updates(input(listOf(edited), listOf(link), mapOf(event.marker.render() to PlanIt.Desired("Gym — Push", ""))))
        plan.linkChanges.single().link.state shouldBe CalendarLink.USER_EDITED
        plan.ops.shouldBeEmpty()
    }

    @Test
    fun `a deleted event is user_deleted and a removed marker releases it`() {
        val link = PlanIt.link(event, 7)
        PlanIt.updates(input(emptyList(), listOf(link))).linkChanges.single().link.state shouldBe CalendarLink.USER_DELETED
        val unmarked = instance(event, description = "Leg press · 3 sets")
        PlanIt.updates(input(listOf(unmarked), listOf(link))).linkChanges.single().link.state shouldBe CalendarLink.RELEASED
    }

    @Test
    fun `a deleted link whose marked event is back is the app's again`() {
        val gone = PlanIt.link(event, 7).copy(state = CalendarLink.DELETED_BY_APP)
        val want = mapOf(event.marker.render() to PlanIt.Desired("Gym — Push", ""))
        // Back unchanged: active, and the plan's updates apply again.
        val back = PlanIt.updates(input(listOf(instance(event, id = 9)), listOf(gone), want))
        back.linkChanges.single().link.state shouldBe CalendarLink.ACTIVE
        back.linkChanges.single().link.eventId shouldBe 9L
        back.ops.single().shouldBeInstanceOf<WriteOp.Update>().eventId shouldBe 9L
        // Back at another time: user_moved; with another title: user_edited and left alone.
        val moved = instance(event, start = event.start.plusSeconds(1800), end = event.end.plusSeconds(1800))
        PlanIt.updates(input(listOf(moved), listOf(gone.copy(state = CalendarLink.USER_DELETED)))).linkChanges.single().link.state shouldBe CalendarLink.USER_MOVED
        val edited = PlanIt.updates(input(listOf(instance(event, title = "Gym with Sam")), listOf(gone), want))
        edited.linkChanges.single().link.state shouldBe CalendarLink.USER_EDITED
        edited.ops.shouldBeEmpty()
        // Still gone: unchanged.
        PlanIt.updates(input(emptyList(), listOf(gone))).linkChanges.shouldBeEmpty()
    }

    @Test
    fun `a past event and one without a wish are left alone, and a lost link is rebuilt`() {
        val link = PlanIt.link(event, 7)
        val late = PlanIt.UpdateInput(event.start.plusSeconds(60), windowStart, windowEnd, listOf(link), listOf(instance(event)), mapOf(event.marker.render() to PlanIt.Desired("Gym — Push", "")), human)
        PlanIt.updates(late).ops.shouldBeEmpty()
        PlanIt.updates(input(listOf(instance(event)), listOf(link))).ops.shouldBeEmpty()
        val rebuilt = PlanIt.updates(input(listOf(instance(event)), emptyList())).linkChanges.single().link
        rebuilt.kind shouldBe PlanIt.KIND
        rebuilt.state shouldBe CalendarLink.ACTIVE
        rebuilt.markerUuid shouldBe rec
    }
}
