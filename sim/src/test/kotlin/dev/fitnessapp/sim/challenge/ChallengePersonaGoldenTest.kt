package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.eca.Outcome
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.LocalDate

/**
 * GF-CE-33: every persona of CHALLENGE_ENGINE 2.6, with all proposals approved and all declined, runs 26
 * weeks twice with byte-identical output, holds the global invariants, and matches its committed snapshot in
 * `fixtures/golden/challenge/` (`-PupdateGoldens` rewrites the snapshots; the diff is the review).
 */
class ChallengePersonaGoldenTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })
    private val update = System.getProperty("fitness.updateGoldens") == "true"
    private val dir = File(root, "fixtures/golden/challenge")
    private val def = SimResources.oap

    private fun run(p: Persona, approve: Boolean): SimResult =
        ChallengeSimulator(def, SimResources.params(), SimResources.catalog, p, approve).run()

    private val cache = sortedMapOf<String, SimResult>()

    private fun result(p: Persona, approve: Boolean): SimResult = cache.getOrPut("${p.id}.$approve") { run(p, approve) }

    @TestFactory
    fun `persona snapshots`(): List<DynamicTest> = Persona.all().flatMap { p ->
        listOf(true, false).map { approve ->
            val tag = if (approve) "approved" else "declined"
            DynamicTest.dynamicTest("${p.id}.$tag") {
                val a = result(p, approve)
                val b = run(p, approve)
                val params = SimResources.params()
                val text = Trajectory.text(def, params, a)
                Trajectory.text(def, params, b) shouldBe text
                a.violations.shouldBeEmpty()
                val file = File(dir, "${p.id}.$tag.json")
                if (update || !file.exists()) {
                    dir.mkdirs()
                    file.writeText(text, Charsets.UTF_8)
                } else {
                    text shouldBe file.readText(Charsets.UTF_8).replace("\r\n", "\n")
                }
            }
        }
    }

    private fun fired(r: SimResult, rule: String, outcome: Outcome = Outcome.APPLIED) = r.audits.count { it.ruleId == rule && it.outcome == outcome }

    @Test
    fun `all_success passes the phase-1 gate`() {
        val r = result(AllSuccess, true)
        r.weeks.map { it.state.phase } shouldContain 2
        fired(r, "struct.phase.gate") shouldBe 1
        r.finalState.vars.isNotEmpty() shouldBe true
    }

    @Test
    fun `stall reaches stage 2 once, inserts one extra deload and reverts after progress`() {
        val r = result(Stall, true)
        val stages = r.weeks.map { (it.state.vars["stallStage"] as dev.fitnessapp.algo.challenge.fitexpr.QV).v.toInt() }
        stages shouldContain 1
        stages shouldContain 2
        r.weeks.count { it.state.mode == "extra_deload" } shouldBe 1
        r.weeks.map { (it.state.vars["bVariant"] as dev.fitnessapp.algo.challenge.fitexpr.StrV).s } shouldContain "ladder123"
        fired(r, "struct.stall.revert") shouldBe 1
        (r.finalState.vars["bVariant"] as dev.fitnessapp.algo.challenge.fitexpr.StrV).s shouldBe "6x2"
        // Declined: the ladders stay once switched on (no P24).
        val d = result(Stall, false)
        fired(d, "struct.stall.revert") shouldBe 0
    }

    @Test
    fun `grip slips block the weighted increases`() {
        val r = result(GripSlips, true)
        fired(r, "prog.gripSlip.lock") shouldBeGreaterThan 0
        r.audits.count { it.ruleId.startsWith("prog.") && it.outcome == Outcome.SUPPRESSED_LOCK && it.suppressedBy == "prog.gripSlip.lock" } shouldBeGreaterThan 0
    }

    private fun injuryDays(r: SimResult): Int {
        val start = r.events.firstOrNull { e -> e.type == EventTypes.PAIN }?.localDate ?: return 0
        val exit = r.audits.firstOrNull { it.ruleId.startsWith("safety.injury.exit") && it.outcome == Outcome.APPLIED }
        val exitDate = exit?.let { a -> r.events.first { it.id == a.eventId }.localDate } ?: return Int.MAX_VALUE
        return java.time.temporal.ChronoUnit.DAYS.between(start, exitDate).toInt()
    }

    @Test
    fun `the elbow protocol lasts 14 days and P14 extends it while pain persists`() {
        injuryDays(result(ElbowPainWeek7, true)) shouldBe 14
        injuryDays(result(ElbowPainWeek7, false)) shouldBe 14
        val long = result(ElbowPainWeek7Long, true)
        fired(long, "safety.injury.extend") shouldBe 1
        injuryDays(long) shouldBe 21
        fired(result(ElbowPainWeek7Long, false), "safety.injury.extend") shouldBe 0
        // Unilateral and eccentric work is absent while injured (the sessions inside the protocol).
        for (r in listOf(long, result(ElbowPainWeek7, true))) {
            val start = r.events.first { it.type == EventTypes.PAIN }.localDate
            val end = start.plusDays(injuryDays(r).toLong())
            val inside = r.events.mapNotNull { (it.payload as? EventPayload.Session)?.session }
                .filter { it.localDate.isAfter(start) && it.localDate.isBefore(end) }
            inside.size shouldBeGreaterThan 0
            for (s in inside) for (key in listOf("oaa", "cal", "neg", "top", "h90", "sth", "hang", "scap")) {
                s.items[key]?.let { it.status shouldBe "excluded" }
            }
        }
    }

    @Test
    fun `missed weeks repeat and pauses follow P15`() {
        val a = result(MissedWeeks, true)
        val notes = a.weeks.flatMap { it.notifications }.map { it.substringAfter(' ') }
        notes shouldContain "info:missed_week_no_makeup"
        notes shouldContain "info:resume_repeat"
        notes shouldContain "info:resume_restart"
        val d = result(MissedWeeks, false)
        d.weeks.flatMap { it.notifications }.map { it.substringAfter(' ') } shouldContain "info:resume_same_week"
    }

    @Test
    fun `a busy machine never moves the A pin`() {
        val r = result(MachineBusy, true)
        val busy = r.events.filter { e ->
            val s = (e.payload as? EventPayload.Session)?.session ?: return@filter false
            s.slot == "A" && s.items["oaa"]?.progressionExcluded == true
        }
        busy.size shouldBeGreaterThanOrEqual 5
        for (e in busy) {
            r.audits.filter { it.eventId == e.id && it.ruleId.startsWith("prog.A.pin") }.map { it.outcome } shouldNotContain Outcome.APPLIED
        }
    }

    @Test
    fun `twelve days away across DST synthesize one tick per date`() {
        val r = result(DstAndLateTicks, true)
        r.events.count { it.type == EventTypes.DAY_TICK && it.localDate == LocalDate.parse("2026-10-25") } shouldBe 1
        r.violations.shouldBeEmpty()
    }
}
