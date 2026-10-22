package dev.fitnessapp.algo.challenge.calendar

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarClassification
import dev.fitnessapp.algo.core.calendar.CalendarClassifier
import dev.fitnessapp.algo.core.calendar.CalendarFixtures
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.ENROLLMENT
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.RECOMMENDATION
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.fCal41
import dev.fitnessapp.algo.core.calendar.CalendarFixtures.inst
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.CalendarOverride
import dev.fitnessapp.algo.core.calendar.ClassifyContext
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.calendar.RunIntensity
import dev.fitnessapp.algo.core.calendar.SlotMatching
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** ALGORITHMS_PLANNING 1.7 (`calendar.classify`) and GF-CE-25 (`challenge.calendar.matchSlots`) on fixture F-CAL-41. */
class CalendarClassifyTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()
    private val zone = CalendarFixtures.zone

    private fun view(state: EnrollmentState = OapFixture.start(OapFixture.import())) = EnrollmentViews.of(
        ENROLLMENT, def, params, state, OapFixture.facts,
        listOf(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-12")), CalendarFixtures.gymHours,
    )

    private fun ctx(
        enrollments: Boolean = true,
        overrides: Map<String, CalendarOverride> = emptyMap(),
        now: Instant = CalendarFixtures.now,
    ) = ClassifyContext(
        now = now, zone = zone, allowlist = setOf(CalendarFixtures.PRIMARY, CalendarFixtures.TODOIST),
        ignoreCalendarNamePatterns = listOf("(?i)todoist"), ignoreAllDay = true,
        gymLocationKeywords = listOf("basic-fit", "basicfit", "basic fit", "ierse pond"),
        enrollments = if (enrollments) listOf(view()) else emptyList(), overrides = overrides,
        catalogNames = listOf("Weighted pull-up", "Lat pulldown", "Plank", "Optrekken met gewicht"),
    )

    private val matcher: SlotMatching = SlotMatcher.matching(params, zone)

    private fun classify(instances: List<CalendarInstance>, c: ClassifyContext = ctx()): CalendarClassification =
        CalendarClassifier.classify(instances, c, params, matcher)

    private fun CalendarClassification.byId(id: Long) = anchors.single { it.eventId == id }

    @Test
    fun `GF-CE-25 and classifiesRealWeek41`() {
        val out = classify(fCal41)
        out.status shouldBe CalendarClassification.OK
        val c = out.byId(10)
        c.kind shouldBe AnchorKind.CHALLENGE_SESSION
        c.slot shouldBe "C"
        c.method shouldBe MatchMethod.TITLE_PATTERN
        // 0.6 pattern + 0.2 gym word + 0.1 duration + 0.05 gym hours (Tuesday is not C's preferred day).
        c.matchScore shouldBe 0.95
        out.byId(11).slot shouldBe "A"
        out.byId(11).matchScore shouldBe 0.95
        val t = out.byId(12)
        t.slot shouldBe "TEST"
        t.matchScore shouldBe 1.0
        t.confidence shouldBe "high"
        out.byId(13).ignoredReason shouldBe "todoist"
        out.byId(14).kind shouldBe AnchorKind.BUSY
        out.byId(15).kind shouldBe AnchorKind.RUN
        out.byId(16).runIntensity shouldBe RunIntensity.INTERVALS
        out.byId(17).ignoredReason shouldBe "declined"
        out.byId(18).ignoredReason shouldBe "cancelled"
        out.byId(19).kind shouldBe AnchorKind.BUSY
    }

    @Test
    fun runIsNeverRejected() {
        val out = classify(
            listOf(
                inst(1, "Hardlopen", "2026-10-09", "07:00", "07:45"),
                inst(2, "Hardlopen intervallen", "2026-10-10", "07:00", "07:50"),
                inst(3, "Lange duurloop", "2026-10-11", "08:00", "09:30"),
            ),
        )
        out.byId(1).run { kind shouldBe AnchorKind.RUN; runIntensity shouldBe RunIntensity.EASY; runMinutes shouldBe 45 }
        out.byId(2).runIntensity shouldBe RunIntensity.INTERVALS
        out.byId(3).run { runIntensity shouldBe RunIntensity.EASY; runLong shouldBe true; runMinutes shouldBe 90 }
    }

    @Test
    fun chessIsBusy() {
        val a = classify(listOf(inst(1, "Training chess", "2026-10-09", "19:00", "20:30"))).byId(1)
        a.kind shouldBe AnchorKind.BUSY
        a.score shouldBe -1
    }

    @Test
    fun bareSessieNeedsConfirm() {
        // "sessie C" alone: 2 (challenge title) + 1 (duration) = 3, a gym candidate; C is already taken by the Tuesday event.
        val out = classify(fCal41 + inst(30, "sessie C", "2026-10-09", "18:00", "19:30"))
        val a = out.byId(30)
        a.kind shouldBe AnchorKind.PLANNED_GYM
        a.gymType shouldBe CalendarClassifier.AUTO
        a.unmatchedChallengeCandidate shouldBe true
        a.needsConfirm shouldBe true
    }

    @Test
    fun markerWins() {
        val desc = "Weighted pull-ups\n\nPlanned by FitnessApp.\n[fitapp:v1 $ENROLLMENT oap/A/2026-W41]"
        val a = classify(listOf(inst(1, "Something else", "2026-10-09", "19:45", "21:15", description = desc))).byId(1)
        a.kind shouldBe AnchorKind.CHALLENGE_SESSION
        a.slot shouldBe "A"
        a.method shouldBe MatchMethod.MARKER
        a.features shouldBe emptyList()
    }

    @Test
    fun generalMarker() {
        val desc = "[fitapp:v1 $RECOMMENDATION gen/2026-W41/5]"
        val a = classify(listOf(inst(1, "Gym — Legs + Push", "2026-10-09", "19:45", "21:00", description = desc))).byId(1)
        a.kind shouldBe AnchorKind.PLANNED_GYM
        a.marker!!.slotKey shouldBe "gen/2026-W41/5"
        a.gymType shouldBe "PUSH+LEGS"
    }

    @Test
    fun dedupeTwoGymEvents() {
        val out = classify(listOf(inst(1, "Gym", "2026-10-09", "19:45", "21:00"), inst(2, "Gym legs", "2026-10-09", "20:00", "21:00")))
        val kept = out.anchors.filter { it.kind == AnchorKind.PLANNED_GYM }
        kept.size shouldBe 1
        out.anchors.single { it.kind == AnchorKind.IGNORED }.duplicateOf shouldBe kept.single().instanceKey
    }

    @Test
    fun midnightSpanAndDst() {
        val a = classify(listOf(inst(1, "Gym", "2026-10-10", "23:30", "00:30", endDate = "2026-10-11"))).byId(1)
        a.localDate shouldBe LocalDate.parse("2026-10-10")
        val b = CalendarClassifier.classify(
            listOf(inst(2, "Dentist", "2026-10-25", "02:30", "03:00").copy(beginUtc = Instant.parse("2026-10-25T01:30:00Z"), endUtc = Instant.parse("2026-10-25T02:00:00Z"))),
            ctx(), params, matcher,
        ).byId(2)
        b.localDate shouldBe LocalDate.parse("2026-10-25")
        b.startLocal shouldBe LocalTime.of(2, 30)
    }

    @Test
    fun permissionDenied() {
        val out = CalendarClassifier.unavailable(permission = true)
        out.status shouldBe CalendarClassification.UNAVAILABLE
        out.anchors shouldBe emptyList()
    }

    @Test
    fun linkWithin3h() {
        val anchors = classify(listOf(inst(1, "Gym", "2026-10-09", "19:45", "21:00"))).anchors
        CalendarClassifier.linkSession(CalendarFixtures.at("2026-10-09", "20:10"), null, anchors, zone, params)!!.eventId shouldBe 1L
        CalendarClassifier.linkSession(CalendarFixtures.at("2026-10-09", "22:45"), null, anchors, zone, params)!!.eventId shouldBe 1L
        CalendarClassifier.linkSession(CalendarFixtures.at("2026-10-09", "23:00"), null, anchors, zone, params) shouldBe null
    }

    @Test
    fun deterministicOrder() {
        val a = classify(fCal41)
        val b = classify(fCal41.reversed())
        val c = classify(fCal41.sortedBy { it.title })
        a shouldBe b
        a shouldBe c
    }

    @Test
    fun `overrides win and adopt still matches`() {
        val chess = fCal41.single { it.eventId == 14L }
        val tue = fCal41.single { it.eventId == 10L }
        val out = classify(
            fCal41,
            ctx(
                overrides = mapOf(
                    chess.seriesKey to CalendarOverride(AnchorKind.PLANNED_GYM.code, gymType = "LEGS"),
                    tue.instanceKey to CalendarOverride(CalendarOverride.ADOPT),
                ),
            ),
        )
        out.byId(14).run { kind shouldBe AnchorKind.PLANNED_GYM; gymType shouldBe "LEGS"; method shouldBe MatchMethod.MANUAL }
        out.byId(10).run { slot shouldBe "C"; method shouldBe MatchMethod.ADOPTED }
        val manual = classify(fCal41, ctx(overrides = mapOf(tue.instanceKey to CalendarOverride(AnchorKind.CHALLENGE_SESSION.code, slot = "A", enrollmentId = ENROLLMENT))))
        manual.byId(10).run { slot shouldBe "A"; method shouldBe MatchMethod.MANUAL }
        // The Thursday A event loses its slot to the manual correction and stays a gym session.
        manual.byId(11).kind shouldBe AnchorKind.PLANNED_GYM
    }

    @Test
    fun `GF-CE-28 a postponed test week matches the testdag event to B with a mismatch`() {
        // The test was postponed to a later week (needsCleanSession): W41's TEST slot becomes B (C36).
        val state = OapFixture.start(OapFixture.import()).let {
            it.copy(plannedTest = it.plannedTest.copy(date = LocalDate.parse("2026-10-18"), basis = "postponed"))
        }
        val c = ctx().copy(enrollments = listOf(view(state)))
        view(state).slotsPerWeek.getValue("2026-W41") shouldBe listOf("C", "A", "B")
        val t = classify(fCal41, c).byId(12)
        t.slot shouldBe "B"
        t.attention shouldBe SlotMatcher.SLOT_MISMATCH
        t.matchScore shouldBe 0.9
    }
}
