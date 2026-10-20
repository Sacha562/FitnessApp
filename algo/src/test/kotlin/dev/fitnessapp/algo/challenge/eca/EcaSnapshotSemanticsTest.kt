package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.OapFixture.bWpu
import dev.fitnessapp.algo.challenge.OapFixture.oaa
import dev.fitnessapp.algo.challenge.OapFixture.session
import dev.fitnessapp.algo.challenge.cds.Cds
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.fitexpr.QV
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** GF-CE-16 .. 20: snapshot semantics, locks, first writer, injury dominance, frozen modes, proposal gating. */
class EcaSnapshotSemanticsTest {
    private val params = OapFixture.params()

    /** The OAP definition plus synthetic rules (JSON objects). */
    private fun withRules(vararg rules: String): CompiledDefinition {
        val base = Json.parseToJsonElement(ChallengeTestSupport.oapText).jsonObject
        val list = base.getValue("rules").jsonArray + rules.map { Json.parseToJsonElement(it) }
        return Cds.load(JsonObject(LinkedHashMap(base).also { it["rules"] = JsonArray(list) }), ChallengeTestSupport.context).orThrow()
    }

    private fun apply(def: CompiledDefinition, s: EnrollmentState, e: dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent) =
        EnrollmentFold.apply(def, params, s, e)

    private fun rows(step: EnrollmentFold.Step, vararg ids: String) = step.audits.filter { it.ruleId in ids }

    @Test
    fun `GF-CE-16 a grip slip locks the weighted loads`() {
        val s = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        val b = session("B", "2026-10-11", mapOf("wpu" to bWpu("27.5", listOf(2, 2, 2, 3, 2, 2), slipSet = 4)), time = "12:30")
        val step = apply(OapFixture.def, s, b)
        val lock = rows(step, "prog.gripSlip.lock").single()
        lock.outcome shouldBe Outcome.APPLIED
        val bw = rows(step, "prog.B.wpu").single()
        bw.outcome shouldBe Outcome.SUPPRESSED_LOCK
        bw.suppressedBy shouldBe "prog.gripSlip.lock"
        (step.state.vars.getValue("wpuB") as QV).v.compareTo(BigDecimal("27.5")) shouldBe 0
        step.notifications.map { it.msgKey } shouldContainExactly listOf("grip_not_strength")
    }

    @Test
    fun `B progression without a slip`() {
        val s = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false))
        val b = session("B", "2026-10-11", mapOf("wpu" to bWpu("27.5", listOf(2, 2, 2, 3, 2, 2))), time = "12:30")
        val step = apply(OapFixture.def, s, b)
        rows(step, "prog.B.wpu").single().outcome shouldBe Outcome.APPLIED
        (step.state.vars.getValue("wpuB") as QV).v.compareTo(BigDecimal("30")) shouldBe 0
        rows(step, "prog.B.wpu").single().explanation shouldBe "All 6 x 2 with at least 2 reps in reserve: +2.5 kg next week."
    }

    @Test
    fun `GF-CE-17 the first writer wins`() {
        val def = withRules(
            """{"id": "t.R1", "tag": "E", "stage": "structural", "prio": 50, "on": ["day.tick"], "if": "true", "then": [{"set": "s.cEasyStreak", "to": "1"}], "explain": {"en": "R1"}}""",
            """{"id": "t.R2", "tag": "E", "stage": "structural", "prio": 40, "on": ["day.tick"], "if": "true", "then": [{"set": "s.cEasyStreak", "to": "2"}], "explain": {"en": "R2"}}""",
        )
        val s = OapFixture.start(definition = def)
        val step = apply(def, s, OapFixture.event(EventTypes.DAY_TICK, "2026-10-08", "00:00", EventPayload.DayTick(java.time.LocalDate.parse("2026-10-08"))))
        rows(step, "t.R1").single().outcome shouldBe Outcome.APPLIED
        val r2 = rows(step, "t.R2").single()
        r2.outcome shouldBe Outcome.SUPPRESSED_FIRST_WRITER
        r2.suppressedBy shouldBe "t.R1"
        (step.state.vars.getValue("cEasyStreak") as QV).v.toInt() shouldBe 1
    }

    @Test
    fun `GF-CE-18 entering injury suppresses progression`() {
        val def = withRules(
            """{"id": "t.prog", "tag": "E", "stage": "progression", "prio": 50, "on": ["pain.reported"], "if": "true", "then": [{"adjust": "s.cEasyStreak", "by": "1"}], "explain": {"en": "p"}}""",
        )
        val s = OapFixture.start(definition = def)
        val pain = OapFixture.event(
            EventTypes.PAIN, "2026-10-08", "20:00",
            EventPayload.Pain("elbow_medial", "R", listOf("medial_elbow_ache_after"), "mild", 2),
        )
        val step = apply(def, s, pain)
        step.state.mode shouldBe "injury"
        val p = rows(step, "t.prog").single()
        p.outcome shouldBe Outcome.SUPPRESSED_INJURY
        p.suppressedBy shouldBe "safety.elbow.stop"
        step.notifications.map { it.kind to it.msgKey } shouldContainExactly listOf("stop" to "elbow_protocol")
    }

    private val frozenRule =
        """{"id": "t.frozen", "tag": "E", "stage": "progression", "prio": 10, "on": ["session.completed"], "if": "sess.slot == 'C'", "then": [{"set": "s.sthS", "to": "8s"}], "explain": {"en": "f"}}"""

    @Test
    fun `GF-CE-19 frozen modes keep protective rules only`() {
        val def = withRules(frozenRule)
        val c = session("C", "2026-10-07", mapOf("oaa" to oaa("50", listOf(5 to 5, 4 to 3), targetReps = 5, plannedPairs = 2)), time = "21:15")
        val deload = OapFixture.start(OapFixture.import(mode = "deload"), definition = def)
        val step = apply(def, deload, c)
        rows(step, "prog.C.pin.hard").single().outcome shouldBe Outcome.APPLIED
        (step.state.vars.getValue("pinC") as QV).v.compareTo(BigDecimal(55)) shouldBe 0
        rows(step, "t.frozen").single().let {
            it.outcome shouldBe Outcome.SUPPRESSED_LOCK
            it.suppressedBy shouldBe "mode:deload"
        }
        val pending = OapFixture.start(OapFixture.import(mode = "test_pending"), definition = def, except = mapOf("P19" to false))
        val step2 = apply(def, pending, c)
        rows(step2, "t.frozen").single().suppressedBy shouldBe "mode:test_pending"
        (step2.state.vars.getValue("pinC") as QV).v.compareTo(BigDecimal(55)) shouldBe 0
    }

    @Test
    fun `GF-CE-20 proposal gating of the A pin rules`() {
        val a = session(
            "A", "2026-10-08",
            mapOf("oaa" to oaa("40", List(5) { 3 to 3 }, targetReps = 3)), time = "21:15",
        )
        val normal = OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false)
        val approved = apply(OapFixture.def, OapFixture.start(normal, approve = true), a)
        rows(approved, "prog.A.pin.gated").single().outcome shouldBe Outcome.APPLIED
        rows(approved, "prog.A.pin.weekly") shouldBe emptyList()
        (approved.state.vars.getValue("pinA") as QV).v.compareTo(BigDecimal(35)) shouldBe 0
        val declined = apply(OapFixture.def, OapFixture.start(normal, approve = false), a)
        rows(declined, "prog.A.pin.weekly").single().outcome shouldBe Outcome.APPLIED
        rows(declined, "prog.A.pin.gated") shouldBe emptyList()
        val undecided = apply(OapFixture.def, EnrollmentFold.fold(OapFixture.def, params, listOf(OapFixture.created(normal))).state, a)
        rows(undecided, "prog.A.pin.weekly").single().ruleDecision shouldBe "declined"
    }
}
