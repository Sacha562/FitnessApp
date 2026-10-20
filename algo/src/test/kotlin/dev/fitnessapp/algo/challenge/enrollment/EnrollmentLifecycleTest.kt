package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.eca.GateCriteria
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 4.5 status transitions, 4.11 amendments and retractions, and the phase-3 gate on attempts (P16). */
class EnrollmentLifecycleTest {
    private val def = OapFixture.def
    private val params = OapFixture.params()

    private fun apply(s: EnrollmentState, e: ChallengeEvent) = EnrollmentFold.apply(def, params, s, e)

    private fun attempt(side: String, success: Boolean, time: String) =
        OapFixture.event(EventTypes.ATTEMPT, "2026-10-14", time, EventPayload.AttemptLogged("oa_rep", side, success))

    private val elbowPain = EventPayload.Pain("elbow_medial", "R", listOf("medial_elbow_ache_after"), "mild", 2)

    @Test
    fun `completed and abandoned are final`() {
        val s = OapFixture.start()
        val abandoned = apply(s, OapFixture.event(EventTypes.ABANDON, "2026-10-08", "10:00", EventPayload.Abandon(null))).state
        abandoned.status shouldBe EnrollmentState.ABANDONED
        val late = apply(abandoned, OapFixture.event(EventTypes.RESUME, "2026-10-09", "10:00", EventPayload.Resume(null, 1)))
        late.state.status shouldBe EnrollmentState.ABANDONED
        late.warnings shouldBe listOf("ignored:resume:abandoned")
        val done = s.copy(status = EnrollmentState.COMPLETED)
        apply(done, OapFixture.event(EventTypes.PAUSE, "2026-10-09", "10:00", EventPayload.Pause(null))).state.status shouldBe EnrollmentState.COMPLETED
        apply(done, OapFixture.event(EventTypes.ABANDON, "2026-10-09", "11:00", EventPayload.Abandon(null))).state.status shouldBe EnrollmentState.COMPLETED
        // Resume while active changes nothing.
        apply(s, OapFixture.event(EventTypes.RESUME, "2026-10-09", "10:00", EventPayload.Resume(null, 1))).state.status shouldBe EnrollmentState.ACTIVE
    }

    @Test
    fun `P16 approved - one right-arm rep completes the challenge`() {
        val s = OapFixture.start().copy(phase = 3)
        val failLeft = apply(s, attempt("L", false, "20:00")).state
        failLeft.status shouldBe EnrollmentState.ACTIVE
        failLeft.phaseObservations["oa_rep"] shouldBe SideV(BoolV.FALSE, BoolV.FALSE)
        val right = apply(failLeft, attempt("R", true, "20:05")).state
        right.status shouldBe EnrollmentState.COMPLETED
        // Directly after a right-arm success too.
        apply(s, attempt("R", true, "20:00")).state.status shouldBe EnrollmentState.COMPLETED
        GateCriteria.criteria(def, params, s.copy(phaseObservations = sortedMapOf("oa_rep" to SideV(BoolV.FALSE, BoolV.TRUE))), OapFixture.facts)
            .map { it.met } shouldBe listOf(true)
    }

    @Test
    fun `P16 declined - both arms are needed`() {
        val s = OapFixture.start(except = mapOf("P16" to false)).copy(phase = 3)
        val right = apply(s, attempt("R", true, "20:00")).state
        right.status shouldBe EnrollmentState.ACTIVE
        apply(right, attempt("L", true, "20:05")).state.status shouldBe EnrollmentState.COMPLETED
    }

    @Test
    fun `a retracted pain report is folded as if it never happened`() {
        val created = OapFixture.created(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        val decisions = OapFixture.decisions(true)
        val pain = OapFixture.event(EventTypes.PAIN, "2026-10-08", "20:00", elbowPain, name = "pain")
        EnrollmentFold.fold(def, params, listOf(created) + decisions + pain).state.mode shouldBe "injury"
        val retraction = OapFixture.event(EventTypes.PAIN, "2026-10-08", "21:00", elbowPain.copy(retracted = true), name = "retract")
            .copy(supersedesEventId = pain.id)
        EnrollmentFold.fold(def, params, listOf(created) + decisions + pain + retraction).state.mode shouldBe "normal"
        // The incremental path refuses history changes.
        shouldThrow<IllegalArgumentException> { apply(OapFixture.start(), retraction) }
    }

    @Test
    fun `an amendment cycle or an orphan amendment does not hang the fold`() {
        val created = OapFixture.created()
        val a = OapFixture.event(EventTypes.PAIN, "2026-10-08", "20:00", elbowPain, name = "a")
        val b = OapFixture.event(EventTypes.PAIN, "2026-10-08", "21:00", elbowPain, name = "b")
        val cycle = listOf(a.copy(supersedesEventId = b.id), b.copy(supersedesEventId = a.id))
        EnrollmentFold.fold(def, params, listOf(created) + cycle).state.mode shouldBe "test_pending"
        val orphan = b.copy(supersedesEventId = OapFixture.id("missing"))
        EnrollmentFold.fold(def, params, listOf(created, orphan)).warnings shouldBe listOf("orphan_amendment:${orphan.id}")
    }
}
