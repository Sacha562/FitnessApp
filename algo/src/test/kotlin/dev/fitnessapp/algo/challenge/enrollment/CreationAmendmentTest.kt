package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Values
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.TreeMap

/**
 * D48: a second `enrollment.created` that supersedes the first corrects the imported earlier tests and nothing else. The
 * stored D40 import lost every per-arm value (the 30 August hang, top hold and least assistance, the 29 September hang).
 */
class CreationAmendmentTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val def = OapFixture.def
    private val params = OapFixture.params()

    /** The earlier tests as the D40 setup stored them. */
    private val stored = listOf(
        PastTest(
            d("2026-08-30"),
            TreeMap(mapOf("bw" to Values.mass("71"), "wpu_3rm_added" to Values.mass("26"), "wpu_1rm_added" to Values.mass("32"), "bw_max_reps" to Values.int(18))),
            validForGate = true, notComparable = setOf("oa_hang_s"), protocolCodes = TreeMap(mapOf("oa_hang_s" to "oa_hang_bar_supinated_release_v0")),
        ),
        PastTest(d("2026-09-19"), TreeMap(mapOf("bw" to Values.mass("70.7"), "wpu_3rm_added" to Values.mass("32.5"))), validForGate = true),
        PastTest(d("2026-09-29"), TreeMap(), validForGate = true),
    )

    /** The same tests with the values of OAP_CHALLENGE 11.2 and the cutover draft. */
    private val corrected = listOf(
        stored[0].copy(
            metrics = TreeMap(
                stored[0].metrics + mapOf(
                    "oa_hang_s" to SideV(Values.duration("3"), Values.duration("8")),
                    "oa_top_hold_s" to SideV(Values.duration("0.5"), Values.duration("1")),
                    "oa_least_assist_pin" to SideV(Values.pin("35"), Values.pin("35")),
                ),
            ),
        ),
        stored[1],
        stored[2].copy(metrics = TreeMap(mapOf("oa_hang_s" to SideV(Values.duration("15"), Values.duration("20"))))),
    )

    private val imp = OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false).copy(pastTests = stored)
    private val created = OapFixture.created(imp)
    private val base = listOf(created) + OapFixture.decisions(true)

    private fun amendment(name: String, change: (EventPayload.Created) -> EventPayload.Created, supersedes: ChallengeEvent = created) =
        OapFixture.event(EventTypes.CREATED, "2026-10-25", "22:00", change(created.payload as EventPayload.Created), name = name)
            .copy(supersedesEventId = supersedes.id)

    private fun withTests(tests: List<PastTest>): (EventPayload.Created) -> EventPayload.Created = { p -> p.copy(import = p.import!!.copy(pastTests = tests)) }

    @Test
    fun `the correction reaches the baseline and leaves the vars alone`() {
        val before = EnrollmentFold.fold(def, params, base)
        val after = EnrollmentFold.fold(def, params, base + amendment("fix", withTests(corrected)))
        after.warnings.filter { it.startsWith("creation_amendment") }.shouldBeEmpty()
        val b = after.state.testBaseline
        b.getValue("oa_hang_s").let { it.value to it.date } shouldBe (SideV(Values.duration("15"), Values.duration("20")) to d("2026-09-29"))
        b.getValue("oa_top_hold_s").let { it.value to it.date } shouldBe (SideV(Values.duration("0.5"), Values.duration("1")) to d("2026-08-30"))
        b.getValue("oa_least_assist_pin").value shouldBe SideV(Values.pin("35"), Values.pin("35"))
        b.getValue("bw").date shouldBe d("2026-09-19")
        after.state.lastValidTest!!.metrics["oa_hang_s"] shouldBe SideV(Values.duration("15"), Values.duration("20"))
        after.state.validTestCount shouldBe 3
        after.state.vars shouldBe before.state.vars
        after.state.mode shouldBe before.state.mode
        // The original keeps its place in the trail; the correction is not folded as an event of its own.
        after.trail.first().first.id shouldBe created.id
        after.trail.size shouldBe before.trail.size
    }

    @Test
    fun `a correction read back from its stored JSON is still accepted`() {
        fun roundTrip(e: ChallengeEvent) =
            EventCodec.decode(def, e.id, e.type, e.occurredAt, e.localDate, EventCodec.encode(def, e), e.supersedesEventId).copy(engineFacts = e.engineFacts)
        val stored = roundTrip(created)
        val fix = roundTrip(amendment("fix", withTests(corrected), supersedes = stored))
        val after = EnrollmentFold.fold(def, params, listOf(stored) + OapFixture.decisions(true) + fix)
        after.warnings.filter { it.startsWith("creation_amendment") }.shouldBeEmpty()
        after.state.testBaseline.keys.containsAll(listOf("oa_hang_s", "oa_top_hold_s", "oa_least_assist_pin")) shouldBe true
    }

    @Test
    fun `a correction that changes anything besides the tests is ignored`() {
        val before = EnrollmentFold.fold(def, params, base)
        val load: (EventPayload.Created) -> EventPayload.Created = { p ->
            withTests(corrected)(p).let { q -> q.copy(import = q.import!!.copy(vars = TreeMap(q.import!!.vars + ("wpuB" to Values.mass("30"))))) }
        }
        val fix = amendment("load", load)
        val after = EnrollmentFold.fold(def, params, base + fix)
        after.warnings shouldBe before.warnings + "creation_amendment_ignored:${fix.id}"
        after.state shouldBe before.state
    }

    @Test
    fun `the original is the creation that supersedes nothing, in any order`() {
        val events = base + amendment("fix", withTests(corrected))
        val sorted = EnrollmentFold.fold(def, params, events).state
        EnrollmentFold.fold(mapOf(def.version to def), params, events.reversed()).state shouldBe sorted
    }

    @Test
    fun `the newest correction wins`() {
        val first = amendment("fix", withTests(corrected))
        val second = OapFixture.event(EventTypes.CREATED, "2026-10-26", "09:00", withTests(stored)(created.payload as EventPayload.Created), name = "fix2")
            .copy(supersedesEventId = first.id)
        val state = EnrollmentFold.fold(def, params, base + first + second).state
        state shouldBe EnrollmentFold.fold(def, params, base).state
    }
}
