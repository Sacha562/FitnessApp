package dev.fitnessapp.algo.challenge.calendar

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.enrollment.BlockPos
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.calendar.CalendarClassifier
import dev.fitnessapp.algo.core.calendar.CalendarFixtures
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.ENROLLMENT
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.fCal41
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.CalendarKeys
import dev.fitnessapp.algo.core.calendar.ClassifyContext
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.clock.Week
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** GF-CE-25 (adopt offers), 26 (W42 inserts), 28 (postponed test prompts) and the ANDROID 16.7 edit policy, on a fake calendar. */
class WritePlannerTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val zone = CalendarFixtures.zone
    private val human = "Planned by FitnessApp. If you move this event, the app keeps your new time."
    private val windowStart = CalendarFixtures.at("2026-09-28", "00:00")
    private val windowEnd = CalendarFixtures.at("2026-10-26", "00:00")

    /** A device calendar that applies write plans like the executor does. */
    private class FakeCalendar(var events: List<CalendarInstance>, var links: List<CalendarLink> = emptyList()) {
        private var nextId = 1000L

        fun apply(plan: WritePlan) {
            val inserted = sortedMapOf<String, Long>()
            for (op in plan.ops) {
                when (op) {
                    is WriteOp.Insert -> {
                        val id = nextId++
                        inserted[op.marker.slotKey] = id
                        events = events + CalendarInstance(
                            CalendarKeys.instanceKey("com.google", "me", "cal1", "ev$id", op.event.start.toEpochMilli()),
                            CalendarKeys.seriesKey("com.google", "me", "cal1", "ev$id"), id, CalendarFixtures.PRIMARY, "me", true,
                            op.event.start, op.event.end, false, op.event.title, op.description, op.event.location, "confirmed", false, false,
                        )
                    }
                    is WriteOp.Update -> events = events.map { e ->
                        if (e.eventId != op.eventId || Marker.ofDescription(e.description) != op.guard) e
                        else e.copy(
                            title = op.title ?: e.title, description = op.description ?: e.description,
                            beginUtc = op.start ?: e.beginUtc, endUtc = op.end ?: e.endUtc,
                        )
                    }
                    is WriteOp.Delete -> events = events.filter { it.eventId != op.eventId }
                }
            }
            links = WritePlanner.linksAfter(links, plan, ENROLLMENT, "TEST", inserted)
        }

        fun edit(id: Long, f: (CalendarInstance) -> CalendarInstance) {
            events = events.map { if (it.eventId == id) f(it) else it }
        }
    }

    private fun normalBlock2(): EnrollmentState =
        OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 1, plannedTest = null, needsCleanSession = false))
            .copy(block = BlockPos(2, 1, LocalDate.parse("2026-10-12")))

    private fun planInput(cal: FakeCalendar, state: EnrollmentState, weekStart: String, now: Instant): WritePlanInput {
        val starts = listOf(LocalDate.parse(weekStart))
        val view = EnrollmentViews.of(ENROLLMENT, def, params, state, OapFixture.facts, starts, CalendarFixtures.gymHours)
        val ctx = ClassifyContext(
            now, zone, setOf(CalendarFixtures.PRIMARY), listOf("(?i)todoist"), true, listOf("basic-fit"), listOf(view), emptyMap(), emptyList(),
        )
        val cls = CalendarClassifier.classify(cal.events, ctx, params, SlotMatcher.matching(params, zone))
        val desired = starts.flatMap { ws ->
            val matched = cls.anchors.filter { it.kind.code == "challenge_session" && it.enrollmentId == ENROLLMENT && Week.isoWeekStart(it.localDate) == ws }
                .map { dev.fitnessapp.algo.challenge.plan.SlotAnchor(it.slot!!, dev.fitnessapp.algo.challenge.plan.Interval(it.beginUtc, it.endUtc), if (it.marker != null) "calendar_marked" else "calendar_unmarked", it.instanceKey, it.attention != null) }
            val plan = WeekPlanner.plan(def, params, PlanInput(now, zone, state, OapFixture.facts, ws, matched = matched))
            WritePlanner.desired(def, plan, "nl", state.block.index, "Basic-Fit") { "${it.slot} lines" }
        }
        return WritePlanInput(
            ENROLLMENT, "oap", "TEST", now, zone, windowStart, windowEnd, starts.map { Week.isoWeek(it) }, desired, emptySet(),
            cal.events, cls.anchors, cal.links, human,
        )
    }

    @Test
    fun `GF-CE-26 W42 gets three inserts with Dutch titles and markers`() {
        val cal = FakeCalendar(emptyList())
        val now = CalendarFixtures.at("2026-10-12", "10:00")
        val plan = WritePlanner.plan(planInput(cal, normalBlock2(), "2026-10-12", now))
        val inserts = plan.ops.filterIsInstance<WriteOp.Insert>()
        inserts.map { "${it.event.title} ${it.event.start} ${it.marker.slotKey}" } shouldBe listOf(
            "Gym — sessie C (volume en techniek) 2026-10-14T17:45:00Z oap/C/2026-W42",
            "Gym — sessie A (één arm zwaar) 2026-10-16T17:45:00Z oap/A/2026-W42",
            "Gym — sessie B (maximale kracht) 2026-10-18T09:00:00Z oap/B/2026-W42",
        )
        inserts.first().description shouldBe "C lines\n\n$human\n[fitapp:v1 $ENROLLMENT oap/C/2026-W42]"
        inserts.first().event.end shouldBe CalendarFixtures.at("2026-10-14", "21:15")
        // Idempotence: once applied, the same inputs give nothing to do.
        cal.apply(plan)
        val again = WritePlanner.plan(planInput(cal, normalBlock2(), "2026-10-12", now))
        again.ops shouldBe emptyList()
        again.prompts shouldBe emptyList()
        cal.links.map { it.state } shouldBe listOf("active", "active", "active")
    }

    @Test
    fun `GF-CE-25 unmarked W41 events get no inserts and adopt offers for future ones`() {
        val cal = FakeCalendar(fCal41)
        val plan = WritePlanner.plan(planInput(cal, OapFixture.start(OapFixture.import()), "2026-10-05", CalendarFixtures.now))
        plan.ops shouldBe emptyList()
        plan.prompts.map { (it as WritePrompt.Adopt).slot } shouldBe listOf("A", "TEST")
    }

    @Test
    fun `GF-CE-28 an unmarked testdag in a postponed week gets an adopt-and-rename prompt, never an edit`() {
        val cal = FakeCalendar(fCal41)
        val state = OapFixture.start(OapFixture.import()).let {
            it.copy(plannedTest = it.plannedTest.copy(date = LocalDate.parse("2026-10-18"), basis = "postponed"))
        }
        val plan = WritePlanner.plan(planInput(cal, state, "2026-10-05", CalendarFixtures.now))
        plan.ops shouldBe emptyList()
        val adopt = plan.prompts.filterIsInstance<WritePrompt.Adopt>().single { it.slot == "B" }
        adopt.mismatch shouldBe true
    }

    @Test
    fun `a marked TEST event in a postponed week is rewritten to B with its marker`() {
        val cal = FakeCalendar(emptyList())
        val now = CalendarFixtures.at("2026-10-05", "08:00")
        val pending = OapFixture.start(OapFixture.import())
        cal.apply(WritePlanner.plan(planInput(cal, pending, "2026-10-05", now)))
        val test = cal.events.single { it.title!!.contains("testdag") }
        val postponed = pending.copy(plannedTest = pending.plannedTest.copy(date = LocalDate.parse("2026-10-18"), basis = "postponed"))
        val plan = WritePlanner.plan(planInput(cal, postponed, "2026-10-05", now))
        val up = plan.ops.single() as WriteOp.Update
        up.eventId shouldBe test.eventId
        up.guard.slotKey shouldBe "oap/TEST/2026-W41"
        up.marker.slotKey shouldBe "oap/B/2026-W41"
        up.title shouldBe "Gym — sessie B (maximale kracht)"
        up.start shouldBe null
        cal.apply(plan)
        WritePlanner.plan(planInput(cal, postponed, "2026-10-05", now)).ops shouldBe emptyList()
        cal.links.single { it.slotKey == "oap/B/2026-W41" }.state shouldBe "active"
    }

    @Test
    fun `edit policy - moved, edited, released and deleted events`() {
        val cal = FakeCalendar(emptyList())
        val now = CalendarFixtures.at("2026-10-12", "10:00")
        val state = normalBlock2()
        cal.apply(WritePlanner.plan(planInput(cal, state, "2026-10-12", now)))
        val (c, a, b) = cal.events.sortedBy { it.beginUtc }
        // The user moves C an hour later, edits A's title, removes B's marker.
        cal.edit(c.eventId) { it.copy(beginUtc = it.beginUtc.plusSeconds(3600), endUtc = it.endUtc.plusSeconds(3600)) }
        cal.edit(a.eventId) { it.copy(title = "Gym A with Sam") }
        cal.edit(b.eventId) { it.copy(description = "my own text") }
        val plan = WritePlanner.plan(planInput(cal, state, "2026-10-12", now))
        plan.linkChanges.associate { it.link.slotKey to it.link.state } shouldBe mapOf(
            "oap/A/2026-W42" to "user_edited", "oap/B/2026-W42" to "released", "oap/C/2026-W42" to "user_moved",
        )
        plan.ops shouldBe emptyList()
        cal.apply(plan)
        // Now the user deletes C: respected, offered back.
        cal.events = cal.events.filter { it.eventId != c.eventId }
        val after = WritePlanner.plan(planInput(cal, state, "2026-10-12", now))
        after.linkChanges.single().link.state shouldBe "user_deleted"
        cal.apply(after)
        val last = WritePlanner.plan(planInput(cal, state, "2026-10-12", now))
        last.ops shouldBe emptyList()
        last.prompts shouldBe listOf(WritePrompt.AddToCalendar("2026-W42", "C", "oap/C/2026-W42"))
    }

    @Test
    fun `a paused enrollment deletes its unmodified future events and asks about edited ones`() {
        val cal = FakeCalendar(emptyList())
        val now = CalendarFixtures.at("2026-10-12", "10:00")
        val state = normalBlock2()
        cal.apply(WritePlanner.plan(planInput(cal, state, "2026-10-12", now)))
        val a = cal.events.sortedBy { it.beginUtc }[1]
        cal.edit(a.eventId) { it.copy(title = "Gym A with Sam") }
        val paused = state.copy(status = "paused")
        val plan = WritePlanner.plan(planInput(cal, paused, "2026-10-12", now))
        plan.ops.map { (it as WriteOp.Delete).guard.slotKey } shouldBe listOf("oap/C/2026-W42", "oap/B/2026-W42")
        plan.prompts shouldBe listOf(WritePrompt.Remove(a.instanceKey, a.eventId, "oap/A/2026-W42"))
    }
}
