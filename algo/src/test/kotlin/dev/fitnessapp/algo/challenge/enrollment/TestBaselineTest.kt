package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.OapFixture
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Values
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.TreeMap

/**
 * D41: `pt` is the newest gate-valid value per measurement. The D40 import counts three tests for the gates; the newest
 * (29 September) holds only the one-arm hang, so the bodyweight and the 3RM must still come from the older tests.
 */
class TestBaselineTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val tests = listOf(
        PastTest(
            d("2026-08-30"),
            TreeMap(mapOf("bw" to Values.mass("71.0"), "wpu_3rm_added" to Values.mass("26"), "bw_max_reps" to Values.int(18), "oa_hang_s" to SideV(Values.duration("3"), Values.duration("8")))),
            validForGate = true, notComparable = setOf("oa_hang_s"), protocolCodes = TreeMap(mapOf("oa_hang_s" to "oa_hang_bar_supinated_release_v0")),
        ),
        PastTest(d("2026-09-19"), TreeMap(mapOf("bw" to Values.mass("70.7"), "wpu_3rm_added" to Values.mass("32.5"))), validForGate = true),
        PastTest(d("2026-09-29"), TreeMap(mapOf("oa_hang_s" to SideV(Values.duration("15"), Values.duration("20")))), validForGate = true),
    )

    private fun state(): EnrollmentState {
        val imp = OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false).copy(pastTests = tests)
        return OapFixture.start(imp)
    }

    @Test
    fun `each measurement comes from the newest test that has it`() {
        val s = state()
        s.lastValidTest!!.date shouldBe d("2026-09-29")
        s.testBaseline.mapValues { it.value.date } shouldBe sortedMapOf(
            "bw" to d("2026-09-19"), "bw_max_reps" to d("2026-08-30"), "oa_hang_s" to d("2026-09-29"), "wpu_3rm_added" to d("2026-09-19"),
        )
        s.testBaseline.getValue("bw").value shouldBe Values.mass("70.7")
        s.testBaseline.getValue("oa_hang_s").value shouldBe SideV(Values.duration("15"), Values.duration("20"))
    }

    @Test
    fun `a value of another protocol never enters the baseline`() {
        val only = OapFixture.start(OapFixture.import(mode = "normal", blockWeek = 2, plannedTest = null, needsCleanSession = false).copy(pastTests = tests.take(1)))
        only.testBaseline.keys shouldBe sortedSetOf("bw", "bw_max_reps", "wpu_3rm_added")
    }

    @Test
    fun `a newer test replaces only what it measured`() {
        val after = EnrollmentFold.withBaseline(state().testBaseline, d("2026-11-29"), mapOf("bw" to Values.mass("69.5")), emptyMap(), emptySet())
        after.getValue("bw").let { it.value to it.date } shouldBe (Values.mass("69.5") to d("2026-11-29"))
        after.getValue("wpu_3rm_added").date shouldBe d("2026-09-19")
        // An older value never overwrites a newer one.
        EnrollmentFold.withBaseline(after, d("2026-08-01"), mapOf("bw" to Values.mass("72")), emptyMap(), emptySet()).getValue("bw").date shouldBe d("2026-11-29")
    }
}
