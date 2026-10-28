package dev.fitnessapp.algo.core.safety

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.testing.TestCatalog
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ALGORITHMS_PLANNING 2.7. */
class SafetyTest {
    private val params = TestParams.defaults()
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun at(date: String, time: String = "18:00"): Instant = Week.atLocal(LocalDate.parse(date), LocalTime.parse(time), zone)

    private fun eval(
        events: List<PainEvent>,
        now: Instant,
        clears: List<PainClear> = emptyList(),
        modes: List<ChallengeModeEffect> = emptyList(),
    ) = Safety.evaluate(SafetyInput(events, clears, modes, TestCatalog.regionJoints, now, zone), params)

    private fun pain(id: String, ts: Instant, region: String, chip: String) =
        PainEvent(id, ts, "pain_sheet", region, "R", Safety.severityOfChip(chip, params))

    @Test
    fun `elbowTraceFromReadinessCheck`() {
        val e = requireNotNull(Safety.elbowCheckEvent("e1", at("2026-10-20"), 1, params))
        val s = eval(listOf(e), at("2026-10-21"))
        s.flagMap shouldBe mapOf("elbow_lateral" to 1, "elbow_medial" to 1)
        s.jointFlags.first().until shouldBe LocalDate.parse("2026-10-28").atStartOfDay(zone).toInstant()
        s.forbidSessionTags shouldContainAll listOf("elbow_flexor_heavy", "grip_heavy")
        Safety.elbowCheckEvent("e0", at("2026-10-20"), 0, params) shouldBe null
        Safety.elbowCheckEvent("e3", at("2026-10-20"), 3, params)!!.severity shouldBe 2
    }

    @Test
    fun `sharpPainNotice`() {
        val s = eval(listOf(pain("p1", at("2026-10-20"), "elbow_medial", "sharp")), at("2026-10-21"))
        s.flagMap shouldBe mapOf("elbow_medial" to 3)
        s.notices.map { it.code } shouldBe listOf("sharp_pain")
        s.forbidSessionTags shouldContainAll listOf("pull_heavy")
    }

    @Test
    fun `escalation`() {
        val s = eval(
            listOf(pain("w1", at("2026-10-10"), "wrist", "mild"), pain("w2", at("2026-10-15"), "wrist", "mild"), pain("w3", at("2026-10-20"), "wrist", "mild")),
            at("2026-10-21"),
        )
        s.flagMap shouldBe mapOf("wrist" to 2)
        s.forbidSessionTags shouldBe listOf("grip_heavy")
    }

    @Test
    fun `clearRequiresPainFreeDays`() {
        val p = pain("k1", at("2026-10-20"), "knee", "moderate")
        eval(listOf(p), at("2026-10-22"), listOf(PainClear("c1", at("2026-10-21"), "knee"))).flagMap shouldBe mapOf("knee" to 2)
        eval(listOf(p), at("2026-10-24"), listOf(PainClear("c3", at("2026-10-23"), "knee"))).flagMap shouldBe emptyMap()
        // A mild flag clears at once.
        eval(listOf(pain("k2", at("2026-10-20"), "knee", "mild")), at("2026-10-22"), listOf(PainClear("c", at("2026-10-21"), "knee"))).flagMap shouldBe emptyMap()
    }

    @Test
    fun `challengeModeMerged`() {
        val injury = ChallengeModeEffect(
            "enr", "injury", at("2026-10-08"), listOf("grip_heavy", "elbow_flexor_heavy"), listOf("unilateral_pull", "eccentric"), mapOf("elbow_medial" to 3),
        )
        val s = eval(listOf(pain("m", at("2026-10-20"), "elbow_medial", "mild")), at("2026-10-21"), modes = listOf(injury))
        s.forbidSessionTags shouldContainAll listOf("grip_heavy", "elbow_flexor_heavy")
        s.forbidExerciseTags shouldBe listOf("eccentric", "unilateral_pull")
        s.flagMap.getValue("elbow_medial") shouldBe 3
        s.jointFlags.single().until shouldBe null
        s.notices.map { it.code } shouldBe listOf("challenge_mode")
    }

    @Test
    fun `dstHold`() {
        val s = eval(listOf(pain("d", at("2026-10-20", "18:00"), "elbow_medial", "mild")), at("2026-10-27", "23:30"))
        // End of 2026-10-27 local is 2026-10-28 00:00 CET = 23:00Z: seven local days across the DST change.
        s.jointFlags.single().until shouldBe Instant.parse("2026-10-27T23:00:00Z")
        eval(listOf(pain("d", at("2026-10-20"), "elbow_medial", "mild")), Instant.parse("2026-10-27T23:00:00Z")).jointFlags shouldBe emptyList()
    }

    @Test
    fun `fingerprint follows flags, tags and modes only`() {
        val a = eval(listOf(pain("a", at("2026-10-20"), "knee", "mild")), at("2026-10-21"))
        val b = eval(listOf(pain("b", at("2026-10-20", "19:00"), "knee", "mild")), at("2026-10-22"))
        a.fingerprint shouldBe b.fingerprint
        eval(emptyList(), at("2026-10-21")).fingerprint shouldNotBe a.fingerprint
        eval(listOf(pain("n", at("2026-10-20"), "neck", "sharp")), at("2026-10-21")).let {
            it.jointFlags shouldBe emptyList()
            it.notices.map { n -> n.code } shouldBe listOf("pain_no_joint")
            it.forbidSessionTags shouldNotContain "pull_heavy"
        }
    }
}
